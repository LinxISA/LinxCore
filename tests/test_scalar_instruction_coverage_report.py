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


def _fixture_source(symbols: list[str], end_signature: str) -> str:
    lines = ["private def isSupported(op: UInt): Bool ="]
    for index, symbol in enumerate(symbols):
        suffix = " ||" if index + 1 < len(symbols) else ""
        lines.append(f"  op === opcode(FrontendOpcodeDecodeTable.{symbol}){suffix}")
    lines.extend(["", end_signature, "  false.B"])
    return "\n".join(lines) + "\n"


def _assert_report_matches_detected_contract(testcase: unittest.TestCase, report: dict) -> None:
    contract = report["source_shape_contracts"]["reduced_scalar_alu_is_supported"]
    testcase.assertIn(contract["contract_id"], report_cov.SOURCE_SHAPE_CONTRACTS)
    for key, expected in contract["expected"].items():
        testcase.assertEqual(
            report[key]["covered"],
            expected["covered"],
            f"{key} covered count should match detected {contract['contract_id']} contract",
        )
        testcase.assertEqual(
            report[key]["denominator"],
            expected["denominator"],
            f"{key} denominator should match detected {contract['contract_id']} contract",
        )


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

        contract = report["source_shape_contracts"]["reduced_scalar_alu_is_supported"]
        _assert_report_matches_detected_contract(self, report)
        self.assertEqual(report["frontend_strict_decode"]["covered"], 546)
        self.assertEqual(report["frontend_strict_decode"]["denominator"], 547)
        self.assertEqual(
            [item["mnemonic"] for item in report["frontend_strict_decode"]["missing"]],
            ["XB"],
        )
        self.assertEqual(
            report["reduced_scalar_alu_support"]["covered"],
            contract["expected"]["reduced_scalar_alu_support"]["covered"],
        )
        self.assertEqual(
            report["cross_stack_aligned_support"]["covered"],
            190,
        )
        self.assertEqual(report["cross_stack_aligned_support"]["known_divergences"], {})

    def test_expanded_contract_fixture_uses_expanded_expected_values(self) -> None:
        with tempfile.TemporaryDirectory() as td:
            alu_path = Path(td) / "ReducedScalarAluExecute.scala"
            alu_path.write_text(
                _fixture_source(
                    [
                        "OP_ADD",
                        "OP_ADDI",
                        "OP_ADDIW",
                        "OP_ADDTPC",
                        "OP_ADDW",
                        "OP_AND",
                        "OP_ANDI",
                        "OP_ANDIW",
                        "OP_ANDW",
                        "OP_BCNT",
                        "OP_BIC",
                        "OP_BIS",
                        "OP_BXS",
                        "OP_BXU",
                        "OP_CLZ",
                        "OP_CMP_AND",
                        "OP_CMP_ANDI",
                        "OP_CMP_EQ",
                        "OP_CMP_EQI",
                        "OP_CMP_GE",
                        "OP_CMP_GEI",
                        "OP_CMP_GEU",
                        "OP_CMP_GEUI",
                        "OP_CMP_LT",
                        "OP_CMP_LTI",
                        "OP_CMP_LTU",
                        "OP_CMP_LTUI",
                        "OP_CMP_NE",
                        "OP_CMP_NEI",
                        "OP_CMP_OR",
                        "OP_CMP_ORI",
                        "OP_CSEL",
                        "OP_CTZ",
                        "OP_C_ADD",
                        "OP_C_ADDI",
                        "OP_C_AND",
                        "OP_C_CMP_EQI",
                        "OP_C_CMP_NEI",
                        "OP_C_LDI",
                        "OP_C_LWI",
                        "OP_C_MOVI",
                        "OP_C_MOVR",
                        "OP_C_OR",
                        "OP_C_SDI",
                        "OP_C_SETC_EQ",
                        "OP_C_SETC_NE",
                        "OP_C_SETC_TGT",
                        "OP_C_SETRET",
                        "OP_C_SEXT_B",
                        "OP_C_SEXT_H",
                        "OP_C_SEXT_W",
                        "OP_C_SLLI",
                        "OP_C_SRLI",
                        "OP_C_SUB",
                        "OP_C_SWI",
                        "OP_C_ZEXT_B",
                        "OP_C_ZEXT_H",
                        "OP_C_ZEXT_W",
                        "OP_DIV",
                        "OP_DIVU",
                        "OP_DIVUW",
                        "OP_DIVW",
                        "OP_FCVT",
                        "OP_FENTRY",
                        "OP_FEQ",
                        "OP_FRET_STK",
                        "OP_HL_ADDI",
                        "OP_HL_ADDIW",
                        "OP_HL_ANDI",
                        "OP_HL_ANDIW",
                        "OP_HL_CMP_ANDI",
                        "OP_HL_LBIP",
                        "OP_HL_LBUIP",
                        "OP_HL_LBU_PCR",
                        "OP_HL_LB_PCR",
                        "OP_HL_LDIP",
                        "OP_HL_LDIP_U",
                        "OP_HL_LD_PCR",
                        "OP_HL_LHIP",
                        "OP_HL_LHIP_U",
                        "OP_HL_LHUIP",
                        "OP_HL_LHUIP_U",
                        "OP_HL_LIS",
                        "OP_HL_LIU",
                        "OP_HL_LUI",
                        "OP_HL_LWIP",
                        "OP_HL_LWIP_U",
                        "OP_HL_LWUIP",
                        "OP_HL_LWUIP_U",
                        "OP_HL_LWU_PCR",
                        "OP_HL_LW_PCR",
                        "OP_HL_ORI",
                        "OP_HL_ORIW",
                        "OP_HL_SB_PCR",
                        "OP_HL_SDIP",
                        "OP_HL_SDIP_U",
                        "OP_HL_SDI_PO",
                        "OP_HL_SDI_PR",
                        "OP_HL_SD_PCR",
                        "OP_HL_SETRET",
                        "OP_HL_SH_PCR",
                        "OP_HL_SUBI",
                        "OP_HL_SUBIW",
                        "OP_HL_SWIP",
                        "OP_HL_SWIP_U",
                        "OP_HL_SWI_PO",
                        "OP_HL_SW_PCR",
                        "OP_HL_XORI",
                        "OP_HL_XORIW",
                        "OP_LB",
                        "OP_LBI",
                        "OP_LBU",
                        "OP_LBUI",
                        "OP_LD",
                        "OP_LDI",
                        "OP_LD_PCR",
                        "OP_LH",
                        "OP_LHI",
                        "OP_LHU",
                        "OP_LHUI",
                        "OP_LUI",
                        "OP_LW",
                        "OP_LWI",
                        "OP_LWI_U",
                        "OP_LWUI",
                        "OP_LR_W",
                        "OP_MADD",
                        "OP_MAX",
                        "OP_MAXU",
                        "OP_MIN",
                        "OP_MINU",
                        "OP_MUL",
                        "OP_MULU",
                        "OP_MULUW",
                        "OP_MULW",
                        "OP_OR",
                        "OP_ORI",
                        "OP_ORIW",
                        "OP_ORW",
                        "OP_REM",
                        "OP_REMU",
                        "OP_REMUW",
                        "OP_REMW",
                        "OP_SB",
                        "OP_SBI",
                        "OP_SD",
                        "OP_SDI",
                        "OP_SETC_ANDI",
                        "OP_SETC_EQ",
                        "OP_SETC_EQI",
                        "OP_SETC_GE",
                        "OP_SETC_GEI",
                        "OP_SETC_GEU",
                        "OP_SETC_GEUI",
                        "OP_SETC_LT",
                        "OP_SETC_LTI",
                        "OP_SETC_LTU",
                        "OP_SETC_LTUI",
                        "OP_SETC_NE",
                        "OP_SETC_NEI",
                        "OP_SETC_ORI",
                        "OP_SETC_TGT",
                        "OP_SETRET",
                        "OP_SH",
                        "OP_SHI",
                        "OP_SLL",
                        "OP_SLLI",
                        "OP_SLLIW",
                        "OP_SLLW",
                        "OP_SRA",
                        "OP_SRAI",
                        "OP_SRAIW",
                        "OP_SRAW",
                        "OP_SRL",
                        "OP_SRLI",
                        "OP_SRLIW",
                        "OP_SRLW",
                        "OP_SSRGET",
                        "OP_SSRSET",
                        "OP_SUB",
                        "OP_SUBI",
                        "OP_SUBIW",
                        "OP_SUBW",
                        "OP_SW",
                        "OP_SWI",
                        "OP_UCVTF",
                        "OP_XOR",
                        "OP_XORI",
                        "OP_XORIW",
                        "OP_XORW",
                    ],
                    "private def isDivideOrRemainder(op: UInt): Bool =",
                ),
                encoding="utf-8",
            )

            report = report_cov.build_report(alu_path=alu_path).report

        contract = report["source_shape_contracts"]["reduced_scalar_alu_is_supported"]
        self.assertEqual(contract["contract_id"], "expanded_current")
        _assert_report_matches_detected_contract(self, report)
        self.assertEqual(report["reduced_scalar_alu_support"]["covered"], 190)
        self.assertEqual(report["cross_stack_aligned_support"]["covered"], 190)

    def test_legacy_contract_fixture_uses_legacy_expected_values(self) -> None:
        legacy_symbols = [
            "OP_ADD",
            "OP_ADDW",
            "OP_ADDI",
            "OP_ADDTPC",
            "OP_C_MOVI",
            "OP_C_MOVR",
            "OP_C_SETRET",
            "OP_C_AND",
            "OP_C_SUB",
            "OP_C_SEXT_B",
            "OP_C_SEXT_H",
            "OP_C_SEXT_W",
            "OP_C_ZEXT_B",
            "OP_C_ZEXT_H",
            "OP_C_ZEXT_W",
            "OP_C_LDI",
            "OP_C_SDI",
            "OP_C_SWI",
            "OP_C_SETC_EQ",
            "OP_C_SETC_NE",
            "OP_C_SETC_TGT",
            "OP_CMP_EQI",
            "OP_CSEL",
            "OP_FRET_STK",
            "OP_FENTRY",
            "OP_AND",
            "OP_ANDI",
            "OP_ANDIW",
            "OP_HL_LUI",
            "OP_HL_LD_PCR",
            "OP_HL_SB_PCR",
            "OP_HL_SD_PCR",
            "OP_HL_SH_PCR",
            "OP_HL_SW_PCR",
            "OP_LBUI",
            "OP_LD_PCR",
            "OP_LDI",
            "OP_MUL",
            "OP_MULW",
            "OP_ORI",
            "OP_SBI",
            "OP_SETC_LT",
            "OP_SETC_LTU",
            "OP_SETC_LTUI",
            "OP_SETC_TGT",
            "OP_SD",
            "OP_SDI",
            "OP_SWI",
            "OP_SLL",
            "OP_SLLI",
            "OP_SRL",
            "OP_SRA",
            "OP_SSRSET",
            "OP_OR",
            "OP_C_ADD",
            "OP_SUB",
            "OP_SUBI",
            "OP_XORI",
        ]
        with tempfile.TemporaryDirectory() as td:
            alu_path = Path(td) / "ReducedScalarAluExecute.scala"
            alu_path.write_text(
                _fixture_source(
                    legacy_symbols,
                    "private def ldiScaledOffset(imm: UInt): UInt =",
                ),
                encoding="utf-8",
            )
            report = report_cov.build_report(alu_path=alu_path).report

        contract = report["source_shape_contracts"]["reduced_scalar_alu_is_supported"]
        self.assertEqual(contract["contract_id"], "legacy_clean_head")
        _assert_report_matches_detected_contract(self, report)
        self.assertEqual(report["reduced_scalar_alu_support"]["covered"], 58)
        self.assertEqual(report["cross_stack_aligned_support"]["covered"], 58)

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
        _assert_report_matches_detected_contract(self, parsed)

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

            with self.assertRaisesRegex(ValueError, "unknown|invalid|ambiguous"):
                report_cov._parse_supported_alu_symbols(path)

            path.write_text(
                "\n".join(
                    [
                        "private def isSupported(op: UInt): Bool =",
                        "  op === opcode(FrontendOpcodeDecodeTable.OP_ADD)",
                        "",
                        "private def someOtherHelper(op: UInt): Bool = false.B",
                    ]
                ),
                encoding="utf-8",
            )

            with self.assertRaisesRegex(ValueError, "unknown isSupported source-shape"):
                report_cov._parse_supported_alu_symbols(path)


if __name__ == "__main__":
    unittest.main()
