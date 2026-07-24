#!/usr/bin/env python3
"""Evaluate frozen CoreMark and Dhrystone natural-run manifests."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
import tempfile
from pathlib import Path
from typing import Any


SCHEMA = "linxcore.dual_natural_benchmark_ipc.v1"
NATURAL_SCHEMA = "linxcore.benchmark_autonomous_natural.v1"
DEFAULT_EXPECTED_NATURAL_RUNNER_SHA256 = (
    "91d84b3b300209bf5f384f185eb1ace4b5bfb81b3a8107fc8a6678e3a86bc1e3"
)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def load_json(path: Path) -> dict[str, Any]:
    def reject_duplicates(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
        result: dict[str, Any] = {}
        for key, value in pairs:
            if key in result:
                raise ValueError(f"duplicate JSON key {key!r} in {path}")
            result[key] = value
        return result

    value = json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=reject_duplicates)
    if not isinstance(value, dict):
        raise ValueError(f"expected JSON object in {path}")
    return value


def add_error(errors: list[str], condition: bool, message: str) -> None:
    if not condition:
        errors.append(message)


def evaluate_workload(
    *,
    workload: str,
    manifest_path: Path,
    elf_path: Path,
    expected_elf_sha256: str,
    expected_reset_sp: int,
    target_ipc: float,
) -> dict[str, Any]:
    errors: list[str] = []
    manifest: dict[str, Any] = {}

    if not manifest_path.is_file():
        errors.append(f"{workload}: missing natural manifest: {manifest_path}")
    else:
        try:
            manifest = load_json(manifest_path)
        except (OSError, ValueError, json.JSONDecodeError) as exc:
            errors.append(f"{workload}: invalid natural manifest: {exc}")

    actual_elf_sha256 = ""
    if not elf_path.is_file():
        errors.append(f"{workload}: missing ELF: {elf_path}")
    else:
        actual_elf_sha256 = sha256(elf_path)
        add_error(
            errors,
            actual_elf_sha256 == expected_elf_sha256,
            f"{workload}: ELF SHA-256 drift: got {actual_elf_sha256}, expected {expected_elf_sha256}",
        )

    cycles = manifest.get("cycles")
    commits = manifest.get("commits")
    cycles_valid = isinstance(cycles, int) and not isinstance(cycles, bool) and cycles > 0
    commits_valid = isinstance(commits, int) and not isinstance(commits, bool) and commits > 0
    ipc = commits / cycles if cycles_valid and commits_valid else None

    add_error(errors, manifest.get("schema") == NATURAL_SCHEMA, f"{workload}: wrong manifest schema")
    add_error(
        errors,
        manifest.get("terminal_status") == "finisher_pass",
        f"{workload}: terminal_status is {manifest.get('terminal_status')!r}, expected 'finisher_pass'",
    )
    add_error(errors, manifest.get("finisher_pass") is True, f"{workload}: finisher_pass is not true")
    add_error(
        errors,
        manifest.get("reset_sp") == expected_reset_sp,
        f"{workload}: reset_sp is {manifest.get('reset_sp')!r}, expected {expected_reset_sp}",
    )
    add_error(errors, cycles_valid, f"{workload}: cycles must be a positive integer")
    add_error(errors, commits_valid, f"{workload}: commits must be a positive integer")
    add_error(
        errors,
        ipc is not None and math.isfinite(ipc) and ipc >= target_ipc,
        f"{workload}: IPC is {ipc!r}, expected at least {target_ipc}",
    )

    return {
        "workload": workload,
        "status": "pass" if not errors else "fail",
        "elf": {
            "path": str(elf_path.resolve()),
            "sha256": actual_elf_sha256,
            "expected_sha256": expected_elf_sha256,
        },
        "natural_manifest": {
            "path": str(manifest_path.resolve()),
            "sha256": sha256(manifest_path) if manifest_path.is_file() else "",
        },
        "terminal_status": manifest.get("terminal_status"),
        "finisher_pass": manifest.get("finisher_pass"),
        "cycles": cycles,
        "commits": commits,
        "ipc": ipc,
        "target_ipc": target_ipc,
        "reset_sp": manifest.get("reset_sp"),
        "errors": errors,
    }


def write_json_atomic(path: Path, payload: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, temporary_name = tempfile.mkstemp(prefix=f".{path.name}.", dir=path.parent)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as stream:
            json.dump(payload, stream, indent=2, sort_keys=True)
            stream.write("\n")
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary_name, path)
    except BaseException:
        try:
            os.unlink(temporary_name)
        except FileNotFoundError:
            pass
        raise


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--coremark-manifest", type=Path, required=True)
    parser.add_argument("--dhrystone-manifest", type=Path, required=True)
    parser.add_argument("--coremark-elf", type=Path, required=True)
    parser.add_argument("--dhrystone-elf", type=Path, required=True)
    parser.add_argument("--expected-coremark-sha256", required=True)
    parser.add_argument("--expected-dhrystone-sha256", required=True)
    parser.add_argument("--runner", type=Path, required=True)
    parser.add_argument("--expected-runner-sha256", default=DEFAULT_EXPECTED_NATURAL_RUNNER_SHA256)
    parser.add_argument("--target-ipc", type=float, default=1.90)
    parser.add_argument("--expected-reset-sp", type=lambda value: int(value, 0), default=0x7FEFFF0)
    parser.add_argument("--output", type=Path, required=True)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    if not math.isfinite(args.target_ipc) or args.target_ipc <= 0:
        raise SystemExit("--target-ipc must be a positive finite number")
    if not args.runner.is_file():
        raise SystemExit(f"runner does not exist: {args.runner}")
    runner_sha256 = sha256(args.runner)

    workloads = [
        evaluate_workload(
            workload="coremark",
            manifest_path=args.coremark_manifest,
            elf_path=args.coremark_elf,
            expected_elf_sha256=args.expected_coremark_sha256,
            expected_reset_sp=args.expected_reset_sp,
            target_ipc=args.target_ipc,
        ),
        evaluate_workload(
            workload="dhrystone",
            manifest_path=args.dhrystone_manifest,
            elf_path=args.dhrystone_elf,
            expected_elf_sha256=args.expected_dhrystone_sha256,
            expected_reset_sp=args.expected_reset_sp,
            target_ipc=args.target_ipc,
        ),
    ]
    errors = [error for workload in workloads for error in workload["errors"]]
    add_error(
        errors,
        runner_sha256 == args.expected_runner_sha256,
        f"runner SHA-256 drift: got {runner_sha256}, expected {args.expected_runner_sha256}",
    )
    payload = {
        "schema": SCHEMA,
        "status": "pass" if not errors else "fail",
        "target_ipc": args.target_ipc,
        "expected_reset_sp": args.expected_reset_sp,
        "runner": {
            "path": str(args.runner.resolve()),
            "sha256": runner_sha256,
            "expected_sha256": args.expected_runner_sha256,
        },
        "workloads": workloads,
        "errors": errors,
    }
    write_json_atomic(args.output, payload)
    print(f"dual-natural-benchmark-ipc-summary={args.output}")
    print(f"status={payload['status']}")
    for workload in workloads:
        print(
            f"{workload['workload']}: terminal={workload['terminal_status']} "
            f"commits={workload['commits']} cycles={workload['cycles']} ipc={workload['ipc']}"
        )
    for error in errors:
        print(f"error: {error}")
    return 0 if payload["status"] == "pass" else 1


if __name__ == "__main__":
    raise SystemExit(main())
