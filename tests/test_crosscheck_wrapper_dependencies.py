from __future__ import annotations

import subprocess
import unittest
from pathlib import Path

from tools.chisel.check_crosscheck_wrapper_dependencies import (
    canonical_invocation_errors,
    documentation_reference_errors,
)


ROOT = Path(__file__).resolve().parents[1]
TOOLS = ROOT / "tools" / "chisel"
CANONICAL = TOOLS / "run_chisel_frontend_trace_top_xcheck.sh"
DELETED = (
    "run_chisel_top_xcheck.sh",
    "run_chisel_trace_replay_xcheck.sh",
    "run_chisel_qemu_trace_replay_xcheck.sh",
    "run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh",
    "run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh",
    "build_frontend_fetch_rf_alu_qemu_fixture_elf.sh",
)


class CrosscheckWrapperDependenciesTest(unittest.TestCase):
    def test_dependency_closure_uses_only_current_canonical_emitter(self) -> None:
        proc = subprocess.run(
            ["python3", str(TOOLS / "check_crosscheck_wrapper_dependencies.py")],
            cwd=ROOT, text=True, stdout=subprocess.PIPE,
            stderr=subprocess.PIPE, check=False)
        self.assertEqual(proc.returncode, 0, proc.stdout + proc.stderr)
        self.assertIn("canonical-emitter=linxcore.top.EmitLinxCoreFrontendTraceTop", proc.stdout)
        self.assertIn("canonical-dry-run=pass", proc.stdout)
        self.assertIn("active-docs-checked=", proc.stdout)
        self.assertIn("historical-doc-archives-excluded=4", proc.stdout)
        self.assertRegex(proc.stdout, r"historical-specialized-docs=[1-9][0-9]*")
        self.assertRegex(proc.stdout, r"historical-specialized-blocks=[1-9][0-9]*")

    def test_displaced_entrypoints_are_deleted(self) -> None:
        for name in DELETED:
            with self.subTest(name=name):
                self.assertFalse((TOOLS / name).exists())

    def test_active_chisel_docs_do_not_invoke_deleted_entrypoints(self) -> None:
        historical_archives = {
            ROOT / "docs/chisel/agent-loop.md",
            ROOT / "docs/chisel/verification/phase1-evidence.md",
            ROOT / "docs/chisel/verification/phase2-evidence.md",
            ROOT / "docs/chisel/verification/phase5-prep-evidence.md",
        }
        stale = []
        for path in sorted((ROOT / "docs/chisel").rglob("*.md")):
            if path in historical_archives:
                continue
            source = path.read_text(encoding="utf-8")
            errors, _ = documentation_reference_errors(path, source)
            stale.extend(errors)
        self.assertEqual(stale, [], "active docs misuse historical evidence:\n" + "\n".join(stale))

    def test_exact_commit_and_artifact_provenance_are_accepted(self) -> None:
        commit_fixture = ROOT / "docs/chisel/issues.md"
        artifact_fixture = ROOT / "docs/chisel/fixture.md"
        deleted = DELETED[4]
        valid_commit = (
            "<!-- task15-historical-specialized-evidence:start -->\n"
            "Historical evidence only; no current runnable equivalent.\n"
            "source commit 801cbabb2475059b784de7587207e3332fee7a24\n"
            f"`bash tools/chisel/{deleted} --elf old.elf`\n"
            "<!-- task15-historical-specialized-evidence:end -->\n")
        valid_artifact = (
            "<!-- task15-historical-specialized-evidence:start -->\n"
            "Historical evidence only; no current runnable equivalent.\n"
            "retained artifact docs/chisel/generated/top-interface-manifest.json\n"
            "<!-- task15-historical-specialized-evidence:end -->\n")
        self.assertEqual(
            documentation_reference_errors(commit_fixture, valid_commit), ([], 1))
        self.assertEqual(
            documentation_reference_errors(artifact_fixture, valid_artifact), ([], 1))

    def test_historical_evidence_blocks_require_exact_provenance(self) -> None:
        commit_fixture = ROOT / "docs/chisel/issues.md"
        deleted = DELETED[4]
        valid_commit = (
            "<!-- task15-historical-specialized-evidence:start -->\n"
            "Historical evidence only; no current runnable equivalent.\n"
            "source commit 801cbabb2475059b784de7587207e3332fee7a24\n"
            f"`bash tools/chisel/{deleted} --elf old.elf`\n"
            "<!-- task15-historical-specialized-evidence:end -->\n")
        for source in (
                f"`bash tools/chisel/{deleted}`",
                valid_commit.replace("no current runnable equivalent", "archived"),
                valid_commit.replace("source commit 801cbabb2475059b784de7587207e3332fee7a24\n", ""),
                valid_commit.replace(
                    "801cbabb2475059b784de7587207e3332fee7a24",
                    "d72aed8f1f52b0edfb3b27c734302c95138cf26a"),
                valid_commit.replace("<!-- task15-historical-specialized-evidence:end -->", "")):
            with self.subTest(source=source):
                errors, _ = documentation_reference_errors(commit_fixture, source)
                self.assertNotEqual(errors, [])

    def test_active_docs_use_only_the_supported_canonical_cli(self) -> None:
        fixture = ROOT / "docs/chisel/fixture.md"
        canonical = "tools/chisel/run_chisel_frontend_trace_top_xcheck.sh"
        for command in (
                f"bash {canonical}",
                f"bash {canonical} --dry-run",
                f"bash {canonical} --check-dependencies",
                f"BUILD_DIR=generated/check bash {canonical}"):
            with self.subTest(command=command):
                self.assertEqual(canonical_invocation_errors(fixture, command), [])

        for command in (
                f"bash {canonical} --elf stale.elf",
                f"bash {canonical} --dry-run --elf stale.elf",
                f"bash {canonical} stale.elf",
                f"bash {canonical} --dry-run stale.elf",
                f"bash {canonical} FOO=1",
                f"FETCH_REPLAY_LIQ=1 bash {canonical}",
                "FETCH_REPLAY_LIQ=1 \\\n" f"bash {canonical}"):
            with self.subTest(command=command):
                self.assertNotEqual(canonical_invocation_errors(fixture, command), [])

    def test_special_modes_reject_trailing_tokens_before_execution(self) -> None:
        for arguments in (
                ("--dry-run", "stale.elf"),
                ("--check-dependencies", "FOO=1")):
            with self.subTest(arguments=arguments):
                proc = subprocess.run(
                    ["bash", str(CANONICAL), *arguments], cwd=ROOT, text=True,
                    stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=False)
                self.assertEqual(proc.returncode, 2, proc.stdout + proc.stderr)
                self.assertIn("unsupported argument", proc.stderr)
                self.assertNotIn("=pass", proc.stdout)

    def test_canonical_dry_run_validates_the_non_rtl_command_closure(self) -> None:
        proc = subprocess.run(
            ["bash", str(CANONICAL), "--dry-run"], cwd=ROOT, text=True,
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=False)
        self.assertEqual(proc.returncode, 0, proc.stdout + proc.stderr)
        for token in ("emitter=", "top=", "harness=", "crosscheck=", "--mode failfast"):
            self.assertIn(token, proc.stdout)


if __name__ == "__main__":
    unittest.main()
