#!/usr/bin/env python3
"""Replay QEMU raw windows through generated RTL with matching RF seeds.

This scanner consumes existing QEMU raw-prefix artifacts and candidate reports.
For each selected raw dynamic window it:

1. builds an RF seed from rows before the window start,
2. slices the raw QEMU window into a replay trace,
3. runs the existing reduced-store replay-LIQ Verilator trace wrapper, and
4. classifies the generated manifest plus replay-LIQ sideband counters.

The scanner is a proof orchestrator, not a new architectural oracle. A trial is
replay-LIQ activation proof only when the generated-RTL comparator manifest
passes and every required activation counter is nonzero.
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

from build_frontend_fetch_rf_seed import build_seed, load_jsonl, write_seed
from scan_replay_liq_activation_artifacts import (
    REQUIRED_ACTIVATION_COUNTERS,
    SUMMARY_COUNTERS,
    counter_map,
    manifest_summary,
    positive,
)


ROOT_DIR = Path(__file__).resolve().parents[2]
WRAPPER = ROOT_DIR / "tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh"
SCHEMA = "linxcore.replay_liq_qemu_seeded_window_scan.v1"

DEFAULT_RAW_TRACE = (
    ROOT_DIR / "generated/r621-coremark-unskipped-1721-qemu-preflight/traces/qemu.live.raw.jsonl"
)
DEFAULT_CANDIDATES = (
    ROOT_DIR
    / "generated/r625-coremark-unskipped-1721-qemu-candidates-top100/report/replay_liq_qemu_candidates.json"
)
DEFAULT_ELF = ROOT_DIR / "tests/benchmarks/build/coremark_real.elf"
DEFAULT_BUILD_DIR = ROOT_DIR / "generated/r630-replay-liq-qemu-seeded-window-scan"


def load_json(path: Path) -> dict[str, Any]:
    with path.open("r", encoding="utf-8") as f:
        data = json.load(f)
    if not isinstance(data, dict):
        raise ValueError(f"{path} did not contain a JSON object")
    return data


def write_json(path: Path, data: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def parse_int(value: Any) -> int:
    if isinstance(value, int):
        return value
    if isinstance(value, str):
        return int(value, 0)
    raise ValueError(f"expected integer-like value, got {value!r}")


def load_jsonl_lines(path: Path) -> list[str]:
    lines: list[str] = []
    with path.open("r", encoding="utf-8", errors="replace") as f:
        for line in f:
            if line.strip():
                lines.append(line)
    return lines


def write_window_slice(source: Path, output: Path, *, start: int, count: int) -> int:
    lines = load_jsonl_lines(source)
    output.parent.mkdir(parents=True, exist_ok=True)
    selected = lines[start : start + count]
    output.write_text("".join(selected), encoding="utf-8")
    return len(selected)


def candidate_windows(
    report: dict[str, Any],
    *,
    skip_windows: int,
    max_trials: int,
    max_capture_rows: int,
) -> list[dict[str, Any]]:
    raw_candidates = report.get("top_candidates")
    if not isinstance(raw_candidates, list):
        return []
    selected: list[dict[str, Any]] = []
    seen: set[tuple[int, int]] = set()
    eligible_index = 0
    for candidate in raw_candidates:
        if not isinstance(candidate, dict):
            continue
        hint = candidate.get("probe_hint", {}).get("raw_dynamic_window")
        if not isinstance(hint, dict):
            continue
        skip_rows = parse_int(hint.get("qemu_skip_rows"))
        capture_rows = parse_int(hint.get("capture_rows"))
        if capture_rows <= 0 or capture_rows > max_capture_rows:
            continue
        key = (skip_rows, capture_rows)
        if key in seen:
            continue
        seen.add(key)
        if eligible_index < skip_windows:
            eligible_index += 1
            continue
        selected.append(
            {
                "index": eligible_index,
                "kind": candidate.get("kind"),
                "exact_overlap": candidate.get("exact_overlap"),
                "score": candidate.get("score"),
                "row_distance": candidate.get("row_distance"),
                "pc_lo": f"0x{parse_int(candidate.get('pc_lo')):x}",
                "pc_hi": f"0x{parse_int(candidate.get('pc_hi')):x}",
                "qemu_skip_rows": skip_rows,
                "capture_rows": capture_rows,
                "raw_dynamic_window": hint,
            }
        )
        eligible_index += 1
        if len(selected) >= max_trials:
            break
    return selected


def manifest_pass(summary: dict[str, Any]) -> bool:
    if summary.get("present") is not True:
        return False
    return (
        summary.get("status") == "pass"
        and summary.get("comparator_status") == 0
        and isinstance(summary.get("compared_rows"), int)
        and summary.get("compared_rows") > 0
        and summary.get("mismatch_count") == 0
        and summary.get("cbstop_qemu") == 0
        and summary.get("cbstop_dut") == 0
    )


def sideband_summary(sideband_path: Path) -> dict[str, Any]:
    if not sideband_path.exists():
        return {
            "present": False,
            "activation_positive": False,
            "counters": {key: None for key in SUMMARY_COUNTERS},
        }
    sideband = load_json(sideband_path)
    counters = counter_map(sideband)
    activation_positive = all(positive(counters, key) for key in REQUIRED_ACTIVATION_COUNTERS)
    return {
        "present": True,
        "schema": sideband.get("schema"),
        "reduced_store_replay_liq_top": sideband.get("reduced_store_replay_liq_top"),
        "activation_positive": activation_positive,
        "counters": counters,
    }


def run_window(args: argparse.Namespace, window: dict[str, Any], all_rows: list[dict[str, Any]]) -> dict[str, Any]:
    label = (
        f"window{window['index']:02d}-skip{window['qemu_skip_rows']}"
        f"-rows{window['capture_rows']}"
    )
    build_dir = args.build_dir / label
    trace_dir = build_dir / "traces"
    report_dir = build_dir / "report"
    seed_path = trace_dir / "rf_seed.jsonl"
    raw_window = trace_dir / "qemu.window.raw.jsonl"
    report_dir.mkdir(parents=True, exist_ok=True)

    state, corrections = build_seed(
        all_rows,
        stop_index=window["qemu_skip_rows"],
        max_arch_reg=args.max_arch_reg,
    )
    write_seed(
        seed_path,
        state=state,
        source=args.input_raw,
        stop_index=window["qemu_skip_rows"],
        stop_pc=None,
    )
    sliced_rows = write_window_slice(
        args.input_raw,
        raw_window,
        start=window["qemu_skip_rows"],
        count=window["capture_rows"],
    )

    env = os.environ.copy()
    env.update(
        {
            "BUILD_DIR": str(build_dir),
            "FETCH_ELF": str(args.elf),
            "FETCH_QEMU_TRACE": str(raw_window),
            "FETCH_QEMU_MAX_ROWS": "0",
            "FETCH_QEMU_ALLOW_BLOCK_MARKERS": "1",
            "FETCH_QEMU_ALLOW_BLOCK_LOOP_REENTRY": "1",
            "FETCH_REDUCED_STORE_REPLAY_LIQ": "1",
            "FETCH_DISABLE_STORE_MEMORY_MUTATION": "1",
            "FETCH_RF_SEED": str(seed_path),
        }
    )
    if args.early_sta_address:
        env["LINXCORE_REPLAY_LIQ_EARLY_STA_ADDRESS"] = "1"
    if args.allow_residual_replay_liq_wait:
        env["FETCH_ALLOW_RESIDUAL_REPLAY_LIQ_WAIT"] = "1"

    timed_out = False
    process = subprocess.Popen(
        ["bash", str(WRAPPER)],
        cwd=ROOT_DIR,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        start_new_session=True,
        env=env,
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

    (report_dir / "seeded-window.stdout.txt").write_text(stdout, encoding="utf-8")
    (report_dir / "seeded-window.stderr.txt").write_text(stderr, encoding="utf-8")

    manifest = manifest_summary(report_dir / "crosscheck_manifest.json")
    sideband = sideband_summary(report_dir / "frontend_fetch_rf_alu_sideband_stats.json")
    comparator_pass = manifest_pass(manifest)
    activation_positive = comparator_pass and sideband.get("activation_positive") is True
    rf_conflict = "expected rows require conflicting initial RF data" in stderr
    if activation_positive:
        status = "activation_positive"
        reason = "generated RTL comparator passed and all required replay-LIQ activation counters were nonzero"
    elif comparator_pass:
        status = "compare_pass_no_activation"
        reason = "generated RTL comparator passed, but replay-LIQ activation counters were not all nonzero"
    elif rf_conflict:
        status = "rf_source_conflict"
        reason = "reduced expected rows require conflicting initial RF source data inside one seeded launch window"
    elif timed_out:
        status = "timeout"
        reason = "generated RTL wrapper timed out"
    else:
        status = "wrapper_failed"
        reason = "generated RTL wrapper failed before a passing comparator manifest"

    return {
        **window,
        "label": label,
        "status": status,
        "reason": reason,
        "build_dir": str(build_dir),
        "raw_window": str(raw_window),
        "rf_seed": str(seed_path),
        "seed_register_count": len(state),
        "seed_source_corrections": len(corrections),
        "sliced_rows": sliced_rows,
        "wrapper_returncode": process.returncode,
        "wrapper_timed_out": timed_out,
        "stdout": str(report_dir / "seeded-window.stdout.txt"),
        "stderr": str(report_dir / "seeded-window.stderr.txt"),
        "manifest": manifest,
        "sideband": sideband,
    }


def build_report(args: argparse.Namespace, windows: list[dict[str, Any]], trials: list[dict[str, Any]]) -> dict[str, Any]:
    positives = [trial for trial in trials if trial.get("status") == "activation_positive"]
    compare_passes = [trial for trial in trials if trial.get("status") in {"activation_positive", "compare_pass_no_activation"}]
    return {
        "schema": SCHEMA,
        "status": "pass",
        "claim_boundary": (
            "A seeded raw-window trial is replay-LIQ activation proof only when "
            "the generated-RTL comparator manifest passes and every required "
            "eligible-store, ResolveQ, MDB, LIQ, replay-output, and W2-promotion "
            "counter is nonzero. Comparator pass alone proves launch-state "
            "reconstruction, not replay-LIQ replacement."
        ),
        "inputs": {
            "candidate_report": str(args.candidates),
            "input_raw": str(args.input_raw),
            "elf": str(args.elf),
            "build_dir": str(args.build_dir),
            "skip_windows": args.skip_windows,
            "max_trials": args.max_trials,
            "max_capture_rows": args.max_capture_rows,
            "early_sta_address": args.early_sta_address,
            "allow_residual_replay_liq_wait": args.allow_residual_replay_liq_wait,
        },
        "required_activation_counters": list(REQUIRED_ACTIVATION_COUNTERS),
        "summary": {
            "window_count": len(windows),
            "trial_count": len(trials),
            "compare_pass_count": len(compare_passes),
            "activation_positive_count": len(positives),
            "first_activation_positive": positives[0] if positives else None,
        },
        "generated_rtl": {
            "status": "activation_positive" if positives else "no_activation",
            "reason": (
                "At least one seeded raw window produced full replay-LIQ activation proof"
                if positives
                else "No scanned seeded raw window produced the required positive replay-LIQ counter bundle"
            ),
        },
        "windows": windows,
        "trials": trials,
    }


def validate_report(report: dict[str, Any]) -> list[str]:
    errors: list[str] = []
    if report.get("schema") != SCHEMA:
        errors.append("report: unexpected schema")
    if report.get("status") != "pass":
        errors.append("report: status must be pass")
    boundary = report.get("claim_boundary")
    if not isinstance(boundary, str) or "Comparator pass alone" not in boundary:
        errors.append("report: claim_boundary must preserve comparator-vs-activation boundary")
    summary = report.get("summary")
    trials = report.get("trials")
    windows = report.get("windows")
    if not isinstance(summary, dict):
        errors.append("summary: missing section")
        summary = {}
    if not isinstance(trials, list):
        errors.append("trials: missing section")
        trials = []
    if not isinstance(windows, list):
        errors.append("windows: missing section")
        windows = []
    if summary.get("window_count") != len(windows):
        errors.append("summary: window_count mismatch")
    if summary.get("trial_count") != len(trials):
        errors.append("summary: trial_count mismatch")
    compare_pass_count = sum(
        1
        for trial in trials
        if isinstance(trial, dict)
        and trial.get("status") in {"activation_positive", "compare_pass_no_activation"}
    )
    activation_count = sum(
        1 for trial in trials if isinstance(trial, dict) and trial.get("status") == "activation_positive"
    )
    if summary.get("compare_pass_count") != compare_pass_count:
        errors.append("summary: compare_pass_count mismatch")
    if summary.get("activation_positive_count") != activation_count:
        errors.append("summary: activation_positive_count mismatch")
    generated = report.get("generated_rtl")
    if not isinstance(generated, dict) or generated.get("status") not in {"activation_positive", "no_activation"}:
        errors.append("generated_rtl: unexpected status")
    for trial in trials:
        if not isinstance(trial, dict):
            errors.append("trials: non-object trial")
            continue
        if trial.get("status") not in {
            "activation_positive",
            "compare_pass_no_activation",
            "rf_source_conflict",
            "timeout",
            "wrapper_failed",
        }:
            errors.append(f"{trial.get('label')}: unexpected trial status {trial.get('status')!r}")
        if trial.get("status") == "activation_positive":
            manifest = trial.get("manifest")
            sideband = trial.get("sideband")
            if not isinstance(manifest, dict) or not manifest_pass(manifest):
                errors.append(f"{trial.get('label')}: activation-positive trial lacks passing manifest")
            if not isinstance(sideband, dict):
                errors.append(f"{trial.get('label')}: activation-positive trial missing sideband")
                continue
            counters = sideband.get("counters")
            if not isinstance(counters, dict):
                errors.append(f"{trial.get('label')}: activation-positive trial missing counters")
                continue
            for key in REQUIRED_ACTIVATION_COUNTERS:
                if not positive(counters, key):
                    errors.append(f"{trial.get('label')}: {key} must be positive")
    return errors


def sample_report() -> dict[str, Any]:
    trial = {
        "label": "window00-skip0-rows4",
        "status": "activation_positive",
        "manifest": {
            "present": True,
            "status": "pass",
            "comparator_status": 0,
            "compared_rows": 3,
            "mismatch_count": 0,
            "cbstop_qemu": 0,
            "cbstop_dut": 0,
        },
        "sideband": {
            "present": True,
            "activation_positive": True,
            "counters": {key: 1 for key in SUMMARY_COUNTERS},
        },
    }
    return {
        "schema": SCHEMA,
        "status": "pass",
        "claim_boundary": "Comparator pass alone is not replay-LIQ activation proof",
        "summary": {
            "window_count": 1,
            "trial_count": 1,
            "compare_pass_count": 1,
            "activation_positive_count": 1,
        },
        "generated_rtl": {"status": "activation_positive"},
        "windows": [{"index": 0}],
        "trials": [trial],
    }


def run_self_test() -> None:
    passing = sample_report()
    errors = validate_report(passing)
    if errors:
        raise AssertionError(f"valid sample report failed validation: {errors}")
    failing = copy.deepcopy(passing)
    failing["summary"]["activation_positive_count"] = 0
    if not validate_report(failing):
        raise AssertionError("activation-positive summary mismatch was not detected")
    failing = copy.deepcopy(passing)
    failing["trials"][0]["sideband"]["counters"]["resolve_queue_push_accepted"] = 0
    if not validate_report(failing):
        raise AssertionError("missing positive counter was not detected")


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--candidates", type=Path, default=DEFAULT_CANDIDATES)
    parser.add_argument("--input-raw", type=Path, default=DEFAULT_RAW_TRACE)
    parser.add_argument("--elf", type=Path, default=DEFAULT_ELF)
    parser.add_argument("--build-dir", type=Path, default=DEFAULT_BUILD_DIR)
    parser.add_argument("--output", type=Path, default=None)
    parser.add_argument(
        "--skip-windows",
        type=int,
        default=0,
        help="skip this many eligible candidate windows before running trials",
    )
    parser.add_argument("--max-trials", type=int, default=1)
    parser.add_argument("--max-capture-rows", type=int, default=160)
    parser.add_argument("--max-arch-reg", type=int, default=24)
    parser.add_argument("--wrapper-timeout-seconds", type=int, default=300)
    parser.add_argument("--no-early-sta-address", action="store_true")
    parser.add_argument("--allow-residual-replay-liq-wait", action="store_true")
    parser.add_argument("--stop-on-activation", action="store_true")
    parser.add_argument("--validate-only", type=Path, default=None)
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args(argv)
    if args.output is None:
        args.output = args.build_dir / "report/seeded_window_scan.json"
    if args.skip_windows < 0:
        raise SystemExit("error: --skip-windows must be non-negative")
    if args.max_trials <= 0:
        raise SystemExit("error: --max-trials must be positive")
    if args.max_capture_rows <= 0:
        raise SystemExit("error: --max-capture-rows must be positive")
    if args.max_arch_reg <= 0:
        raise SystemExit("error: --max-arch-reg must be positive")
    if args.wrapper_timeout_seconds <= 0:
        raise SystemExit("error: --wrapper-timeout-seconds must be positive")
    args.early_sta_address = not args.no_early_sta_address
    return args


def main(argv: list[str]) -> int:
    args = parse_args(argv)
    if args.self_test:
        run_self_test()
        print("replay-liq-qemu-seeded-window-scan self-test: ok")
        if args.validate_only is None:
            return 0

    if args.validate_only is not None:
        report = load_json(args.validate_only)
        errors = validate_report(report)
        if errors:
            for error in errors:
                print(f"error: {error}", file=sys.stderr)
            return 1
        print(f"replay-liq-qemu-seeded-window-scan: ok manifest={args.validate_only}")
        return 0

    candidate_report = load_json(args.candidates)
    windows = candidate_windows(
        candidate_report,
        skip_windows=args.skip_windows,
        max_trials=args.max_trials,
        max_capture_rows=args.max_capture_rows,
    )
    all_rows = load_jsonl(args.input_raw)
    trials: list[dict[str, Any]] = []
    for window in windows:
        trial = run_window(args, window, all_rows)
        trials.append(trial)
        print(
            "seeded-window "
            f"label={trial['label']} status={trial['status']} "
            f"seed_regs={trial['seed_register_count']} sliced_rows={trial['sliced_rows']} "
            f"compared={trial.get('manifest', {}).get('compared_rows')} "
            f"activation={trial.get('sideband', {}).get('activation_positive')}",
            flush=True,
        )
        if args.stop_on_activation and trial["status"] == "activation_positive":
            break

    report = build_report(args, windows, trials)
    errors = validate_report(report)
    if errors:
        for error in errors:
            print(f"error: {error}", file=sys.stderr)
        return 1
    write_json(args.output, report)
    print(f"replay-liq-qemu-seeded-window-scan={args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
