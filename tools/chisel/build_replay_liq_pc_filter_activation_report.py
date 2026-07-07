#!/usr/bin/env python3
"""Build the R626 replay-LIQ PC-filter activation attempt report.

The report deliberately separates a passing QEMU-only PC-filter preflight from
the generated-RTL attempt. The latter failed in the top harness before the
common crosscheck manifest was emitted, so it is failure evidence rather than
replay-LIQ activation proof.
"""

from __future__ import annotations

import argparse
import copy
import json
import re
import sys
from pathlib import Path
from typing import Any


ROOT_DIR = Path(__file__).resolve().parents[2]
SCHEMA = "linxcore.replay_liq_pc_filter_activation_attempt.v1"

DEFAULT_PREFLIGHT = (
    ROOT_DIR
    / "generated/r626-replay-liq-pc-filter-preflight-search-v2/report/pc_filter_preflight_search.json"
)
DEFAULT_RTL_DIR = ROOT_DIR / "generated/r626-coremark-pc-filter-4000d7e6-4000d7f2-rtl-xcheck-abs"
DEFAULT_OUTPUT = (
    ROOT_DIR
    / "generated/r626-replay-liq-pc-filter-activation-report/report/replay_liq_pc_filter_activation_report.json"
)

MISMATCH_RE = re.compile(
    r"frontend fetch RF ALU trace top dst/wb mismatch "
    r"expected_pc=(?P<expected_pc>0x[0-9a-fA-F]+) "
    r"observed_pc=(?P<observed_pc>0x[0-9a-fA-F]+) "
    r"expected_insn=(?P<expected_insn>0x[0-9a-fA-F]+) "
    r"observed_insn=(?P<observed_insn>0x[0-9a-fA-F]+) "
    r"expected=\((?P<expected_rd>\d+),(?P<expected_value>\d+)\) "
    r"observed_dst=\((?P<observed_dst_rd>\d+),(?P<observed_dst_value>\d+)\) "
    r"observed_wb=\((?P<observed_wb_rd>\d+),(?P<observed_wb_value>\d+)\)"
)


def load_json(path: Path) -> dict[str, Any]:
    with path.open("r", encoding="utf-8") as f:
        data = json.load(f)
    if not isinstance(data, dict):
        raise ValueError(f"{path} did not contain a JSON object")
    return data


def load_jsonl_first(path: Path) -> dict[str, Any] | None:
    if not path.exists():
        return None
    with path.open("r", encoding="utf-8", errors="replace") as f:
        for line in f:
            if line.strip():
                data = json.loads(line)
                if isinstance(data, dict):
                    return data
                raise ValueError(f"{path} first JSONL row was not an object")
    return None


def write_json(path: Path, data: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def count_rows(path: Path) -> int:
    if not path.exists():
        return 0
    with path.open("r", encoding="utf-8", errors="replace") as f:
        return sum(1 for line in f if line.strip())


def read_text(path: Path) -> str:
    if not path.exists():
        return ""
    return path.read_text(encoding="utf-8", errors="replace")


def parse_mismatch(stderr: str) -> dict[str, Any] | None:
    match = MISMATCH_RE.search(stderr)
    if match is None:
        return None
    groups = match.groupdict()
    numeric = {
        key: int(value)
        for key, value in groups.items()
        if key
        in {
            "expected_rd",
            "expected_value",
            "observed_dst_rd",
            "observed_dst_value",
            "observed_wb_rd",
            "observed_wb_value",
        }
    }
    out: dict[str, Any] = {key: value for key, value in groups.items() if key not in numeric}
    out.update(numeric)
    return out


def build_report(args: argparse.Namespace) -> dict[str, Any]:
    preflight = load_json(args.preflight_report)
    first_pass = preflight.get("summary", {}).get("first_pass")
    if not isinstance(first_pass, dict):
        first_pass = None

    report_dir = args.rtl_build_dir / "report"
    trace_dir = args.rtl_build_dir / "traces"
    stdout_path = report_dir / "generated-rtl-attempt.stdout.txt"
    stderr_path = report_dir / "generated-rtl-attempt.stderr.txt"
    stderr = read_text(stderr_path)
    stdout = read_text(stdout_path)
    mismatch = parse_mismatch(stderr)

    manifest_path = report_dir / "crosscheck_manifest.json"
    sideband_path = report_dir / "frontend_fetch_rf_alu_sideband_stats.json"
    preview_path = trace_dir / "qemu.live.expected.preview.jsonl"
    raw_trace_path = trace_dir / "qemu.live.raw.jsonl"
    dut_trace_path = trace_dir / "dut.chisel.jsonl"
    qemu_reference_path = trace_dir / "qemu.reference.jsonl"

    return {
        "schema": SCHEMA,
        "status": "failed",
        "claim_boundary": (
            "R626 found a QEMU-only PC-filter preflight that passed exact memory-PC "
            "guards, but the generated-RTL attempt failed before crosscheck manifest "
            "generation. This is not replay-LIQ activation proof and must not be "
            "used as natural CoreMark replacement evidence."
        ),
        "preflight": {
            "report": str(args.preflight_report),
            "status": preflight.get("status"),
            "generated_rtl_status": preflight.get("generated_rtl", {}).get("status"),
            "first_pass": first_pass,
        },
        "generated_rtl_attempt": {
            "build_dir": str(args.rtl_build_dir),
            "status": "failed_before_manifest",
            "stdout": str(stdout_path),
            "stderr": str(stderr_path),
            "manifest": {
                "path": str(manifest_path),
                "exists": manifest_path.exists(),
            },
            "sideband": {
                "path": str(sideband_path),
                "exists": sideband_path.exists(),
            },
            "trace_rows": {
                "qemu_live_raw": count_rows(raw_trace_path),
                "qemu_live_expected_preview": count_rows(preview_path),
                "dut_chisel": count_rows(dut_trace_path),
                "qemu_reference": count_rows(qemu_reference_path),
            },
            "first_expected_preview_row": load_jsonl_first(preview_path),
            "verilator_built": "V e r i l a t i o n   R e p o r t" in stdout,
            "mismatch": mismatch,
        },
        "next_actions": [
            "Do not promote R626 as generated-RTL replay-LIQ activation proof.",
            "Classify the first-row dst/wb mismatch before rerunning wider CoreMark PC filters.",
            "Continue with checkpoint/state replay, a legal natural workload shard, or a PC-filter shape whose generated-RTL first row matches QEMU.",
        ],
        "skill_evolve": {
            "decision": "no-update",
            "reason": (
                "The packet adds repo-local automation and records a failed PC-filter "
                "launch boundary; it does not change a reusable LinxCore "
                "microarchitecture invariant or mandatory external skill rule."
            ),
        },
    }


def validate_report(report: dict[str, Any]) -> list[str]:
    errors: list[str] = []
    if report.get("schema") != SCHEMA:
        errors.append("report: unexpected schema")
    if report.get("status") != "failed":
        errors.append("report: R626 activation report must remain failed")
    boundary = report.get("claim_boundary")
    if not isinstance(boundary, str) or "not replay-LIQ activation proof" not in boundary:
        errors.append("report: claim boundary must reject activation proof")

    preflight = report.get("preflight")
    if not isinstance(preflight, dict):
        errors.append("preflight: missing section")
        preflight = {}
    if preflight.get("status") != "pass":
        errors.append("preflight: expected QEMU-only search pass")
    if preflight.get("generated_rtl_status") != "ready":
        errors.append("preflight: expected generated_rtl ready authorization")

    rtl = report.get("generated_rtl_attempt")
    if not isinstance(rtl, dict):
        errors.append("generated_rtl_attempt: missing section")
        rtl = {}
    if rtl.get("status") != "failed_before_manifest":
        errors.append("generated_rtl_attempt: expected failed_before_manifest")
    if rtl.get("manifest", {}).get("exists") is not False:
        errors.append("generated_rtl_attempt: manifest must be absent for this failure")
    if rtl.get("sideband", {}).get("exists") is not False:
        errors.append("generated_rtl_attempt: sideband must be absent for this failure")
    rows = rtl.get("trace_rows", {})
    if not isinstance(rows, dict):
        errors.append("generated_rtl_attempt: missing trace rows")
        rows = {}
    if rows.get("qemu_live_raw", 0) <= 0 or rows.get("qemu_live_expected_preview", 0) <= 0:
        errors.append("generated_rtl_attempt: expected QEMU raw and preview rows")
    if rows.get("dut_chisel") != 0:
        errors.append("generated_rtl_attempt: DUT JSONL should be empty after harness mismatch")
    mismatch = rtl.get("mismatch")
    if not isinstance(mismatch, dict):
        errors.append("generated_rtl_attempt: missing parsed mismatch")
    else:
        if mismatch.get("expected_pc") != mismatch.get("observed_pc"):
            errors.append("generated_rtl_attempt: expected/observed PC should match in this failure")
        if mismatch.get("expected_value") == mismatch.get("observed_dst_value"):
            errors.append("generated_rtl_attempt: mismatch values unexpectedly match")
    return errors


def sample_report() -> dict[str, Any]:
    return {
        "schema": SCHEMA,
        "status": "failed",
        "claim_boundary": "not replay-LIQ activation proof",
        "preflight": {"status": "pass", "generated_rtl_status": "ready"},
        "generated_rtl_attempt": {
            "status": "failed_before_manifest",
            "manifest": {"exists": False},
            "sideband": {"exists": False},
            "trace_rows": {
                "qemu_live_raw": 6,
                "qemu_live_expected_preview": 5,
                "dut_chisel": 0,
            },
            "mismatch": {
                "expected_pc": "0x4000d7e6",
                "observed_pc": "0x4000d7e6",
                "expected_value": 1342110568,
                "observed_dst_value": 18446744073709551608,
            },
        },
    }


def run_self_test() -> None:
    passing = sample_report()
    errors = validate_report(passing)
    if errors:
        raise AssertionError(f"valid sample failed validation: {errors}")
    failing = copy.deepcopy(passing)
    failing["generated_rtl_attempt"]["manifest"]["exists"] = True
    if not validate_report(failing):
        raise AssertionError("manifest-present failure was not detected")
    failing = copy.deepcopy(passing)
    failing["generated_rtl_attempt"]["mismatch"]["observed_dst_value"] = 1342110568
    if not validate_report(failing):
        raise AssertionError("matching dst/wb value was not detected")


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--preflight-report", type=Path, default=DEFAULT_PREFLIGHT)
    parser.add_argument("--rtl-build-dir", type=Path, default=DEFAULT_RTL_DIR)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--validate-only", type=Path, default=None)
    parser.add_argument("--self-test", action="store_true")
    return parser.parse_args(argv)


def main(argv: list[str]) -> int:
    args = parse_args(argv)
    if args.self_test:
        run_self_test()
        print("replay-liq-pc-filter-activation-report self-test: ok")
        if args.validate_only is None:
            return 0

    if args.validate_only is not None:
        report = load_json(args.validate_only)
        errors = validate_report(report)
        if errors:
            for error in errors:
                print(f"error: {error}", file=sys.stderr)
            return 1
        print(f"replay-liq-pc-filter-activation-report: ok manifest={args.validate_only}")
        return 0

    report = build_report(args)
    errors = validate_report(report)
    if errors:
        for error in errors:
            print(f"error: {error}", file=sys.stderr)
        return 1
    write_json(args.output, report)
    print(f"replay-liq-pc-filter-activation-report={args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
