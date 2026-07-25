#!/usr/bin/env python3
from __future__ import annotations

import json
import os
import re
import sys
import unittest
from dataclasses import dataclass
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
GENERATE = ROOT / "tools" / "generate"
SRC = ROOT / "src"
QEMU_LINX = Path(
    os.environ.get(
        "QEMU_LINX",
        ROOT.parents[1] / "emulator" / "qemu" / "target" / "linx",
    )
)
CATALOG = SRC / "common" / "opcode_catalog.yaml"
CHISEL_TABLE = (
    ROOT
    / "chisel"
    / "src"
    / "main"
    / "scala"
    / "linxcore"
    / "frontend"
    / "FrontendOpcodeDecodeTable.scala"
)

sys.path.insert(0, str(GENERATE))
sys.path.insert(0, str(SRC))

from opcode_catalog_lib import (  # noqa: E402
    classify_fields,
    classify_major_minor,
    load_qemu_entries,
    mnemonic_to_symbol,
)
from common.opcode_meta_gen import opcode_meta_by_mnemonic  # noqa: E402


@dataclass(frozen=True)
class FieldSegment:
    pos: int
    width: int
    signed: bool


def _load_catalog_records() -> dict[str, dict[str, object]]:
    catalog = json.loads(CATALOG.read_text(encoding="utf-8"))
    return {str(record["mnemonic"]): record for record in catalog["records"]}


def _qemu_entries() -> dict[str, object]:
    return {
        entry.mnemonic: entry
        for entry in load_qemu_entries(QEMU_LINX)
        if entry.file == "insn48.decode"
    }


def _field_specs() -> dict[str, tuple[FieldSegment, ...]]:
    specs: dict[str, tuple[FieldSegment, ...]] = {}
    for line in (QEMU_LINX / "insn48.decode").read_text(encoding="utf-8").splitlines():
        line = line.split("#", 1)[0].strip()
        if not line.startswith("%"):
            continue
        name, *pieces = line.split()
        segments = []
        for piece in pieces:
            pos, width = piece.split(":", 1)
            signed = width.startswith("s")
            segments.append(FieldSegment(int(pos), int(width.removeprefix("s")), signed))
        specs[name[1:]] = tuple(segments)
    return specs


def _extract_field(raw: int, spec: tuple[FieldSegment, ...]) -> int:
    value = 0
    total_width = 0
    signed = False
    for segment in spec:
        value = (value << segment.width) | ((raw >> segment.pos) & ((1 << segment.width) - 1))
        total_width += segment.width
        signed = signed or segment.signed
    if signed and value & (1 << (total_width - 1)):
        value -= 1 << total_width
    return value


def _chisel_rules() -> dict[str, dict[str, object]]:
    rules: dict[str, dict[str, object]] = {}
    text = CHISEL_TABLE.read_text(encoding="utf-8")
    for line in text.splitlines():
        if "Rule(" not in line:
            continue
        symbol_match = re.search(r'Rule\(symbol = "([^"]+)"', line)
        if not symbol_match:
            continue
        symbol = symbol_match.group(1)
        fields: dict[str, object] = {}
        for name, value in re.findall(r"(\w+) = BigInt\(\"([0-9a-f]+)\", 16\)", line):
            fields[name] = int(value, 16)
        for name, value in re.findall(r"(\w+) = (true|false)\b", line):
            fields[name] = value == "true"
        for name, value in re.findall(r"(\w+) = ((?:Cat|Operand|Imm)[A-Za-z0-9_]+)", line):
            fields[name] = value
        for name, value in re.findall(r"(\w+) = (\d+)(?=,|\))", line):
            fields.setdefault(name, int(value))
        for name, value in re.findall(r'(\w+) = "([^"]+)"', line):
            fields.setdefault(name, value)
        rules[symbol] = fields
    return rules


def _meta_tuple(record: object) -> tuple[object, ...]:
    return (
        getattr(record, "op_id"),
        getattr(record, "symbol"),
        getattr(record, "major_cat"),
        getattr(record, "rd_kind"),
        getattr(record, "rs1_kind"),
        getattr(record, "rs2_kind"),
        getattr(record, "imm_kind"),
        getattr(record, "mask"),
        getattr(record, "match"),
        getattr(record, "insn_len"),
    )


def _field_classification(fields: list[str]) -> dict[str, str]:
    rd_kind, rs1_kind, rs2_kind, imm_kind = classify_fields(fields)
    return {
        "rd_kind": rd_kind,
        "rs1_kind": rs1_kind,
        "rs2_kind": rs2_kind,
        "imm_kind": imm_kind,
    }


class HlSdiPrMetadataTest(unittest.TestCase):
    maxDiff = None

    @classmethod
    def setUpClass(cls) -> None:
        cls.catalog = _load_catalog_records()
        cls.qemu = _qemu_entries()
        cls.field_specs = _field_specs()
        cls.chisel = _chisel_rules()

    def test_derives_hl_sdi_pr_encoding_and_operands_from_real_qemu_decode(self) -> None:
        entry = self.qemu["hl_sdi_pr"]
        raw = int.from_bytes(bytes.fromhex("ee0fd9bf15fc"), "little")

        self.assertEqual(entry.file, "insn48.decode")
        self.assertEqual(entry.enc_len, 64)
        self.assertEqual(entry.mask, 0xFFFF0000707F003F)
        self.assertEqual(entry.match, 0x3059002E)
        self.assertEqual(raw & entry.mask, entry.match)
        self.assertEqual(entry.fields, ["%RegDst1", "%SrcD", "%SrcR", "simm=%simm17_6_s5_23_5_41_7"])
        self.assertEqual(_extract_field(raw, self.field_specs["RegDst1"]), 1)
        self.assertEqual(_extract_field(raw, self.field_specs["SrcD"]), 11)
        self.assertEqual(_extract_field(raw, self.field_specs["SrcR"]), 1)
        self.assertEqual(_extract_field(raw, self.field_specs["simm17_6_s5_23_5_41_7"]), -2)

    def test_classifier_maps_hl_sdi_pr_source_tokens_to_frontend_slots(self) -> None:
        entry = self.qemu["hl_sdi_pr"]
        cases = {
            "RegDst1 destination": (
                ["%RegDst1"],
                {"rd_kind": "REG", "rs1_kind": "NONE", "rs2_kind": "NONE", "imm_kind": "NONE"},
            ),
            "SrcD store data": (
                ["%SrcD"],
                {"rd_kind": "NONE", "rs1_kind": "REG", "rs2_kind": "NONE", "imm_kind": "NONE"},
            ),
            "SrcR address base": (
                ["%SrcR"],
                {"rd_kind": "NONE", "rs1_kind": "NONE", "rs2_kind": "REG", "imm_kind": "NONE"},
            ),
            "real hl_sdi_pr fields": (
                entry.fields,
                {
                    "rd_kind": "REG",
                    "rs1_kind": "REG",
                    "rs2_kind": "REG",
                    "imm_kind": "SIMM17_6_S5_23_5_41_7",
                },
            ),
        }

        mismatches = []
        for name, (fields, expected) in cases.items():
            observed = _field_classification(fields)
            if observed != expected:
                mismatches.append((name, fields, observed, expected))
        self.assertEqual(mismatches, [])

    def test_hl_sdi_pr_has_future_green_store_metadata_across_generated_surfaces(self) -> None:
        entry = self.qemu["hl_sdi_pr"]
        catalog = self.catalog["hl_sdi_pr"]
        python_meta = opcode_meta_by_mnemonic("hl_sdi_pr")
        chisel = self.chisel["OP_HL_SDI_PR"]

        future = {
            "op_id": 217,
            "symbol": "OP_HL_SDI_PR",
            "mask": entry.mask,
            "match": entry.match,
            "major_cat": "STORE",
            "minor_cat": "store",
            "rd_kind": "REG",
            "rs1_kind": "REG",
            "rs2_kind": "REG",
            "imm_kind": "SIMM17_6_S5_23_5_41_7",
            "insn_len": 64,
        }
        observed_catalog = {
            key: catalog[key]
            for key in (
                "op_id",
                "symbol",
                "major_cat",
                "minor_cat",
                "rd_kind",
                "rs1_kind",
                "rs2_kind",
                "imm_kind",
            )
        } | {
            "mask": int(str(catalog["mask"]), 16),
            "match": int(str(catalog["match"]), 16),
            "insn_len": catalog["enc_len"],
        }
        observed_python = {
            "op_id": python_meta.op_id,
            "symbol": python_meta.symbol,
            "mask": python_meta.mask,
            "match": python_meta.match,
            "major_cat": python_meta.major_cat,
            "minor_cat": python_meta.minor_cat,
            "rd_kind": python_meta.rd_kind,
            "rs1_kind": python_meta.rs1_kind,
            "rs2_kind": python_meta.rs2_kind,
            "imm_kind": python_meta.imm_kind,
            "insn_len": python_meta.insn_len,
        }
        expected_chisel = {
            "opcode": 217,
            "lenBytes": 6,
            "mask": entry.mask,
            "value": entry.match,
            "category": "CatSTORE",
            "dispatch": 4,
            "rdKind": "OperandREG",
            "rs1Kind": "OperandREG",
            "rs2Kind": "OperandREG",
            "immKind": "ImmSIMM17_6_S5_23_5_41_7",
            "isLoad": False,
            "isStore": True,
        }
        observed_chisel = {key: chisel[key] for key in expected_chisel}

        mismatches = []
        if observed_catalog != future:
            mismatches.append(("catalog", observed_catalog, future))
        if observed_python != future:
            mismatches.append(("python", observed_python, future))
        if observed_chisel != expected_chisel:
            mismatches.append(("chisel", observed_chisel, expected_chisel))
        self.assertEqual(mismatches, [])

    def test_hl_sdi_neighbors_keep_source_encoding_identity_and_generated_parity(self) -> None:
        expected = {
            "hl_sdi": (213, 0xFFFF0000707F003F, 0x3059000E, "SIMM22_6_S10_23_5_41_7", "NONE"),
            "hl_sdi_po": (216, 0xFFFF0000707F003F, 0x3059003E, "SIMM17_6_S5_23_5_41_7", "REG"),
            "hl_sdi_u": (218, 0xFFFF0000707F003F, 0x7059000E, "SIMM22_6_S10_23_5_41_7", "NONE"),
            "hl_sdi_upo": (219, 0xFFFF0000707F003F, 0x7059003E, "SIMM17_6_S5_23_5_41_7", "REG"),
            "hl_sdi_upr": (220, 0xFFFF0000707F003F, 0x7059002E, "SIMM17_6_S5_23_5_41_7", "REG"),
        }
        mismatches = []
        for mnemonic, (op_id, mask, match, imm_kind, rd_kind) in expected.items():
            with self.subTest(mnemonic=mnemonic):
                entry = self.qemu[mnemonic]
                catalog = self.catalog[mnemonic]
                python_meta = opcode_meta_by_mnemonic(mnemonic)
                chisel = self.chisel[mnemonic_to_symbol(mnemonic)]
                expected_fields = {
                    "major_cat": "STORE",
                    "minor_cat": "store",
                    "rd_kind": rd_kind,
                    "rs1_kind": "REG",
                    "rs2_kind": "REG",
                    "imm_kind": imm_kind,
                }

                self.assertEqual((entry.mask, entry.match), (mask, match))
                self.assertEqual(catalog["op_id"], op_id)
                self.assertEqual(int(str(catalog["mask"]), 16), mask)
                self.assertEqual(int(str(catalog["match"]), 16), match)
                self.assertEqual(python_meta.op_id, op_id)
                self.assertEqual(python_meta.mask, mask)
                self.assertEqual(python_meta.match, match)
                self.assertEqual(chisel["opcode"], op_id)
                self.assertEqual(chisel["mask"], mask)
                self.assertEqual(chisel["value"], match)
                observed_catalog = {key: catalog[key] for key in expected_fields}
                observed_python = {
                    "major_cat": python_meta.major_cat,
                    "minor_cat": python_meta.minor_cat,
                    "rd_kind": python_meta.rd_kind,
                    "rs1_kind": python_meta.rs1_kind,
                    "rs2_kind": python_meta.rs2_kind,
                    "imm_kind": python_meta.imm_kind,
                }
                expected_chisel = {
                    "category": "CatSTORE",
                    "dispatch": 4,
                    "rdKind": f"Operand{rd_kind}",
                    "rs1Kind": "OperandREG",
                    "rs2Kind": "OperandREG",
                    "immKind": f"Imm{imm_kind}",
                    "isLoad": False,
                    "isStore": True,
                }
                observed_chisel = {key: chisel[key] for key in expected_chisel}
                if observed_catalog != expected_fields:
                    mismatches.append((mnemonic, "catalog", observed_catalog, expected_fields))
                if observed_python != expected_fields:
                    mismatches.append((mnemonic, "python", observed_python, expected_fields))
                if observed_chisel != expected_chisel:
                    mismatches.append((mnemonic, "chisel", observed_chisel, expected_chisel))
        self.assertEqual(mismatches, [])

    def test_unrelated_anchor_opcodes_keep_id_encoding_category_and_fields(self) -> None:
        anchors = {
            "sb": {
                "op_id": 386,
                "mask": 0x7FFF,
                "match": 0x49,
                "major_cat": "STORE",
                "rd_kind": "NONE",
                "rs1_kind": "REG",
                "rs2_kind": "REG",
                "imm_kind": "NONE",
            },
            "sdi": {
                "op_id": 390,
                "mask": 0x707F,
                "match": 0x3059,
                "major_cat": "STORE",
                "rd_kind": "NONE",
                "rs1_kind": "REG",
                "rs2_kind": "REG",
                "imm_kind": "SIMM12_7_S5_25_7",
            },
            "add": {
                "op_id": 61,
                "mask": 0x707F,
                "match": 0x5,
                "major_cat": "ALU_INT",
                "rd_kind": "REG",
                "rs1_kind": "REG",
                "rs2_kind": "REG",
                "imm_kind": "NONE",
            },
        }
        for mnemonic, expected in anchors.items():
            with self.subTest(mnemonic=mnemonic):
                catalog = self.catalog[mnemonic]
                python_meta = opcode_meta_by_mnemonic(mnemonic)
                chisel = self.chisel[mnemonic_to_symbol(mnemonic)]
                expected_category, _ = classify_major_minor(mnemonic)

                self.assertEqual(catalog["major_cat"], expected_category)
                self.assertEqual(catalog["op_id"], expected["op_id"])
                self.assertEqual(int(str(catalog["mask"]), 16), expected["mask"])
                self.assertEqual(int(str(catalog["match"]), 16), expected["match"])
                self.assertEqual(python_meta.op_id, expected["op_id"])
                self.assertEqual(python_meta.major_cat, expected["major_cat"])
                self.assertEqual((python_meta.rd_kind, python_meta.rs1_kind, python_meta.rs2_kind), (
                    expected["rd_kind"],
                    expected["rs1_kind"],
                    expected["rs2_kind"],
                ))
                self.assertEqual(chisel["opcode"], expected["op_id"])
                self.assertEqual(chisel["mask"], expected["mask"])
                self.assertEqual(chisel["value"], expected["match"])


if __name__ == "__main__":
    unittest.main(verbosity=2)
