#!/usr/bin/env python3
"""Supervise one Chisel/SBT command with observable liveness diagnostics."""

from __future__ import annotations

import argparse
import dataclasses
import json
import os
from pathlib import Path
import signal
import stat
import subprocess
import sys
import time
from typing import Sequence


HEARTBEAT_PREFIX = "linx-chisel-heartbeat "
SUMMARY_PREFIX = "linx-chisel-summary "


@dataclasses.dataclass(frozen=True)
class ArtifactSnapshot:
    bytes: int
    latest_mtime_ns: int
    file_count: int
    largest: tuple[tuple[str, int], ...]


@dataclasses.dataclass(frozen=True)
class SupervisorConfig:
    heartbeat_seconds: int = 30
    stall_seconds: int = 600
    wall_seconds: int = 0
    low_cpu_percent: float = 1.0
    artifact_root: Path | None = None
    artifact_budget_bytes: int = 0
    log_path: Path | None = None
    selector: str = "all"
    jobs: int = 1


@dataclasses.dataclass(frozen=True)
class _ProcessSnapshot:
    count: int = 0
    cpu_percent: float = 0.0
    rss_bytes: int = 0
    most_active_command: str = ""


def parse_positive_int(name: str, text: str) -> int:
    try:
        value = int(text)
    except (TypeError, ValueError) as error:
        raise ValueError(f"{name} must be a positive integer") from error
    if value <= 0 or str(value) != str(text).strip().lstrip("+"):
        raise ValueError(f"{name} must be a positive integer")
    return value


def parse_non_negative_int(name: str, text: str) -> int:
    try:
        value = int(text)
    except (TypeError, ValueError) as error:
        raise ValueError(f"{name} must be a non-negative integer") from error
    if value < 0 or str(value) != str(text).strip().lstrip("+"):
        raise ValueError(f"{name} must be a non-negative integer")
    return value


def snapshot_artifacts(root: Path | None) -> ArtifactSnapshot:
    if root is None or not root.exists():
        return ArtifactSnapshot(0, 0, 0, ())

    total_bytes = 0
    latest_mtime_ns = 0
    files: list[tuple[str, int]] = []
    for directory, _, names in os.walk(root, followlinks=False):
        base = Path(directory)
        for name in names:
            path = base / name
            try:
                stat_result = path.stat(follow_symlinks=False)
            except (FileNotFoundError, PermissionError, OSError):
                continue
            if not stat.S_ISREG(stat_result.st_mode):
                continue
            relative = path.relative_to(root).as_posix()
            total_bytes += stat_result.st_size
            latest_mtime_ns = max(latest_mtime_ns, stat_result.st_mtime_ns)
            files.append((relative, stat_result.st_size))

    files.sort(key=lambda entry: (-entry[1], entry[0]))
    return ArtifactSnapshot(
        bytes=total_bytes,
        latest_mtime_ns=latest_mtime_ns,
        file_count=len(files),
        largest=tuple(files[:5]),
    )


def _snapshot_processes(root_pid: int) -> _ProcessSnapshot:
    try:
        result = subprocess.run(
            ["ps", "-axo", "pid=,ppid=,%cpu=,rss=,command="],
            check=False,
            capture_output=True,
            text=True,
        )
    except OSError:
        return _ProcessSnapshot()

    rows: dict[int, tuple[int, float, int, str]] = {}
    children: dict[int, list[int]] = {}
    for line in result.stdout.splitlines():
        fields = line.strip().split(None, 4)
        if len(fields) != 5:
            continue
        try:
            pid = int(fields[0])
            ppid = int(fields[1])
            cpu = float(fields[2])
            rss_kib = int(fields[3])
        except ValueError:
            continue
        rows[pid] = (ppid, cpu, rss_kib * 1024, fields[4])
        children.setdefault(ppid, []).append(pid)

    descendants: set[int] = set()
    pending = [root_pid]
    while pending:
        pid = pending.pop()
        if pid in descendants:
            continue
        descendants.add(pid)
        pending.extend(children.get(pid, ()))

    selected = [(pid, rows[pid]) for pid in descendants if pid in rows]
    if not selected:
        return _ProcessSnapshot()
    most_active = max(selected, key=lambda entry: (entry[1][1], entry[0]))
    return _ProcessSnapshot(
        count=len(selected),
        cpu_percent=sum(row[1] for _, row in selected),
        rss_bytes=sum(row[2] for _, row in selected),
        most_active_command=most_active[1][3],
    )


def _classify_phase(processes: _ProcessSnapshot, recent_output: str) -> str:
    command = processes.most_active_command.lower()
    output = recent_output.lower()
    combined = f"{command}\n{output}"
    if "verilator_bin" in command or "v3split" in combined:
        return "verilation"
    if any(token in command for token in ("clang++", "g++", "c++", " cc ")):
        return "cxx-build"
    if "make " in command and "verilated-sources" in command:
        return "cxx-build"
    if "simulation" in command and "verilator" not in command:
        return "simulation"
    if "firtool" in combined or "firrtl" in output:
        return "firrtl"
    if "elaborat" in output or "chiselsim" in combined:
        return "elaboration"
    if "scalac" in command or "compiling" in output:
        return "scala-compile"
    if "sbt" in combined or "java" in command:
        return "sbt"
    return "result"


def _artifact_payload(snapshot: ArtifactSnapshot) -> list[dict[str, object]]:
    return [{"path": path, "bytes": size} for path, size in snapshot.largest]


def _record(
    config: SupervisorConfig,
    start: float,
    now: float,
    last_output: float,
    last_artifact: float,
    artifacts: ArtifactSnapshot,
    processes: _ProcessSnapshot,
    peak_rss_bytes: int,
    recent_output: str,
) -> dict[str, object]:
    return {
        "selector": config.selector,
        "jobs": config.jobs,
        "phase": _classify_phase(processes, recent_output),
        "elapsed_seconds": round(now - start, 3),
        "output_quiet_seconds": round(now - last_output, 3),
        "artifact_quiet_seconds": round(now - last_artifact, 3),
        "artifact_bytes": artifacts.bytes,
        "artifact_files": artifacts.file_count,
        "process_count": processes.count,
        "cpu_percent": round(processes.cpu_percent, 2),
        "rss_bytes": processes.rss_bytes,
        "peak_rss_bytes": peak_rss_bytes,
        "most_active_command": processes.most_active_command,
    }


def _emit(prefix: str, payload: dict[str, object]) -> None:
    print(prefix + json.dumps(payload, sort_keys=True), flush=True)


def _signal_process_group(process: subprocess.Popen[bytes], signum: int) -> None:
    try:
        os.killpg(process.pid, signum)
    except ProcessLookupError:
        return


def _stop_process_group(
    process: subprocess.Popen[bytes], signum: int, grace_seconds: float = 5.0
) -> int:
    _signal_process_group(process, signum)
    deadline = time.monotonic() + grace_seconds
    while process.poll() is None and time.monotonic() < deadline:
        time.sleep(0.05)
    if process.poll() is None:
        _signal_process_group(process, signal.SIGKILL)
    try:
        return process.wait(timeout=1)
    except subprocess.TimeoutExpired:
        return -signal.SIGKILL


def supervise(config: SupervisorConfig, command: Sequence[str]) -> int:
    if not command:
        raise ValueError("supervised command must not be empty")
    if config.heartbeat_seconds <= 0:
        raise ValueError("heartbeat_seconds must be positive")
    if config.stall_seconds < 0 or config.wall_seconds < 0:
        raise ValueError("stall_seconds and wall_seconds must be non-negative")
    if config.low_cpu_percent < 0:
        raise ValueError("low_cpu_percent must be non-negative")
    if config.artifact_budget_bytes < 0:
        raise ValueError("artifact_budget_bytes must be non-negative")
    if config.jobs <= 0:
        raise ValueError("jobs must be positive")

    log_stream = None
    if config.log_path is not None:
        config.log_path.parent.mkdir(parents=True, exist_ok=True)
        log_stream = config.log_path.open("w", encoding="utf-8")

    process = subprocess.Popen(
        list(command),
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        start_new_session=True,
        bufsize=0,
    )
    assert process.stdout is not None
    os.set_blocking(process.stdout.fileno(), False)

    caught_signal = 0

    def catch_signal(signum: int, _frame: object) -> None:
        nonlocal caught_signal
        caught_signal = signum

    previous_handlers: dict[int, object] = {}
    for signum in (signal.SIGINT, signal.SIGTERM):
        previous_handlers[signum] = signal.getsignal(signum)
        signal.signal(signum, catch_signal)

    start = time.monotonic()
    now = start
    next_heartbeat = start + config.heartbeat_seconds
    probe_interval = min(1.0, max(0.2, config.heartbeat_seconds / 4.0))
    next_probe = start + probe_interval
    last_output = start
    last_artifact = start
    last_cpu_active = start
    artifacts = snapshot_artifacts(config.artifact_root)
    processes = _snapshot_processes(process.pid)
    peak_rss_bytes = processes.rss_bytes
    recent_output = ""
    reason = "child-exit"
    return_status: int | None = None

    try:
        while True:
            while True:
                try:
                    chunk = os.read(process.stdout.fileno(), 65536)
                except BlockingIOError:
                    break
                if not chunk:
                    break
                text = chunk.decode("utf-8", errors="replace")
                sys.stdout.write(text)
                sys.stdout.flush()
                if log_stream is not None:
                    log_stream.write(text)
                    log_stream.flush()
                recent_output = (recent_output + text)[-8192:]
                last_output = time.monotonic()

            now = time.monotonic()
            if now >= next_probe:
                current_artifacts = snapshot_artifacts(config.artifact_root)
                if (
                    current_artifacts.bytes != artifacts.bytes
                    or current_artifacts.latest_mtime_ns != artifacts.latest_mtime_ns
                    or current_artifacts.file_count != artifacts.file_count
                ):
                    last_artifact = now
                artifacts = current_artifacts

                current_processes = _snapshot_processes(process.pid)
                if current_processes.count:
                    processes = current_processes
                peak_rss_bytes = max(peak_rss_bytes, processes.rss_bytes)
                if processes.cpu_percent >= config.low_cpu_percent:
                    last_cpu_active = now
                while next_probe <= now:
                    next_probe += probe_interval

            if now >= next_heartbeat:
                _emit(
                    HEARTBEAT_PREFIX,
                    _record(
                        config,
                        start,
                        now,
                        last_output,
                        last_artifact,
                        artifacts,
                        processes,
                        peak_rss_bytes,
                        recent_output,
                    ),
                )
                while next_heartbeat <= now:
                    next_heartbeat += config.heartbeat_seconds

            child_status = process.poll()
            if child_status is not None:
                return_status = child_status
                reason = "child-exit"
                break

            if caught_signal:
                _stop_process_group(process, caught_signal)
                return_status = 128 + caught_signal
                reason = "signal"
                break

            if config.wall_seconds and now - start >= config.wall_seconds:
                _stop_process_group(process, signal.SIGTERM)
                return_status = 124
                reason = "wall-limit"
                break

            if config.stall_seconds and min(
                now - last_output,
                now - last_artifact,
                now - last_cpu_active,
            ) >= config.stall_seconds:
                _stop_process_group(process, signal.SIGTERM)
                return_status = 124
                reason = "idle-stall"
                break

            time.sleep(0.05)

        while True:
            try:
                chunk = os.read(process.stdout.fileno(), 65536)
            except BlockingIOError:
                break
            if not chunk:
                break
            text = chunk.decode("utf-8", errors="replace")
            sys.stdout.write(text)
            sys.stdout.flush()
            if log_stream is not None:
                log_stream.write(text)
                log_stream.flush()
            recent_output = (recent_output + text)[-8192:]
            last_output = time.monotonic()

        now = time.monotonic()
        artifacts = snapshot_artifacts(config.artifact_root)
        final_processes = _snapshot_processes(process.pid)
        if final_processes.count:
            processes = final_processes
        peak_rss_bytes = max(peak_rss_bytes, processes.rss_bytes)
        if (
            return_status == 0
            and config.artifact_budget_bytes
            and artifacts.bytes > config.artifact_budget_bytes
        ):
            return_status = 2
            reason = "artifact-budget"

        summary = _record(
            config,
            start,
            now,
            last_output,
            last_artifact,
            artifacts,
            processes,
            peak_rss_bytes,
            recent_output,
        )
        summary.update(
            {
                "reason": reason,
                "child_status": process.poll(),
                "return_status": return_status,
                "artifact_budget_bytes": config.artifact_budget_bytes,
                "largest_artifacts": _artifact_payload(artifacts),
            }
        )
        _emit(SUMMARY_PREFIX, summary)
        assert return_status is not None
        return return_status
    finally:
        if process.poll() is None:
            _stop_process_group(process, signal.SIGTERM)
        for signum, handler in previous_handlers.items():
            signal.signal(signum, handler)
        process.stdout.close()
        if log_stream is not None:
            log_stream.close()


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Supervise one Chisel test command with heartbeats")
    parser.add_argument("--heartbeat-seconds", default="30")
    parser.add_argument("--stall-seconds", default="600")
    parser.add_argument("--wall-seconds", default="0")
    parser.add_argument("--low-cpu-percent", default="1.0")
    parser.add_argument("--artifact-root", type=Path)
    parser.add_argument("--artifact-budget-bytes", default="0")
    parser.add_argument("--log", dest="log_path", type=Path)
    parser.add_argument("--selector", default="all")
    parser.add_argument("--jobs", default="1")
    parser.add_argument("command", nargs=argparse.REMAINDER)
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    parser = _parser()
    args = parser.parse_args(argv)
    command = list(args.command)
    if command and command[0] == "--":
        command.pop(0)
    if not command:
        parser.error("a command is required after --")
    try:
        heartbeat_seconds = parse_positive_int(
            "heartbeat seconds", args.heartbeat_seconds)
        stall_seconds = parse_non_negative_int("stall seconds", args.stall_seconds)
        wall_seconds = parse_non_negative_int("wall seconds", args.wall_seconds)
        artifact_budget_bytes = parse_non_negative_int(
            "artifact budget bytes", args.artifact_budget_bytes)
        jobs = parse_positive_int("jobs", args.jobs)
        low_cpu_percent = float(args.low_cpu_percent)
        if low_cpu_percent < 0:
            raise ValueError("low CPU percent must be non-negative")
    except ValueError as error:
        parser.error(str(error))

    return supervise(
        SupervisorConfig(
            heartbeat_seconds=heartbeat_seconds,
            stall_seconds=stall_seconds,
            wall_seconds=wall_seconds,
            low_cpu_percent=low_cpu_percent,
            artifact_root=args.artifact_root,
            artifact_budget_bytes=artifact_budget_bytes,
            log_path=args.log_path,
            selector=args.selector,
            jobs=jobs,
        ),
        command,
    )


if __name__ == "__main__":
    raise SystemExit(main())
