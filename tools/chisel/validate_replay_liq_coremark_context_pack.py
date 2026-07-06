#!/usr/bin/env python3
"""Validate replay-LIQ CoreMark context-pack evidence boundaries."""

from __future__ import annotations

import argparse
import copy
import json
import sys
from pathlib import Path
from typing import Any


COREMARK_ZERO_COUNTERS = (
    "wait_replay_capture_accepted",
    "replay_queue_out_fire",
    "liq_alloc_accepted",
    "mdb_conflict_valid",
    "mdb_lookup_wait_plan_request_valid",
    "liq_replay_wake_wait_store_clear",
)

CONSTRUCTED_NONZERO_COUNTERS = (
    "wait_replay_capture_accepted",
    "replay_queue_out_fire",
    "liq_alloc_accepted",
    "liq_base_lookup_granted",
    "resolve_queue_push_accepted",
    "mdb_conflict_valid",
    "mdb_fanout_record_processed",
    "mdb_fanout_lookup_table_hit",
    "mdb_lookup_wait_plan_request_valid",
    "liq_replay_wake_wait_store_clear",
)

CONSTRUCTED_ZERO_COUNTERS = ("mdb_fanout_su_wakeup_valid",)


def load_json(path: Path) -> dict[str, Any]:
    with path.open("r", encoding="utf-8") as f:
        data = json.load(f)
    if not isinstance(data, dict):
        raise ValueError(f"{path} did not contain a JSON object")
    return data


def summary_value(section: dict[str, Any], key: str) -> int:
    summary = section.get("summary")
    if not isinstance(summary, dict):
        raise AssertionError("missing summary object")
    value = summary.get(key)
    if not isinstance(value, int):
        raise AssertionError(f"summary.{key} is not an integer")
    return value


def sideband_value(section: dict[str, Any], key: str) -> int:
    sideband = section.get("sideband")
    if not isinstance(sideband, dict):
        raise AssertionError("missing sideband object")
    value = sideband.get(key)
    if value is None:
        manifest_path = section.get("manifest")
        if isinstance(manifest_path, str):
            raw_sideband_path = Path(manifest_path).with_name("frontend_fetch_rf_alu_sideband_stats.json")
            if raw_sideband_path.is_file():
                raw_sideband = load_json(raw_sideband_path).get("replay_liq", {})
                if isinstance(raw_sideband, dict):
                    value = raw_sideband.get(key)
    if not isinstance(value, int):
        raise AssertionError(f"sideband.{key} is not an integer")
    return value


def check_passed_section(name: str, section: Any) -> list[str]:
    errors: list[str] = []
    if not isinstance(section, dict):
        return [f"{name}: missing section object"]
    if section.get("status") != "pass":
        errors.append(f"{name}: status is {section.get('status')!r}, expected 'pass'")
    try:
        compared = summary_value(section, "compared_rows")
        mismatches = summary_value(section, "mismatch_count")
    except AssertionError as exc:
        errors.append(f"{name}: {exc}")
    else:
        if compared <= 0:
            errors.append(f"{name}: compared_rows must be positive")
        if mismatches != 0:
            errors.append(f"{name}: mismatch_count must be zero, got {mismatches}")
    return errors


def validate_manifest(manifest: dict[str, Any]) -> list[str]:
    errors: list[str] = []
    if manifest.get("schema") != "linxcore.replay_liq_coremark_context_pack.v1":
        errors.append("manifest: unexpected schema")
    if manifest.get("status") != "pass":
        errors.append(f"manifest: status is {manifest.get('status')!r}, expected 'pass'")
    boundary = manifest.get("claim_boundary")
    if not isinstance(boundary, str) or "CoreMark prefix" not in boundary or "constructed replay loop" not in boundary:
        errors.append("manifest: claim_boundary must name CoreMark prefix and constructed replay loop")
    if manifest.get("evidence_kind") != "coremark_context_pack_plus_constructed_replay_liq":
        errors.append("manifest: unexpected evidence_kind")

    coremark = manifest.get("coremark_prefix")
    constructed = manifest.get("constructed_replay_loop")
    errors.extend(check_passed_section("coremark_prefix", coremark))
    errors.extend(check_passed_section("constructed_replay_loop", constructed))
    if not isinstance(coremark, dict) or not isinstance(constructed, dict):
        return errors

    allow_natural = bool(manifest.get("natural_coremark_replay_allowed", False))
    if not allow_natural:
        for counter in COREMARK_ZERO_COUNTERS:
            try:
                value = sideband_value(coremark, counter)
            except AssertionError as exc:
                errors.append(f"coremark_prefix: {exc}")
                continue
            if value != 0:
                errors.append(f"coremark_prefix: {counter} must be zero, got {value}")

    for counter in CONSTRUCTED_NONZERO_COUNTERS:
        try:
            value = sideband_value(constructed, counter)
        except AssertionError as exc:
            errors.append(f"constructed_replay_loop: {exc}")
            continue
        if value <= 0:
            errors.append(f"constructed_replay_loop: {counter} must be nonzero, got {value}")

    for counter in CONSTRUCTED_ZERO_COUNTERS:
        try:
            value = sideband_value(constructed, counter)
        except AssertionError as exc:
            errors.append(f"constructed_replay_loop: {exc}")
            continue
        if value != 0:
            errors.append(f"constructed_replay_loop: {counter} must be zero, got {value}")

    return errors


def sample_manifest() -> dict[str, Any]:
    sideband_zero = {key: 0 for key in COREMARK_ZERO_COUNTERS}
    constructed_sideband = {key: 1 for key in CONSTRUCTED_NONZERO_COUNTERS}
    constructed_sideband["mdb_fanout_su_wakeup_valid"] = 0
    return {
        "schema": "linxcore.replay_liq_coremark_context_pack.v1",
        "status": "pass",
        "evidence_kind": "coremark_context_pack_plus_constructed_replay_liq",
        "claim_boundary": (
            "CoreMark prefix is no-regression evidence; "
            "constructed replay loop is positive LIQ/MDB evidence."
        ),
        "natural_coremark_replay_allowed": False,
        "coremark_prefix": {
            "status": "pass",
            "summary": {"compared_rows": 4, "mismatch_count": 0},
            "sideband": sideband_zero,
        },
        "constructed_replay_loop": {
            "status": "pass",
            "summary": {"compared_rows": 3, "mismatch_count": 0},
            "sideband": constructed_sideband,
        },
    }


def run_self_test() -> None:
    passing = sample_manifest()
    if validate_manifest(passing):
        raise AssertionError("valid sample manifest failed validation")
    failing = copy.deepcopy(passing)
    failing["coremark_prefix"]["sideband"]["liq_alloc_accepted"] = 1
    if not validate_manifest(failing):
        raise AssertionError("coremark natural replay violation was not detected")
    failing = copy.deepcopy(passing)
    failing["constructed_replay_loop"]["sideband"]["mdb_conflict_valid"] = 0
    if not validate_manifest(failing):
        raise AssertionError("constructed nonzero violation was not detected")
    failing = copy.deepcopy(passing)
    failing["constructed_replay_loop"]["sideband"]["mdb_fanout_su_wakeup_valid"] = 1
    if not validate_manifest(failing):
        raise AssertionError("constructed zero violation was not detected")


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("manifest", nargs="?", type=Path, help="R525 context-pack manifest JSON")
    parser.add_argument("--self-test", action="store_true", help="run built-in validator checks")
    return parser.parse_args(argv)


def main(argv: list[str]) -> int:
    args = parse_args(argv)
    if args.self_test:
        run_self_test()
        print("replay-liq-coremark-context-pack-validator self-test: ok")
    if args.manifest is None:
        return 0
    manifest = load_json(args.manifest)
    errors = validate_manifest(manifest)
    if errors:
        for error in errors:
            print(f"error: {error}", file=sys.stderr)
        return 1
    print(f"replay-liq-coremark-context-pack-validator: ok manifest={args.manifest}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
