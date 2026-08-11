from __future__ import annotations

import subprocess
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
CHECKER = ROOT / "tools/chisel/check_crosscheck_wrapper_dependencies.py"


class CrosscheckWrapperDependenciesTest(unittest.TestCase):
    def test_dependency_closure_uses_only_top(self) -> None:
        result = subprocess.run(
            ["python3", str(CHECKER)], cwd=ROOT, text=True,
            capture_output=True, check=False)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("canonical-emitter=linxcore.top.EmitTOP", result.stdout)
        self.assertIn("canonical-top=TOP", result.stdout)
        self.assertIn("canonical-runner=tools/chisel/run_top_natural.sh", result.stdout)

    def test_displaced_top_sources_and_runners_are_absent(self) -> None:
        displaced = (
            "chisel/src/main/scala/linxcore/top/" + "LinxCoreFrontend" + "TraceTop.scala",
            "chisel/src/main/scala/linxcore/top/" + "LinxCoreFrontend" + "FetchTraceTop.scala",
            "tools/chisel/run_chisel_benchmark_autonomous_top_natural.sh",
            "tools/chisel/run_chisel_frontend_trace_top_xcheck.sh",
        )
        for relative in displaced:
            self.assertFalse((ROOT / relative).exists(), relative)


if __name__ == "__main__":
    unittest.main()
