#!/usr/bin/env python3
from __future__ import annotations

import importlib.util
import json
import re
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
GENERATE = ROOT / "tools" / "generate"
sys.path.insert(0, str(GENERATE))
sys.path.insert(0, str(ROOT / "src"))

from opcode_catalog_lib import (  # noqa: E402
    build_catalog_from_entries,
    load_qemu_entries,
    save_catalog,
)


class OpcodeCatalogFormsTest(unittest.TestCase):
    def setUp(self) -> None:
        self.tempdir = tempfile.TemporaryDirectory()
        self.root = Path(self.tempdir.name)
        self.qemu = self.root / "qemu-linx"
        self.qemu.mkdir()
        (self.qemu / "insn16.decode").write_text(
            "tiny 0000 0000 0000 0001\n", encoding="utf-8"
        )
        (self.qemu / "insn32.decode").write_text(
            "duplicate 0000 0000 0000 0000 0000 0000 0000 0011\n"
            "duplicate 0000 0000 0000 0000 0000 0000 0000 0111\n",
            encoding="utf-8",
        )
        (self.qemu / "insn48.decode").write_text("", encoding="utf-8")
        (self.qemu / "insn64.decode").write_text("", encoding="utf-8")

    def tearDown(self) -> None:
        self.tempdir.cleanup()

    def _fixture_catalog(self) -> dict[str, object]:
        return build_catalog_from_entries(load_qemu_entries(self.qemu))

    def test_catalog_keeps_every_form_with_one_stable_opcode_id(self) -> None:
        catalog = self._fixture_catalog()
        forms = [
            record
            for record in catalog["records"]
            if record["mnemonic"] == "duplicate"
        ]

        self.assertEqual(len(forms), 2)
        self.assertEqual({record["op_id"] for record in forms}, {forms[0]["op_id"]})
        self.assertEqual([record["form_index"] for record in forms], [0, 1])
        self.assertEqual({record["form_count"] for record in forms}, {2})
        self.assertEqual(
            [record["match"] for record in forms], ["0x3", "0x7"]
        )

    def test_generated_consumers_expose_all_forms_and_legacy_lookup(self) -> None:
        catalog_path = self.root / "opcode_catalog.yaml"
        common = self.root / "common"
        save_catalog(catalog_path, self._fixture_catalog())
        subprocess.run(
            [
                sys.executable,
                str(GENERATE / "gen_opcode_tables.py"),
                "--catalog",
                str(catalog_path),
                "--linxcore-common",
                str(common),
                "--qemu-linx-dir",
                str(self.qemu),
            ],
            check=True,
            capture_output=True,
            text=True,
        )

        module_path = common / "opcode_meta_gen.py"
        spec = importlib.util.spec_from_file_location("generated_opcode_meta", module_path)
        assert spec is not None and spec.loader is not None
        module = importlib.util.module_from_spec(spec)
        sys.modules[spec.name] = module
        spec.loader.exec_module(module)
        self.addCleanup(sys.modules.pop, spec.name, None)

        forms = module.opcode_meta_forms_by_mnemonic("duplicate")
        self.assertEqual(len(forms), 2)
        self.assertIs(module.opcode_meta_by_mnemonic("duplicate"), forms[0])
        self.assertEqual({form.op_id for form in forms}, {forms[0].op_id})
        self.assertEqual(
            len(module.opcode_meta_forms_by_id(forms[0].op_id)), 2
        )

        qemu_meta = (self.qemu / "linx_opcode_meta_gen.h").read_text(
            encoding="utf-8"
        )
        self.assertEqual(qemu_meta.count('.mnemonic="duplicate"'), 2)

        data = json.loads(catalog_path.read_text(encoding="utf-8"))
        self.assertEqual(
            len([r for r in data["records"] if r["mnemonic"] == "duplicate"]),
            2,
        )

    def test_parity_rejects_a_mnemonic_collapsed_catalog(self) -> None:
        catalog = self._fixture_catalog()
        seen: set[str] = set()
        collapsed_records = []
        for record in catalog["records"]:
            mnemonic = str(record["mnemonic"])
            if mnemonic in seen:
                continue
            seen.add(mnemonic)
            collapsed_records.append(record)
        collapsed = dict(catalog)
        collapsed["records"] = collapsed_records
        catalog_path = self.root / "collapsed.json"
        catalog_path.write_text(json.dumps(collapsed), encoding="utf-8")

        result = subprocess.run(
            [
                sys.executable,
                str(GENERATE / "check_decode_parity.py"),
                "--qemu-linx-dir",
                str(self.qemu),
                "--catalog",
                str(catalog_path),
            ],
            capture_output=True,
            text=True,
        )
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("missing decode forms", result.stdout)
        self.assertIn("duplicate", result.stdout)

    def test_checked_in_python_and_chisel_cover_all_bstart64_forms(self) -> None:
        from common.decode32 import decode32_meta
        from common.decode64 import decode64_meta
        from common.opcode_meta_gen import opcode_meta_forms_by_mnemonic

        bstart_forms = {
            "l_bstart_std": (
                0x10010000000F,
                0x20010000000F,
                0x30010000000F,
                0x40010000000F,
            ),
            "l_bstart_fp": (
                0x10810000000F,
                0x20810000000F,
                0x30810000000F,
                0x40810000000F,
            ),
            "l_bstart_sys": (0x10110000000F,),
        }
        for mnemonic, raw_forms in bstart_forms.items():
            forms = opcode_meta_forms_by_mnemonic(mnemonic)
            self.assertEqual(len(forms), len(raw_forms))
            self.assertEqual(len({form.op_id for form in forms}), 1)
            for raw in raw_forms:
                decoded = decode64_meta(raw)
                self.assertIsNotNone(decoded)
                self.assertEqual(decoded.mnemonic, mnemonic)

        for mnemonic, raw in (
            ("dc_isw", 0x0040602B),
            ("dc_zva", 0x0070602B),
        ):
            decoded = decode32_meta(raw)
            self.assertIsNotNone(decoded)
            self.assertEqual(decoded.mnemonic, mnemonic)

        scala = (
            ROOT
            / "chisel"
            / "src"
            / "main"
            / "scala"
            / "linxcore"
            / "frontend"
            / "FrontendOpcodeDecodeTable.scala"
        ).read_text(encoding="utf-8")
        for raw_forms in bstart_forms.values():
            for raw in raw_forms:
                self.assertIn(f'value = BigInt("{raw:x}", 16)', scala)

    def test_active_tlsu_cube_and_scalar_delta_decode(self) -> None:
        from common.decode32 import decode32_meta
        from common.opcode_meta_gen import opcode_meta_forms_by_mnemonic

        tlsu_forms = (
            ("bstart_tload", 0x00011181),
            ("bstart_tstore", 0x00111181),
            ("bstart_tmov", 0x00211181),
            ("bstart_tprefetch", 0x00311181),
            ("bstart_mgather", 0x00411181),
            ("bstart_mscatter", 0x00511181),
            ("bstart_mgather_mask", 0x00611181),
            ("bstart_mscatter_mask", 0x00711181),
            ("bstart_mgather_cas", 0x00811181),
            ("bstart_gmov", 0x00D11181),
        )
        for mnemonic, raw in tlsu_forms:
            decoded = decode32_meta(raw)
            self.assertIsNotNone(decoded)
            self.assertEqual(decoded.mnemonic, mnemonic)
            self.assertEqual(decoded.symbol, "OP_BSTART_TLSU")
            self.assertEqual(len(opcode_meta_forms_by_mnemonic(mnemonic)), 1)

        cube_aliases = (
            ("bstart_tmatmul", 0x00031181),
            ("bstart_tmatmul_bias", 0x00131181),
            ("bstart_tmatmul_acc", 0x00231181),
            ("bstart_tmatmulmx", 0x00431181),
            ("bstart_tmatmulmx_bias", 0x00531181),
            ("bstart_tmatmulmx_acc", 0x00631181),
        )
        for mnemonic, raw in cube_aliases:
            decoded = decode32_meta(raw)
            self.assertIsNotNone(decoded)
            self.assertEqual(decoded.mnemonic, mnemonic)
            self.assertEqual(decoded.symbol, "OP_BSTART_CUBE")

        scalar_forms = (
            ("casb", "OP_CASB", 0x0000001B),
            ("cash", "OP_CASH", 0x0000101B),
            ("casw", "OP_CASW", 0x0000201B),
            ("casd", "OP_CASD", 0x0000301B),
            ("dma", "OP_DMA", 0x0000700B),
        )
        for mnemonic, symbol, raw in scalar_forms:
            decoded = decode32_meta(raw)
            self.assertIsNotNone(decoded)
            self.assertEqual(decoded.mnemonic, mnemonic)
            self.assertEqual(decoded.symbol, symbol)

    def test_active_non_pto_command_and_boundary_forms_remain_decodable(self) -> None:
        from common.decode16 import decode16_meta
        from common.decode32 import decode32_meta
        from common.decode64 import decode64_meta
        from common.opcode_meta_gen import opcode_meta_forms_by_mnemonic

        expected_forms = (
            ("bstart_tepl", 32, 0x000FFFFF, 0x00019181),
            ("bstart_vpar", 32, 0xF9FFFFFF, 0x00021181),
            ("bstart_vseq", 32, 0xF9FFFFFF, 0x00029181),
            ("c_bstart_vpar", 16, 0x0000FFFF, 0x000088C0),
            ("c_bstart_vseq", 16, 0x0000FFFF, 0x0000C8C0),
            ("v_qpop", 64, 0xFFF0707FFFF0707F, 0x0000207D0000007F),
            ("v_qpush", 64, 0xFE00707FFE00707F, 0x0000107D0000007F),
        )
        for mnemonic, enc_len, mask, match in expected_forms:
            forms = opcode_meta_forms_by_mnemonic(mnemonic)
            self.assertEqual(len(forms), 1, mnemonic)
            self.assertEqual(forms[0].insn_len, enc_len, mnemonic)
            self.assertEqual(forms[0].mask, mask, mnemonic)
            self.assertEqual(forms[0].match, match, mnemonic)

        catalog = json.loads(
            (ROOT / "src/common/opcode_catalog.yaml").read_text(encoding="utf-8")
        )
        tepl_carrier = next(
            record
            for record in catalog["records"]
            if record["mnemonic"] == "bstart_tepl"
        )
        self.assertEqual(tepl_carrier["flags"], "DECODE_ONLY_CARRIER")
        self.assertEqual(tepl_carrier["ooo"]["disposition"], "DISPATCH")

        opcode_ids = {
            str(record["symbol"]): int(record["op_id"])
            for record in catalog["records"]
        }
        self.assertEqual(opcode_ids["OP_BSTART_TEPL"], 19)
        self.assertEqual(opcode_ids["OP_ADD"], 49)
        self.assertEqual(
            {symbol: opcode_ids[symbol] for symbol in (
                "OP_BSTART_VPAR",
                "OP_BSTART_VSEQ",
                "OP_C_BSTART_VPAR",
                "OP_C_BSTART_VSEQ",
                "OP_V_QPOP",
                "OP_V_QPUSH",
            )},
            {
                "OP_BSTART_VPAR": 730,
                "OP_BSTART_VSEQ": 731,
                "OP_C_BSTART_VPAR": 732,
                "OP_C_BSTART_VSEQ": 733,
                "OP_V_QPOP": 734,
                "OP_V_QPUSH": 735,
            },
        )

        self.assertEqual(decode16_meta(0x88C0).mnemonic, "c_bstart_vpar")
        self.assertEqual(decode16_meta(0xC8C0).mnemonic, "c_bstart_vseq")
        self.assertEqual(decode32_meta(0x00021181).mnemonic, "bstart_vpar")
        self.assertEqual(decode32_meta(0x00029181).mnemonic, "bstart_vseq")
        self.assertEqual(decode64_meta(0x0000207D0000007F).mnemonic, "v_qpop")
        self.assertEqual(decode64_meta(0x0000107D0000007F).mnemonic, "v_qpush")

    def test_active_locked_pto_counts_and_exact_decode_surface(self) -> None:
        from common.decode32 import decode32_meta

        catalog = json.loads((ROOT / "src/common/opcode_catalog.yaml").read_text(encoding="utf-8"))
        self.assertEqual(catalog["source"]["release"], "0.58.1")
        self.assertEqual(
            catalog["source"]["pto_spec_commit"],
            "c381465b2b8e457e162a4246ee58bb9a2c5b49fd",
        )
        self.assertEqual(
            catalog["source"]["pto_spec_tree"],
            "463a19db3d6ba70022f18bdbca0d4b2c6ed586e4",
        )
        self.assertEqual(
            catalog["source"]["pto_spec_content_sha256"],
            "693e8c0734b48598ac35ffe7fe6f2a01037788fba30ebe026895808d23139f2c",
        )
        self.assertEqual(
            catalog["source"]["pto_spec_encoding_abi"],
            "pto-isa-0.58.1-mode-function-v1",
        )
        self.assertEqual(
            catalog["source"]["pto_spec_encoding_projection_sha256"],
            "89b872d6eaf0252200bc9349d49b9346e2a69d894cdcc2dcd0fd71911c1e0b8c",
        )
        self.assertEqual(
            catalog["source"]["pto_spec_release_manifest_sha256"],
            "acea87af67301173e6d1c6e04014a8dc6e2f658cd2992ed658ab2589cddc7841",
        )
        self.assertEqual(
            catalog["source"]["hardware_conformance_profile_id"],
            "pto-hardware-numeric-0.58.1-ieee-v1",
        )
        self.assertEqual(
            catalog["source"]["hardware_conformance_profile_sha256"],
            "170deadacb174c933c287231fb67da1046d7989f84b6852bf353d68a495d1755",
        )
        self.assertEqual(
            catalog["source"]["numeric_conformance_vectors_sha256"],
            "59c96cc2f45f8e8f3eebb8230338b21ec3a77a99e8fb5e1c7c7b391819a6aa81",
        )
        self.assertEqual(catalog["source"]["command_form_count"], 74)
        self.assertEqual(catalog["source"]["tile_operation_count"], 109)
        expected_counts = {"TEPL": 87, "TLSU": 10, "CUBE": 12}
        actual_counts = {
            family: len(
                [record for record in catalog["records"] if record.get("operation_family") == family]
            )
            for family in expected_counts
        }
        self.assertEqual(actual_counts, expected_counts)

        for record in catalog["records"]:
            if record.get("operation_family") not in expected_counts:
                continue
            decoded = decode32_meta(int(record["match"], 0))
            self.assertIsNotNone(decoded, record["mnemonic"])
            self.assertEqual(decoded.mnemonic, record["mnemonic"])
            self.assertEqual(decoded.symbol, f"OP_BSTART_{record['operation_family']}")

        by_mnemonic: dict[str, list[dict[str, object]]] = {}
        for record in catalog["records"]:
            by_mnemonic.setdefault(str(record["mnemonic"]), []).append(record)
        self.assertEqual(int(by_mnemonic["b_catr"][0]["mask"], 0), 0xFBF07FFF)
        self.assertEqual(int(by_mnemonic["b_catr"][0]["match"], 0), 0x00000023)
        self.assertEqual(int(by_mnemonic["b_datr"][0]["mask"], 0), 0x000C707F)
        self.assertEqual(int(by_mnemonic["b_datr"][0]["match"], 0), 0x00001023)
        self.assertEqual(len(by_mnemonic["b_ios"]), 1)
        self.assertEqual(int(by_mnemonic["b_ios"][0]["mask"], 0), 0xF00871FF)
        self.assertEqual(int(by_mnemonic["b_ios"][0]["match"], 0), 0x00001013)
        decoded = decode32_meta(0x00001013)
        self.assertIsNotNone(decoded)
        self.assertEqual(decoded.mnemonic, "b_ios")
        self.assertEqual(decoded.symbol, "OP_B_IOS")

        fpatr = decode32_meta(0x00002023)
        self.assertIsNotNone(fpatr)
        self.assertEqual(fpatr.mnemonic, "b_fpatr")
        self.assertEqual(fpatr.symbol, "OP_B_FPATR")
        icall = decode32_meta(0x50166001)
        self.assertIsNotNone(icall)
        self.assertEqual(icall.mnemonic, "bstart_icall")
        self.assertEqual(icall.symbol, "OP_BSTART_ICALL")

        by_mnemonic = {
            mnemonic: records for mnemonic, records in by_mnemonic.items()
        }
        self.assertEqual(int(by_mnemonic["b_fpatr"][0]["mask"], 0), 0x00007FFF)
        self.assertEqual(int(by_mnemonic["b_fpatr"][0]["match"], 0), 0x00002023)
        self.assertEqual(int(by_mnemonic["bstart_icall"][0]["mask"], 0), 0xF83FFFFF)
        self.assertEqual(int(by_mnemonic["bstart_icall"][0]["match"], 0), 0x50166001)
        self.assertEqual(by_mnemonic["b_fpatr"][0]["cmd_kind"], "DESC_B_FPATR")
        self.assertEqual(by_mnemonic["bstart_icall"][0]["ooo"]["implicit_destination"], "RA")
        self.assertEqual(by_mnemonic["bstart_icall"][0]["ooo"]["p_destination_count"], 1)
        self.assertTrue(by_mnemonic["bstart_icall"][0]["ooo"]["requires_target_validation"])
        self.assertTrue(by_mnemonic["bstart_icall"][0]["ooo"]["may_redirect"])

    def test_active_reserved_deleted_and_legacy_pto_forms_are_illegal(self) -> None:
        from common.decode32 import decode32_meta
        from common.opcode_meta_gen import opcode_meta_forms_by_mnemonic

        reserved_raw = (
            0x00519181,  # reserved TEPL selector 0x005
            0x01819181,  # reserved TEPL selector range 0x018..0x019
            0x07F19181,  # reserved TEPL selector 0x07f
            0x00911181,  # reserved TMA function 9
            0x00331181,  # unnamed/reserved CUBE function 3
            0x00039181,  # deleted generic FIXP header
            0x18002023,  # B.FPATR reserved PreQuantMode 6
            0x02002023,  # B.FPATR reserved ReluMode 4
            0x00502023,  # B.FPATR reserved GroupNCode 10
        )
        for raw in reserved_raw:
            self.assertIsNone(decode32_meta(raw), f"reserved raw 0x{raw:08x}")

        retired_raw = (
            0x00006001,  # retired generic BSTART.ICALL
            0x00003043,
            0x020FAE23,
            0x180221A3,
            0x18022423,
            0x1800A4A3,
        )
        for raw in retired_raw:
            self.assertIsNone(decode32_meta(raw), f"retired B.ARG raw 0x{raw:08x}")

        for mnemonic in ("b_arg", "bstart_cube", "bstart_fixp"):
            self.assertEqual(opcode_meta_forms_by_mnemonic(mnemonic), ())
        for deleted in (
            "taddc",
            "taddsc",
            "tfmod",
            "tfmods",
            "tlrelu",
            "trandom",
            "tsubc",
            "tsubsc",
        ):
            self.assertEqual(opcode_meta_forms_by_mnemonic(f"bstart_{deleted}"), ())

    def test_lr_sc_catalog_routes_through_lsu_not_d2_or_alu(self) -> None:
        from common.opcode_meta_gen import opcode_meta_by_mnemonic, opcode_meta_forms_by_mnemonic

        expected = {
            "lr_b": ("OP_LR_B", "LOAD"),
            "lr_h": ("OP_LR_H", "LOAD"),
            "lr_w": ("OP_LR_W", "LOAD"),
            "lr_d": ("OP_LR_D", "LOAD"),
            "sc_b": ("OP_SC_B", "STORE"),
            "sc_h": ("OP_SC_H", "STORE"),
            "sc_w": ("OP_SC_W", "STORE"),
            "sc_d": ("OP_SC_D", "STORE"),
        }
        for mnemonic, (symbol, major_cat) in expected.items():
            meta = opcode_meta_by_mnemonic(mnemonic)
            self.assertEqual(meta.symbol, symbol)
            self.assertEqual(meta.major_cat, major_cat)
            self.assertEqual(meta.minor_cat, "atomic")

        scala = (
            ROOT
            / "chisel"
            / "src"
            / "main"
            / "scala"
            / "linxcore"
            / "frontend"
            / "FrontendOpcodeDecodeTable.scala"
        ).read_text(encoding="utf-8")
        load_category = re.search(r"val CatLOAD: Int = (\d+)", scala)
        store_category = re.search(r"val CatSTORE: Int = (\d+)", scala)
        self.assertIsNotNone(load_category)
        self.assertIsNotNone(store_category)
        for symbol in ("OP_LR_B", "OP_LR_H", "OP_LR_W", "OP_LR_D"):
            needle = f'Rule(symbol = "{symbol}"'
            start = scala.index(needle)
            rule = scala[start : scala.index("\n", start)]
            self.assertIn(f"category = {load_category.group(1)}", rule)
            self.assertIn("dispatch = 4", rule)
            self.assertIn("isLoad = true", rule)
            self.assertIn("isStore = false", rule)
        for symbol in ("OP_SC_B", "OP_SC_H", "OP_SC_W", "OP_SC_D"):
            needle = f'Rule(symbol = "{symbol}"'
            start = scala.index(needle)
            rule = scala[start : scala.index("\n", start)]
            self.assertIn(f"category = {store_category.group(1)}", rule)
            self.assertIn("dispatch = 4", rule)
            self.assertIn("isLoad = false", rule)
            self.assertIn("isStore = true", rule)

if __name__ == "__main__":
    unittest.main()
