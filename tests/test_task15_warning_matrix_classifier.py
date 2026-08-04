from __future__ import annotations

import importlib.util
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


if __name__ == "__main__":
    unittest.main()
