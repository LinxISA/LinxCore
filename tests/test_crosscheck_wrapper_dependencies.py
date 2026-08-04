from __future__ import annotations

import subprocess
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
TOOLS = ROOT / "tools" / "chisel"


class CrosscheckWrapperDependenciesTest(unittest.TestCase):
    def run_script(self, name: str, *args: str) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            ["bash", str(TOOLS / name), *args],
            cwd=ROOT,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            check=False,
        )

    def test_dependency_closure_uses_current_canonical_emitter(self) -> None:
        proc = subprocess.run(
            ["python3", str(TOOLS / "check_crosscheck_wrapper_dependencies.py")],
            cwd=ROOT,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            check=False,
        )
        self.assertEqual(proc.returncode, 0, proc.stdout + proc.stderr)
        self.assertIn("canonical-emitter=linxcore.top.EmitLinxCoreFrontendTraceTop", proc.stdout)

    def test_compatibility_shims_fail_closed_with_migration_guidance(self) -> None:
        for name in (
            "run_chisel_trace_replay_xcheck.sh",
            "run_chisel_qemu_trace_replay_xcheck.sh",
            "run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh",
            "run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh",
            "build_frontend_fetch_rf_alu_qemu_fixture_elf.sh",
        ):
            with self.subTest(name=name):
                proc = self.run_script(name)
                self.assertNotEqual(proc.returncode, 0, proc.stdout + proc.stderr)
                self.assertIn("--check-dependencies", proc.stderr)

    def test_every_compatibility_entrypoint_can_check_dependencies_without_rtl(self) -> None:
        for name in (
            "run_chisel_top_xcheck.sh",
            "run_chisel_trace_replay_xcheck.sh",
            "run_chisel_qemu_trace_replay_xcheck.sh",
            "run_chisel_frontend_trace_top_xcheck.sh",
            "run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh",
            "run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh",
            "build_frontend_fetch_rf_alu_qemu_fixture_elf.sh",
        ):
            with self.subTest(name=name):
                proc = self.run_script(name, "--check-dependencies")
                self.assertEqual(proc.returncode, 0, proc.stdout + proc.stderr)
                self.assertIn("crosscheck-wrapper-dependencies=pass", proc.stdout)


if __name__ == "__main__":
    unittest.main()
