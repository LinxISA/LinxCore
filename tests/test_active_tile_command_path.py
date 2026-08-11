from __future__ import annotations

from pathlib import Path
import unittest

from common.tile_commands import pack_bios_descriptor, pack_biot_descriptor


ROOT = Path(__file__).resolve().parents[1]


class ActiveTileCommandPathTest(unittest.TestCase):
    def test_shared_binding_descriptor_preserves_all_architectural_fields(self) -> None:
        self.assertEqual(pack_bios_descriptor(0xFF, 0xA, 7), 0x7AFF)
        self.assertEqual(pack_bios_descriptor(0, 0, 0), 0)

    def test_local_binding_descriptor_matches_the_runtime_transport_layout(self) -> None:
        self.assertEqual(
            pack_biot_descriptor(
                src0=0x21,
                src1=0x12,
                dst=3,
                last=1,
                pe_mask=0xA,
                tsize=2,
                func=4,
            ),
            0x48A0B4A1,
        )
        self.assertEqual(
            pack_biot_descriptor(
                src0=0x3F,
                src1=0,
                dst=1,
                last=0,
                pe_mask=0x1,
                tsize=0,
                func=5,
            ),
            0x0012103F,
        )
        self.assertEqual(
            pack_biot_descriptor(
                src0=0,
                src1=0,
                dst=2,
                last=1,
                pe_mask=0xF,
                tsize=7,
                func=6,
            ),
            0x52F3A000,
        )
        with self.assertRaises(ValueError):
            pack_biot_descriptor(0, 0, 0, 0, 0, 0, 3)

    def test_dispatch_classifies_shared_and_local_bindings_as_commands(self) -> None:
        source = (ROOT / "src/bcc/backend/dispatch.py").read_text(encoding="utf-8")
        self.assertIn("OP_B_IOT", source)
        self.assertIn("OP_B_IOS", source)

    def test_production_decode_uses_only_the_five_locked_local_forms(self) -> None:
        source = (ROOT / "src/common/decode.py").read_text(encoding="utf-8")
        for mask, match in (
            (0xFC00707F, 0x00005013),
            (0x00007E7F, 0x00004013),
            (0x0000707F, 0x00004013),
            (0xFC007E7F, 0x00005013),
            (0xFFF0707F, 0x00006013),
        ):
            self.assertIn(f"0x{mask:08X}, 0x{match:08X}", source)
        self.assertNotIn("mask=0x0000607F", source)
        self.assertIn("mask=0xF00871FF, match=0x00001013", source)


if __name__ == "__main__":
    unittest.main()
