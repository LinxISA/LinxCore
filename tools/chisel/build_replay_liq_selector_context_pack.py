#!/usr/bin/env python3
"""Build and validate the current replay-LIQ selector context pack.

This manifest intentionally keeps three evidence classes separate:

* a CoreMark generated-RTL/QEMU no-regression prefix with zero natural
  replay-LIQ activity,
* a focused replay fixture with positive selector-origin sideband proof, and
* a QEMU-only candidate hint that may guide future stateful CoreMark work.
"""

from __future__ import annotations

import argparse
import copy
import json
import subprocess
import sys
from pathlib import Path
from typing import Any


ROOT_DIR = Path(__file__).resolve().parents[2]

SCHEMA = "linxcore.replay_liq_selector_context_pack.v1"
EVIDENCE_KIND = "coremark_zero_natural_plus_focused_selector_origin_plus_qemu_hint"

COREMARK_ZERO_COUNTERS = (
    "wait_replay_capture_accepted",
    "replay_queue_out_fire",
    "liq_alloc_accepted",
    "lret_w2_slot_accepted",
    "w2_promotion_live",
    "w2_retire_record_physical_bundle_suppress_select_selected_from_promotion",
    "w2_retire_record_physical_bundle_suppress_select_selected_from_probe",
    "mdb_conflict_valid",
    "mdb_fanout_record_valid",
    "mdb_lookup_wait_plan_wait_intent_valid",
)

FOCUSED_NONZERO_COUNTERS = (
    "wait_replay_capture_accepted",
    "replay_queue_out_fire",
    "liq_alloc_accepted",
    "lret_w2_slot_accepted",
    "w2_promotion_live",
    "w2_retire_record_physical_bundle_suppress_plan_atomic_suppress_candidate",
    "w2_retire_record_physical_bundle_suppress_select_selected",
    "w2_retire_record_physical_bundle_suppress_select_selected_from_promotion",
    "w2_retire_record_physical_bundle_suppress_boundary_capture",
    "w2_retire_record_physical_bundle_suppress_ownership_eligible_registered_mask",
    "w2_retire_record_physical_bundle_suppress_live_mask_enabled_candidate",
    "w2_retire_record_physical_bundle_suppress_clear_proof_all_clear_aligned",
)

FOCUSED_ZERO_COUNTERS = (
    "w2_retire_record_physical_bundle_suppress_probe_selected",
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


def git_sha(path: Path) -> str:
    try:
        return subprocess.check_output(
            ["git", "-C", str(path), "rev-parse", "HEAD"],
            text=True,
            stderr=subprocess.DEVNULL,
        ).strip()
    except subprocess.CalledProcessError:
        return "unknown"


def crosscheck_summary(manifest: dict[str, Any]) -> dict[str, Any]:
    summary = manifest.get("summary")
    return dict(summary) if isinstance(summary, dict) else {}


def replay_liq_sideband(sideband: dict[str, Any], keys: tuple[str, ...]) -> dict[str, int | None]:
    replay = sideband.get("replay_liq")
    if not isinstance(replay, dict):
        return {key: None for key in keys}
    out: dict[str, int | None] = {}
    for key in keys:
        value = replay.get(key)
        out[key] = value if isinstance(value, int) else None
    return out


def first_candidate(candidate_report: dict[str, Any]) -> dict[str, Any]:
    candidates = candidate_report.get("top_candidates")
    if not isinstance(candidates, list) or not candidates:
        return {}
    candidate = candidates[0]
    return dict(candidate) if isinstance(candidate, dict) else {}


def build_manifest(args: argparse.Namespace) -> dict[str, Any]:
    coremark_manifest = load_json(args.coremark_manifest)
    coremark_sideband = load_json(args.coremark_sideband)
    focused_manifest = load_json(args.focused_manifest)
    focused_sideband = load_json(args.focused_sideband)
    candidate_report = load_json(args.candidate_report)
    candidate = first_candidate(candidate_report)

    return {
        "schema": SCHEMA,
        "status": "pass",
        "evidence_kind": EVIDENCE_KIND,
        "claim_boundary": (
            "CoreMark prefix is zero-natural-replay no-regression evidence; "
            "focused replay fixture is positive selector-origin proof; "
            "QEMU candidate report is an address-cluster hint only, not DUT proof."
        ),
        "coremark_zero_natural": {
            "manifest": str(args.coremark_manifest),
            "sideband_report": str(args.coremark_sideband),
            "status": coremark_manifest.get("status"),
            "summary": crosscheck_summary(coremark_manifest),
            "required_zero_sideband": replay_liq_sideband(coremark_sideband, COREMARK_ZERO_COUNTERS),
        },
        "focused_selector_origin": {
            "manifest": str(args.focused_manifest),
            "sideband_report": str(args.focused_sideband),
            "status": focused_manifest.get("status"),
            "summary": crosscheck_summary(focused_manifest),
            "required_nonzero_sideband": replay_liq_sideband(focused_sideband, FOCUSED_NONZERO_COUNTERS),
            "required_zero_sideband": replay_liq_sideband(focused_sideband, FOCUSED_ZERO_COUNTERS),
        },
        "qemu_candidate_hint": {
            "candidate_report": str(args.candidate_report),
            "status": "hint",
            "row_space": candidate_report.get("row_space"),
            "candidate_count": candidate_report.get("candidate_count"),
            "first_candidate": candidate,
        },
        "git": {
            "linxcore": git_sha(ROOT_DIR),
            "linxcore_model": git_sha(ROOT_DIR.parent.parent / "model/LinxCoreModel"),
            "qemu": git_sha(ROOT_DIR.parent.parent / "emulator/qemu"),
            "superproject": git_sha(ROOT_DIR.parent.parent),
        },
    }


def require_passed_crosscheck(name: str, section: Any) -> list[str]:
    errors: list[str] = []
    if not isinstance(section, dict):
        return [f"{name}: missing section"]
    if section.get("status") != "pass":
        errors.append(f"{name}: status is {section.get('status')!r}, expected 'pass'")
    summary = section.get("summary")
    if not isinstance(summary, dict):
        return errors + [f"{name}: missing summary"]
    compared = summary.get("compared_rows")
    mismatches = summary.get("mismatch_count")
    if not isinstance(compared, int) or compared <= 0:
        errors.append(f"{name}: compared_rows must be positive, got {compared!r}")
    if mismatches != 0:
        errors.append(f"{name}: mismatch_count must be zero, got {mismatches!r}")
    return errors


def check_counter_map(
    section_name: str,
    label: str,
    values: Any,
    keys: tuple[str, ...],
    *,
    require_nonzero: bool,
) -> list[str]:
    if not isinstance(values, dict):
        return [f"{section_name}: missing {label}"]
    errors: list[str] = []
    for key in keys:
        value = values.get(key)
        if not isinstance(value, int):
            errors.append(f"{section_name}: {label}.{key} is not an integer")
        elif require_nonzero and value <= 0:
            errors.append(f"{section_name}: {label}.{key} must be nonzero, got {value}")
        elif not require_nonzero and value != 0:
            errors.append(f"{section_name}: {label}.{key} must be zero, got {value}")
    return errors


def validate_manifest(manifest: dict[str, Any]) -> list[str]:
    errors: list[str] = []
    if manifest.get("schema") != SCHEMA:
        errors.append("manifest: unexpected schema")
    if manifest.get("status") != "pass":
        errors.append(f"manifest: status is {manifest.get('status')!r}, expected 'pass'")
    if manifest.get("evidence_kind") != EVIDENCE_KIND:
        errors.append("manifest: unexpected evidence_kind")
    boundary = manifest.get("claim_boundary")
    if not isinstance(boundary, str) or "zero-natural-replay" not in boundary or "address-cluster hint" not in boundary:
        errors.append("manifest: claim_boundary must preserve evidence separation")

    coremark = manifest.get("coremark_zero_natural")
    focused = manifest.get("focused_selector_origin")
    candidate = manifest.get("qemu_candidate_hint")
    errors.extend(require_passed_crosscheck("coremark_zero_natural", coremark))
    errors.extend(require_passed_crosscheck("focused_selector_origin", focused))

    if isinstance(coremark, dict):
        errors.extend(
            check_counter_map(
                "coremark_zero_natural",
                "required_zero_sideband",
                coremark.get("required_zero_sideband"),
                COREMARK_ZERO_COUNTERS,
                require_nonzero=False,
            )
        )
    if isinstance(focused, dict):
        errors.extend(
            check_counter_map(
                "focused_selector_origin",
                "required_nonzero_sideband",
                focused.get("required_nonzero_sideband"),
                FOCUSED_NONZERO_COUNTERS,
                require_nonzero=True,
            )
        )
        errors.extend(
            check_counter_map(
                "focused_selector_origin",
                "required_zero_sideband",
                focused.get("required_zero_sideband"),
                FOCUSED_ZERO_COUNTERS,
                require_nonzero=False,
            )
        )

    if not isinstance(candidate, dict):
        errors.append("qemu_candidate_hint: missing section")
    else:
        if candidate.get("status") != "hint":
            errors.append("qemu_candidate_hint: status must be 'hint'")
        count = candidate.get("candidate_count")
        if not isinstance(count, int) or count <= 0:
            errors.append(f"qemu_candidate_hint: candidate_count must be positive, got {count!r}")
        first = candidate.get("first_candidate")
        if not isinstance(first, dict) or not first:
            errors.append("qemu_candidate_hint: missing first_candidate")
        else:
            hint = first.get("probe_hint")
            raw_window = hint.get("raw_dynamic_window") if isinstance(hint, dict) else None
            expected_pcs = hint.get("expected_memory_pcs") if isinstance(hint, dict) else None
            if not isinstance(raw_window, dict):
                errors.append("qemu_candidate_hint: missing probe_hint.raw_dynamic_window")
            else:
                skip = raw_window.get("qemu_skip_rows")
                capture = raw_window.get("capture_rows")
                if not isinstance(skip, int) or skip < 0:
                    errors.append(f"qemu_candidate_hint: qemu_skip_rows must be non-negative, got {skip!r}")
                if not isinstance(capture, int) or capture <= 0:
                    errors.append(f"qemu_candidate_hint: capture_rows must be positive, got {capture!r}")
            if not isinstance(expected_pcs, dict) or not expected_pcs.get("store_pcs") or not expected_pcs.get("load_pcs"):
                errors.append("qemu_candidate_hint: expected memory PC lists must be present")
    return errors


def sample_manifest() -> dict[str, Any]:
    coremark_zero = {key: 0 for key in COREMARK_ZERO_COUNTERS}
    focused_nonzero = {key: 1 for key in FOCUSED_NONZERO_COUNTERS}
    focused_zero = {key: 0 for key in FOCUSED_ZERO_COUNTERS}
    return {
        "schema": SCHEMA,
        "status": "pass",
        "evidence_kind": EVIDENCE_KIND,
        "claim_boundary": (
            "CoreMark prefix is zero-natural-replay no-regression evidence; "
            "focused replay fixture is positive selector-origin proof; "
            "QEMU candidate report is an address-cluster hint only, not DUT proof."
        ),
        "coremark_zero_natural": {
            "status": "pass",
            "summary": {"compared_rows": 4, "mismatch_count": 0},
            "required_zero_sideband": coremark_zero,
        },
        "focused_selector_origin": {
            "status": "pass",
            "summary": {"compared_rows": 4, "mismatch_count": 0},
            "required_nonzero_sideband": focused_nonzero,
            "required_zero_sideband": focused_zero,
        },
        "qemu_candidate_hint": {
            "status": "hint",
            "candidate_count": 1,
            "first_candidate": {
                "probe_hint": {
                    "raw_dynamic_window": {"qemu_skip_rows": 1, "capture_rows": 2},
                    "expected_memory_pcs": {"store_pcs": ["0x10"], "load_pcs": ["0x14"]},
                }
            },
        },
    }


def run_self_test() -> None:
    passing = sample_manifest()
    if validate_manifest(passing):
        raise AssertionError("valid sample manifest failed validation")
    failing = copy.deepcopy(passing)
    failing["coremark_zero_natural"]["required_zero_sideband"]["liq_alloc_accepted"] = 1
    if not validate_manifest(failing):
        raise AssertionError("CoreMark nonzero violation was not detected")
    failing = copy.deepcopy(passing)
    failing["focused_selector_origin"]["required_nonzero_sideband"]["w2_promotion_live"] = 0
    if not validate_manifest(failing):
        raise AssertionError("focused nonzero violation was not detected")
    failing = copy.deepcopy(passing)
    del failing["qemu_candidate_hint"]["first_candidate"]["probe_hint"]["raw_dynamic_window"]
    if not validate_manifest(failing):
        raise AssertionError("missing raw candidate hint was not detected")


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--coremark-manifest",
        type=Path,
        default=ROOT_DIR / "generated/r611-coremark-promoted-selector-observation-4096-xcheck/report/crosscheck_manifest.json",
        help="CoreMark no-regression crosscheck manifest",
    )
    parser.add_argument(
        "--coremark-sideband",
        type=Path,
        default=ROOT_DIR / "generated/r611-coremark-promoted-selector-observation-4096-xcheck/report/frontend_fetch_rf_alu_sideband_stats.json",
        help="CoreMark no-regression sideband stats",
    )
    parser.add_argument(
        "--focused-manifest",
        type=Path,
        default=ROOT_DIR / "generated/r616-replay-suppress-preset-xcheck/report/crosscheck_manifest.json",
        help="focused selector-origin crosscheck manifest",
    )
    parser.add_argument(
        "--focused-sideband",
        type=Path,
        default=ROOT_DIR / "generated/r616-replay-suppress-preset-xcheck/report/frontend_fetch_rf_alu_sideband_stats.json",
        help="focused selector-origin sideband stats",
    )
    parser.add_argument(
        "--candidate-report",
        type=Path,
        default=ROOT_DIR / "generated/r617-coremark-qemu-memory-candidate-hints/replay_liq_qemu_candidates_with_raw_hints.json",
        help="QEMU candidate report with raw-window hints",
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=ROOT_DIR / "generated/r618-replay-liq-selector-context-pack/report/replay_liq_selector_context_pack.json",
        help="output manifest path",
    )
    parser.add_argument("--validate-only", type=Path, default=None, help="validate an existing context-pack manifest")
    parser.add_argument("--self-test", action="store_true", help="run built-in validator checks")
    return parser.parse_args(argv)


def main(argv: list[str]) -> int:
    args = parse_args(argv)
    if args.self_test:
        run_self_test()
        print("replay-liq-selector-context-pack self-test: ok")
        if args.validate_only is None and not any(
            flag in argv
            for flag in (
                "--coremark-manifest",
                "--coremark-sideband",
                "--focused-manifest",
                "--focused-sideband",
                "--candidate-report",
                "--output",
            )
        ):
            return 0

    if args.validate_only is not None:
        manifest = load_json(args.validate_only)
        errors = validate_manifest(manifest)
        if errors:
            for error in errors:
                print(f"error: {error}", file=sys.stderr)
            return 1
        print(f"replay-liq-selector-context-pack: ok manifest={args.validate_only}")
        return 0

    manifest = build_manifest(args)
    errors = validate_manifest(manifest)
    if errors:
        for error in errors:
            print(f"error: {error}", file=sys.stderr)
        return 1
    write_json(args.output, manifest)
    print(f"replay-liq-selector-context-pack-report={args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
