#!/usr/bin/env python3
from __future__ import annotations

import os
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
    "bstart_tload": "OP_BSTART_TMA",
    "bstart_tstore": "OP_BSTART_TMA",
    "bstart_tmov": "OP_BSTART_TMA",
    "bstart_tmatmul": "OP_BSTART_CUBE",
    "bstart_tmatmul_acc": "OP_BSTART_CUBE",
    "bstart_tmatmul_bias": "OP_BSTART_CUBE",
    "bstart_tmatmulmx": "OP_BSTART_CUBE",
    "bstart_tmatmulmx_acc": "OP_BSTART_CUBE",
    "bstart_tmatmulmx_bias": "OP_BSTART_CUBE",
    "bstart_tgemv": "OP_BSTART_CUBE",
    "bstart_tgemv_acc": "OP_BSTART_CUBE",
    "bstart_tgemv_bias": "OP_BSTART_CUBE",
    "bstart_tgemvmx": "OP_BSTART_CUBE",
    "bstart_tgemvmx_acc": "OP_BSTART_CUBE",
    "bstart_tgemvmx_bias": "OP_BSTART_CUBE",
    "bstart_tprefetch": "OP_BSTART_TMA",
    "bstart_mgather": "OP_BSTART_TMA",
    "bstart_mscatter": "OP_BSTART_TMA",
    "bstart_mgather_mask": "OP_BSTART_TMA",
    "bstart_mscatter_mask": "OP_BSTART_TMA",
    "bstart_mgather_cas": "OP_BSTART_TMA",
    "hl_bstart_std_fall": "OP_BSTART_STD_FALL",
    "hl_bstart_std_call": "OP_BSTART_STD_CALL",
    "hl_bstart_std_cond": "OP_BSTART_STD_COND",
    "hl_bstart_std_direct": "OP_BSTART_STD_DIRECT",
    "c_bstart_cond": "OP_C_BSTART_COND",
    "c_bstart_direct": "OP_C_BSTART_DIRECT",
    "c_bstop": "OP_C_BSTOP",
    "b_text": "OP_BTEXT",
    "b_ior": "OP_BIOR",
    "internal_invalid": "OP_INVALID",
    "internal_c_bstart_std": "OP_C_BSTART_STD",
    "internal_c_setret": "OP_C_SETRET",
}

OP_ID_CATEGORY_OVERRIDES = {
    # Preserve existing opcode IDs when repairing the product category.
    "OP_HL_SDI": "ALU_INT",
    "OP_HL_SDI_PO": "ALU_INT",
    "OP_HL_SDI_PR": "ALU_INT",
    "OP_HL_SDI_U": "ALU_INT",
    "OP_HL_SDI_UPO": "ALU_INT",
    "OP_HL_SDI_UPR": "ALU_INT",
    "OP_LR_B": "MISC",
    "OP_LR_D": "MISC",
    "OP_LR_H": "MISC",
    "OP_LR_W": "MISC",
    "OP_SC_B": "MISC",
    "OP_SC_D": "MISC",
    "OP_SC_H": "MISC",
    "OP_SC_W": "MISC",
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
    source_profile: str = ""


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


def _normalize_qemu_pattern(bits: str, enc_len: int, file_name: str) -> str | None:
    if len(bits) == enc_len:
        return bits
    if file_name == "insn48.decode" and enc_len == 64 and len(bits) == 48:
        # QEMU decodes 48-bit instructions through a 64-bit container with the
        # top 16 bits zeroed. Some insn48 forms spell those container bits
        # explicitly; others only spell the low 48 payload bits. Normalize both
        # forms to the same packed-64 representation.
        return ("0" * 16) + bits
    return None


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
        normalized_bits = _normalize_qemu_pattern(bits, enc_len, path.name)
        if normalized_bits is None:
            # Decodetree lines can be malformed for our parser only if non-bit
            # tokens got mixed in; skip those lines safely.
            continue
        mask, match = _pattern_to_mask_match(normalized_bits)
        fields = toks[idx:]
        out.append(
            DecodeEntry(
                mnemonic=mnemonic,
                file=path.name,
                enc_len=enc_len,
                pattern=normalized_bits,
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
    hl_sdi_immediate_stores = {
        "hl_sdi",
        "hl_sdi_u",
        "hl_sdi_po",
        "hl_sdi_pr",
        "hl_sdi_upo",
        "hl_sdi_upr",
    }
    if m.startswith("internal_"):
        return "MISC", "internal"
    if m.startswith("v_"):
        return "VECTOR", "vector"
    if m.startswith("hl_") and m.endswith("_pcr"):
        return "HL_PCR", "hl_pcr"
    if m in {"fentry", "fexit", "fret_ra", "fret_stk", "mcopy", "mset", "esave", "ercov"}:
        return "MACRO_TEMPLATE", "template"
    if m in {"lr_b", "lr_h", "lr_w", "lr_d"}:
        return "LOAD", "atomic"
    if m in {"sc_b", "sc_h", "sc_w", "sc_d"}:
        return "STORE", "atomic"
    if m in {"casb", "cash", "casw", "casd", "dma"}:
        return "ALU_INT", "atomic"
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
    if m in {"b_text", "b_ior", "b_iot"}:
        return "CMD_PIPE", "block_cmd"
    if m.startswith("b_"):
        return "BLOCK_ARGS_DESC", "block_desc"
    if re.match(r"^l[bhwd]", m) or m in {"lbi", "lhi", "lhui", "lwui", "ldi", "lwi", "lbui", "lw_pcr"}:
        return "LOAD", "load"
    if re.match(r"^s[bhwd]", m) or m in {"sbi", "shi", "sdi", "swi", "sw_pcr"} or m in hl_sdi_immediate_stores:
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
        if name in {"regdst", "regdst1", "rd", "dsttype"}:
            rd_kind = "REG"
        elif name in {"srcl", "src0", "srca", "srcd"}:
            rs1_kind = "REG"
        elif name in {"srcr", "src1", "srcp"}:
            rs2_kind = "REG"
        if "imm" in name:
            if imm_kind == "NONE":
                imm_kind = name.upper()
    return rd_kind, rs1_kind, rs2_kind, imm_kind


def cmd_kind_for_mnemonic(mnemonic: str) -> str:
    return {
        "b_text": "BTEXT",
        "b_ior": "BIOR",
        "b_iot": "BIOT",
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


OOO_PAIR_LOAD_SYMBOLS = {
    "OP_HL_LBP", "OP_HL_LHP", "OP_HL_LWP", "OP_HL_LDP",
    "OP_HL_LBUP", "OP_HL_LHUP", "OP_HL_LWUP", "OP_HL_LBIP",
    "OP_HL_LHIP", "OP_HL_LWIP", "OP_HL_LDIP", "OP_HL_LBUIP",
    "OP_HL_LHUIP", "OP_HL_LWUIP", "OP_HL_LHIP_U", "OP_HL_LWIP_U",
    "OP_HL_LDIP_U", "OP_HL_LHUIP_U", "OP_HL_LWUIP_U",
}

OOO_PAIR_STORE_SYMBOLS = {
    "OP_HL_SBP", "OP_HL_SHP", "OP_HL_SWP", "OP_HL_SDP",
    "OP_HL_SHP_U", "OP_HL_SWP_U", "OP_HL_SDP_U", "OP_HL_SBIP",
    "OP_HL_SHIP", "OP_HL_SWIP", "OP_HL_SDIP", "OP_HL_SHIP_U",
    "OP_HL_SWIP_U", "OP_HL_SDIP_U",
}

# Indexed scalar stores encode three architectural sources: SrcD carries the
# store data while SrcL/SrcR form the address.  The generic decode-field
# classifier can represent only rs1/rs2, so the OOO recipe must account for
# SrcD explicitly or rename will under-declare the physical source demand.
OOO_INDEXED_SCALAR_STORE_SYMBOLS = {
    "OP_SB", "OP_SH", "OP_SW", "OP_SD",
    "OP_SH_U", "OP_SW_U", "OP_SD_U",
}

OOO_PC_READ_BRU_SYMBOLS = {
    "OP_ADDTPC", "OP_HL_ADDTPC", "OP_B_NZ", "OP_B_Z", "OP_J", "OP_JR",
}
OOO_PC_READ_AGU_SYMBOLS = {
    "OP_LB_PCR", "OP_LBU_PCR", "OP_LD_PCR", "OP_LH_PCR",
    "OP_LHU_PCR", "OP_LW_PCR", "OP_LWU_PCR",
    "OP_SB_PCR", "OP_SD_PCR", "OP_SH_PCR", "OP_SW_PCR",
}
OOO_PC_READ_ALU_SYMBOLS = {
    "OP_HL_LB_PCR", "OP_HL_LBU_PCR", "OP_HL_LD_PCR", "OP_HL_LH_PCR",
    "OP_HL_LHU_PCR", "OP_HL_LW_PCR", "OP_HL_LWU_PCR",
    "OP_HL_SB_PCR", "OP_HL_SD_PCR", "OP_HL_SH_PCR", "OP_HL_SW_PCR",
}

OOO_CTU_SYMBOLS = {"OP_FENTRY", "OP_FEXIT", "OP_FRET_RA", "OP_FRET_STK"}
OOO_UNRESOLVED_TEMPLATE_SYMBOLS = {"OP_ERCOV", "OP_ESAVE", "OP_MCOPY", "OP_MSET"}
OOO_START_CALL_SYMBOLS = {"OP_START_CALL_32", "OP_START_CALL_48"}
OOO_SETRET_SYMBOLS = {"OP_SETRET", "OP_C_SETRET", "OP_HL_SETRET"}
OOO_ATOMIC_PREFIXES = ("OP_LR_", "OP_SC_", "OP_CAS", "OP_HL_CAS", "OP_AMO")
OOO_ENGINE_BOUNDARY_SYMBOLS = {
    "OP_BSTART_CUBE", "OP_BSTART_FIXP", "OP_BSTART_MPAR", "OP_BSTART_MSEQ",
    "OP_BSTART_TEPL", "OP_BSTART_TMA", "OP_BSTART_VPAR", "OP_BSTART_VSEQ",
    "OP_BSTART_SYS", "OP_HL_BSTART_SYS", "OP_L_BSTART_SYS",
    "OP_C_BSTART_MPAR", "OP_C_BSTART_MSEQ", "OP_C_BSTART_SYS",
    "OP_C_BSTART_VPAR", "OP_C_BSTART_VSEQ",
}
OOO_DISPATCH_CLASSES = ("ALU", "BRU", "AGU", "STD", "FSU", "SYS", "CMD", "BOUNDARY")


def _is_ooo_atomic_symbol(symbol: str) -> bool:
    if symbol.startswith(OOO_ATOMIC_PREFIXES) or symbol == "OP_DMA":
        return True
    if symbol.startswith("OP_SWAP"):
        return True
    return bool(re.match(r"^OP_(?:LW|LD|SW|SD)_(?:ADD|AND|OR|XOR|SMAX|SMIN|UMAX|UMIN)$", symbol))


def derive_ooo_metadata(record: Dict[str, object]) -> Dict[str, object]:
    """Derive the production OOO recipe contract for one catalog form.

    The contract deliberately fails closed where the ISA/model evidence does
    not yet define an exact child stream.  This metadata is consumed by the
    Chisel OOO generator; it is not an execution-semantics substitute.
    """
    symbol = str(record["symbol"])
    major = str(record["major_cat"])
    minor = str(record["minor_cat"])
    block_kind = str(record["block_kind"])
    source_file = str(record["source_file"])
    is_internal = source_file == "internal"

    metadata: Dict[str, object] = {
        "disposition": "ILLEGAL",
        "recipe_kind": "ILLEGAL",
        "uop_count_min": 0,
        "uop_count_max": 0,
        "complex_break": False,
        "late_split_kind": "NONE",
        "fusion_head_class": "NONE",
        "fusion_tail_class": "NONE",
        "fast_resolve_class": "NONE",
        "implicit_source_mask": 0,
        "implicit_destination": "NONE",
        "side_effect_owner": "ILLEGAL",
        "requires_target_validation": False,
        "may_trap": False,
        "may_trap_late": False,
        "may_redirect": False,
        "nonspeculative": False,
        "dispatch_class": "NONE",
        "dispatch_writes": 0,
        "dispatch_demand": {name: 0 for name in OOO_DISPATCH_CLASSES},
        "memory_request_count": 0,
        "p_source_count": int(record["rs1_kind"] == "REG") + int(record["rs2_kind"] == "REG"),
        "p_destination_count": int(record["rd_kind"] == "REG"),
        "t_allocation_count": 0,
        "u_allocation_count": 0,
        "reason": "unclassified opcode must trap before dispatch",
        "pc_read_parent": "NONE",
        "pc_read_class": "NONE",
    }

    if symbol in OOO_PC_READ_BRU_SYMBOLS:
        metadata.update({"pc_read_parent": "PRIMARY", "pc_read_class": "BRU"})
    elif symbol in OOO_PC_READ_AGU_SYMBOLS:
        metadata.update({"pc_read_parent": "PRIMARY", "pc_read_class": "AGU"})
    elif symbol in OOO_PC_READ_ALU_SYMBOLS:
        metadata.update({"pc_read_parent": "PRIMARY", "pc_read_class": "ALU"})

    def dispatch(
        recipe: str,
        dispatch_class: str,
        owner: str,
        *,
        children: int = 1,
        demand: Dict[str, int] | None = None,
    ) -> None:
        dispatch_demand = {name: 0 for name in OOO_DISPATCH_CLASSES}
        if demand is None:
            dispatch_demand[dispatch_class] = children
        else:
            dispatch_demand.update(demand)
        metadata.update({
            "disposition": "DISPATCH",
            "recipe_kind": recipe,
            "uop_count_min": children,
            "uop_count_max": children,
            "side_effect_owner": owner,
            "dispatch_class": dispatch_class,
            "dispatch_writes": children,
            "dispatch_demand": dispatch_demand,
            "reason": "generated dispatch recipe",
        })

    def fast(recipe: str, fast_class: str, owner: str) -> None:
        metadata.update({
            "disposition": "FAST_RESOLVE",
            "recipe_kind": recipe,
            "uop_count_min": 1,
            "uop_count_max": 1,
            "fast_resolve_class": fast_class,
            "side_effect_owner": owner,
            "reason": "generated ordered fast-resolve recipe",
        })

    if symbol == "OP_INVALID" or (is_internal and symbol not in {"OP_C_SETRET", "OP_C_BSTART_STD"}):
        metadata["reason"] = "internal or invalid opcode"
        return metadata

    if symbol in OOO_CTU_SYMBOLS:
        is_return = symbol in {"OP_FRET_RA", "OP_FRET_STK"}
        metadata.update({
            "disposition": "CTU",
            "recipe_kind": "CTU_TEMPLATE",
            "uop_count_min": 3 if is_return else 2,
            "uop_count_max": 24 if is_return else 23,
            "complex_break": True,
            "side_effect_owner": "CTU",
            "may_trap": True,
            "may_trap_late": True,
            "reason": "external CTU owns the canonical child stream",
        })
        return metadata

    if symbol in OOO_UNRESOLVED_TEMPLATE_SYMBOLS:
        metadata.update({
            "complex_break": True,
            "may_trap": True,
            "reason": "template child recipe is not yet architecturally frozen",
        })
        return metadata

    if _is_ooo_atomic_symbol(symbol):
        metadata.update({
            "recipe_kind": "ATOMIC_UNRESOLVED",
            "may_trap": True,
            "may_trap_late": True,
            "nonspeculative": True,
            "side_effect_owner": "LSU",
            "reason": "atomic child and terminal-result ownership must be explicit",
        })
        return metadata

    if symbol in OOO_PAIR_LOAD_SYMBOLS:
        dispatch("PAIR_LOAD", "AGU", "LSU")
        metadata.update({
            "uop_count_min": 1,
            "uop_count_max": 1,
            "may_trap": True,
            "may_trap_late": True,
            "memory_request_count": 2,
            "p_destination_count": 2,
            "reason": "one LDA_PAIR owner publishes two atomic LSU results",
        })
        return metadata

    if symbol in OOO_PAIR_STORE_SYMBOLS:
        dispatch(
            "PAIR_STORE",
            "STD",
            "LSU",
            children=2,
            demand={"AGU": 1, "STD": 1},
        )
        metadata.update({
            "late_split_kind": "PAIR_STORE_ADDRESS_DATA",
            "may_trap": True,
            "may_trap_late": True,
            "nonspeculative": True,
            "memory_request_count": 2,
            # The IP forms carry an immediate and therefore use data0, data1,
            # and base.  The register-indexed P forms add SrcR as a fourth
            # source; QEMU insn48.decode is the encoding authority here.
            "p_source_count": 3 if "IP" in symbol else 4,
            "reason": "atomic STA_PAIR plus STD_PAIR publication with two LSU stores",
        })
        return metadata

    if symbol in OOO_START_CALL_SYMBOLS:
        fast("START_CALL", "CONTROL_VALUE_PRODUCER", "BCTRL")
        metadata.update({
            "implicit_destination": "RA",
            "p_destination_count": 1,
            "requires_target_validation": True,
            "may_redirect": True,
        })
        return metadata

    if symbol in OOO_SETRET_SYMBOLS:
        fast("SETRET", "IMMEDIATE_PRODUCER", "IEX")
        metadata.update({
            "implicit_destination": "RA",
            "p_destination_count": 1,
            "may_trap": True,
            "reason": "IQ-bypass RA producer with CALL/ICALL sequence validation",
        })
        return metadata

    if symbol in OOO_ENGINE_BOUNDARY_SYMBOLS:
        dispatch("ENGINE_CMD", "CMD", "BCTRL")
        metadata.update({
            "may_trap": True,
            "may_redirect": True,
            "nonspeculative": True,
            "reason": "engine header requires a command child plus BROB metadata",
        })
        return metadata

    if block_kind != "NONE" or symbol in {"OP_BSTOP", "OP_C_BSTART_STD"}:
        metadata.update({
            "disposition": "FAST_RESOLVE",
            "recipe_kind": "BOUNDARY",
            "uop_count_min": 0,
            "uop_count_max": 1,
            "fusion_head_class": "START_MARKER" if block_kind != "STOP" else "NONE",
            "fusion_tail_class": "STOP_MARKER" if block_kind == "STOP" or symbol == "OP_BSTOP" else "NONE",
            "fast_resolve_class": "BOUNDARY_METADATA",
            "side_effect_owner": "BCTRL",
            "requires_target_validation": block_kind in {"CALL", "COND", "DIRECT", "RET"},
            "may_trap": True,
            "may_redirect": True,
            "nonspeculative": True,
            "reason": "pure boundary may fuse or publish one ordered metadata member",
        })
        return metadata

    if symbol == "OP_EBREAK":
        fast("PRECISE_TRAP", "PRECISE_TRAP_RECORD", "COMMIT")
        metadata.update({"may_trap": True, "nonspeculative": True})
        return metadata

    if major == "LOAD":
        dispatch("SCALAR_LOAD", "AGU", "LSU")
        metadata.update({"may_trap": True, "may_trap_late": True, "memory_request_count": 1})
        return metadata

    if major == "STORE":
        dispatch(
            "SCALAR_STORE",
            "STD",
            "LSU",
            children=2,
            demand={"AGU": 1, "STD": 1},
        )
        metadata.update({
            "late_split_kind": "STORE_ADDRESS_DATA",
            "may_trap": True,
            "may_trap_late": True,
            "memory_request_count": 1,
            "p_source_count": 3 if symbol in OOO_INDEXED_SCALAR_STORE_SYMBOLS
            else metadata["p_source_count"],
        })
        return metadata

    if major == "BRU_SETC_CMP":
        dispatch("SINGLE", "BRU", "BCTRL")
        metadata["may_redirect"] = symbol.startswith(("OP_B_", "OP_J"))
        return metadata

    if major in {"ALU_INT", "COMPRESSED", "HL_PCR"}:
        dispatch("SINGLE", "ALU", "IEX")
        return metadata

    if major == "VECTOR":
        dispatch("SINGLE", "FSU", "IEX")
        return metadata

    if major == "FP_SYS" and minor == "fp":
        dispatch("SINGLE", "FSU", "IEX")
        return metadata

    if major == "FP_SYS" and minor == "sys":
        dispatch("SINGLE", "SYS", "COMMIT")
        metadata.update({"may_trap": True, "nonspeculative": True})
        return metadata

    if major in {"BLOCK_ARGS_DESC", "CMD_PIPE"}:
        dispatch("SINGLE", "CMD", "BCTRL")
        return metadata

    if major == "MISC":
        if symbol.startswith(("OP_F", "OP_SCVTF", "OP_UCVTF")):
            dispatch("SINGLE", "FSU", "IEX")
        elif symbol.startswith(("OP_BC_", "OP_DC_", "OP_IC_", "OP_TLB_", "OP_FENCE_", "OP_LSR")) or symbol == "OP_ASSERT":
            dispatch("SINGLE", "SYS", "COMMIT")
            metadata.update({"may_trap": True, "nonspeculative": True})
        elif symbol in {"OP_J", "OP_JR"}:
            dispatch("SINGLE", "BRU", "BCTRL")
            metadata["may_redirect"] = True
        elif symbol in {"OP_MADD", "OP_MADDW", "OP_MAX", "OP_MAXU", "OP_MIN", "OP_MINU", "OP_REV", "OP_BSE", "OP_BWE", "OP_BWI", "OP_BWT"}:
            dispatch("SINGLE", "ALU", "IEX")
        elif symbol in {"OP_PRF", "OP_PRFI_U"}:
            dispatch("SINGLE", "AGU", "LSU")
        return metadata

    return metadata


def _camel_to_decode_name(name: str) -> str:
    return name.lower().replace(".", "_")


def _field_tokens_from_linxisa_instruction(insn: dict) -> list[str]:
    fields = []
    enc = insn.get("encoding", {})
    parts = enc.get("parts", [])
    if not parts:
        return fields
    for field in parts[0].get("fields", []):
        name = str(field.get("name", ""))
        if name:
            fields.append(f"%{name}")
    return fields


def load_linxisa_v057_supplement(linxisa_json: Path) -> list[DecodeEntry]:
    data = json.loads(linxisa_json.read_text(encoding="utf-8"))
    wanted = {
        "BSTART.TPREFETCH",
        "BSTART.MGATHER",
        "BSTART.MSCATTER",
        "BSTART.MGATHER.MASK",
        "BSTART.MSCATTER.MASK",
        "BSTART.MGATHER.CAS",
        "BSTART.TMATMUL",
        "BSTART.TMATMUL.BIAS",
        "BSTART.TMATMUL.ACC",
        "BSTART.TMATMULMX",
        "BSTART.TMATMULMX.BIAS",
        "BSTART.TMATMULMX.ACC",
        "BSTART.TGEMV",
        "BSTART.TGEMV.BIAS",
        "BSTART.TGEMV.ACC",
        "BSTART.TGEMVMX",
        "BSTART.TGEMVMX.BIAS",
        "BSTART.TGEMVMX.ACC",
        "CASB",
        "CASH",
        "CASW",
        "CASD",
        "DMA",
    }
    out: list[DecodeEntry] = []
    for insn in data.get("instructions", []):
        mnemonic = str(insn.get("mnemonic", ""))
        if mnemonic not in wanted:
            continue
        enc_parts = insn.get("encoding", {}).get("parts", [])
        if not enc_parts:
            continue
        enc = enc_parts[0]
        out.append(
            DecodeEntry(
                mnemonic=_camel_to_decode_name(mnemonic),
                file="insn32.decode",
                enc_len=int(insn.get("length_bits", enc.get("length_bits", 32))),
                pattern=str(enc["pattern"]),
                mask=int(str(enc["mask"]), 0),
                match=int(str(enc["match"]), 0),
                fields=_field_tokens_from_linxisa_instruction(insn),
                source_profile="v0.57",
            )
        )
    return out


def _resolve_linxisa_json(profile: str | None) -> Path | None:
    if not profile:
        return None
    root = os.environ.get("LINXISA_ROOT") or os.environ.get("LINXISA_DIR")
    if root:
        candidate = Path(root) / "isa" / profile / f"linxisa-{profile}.json"
    else:
        candidate = Path(__file__).resolve().parents[4] / "isa" / profile / f"linxisa-{profile}.json"
    return candidate if candidate.exists() else None


def build_catalog(qemu_linx_dir: Path, *, isa_profile: str | None = None) -> Dict[str, object]:
    entries = load_qemu_entries(qemu_linx_dir)
    supplement_json = _resolve_linxisa_json(isa_profile)
    if supplement_json is not None:
        seen = {(entry.mnemonic, entry.enc_len, entry.mask, entry.match) for entry in entries}
        for entry in load_linxisa_v057_supplement(supplement_json):
            signature = (entry.mnemonic, entry.enc_len, entry.mask, entry.match)
            if signature in seen:
                continue
            seen.add(signature)
            entries.append(entry)
    by_mnemonic: Dict[str, List[DecodeEntry]] = {}
    for e in entries:
        by_mnemonic.setdefault(e.mnemonic, []).append(e)

    for m in MISC_INTERNAL_MNEMONICS:
        if m not in by_mnemonic:
            by_mnemonic[m] = [
                DecodeEntry(
                    mnemonic=m,
                    file="internal",
                    enc_len=0,
                    pattern="",
                    mask=0,
                    match=0,
                    fields=[],
                )
            ]

    records = []
    for mnemonic in sorted(by_mnemonic.keys()):
        forms = by_mnemonic[mnemonic]
        for form_index, e in enumerate(forms):
            major, minor = classify_major_minor(mnemonic)
            rd_kind, rs1_kind, rs2_kind, imm_kind = classify_fields(e.fields)
            record = {
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
            if e.source_profile:
                record["source_profile"] = e.source_profile
            if len(forms) > 1:
                # Additive schema extension: legacy single-form records keep
                # their original shape, while repeated mnemonic forms expose
                # deterministic source-order identity.
                record["form_index"] = form_index
                record["form_count"] = len(forms)
            records.append(record)

    sym_to_cat: Dict[str, str] = {}
    for r in records:
        symbol = r["symbol"]
        sym_to_cat.setdefault(symbol, OP_ID_CATEGORY_OVERRIDES.get(symbol, r["major_cat"]))

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
        r["ooo"] = derive_ooo_metadata(r)

    return {
        "version": 2,
        "category_order": CATEGORY_ORDER,
        "records": records,
    }


def load_catalog(path: Path) -> Dict[str, object]:
    return json.loads(path.read_text(encoding="utf-8"))


def save_catalog(path: Path, catalog: Dict[str, object]) -> None:
    path.write_text(json.dumps(catalog, indent=2, sort_keys=False) + "\n", encoding="utf-8")
