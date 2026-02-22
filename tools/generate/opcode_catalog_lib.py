#!/usr/bin/env python3
from __future__ import annotations

import json
import re
from dataclasses import dataclass
from pathlib import Path
from typing import Dict, Iterable, List

QEMU_DECODE_FILES = (
    ("insn16.decode", 16),
    ("insn32.decode", 32),
    ("insn48.decode", 64),
    ("insn64.decode", 64),
)

CATEGORY_ORDER = [
    "BLOCK_BOUNDARY",
    "BLOCK_ARGS_DESC",
    "ALU_INT",
    "BRU_SETC_CMP",
    "LOAD",
    "STORE",
    "CMD_PIPE",
    "MACRO_TEMPLATE",
    "HL_PCR",
    "VECTOR",
    "FP_SYS",
    "COMPRESSED",
    "MISC",
]

MISC_INTERNAL_MNEMONICS = (
    "internal_invalid",
    "internal_c_bstart_std",
    "internal_c_setret",
)

LEGACY_SYMBOL_OVERRIDES = {
    # Keep existing LinxCore symbols stable where possible.
    "bstart_call": "OP_BSTART_STD_CALL",
    "bstart_cond": "OP_BSTART_STD_COND",
    "bstart_direct": "OP_BSTART_STD_DIRECT",
    "hl_bstart_std_fall": "OP_BSTART_STD_FALL",
    "hl_bstart_std_call": "OP_BSTART_STD_CALL",
    "hl_bstart_std_cond": "OP_BSTART_STD_COND",
    "hl_bstart_std_direct": "OP_BSTART_STD_DIRECT",
    "c_bstart_cond": "OP_C_BSTART_COND",
    "c_bstart_direct": "OP_C_BSTART_DIRECT",
    "c_bstop": "OP_C_BSTOP",
    "b_text": "OP_BTEXT",
    "b_ior": "OP_BIOR",
    "b_iot": "OP_BLOAD",
    "b_ioti": "OP_BSTORE",
    "internal_invalid": "OP_INVALID",
    "internal_c_bstart_std": "OP_C_BSTART_STD",
    "internal_c_setret": "OP_C_SETRET",
}


@dataclass(frozen=True)
class DecodeEntry:
    mnemonic: str
    file: str
    enc_len: int
    pattern: str
    mask: int
    match: int
    fields: List[str]


def _pattern_to_mask_match(bits: str) -> tuple[int, int]:
    mask = 0
    match = 0
    for ch in bits:
        mask <<= 1
        match <<= 1
        if ch in "01":
            mask |= 1
            if ch == "1":
                match |= 1
    return mask, match


def parse_decode_file(path: Path, enc_len: int) -> List[DecodeEntry]:
    out: List[DecodeEntry] = []
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.split("#", 1)[0].strip()
        if not line:
            continue
        if line.startswith(("%", "{", "}")):
            continue
        m = re.match(r"^([A-Za-z0-9_.]+)\s+(.+)$", line)
        if not m:
            continue
        mnemonic = m.group(1)
        rest = m.group(2).strip()
        toks = rest.split()
        patt: List[str] = []
        idx = 0
        for tok in toks:
            if re.fullmatch(r"[01.]+", tok):
                patt.append(tok)
                idx += 1
                continue
            break
        if not patt:
            continue
        bits = "".join(patt)
        if len(bits) != enc_len:
            # Decodetree lines can be malformed for our parser only if non-bit
            # tokens got mixed in; skip those lines safely.
            continue
        mask, match = _pattern_to_mask_match(bits)
        fields = toks[idx:]
        out.append(
            DecodeEntry(
                mnemonic=mnemonic,
                file=path.name,
                enc_len=enc_len,
                pattern=bits,
                mask=mask,
                match=match,
                fields=fields,
            )
        )
    return out


def load_qemu_entries(qemu_linx_dir: Path) -> List[DecodeEntry]:
    all_entries: List[DecodeEntry] = []
    for fname, width in QEMU_DECODE_FILES:
        all_entries.extend(parse_decode_file(qemu_linx_dir / fname, width))
    return all_entries


def mnemonic_to_symbol(mnemonic: str) -> str:
    if mnemonic in LEGACY_SYMBOL_OVERRIDES:
        return LEGACY_SYMBOL_OVERRIDES[mnemonic]
    name = mnemonic.upper().replace(".", "_")
    name = re.sub(r"[^A-Z0-9_]", "_", name)
    name = re.sub(r"_+", "_", name).strip("_")
    return f"OP_{name}"


def classify_major_minor(mnemonic: str) -> tuple[str, str]:
    m = mnemonic.lower()
    if m.startswith("internal_"):
        return "MISC", "internal"
    if m.startswith("v_"):
        return "VECTOR", "vector"
    if m.startswith("hl_") and m.endswith("_pcr"):
        return "HL_PCR", "hl_pcr"
    if m in {"fentry", "fexit", "fret_ra", "fret_stk", "mcopy", "mset", "esave", "ercov"}:
        return "MACRO_TEMPLATE", "template"
    if m.startswith("c_"):
        if m.startswith("c_bstart") or m == "c_bstop":
            return "BLOCK_BOUNDARY", "c_boundary"
        if m.startswith("c_setc") or m.startswith("c_cmp"):
            return "BRU_SETC_CMP", "c_pred"
        return "COMPRESSED", "compressed"
    if m.startswith("bstart"):
        return "BLOCK_BOUNDARY", "boundary"
    if m in {"b_z", "b_nz", "setc_tgt"}:
        return "BRU_SETC_CMP", "branch_pred"
    if m.startswith("setc") or m.startswith("cmp_"):
        return "BRU_SETC_CMP", "setc_cmp"
    if m in {"b_text", "b_ior", "b_iot", "b_ioti"}:
        return "CMD_PIPE", "block_cmd"
    if m.startswith("b_"):
        return "BLOCK_ARGS_DESC", "block_desc"
    if re.match(r"^l[bhwd]", m) or m in {"lbi", "lhi", "lhui", "lwui", "ldi", "lwi", "lbui", "lw_pcr"}:
        return "LOAD", "load"
    if re.match(r"^s[bhwd]", m) or m in {"sbi", "shi", "sdi", "swi", "sw_pcr"}:
        return "STORE", "store"
    if m in {"feq", "flt", "fge", "fadd", "fsub", "fmul", "fdiv", "fcvt", "fcvtz", "fabs"}:
        return "FP_SYS", "fp"
    if m in {"ebreak", "ecall", "ssrget", "ssrset", "ssrswap", "hl_ssrget", "hl_ssrset", "acrc", "acre"}:
        return "FP_SYS", "sys"
    if m in {"setret", "addtpc", "hl_addtpc"}:
        return "BRU_SETC_CMP", "setret_addtpc"
    if m.startswith("hl_"):
        return "ALU_INT", "hl_alu"
    if m.startswith(("add", "sub", "and", "or", "xor", "mul", "div", "rem", "sll", "srl", "sra", "csel", "bcnt", "bic", "bis", "bxs", "bxu", "clz", "ctz", "lui")):
        return "ALU_INT", "alu"
    return "MISC", "misc"


def classify_fields(fields: Iterable[str]) -> tuple[str, str, str, str]:
    rd_kind = "NONE"
    rs1_kind = "NONE"
    rs2_kind = "NONE"
    imm_kind = "NONE"
    tokens = list(fields)
    for tok in tokens:
        core = tok
        if "=" in core:
            core = core.split("=", 1)[1]
        if core.startswith("%"):
            core = core[1:]
        name = core.lower()
        if name in {"regdst", "rd", "dsttype"}:
            rd_kind = "REG"
        elif name in {"srcl", "src0", "srca"}:
            rs1_kind = "REG"
        elif name in {"srcr", "src1", "srcd", "srcp"}:
            rs2_kind = "REG"
        if "imm" in name:
            if imm_kind == "NONE":
                imm_kind = name.upper()
    return rd_kind, rs1_kind, rs2_kind, imm_kind


def cmd_kind_for_mnemonic(mnemonic: str) -> str:
    return {
        "b_text": "BTEXT",
        "b_ior": "BIOR",
        "b_iot": "BLOAD",
        "b_ioti": "BSTORE",
    }.get(mnemonic, "NONE")


def block_kind_for_mnemonic(mnemonic: str) -> str:
    m = mnemonic.lower()
    if "bstart" in m:
        if "call" in m:
            return "CALL"
        if "cond" in m:
            return "COND"
        if "direct" in m:
            return "DIRECT"
        if "fall" in m:
            return "FALL"
        if "ret" in m:
            return "RET"
        return "BLOCK"
    if m in {"c_bstop", "bstop"}:
        return "STOP"
    return "NONE"


def build_catalog(qemu_linx_dir: Path) -> Dict[str, object]:
    entries = load_qemu_entries(qemu_linx_dir)
    by_mnemonic: Dict[str, DecodeEntry] = {}
    for e in entries:
        by_mnemonic.setdefault(e.mnemonic, e)

    for m in MISC_INTERNAL_MNEMONICS:
        if m not in by_mnemonic:
            by_mnemonic[m] = DecodeEntry(
                mnemonic=m,
                file="internal",
                enc_len=0,
                pattern="",
                mask=0,
                match=0,
                fields=[],
            )

    records = []
    for mnemonic in sorted(by_mnemonic.keys()):
        e = by_mnemonic[mnemonic]
        major, minor = classify_major_minor(mnemonic)
        rd_kind, rs1_kind, rs2_kind, imm_kind = classify_fields(e.fields)
        records.append(
            {
                "mnemonic": mnemonic,
                "symbol": mnemonic_to_symbol(mnemonic),
                "enc_len": e.enc_len,
                "pattern": e.pattern,
                "mask": f"0x{e.mask:x}",
                "match": f"0x{e.match:x}",
                "major_cat": major,
                "minor_cat": minor,
                "rd_kind": rd_kind,
                "rs1_kind": rs1_kind,
                "rs2_kind": rs2_kind,
                "imm_kind": imm_kind,
                "block_kind": block_kind_for_mnemonic(mnemonic),
                "cmd_kind": cmd_kind_for_mnemonic(mnemonic),
                "flags": "",
                "source_file": e.file,
            }
        )

    sym_to_cat: Dict[str, str] = {}
    for r in records:
        sym_to_cat.setdefault(r["symbol"], r["major_cat"])

    ordered_symbols = sorted(
        sym_to_cat.keys(),
        key=lambda s: (
            CATEGORY_ORDER.index(sym_to_cat[s]) if sym_to_cat[s] in CATEGORY_ORDER else len(CATEGORY_ORDER),
            s,
        ),
    )
    sym_to_id = {sym: idx + 1 for idx, sym in enumerate(ordered_symbols)}
    sym_to_id["OP_INVALID"] = 0

    for r in records:
        r["op_id"] = sym_to_id[r["symbol"]]

    return {
        "version": 1,
        "category_order": CATEGORY_ORDER,
        "records": records,
    }


def load_catalog(path: Path) -> Dict[str, object]:
    return json.loads(path.read_text(encoding="utf-8"))


def save_catalog(path: Path, catalog: Dict[str, object]) -> None:
    path.write_text(json.dumps(catalog, indent=2, sort_keys=False) + "\n", encoding="utf-8")
