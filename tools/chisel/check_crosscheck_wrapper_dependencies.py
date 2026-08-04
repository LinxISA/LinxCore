#!/usr/bin/env python3
"""Validate the single canonical Chisel cross-check wrapper closure."""

from __future__ import annotations

import subprocess
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
TOOLS = ROOT / "tools" / "chisel"
CANONICAL = TOOLS / "run_chisel_frontend_trace_top_xcheck.sh"
DELETED_WRAPPERS = (
    "run_chisel_top_xcheck.sh",
    "run_chisel_trace_replay_xcheck.sh",
    "run_chisel_qemu_trace_replay_xcheck.sh",
    "run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh",
    "run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh",
    "build_frontend_fetch_rf_alu_qemu_fixture_elf.sh",
)
DEPENDENCIES = (
    CANONICAL,
    TOOLS / "run_chisel_qemu_crosscheck.sh",
    TOOLS / "chisel_env.sh",
    ROOT / "chisel/src/main/scala/linxcore/top/LinxCoreFrontendTraceTop.scala",
    TOOLS / "frontend_trace_top_tb.cpp",
    TOOLS / "trace_schema_adapter.py",
)
ACTIVE_DOCS = (
    ROOT / "docs/chisel/README.md",
    ROOT / "docs/chisel/integrated-development-flow.md",
    ROOT / "docs/chisel/verification/chisel-flow.md",
    ROOT / "docs/chisel/verification/qemu-crosscheck.md",
    ROOT / "docs/chisel/modules/top/LinxCoreFrontendTraceTop.md",
)
ACTIVE_DOC_SECTIONS = (
    (
        ROOT / "docs/chisel/development-loop.md",
        "LinxCore adaptation:",
        "## Agent Loop",
    ),
    (
        ROOT / "docs/chisel/development-loop.md",
        "## Cross-Check Ladder",
        "## Project Maintenance",
    ),
)


def fail(message: str) -> None:
    raise SystemExit(f"error: {message}")


def main() -> int:
    missing = [str(path.relative_to(ROOT)) for path in DEPENDENCIES if not path.is_file()]
    if missing:
        fail("missing canonical cross-check dependencies: " + ", ".join(missing))

    present_deleted = [name for name in DELETED_WRAPPERS if (TOOLS / name).exists()]
    if present_deleted:
        fail("deleted wrapper entrypoints still exist: " + ", ".join(present_deleted))

    for path in (CANONICAL, TOOLS / "run_chisel_qemu_crosscheck.sh", TOOLS / "chisel_env.sh"):
        result = subprocess.run(
            ["bash", "-n", str(path)], cwd=ROOT, text=True,
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=False)
        if result.returncode != 0:
            fail(f"shell syntax failed for {path.relative_to(ROOT)}: {result.stderr.strip()}")

    emitter = DEPENDENCIES[3].read_text(encoding="utf-8")
    if "object EmitLinxCoreFrontendTraceTop" not in emitter:
        fail("canonical frontend trace emitter object is absent")

    runner = CANONICAL.read_text(encoding="utf-8")
    for token in (
        "linxcore.top.EmitLinxCoreFrontendTraceTop",
        "LinxCoreFrontendTraceTop.sv",
        "frontend_trace_top_tb.cpp",
        "run_chisel_qemu_crosscheck.sh",
    ):
        if token not in runner:
            fail(f"canonical frontend trace runner does not name {token}")

    forbidden_docs: list[str] = []
    for path in ACTIVE_DOCS:
        source = path.read_text(encoding="utf-8")
        for name in DELETED_WRAPPERS:
            if name in source:
                forbidden_docs.append(f"{path.relative_to(ROOT)}:{name}")
    for path, start_marker, end_marker in ACTIVE_DOC_SECTIONS:
        source = path.read_text(encoding="utf-8")
        active = source.split(start_marker, 1)[1].split(end_marker, 1)[0]
        for name in DELETED_WRAPPERS:
            if name in active:
                forbidden_docs.append(f"{path.relative_to(ROOT)}:{name}")
    if forbidden_docs:
        fail("active documentation names deleted wrappers: " + ", ".join(forbidden_docs))

    dry_run = subprocess.run(
        ["bash", str(CANONICAL), "--dry-run"], cwd=ROOT, text=True,
        stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=False)
    if dry_run.returncode != 0:
        fail("canonical dry-run failed: " + (dry_run.stdout + dry_run.stderr).strip())
    for token in ("emitter=", "top=", "harness=", "crosscheck=", "--mode failfast"):
        if token not in dry_run.stdout:
            fail(f"canonical dry-run does not validate {token}")

    print("crosscheck-wrapper-dependencies=pass")
    print("canonical-emitter=linxcore.top.EmitLinxCoreFrontendTraceTop")
    print("canonical-top=LinxCoreFrontendTraceTop")
    print("canonical-dry-run=pass")
    return 0


if __name__ == "__main__":
    sys.exit(main())
