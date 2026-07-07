#!/usr/bin/env python3
"""Plan safe replay-LIQ selector CoreMark probe commands from a context pack.

The planner deliberately refuses to emit a generated-RTL command from a raw
QEMU row hint. Skipped rows are QEMU-only in the current wrapper because the
reduced Verilator top cannot reconstruct skipped architectural state.
"""

from __future__ import annotations

import argparse
import copy
import json
import sys
from pathlib import Path
from typing import Any

from build_replay_liq_selector_context_pack import SCHEMA as CONTEXT_SCHEMA
from build_replay_liq_selector_context_pack import validate_manifest as validate_context_pack


ROOT_DIR = Path(__file__).resolve().parents[2]
SCHEMA = "linxcore.replay_liq_selector_probe_plan.v1"

DEFAULT_CONTEXT_PACK = (
    ROOT_DIR
    / "generated/r618-replay-liq-selector-context-pack/report/replay_liq_selector_context_pack.json"
)
DEFAULT_OUTPUT = (
    ROOT_DIR
    / "generated/r619-replay-liq-selector-probe-plan/report/replay_liq_selector_probe_plan.json"
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


def candidate_hint(context_pack: dict[str, Any]) -> dict[str, Any]:
    section = context_pack.get("qemu_candidate_hint")
    first = section.get("first_candidate") if isinstance(section, dict) else None
    hint = first.get("probe_hint") if isinstance(first, dict) else None
    if not isinstance(hint, dict):
        raise ValueError("context pack does not contain qemu_candidate_hint.first_candidate.probe_hint")
    return hint


def first_str(values: Any, label: str) -> str:
    if not isinstance(values, list) or not values:
        raise ValueError(f"missing {label}")
    value = values[0]
    if not isinstance(value, str) or not value:
        raise ValueError(f"invalid {label}")
    return value


def qemu_base_args() -> list[str]:
    return [
        "-nographic",
        "-monitor",
        "none",
        "-machine",
        "virt",
        "-m",
        "1280M",
        "-kernel",
        "tests/benchmarks/build/coremark_real.elf",
    ]


def build_plan(context_pack: dict[str, Any]) -> dict[str, Any]:
    context_errors = validate_context_pack(context_pack)
    if context_errors:
        raise ValueError("invalid context pack: " + "; ".join(context_errors))
    if context_pack.get("schema") != CONTEXT_SCHEMA:
        raise ValueError("unexpected context pack schema")

    hint = candidate_hint(context_pack)
    raw_window = hint.get("raw_dynamic_window")
    pc_filter = hint.get("pc_filter")
    expected = hint.get("expected_memory_pcs")
    if not isinstance(raw_window, dict) or not isinstance(pc_filter, dict) or not isinstance(expected, dict):
        raise ValueError("candidate hint must include raw window, PC filter, and expected memory PCs")

    skip_rows = raw_window.get("qemu_skip_rows")
    capture_rows = raw_window.get("capture_rows")
    pc_lo = pc_filter.get("pc_lo")
    pc_hi = pc_filter.get("pc_hi", pc_filter.get("pc_hi_exclusive"))
    store_pc = first_str(expected.get("store_pcs"), "expected store PC")
    load_pc = first_str(expected.get("load_pcs"), "expected load PC")
    if not isinstance(skip_rows, int) or skip_rows < 0:
        raise ValueError("raw window qemu_skip_rows must be a non-negative integer")
    if not isinstance(capture_rows, int) or capture_rows <= 0:
        raise ValueError("raw window capture_rows must be a positive integer")
    if not isinstance(pc_lo, str) or not isinstance(pc_hi, str):
        raise ValueError("PC filter bounds must be strings")

    raw_build_dir = f"generated/r619-coremark-candidate-raw{skip_rows}-qemu-preflight"
    pc_build_dir = "generated/r619-coremark-candidate-pc-filter-qemu-preflight"
    raw_command = [
        "bash",
        "tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh",
        "--build-dir",
        raw_build_dir,
        "--elf",
        "tests/benchmarks/build/coremark_real.elf",
        "--expected-rows",
        "0",
        "--capture-rows",
        str(capture_rows),
        "--qemu-skip-rows",
        str(skip_rows),
        "--allow-block-markers",
        "--allow-block-loop-reentry",
        "--qemu-only",
        "--expect-store-pcs",
        store_pc,
        "--expect-load-pcs",
        load_pc,
        "--max-seconds",
        "45",
        "--",
        *qemu_base_args(),
    ]
    pc_filter_command = [
        "bash",
        "tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh",
        "--build-dir",
        pc_build_dir,
        "--elf",
        "tests/benchmarks/build/coremark_real.elf",
        "--expected-rows",
        "0",
        "--capture-rows",
        "8",
        "--pc-lo",
        pc_lo,
        "--pc-hi",
        pc_hi,
        "--allow-block-markers",
        "--allow-block-loop-reentry",
        "--qemu-only",
        "--expect-store-pcs",
        store_pc,
        "--expect-load-pcs",
        load_pc,
        "--max-seconds",
        "45",
        "--",
        *qemu_base_args(),
    ]

    return {
        "schema": SCHEMA,
        "status": "planned",
        "source_context_pack_schema": context_pack.get("schema"),
        "claim_boundary": (
            "This plan emits QEMU-only preflights from the R618 address-cluster hint. "
            "It intentionally withholds a generated-RTL command until the exact "
            "generated-RTL command shape has a passing QEMU-only expected-memory-PC preflight."
        ),
        "candidate": {
            "raw_window": {"qemu_skip_rows": skip_rows, "capture_rows": capture_rows},
            "pc_filter": {"pc_lo": pc_lo, "pc_hi": pc_hi},
            "expected_memory_pcs": {"store_pc": store_pc, "load_pc": load_pc},
        },
        "commands": {
            "raw_window_qemu_only_preflight": raw_command,
            "pc_filter_qemu_only_preflight": pc_filter_command,
            "generated_rtl": {
                "status": "blocked",
                "reason": (
                    "--qemu-skip-rows is QEMU-only, and the known PC-filter preflight for this "
                    "candidate reaches an earlier dynamic occurrence without the expected load."
                ),
            },
        },
    }


def validate_plan(plan: dict[str, Any]) -> list[str]:
    errors: list[str] = []
    if plan.get("schema") != SCHEMA:
        errors.append("plan: unexpected schema")
    if plan.get("status") != "planned":
        errors.append("plan: status must be 'planned'")
    boundary = plan.get("claim_boundary")
    if not isinstance(boundary, str) or "QEMU-only preflights" not in boundary or "withholds a generated-RTL command" not in boundary:
        errors.append("plan: claim_boundary must preserve the QEMU-only gate")
    commands = plan.get("commands")
    if not isinstance(commands, dict):
        return errors + ["plan: missing commands"]
    raw = commands.get("raw_window_qemu_only_preflight")
    pc = commands.get("pc_filter_qemu_only_preflight")
    generated = commands.get("generated_rtl")
    for label, command in (("raw_window_qemu_only_preflight", raw), ("pc_filter_qemu_only_preflight", pc)):
        if not isinstance(command, list) or "--qemu-only" not in command:
            errors.append(f"commands.{label}: must be a QEMU-only command list")
        if isinstance(command, list) and "--expect-load-pcs" not in command:
            errors.append(f"commands.{label}: missing expected load PC guard")
        if isinstance(command, list) and "--expect-store-pcs" not in command:
            errors.append(f"commands.{label}: missing expected store PC guard")
    if not isinstance(generated, dict) or generated.get("status") != "blocked":
        errors.append("commands.generated_rtl: must remain blocked")
    return errors


def sample_context_pack() -> dict[str, Any]:
    from build_replay_liq_selector_context_pack import sample_manifest

    sample = sample_manifest()
    hint = sample["qemu_candidate_hint"]["first_candidate"]["probe_hint"]
    hint["pc_filter"] = {"pc_lo": "0x10", "pc_hi_exclusive": "0x18"}
    return sample


def run_self_test() -> None:
    plan = build_plan(sample_context_pack())
    if validate_plan(plan):
        raise AssertionError("valid sample plan failed validation")
    failing = copy.deepcopy(plan)
    failing["commands"]["generated_rtl"]["status"] = "ready"
    if not validate_plan(failing):
        raise AssertionError("unblocked generated-RTL command was not detected")
    failing = copy.deepcopy(plan)
    failing["commands"]["raw_window_qemu_only_preflight"].remove("--qemu-only")
    if not validate_plan(failing):
        raise AssertionError("missing QEMU-only guard was not detected")


def shell_quote(argv: list[str]) -> str:
    import shlex

    return " ".join(shlex.quote(arg) for arg in argv)


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--context-pack", type=Path, default=DEFAULT_CONTEXT_PACK)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--validate-only", type=Path, default=None)
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument("--print-commands", action="store_true")
    return parser.parse_args(argv)


def main(argv: list[str]) -> int:
    args = parse_args(argv)
    if args.self_test:
        run_self_test()
        print("replay-liq-selector-probe-plan self-test: ok")
        if args.validate_only is None and not args.print_commands and "--context-pack" not in argv and "--output" not in argv:
            return 0

    if args.validate_only is not None:
        plan = load_json(args.validate_only)
        errors = validate_plan(plan)
        if errors:
            for error in errors:
                print(f"error: {error}", file=sys.stderr)
            return 1
        print(f"replay-liq-selector-probe-plan: ok manifest={args.validate_only}")
        return 0

    plan = build_plan(load_json(args.context_pack))
    errors = validate_plan(plan)
    if errors:
        for error in errors:
            print(f"error: {error}", file=sys.stderr)
        return 1
    write_json(args.output, plan)
    print(f"replay-liq-selector-probe-plan-report={args.output}")
    if args.print_commands:
        commands = plan["commands"]
        print("raw-window-qemu-only:")
        print(shell_quote(commands["raw_window_qemu_only_preflight"]))
        print("pc-filter-qemu-only:")
        print(shell_quote(commands["pc_filter_qemu_only_preflight"]))
        print("generated-rtl: blocked")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
