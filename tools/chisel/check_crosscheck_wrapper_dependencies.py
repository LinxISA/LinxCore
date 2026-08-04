#!/usr/bin/env python3
"""Validate the single canonical Chisel cross-check wrapper closure."""

from __future__ import annotations

import re
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
DOCS_ROOT = ROOT / "docs/chisel"
HISTORICAL_DOC_ARCHIVES = {
    DOCS_ROOT / "agent-loop.md",
    DOCS_ROOT / "verification/phase1-evidence.md",
    DOCS_ROOT / "verification/phase2-evidence.md",
    DOCS_ROOT / "verification/phase5-prep-evidence.md",
}
SUPPORTED_CANONICAL_ARGS = {"--dry-run", "--check-dependencies"}


def canonical_invocation_errors(path: Path, source: str) -> list[str]:
    """Return active-doc canonical commands outside the supported CLI surface."""
    errors: list[str] = []
    lines = source.splitlines()
    canonical_name = CANONICAL.name
    for index, line in enumerate(lines):
        if canonical_name not in line:
            continue
        before, after = line.split(canonical_name, 1)
        inside_code_span = before.count("`") % 2 == 1
        if inside_code_span:
            before = before.rsplit("`", 1)[1]
            after = after.split("`", 1)[0]
        command_like = (
            "bash " in before or
            "tools/chisel/" in before or
            not before.strip() or
            after.lstrip().startswith("--")
        )
        if not command_like:
            continue

        options = re.findall(r"(?:^|\s)(--[a-z0-9-]+)", after)
        if (len(options) > 1 or
                any(option not in SUPPORTED_CANONICAL_ARGS for option in options)):
            errors.append(f"{path.relative_to(ROOT)}:{index + 1}:unsupported arguments")
        if after.rstrip().endswith("\\"):
            errors.append(f"{path.relative_to(ROOT)}:{index + 1}:continued arguments")

        command_prefix = before
        prior = index - 1
        while prior >= 0 and lines[prior].rstrip().endswith("\\"):
            command_prefix += " " + lines[prior]
            prior -= 1
        assignments = re.findall(r"(?:^|\s)([A-Z][A-Z0-9_]*)=", command_prefix)
        unsupported_env = sorted(set(assignments) - {"BUILD_DIR"})
        if unsupported_env:
            errors.append(
                f"{path.relative_to(ROOT)}:{index + 1}:unsupported environment " +
                ",".join(unsupported_env))
    return errors


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

    active_docs = [
        path for path in sorted(DOCS_ROOT.rglob("*.md"))
        if path not in HISTORICAL_DOC_ARCHIVES
    ]
    forbidden_docs: list[str] = []
    unsupported_canonical_docs: list[str] = []
    for path in active_docs:
        source = path.read_text(encoding="utf-8")
        for name in DELETED_WRAPPERS:
            if name in source:
                forbidden_docs.append(f"{path.relative_to(ROOT)}:{name}")
        unsupported_canonical_docs.extend(canonical_invocation_errors(path, source))
    if forbidden_docs:
        fail("active documentation names deleted wrappers: " + ", ".join(forbidden_docs))
    if unsupported_canonical_docs:
        fail("active documentation uses unsupported canonical commands: " +
             ", ".join(unsupported_canonical_docs))

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
    print(f"active-docs-checked={len(active_docs)}")
    print(f"historical-doc-archives-excluded={len(HISTORICAL_DOC_ARCHIVES)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
