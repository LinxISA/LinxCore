#!/usr/bin/env python3
"""Build a replay-LIQ activation-gap report from R621 evidence.

The report distinguishes a QEMU commit-stream store/load address cluster from a
generated-RTL stimulus that actually reaches resident-store overlap, ResolveQ,
MDB conflict, and LIQ allocation.
"""

from __future__ import annotations

import argparse
import copy
import json
import sys
from pathlib import Path
from typing import Any


ROOT_DIR = Path(__file__).resolve().parents[2]
SCHEMA = "linxcore.replay_liq_activation_gap_report.v1"

DEFAULT_R621_REPORT = (
    ROOT_DIR
    / "generated/r621-replay-liq-selector-unskipped-prefix-report/report/replay_liq_selector_unskipped_prefix_report.json"
)
DEFAULT_SIDEBAND = (
    ROOT_DIR
    / "generated/r621-coremark-unskipped-1721-rtl-xcheck/report/frontend_fetch_rf_alu_sideband_stats.json"
)
DEFAULT_OUTPUT = (
    ROOT_DIR
    / "generated/r622-replay-liq-activation-gap-report/report/replay_liq_activation_gap_report.json"
)

COUNTERS = (
    "load_lookup_valid",
    "load_lookup_execute_granted",
    "load_lookup_execute_with_eligible_store",
    "load_lookup_execute_with_wait_store",
    "resident_store_eligible",
    "resident_store_ready_forward",
    "resident_store_wait_blocked",
    "resident_store_wait_store_valid",
    "store_stq_resident",
    "store_sta_dequeue_fire",
    "store_std_dequeue_fire",
    "store_stq_addr_ready_not_data_ready",
    "mdb_conflict_store_valid",
    "mdb_conflict_store_with_resolve_queue_valid",
    "mdb_conflict_store_without_resolve_queue_valid",
    "resolve_queue_push_accepted",
    "resolve_queue_valid",
    "mdb_conflict_valid",
    "mdb_fanout_record_valid",
    "mdb_lookup_wait_plan_wait_intent_valid",
    "wait_replay_capture_accepted",
    "liq_alloc_accepted",
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


def replay_counters(sideband: dict[str, Any]) -> dict[str, int | None]:
    replay = sideband.get("replay_liq")
    if not isinstance(replay, dict):
        return {key: None for key in COUNTERS}
    out: dict[str, int | None] = {}
    for key in COUNTERS:
        value = replay.get(key)
        out[key] = value if isinstance(value, int) else None
    return out


def positive(counters: dict[str, int | None], key: str) -> bool:
    value = counters.get(key)
    return isinstance(value, int) and value > 0


def zero(counters: dict[str, int | None], key: str) -> bool:
    value = counters.get(key)
    return isinstance(value, int) and value == 0


def build_report(args: argparse.Namespace) -> dict[str, Any]:
    r621 = load_json(args.r621_report)
    sideband = load_json(args.sideband)
    counters = replay_counters(sideband)

    qemu_pair_present = bool(
        r621.get("qemu_only_unskipped_prefix", {}).get("target_pair", {}).get("present")
    )
    rtl_pair_present = bool(
        r621.get("generated_rtl_unskipped_prefix", {}).get("target_pair", {}).get("present")
    )
    manifest_summary = r621.get("generated_rtl_unskipped_prefix", {}).get("summary")
    compare_pass = (
        r621.get("generated_rtl_unskipped_prefix", {}).get("manifest_status") == "pass"
        and isinstance(manifest_summary, dict)
        and manifest_summary.get("mismatch_count") == 0
        and isinstance(manifest_summary.get("compared_rows"), int)
        and manifest_summary.get("compared_rows") > 0
    )

    memory_path_active = (
        positive(counters, "load_lookup_valid")
        and positive(counters, "store_stq_resident")
        and positive(counters, "store_sta_dequeue_fire")
        and positive(counters, "store_std_dequeue_fire")
    )
    resident_overlap_absent = (
        zero(counters, "resident_store_eligible")
        and zero(counters, "load_lookup_execute_with_eligible_store")
        and zero(counters, "load_lookup_execute_with_wait_store")
        and zero(counters, "resident_store_wait_store_valid")
    )
    resolve_queue_absent = zero(counters, "resolve_queue_push_accepted") and zero(counters, "resolve_queue_valid")
    mdb_absent = (
        positive(counters, "mdb_conflict_store_valid")
        and zero(counters, "mdb_conflict_store_with_resolve_queue_valid")
        and positive(counters, "mdb_conflict_store_without_resolve_queue_valid")
        and zero(counters, "mdb_conflict_valid")
        and zero(counters, "mdb_fanout_record_valid")
    )
    liq_absent = zero(counters, "wait_replay_capture_accepted") and zero(counters, "liq_alloc_accepted")

    return {
        "schema": SCHEMA,
        "status": "pass",
        "claim_boundary": (
            "R621 reaches a QEMU and generated-RTL preview address cluster, but the generated-RTL "
            "run never observes a load lookup with an eligible resident store. The activation gap "
            "is before ResolveQ/MDB/LIQ proof, so later work needs a live resident-store overlap "
            "stimulus or a focused replay fixture."
        ),
        "inputs": {
            "r621_report": str(args.r621_report),
            "sideband_report": str(args.sideband),
        },
        "r621_candidate": {
            "qemu_pair_present": qemu_pair_present,
            "generated_rtl_pair_present": rtl_pair_present,
            "compare_pass": compare_pass,
        },
        "activation_stages": {
            "memory_path_active": memory_path_active,
            "resident_store_overlap_absent": resident_overlap_absent,
            "resolve_queue_absent": resolve_queue_absent,
            "mdb_absent": mdb_absent,
            "liq_absent": liq_absent,
        },
        "counters": counters,
        "next_probe_contract": {
            "must_find_or_construct": "load_lookup_execute_with_eligible_store > 0",
            "positive_replay_proof_requires": [
                "load_lookup_execute_with_wait_store > 0 or equivalent retained replay fixture stimulus",
                "resolve_queue_push_accepted > 0",
                "mdb_conflict_valid > 0",
                "mdb_fanout_record_valid > 0",
                "wait_replay_capture_accepted > 0",
                "liq_alloc_accepted > 0",
            ],
        },
    }


def validate_report(report: dict[str, Any]) -> list[str]:
    errors: list[str] = []
    if report.get("schema") != SCHEMA:
        errors.append("report: unexpected schema")
    if report.get("status") != "pass":
        errors.append(f"report: status is {report.get('status')!r}, expected 'pass'")
    boundary = report.get("claim_boundary")
    if not isinstance(boundary, str) or "before ResolveQ/MDB/LIQ proof" not in boundary:
        errors.append("report: claim_boundary must identify the pre-ResolveQ activation gap")
    candidate = report.get("r621_candidate")
    if not isinstance(candidate, dict):
        errors.append("r621_candidate: missing section")
    else:
        for key in ("qemu_pair_present", "generated_rtl_pair_present", "compare_pass"):
            if candidate.get(key) is not True:
                errors.append(f"r621_candidate: {key} must be true")
    stages = report.get("activation_stages")
    if not isinstance(stages, dict):
        errors.append("activation_stages: missing section")
    else:
        expected_true = (
            "memory_path_active",
            "resident_store_overlap_absent",
            "resolve_queue_absent",
            "mdb_absent",
            "liq_absent",
        )
        for key in expected_true:
            if stages.get(key) is not True:
                errors.append(f"activation_stages: {key} must be true")
    counters = report.get("counters")
    if not isinstance(counters, dict):
        errors.append("counters: missing section")
    else:
        for key in COUNTERS:
            if not isinstance(counters.get(key), int):
                errors.append(f"counters: {key} is not an integer")
    return errors


def sample_report() -> dict[str, Any]:
    return {
        "schema": SCHEMA,
        "status": "pass",
        "claim_boundary": "gap is before ResolveQ/MDB/LIQ proof",
        "r621_candidate": {
            "qemu_pair_present": True,
            "generated_rtl_pair_present": True,
            "compare_pass": True,
        },
        "activation_stages": {
            "memory_path_active": True,
            "resident_store_overlap_absent": True,
            "resolve_queue_absent": True,
            "mdb_absent": True,
            "liq_absent": True,
        },
        "counters": {
            key: (1 if key in {
                "load_lookup_valid",
                "store_stq_resident",
                "store_sta_dequeue_fire",
                "store_std_dequeue_fire",
                "mdb_conflict_store_valid",
                "mdb_conflict_store_without_resolve_queue_valid",
            } else 0)
            for key in COUNTERS
        },
    }


def run_self_test() -> None:
    passing = sample_report()
    errors = validate_report(passing)
    if errors:
        raise AssertionError(f"valid sample report failed validation: {errors}")
    failing = copy.deepcopy(passing)
    failing["activation_stages"]["resident_store_overlap_absent"] = False
    if not validate_report(failing):
        raise AssertionError("resident-overlap classification violation was not detected")
    failing = copy.deepcopy(passing)
    failing["r621_candidate"]["compare_pass"] = False
    if not validate_report(failing):
        raise AssertionError("compare-pass violation was not detected")
    failing = copy.deepcopy(passing)
    failing["counters"]["liq_alloc_accepted"] = None
    if not validate_report(failing):
        raise AssertionError("missing counter violation was not detected")


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--r621-report", type=Path, default=DEFAULT_R621_REPORT)
    parser.add_argument("--sideband", type=Path, default=DEFAULT_SIDEBAND)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--validate-only", type=Path, default=None)
    parser.add_argument("--self-test", action="store_true")
    return parser.parse_args(argv)


def main(argv: list[str]) -> int:
    args = parse_args(argv)
    if args.self_test:
        run_self_test()
        print("replay-liq-activation-gap-report self-test: ok")
        if args.validate_only is None and "--output" not in argv:
            return 0

    if args.validate_only is not None:
        report = load_json(args.validate_only)
        errors = validate_report(report)
        if errors:
            for error in errors:
                print(f"error: {error}", file=sys.stderr)
            return 1
        print(f"replay-liq-activation-gap-report: ok manifest={args.validate_only}")
        return 0

    report = build_report(args)
    errors = validate_report(report)
    if errors:
        for error in errors:
            print(f"error: {error}", file=sys.stderr)
        return 1
    write_json(args.output, report)
    print(f"replay-liq-activation-gap-report={args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
