#!/usr/bin/env python3
"""Scan generated replay-LIQ sideband artifacts for activation coverage.

This is a cheap pre-Verilator triage tool. It does not create new proof; it
classifies existing generated artifacts so the next packet can avoid
rediscovering whether positive eligible-store activation is currently limited
to focused fixtures or already present in CoreMark/natural workload runs.
"""

from __future__ import annotations

import argparse
import copy
import json
import sys
from pathlib import Path
from typing import Any


ROOT_DIR = Path(__file__).resolve().parents[2]
SCHEMA = "linxcore.replay_liq_activation_artifact_scan.v1"
DEFAULT_GENERATED_DIR = ROOT_DIR / "generated"
DEFAULT_OUTPUT = (
    ROOT_DIR
    / "generated/r624-replay-liq-activation-artifact-scan/report/replay_liq_activation_artifact_scan.json"
)

REQUIRED_ACTIVATION_COUNTERS = (
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

SUMMARY_COUNTERS = (
    "load_lookup_valid",
    "store_stq_resident",
    "store_sta_dequeue_fire",
    "store_std_dequeue_fire",
    *REQUIRED_ACTIVATION_COUNTERS,
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


def counter_map(sideband: dict[str, Any]) -> dict[str, int | None]:
    replay = sideband.get("replay_liq")
    if not isinstance(replay, dict):
        return {key: None for key in SUMMARY_COUNTERS}
    out: dict[str, int | None] = {}
    for key in SUMMARY_COUNTERS:
        value = replay.get(key)
        out[key] = value if isinstance(value, int) else None
    return out


def positive(counters: dict[str, int | None], key: str) -> bool:
    value = counters.get(key)
    return isinstance(value, int) and value > 0


def classify_artifact(build_dir: Path) -> str:
    name = build_dir.name.lower()
    if "coremark" in name:
        return "coremark"
    if "fixture" in name or "replay" in name or "liq" in name:
        return "focused_or_synthetic"
    return "other"


def manifest_summary(manifest_path: Path) -> dict[str, Any]:
    if not manifest_path.exists():
        return {"present": False}
    manifest = load_json(manifest_path)
    summary = manifest.get("summary")
    cbstop = summary.get("cbstop_counts") if isinstance(summary, dict) else None
    return {
        "present": True,
        "status": manifest.get("status"),
        "comparator_status": manifest.get("comparator_status"),
        "compared_rows": summary.get("compared_rows") if isinstance(summary, dict) else None,
        "mismatch_count": summary.get("mismatch_count") if isinstance(summary, dict) else None,
        "cbstop_qemu": cbstop.get("qemu") if isinstance(cbstop, dict) else None,
        "cbstop_dut": cbstop.get("dut") if isinstance(cbstop, dict) else None,
        "git": manifest.get("git", {}),
    }


def scan_artifact(sideband_path: Path, generated_dir: Path) -> dict[str, Any]:
    sideband = load_json(sideband_path)
    counters = counter_map(sideband)
    build_dir = sideband_path.parent.parent
    activation_positive = all(positive(counters, key) for key in REQUIRED_ACTIVATION_COUNTERS)
    return {
        "build_dir": str(build_dir),
        "relative_build_dir": str(build_dir.relative_to(ROOT_DIR)),
        "kind": classify_artifact(build_dir),
        "activation_positive": activation_positive,
        "counters": counters,
        "manifest": manifest_summary(build_dir / "report/crosscheck_manifest.json"),
    }


def build_report(args: argparse.Namespace) -> dict[str, Any]:
    generated_dir = args.generated_dir
    sideband_paths = sorted(generated_dir.glob("*/report/frontend_fetch_rf_alu_sideband_stats.json"))
    artifacts = [scan_artifact(path, generated_dir) for path in sideband_paths]
    positive_artifacts = [artifact for artifact in artifacts if artifact["activation_positive"]]
    coremark_positive = [
        artifact for artifact in positive_artifacts if artifact.get("kind") == "coremark"
    ]
    focused_positive = [
        artifact for artifact in positive_artifacts if artifact.get("kind") == "focused_or_synthetic"
    ]
    return {
        "schema": SCHEMA,
        "status": "pass",
        "claim_boundary": (
            "This scan classifies existing generated sideband artifacts only. It is not a new "
            "generated-RTL proof and must not replace a fresh manifest-backed workload run."
        ),
        "inputs": {"generated_dir": str(generated_dir)},
        "summary": {
            "artifact_count": len(artifacts),
            "activation_positive_count": len(positive_artifacts),
            "coremark_positive_count": len(coremark_positive),
            "focused_or_synthetic_positive_count": len(focused_positive),
        },
        "positive_artifacts": positive_artifacts,
        "artifacts": artifacts,
    }


def validate_report(report: dict[str, Any]) -> list[str]:
    errors: list[str] = []
    if report.get("schema") != SCHEMA:
        errors.append("report: unexpected schema")
    if report.get("status") != "pass":
        errors.append(f"report: status is {report.get('status')!r}, expected 'pass'")
    boundary = report.get("claim_boundary")
    if not isinstance(boundary, str) or "not a new generated-RTL proof" not in boundary:
        errors.append("report: claim_boundary must preserve scan-vs-proof boundary")
    artifacts = report.get("artifacts")
    positives = report.get("positive_artifacts")
    summary = report.get("summary")
    if not isinstance(artifacts, list):
        errors.append("artifacts: missing section")
        artifacts = []
    if not isinstance(positives, list):
        errors.append("positive_artifacts: missing section")
        positives = []
    if not isinstance(summary, dict):
        errors.append("summary: missing section")
        summary = {}
    positive_count = sum(1 for artifact in artifacts if artifact.get("activation_positive") is True)
    coremark_count = sum(
        1
        for artifact in artifacts
        if artifact.get("activation_positive") is True and artifact.get("kind") == "coremark"
    )
    focused_count = sum(
        1
        for artifact in artifacts
        if artifact.get("activation_positive") is True
        and artifact.get("kind") == "focused_or_synthetic"
    )
    if summary.get("artifact_count") != len(artifacts):
        errors.append("summary: artifact_count mismatch")
    if summary.get("activation_positive_count") != positive_count:
        errors.append("summary: activation_positive_count mismatch")
    if summary.get("coremark_positive_count") != coremark_count:
        errors.append("summary: coremark_positive_count mismatch")
    if summary.get("focused_or_synthetic_positive_count") != focused_count:
        errors.append("summary: focused_or_synthetic_positive_count mismatch")
    if len(positives) != positive_count:
        errors.append("positive_artifacts: count mismatch")
    for artifact in positives:
        counters = artifact.get("counters")
        if not isinstance(counters, dict):
            errors.append(f"{artifact.get('relative_build_dir')}: missing counters")
            continue
        for key in REQUIRED_ACTIVATION_COUNTERS:
            if not positive(counters, key):
                errors.append(f"{artifact.get('relative_build_dir')}: {key} must be positive")
    return errors


def sample_report() -> dict[str, Any]:
    positive_artifact = {
        "relative_build_dir": "generated/sample-focused",
        "kind": "focused_or_synthetic",
        "activation_positive": True,
        "counters": {key: 1 for key in SUMMARY_COUNTERS},
        "manifest": {"present": True, "status": "pass"},
    }
    coremark_artifact = {
        "relative_build_dir": "generated/sample-coremark",
        "kind": "coremark",
        "activation_positive": False,
        "counters": {key: 0 for key in SUMMARY_COUNTERS},
        "manifest": {"present": True, "status": "pass"},
    }
    return {
        "schema": SCHEMA,
        "status": "pass",
        "claim_boundary": "scanner only; not a new generated-RTL proof",
        "summary": {
            "artifact_count": 2,
            "activation_positive_count": 1,
            "coremark_positive_count": 0,
            "focused_or_synthetic_positive_count": 1,
        },
        "positive_artifacts": [positive_artifact],
        "artifacts": [coremark_artifact, positive_artifact],
    }


def run_self_test() -> None:
    passing = sample_report()
    errors = validate_report(passing)
    if errors:
        raise AssertionError(f"valid sample report failed validation: {errors}")
    failing = copy.deepcopy(passing)
    failing["summary"]["activation_positive_count"] = 2
    if not validate_report(failing):
        raise AssertionError("summary count mismatch was not detected")
    failing = copy.deepcopy(passing)
    failing["positive_artifacts"][0]["counters"]["resolve_queue_push_accepted"] = 0
    if not validate_report(failing):
        raise AssertionError("missing positive activation counter was not detected")


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--generated-dir", type=Path, default=DEFAULT_GENERATED_DIR)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--validate-only", type=Path, default=None)
    parser.add_argument("--self-test", action="store_true")
    return parser.parse_args(argv)


def main(argv: list[str]) -> int:
    args = parse_args(argv)
    if args.self_test:
        run_self_test()
        print("replay-liq-activation-artifact-scan self-test: ok")
        if args.validate_only is None and "--output" not in argv:
            return 0

    if args.validate_only is not None:
        report = load_json(args.validate_only)
        errors = validate_report(report)
        if errors:
            for error in errors:
                print(f"error: {error}", file=sys.stderr)
            return 1
        print(f"replay-liq-activation-artifact-scan: ok manifest={args.validate_only}")
        return 0

    report = build_report(args)
    errors = validate_report(report)
    if errors:
        for error in errors:
            print(f"error: {error}", file=sys.stderr)
        return 1
    write_json(args.output, report)
    print(f"replay-liq-activation-artifact-scan={args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
