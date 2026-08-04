from __future__ import annotations

import subprocess
import unittest
from pathlib import Path


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

    def test_displaced_entrypoints_are_deleted(self) -> None:
        for name in DELETED:
            with self.subTest(name=name):
                self.assertFalse((TOOLS / name).exists())

    def test_canonical_dry_run_validates_the_non_rtl_command_closure(self) -> None:
        proc = subprocess.run(
            ["bash", str(CANONICAL), "--dry-run"], cwd=ROOT, text=True,
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=False)
        self.assertEqual(proc.returncode, 0, proc.stdout + proc.stderr)
        for token in ("emitter=", "top=", "harness=", "crosscheck=", "--mode failfast"):
            self.assertIn(token, proc.stdout)


if __name__ == "__main__":
    unittest.main()
