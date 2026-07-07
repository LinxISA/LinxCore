#!/usr/bin/env python3
"""Build a replay-LIQ eligible-store proof report from R622/R623 evidence.

The report preserves the current claim boundary: R622 explains why the
CoreMark candidate-present prefix is pre-ResolveQ, while R623 proves that the
current generated RTL can activate the resident-store, ResolveQ, MDB, LIQ, and
retire-promotion path on a focused replay fixture.
"""

from __future__ import annotations

import argparse
import copy
import json
import sys
from pathlib import Path
from typing import Any


ROOT_DIR = Path(__file__).resolve().parents[2]
SCHEMA = "linxcore.replay_liq_eligible_store_proof_report.v1"

DEFAULT_R622_REPORT = (
    ROOT_DIR
    / "generated/r622-replay-liq-activation-gap-report/report/replay_liq_activation_gap_report.json"
)
DEFAULT_R623_MANIFEST = (
    ROOT_DIR
    / "generated/r623-replay-eligible-store-focused-xcheck/report/crosscheck_manifest.json"
)
DEFAULT_R623_SIDEBAND = (
    ROOT_DIR
    / "generated/r623-replay-eligible-store-focused-xcheck/report/frontend_fetch_rf_alu_sideband_stats.json"
)
DEFAULT_OUTPUT = (
    ROOT_DIR
    / "generated/r623-replay-liq-eligible-store-proof-report/report/replay_liq_eligible_store_proof_report.json"
)

REQUIRED_POSITIVE_COUNTERS = (
    "load_lookup_valid",
    "load_lookup_execute_with_eligible_store",
    "load_lookup_execute_with_wait_store",
    "resident_store_eligible",
    "resident_store_wait_store_valid",
    "resolve_queue_push_accepted",
    "resolve_queue_valid",
    "mdb_conflict_valid",
    "mdb_fanout_record_valid",
    "wait_replay_capture_accepted",
    "liq_alloc_accepted",
    "replay_queue_out_fire",
    "lret_w2_slot_accepted",
    "w2_promotion_live",
)

SELECTOR_ORIGIN_COUNTERS = (
    "w2_retire_record_physical_bundle_suppress_plan_atomic_suppress_candidate",
    "w2_retire_record_physical_bundle_suppress_select_selected",
    "w2_retire_record_physical_bundle_suppress_select_selected_from_promotion",
    "w2_retire_record_physical_bundle_suppress_boundary_capture",
    "w2_retire_record_physical_bundle_suppress_ownership_eligible_registered_mask",
    "w2_retire_record_physical_bundle_suppress_live_mask_enabled_candidate",
    "w2_retire_record_physical_bundle_suppress_clear_proof_all_clear_aligned",
)

REQUIRED_ZERO_COUNTERS = (
    "w2_retire_record_physical_bundle_suppress_select_selected_from_probe",
    "w2_retire_record_physical_bundle_suppress_select_blocked_by_promote_disabled",
    "w2_retire_record_physical_bundle_suppress_select_blocked_by_partial_plan_mask",
    "w2_retire_record_physical_bundle_suppress_select_invalid_probe_promotion_mask_mismatch",
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


def replay_counters(sideband: dict[str, Any], keys: tuple[str, ...]) -> dict[str, int | None]:
    replay = sideband.get("replay_liq")
    if not isinstance(replay, dict):
        return {key: None for key in keys}
    out: dict[str, int | None] = {}
    for key in keys:
        value = replay.get(key)
        out[key] = value if isinstance(value, int) else None
    return out


def positive(counters: dict[str, int | None], key: str) -> bool:
    value = counters.get(key)
    return isinstance(value, int) and value > 0


def zero(counters: dict[str, int | None], key: str) -> bool:
    value = counters.get(key)
    return isinstance(value, int) and value == 0


def manifest_pass(manifest: dict[str, Any]) -> bool:
    summary = manifest.get("summary")
    cbstop = summary.get("cbstop_counts") if isinstance(summary, dict) else None
    comparator_status = manifest.get("comparator_status")
    return (
        manifest.get("status") == "pass"
        and comparator_status in (0, "pass")
        and isinstance(summary, dict)
        and isinstance(summary.get("compared_rows"), int)
        and summary["compared_rows"] > 0
        and summary.get("mismatch_count") == 0
        and isinstance(cbstop, dict)
        and cbstop.get("qemu") == 0
        and cbstop.get("dut") == 0
    )


def manifest_summary(manifest: dict[str, Any]) -> dict[str, Any]:
    summary = manifest.get("summary")
    if not isinstance(summary, dict):
        return {}
    cbstop = summary.get("cbstop_counts")
    return {
        "status": manifest.get("status"),
        "comparator_status": manifest.get("comparator_status"),
        "compared_rows": summary.get("compared_rows"),
        "mismatch_count": summary.get("mismatch_count"),
        "cbstop_qemu": cbstop.get("qemu") if isinstance(cbstop, dict) else None,
        "cbstop_dut": cbstop.get("dut") if isinstance(cbstop, dict) else None,
    }


def r622_gap_still_pre_resolveq(r622: dict[str, Any]) -> bool:
    stages = r622.get("activation_stages")
    counters = r622.get("counters")
    return (
        r622.get("schema") == "linxcore.replay_liq_activation_gap_report.v1"
        and r622.get("status") == "pass"
        and isinstance(stages, dict)
        and stages.get("memory_path_active") is True
        and stages.get("resident_store_overlap_absent") is True
        and stages.get("resolve_queue_absent") is True
        and isinstance(counters, dict)
        and counters.get("load_lookup_execute_with_eligible_store") == 0
    )


def build_report(args: argparse.Namespace) -> dict[str, Any]:
    r622 = load_json(args.r622_report)
    manifest = load_json(args.r623_manifest)
    sideband = load_json(args.r623_sideband)
    positive_counters = replay_counters(sideband, REQUIRED_POSITIVE_COUNTERS)
    selector_counters = replay_counters(sideband, SELECTOR_ORIGIN_COUNTERS)
    zero_counters = replay_counters(sideband, REQUIRED_ZERO_COUNTERS)

    activation_chain = {
        "r622_coremark_gap_confirmed": r622_gap_still_pre_resolveq(r622),
        "r623_manifest_pass": manifest_pass(manifest),
        "resident_store_overlap_present": positive(positive_counters, "load_lookup_execute_with_eligible_store")
        and positive(positive_counters, "resident_store_eligible"),
        "wait_store_present": positive(positive_counters, "load_lookup_execute_with_wait_store")
        and positive(positive_counters, "resident_store_wait_store_valid"),
        "resolve_queue_active": positive(positive_counters, "resolve_queue_push_accepted")
        and positive(positive_counters, "resolve_queue_valid"),
        "mdb_active": positive(positive_counters, "mdb_conflict_valid")
        and positive(positive_counters, "mdb_fanout_record_valid"),
        "liq_active": positive(positive_counters, "wait_replay_capture_accepted")
        and positive(positive_counters, "liq_alloc_accepted")
        and positive(positive_counters, "replay_queue_out_fire"),
        "retire_promotion_active": positive(positive_counters, "lret_w2_slot_accepted")
        and positive(positive_counters, "w2_promotion_live"),
        "selector_origin_active": all(positive(selector_counters, key) for key in SELECTOR_ORIGIN_COUNTERS)
        and all(zero(zero_counters, key) for key in REQUIRED_ZERO_COUNTERS),
    }

    return {
        "schema": SCHEMA,
        "status": "pass",
        "claim_boundary": (
            "R623 proves current-head focused-fixture replay-LIQ activation with eligible "
            "resident-store overlap, ResolveQ, MDB, LIQ allocation, replay output, and W2 "
            "promotion. It does not convert the R621/R622 CoreMark candidate-present prefix "
            "into natural replay-LIQ replacement proof."
        ),
        "inputs": {
            "r622_report": str(args.r622_report),
            "r623_manifest": str(args.r623_manifest),
            "r623_sideband": str(args.r623_sideband),
        },
        "r623_manifest": manifest_summary(manifest),
        "activation_chain": activation_chain,
        "positive_counters": positive_counters,
        "selector_origin_counters": selector_counters,
        "required_zero_counters": zero_counters,
        "git": manifest.get("git", {}),
        "next_probe_contract": {
            "coremark_replacement_needs": [
                "a generated-RTL CoreMark or natural workload command with load_lookup_execute_with_eligible_store > 0",
                "nonzero ResolveQ/MDB/LIQ proof counters in that same run",
                "zero architectural mismatches and zero QEMU/DUT CBSTOP rows",
            ],
            "focused_fixture_status": "positive current-head proof surface for replay-LIQ activation",
        },
    }


def validate_report(report: dict[str, Any]) -> list[str]:
    errors: list[str] = []
    if report.get("schema") != SCHEMA:
        errors.append("report: unexpected schema")
    if report.get("status") != "pass":
        errors.append(f"report: status is {report.get('status')!r}, expected 'pass'")
    boundary = report.get("claim_boundary")
    if not isinstance(boundary, str) or "does not convert" not in boundary:
        errors.append("report: claim_boundary must preserve the CoreMark replacement boundary")
    manifest = report.get("r623_manifest")
    if not isinstance(manifest, dict):
        errors.append("r623_manifest: missing section")
    else:
        if manifest.get("status") != "pass" or manifest.get("comparator_status") not in (0, "pass"):
            errors.append("r623_manifest: status fields must pass")
        if not isinstance(manifest.get("compared_rows"), int) or manifest["compared_rows"] <= 0:
            errors.append("r623_manifest: compared_rows must be positive")
        if manifest.get("mismatch_count") != 0:
            errors.append("r623_manifest: mismatch_count must be zero")
        if manifest.get("cbstop_qemu") != 0 or manifest.get("cbstop_dut") != 0:
            errors.append("r623_manifest: QEMU/DUT CBSTOP counts must be zero")
    chain = report.get("activation_chain")
    if not isinstance(chain, dict):
        errors.append("activation_chain: missing section")
    else:
        for key, value in chain.items():
            if value is not True:
                errors.append(f"activation_chain: {key} must be true")
    positives = report.get("positive_counters")
    if not isinstance(positives, dict):
        errors.append("positive_counters: missing section")
    else:
        for key in REQUIRED_POSITIVE_COUNTERS:
            if not positive(positives, key):
                errors.append(f"positive_counters: {key} must be positive")
    selector = report.get("selector_origin_counters")
    if not isinstance(selector, dict):
        errors.append("selector_origin_counters: missing section")
    else:
        for key in SELECTOR_ORIGIN_COUNTERS:
            if not positive(selector, key):
                errors.append(f"selector_origin_counters: {key} must be positive")
    zeroes = report.get("required_zero_counters")
    if not isinstance(zeroes, dict):
        errors.append("required_zero_counters: missing section")
    else:
        for key in REQUIRED_ZERO_COUNTERS:
            if not zero(zeroes, key):
                errors.append(f"required_zero_counters: {key} must be zero")
    return errors


def sample_report() -> dict[str, Any]:
    return {
        "schema": SCHEMA,
        "status": "pass",
        "claim_boundary": "focused proof does not convert CoreMark replacement boundary",
        "r623_manifest": {
            "status": "pass",
            "comparator_status": "pass",
            "compared_rows": 18,
            "mismatch_count": 0,
            "cbstop_qemu": 0,
            "cbstop_dut": 0,
        },
        "activation_chain": {
            "r622_coremark_gap_confirmed": True,
            "r623_manifest_pass": True,
            "resident_store_overlap_present": True,
            "wait_store_present": True,
            "resolve_queue_active": True,
            "mdb_active": True,
            "liq_active": True,
            "retire_promotion_active": True,
            "selector_origin_active": True,
        },
        "positive_counters": {key: 1 for key in REQUIRED_POSITIVE_COUNTERS},
        "selector_origin_counters": {key: 1 for key in SELECTOR_ORIGIN_COUNTERS},
        "required_zero_counters": {key: 0 for key in REQUIRED_ZERO_COUNTERS},
    }


def run_self_test() -> None:
    passing = sample_report()
    errors = validate_report(passing)
    if errors:
        raise AssertionError(f"valid sample report failed validation: {errors}")
    failing = copy.deepcopy(passing)
    failing["activation_chain"]["resolve_queue_active"] = False
    if not validate_report(failing):
        raise AssertionError("missing ResolveQ activation was not detected")
    failing = copy.deepcopy(passing)
    failing["positive_counters"]["load_lookup_execute_with_eligible_store"] = 0
    if not validate_report(failing):
        raise AssertionError("missing eligible-store counter was not detected")
    failing = copy.deepcopy(passing)
    failing["required_zero_counters"][
        "w2_retire_record_physical_bundle_suppress_select_invalid_probe_promotion_mask_mismatch"
    ] = 1
    if not validate_report(failing):
        raise AssertionError("selector invalid-mask mismatch was not detected")


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--r622-report", type=Path, default=DEFAULT_R622_REPORT)
    parser.add_argument("--r623-manifest", type=Path, default=DEFAULT_R623_MANIFEST)
    parser.add_argument("--r623-sideband", type=Path, default=DEFAULT_R623_SIDEBAND)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--validate-only", type=Path, default=None)
    parser.add_argument("--self-test", action="store_true")
    return parser.parse_args(argv)


def main(argv: list[str]) -> int:
    args = parse_args(argv)
    if args.self_test:
        run_self_test()
        print("replay-liq-eligible-store-proof-report self-test: ok")
        if args.validate_only is None and "--output" not in argv:
            return 0

    if args.validate_only is not None:
        report = load_json(args.validate_only)
        errors = validate_report(report)
        if errors:
            for error in errors:
                print(f"error: {error}", file=sys.stderr)
            return 1
        print(f"replay-liq-eligible-store-proof-report: ok manifest={args.validate_only}")
        return 0

    report = build_report(args)
    errors = validate_report(report)
    if errors:
        for error in errors:
            print(f"error: {error}", file=sys.stderr)
        return 1
    write_json(args.output, report)
    print(f"replay-liq-eligible-store-proof-report={args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
