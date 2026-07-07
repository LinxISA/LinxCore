#!/usr/bin/env python3
"""Build a replay-LIQ selector unskipped-prefix report.

R621 proves that the R617 CoreMark candidate can be reached without skipped
QEMU rows and that the matching generated-RTL/QEMU reduced prefix still passes.
It does not prove natural replay-LIQ replacement activity unless the replay-LIQ
and MDB sideband counters become positive.
"""

from __future__ import annotations

import argparse
import copy
import json
import sys
from pathlib import Path
from typing import Any


ROOT_DIR = Path(__file__).resolve().parents[2]
SCHEMA = "linxcore.replay_liq_selector_unskipped_prefix_report.v1"

TARGET_STORE_PC = 0x4000D7E6
TARGET_LOAD_PC = 0x4000D7F2
TARGET_ADDR = 0x4FFEFB68
TARGET_SIZE = 8

DEFAULT_QEMU_ONLY_PREVIEW = (
    ROOT_DIR
    / "generated/r621-coremark-unskipped-1721-qemu-preflight/traces/qemu.live.expected.preview.jsonl"
)
DEFAULT_QEMU_ONLY_RAW = (
    ROOT_DIR
    / "generated/r621-coremark-unskipped-1721-qemu-preflight/traces/qemu.live.raw.jsonl"
)
DEFAULT_QEMU_ONLY_CANDIDATES = (
    ROOT_DIR
    / "generated/r621-coremark-unskipped-1721-qemu-preflight/report/replay_liq_qemu_candidates.json"
)
DEFAULT_RTL_MANIFEST = (
    ROOT_DIR / "generated/r621-coremark-unskipped-1721-rtl-xcheck/report/crosscheck_manifest.json"
)
DEFAULT_RTL_SIDEBAND = (
    ROOT_DIR
    / "generated/r621-coremark-unskipped-1721-rtl-xcheck/report/frontend_fetch_rf_alu_sideband_stats.json"
)
DEFAULT_RTL_PREVIEW = (
    ROOT_DIR
    / "generated/r621-coremark-unskipped-1721-rtl-xcheck/traces/qemu.live.expected.preview.jsonl"
)
DEFAULT_OUTPUT = (
    ROOT_DIR
    / "generated/r621-replay-liq-selector-unskipped-prefix-report/report/replay_liq_selector_unskipped_prefix_report.json"
)

ZERO_NATURAL_COUNTERS = (
    "wait_replay_capture_accepted",
    "replay_queue_out_fire",
    "liq_alloc_accepted",
    "lret_w2_slot_accepted",
    "w2_promotion_live",
    "w2_retire_record_physical_bundle_suppress_select_selected_from_promotion",
    "w2_retire_record_physical_bundle_suppress_select_selected_from_probe",
    "mdb_conflict_valid",
    "mdb_fanout_record_valid",
    "mdb_fanout_record_accepted",
    "mdb_lookup_wait_plan_wait_intent_valid",
    "resolve_queue_push_accepted",
)

ACTIVITY_CONTEXT_COUNTERS = (
    "load_lookup_valid",
    "load_lookup_execute_with_eligible_store",
    "load_lookup_execute_with_wait_store",
    "mdb_conflict_store_valid",
    "store_sta_dequeue_fire",
    "store_std_dequeue_fire",
    "store_stq_resident",
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


def read_jsonl(path: Path) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    with path.open("r", encoding="utf-8", errors="replace") as f:
        for line in f:
            text = line.strip()
            if not text:
                continue
            row = json.loads(text)
            if not isinstance(row, dict):
                raise ValueError(f"{path} contains a non-object JSONL row")
            rows.append(row)
    return rows


def hx(value: int) -> str:
    return hex(value)


def memory_events(rows: list[dict[str, Any]]) -> list[dict[str, Any]]:
    events: list[dict[str, Any]] = []
    for index, row in enumerate(rows):
        if row.get("mem_valid") != 1:
            continue
        pc = row.get("pc")
        addr = row.get("mem_addr")
        size = row.get("mem_size")
        if not isinstance(pc, int) or not isinstance(addr, int) or not isinstance(size, int):
            continue
        events.append(
            {
                "row": index,
                "pc": hx(pc),
                "addr": hx(addr),
                "size": size,
                "op": "store" if row.get("mem_is_store") == 1 else "load",
            }
        )
    return events


def target_pair(rows: list[dict[str, Any]]) -> dict[str, Any]:
    store: dict[str, Any] | None = None
    load: dict[str, Any] | None = None
    for event in memory_events(rows):
        if (
            event["op"] == "store"
            and event["pc"] == hx(TARGET_STORE_PC)
            and event["addr"] == hx(TARGET_ADDR)
            and event["size"] == TARGET_SIZE
        ):
            store = event
        if (
            event["op"] == "load"
            and event["pc"] == hx(TARGET_LOAD_PC)
            and event["addr"] == hx(TARGET_ADDR)
            and event["size"] == TARGET_SIZE
        ):
            load = event
    present = store is not None and load is not None
    return {
        "present": present,
        "store": store,
        "load": load,
        "row_distance": (load["row"] - store["row"]) if present else None,
    }


def first_candidate(candidate_report: dict[str, Any]) -> dict[str, Any]:
    candidates = candidate_report.get("top_candidates")
    if not isinstance(candidates, list) or not candidates:
        return {}
    candidate = candidates[0]
    return dict(candidate) if isinstance(candidate, dict) else {}


def sideband_counters(sideband: dict[str, Any], keys: tuple[str, ...]) -> dict[str, int | None]:
    replay = sideband.get("replay_liq")
    if not isinstance(replay, dict):
        return {key: None for key in keys}
    out: dict[str, int | None] = {}
    for key in keys:
        value = replay.get(key)
        out[key] = value if isinstance(value, int) else None
    return out


def manifest_summary(manifest: dict[str, Any]) -> dict[str, Any]:
    summary = manifest.get("summary")
    return dict(summary) if isinstance(summary, dict) else {}


def cbstop_clean(summary: dict[str, Any]) -> bool:
    counts = summary.get("cbstop_counts")
    return isinstance(counts, dict) and counts.get("qemu") == 0 and counts.get("dut") == 0


def build_report(args: argparse.Namespace) -> dict[str, Any]:
    qemu_only_raw = read_jsonl(args.qemu_only_raw)
    qemu_only_preview = read_jsonl(args.qemu_only_preview)
    qemu_only_candidates = load_json(args.qemu_only_candidates)
    rtl_manifest = load_json(args.rtl_manifest)
    rtl_sideband = load_json(args.rtl_sideband)
    rtl_preview = read_jsonl(args.rtl_preview)

    qemu_pair = target_pair(qemu_only_preview)
    rtl_pair = target_pair(rtl_preview)
    summary = manifest_summary(rtl_manifest)
    zero_counters = sideband_counters(rtl_sideband, ZERO_NATURAL_COUNTERS)
    context_counters = sideband_counters(rtl_sideband, ACTIVITY_CONTEXT_COUNTERS)
    zero_natural = all(isinstance(value, int) and value == 0 for value in zero_counters.values())
    candidate = first_candidate(qemu_only_candidates)

    generated_rtl_status = "pass_no_regression_with_candidate_present"
    proof_kind = (
        "no_regression_with_candidate_present_not_replay_proof"
        if zero_natural
        else "candidate_present_with_replay_sideband_activity"
    )

    return {
        "schema": SCHEMA,
        "status": "pass",
        "claim_boundary": (
            "The unskipped CoreMark prefix reaches the R617 store/load candidate and the "
            "matching generated RTL/QEMU reduced prefix passes. Because replay-LIQ, "
            "selector, and MDB fanout counters remain zero, this is no-regression "
            "coverage with a candidate present, not natural replay-LIQ replacement proof."
        ),
        "target": {
            "store_pc": hx(TARGET_STORE_PC),
            "load_pc": hx(TARGET_LOAD_PC),
            "address": hx(TARGET_ADDR),
            "size": TARGET_SIZE,
        },
        "qemu_only_unskipped_prefix": {
            "status": "pass" if qemu_pair["present"] else "fail",
            "raw_trace": str(args.qemu_only_raw),
            "preview": str(args.qemu_only_preview),
            "candidate_report": str(args.qemu_only_candidates),
            "raw_rows": len(qemu_only_raw),
            "preview_rows": len(qemu_only_preview),
            "target_pair": qemu_pair,
            "candidate_count": qemu_only_candidates.get("candidate_count"),
            "first_candidate": candidate,
        },
        "generated_rtl_unskipped_prefix": {
            "status": generated_rtl_status,
            "proof_kind": proof_kind,
            "manifest": str(args.rtl_manifest),
            "sideband_report": str(args.rtl_sideband),
            "preview": str(args.rtl_preview),
            "manifest_status": rtl_manifest.get("status"),
            "summary": summary,
            "target_pair": rtl_pair,
            "replay_liq_activity": "zero-natural-replay" if zero_natural else "nonzero-replay-liq-or-mdb",
            "required_zero_sideband": zero_counters,
            "activity_context_sideband": context_counters,
        },
    }


def require_passed_manifest(section: dict[str, Any], errors: list[str]) -> None:
    if section.get("manifest_status") != "pass":
        errors.append(f"generated_rtl_unskipped_prefix: manifest_status is {section.get('manifest_status')!r}")
    summary = section.get("summary")
    if not isinstance(summary, dict):
        errors.append("generated_rtl_unskipped_prefix: missing summary")
        return
    compared = summary.get("compared_rows")
    mismatches = summary.get("mismatch_count")
    if not isinstance(compared, int) or compared <= 0:
        errors.append(f"generated_rtl_unskipped_prefix: compared_rows must be positive, got {compared!r}")
    if mismatches != 0:
        errors.append(f"generated_rtl_unskipped_prefix: mismatch_count must be zero, got {mismatches!r}")
    if not cbstop_clean(summary):
        errors.append("generated_rtl_unskipped_prefix: QEMU/DUT CBSTOP counts must be zero")


def validate_report(report: dict[str, Any]) -> list[str]:
    errors: list[str] = []
    if report.get("schema") != SCHEMA:
        errors.append("report: unexpected schema")
    if report.get("status") != "pass":
        errors.append(f"report: status is {report.get('status')!r}, expected 'pass'")
    boundary = report.get("claim_boundary")
    if not isinstance(boundary, str) or "not natural replay-LIQ replacement proof" not in boundary:
        errors.append("report: claim_boundary must preserve the no-replay-proof boundary")

    qemu = report.get("qemu_only_unskipped_prefix")
    if not isinstance(qemu, dict):
        errors.append("qemu_only_unskipped_prefix: missing section")
    else:
        if qemu.get("status") != "pass":
            errors.append("qemu_only_unskipped_prefix: expected pass")
        if qemu.get("raw_rows") != 1721:
            errors.append(f"qemu_only_unskipped_prefix: raw_rows expected 1721, got {qemu.get('raw_rows')!r}")
        pair = qemu.get("target_pair")
        if not isinstance(pair, dict) or not pair.get("present"):
            errors.append("qemu_only_unskipped_prefix: target pair must be present")
        first = qemu.get("first_candidate")
        if not isinstance(first, dict) or first.get("score") != 1186:
            errors.append("qemu_only_unskipped_prefix: first candidate must be the R617 target")

    rtl = report.get("generated_rtl_unskipped_prefix")
    if not isinstance(rtl, dict):
        errors.append("generated_rtl_unskipped_prefix: missing section")
    else:
        if rtl.get("status") != "pass_no_regression_with_candidate_present":
            errors.append("generated_rtl_unskipped_prefix: unexpected status")
        if rtl.get("proof_kind") != "no_regression_with_candidate_present_not_replay_proof":
            errors.append("generated_rtl_unskipped_prefix: proof kind must remain no-regression only")
        require_passed_manifest(rtl, errors)
        pair = rtl.get("target_pair")
        if not isinstance(pair, dict) or not pair.get("present"):
            errors.append("generated_rtl_unskipped_prefix: target pair must be present in preview")
        counters = rtl.get("required_zero_sideband")
        if not isinstance(counters, dict):
            errors.append("generated_rtl_unskipped_prefix: missing required_zero_sideband")
        else:
            for key in ZERO_NATURAL_COUNTERS:
                value = counters.get(key)
                if not isinstance(value, int):
                    errors.append(f"generated_rtl_unskipped_prefix: {key} is not an integer")
                elif value != 0:
                    errors.append(f"generated_rtl_unskipped_prefix: {key} must be zero, got {value}")
    return errors


def sample_report() -> dict[str, Any]:
    return {
        "schema": SCHEMA,
        "status": "pass",
        "claim_boundary": "candidate present, not natural replay-LIQ replacement proof",
        "qemu_only_unskipped_prefix": {
            "status": "pass",
            "raw_rows": 1721,
            "target_pair": {"present": True},
            "first_candidate": {"score": 1186},
        },
        "generated_rtl_unskipped_prefix": {
            "status": "pass_no_regression_with_candidate_present",
            "proof_kind": "no_regression_with_candidate_present_not_replay_proof",
            "manifest_status": "pass",
            "summary": {"compared_rows": 1169, "mismatch_count": 0, "cbstop_counts": {"qemu": 0, "dut": 0}},
            "target_pair": {"present": True},
            "required_zero_sideband": {key: 0 for key in ZERO_NATURAL_COUNTERS},
        },
    }


def run_self_test() -> None:
    passing = sample_report()
    errors = validate_report(passing)
    if errors:
        raise AssertionError(f"valid sample report failed validation: {errors}")
    failing = copy.deepcopy(passing)
    failing["generated_rtl_unskipped_prefix"]["summary"]["mismatch_count"] = 1
    if not validate_report(failing):
        raise AssertionError("mismatch violation was not detected")
    failing = copy.deepcopy(passing)
    failing["generated_rtl_unskipped_prefix"]["required_zero_sideband"]["liq_alloc_accepted"] = 1
    if not validate_report(failing):
        raise AssertionError("nonzero replay-LIQ counter was not detected")
    failing = copy.deepcopy(passing)
    failing["qemu_only_unskipped_prefix"]["target_pair"]["present"] = False
    if not validate_report(failing):
        raise AssertionError("missing QEMU target pair was not detected")


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--qemu-only-preview", type=Path, default=DEFAULT_QEMU_ONLY_PREVIEW)
    parser.add_argument("--qemu-only-raw", type=Path, default=DEFAULT_QEMU_ONLY_RAW)
    parser.add_argument("--qemu-only-candidates", type=Path, default=DEFAULT_QEMU_ONLY_CANDIDATES)
    parser.add_argument("--rtl-manifest", type=Path, default=DEFAULT_RTL_MANIFEST)
    parser.add_argument("--rtl-sideband", type=Path, default=DEFAULT_RTL_SIDEBAND)
    parser.add_argument("--rtl-preview", type=Path, default=DEFAULT_RTL_PREVIEW)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--validate-only", type=Path, default=None)
    parser.add_argument("--self-test", action="store_true")
    return parser.parse_args(argv)


def main(argv: list[str]) -> int:
    args = parse_args(argv)
    if args.self_test:
        run_self_test()
        print("replay-liq-selector-unskipped-prefix-report self-test: ok")
        if args.validate_only is None and "--output" not in argv:
            return 0

    if args.validate_only is not None:
        report = load_json(args.validate_only)
        errors = validate_report(report)
        if errors:
            for error in errors:
                print(f"error: {error}", file=sys.stderr)
            return 1
        print(f"replay-liq-selector-unskipped-prefix-report: ok manifest={args.validate_only}")
        return 0

    report = build_report(args)
    errors = validate_report(report)
    if errors:
        for error in errors:
            print(f"error: {error}", file=sys.stderr)
        return 1
    write_json(args.output, report)
    print(f"replay-liq-selector-unskipped-prefix-report={args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
