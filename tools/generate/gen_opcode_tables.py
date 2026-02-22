#!/usr/bin/env python3
from __future__ import annotations

import argparse
from collections import OrderedDict
from pathlib import Path

from opcode_catalog_lib import CATEGORY_ORDER, load_catalog


def _emit_py_ids(out: Path, symbol_to_id: OrderedDict[str, int], legacy_aliases: dict[str, str]) -> None:
    lines: list[str] = []
    lines.append("from __future__ import annotations")
    lines.append("")
    lines.append("# AUTO-GENERATED FILE. DO NOT EDIT.")
    lines.append("")
    for sym, sid in symbol_to_id.items():
        lines.append(f"{sym} = {sid}")
    lines.append("")
    lines.append("# Legacy aliases kept for compatibility with existing LinxCore code.")
    for alias, target in sorted(legacy_aliases.items()):
        lines.append(f"{alias} = {target}")
    all_syms = list(symbol_to_id.keys()) + sorted(legacy_aliases.keys())
    lines.append("")
    lines.append("__all__ = [")
    for sym in all_syms:
        lines.append(f"    \"{sym}\",")
    lines.append("]")
    out.write_text("\n".join(lines) + "\n", encoding="utf-8")


def _emit_py_meta(out: Path, records: list[dict]) -> None:
    lines: list[str] = []
    lines.append("from __future__ import annotations")
    lines.append("")
    lines.append("from dataclasses import dataclass")
    lines.append("")
    lines.append("# AUTO-GENERATED FILE. DO NOT EDIT.")
    lines.append("")
    lines.append("CAT_BLOCK_BOUNDARY = 0")
    lines.append("CAT_BLOCK_ARGS_DESC = 1")
    lines.append("CAT_ALU_INT = 2")
    lines.append("CAT_BRU_SETC_CMP = 3")
    lines.append("CAT_LOAD = 4")
    lines.append("CAT_STORE = 5")
    lines.append("CAT_CMD_PIPE = 6")
    lines.append("CAT_MACRO_TEMPLATE = 7")
    lines.append("CAT_HL_PCR = 8")
    lines.append("CAT_VECTOR = 9")
    lines.append("CAT_FP_SYS = 10")
    lines.append("CAT_COMPRESSED = 11")
    lines.append("CAT_MISC = 12")
    lines.append("")
    lines.append("CAT_BY_NAME = {")
    for idx, cat in enumerate(CATEGORY_ORDER):
        lines.append(f"    \"{cat}\": {idx},")
    lines.append("}")
    lines.append("")
    lines.append("@dataclass(frozen=True)")
    lines.append("class OpcodeMeta:")
    lines.append("    op_id: int")
    lines.append("    symbol: str")
    lines.append("    mnemonic: str")
    lines.append("    major_cat: str")
    lines.append("    minor_cat: str")
    lines.append("    insn_len: int")
    lines.append("    mask: int")
    lines.append("    match: int")
    lines.append("    rd_kind: str")
    lines.append("    rs1_kind: str")
    lines.append("    rs2_kind: str")
    lines.append("    imm_kind: str")
    lines.append("    block_kind: str")
    lines.append("    cmd_kind: str")
    lines.append("    flags: str")
    lines.append("    source_file: str")
    lines.append("")
    lines.append("OPCODE_META_BY_MNEMONIC = {")
    for r in sorted(records, key=lambda x: x["mnemonic"]):
        lines.append(
            "    \"{mnemonic}\": OpcodeMeta(op_id={op_id}, symbol=\"{symbol}\", mnemonic=\"{mnemonic}\", "
            "major_cat=\"{major_cat}\", minor_cat=\"{minor_cat}\", insn_len={enc_len}, mask={mask}, match={match}, "
            "rd_kind=\"{rd_kind}\", rs1_kind=\"{rs1_kind}\", rs2_kind=\"{rs2_kind}\", imm_kind=\"{imm_kind}\", "
            "block_kind=\"{block_kind}\", cmd_kind=\"{cmd_kind}\", flags=\"{flags}\", source_file=\"{source_file}\"),".format(
                **r
            )
        )
    lines.append("}")
    lines.append("")
    lines.append("OPCODE_META_BY_ID = {}")
    lines.append("for _m in OPCODE_META_BY_MNEMONIC.values():")
    lines.append("    OPCODE_META_BY_ID.setdefault(_m.op_id, _m)")
    lines.append("")
    lines.append("def opcode_meta_by_mnemonic(mnemonic: str) -> OpcodeMeta | None:")
    lines.append("    return OPCODE_META_BY_MNEMONIC.get(mnemonic)")
    lines.append("")
    lines.append("def opcode_meta_by_id(op_id: int) -> OpcodeMeta | None:")
    lines.append("    return OPCODE_META_BY_ID.get(op_id)")
    out.write_text("\n".join(lines) + "\n", encoding="utf-8")


def _emit_qemu_ids(out: Path, symbol_to_id: OrderedDict[str, int]) -> None:
    lines: list[str] = []
    lines.append("/* AUTO-GENERATED FILE. DO NOT EDIT. */")
    lines.append("#ifndef LINX_OPCODE_IDS_GEN_H")
    lines.append("#define LINX_OPCODE_IDS_GEN_H")
    lines.append("")
    lines.append("typedef enum LinxOpcodeId {")
    for sym, sid in symbol_to_id.items():
        c_sym = sym.replace("OP_", "LINX_OP_")
        lines.append(f"    {c_sym} = {sid},")
    lines.append("} LinxOpcodeId;")
    lines.append("")
    lines.append("#endif /* LINX_OPCODE_IDS_GEN_H */")
    out.write_text("\n".join(lines) + "\n", encoding="utf-8")


def _emit_qemu_meta(out: Path, records: list[dict]) -> None:
    lines: list[str] = []
    lines.append("/* AUTO-GENERATED FILE. DO NOT EDIT. */")
    lines.append("#ifndef LINX_OPCODE_META_GEN_H")
    lines.append("#define LINX_OPCODE_META_GEN_H")
    lines.append("")
    lines.append("#include <stdint.h>")
    lines.append("#include \"linx_opcode_ids_gen.h\"")
    lines.append("")
    lines.append("typedef struct LinxOpcodeMeta {")
    lines.append("    uint16_t op_id;")
    lines.append("    uint8_t major_cat;")
    lines.append("    uint8_t insn_len;")
    lines.append("    uint64_t mask;")
    lines.append("    uint64_t match;")
    lines.append("    const char *mnemonic;")
    lines.append("    const char *minor_cat;")
    lines.append("    const char *rd_kind;")
    lines.append("    const char *rs1_kind;")
    lines.append("    const char *rs2_kind;")
    lines.append("    const char *imm_kind;")
    lines.append("    const char *block_kind;")
    lines.append("    const char *cmd_kind;")
    lines.append("    const char *flags;")
    lines.append("    const char *source_file;")
    lines.append("} LinxOpcodeMeta;")
    lines.append("")
    lines.append("enum {")
    for idx, cat in enumerate(CATEGORY_ORDER):
        lines.append(f"    LINX_CAT_{cat} = {idx},")
    lines.append("};")
    lines.append("")
    lines.append("static const LinxOpcodeMeta linx_opcode_meta_table[] = {")
    for r in sorted(records, key=lambda x: (x["op_id"], x["mnemonic"])):
        lines.append(
            "    {{.op_id={op_id}, .major_cat=LINX_CAT_{major_cat}, .insn_len={enc_len}, .mask=UINT64_C({mask}), .match=UINT64_C({match}), "
            ".mnemonic=\"{mnemonic}\", .minor_cat=\"{minor_cat}\", .rd_kind=\"{rd_kind}\", .rs1_kind=\"{rs1_kind}\", "
            ".rs2_kind=\"{rs2_kind}\", .imm_kind=\"{imm_kind}\", .block_kind=\"{block_kind}\", .cmd_kind=\"{cmd_kind}\", .flags=\"{flags}\", .source_file=\"{source_file}\"}},".format(
                **r
            )
        )
    lines.append("};")
    lines.append("")
    lines.append("static const unsigned linx_opcode_meta_table_count = sizeof(linx_opcode_meta_table) / sizeof(linx_opcode_meta_table[0]);")
    lines.append("")
    lines.append("#endif /* LINX_OPCODE_META_GEN_H */")
    out.write_text("\n".join(lines) + "\n", encoding="utf-8")


def main() -> int:
    ap = argparse.ArgumentParser(description="Generate LinxCore and QEMU opcode id/meta files from catalog")
    ap.add_argument("--catalog", default="/Users/zhoubot/LinxCore/src/common/opcode_catalog.yaml")
    ap.add_argument("--linxcore-common", default="/Users/zhoubot/LinxCore/src/common")
    ap.add_argument("--qemu-linx-dir", default="/Users/zhoubot/qemu/target/linx")
    args = ap.parse_args()

    catalog = load_catalog(Path(args.catalog))
    records: list[dict] = list(catalog["records"])

    symbol_to_id: OrderedDict[str, int] = OrderedDict()
    symbol_to_id["OP_INVALID"] = 0
    for r in sorted(records, key=lambda x: (x["op_id"], x["symbol"])):
        sym = r["symbol"]
        sid = int(r["op_id"])
        if sym not in symbol_to_id:
            symbol_to_id[sym] = sid

    legacy_aliases = {
        # Keep existing backend dispatch/engine names stable.
        "OP_BLOAD": "OP_BLOAD",
        "OP_BSTORE": "OP_BSTORE",
        # Legacy compression path helper.
        "OP_C_SETRET": "OP_C_SETRET",
        # Old BSTART naming used through backend/control paths.
        "OP_BSTART_STD_CALL": "OP_BSTART_STD_CALL",
        "OP_BSTART_STD_COND": "OP_BSTART_STD_COND",
        "OP_BSTART_STD_DIRECT": "OP_BSTART_STD_DIRECT",
        "OP_BSTART_STD_FALL": "OP_BSTART_STD_FALL",
        "OP_C_BSTART_STD": "OP_C_BSTART_STD",
    }

    lc_dir = Path(args.linxcore_common)
    qemu_dir = Path(args.qemu_linx_dir)
    lc_dir.mkdir(parents=True, exist_ok=True)

    _emit_py_ids(lc_dir / "opcode_ids_gen.py", symbol_to_id, legacy_aliases)
    _emit_py_meta(lc_dir / "opcode_meta_gen.py", records)

    qemu_dir.mkdir(parents=True, exist_ok=True)
    _emit_qemu_ids(qemu_dir / "linx_opcode_ids_gen.h", symbol_to_id)
    _emit_qemu_meta(qemu_dir / "linx_opcode_meta_gen.h", records)

    print(f"generated {lc_dir / 'opcode_ids_gen.py'}")
    print(f"generated {lc_dir / 'opcode_meta_gen.py'}")
    print(f"generated {qemu_dir / 'linx_opcode_ids_gen.h'}")
    print(f"generated {qemu_dir / 'linx_opcode_meta_gen.h'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
