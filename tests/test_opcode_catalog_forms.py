#!/usr/bin/env python3
from __future__ import annotations

import importlib.util
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
GENERATE = ROOT / "tools" / "generate"
sys.path.insert(0, str(GENERATE))
sys.path.insert(0, str(ROOT / "src"))

from opcode_catalog_lib import build_catalog, save_catalog  # noqa: E402


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

    def test_catalog_keeps_every_form_with_one_stable_opcode_id(self) -> None:
        catalog = build_catalog(self.qemu)
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
        save_catalog(catalog_path, build_catalog(self.qemu))
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
        catalog = build_catalog(self.qemu)
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

    def test_cmd_recipes_are_commit_owned_and_nonspeculative(self) -> None:
        catalog = json.loads(
            (ROOT / "src" / "common" / "opcode_catalog.yaml").read_text(
                encoding="utf-8"
            )
        )
        commands = [
            record for record in catalog["records"]
            if record["ooo"]["dispatch_class"] == "CMD"
        ]
        self.assertTrue(commands)
        for record in commands:
            with self.subTest(symbol=record["symbol"], form=record.get("form_index")):
                self.assertEqual(record["ooo"]["side_effect_owner"], "COMMIT")
                self.assertTrue(record["ooo"]["nonspeculative"])
                self.assertEqual(
                    record["ooo"]["dispatch_capabilities"]["CMD"],
                    "ENGINE_COMMAND",
                )

    def test_v057_tma_cube_and_scalar_delta_decode(self) -> None:
        from common.decode32 import decode32_meta
        from common.opcode_meta_gen import opcode_meta_forms_by_mnemonic

        tma_forms = (
            ("bstart_tload", 0x00011181),
            ("bstart_tstore", 0x00111181),
            ("bstart_tmov", 0x00211181),
            ("bstart_tprefetch", 0x00311181),
            ("bstart_mgather", 0x00411181),
            ("bstart_mscatter", 0x00511181),
            ("bstart_mgather_mask", 0x00611181),
            ("bstart_mscatter_mask", 0x00711181),
            ("bstart_mgather_cas", 0x00811181),
        )
        for expected_function, (mnemonic, raw) in enumerate(tma_forms):
            decoded = decode32_meta(raw)
            self.assertIsNotNone(decoded)
            self.assertEqual(decoded.mnemonic, mnemonic)
            self.assertEqual(decoded.symbol, "OP_BSTART_TMA")
            self.assertEqual((raw >> 20) & 0x1F, expected_function)
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
        for symbol in ("OP_LR_B", "OP_LR_H", "OP_LR_W", "OP_LR_D"):
            needle = f'Rule(symbol = "{symbol}"'
            start = scala.index(needle)
            rule = scala[start : scala.index("\n", start)]
            self.assertIn("category = CatLOAD", rule)
            self.assertIn("dispatch = 4", rule)
            self.assertIn("isLoad = true", rule)
            self.assertIn("isStore = false", rule)
        for symbol in ("OP_SC_B", "OP_SC_H", "OP_SC_W", "OP_SC_D"):
            needle = f'Rule(symbol = "{symbol}"'
            start = scala.index(needle)
            rule = scala[start : scala.index("\n", start)]
            self.assertIn("category = CatSTORE", rule)
            self.assertIn("dispatch = 4", rule)
            self.assertIn("isLoad = false", rule)
            self.assertIn("isStore = true", rule)

        for retired in (
            "texract",
            "tfill",
            "tfmod",
            "tfmods",
            "tlrelu",
            "tpow",
            "tpows",
            "tprelu",
            "trandom",
        ):
            self.assertEqual(opcode_meta_forms_by_mnemonic(f"bstart_{retired}"), ())


if __name__ == "__main__":
    unittest.main()
