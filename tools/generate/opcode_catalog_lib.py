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
    symbol_override: str = ""
    operation_family: str = ""
    operation_name: str = ""
    imm_kind_override: str = ""


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


def _mask_match_to_pattern(mask: int, match: int, width: int) -> str:
    return "".join(
        "1" if (match >> bit) & 1 else "0" if (mask >> bit) & 1 else "."
        for bit in range(width - 1, -1, -1)
    )


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
        if "icall" in m:
            return "ICALL"
        if "call" in m:
            return "CALL"
        if m.endswith("_ind"):
            return "IND"
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
OOO_EXECUTION_CAPABILITIES = {
    "ALU": "SIMPLE_ALU",
    "BRU": "BRANCH",
    "AGU": "LOAD_ADDRESS",
    "STD": "STORE_DATA",
    "FSU": "FLOATING_VECTOR",
    "SYS": "SYSTEM",
    "CMD": "ENGINE_COMMAND",
    "BOUNDARY": "NONE",
}
OOO_MULTICYCLE_ALU_SYMBOLS = {
    "OP_DIV", "OP_DIVU", "OP_DIVUW", "OP_DIVW",
    "OP_REM", "OP_REMU", "OP_REMUW", "OP_REMW",
    "OP_HL_DIV", "OP_HL_DIVU", "OP_HL_DIVUW", "OP_HL_DIVW",
    "OP_HL_REM", "OP_HL_REMU", "OP_HL_REMUW", "OP_HL_REMW",
}


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
        "dispatch_capabilities": {name: "NONE" for name in OOO_DISPATCH_CLASSES},
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
        capabilities: Dict[str, str] | None = None,
    ) -> None:
        dispatch_demand = {name: 0 for name in OOO_DISPATCH_CLASSES}
        if demand is None:
            dispatch_demand[dispatch_class] = children
        else:
            dispatch_demand.update(demand)
        dispatch_capabilities = {name: "NONE" for name in OOO_DISPATCH_CLASSES}
        for name, count in dispatch_demand.items():
            if count:
                dispatch_capabilities[name] = OOO_EXECUTION_CAPABILITIES[name]
        if capabilities is not None:
            dispatch_capabilities.update(capabilities)
        metadata.update({
            "disposition": "DISPATCH",
            "recipe_kind": recipe,
            "uop_count_min": children,
            "uop_count_max": children,
            "side_effect_owner": owner,
            "dispatch_class": dispatch_class,
            "dispatch_writes": children,
            "dispatch_demand": dispatch_demand,
            "dispatch_capabilities": dispatch_capabilities,
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
            capabilities={"AGU": "STORE_ADDRESS", "STD": "STORE_DATA"},
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
            capabilities={"AGU": "STORE_ADDRESS", "STD": "STORE_DATA"},
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
        if symbol in OOO_MULTICYCLE_ALU_SYMBOLS:
            metadata["dispatch_capabilities"]["ALU"] = "MULTI_CYCLE_ALU"
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
    return re.sub(r"_+", "_", name.lower().replace(".", "_").replace(" ", "_")).strip("_")


def _field_tokens(parts: Iterable[dict]) -> list[str]:
    fields: list[str] = []
    for part in parts:
        for field in part.get("fields", []):
            name = str(field.get("name", ""))
            token = f"%{name}" if name else ""
            if token and token not in fields:
                fields.append(token)
    return fields


def _immediate_kind_from_fields(parts: list[dict]) -> str:
    """Recover the stable frontend immediate-layout name from locked field pieces."""
    for part in parts:
        part_shift = 32 * int(part.get("index", 0)) if len(parts) > 1 else 0
        for field in part.get("fields", []):
            name = str(field.get("name", ""))
            if "imm" not in name.lower():
                continue
            pieces = []
            for piece in field.get("pieces", []):
                lsb = int(piece.get("insn_lsb", piece.get("instruction_lsb", 0))) + part_shift
                if "insn_msb" in piece:
                    width = int(piece["insn_msb"]) - int(piece["insn_lsb"]) + 1
                else:
                    width = int(piece["width"])
                pieces.append((lsb, width))
            layout = tuple(sorted(pieces))
            specialized = {
                ("simm12", ((20, 12),)): "SIMM12_20_S12",
                ("simm12", ((7, 5), (25, 7))): "SIMM12_7_S5_25_7",
                ("simm5", ((11, 5),)): "SIMM5_11_S5",
                ("simm5", ((6, 5),)): "SIMM5_6_S5",
                ("simm17", ((6, 5), (36, 12))): "SIMM17_6_S5_36_12",
                ("simm17", ((6, 5), (23, 5), (41, 7))): "SIMM17_6_S5_23_5_41_7",
                ("simm17", ((11, 5), (23, 5), (41, 7))): "SIMM17_11_S5_23_5_41_7",
                ("simm22", ((6, 10), (36, 12))): "SIMM22_6_S10_36_12",
                ("simm22", ((6, 10), (23, 5), (41, 7))): "SIMM22_6_S10_23_5_41_7",
                ("simm", ((7, 5), (20, 12))): "UIMM17_20_12_7_5",
                ("simm", ((4, 12), (31, 17))): "SIMM_4_S12_31_17",
                ("simm", ((4, 12), (23, 5), (36, 12))): "SIMM_4_S12_23_5_36_12",
                ("simm", ((7, 25), (47, 17))): "SIMM42_7_S25_47_17",
                ("uimm17", ((7, 5), (20, 12))): "UIMM17_20_12_7_5",
                ("uimm", ((7, 5), (25, 7))): "FENTRY_UIMM_HI",
                ("uimm32", ((4, 12), (28, 20))): "IMM32",
                ("imm", ((4, 12), (28, 20))): "IMM32",
            }
            return specialized.get((name.lower(), layout), name.upper())
    return "NONE"


def _source_file_and_width(length_bits: int) -> tuple[str, int]:
    if length_bits == 16:
        return "insn16.decode", 16
    if length_bits == 32:
        return "insn32.decode", 32
    if length_bits == 48:
        return "insn48.decode", 64
    if length_bits == 64:
        return "insn64.decode", 64
    raise ValueError(f"unsupported instruction length: {length_bits}")


def _combine_encoding(parts: list[dict], length_bits: int) -> tuple[int, int, str, int, str]:
    if not parts:
        raise ValueError("instruction has no encoding parts")
    mask = 0
    match = 0
    for part in parts:
        index = int(part.get("index", 0))
        width = int(part.get("width_bits", length_bits))
        shift = 32 * index if len(parts) > 1 and width == 32 else 0
        mask |= int(str(part["mask"]), 0) << shift
        match |= int(str(part["match"]), 0) << shift
    source_file, enc_len = _source_file_and_width(length_bits)
    if length_bits == 48:
        # Frontend/QEMU carry 48-bit instructions in a zero-extended 64-bit word.
        mask |= 0xFFFF << 48
    return mask, match, _mask_match_to_pattern(mask, match, enc_len), enc_len, source_file


def _command_decode_name(form: dict) -> str:
    mnemonic = str(form["mnemonic"])
    asm = str(form.get("asm", "")).upper()
    base = _camel_to_decode_name(mnemonic)
    if mnemonic == "B.DIM":
        match = re.search(r"->LB([0-2])", asm)
        return f"b_dim_lb{match.group(1)}" if match else base
    if mnemonic == "B.HINT" and "TRACE" in asm:
        return "b_hint_trace"
    if mnemonic == "BSTART":
        return "bstart_split_cond" if " COND" in asm else "bstart_split_direct"
    if mnemonic == "BSTART CALL":
        return "start_call_32"
    if mnemonic == "HL.BSTART CALL":
        return "start_call_48"
    if mnemonic == "C.BSTART":
        return "c_bstart_cond" if " COND" in asm else "c_bstart_direct"
    variants = ("FALL", "DIRECT", "COND", "CALL", "IND", "ICALL", "RET")
    variant = next((name.lower() for name in variants if f" {name}" in asm), "")
    if variant and mnemonic in {"BSTART.STD", "BSTART.FP", "HL.BSTART.STD", "HL.BSTART.FP"}:
        prefix = "bstart" if mnemonic == "BSTART.STD" else base
        return f"{prefix}_{variant}"
    return base


def _entry_from_linx_instruction(insn: dict) -> DecodeEntry:
    parts = list(insn.get("encoding", {}).get("parts", []))
    length_bits = int(insn["length_bits"])
    mask, match, pattern, enc_len, source_file = _combine_encoding(parts, length_bits)
    return DecodeEntry(
        mnemonic=_camel_to_decode_name(str(insn["mnemonic"])),
        file=source_file,
        enc_len=enc_len,
        pattern=pattern,
        mask=mask,
        match=match,
        fields=_field_tokens(parts),
        source_profile="v0.57.1",
        imm_kind_override=_immediate_kind_from_fields(parts),
    )


def _entry_from_command_form(
    form: dict,
    *,
    mnemonic: str | None = None,
    mask: int | None = None,
    match: int | None = None,
    symbol_override: str = "",
    operation_family: str = "",
    operation_name: str = "",
    imm_kind_override: str = "",
) -> DecodeEntry:
    encodings = list(form.get("encoding", []))
    length_bits = int(form["length_bits"])
    form_mask, form_match, pattern, enc_len, source_file = _combine_encoding(encodings, length_bits)
    if mask is not None or match is not None:
        form_mask = form_mask if mask is None else mask
        form_match = form_match if match is None else match
        pattern = _mask_match_to_pattern(form_mask, form_match, enc_len)
    return DecodeEntry(
        mnemonic=mnemonic or _command_decode_name(form),
        file=source_file,
        enc_len=enc_len,
        pattern=pattern,
        mask=form_mask,
        match=form_match,
        fields=[f"%{field['name']}" for field in form.get("fields", [])],
        source_profile="v0.57.1",
        symbol_override=symbol_override,
        operation_family=operation_family,
        operation_name=operation_name,
        imm_kind_override=imm_kind_override
        or _immediate_kind_from_fields([{"fields": list(form.get("fields", []))}]),
    )


def _resolve_snapshot_root(linxisa_root: Path | None = None, profile: str = "v0.57") -> Path:
    root = linxisa_root
    if root is None:
        configured = os.environ.get("LINXISA_ROOT") or os.environ.get("LINXISA_DIR")
        root = Path(configured) if configured else Path(__file__).resolve().parents[4]
    snapshot = root / "isa" / profile
    required = (
        snapshot / f"linxisa-{profile}.json",
        snapshot / "pto-spec.lock.json",
        snapshot / "state" / "pto_command_forms.json",
        snapshot / "state" / "pto_ops.json",
    )
    missing = [str(path) for path in required if not path.is_file()]
    if missing:
        raise FileNotFoundError("locked LinxISA snapshot is incomplete: " + ", ".join(missing))
    return snapshot


def load_locked_linxisa_entries(
    linxisa_root: Path | None = None, *, profile: str = "v0.57"
) -> tuple[list[DecodeEntry], dict[str, object]]:
    snapshot = _resolve_snapshot_root(linxisa_root, profile)
    isa = json.loads((snapshot / f"linxisa-{profile}.json").read_text(encoding="utf-8"))
    lock = json.loads((snapshot / "pto-spec.lock.json").read_text(encoding="utf-8"))
    command_forms = json.loads(
        (snapshot / "state" / "pto_command_forms.json").read_text(encoding="utf-8")
    )
    operations = json.loads((snapshot / "state" / "pto_ops.json").read_text(encoding="utf-8"))

    release = str(lock.get("release", ""))
    if release != "0.57.1" or str(isa.get("version", "")) != release:
        raise ValueError(f"expected locked ISA release 0.57.1, got lock={release!r} isa={isa.get('version')!r}")
    expected_commit = "b30ed3df4f1a7fd0c2d19b02a90b049cb452fd87"
    if str(lock.get("source", {}).get("commit", "")) != expected_commit:
        raise ValueError("PTO source commit does not match the reviewed 0.57.1 lock")
    expected_hashes = {
        "command_forms": "c53db18b30fbf53676f1d733e215122f65ad681a778ae0728cc6c4a3674df61e",
        "tile_operations": "2a49616fbbd34ee4ff00b971d56de6dd7b8c1698fa7312db0b20b6119965bc26",
    }
    for catalog_name, expected_hash in expected_hashes.items():
        if str(lock["catalogs"][catalog_name].get("sha256", "")) != expected_hash:
            raise ValueError(f"{catalog_name} hash does not match the reviewed 0.57.1 lock")
    for projection in (command_forms, operations):
        if projection.get("source_lock") != "isa/v0.57/pto-spec.lock.json":
            raise ValueError("PTO projection is not bound to isa/v0.57/pto-spec.lock.json")
    expected_forms = int(lock["catalogs"]["command_forms"]["count"])
    expected_operations = int(lock["catalogs"]["tile_operations"]["count"])
    if int(command_forms.get("form_count", -1)) != expected_forms or len(command_forms.get("forms", [])) != expected_forms:
        raise ValueError("PTO command-form count does not match pto-spec.lock.json")
    if int(operations.get("operation_count", -1)) != expected_operations or len(operations.get("operations", [])) != expected_operations:
        raise ValueError("PTO operation count does not match pto-spec.lock.json")
    expected_families = {"TEPL": 98, "TMA": 9, "CUBE": 13}
    if operations.get("family_counts") != expected_families:
        raise ValueError(f"unexpected PTO operation families: {operations.get('family_counts')!r}")
    expected_deleted = [
        "TADDC", "TADDSC", "TFMA", "TFMOD", "TFMODS", "TLRELU", "TRANDOM", "TSUBC", "TSUBSC"
    ]
    if sorted(operations.get("deleted_names", [])) != expected_deleted:
        raise ValueError(f"unexpected deleted PTO operations: {operations.get('deleted_names')!r}")

    entries = [
        _entry_from_linx_instruction(insn)
        for insn in isa.get("instructions", [])
        if str(insn.get("uop_group", "")) not in {"CMD", "BBD"}
    ]
    forms = list(command_forms["forms"])
    forbidden_forms = {"B.ARG", "BSTART.CUBE", "BSTART.FIXP"}
    present_forbidden = sorted(
        forbidden_forms.intersection(str(form.get("mnemonic", "")) for form in forms)
    )
    if present_forbidden:
        raise ValueError(f"retired/generic PTO forms remain active: {present_forbidden}")
    exact_command_encodings = {
        "B.CATR": {(0xFBF07FFF, 0x00000023)},
        "B.DATR": {(0x000C707F, 0x00001023)},
        "B.IOT": {
            (0xFC00787F, 0x00005013),
            (0x0000707F, 0x00004013),
            (0xFC07FBFF, 0x00005013),
            (0xFFF07C7F, 0x00006013),
            (0x0007F3FF, 0x00004013),
        },
    }
    for mnemonic, expected_encodings in exact_command_encodings.items():
        observed_encodings = {
            (int(form["encoding"][0]["mask"], 0), int(form["encoding"][0]["match"], 0))
            for form in forms
            if form.get("mnemonic") == mnemonic
        }
        if observed_encodings != expected_encodings:
            raise ValueError(f"unexpected locked encodings for {mnemonic}: {observed_encodings!r}")
    generic_tepl = [form for form in forms if form.get("mnemonic") == "BSTART.TEPL"]
    if len(generic_tepl) != 1:
        raise ValueError(f"expected one generic BSTART.TEPL form, got {len(generic_tepl)}")
    tepl_form = generic_tepl[0]
    operation_by_command = {
        str(operation.get("command_mnemonic")): operation
        for operation in operations["operations"]
        if operation.get("family") in {"TMA", "CUBE"}
    }
    for form in forms:
        command_mnemonic = str(form["mnemonic"])
        if command_mnemonic == "BSTART.TEPL":
            continue
        if command_mnemonic in {"C.BSTART.STD", "C.BSTART.FP"}:
            base_mask = int(str(form["encoding"][0]["mask"]), 0)
            base_match = int(str(form["encoding"][0]["match"]), 0)
            variants = ("fall", "direct", "cond", "call", "ind", "icall", "ret")
            for br_type, variant in enumerate(variants, start=1):
                entries.append(
                    _entry_from_command_form(
                        form,
                        mnemonic=(
                            f"c_bstart_std_{variant}"
                            if command_mnemonic == "C.BSTART.STD"
                            else "c_bstart_fp"
                        ),
                        mask=base_mask | (0x7 << 11),
                        match=base_match | (br_type << 11),
                    )
                )
            continue
        operation = operation_by_command.get(command_mnemonic)
        family = str(operation.get("family", "")) if operation else ""
        command_imm_kind = {
            "B.IOT": "IOTIMM4",
            "BSTART CALL": "FUSED_CALL_SIMM12",
            "HL.BSTART CALL": "FUSED_CALL_SIMM25",
        }.get(command_mnemonic, "")
        entries.append(
            _entry_from_command_form(
                form,
                symbol_override=f"OP_BSTART_{family}" if family else "",
                operation_family=family,
                operation_name=str(operation.get("name", "")) if operation else "",
                imm_kind_override=command_imm_kind,
            )
        )
    for operation in operations["operations"]:
        if operation.get("family") != "TEPL":
            continue
        selector = int(str(operation["selector"]), 0)
        base_mask = int(str(tepl_form["encoding"][0]["mask"]), 0)
        base_match = int(str(tepl_form["encoding"][0]["match"]), 0)
        entries.append(
            _entry_from_command_form(
                tepl_form,
                mnemonic=f"bstart_{str(operation['name']).lower()}",
                mask=base_mask | (0x7F << 20),
                match=base_match | (selector << 20),
                symbol_override="OP_BSTART_TEPL",
                operation_family="TEPL",
                operation_name=str(operation["name"]),
            )
        )
    metadata: dict[str, object] = {
        "release": release,
        "pto_spec_commit": str(lock["source"]["commit"]),
        "command_form_count": expected_forms,
        "tile_operation_count": expected_operations,
        "tile_family_counts": expected_families,
        "deleted_tile_operations": expected_deleted,
    }
    return entries, metadata


def _build_catalog_from_entries(
    entries: list[DecodeEntry], *, source_metadata: dict[str, object] | None = None
) -> Dict[str, object]:
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
            if e.imm_kind_override and e.imm_kind_override != "NONE":
                imm_kind = e.imm_kind_override
            record = {
                "mnemonic": mnemonic,
                "symbol": e.symbol_override or mnemonic_to_symbol(mnemonic),
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
            if e.operation_family:
                record["operation_family"] = e.operation_family
                record["operation_name"] = e.operation_name
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

    catalog: Dict[str, object] = {
        "version": 2,
        "category_order": CATEGORY_ORDER,
        "records": records,
    }
    if source_metadata:
        catalog["source"] = source_metadata
    return catalog


def build_catalog_from_entries(entries: list[DecodeEntry]) -> Dict[str, object]:
    """Build a catalog from explicit entries (used by parser/parity unit tests)."""
    return _build_catalog_from_entries(entries)


def build_locked_catalog(
    linxisa_root: Path | None = None, *, profile: str = "v0.57"
) -> Dict[str, object]:
    entries, metadata = load_locked_linxisa_entries(linxisa_root, profile=profile)
    return _build_catalog_from_entries(entries, source_metadata=metadata)


def load_catalog(path: Path) -> Dict[str, object]:
    return json.loads(path.read_text(encoding="utf-8"))


def save_catalog(path: Path, catalog: Dict[str, object]) -> None:
    path.write_text(json.dumps(catalog, indent=2, sort_keys=False) + "\n", encoding="utf-8")
