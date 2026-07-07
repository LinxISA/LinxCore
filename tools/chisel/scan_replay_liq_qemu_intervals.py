#!/usr/bin/env python3
"""Scan skipped QEMU intervals for replay-LIQ candidate windows."""

from __future__ import annotations

import argparse
import json
import os
import signal
import subprocess
import sys
from pathlib import Path
from typing import Any

from find_replay_liq_qemu_candidates import (
    find_candidates,
    load_events,
    pc_histogram,
    write_summary,
)


ROOT_DIR = Path(__file__).resolve().parents[2]
WRAPPER = ROOT_DIR / "tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh"


def parse_int_list(value: str) -> list[int]:
    out: list[int] = []
    for item in value.split(","):
        item = item.strip()
        if not item:
            continue
        parsed = int(item, 0)
        if parsed < 0:
            raise argparse.ArgumentTypeError("skip rows must be non-negative")
        out.append(parsed)
    if not out:
        raise argparse.ArgumentTypeError("at least one skip row is required")
    return out


def count_rows(path: Path) -> int:
    with path.open("r", encoding="utf-8") as f:
        return sum(1 for line in f if line.strip())


def default_qemu_args(elf: Path) -> list[str]:
    return [
        "-nographic",
        "-monitor",
        "none",
        "-machine",
        "virt",
        "-m",
        "1280M",
        "-kernel",
        str(elf),
    ]


def interval_name(skip_rows: int, capture_rows: int) -> str:
    return f"skip{skip_rows}-rows{capture_rows}"


def run_interval(args: argparse.Namespace, skip_rows: int, qemu_args: list[str]) -> dict[str, Any]:
    interval_dir = args.build_dir / interval_name(skip_rows, args.capture_rows)
    trace_dir = interval_dir / "traces"
    report_dir = interval_dir / "report"
    report_dir.mkdir(parents=True, exist_ok=True)

    cmd = [
        "bash",
        str(WRAPPER),
        "--build-dir",
        str(interval_dir),
        "--elf",
        str(args.elf),
        "--expected-rows",
        "0",
        "--capture-rows",
        str(args.capture_rows),
        "--qemu-skip-rows",
        str(skip_rows),
        "--allow-block-markers",
        "--allow-block-loop-reentry",
        "--qemu-raw-only",
        "--max-seconds",
        str(args.max_seconds),
    ]
    if args.qemu_bin is not None:
        cmd += ["--qemu-bin", str(args.qemu_bin)]
    cmd += ["--"] + qemu_args

    timed_out = False
    stdout = ""
    stderr = ""
    process = subprocess.Popen(
        cmd,
        cwd=ROOT_DIR,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        start_new_session=True,
    )
    try:
        stdout, stderr = process.communicate(timeout=args.wrapper_timeout_seconds)
    except subprocess.TimeoutExpired:
        timed_out = True
        os.killpg(process.pid, signal.SIGTERM)
        try:
            stdout, stderr = process.communicate(timeout=5)
        except subprocess.TimeoutExpired:
            os.killpg(process.pid, signal.SIGKILL)
            stdout, stderr = process.communicate()
    (report_dir / "scan-wrapper.stdout.txt").write_text(stdout, encoding="utf-8")
    (report_dir / "scan-wrapper.stderr.txt").write_text(stderr, encoding="utf-8")

    raw_trace = trace_dir / "qemu.live.raw.jsonl"
    raw_rows = count_rows(raw_trace) if raw_trace.exists() else 0
    capture_complete = raw_rows >= args.capture_rows
    interval: dict[str, Any] = {
        "skip_rows": skip_rows,
        "capture_rows": args.capture_rows,
        "build_dir": str(interval_dir),
        "raw_trace": str(raw_trace),
        "wrapper_returncode": process.returncode,
        "wrapper_timed_out": timed_out,
        "capture_complete": capture_complete,
    }
    if process.returncode != 0 and not capture_complete:
        interval["error"] = "wrapper failed"
        return interval
    if not raw_trace.exists():
        interval["error"] = "wrapper did not produce qemu.live.raw.jsonl"
        return interval

    events = load_events(raw_trace)
    candidates = find_candidates(
        events,
        lookback_rows=args.lookback_rows,
        same_line=not args.exact_overlap_only,
        min_second_row=0,
        max_second_row=-1,
        dedupe_pairs=not args.no_dedupe_pairs,
    )
    candidate_summary = write_summary(events, candidates, args.top)
    candidate_path = report_dir / "replay_liq_qemu_candidates.json"
    candidate_path.write_text(json.dumps(candidate_summary, indent=2, sort_keys=True) + "\n", encoding="utf-8")

    interval.update(
        {
            "raw_rows": count_rows(raw_trace),
            "event_count": candidate_summary["event_count"],
            "store_count": candidate_summary["store_count"],
            "load_count": candidate_summary["load_count"],
            "candidate_count": candidate_summary["candidate_count"],
            "candidate_report": str(candidate_path),
            "top_candidates": candidate_summary["top_candidates"][: args.top],
            "memory_pc_histogram": pc_histogram(events)[: args.top],
        }
    )
    return interval


def write_scan_summary(args: argparse.Namespace, intervals: list[dict[str, Any]]) -> dict[str, Any]:
    summary = {
        "schema": "linxcore.replay_liq_qemu_interval_scan.v1",
        "claim_boundary": (
            "Skipped raw QEMU interval scans are candidate-selection hints only; "
            "replay-LIQ evidence still requires generated-RTL sideband counters "
            "and a passing QEMU/DUT comparator manifest from an unskipped state."
        ),
        "input": {
            "elf": str(args.elf),
            "build_dir": str(args.build_dir),
            "skips": args.skips,
            "capture_rows": args.capture_rows,
            "lookback_rows": args.lookback_rows,
            "top": args.top,
        },
        "intervals": intervals,
    }
    args.build_dir.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(summary, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return summary


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--elf", type=Path, required=True, help="direct-boot ELF to run under QEMU")
    parser.add_argument(
        "--build-dir",
        type=Path,
        default=ROOT_DIR / "generated/replay-liq-qemu-interval-scan",
        help="scan output root",
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=None,
        help="scan summary JSON path; default is <build-dir>/report/interval_scan_summary.json",
    )
    parser.add_argument(
        "--skips",
        type=parse_int_list,
        default=parse_int_list("4096,16384,65536,262144"),
        help="comma-separated filtered-row skip offsets",
    )
    parser.add_argument("--capture-rows", type=int, default=2048, help="raw rows to capture per interval")
    parser.add_argument("--max-seconds", type=int, default=60, help="QEMU watchdog per interval")
    parser.add_argument(
        "--wrapper-timeout-seconds",
        type=int,
        default=None,
        help="hard scanner-side timeout per wrapper run; default is max(2*--max-seconds, --max-seconds+30)",
    )
    parser.add_argument("--qemu-bin", type=Path, default=None, help="optional QEMU binary override")
    parser.add_argument("--lookback-rows", type=int, default=2048, help="locator lookback rows")
    parser.add_argument("--top", type=int, default=20, help="top candidates/histogram rows per interval")
    parser.add_argument("--exact-overlap-only", action="store_true", help="drop same-line-only locator candidates")
    parser.add_argument("--no-dedupe-pairs", action="store_true", help="keep repeated dynamic candidate instances")
    parser.add_argument("--stop-on-load", action="store_true", help="stop scanning after the first interval with any loads")
    parser.add_argument("--stop-on-candidate", action="store_true", help="stop scanning after the first interval with candidates")
    parser.add_argument("qemu_args", nargs=argparse.REMAINDER, help="optional QEMU args after --")
    args = parser.parse_args(argv)

    if args.capture_rows <= 0:
        raise SystemExit("error: --capture-rows must be positive")
    if args.max_seconds <= 0:
        raise SystemExit("error: --max-seconds must be positive")
    if args.wrapper_timeout_seconds is None:
        args.wrapper_timeout_seconds = max(args.max_seconds * 2, args.max_seconds + 30)
    if args.wrapper_timeout_seconds <= 0:
        raise SystemExit("error: --wrapper-timeout-seconds must be positive")
    if args.lookback_rows <= 0:
        raise SystemExit("error: --lookback-rows must be positive")
    if args.top <= 0:
        raise SystemExit("error: --top must be positive")
    if args.output is None:
        args.output = args.build_dir / "report/interval_scan_summary.json"
    if args.output.parent != Path("."):
        args.output.parent.mkdir(parents=True, exist_ok=True)
    return args


def main(argv: list[str]) -> int:
    args = parse_args(argv)
    qemu_args = args.qemu_args
    if qemu_args and qemu_args[0] == "--":
        qemu_args = qemu_args[1:]
    if not qemu_args:
        qemu_args = default_qemu_args(args.elf)

    intervals: list[dict[str, Any]] = []
    for skip_rows in args.skips:
        interval = run_interval(args, skip_rows, qemu_args)
        intervals.append(interval)
        print(
            "interval-scan "
            f"skip={skip_rows} rc={interval['wrapper_returncode']} "
            f"timeout={int(bool(interval.get('wrapper_timed_out', False)))} "
            f"rows={interval.get('raw_rows', 0)} events={interval.get('event_count', 0)} "
            f"stores={interval.get('store_count', 0)} loads={interval.get('load_count', 0)} "
            f"candidates={interval.get('candidate_count', 0)}"
        )
        if interval.get("error"):
            break
        if args.stop_on_candidate and int(interval.get("candidate_count", 0)) > 0:
            break
        if args.stop_on_load and int(interval.get("load_count", 0)) > 0:
            break

    summary = write_scan_summary(args, intervals)
    print(f"interval-scan-summary={args.output}")
    return 1 if any(item.get("error") for item in summary["intervals"]) else 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
