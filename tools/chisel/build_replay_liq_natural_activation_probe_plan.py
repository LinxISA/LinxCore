#!/usr/bin/env python3
"""Build the replay-LIQ natural-workload activation probe plan.

R623 proves focused-fixture activation. R624 shows existing positive artifacts
are not CoreMark. This R625 plan widens the CoreMark candidate scan and records
which PC-filter preflights are still blocked before any further Verilator run.
"""

from __future__ import annotations

import argparse
import copy
import json
import sys
from pathlib import Path
from typing import Any


ROOT_DIR = Path(__file__).resolve().parents[2]
SCHEMA = "linxcore.replay_liq_natural_activation_probe_plan.v1"

DEFAULT_R622_REPORT = (
    ROOT_DIR
    / "generated/r622-replay-liq-activation-gap-report/report/replay_liq_activation_gap_report.json"
)
DEFAULT_R624_SCAN = (
    ROOT_DIR
    / "generated/r624-replay-liq-activation-artifact-scan/report/replay_liq_activation_artifact_scan.json"
)
DEFAULT_CANDIDATES = (
    ROOT_DIR
    / "generated/r625-coremark-unskipped-1721-qemu-candidates-top100/report/replay_liq_qemu_candidates.json"
)
DEFAULT_R620_PREFLIGHT = (
    ROOT_DIR
    / "generated/r620-replay-liq-selector-preflight-report/report/replay_liq_selector_preflight_report.json"
)
DEFAULT_OUTPUT = (
    ROOT_DIR
    / "generated/r625-replay-liq-natural-activation-probe-plan/report/replay_liq_natural_activation_probe_plan.json"
)

TRIALS = (
    {
        "label": "r625_pc_filter_400055f2_40005644",
        "build_dir": "generated/r625-coremark-pc-filter-400055f2-40005644-qemu-preflight",
        "pc_lo": "0x400055f2",
        "pc_hi": "0x40005645",
        "expected_store_pcs": ["0x400055f2"],
        "expected_load_pcs": ["0x40005644"],
    },
    {
        "label": "r625_pc_filter_40005682_40005700",
        "build_dir": "generated/r625-coremark-pc-filter-40005682-40005700-qemu-preflight",
        "pc_lo": "0x40005682",
        "pc_hi": "0x40005701",
        "expected_store_pcs": ["0x40005682"],
        "expected_load_pcs": ["0x40005700"],
    },
    {
        "label": "r625_pc_filter_400055b6_40005600",
        "build_dir": "generated/r625-coremark-pc-filter-400055b6-40005600-qemu-preflight",
        "pc_lo": "0x400055b6",
        "pc_hi": "0x40005601",
        "expected_store_pcs": ["0x400055b6"],
        "expected_load_pcs": ["0x40005600"],
    },
)


def load_json(path: Path) -> dict[str, Any]:
    with path.open("r", encoding="utf-8") as f:
        data = json.load(f)
    if not isinstance(data, dict):
        raise ValueError(f"{path} did not contain a JSON object")
    return data


def write_json(path: Path, data: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def count_jsonl_rows(path: Path) -> int:
    if not path.exists():
        return 0
    with path.open("r", encoding="utf-8", errors="replace") as f:
        return sum(1 for line in f if line.strip())


def parse_int(value: Any) -> int:
    if isinstance(value, int):
        return value
    if isinstance(value, str):
        return int(value, 0)
    raise ValueError(f"expected integer-like value, got {value!r}")


def r622_gap_confirmed(report: dict[str, Any]) -> bool:
    stages = report.get("activation_stages")
    counters = report.get("counters")
    return (
        report.get("schema") == "linxcore.replay_liq_activation_gap_report.v1"
        and report.get("status") == "pass"
        and isinstance(stages, dict)
        and stages.get("resident_store_overlap_absent") is True
        and stages.get("resolve_queue_absent") is True
        and isinstance(counters, dict)
        and counters.get("load_lookup_execute_with_eligible_store") == 0
    )


def r624_coremark_absent(report: dict[str, Any]) -> bool:
    summary = report.get("summary")
    return (
        report.get("schema") == "linxcore.replay_liq_activation_artifact_scan.v1"
        and report.get("status") == "pass"
        and isinstance(summary, dict)
        and summary.get("coremark_positive_count") == 0
    )


def candidate_summary(report: dict[str, Any]) -> dict[str, Any]:
    candidates = report.get("top_candidates")
    if not isinstance(candidates, list):
        candidates = []
    narrow_exact = []
    for candidate in candidates:
        if not isinstance(candidate, dict):
            continue
        span = parse_int(candidate.get("pc_hi")) - parse_int(candidate.get("pc_lo"))
        if (
            candidate.get("kind") == "store_before_load"
            and candidate.get("exact_overlap") is True
            and span <= 256
        ):
            narrow_exact.append(
                {
                    "score": candidate.get("score"),
                    "row_distance": candidate.get("row_distance"),
                    "pc_span": span,
                    "store_pc": candidate.get("first", {}).get("pc"),
                    "load_pc": candidate.get("second", {}).get("pc"),
                    "raw_dynamic_window": candidate.get("probe_hint", {}).get("raw_dynamic_window"),
                    "pc_filter": candidate.get("probe_hint", {}).get("pc_filter"),
                }
            )
    return {
        "schema": report.get("schema"),
        "event_count": report.get("event_count"),
        "store_count": report.get("store_count"),
        "load_count": report.get("load_count"),
        "candidate_count": report.get("candidate_count"),
        "top_candidate_count": len(candidates),
        "narrow_exact_store_before_load_count": len(narrow_exact),
        "narrow_exact_store_before_load": narrow_exact,
    }


def classify_trial(trial: dict[str, Any]) -> dict[str, Any]:
    build_dir = ROOT_DIR / str(trial["build_dir"])
    raw_trace = build_dir / "traces/qemu.live.raw.jsonl"
    preview = build_dir / "traces/qemu.live.expected.preview.jsonl"
    raw_rows = count_jsonl_rows(raw_trace)
    preview_rows = count_jsonl_rows(preview)
    if raw_rows == 0:
        status = "empty"
        reason = "PC filter produced no QEMU rows"
    elif preview_rows == 0:
        status = "reducer_failed"
        reason = "PC filter produced rows, but the reduced-row extractor did not produce a legal expected preview"
    else:
        status = "preview_present"
        reason = "PC filter produced a reduced preview; inspect memory-PC guards before any generated-RTL run"
    return {
        **trial,
        "raw_trace": str(raw_trace),
        "preview": str(preview),
        "raw_rows": raw_rows,
        "preview_rows": preview_rows,
        "status": status,
        "reason": reason,
    }


def build_report(args: argparse.Namespace) -> dict[str, Any]:
    r622 = load_json(args.r622_report)
    r624 = load_json(args.r624_scan)
    candidates = load_json(args.candidates)
    r620 = load_json(args.r620_preflight)
    candidate_info = candidate_summary(candidates)
    trials = [classify_trial(trial) for trial in TRIALS]

    top_pc_filter = r620.get("pc_filter_qemu_only")
    top_status = top_pc_filter.get("status") if isinstance(top_pc_filter, dict) else None
    all_trials_blocked = all(trial["status"] in {"empty", "reducer_failed"} for trial in trials)

    return {
        "schema": SCHEMA,
        "status": "pass",
        "claim_boundary": (
            "R625 is a natural-workload probe plan and QEMU-only preflight classifier. "
            "It does not authorize a generated-RTL CoreMark replay-LIQ proof until a "
            "non-skipped or legal PC-filter command shape passes expected-memory-PC guards."
        ),
        "inputs": {
            "r622_report": str(args.r622_report),
            "r624_scan": str(args.r624_scan),
            "candidates": str(args.candidates),
            "r620_preflight": str(args.r620_preflight),
        },
        "evidence_checks": {
            "r622_coremark_gap_confirmed": r622_gap_confirmed(r622),
            "r624_coremark_positive_absent": r624_coremark_absent(r624),
            "top_candidate_pc_filter_blocked": top_status != "pass",
            "sampled_new_pc_filters_blocked": all_trials_blocked,
        },
        "candidate_summary": candidate_info,
        "known_top_candidate_preflight": {
            "source": str(args.r620_preflight),
            "pc_filter_status": top_status,
            "generated_rtl_status": r620.get("generated_rtl", {}).get("status")
            if isinstance(r620.get("generated_rtl"), dict)
            else None,
        },
        "pc_filter_trials": trials,
        "generated_rtl": {
            "status": "blocked",
            "reason": (
                "The unskipped CoreMark prefix remains pre-ResolveQ, the known narrow top "
                "candidate PC-filter preflight did not pass, and sampled untried narrow PC "
                "filters are empty or illegal reduced prefixes."
            ),
        },
        "next_probe_contract": {
            "do_not_spend_verilator_on": [
                "raw skipped windows, because skipped rows cannot reconstruct DUT state",
                "PC filters whose QEMU-only preflight is empty or reducer_failed",
                "wide PC spans that collect unrelated memory rows before exact memory-PC guards pass",
            ],
            "next_viable_paths": [
                "add checkpoint/state-replay support for skipped raw windows",
                "construct a natural workload or benchmark shard whose unskipped prefix reaches eligible-store overlap",
                "find a PC-filtered QEMU-only preflight that produces a legal reduced preview with exact expected memory PCs",
            ],
        },
    }


def validate_report(report: dict[str, Any]) -> list[str]:
    errors: list[str] = []
    if report.get("schema") != SCHEMA:
        errors.append("report: unexpected schema")
    if report.get("status") != "pass":
        errors.append("report: status must be pass")
    boundary = report.get("claim_boundary")
    if not isinstance(boundary, str) or "does not authorize" not in boundary:
        errors.append("report: claim_boundary must keep generated RTL blocked")
    checks = report.get("evidence_checks")
    if not isinstance(checks, dict):
        errors.append("evidence_checks: missing section")
    else:
        for key, value in checks.items():
            if value is not True:
                errors.append(f"evidence_checks: {key} must be true")
    candidate = report.get("candidate_summary")
    if not isinstance(candidate, dict):
        errors.append("candidate_summary: missing section")
    else:
        if not isinstance(candidate.get("candidate_count"), int) or candidate["candidate_count"] <= 0:
            errors.append("candidate_summary: candidate_count must be positive")
        if (
            not isinstance(candidate.get("narrow_exact_store_before_load_count"), int)
            or candidate["narrow_exact_store_before_load_count"] <= 0
        ):
            errors.append("candidate_summary: narrow exact store-before-load candidates must be present")
    trials = report.get("pc_filter_trials")
    if not isinstance(trials, list) or not trials:
        errors.append("pc_filter_trials: missing trials")
    else:
        for trial in trials:
            if not isinstance(trial, dict):
                errors.append("pc_filter_trials: non-object trial")
                continue
            if trial.get("status") not in {"empty", "reducer_failed", "preview_present"}:
                errors.append(f"pc_filter_trials: bad status {trial.get('status')!r}")
    generated = report.get("generated_rtl")
    if not isinstance(generated, dict) or generated.get("status") != "blocked":
        errors.append("generated_rtl: must remain blocked")
    return errors


def sample_report() -> dict[str, Any]:
    return {
        "schema": SCHEMA,
        "status": "pass",
        "claim_boundary": "plan does not authorize generated RTL",
        "evidence_checks": {
            "r622_coremark_gap_confirmed": True,
            "r624_coremark_positive_absent": True,
            "top_candidate_pc_filter_blocked": True,
            "sampled_new_pc_filters_blocked": True,
        },
        "candidate_summary": {
            "candidate_count": 2,
            "narrow_exact_store_before_load_count": 1,
        },
        "pc_filter_trials": [{"status": "empty"}],
        "generated_rtl": {"status": "blocked"},
    }


def run_self_test() -> None:
    passing = sample_report()
    errors = validate_report(passing)
    if errors:
        raise AssertionError(f"valid sample report failed validation: {errors}")
    failing = copy.deepcopy(passing)
    failing["generated_rtl"]["status"] = "ready"
    if not validate_report(failing):
        raise AssertionError("unblocked generated RTL was not detected")
    failing = copy.deepcopy(passing)
    failing["evidence_checks"]["r624_coremark_positive_absent"] = False
    if not validate_report(failing):
        raise AssertionError("failed evidence check was not detected")
    failing = copy.deepcopy(passing)
    failing["candidate_summary"]["narrow_exact_store_before_load_count"] = 0
    if not validate_report(failing):
        raise AssertionError("missing candidate coverage was not detected")


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--r622-report", type=Path, default=DEFAULT_R622_REPORT)
    parser.add_argument("--r624-scan", type=Path, default=DEFAULT_R624_SCAN)
    parser.add_argument("--candidates", type=Path, default=DEFAULT_CANDIDATES)
    parser.add_argument("--r620-preflight", type=Path, default=DEFAULT_R620_PREFLIGHT)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--validate-only", type=Path, default=None)
    parser.add_argument("--self-test", action="store_true")
    return parser.parse_args(argv)


def main(argv: list[str]) -> int:
    args = parse_args(argv)
    if args.self_test:
        run_self_test()
        print("replay-liq-natural-activation-probe-plan self-test: ok")
        if args.validate_only is None and "--output" not in argv:
            return 0

    if args.validate_only is not None:
        report = load_json(args.validate_only)
        errors = validate_report(report)
        if errors:
            for error in errors:
                print(f"error: {error}", file=sys.stderr)
            return 1
        print(f"replay-liq-natural-activation-probe-plan: ok manifest={args.validate_only}")
        return 0

    report = build_report(args)
    errors = validate_report(report)
    if errors:
        for error in errors:
            print(f"error: {error}", file=sys.stderr)
        return 1
    write_json(args.output, report)
    print(f"replay-liq-natural-activation-probe-plan={args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
