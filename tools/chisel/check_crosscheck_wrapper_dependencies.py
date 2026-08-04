#!/usr/bin/env python3
"""Validate the retained Chisel cross-check wrapper dependency closure."""

from __future__ import annotations

import subprocess
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
CHISEL_TOOLS = ROOT / "tools" / "chisel"

SHELL_DEPENDENCIES = (
    "run_chisel_top_xcheck.sh",
    "run_chisel_trace_replay_xcheck.sh",
    "run_chisel_qemu_trace_replay_xcheck.sh",
    "run_chisel_frontend_trace_top_xcheck.sh",
    "run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh",
    "run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh",
    "build_frontend_fetch_rf_alu_qemu_fixture_elf.sh",
    "run_chisel_qemu_crosscheck.sh",
    "chisel_env.sh",
)

FILE_DEPENDENCIES = (
    ROOT / "chisel/src/main/scala/linxcore/top/LinxCoreFrontendTraceTop.scala",
    CHISEL_TOOLS / "frontend_trace_top_tb.cpp",
    CHISEL_TOOLS / "trace_schema_adapter.py",
)

FORBIDDEN_ACTIVE_CONTRACTS = (
    "EmitLinxCoreTopXcheck",
    "LinxCoreTop.sv",
)


def fail(message: str) -> None:
    raise SystemExit(f"error: {message}")


def main() -> int:
    shell_paths = tuple(CHISEL_TOOLS / name for name in SHELL_DEPENDENCIES)
    missing = [str(path.relative_to(ROOT)) for path in (*shell_paths, *FILE_DEPENDENCIES) if not path.is_file()]
    if missing:
        fail("missing cross-check dependencies: " + ", ".join(missing))

    for path in shell_paths:
        result = subprocess.run(
            ["bash", "-n", str(path)],
            cwd=ROOT,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            check=False,
        )
        if result.returncode != 0:
            fail(f"shell syntax failed for {path.relative_to(ROOT)}: {result.stderr.strip()}")

    canonical_source = FILE_DEPENDENCIES[0].read_text(encoding="utf-8")
    if "object EmitLinxCoreFrontendTraceTop" not in canonical_source:
        fail("canonical frontend trace emitter object is absent")

    canonical_runner = (CHISEL_TOOLS / "run_chisel_frontend_trace_top_xcheck.sh").read_text(encoding="utf-8")
    for token in (
        "linxcore.top.EmitLinxCoreFrontendTraceTop",
        "LinxCoreFrontendTraceTop.sv",
        "frontend_trace_top_tb.cpp",
    ):
        if token not in canonical_runner:
            fail(f"canonical frontend trace runner does not name {token}")

    active_runners = (
        CHISEL_TOOLS / "run_chisel_top_xcheck.sh",
        CHISEL_TOOLS / "run_chisel_trace_replay_xcheck.sh",
        CHISEL_TOOLS / "run_chisel_qemu_trace_replay_xcheck.sh",
        CHISEL_TOOLS / "run_chisel_frontend_trace_top_xcheck.sh",
    )
    for path in active_runners:
        source = path.read_text(encoding="utf-8")
        forbidden = [token for token in FORBIDDEN_ACTIVE_CONTRACTS if token in source]
        if forbidden:
            fail(f"{path.relative_to(ROOT)} names deleted contracts: {', '.join(forbidden)}")

    print("crosscheck-wrapper-dependencies=pass")
    print("canonical-emitter=linxcore.top.EmitLinxCoreFrontendTraceTop")
    print("canonical-top=LinxCoreFrontendTraceTop")
    return 0


if __name__ == "__main__":
    sys.exit(main())
