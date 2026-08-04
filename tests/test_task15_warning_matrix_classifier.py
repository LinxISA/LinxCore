from __future__ import annotations

import importlib.util
import subprocess
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
MODULE_PATH = ROOT / "tools" / "chisel" / "classify_task15_warning_matrix.py"
SPEC = importlib.util.spec_from_file_location("task15_warning_matrix", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
CLASSIFIER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CLASSIFIER)


class Task15WarningMatrixClassifierTest(unittest.TestCase):
    def matrix(self, warning: str = "") -> str:
        return "\n".join(
            line
            for width in CLASSIFIER.WIDTHS
            for line in (
                f"TASK15_WARNING_MATRIX_BEGIN {width}",
                warning if width == "W4" else "",
                f"TASK15_WARNING_MATRIX_END {width}",
            )
            if line
        )

    def test_clean_matrix_has_no_target_warning(self) -> None:
        sections, outside = CLASSIFIER.classify(self.matrix())
        self.assertEqual(outside, [])
        self.assertTrue(all(not rows for rows in sections.values()))

    def test_target_warning_is_retained_in_its_width(self) -> None:
        sections, _ = CLASSIFIER.classify(self.matrix("[W004] width mismatch"))
        self.assertEqual(sections["W4"], ["[W004] width mismatch"])

    def test_incomplete_matrix_is_rejected(self) -> None:
        with self.assertRaises(ValueError):
            CLASSIFIER.classify("TASK15_WARNING_MATRIX_BEGIN W2")

    def run_classifier(self, text: str) -> subprocess.CompletedProcess[str]:
        with tempfile.NamedTemporaryFile(mode="w", suffix=".log") as log:
            log.write(text)
            log.flush()
            return subprocess.run(
                ["python3", str(MODULE_PATH), log.name],
                cwd=ROOT,
                text=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                check=False,
            )

    def test_non_target_warning_inside_matrix_fails(self) -> None:
        proc = self.run_classifier(self.matrix("[W099] unexpected warning"))
        self.assertEqual(proc.returncode, 1, proc.stdout + proc.stderr)
        self.assertIn("rejected=W099", proc.stdout)

    def test_warning_outside_matrix_fails(self) -> None:
        proc = self.run_classifier("[W777] outside\n" + self.matrix())
        self.assertEqual(proc.returncode, 1, proc.stdout + proc.stderr)
        self.assertIn("outside=W777", proc.stdout)


if __name__ == "__main__":
    unittest.main()
