#!/usr/bin/env python3
"""Validate the single canonical Chisel cross-check wrapper closure."""

from __future__ import annotations

import re
import shlex
import subprocess
import sys
from functools import lru_cache
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
HISTORICAL_START = "<!-- task15-historical-specialized-evidence:start -->"
HISTORICAL_END = "<!-- task15-historical-specialized-evidence:end -->"
HISTORICAL_DECLARATION = "no current runnable equivalent"
SOURCE_COMMIT = re.compile(r"\bsource commit ([0-9a-f]{40})\b")
RETAINED_ARTIFACT = re.compile(r"\bretained artifact ([A-Za-z0-9_./-]+)\b")
PROVENANCE_SOURCE_REVISION = "0f0f4665031a8655e81e467f8937b3acedbc9717"


@lru_cache(maxsize=None)
def blamed_deleted_wrappers(commit: str, path: Path) -> set[str] | None:
    result = subprocess.run(
        ["git", "blame", "-l", PROVENANCE_SOURCE_REVISION, "--",
         str(path.relative_to(ROOT))], cwd=ROOT,
        text=True, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, check=False)
    if result.returncode != 0:
        return None
    attributed = set()
    for line in result.stdout.splitlines():
        match = re.match(r"\^?([0-9a-f]{40})\s", line)
        if match is not None and match.group(1) == commit:
            attributed.update(name for name in DELETED_WRAPPERS if name in line)
    return attributed


def documentation_reference_errors(path: Path, source: str) -> tuple[list[str], int]:
    """Validate deleted-wrapper references are confined to honest evidence blocks."""
    errors: list[str] = []
    in_historical = False
    declaration_seen = False
    historical_source: list[str] = []
    block_count = 0
    marker = re.compile(
        f"({re.escape(HISTORICAL_START)}|{re.escape(HISTORICAL_END)})")

    def inspect_segment(segment: str, line_number: int) -> None:
        nonlocal declaration_seen
        if in_historical:
            historical_source.append(segment)
            if HISTORICAL_DECLARATION in segment:
                declaration_seen = True
        if not in_historical:
            for name in DELETED_WRAPPERS:
                if name in segment:
                    errors.append(f"{path.relative_to(ROOT)}:{line_number}:{name}")

    for line_number, line in enumerate(source.splitlines(), 1):
        offset = 0
        for match in marker.finditer(line):
            inspect_segment(line[offset:match.start()], line_number)
            token = match.group(0)
            if token == HISTORICAL_START:
                if in_historical:
                    errors.append(
                        f"{path.relative_to(ROOT)}:{line_number}:nested historical block")
                in_historical = True
                declaration_seen = False
                historical_source.clear()
                block_count += 1
            else:
                if not in_historical:
                    errors.append(
                        f"{path.relative_to(ROOT)}:{line_number}:orphan historical end")
                elif not declaration_seen:
                    errors.append(
                        f"{path.relative_to(ROOT)}:{line_number}:missing no-equivalent declaration")
                else:
                    block_source = "\n".join(historical_source)
                    commits = SOURCE_COMMIT.findall(block_source)
                    artifacts = RETAINED_ARTIFACT.findall(block_source)
                    if not commits and not artifacts:
                        errors.append(
                            f"{path.relative_to(ROOT)}:{line_number}:missing exact provenance")
                    deleted_in_block = {
                        name for name in DELETED_WRAPPERS if name in block_source}
                    committed_wrappers = set()
                    for commit in commits:
                        attributed = blamed_deleted_wrappers(commit, path)
                        if attributed is None:
                            errors.append(
                                f"{path.relative_to(ROOT)}:{line_number}:invalid source commit {commit}")
                        else:
                            committed_wrappers.update(attributed)
                    for artifact in artifacts:
                        artifact_path = (ROOT / artifact).resolve()
                        try:
                            artifact_path.relative_to(ROOT)
                        except ValueError:
                            errors.append(
                                f"{path.relative_to(ROOT)}:{line_number}:invalid retained artifact {artifact}")
                        else:
                            if not artifact_path.is_file():
                                errors.append(
                                    f"{path.relative_to(ROOT)}:{line_number}:missing retained artifact {artifact}")
                    if commits and not deleted_in_block.issubset(committed_wrappers):
                        errors.append(
                            f"{path.relative_to(ROOT)}:{line_number}:source commit lacks deleted command")
                in_historical = False
            offset = match.end()
        inspect_segment(line[offset:], line_number)
    if in_historical:
        errors.append(f"{path.relative_to(ROOT)}:unclosed historical block")
    return errors, block_count


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

        try:
            arguments = shlex.split(after)
        except ValueError:
            errors.append(f"{path.relative_to(ROOT)}:{index + 1}:invalid command syntax")
            continue
        if arguments not in ([], ["--dry-run"], ["--check-dependencies"]):
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
    historical_specialized_blocks = 0
    historical_specialized_docs = 0
    for path in active_docs:
        source = path.read_text(encoding="utf-8")
        reference_errors, block_count = documentation_reference_errors(path, source)
        forbidden_docs.extend(reference_errors)
        historical_specialized_blocks += block_count
        historical_specialized_docs += int(block_count > 0)
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
    print(f"historical-specialized-docs={historical_specialized_docs}")
    print(f"historical-specialized-blocks={historical_specialized_blocks}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
