#!/usr/bin/env python3
"""Build a replay-LIQ selector preflight report.

This report records QEMU-only preflight results for the current CoreMark
candidate. It treats raw skipped windows as locator evidence and keeps
generated RTL blocked unless a non-skipped command shape is proven separately.
"""

from __future__ import annotations

import argparse
import copy
import json
import sys
from pathlib import Path
from typing import Any

from build_replay_liq_selector_context_pack import validate_manifest as validate_context_pack


ROOT_DIR = Path(__file__).resolve().parents[2]
SCHEMA = "linxcore.replay_liq_selector_preflight_report.v1"

DEFAULT_CONTEXT_PACK = (
    ROOT_DIR
    / "generated/r618-replay-liq-selector-context-pack/report/replay_liq_selector_context_pack.json"
)
DEFAULT_RAW_PREVIEW = (
    ROOT_DIR
    / "generated/r620-coremark-candidate-raw1715-qemu-preflight/traces/qemu.live.expected.preview.jsonl"
)
DEFAULT_RAW_TRACE = (
    ROOT_DIR
    / "generated/r620-coremark-candidate-raw1715-qemu-preflight/traces/qemu.live.raw.jsonl"
)
DEFAULT_PC_FILTER_TRACE = (
    ROOT_DIR
    / "generated/r620-coremark-candidate-pc-filter-qemu-preflight/traces/qemu.live.raw.jsonl"
)
DEFAULT_OUTPUT = (
    ROOT_DIR
    / "generated/r620-replay-liq-selector-preflight-report/report/replay_liq_selector_preflight_report.json"
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
    if not path.exists():
        return []
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


def memory_pcs(rows: list[dict[str, Any]]) -> dict[str, list[str]]:
    stores: list[str] = []
    loads: list[str] = []
    for row in rows:
        if row.get("mem_valid") != 1:
            continue
        pc = row.get("pc")
        if not isinstance(pc, int):
            continue
        if row.get("mem_is_store") == 1:
            stores.append(hex(pc))
        else:
            loads.append(hex(pc))
    return {"store_pcs": stores, "load_pcs": loads}


def candidate_hint(context_pack: dict[str, Any]) -> dict[str, Any]:
    section = context_pack.get("qemu_candidate_hint")
    first = section.get("first_candidate") if isinstance(section, dict) else None
    hint = first.get("probe_hint") if isinstance(first, dict) else None
    if not isinstance(hint, dict):
        raise ValueError("context pack does not contain qemu_candidate_hint.first_candidate.probe_hint")
    return hint


def expected_pcs(context_pack: dict[str, Any]) -> dict[str, list[str]]:
    expected = candidate_hint(context_pack).get("expected_memory_pcs")
    if not isinstance(expected, dict):
        raise ValueError("candidate hint does not contain expected_memory_pcs")
    stores = expected.get("store_pcs")
    loads = expected.get("load_pcs")
    if not isinstance(stores, list) or not isinstance(loads, list):
        raise ValueError("expected store/load PC lists must be present")
    return {
        "store_pcs": [str(item) for item in stores],
        "load_pcs": [str(item) for item in loads],
    }


def first_mem_addr(rows: list[dict[str, Any]]) -> str | None:
    for row in rows:
        if row.get("mem_valid") == 1 and isinstance(row.get("mem_addr"), int):
            return hex(row["mem_addr"])
    return None


def build_report(args: argparse.Namespace) -> dict[str, Any]:
    context_pack = load_json(args.context_pack)
    context_errors = validate_context_pack(context_pack)
    if context_errors:
        raise ValueError("invalid context pack: " + "; ".join(context_errors))

    expected = expected_pcs(context_pack)
    raw_rows = read_jsonl(args.raw_trace)
    raw_preview_rows = read_jsonl(args.raw_preview)
    pc_filter_rows = read_jsonl(args.pc_filter_trace)
    raw_preview_pcs = memory_pcs(raw_preview_rows)
    pc_filter_pcs = memory_pcs(pc_filter_rows)
    raw_pass = raw_preview_pcs == expected
    pc_filter_status = "empty" if not pc_filter_rows else ("pass" if pc_filter_pcs == expected else "expected_pc_mismatch")

    return {
        "schema": SCHEMA,
        "status": "pass" if raw_pass else "fail",
        "claim_boundary": (
            "Raw-window QEMU-only preflight can confirm the address-cluster candidate. "
            "It cannot authorize generated RTL because skipped rows cannot reconstruct DUT state. "
            "PC-filter preflight must pass expected memory PCs before a PC-filtered generated-RTL run is considered."
        ),
        "expected_memory_pcs": expected,
        "raw_window_qemu_only": {
            "status": "pass" if raw_pass else "fail",
            "raw_trace": str(args.raw_trace),
            "preview": str(args.raw_preview),
            "raw_rows": len(raw_rows),
            "preview_rows": len(raw_preview_rows),
            "memory_pcs": raw_preview_pcs,
            "first_memory_address": first_mem_addr(raw_preview_rows),
        },
        "pc_filter_qemu_only": {
            "status": pc_filter_status,
            "raw_trace": str(args.pc_filter_trace),
            "raw_rows": len(pc_filter_rows),
            "memory_pcs": pc_filter_pcs,
        },
        "generated_rtl": {
            "status": "blocked",
            "reason": (
                "Raw-window preflight uses --qemu-skip-rows, which is QEMU-only. "
                f"PC-filter preflight status is {pc_filter_status!r}, not a passing expected-memory-PC preflight."
            ),
        },
    }


def validate_report(report: dict[str, Any]) -> list[str]:
    errors: list[str] = []
    if report.get("schema") != SCHEMA:
        errors.append("report: unexpected schema")
    if report.get("status") != "pass":
        errors.append("report: status must be 'pass'")
    boundary = report.get("claim_boundary")
    if not isinstance(boundary, str) or "cannot authorize generated RTL" not in boundary:
        errors.append("report: claim_boundary must preserve generated-RTL block")
    raw = report.get("raw_window_qemu_only")
    if not isinstance(raw, dict) or raw.get("status") != "pass":
        errors.append("raw_window_qemu_only: expected pass")
    else:
        if raw.get("raw_rows") != 6:
            errors.append(f"raw_window_qemu_only: raw_rows expected 6, got {raw.get('raw_rows')!r}")
        if raw.get("preview_rows") != 5:
            errors.append(f"raw_window_qemu_only: preview_rows expected 5, got {raw.get('preview_rows')!r}")
    pc_filter = report.get("pc_filter_qemu_only")
    if not isinstance(pc_filter, dict):
        errors.append("pc_filter_qemu_only: missing section")
    elif pc_filter.get("status") not in {"empty", "expected_pc_mismatch", "pass"}:
        errors.append("pc_filter_qemu_only: unexpected status")
    generated = report.get("generated_rtl")
    if not isinstance(generated, dict) or generated.get("status") != "blocked":
        errors.append("generated_rtl: must remain blocked")
    return errors


def sample_report() -> dict[str, Any]:
    return {
        "schema": SCHEMA,
        "status": "pass",
        "claim_boundary": "Raw-window QEMU-only cannot authorize generated RTL.",
        "raw_window_qemu_only": {"status": "pass", "raw_rows": 6, "preview_rows": 5},
        "pc_filter_qemu_only": {"status": "empty"},
        "generated_rtl": {"status": "blocked"},
    }


def run_self_test() -> None:
    passing = sample_report()
    if validate_report(passing):
        raise AssertionError("valid sample report failed validation")
    failing = copy.deepcopy(passing)
    failing["generated_rtl"]["status"] = "ready"
    if not validate_report(failing):
        raise AssertionError("unblocked generated RTL was not detected")
    failing = copy.deepcopy(passing)
    failing["raw_window_qemu_only"]["raw_rows"] = 0
    if not validate_report(failing):
        raise AssertionError("raw-window row-count violation was not detected")


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--context-pack", type=Path, default=DEFAULT_CONTEXT_PACK)
    parser.add_argument("--raw-preview", type=Path, default=DEFAULT_RAW_PREVIEW)
    parser.add_argument("--raw-trace", type=Path, default=DEFAULT_RAW_TRACE)
    parser.add_argument("--pc-filter-trace", type=Path, default=DEFAULT_PC_FILTER_TRACE)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--validate-only", type=Path, default=None)
    parser.add_argument("--self-test", action="store_true")
    return parser.parse_args(argv)


def main(argv: list[str]) -> int:
    args = parse_args(argv)
    if args.self_test:
        run_self_test()
        print("replay-liq-selector-preflight-report self-test: ok")
        if args.validate_only is None and "--context-pack" not in argv and "--output" not in argv:
            return 0

    if args.validate_only is not None:
        report = load_json(args.validate_only)
        errors = validate_report(report)
        if errors:
            for error in errors:
                print(f"error: {error}", file=sys.stderr)
            return 1
        print(f"replay-liq-selector-preflight-report: ok manifest={args.validate_only}")
        return 0

    report = build_report(args)
    errors = validate_report(report)
    if errors:
        for error in errors:
            print(f"error: {error}", file=sys.stderr)
        return 1
    write_json(args.output, report)
    print(f"replay-liq-selector-preflight-report={args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
