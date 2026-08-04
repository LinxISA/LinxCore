from __future__ import annotations

import subprocess
import unittest
from pathlib import Path

from tools.chisel.check_crosscheck_wrapper_dependencies import canonical_invocation_errors


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
            for name in DELETED:
                if name in source:
                    stale.append(f"{path.relative_to(ROOT)}:{name}")
        self.assertEqual(stale, [], "active docs name deleted wrappers:\n" + "\n".join(stale))

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
                f"FETCH_REPLAY_LIQ=1 bash {canonical}",
                "FETCH_REPLAY_LIQ=1 \\\n" f"bash {canonical}"):
            with self.subTest(command=command):
                self.assertNotEqual(canonical_invocation_errors(fixture, command), [])

    def test_canonical_dry_run_validates_the_non_rtl_command_closure(self) -> None:
        proc = subprocess.run(
            ["bash", str(CANONICAL), "--dry-run"], cwd=ROOT, text=True,
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=False)
        self.assertEqual(proc.returncode, 0, proc.stdout + proc.stderr)
        for token in ("emitter=", "top=", "harness=", "crosscheck=", "--mode failfast"):
            self.assertIn(token, proc.stdout)


if __name__ == "__main__":
    unittest.main()
