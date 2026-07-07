#!/usr/bin/env python3
"""Search CoreMark replay-LIQ candidates for legal PC-filter preflights.

The search is intentionally QEMU-only. A candidate can authorize Verilator time
only after the wrapper produces a legal reduced preview and the exact expected
memory-PC guards pass.
"""

from __future__ import annotations

import argparse
import copy
import json
import os
import signal
import subprocess
import sys
from pathlib import Path
from typing import Any


ROOT_DIR = Path(__file__).resolve().parents[2]
WRAPPER = ROOT_DIR / "tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh"
SCHEMA = "linxcore.replay_liq_pc_filter_preflight_search.v2"
LEGACY_SCHEMAS = {"linxcore.replay_liq_pc_filter_preflight_search.v1", SCHEMA}

DEFAULT_CANDIDATES = (
    ROOT_DIR
    / "generated/r625-coremark-unskipped-1721-qemu-candidates-top100/report/replay_liq_qemu_candidates.json"
)
DEFAULT_ELF = ROOT_DIR / "tests/benchmarks/build/coremark_real.elf"
DEFAULT_BUILD_DIR = ROOT_DIR / "generated/r626-replay-liq-pc-filter-preflight-search"


def load_json(path: Path) -> dict[str, Any]:
    with path.open("r", encoding="utf-8") as f:
        data = json.load(f)
    if not isinstance(data, dict):
        raise ValueError(f"{path} did not contain a JSON object")
    return data


def write_json(path: Path, data: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def count_rows(path: Path) -> int:
    if not path.exists():
        return 0
    with path.open("r", encoding="utf-8", errors="replace") as f:
        return sum(1 for line in f if line.strip())


def load_preview_rows(path: Path) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    if not path.exists():
        return rows
    with path.open("r", encoding="utf-8", errors="replace") as f:
        for line in f:
            if not line.strip():
                continue
            row = json.loads(line)
            if isinstance(row, dict):
                rows.append(row)
    return rows


def parse_int(value: Any) -> int:
    if isinstance(value, int):
        return value
    if isinstance(value, str):
        return int(value, 0)
    raise ValueError(f"expected integer-like value, got {value!r}")


def row_bool(row: dict[str, Any], key: str) -> bool:
    value = row.get(key)
    if isinstance(value, bool):
        return value
    if isinstance(value, int):
        return value != 0
    if isinstance(value, str):
        return value.lower() in {"1", "true", "yes"}
    return False


def row_int(row: dict[str, Any], key: str) -> int:
    value = row.get(key, 0)
    try:
        return parse_int(value)
    except (TypeError, ValueError):
        return 0


def state_seed_audit(preview: Path) -> dict[str, Any]:
    first_active = None
    for row in load_preview_rows(preview):
        if not row_bool(row, "skip"):
            first_active = row
            break
    if first_active is None:
        return {
            "status": "unknown",
            "reason": "no non-skipped reduced preview row was available for RF state audit",
        }

    has_visible_source = row_bool(first_active, "src0_valid") or row_bool(first_active, "src1_valid")
    memory_needs_state = row_bool(first_active, "mem_valid") and (
        row_int(first_active, "mem_addr") != 0
        or row_int(first_active, "mem_wdata") != 0
        or row_int(first_active, "mem_rdata") != 0
    )
    dst_needs_state = row_bool(first_active, "dst_valid") and row_int(first_active, "dst_data") != 0
    common = {
        "first_pc": f"0x{row_int(first_active, 'pc'):x}",
        "first_insn": f"0x{row_int(first_active, 'insn'):x}",
        "mem_valid": row_bool(first_active, "mem_valid"),
        "dst_valid": row_bool(first_active, "dst_valid"),
        "src0_valid": row_bool(first_active, "src0_valid"),
        "src1_valid": row_bool(first_active, "src1_valid"),
    }
    if (memory_needs_state or dst_needs_state) and not has_visible_source:
        return {
            **common,
            "status": "insufficient",
            "reason": (
                "first non-skipped reduced row has memory/destination data but no visible "
                "source operands, so the Verilator harness cannot preload the architectural "
                "RF state needed to start generated RTL at this PC filter"
            ),
        }
    return {
        **common,
        "status": "ready",
        "reason": "first reduced row exposes source operands or does not require hidden RF state",
    }


def candidate_pc_list(candidate: dict[str, Any], op: str) -> list[str]:
    first = candidate.get("first", {})
    second = candidate.get("second", {})
    out: list[str] = []
    for item in (first, second):
        if isinstance(item, dict) and item.get("op") == op and isinstance(item.get("pc"), str):
            out.append(item["pc"])
    return out


def candidate_rows(report: dict[str, Any], *, pc_span_limit: int, max_trials: int) -> list[dict[str, Any]]:
    raw_candidates = report.get("top_candidates")
    if not isinstance(raw_candidates, list):
        return []
    rows: list[dict[str, Any]] = []
    seen: set[tuple[str, str, str, str]] = set()
    for candidate in raw_candidates:
        if not isinstance(candidate, dict):
            continue
        if candidate.get("kind") != "store_before_load" or candidate.get("exact_overlap") is not True:
            continue
        pc_lo = parse_int(candidate.get("pc_lo"))
        pc_hi = parse_int(candidate.get("pc_hi"))
        pc_span = pc_hi - pc_lo
        if pc_span <= 0 or pc_span > pc_span_limit:
            continue
        stores = candidate_pc_list(candidate, "store")
        loads = candidate_pc_list(candidate, "load")
        if not stores or not loads:
            continue
        key = (f"0x{pc_lo:x}", f"0x{pc_hi:x}", ",".join(stores), ",".join(loads))
        if key in seen:
            continue
        seen.add(key)
        rows.append(
            {
                "index": len(rows),
                "score": candidate.get("score"),
                "row_distance": candidate.get("row_distance"),
                "pc_span": pc_span,
                "pc_lo": f"0x{pc_lo:x}",
                "pc_hi": f"0x{pc_hi:x}",
                "expected_store_pcs": stores,
                "expected_load_pcs": loads,
                "raw_dynamic_window": candidate.get("probe_hint", {}).get("raw_dynamic_window"),
            }
        )
    rows.sort(key=lambda item: (int(item["pc_span"]), int(item.get("row_distance") or 0), -int(item.get("score") or 0)))
    return rows[:max_trials]


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


def run_preflight(args: argparse.Namespace, candidate: dict[str, Any], qemu_args: list[str]) -> dict[str, Any]:
    label = f"candidate{candidate['index']:02d}-pc{candidate['pc_lo'][2:]}-{candidate['pc_hi'][2:]}"
    build_dir = args.build_dir / label
    report_dir = build_dir / "report"
    trace_dir = build_dir / "traces"
    report_dir.mkdir(parents=True, exist_ok=True)

    command = [
        "bash",
        str(WRAPPER),
        "--build-dir",
        str(build_dir),
        "--elf",
        str(args.elf),
        "--expected-rows",
        "0",
        "--capture-rows",
        str(args.capture_rows),
        "--pc-lo",
        candidate["pc_lo"],
        "--pc-hi",
        candidate["pc_hi"],
        "--allow-block-markers",
        "--allow-block-loop-reentry",
        "--qemu-only",
        "--expect-store-pcs",
        ",".join(candidate["expected_store_pcs"]),
        "--expect-load-pcs",
        ",".join(candidate["expected_load_pcs"]),
        "--max-seconds",
        str(args.max_seconds),
        "--",
        *qemu_args,
    ]

    timed_out = False
    process = subprocess.Popen(
        command,
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

    (report_dir / "pc-filter-preflight.stdout.txt").write_text(stdout, encoding="utf-8")
    (report_dir / "pc-filter-preflight.stderr.txt").write_text(stderr, encoding="utf-8")
    raw_trace = trace_dir / "qemu.live.raw.jsonl"
    preview = trace_dir / "qemu.live.expected.preview.jsonl"
    raw_rows = count_rows(raw_trace)
    preview_rows = count_rows(preview)
    seed_audit = state_seed_audit(preview)

    if process.returncode == 0:
        status = "pass"
        reason = "QEMU-only PC-filter preflight passed exact memory-PC guards"
    elif timed_out:
        status = "timeout"
        reason = "wrapper timed out before producing a passing preflight"
    elif raw_rows == 0:
        status = "empty"
        reason = "PC filter produced no QEMU rows"
    elif preview_rows == 0:
        status = "reducer_failed"
        reason = "PC filter produced rows, but reduced-row extraction failed before memory-PC guards passed"
    elif "did not match expected" in stderr:
        status = "expected_pc_mismatch"
        reason = "reduced preview memory PCs did not match expected guard sequence"
    else:
        status = "wrapper_failed"
        reason = "wrapper failed before a passing guarded QEMU-only preflight"

    return {
        **candidate,
        "label": label,
        "status": status,
        "reason": reason,
        "returncode": process.returncode,
        "timed_out": timed_out,
        "build_dir": str(build_dir),
        "raw_trace": str(raw_trace),
        "preview": str(preview),
        "stdout": str(report_dir / "pc-filter-preflight.stdout.txt"),
        "stderr": str(report_dir / "pc-filter-preflight.stderr.txt"),
        "raw_rows": raw_rows,
        "preview_rows": preview_rows,
        "state_seed_audit": seed_audit,
    }


def build_summary(args: argparse.Namespace, candidates: list[dict[str, Any]], trials: list[dict[str, Any]]) -> dict[str, Any]:
    pass_trials = [trial for trial in trials if trial.get("status") == "pass"]
    ready_trials = [
        trial
        for trial in pass_trials
        if isinstance(trial.get("state_seed_audit"), dict)
        and trial["state_seed_audit"].get("status") == "ready"
    ]
    blocked_statuses = {"empty", "reducer_failed", "expected_pc_mismatch", "timeout", "wrapper_failed"}
    if ready_trials:
        generated_status = "ready"
        generated_reason = "At least one QEMU-only PC-filter preflight passed exact memory-PC guards and RF state-seed audit"
    elif pass_trials:
        generated_status = "blocked"
        generated_reason = "Passing QEMU-only PC-filter preflights lacked enough visible source state for generated-RTL RF preload"
    else:
        generated_status = "blocked"
        generated_reason = "No scanned PC-filter candidate produced a passing guarded reduced preview"
    return {
        "schema": SCHEMA,
        "status": "pass",
        "claim_boundary": (
            "This is a QEMU-only PC-filter preflight search. A passing trial only "
            "authorizes spending generated-RTL time on the same command shape; it "
            "is not replay-LIQ proof until a generated-RTL manifest and sideband "
            "activation counters pass."
        ),
        "inputs": {
            "candidate_report": str(args.candidates),
            "elf": str(args.elf),
            "build_dir": str(args.build_dir),
            "pc_span_limit": args.pc_span_limit,
            "max_trials": args.max_trials,
            "capture_rows": args.capture_rows,
            "max_seconds": args.max_seconds,
        },
        "summary": {
            "candidate_count": len(candidates),
            "trial_count": len(trials),
            "pass_count": len(pass_trials),
            "state_seed_ready_count": len(ready_trials),
            "blocked_count": sum(1 for trial in trials if trial.get("status") in blocked_statuses),
            "first_pass": pass_trials[0] if pass_trials else None,
            "first_generated_rtl_ready": ready_trials[0] if ready_trials else None,
        },
        "generated_rtl": {
            "status": generated_status,
            "reason": generated_reason,
        },
        "trials": trials,
    }


def validate_report(report: dict[str, Any]) -> list[str]:
    errors: list[str] = []
    schema = report.get("schema")
    if schema not in LEGACY_SCHEMAS:
        errors.append("report: unexpected schema")
    if report.get("status") != "pass":
        errors.append("report: status must be pass")
    boundary = report.get("claim_boundary")
    if not isinstance(boundary, str) or "QEMU-only" not in boundary or "not replay-LIQ proof" not in boundary:
        errors.append("report: claim_boundary must preserve QEMU-only proof boundary")
    summary = report.get("summary")
    trials = report.get("trials")
    if not isinstance(summary, dict):
        errors.append("summary: missing section")
        summary = {}
    if not isinstance(trials, list):
        errors.append("trials: missing section")
        trials = []
    if summary.get("trial_count") != len(trials):
        errors.append("summary: trial_count mismatch")
    pass_count = sum(1 for trial in trials if isinstance(trial, dict) and trial.get("status") == "pass")
    if summary.get("pass_count") != pass_count:
        errors.append("summary: pass_count mismatch")
    seed_ready_count = sum(
        1
        for trial in trials
        if isinstance(trial, dict)
        and trial.get("status") == "pass"
        and isinstance(trial.get("state_seed_audit"), dict)
        and trial["state_seed_audit"].get("status") == "ready"
    )
    if schema == SCHEMA and summary.get("state_seed_ready_count") != seed_ready_count:
        errors.append("summary: state_seed_ready_count mismatch")
    generated = report.get("generated_rtl")
    if not isinstance(generated, dict):
        errors.append("generated_rtl: missing section")
    elif generated.get("status") not in {"blocked", "ready"}:
        errors.append("generated_rtl: unexpected status")
    for trial in trials:
        if not isinstance(trial, dict):
            errors.append("trials: non-object trial")
            continue
        if trial.get("status") not in {"pass", "empty", "reducer_failed", "expected_pc_mismatch", "timeout", "wrapper_failed"}:
            errors.append(f"trials: unexpected status {trial.get('status')!r}")
        seed = trial.get("state_seed_audit")
        if schema == SCHEMA and trial.get("status") == "pass":
            if not isinstance(seed, dict):
                errors.append("trials: passing v2 trial missing state_seed_audit")
            elif seed.get("status") not in {"ready", "insufficient", "unknown"}:
                errors.append(f"trials: unexpected state_seed_audit status {seed.get('status')!r}")
    return errors


def sample_report() -> dict[str, Any]:
    return {
        "schema": SCHEMA,
        "status": "pass",
        "claim_boundary": "QEMU-only preflight, not replay-LIQ proof",
        "summary": {"trial_count": 2, "pass_count": 1, "state_seed_ready_count": 1},
        "generated_rtl": {"status": "ready"},
        "trials": [{"status": "empty"}, {"status": "pass", "state_seed_audit": {"status": "ready"}}],
    }


def run_self_test() -> None:
    passing = sample_report()
    errors = validate_report(passing)
    if errors:
        raise AssertionError(f"valid sample failed validation: {errors}")
    failing = copy.deepcopy(passing)
    failing["summary"]["pass_count"] = 0
    if not validate_report(failing):
        raise AssertionError("pass-count mismatch was not detected")
    failing = copy.deepcopy(passing)
    failing["trials"][0]["status"] = "unknown"
    if not validate_report(failing):
        raise AssertionError("bad trial status was not detected")
    failing = copy.deepcopy(passing)
    failing["summary"]["state_seed_ready_count"] = 0
    if not validate_report(failing):
        raise AssertionError("state-seed ready-count mismatch was not detected")


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--candidates", type=Path, default=DEFAULT_CANDIDATES)
    parser.add_argument("--elf", type=Path, default=DEFAULT_ELF)
    parser.add_argument("--build-dir", type=Path, default=DEFAULT_BUILD_DIR)
    parser.add_argument("--output", type=Path, default=None)
    parser.add_argument("--pc-span-limit", type=int, default=256)
    parser.add_argument("--max-trials", type=int, default=12)
    parser.add_argument("--capture-rows", type=int, default=32)
    parser.add_argument("--max-seconds", type=int, default=30)
    parser.add_argument("--wrapper-timeout-seconds", type=int, default=None)
    parser.add_argument("--stop-on-pass", action="store_true")
    parser.add_argument("--validate-only", type=Path, default=None)
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument("qemu_args", nargs=argparse.REMAINDER)
    args = parser.parse_args(argv)
    if args.pc_span_limit <= 0:
        raise SystemExit("error: --pc-span-limit must be positive")
    if args.max_trials <= 0:
        raise SystemExit("error: --max-trials must be positive")
    if args.capture_rows <= 0:
        raise SystemExit("error: --capture-rows must be positive")
    if args.max_seconds <= 0:
        raise SystemExit("error: --max-seconds must be positive")
    if args.wrapper_timeout_seconds is None:
        args.wrapper_timeout_seconds = max(args.max_seconds * 2, args.max_seconds + 15)
    if args.output is None:
        args.output = args.build_dir / "report/pc_filter_preflight_search.json"
    return args


def main(argv: list[str]) -> int:
    args = parse_args(argv)
    if args.self_test:
        run_self_test()
        print("replay-liq-pc-filter-preflight-search self-test: ok")
        if args.validate_only is None:
            return 0

    if args.validate_only is not None:
        report = load_json(args.validate_only)
        errors = validate_report(report)
        if errors:
            for error in errors:
                print(f"error: {error}", file=sys.stderr)
            return 1
        print(f"replay-liq-pc-filter-preflight-search: ok manifest={args.validate_only}")
        return 0

    candidate_report = load_json(args.candidates)
    candidates = candidate_rows(
        candidate_report,
        pc_span_limit=args.pc_span_limit,
        max_trials=args.max_trials,
    )
    qemu_args = args.qemu_args
    if qemu_args and qemu_args[0] == "--":
        qemu_args = qemu_args[1:]
    if not qemu_args:
        qemu_args = default_qemu_args(args.elf)

    trials: list[dict[str, Any]] = []
    for candidate in candidates:
        trial = run_preflight(args, candidate, qemu_args)
        trials.append(trial)
        print(
            "pc-filter-preflight "
            f"label={trial['label']} status={trial['status']} "
            f"raw_rows={trial['raw_rows']} preview_rows={trial['preview_rows']}",
            flush=True,
        )
        if args.stop_on_pass and trial["status"] == "pass":
            break

    report = build_summary(args, candidates, trials)
    errors = validate_report(report)
    if errors:
        for error in errors:
            print(f"error: {error}", file=sys.stderr)
        return 1
    write_json(args.output, report)
    print(f"replay-liq-pc-filter-preflight-search={args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
