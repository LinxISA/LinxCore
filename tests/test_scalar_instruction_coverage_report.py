#!/usr/bin/env python3
from __future__ import annotations

import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools" / "chisel"))

import report_scalar_instruction_coverage as report_cov  # noqa: E402


class ScalarInstructionCoverageReportTest(unittest.TestCase):
    def test_rebuilds_frozen_scalar_denominator(self) -> None:
        report = report_cov.build_report().report

        self.assertEqual(report["catalog_forms"], 769)
        self.assertEqual(report["excluded"]["vector_form"]["count"], 184)
        self.assertEqual(report["excluded"]["tile_pto_descriptor"]["count"], 30)
        self.assertEqual(report["excluded"]["vector_mode_block_descriptor"]["count"], 8)
        self.assertEqual(report["scalar_denominator"], 547)

    def test_reports_current_frontend_and_alu_coverage(self) -> None:
        report = report_cov.build_report().report

        self.assertEqual(report["frontend_strict_decode"]["covered"], 546)
        self.assertEqual(report["frontend_strict_decode"]["denominator"], 547)
        self.assertEqual(
            [item["mnemonic"] for item in report["frontend_strict_decode"]["missing"]],
            ["XB"],
        )
        self.assertEqual(report["reduced_scalar_alu_support"]["covered"], 189)
        self.assertEqual(report["reduced_scalar_alu_support"]["denominator"], 547)
        self.assertEqual(report["cross_stack_aligned_support"]["covered"], 188)
        self.assertEqual(report["cross_stack_aligned_support"]["denominator"], 547)
        self.assertIn("CSEL", report["cross_stack_aligned_support"]["known_divergences"])

    def test_cli_check_is_machine_consumable_json(self) -> None:
        result = subprocess.run(
            [
                sys.executable,
                str(ROOT / "tools" / "chisel" / "report_scalar_instruction_coverage.py"),
                "--check",
            ],
            cwd=ROOT,
            check=True,
            capture_output=True,
            text=True,
        )
        parsed = json.loads(result.stdout)
        self.assertEqual(parsed["schema_version"], "linxcore.scalar_instruction_coverage.v1")
        self.assertEqual(parsed["scalar_denominator"], 547)

    def test_is_supported_source_shape_contract_is_fail_closed(self) -> None:
        with tempfile.TemporaryDirectory() as td:
            path = Path(td) / "ReducedScalarAluExecute.scala"
            path.write_text(
                "\n".join(
                    [
                        "private def isSupported(op: UInt): Bool =",
                        "  op === opcode(FrontendOpcodeDecodeTable.OP_ADD) ||",
                        "  op === opcode(FrontendOpcodeDecodeTable.OP_SUB)",
                        "",
                        "private def helper(op: UInt): Bool = false.B",
                        "",
                        "private def isDivideOrRemainder(op: UInt): Bool = false.B",
                    ]
                ),
                encoding="utf-8",
            )

            with self.assertRaisesRegex(ValueError, "source-shape contract drifted"):
                report_cov._parse_supported_alu_symbols(path)

            path.write_text(
                "\n".join(
                    [
                        "private def isDivideOrRemainder(op: UInt): Bool = false.B",
                        "",
                        "private def isSupported(op: UInt): Bool =",
                        "  op === opcode(FrontendOpcodeDecodeTable.OP_ADD)",
                    ]
                ),
                encoding="utf-8",
            )

            with self.assertRaisesRegex(ValueError, "could not isolate"):
                report_cov._parse_supported_alu_symbols(path)


if __name__ == "__main__":
    unittest.main()
