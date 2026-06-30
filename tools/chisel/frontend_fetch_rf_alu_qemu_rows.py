#!/usr/bin/env python3
"""Extract reduced scalar expected rows from a QEMU commit JSONL trace."""

from __future__ import annotations

import argparse
import json
import tempfile
from pathlib import Path
from typing import Any, Iterable

from trace_schema_adapter import REQUIRED_TRACE_FIELDS, load_jsonl, normalize_row


MASK64 = (1 << 64) - 1
C_BSTART_EXACT = {
    0x08C0,  # C.BSTART.MPAR
    0x48C0,  # C.BSTART.MSEQ
    0x2000,  # C.BSTART.STD.CALL
    0x1800,  # C.BSTART.STD.COND
    0x1000,  # C.BSTART.STD.DIRECT
    0x0800,  # C.BSTART.STD.FALL
    0x3000,  # C.BSTART.STD.ICALL
    0x2800,  # C.BSTART.STD.IND
    0x3800,  # C.BSTART.STD.RET
    0x0840,  # C.BSTART.SYS
    0x88C0,  # C.BSTART.VPAR
    0xC8C0,  # C.BSTART.VSEQ
}
HL_BSTART_MASK = 0xFFFF_0000_7FFF_000F
HL_BSTART_MATCHES = {
    0x4101_000E,  # HL.BSTART.FP.CALL
    0x3101_000E,  # HL.BSTART.FP.COND
    0x2101_000E,  # HL.BSTART.FP.DIRECT
    0x1101_000E,  # HL.BSTART.FP.FALL
    0x4001_000E,  # HL.BSTART.STD.CALL
    0x3001_000E,  # HL.BSTART.STD.COND
    0x2001_000E,  # HL.BSTART.STD.DIRECT
    0x1001_000E,  # HL.BSTART.STD.FALL
    0x1081_000E,  # HL.BSTART.SYS
}
HL_LUI_MASK = 0xFFFF_0000_007F_000F
HL_LUI_MATCH = 0x0000_0000_0017_000E
HL_LD_PCR_MASK = 0xFFFF_0000_707F_000F
HL_LD_PCR_MATCH = 0x0000_0000_3039_000E
SLL_MASK = 0xFE00_707F
SLL_MATCH = 0x0000_7005
SLLI_MASK = 0xFC00_707F
SLLI_MATCH = 0x0000_7015
SRL_MASK = 0xFE00_707F
SRL_MATCH = 0x0000_5005
SRA_MASK = 0xFE00_707F
SRA_MATCH = 0x0000_6005
MUL_MASK = 0xFE00_707F
MUL_MATCH = 0x0000_0047
MULW_MASK = 0xFE00_707F
MULW_MATCH = 0x0000_2047
OR_MASK = 0x707F
OR_MATCH = 0x3005
SETC_LTU_MASK = 0xF800_7FFF
SETC_LTU_MATCH = 0x0000_6065
SETC_LTUI_MASK = 0x707F
SETC_LTUI_MATCH = 0x6075
SETC_TGT_MASK = 0xFFF0_7FFF
SETC_TGT_MATCH = 0x0000_403B
SD_MASK = 0x7FFF
SD_MATCH = 0x3049
LOCAL_QUEUE_DEPTH = 4


class RowExtractionError(RuntimeError):
    pass


def _mask_insn(insn: int, length: int) -> int:
    if length == 2:
        return insn & 0xFFFF
    if length == 4:
        return insn & 0xFFFF_FFFF
    if length == 6:
        return insn & 0xFFFF_FFFF_FFFF
    if length == 8:
        return insn & 0xFFFF_FFFF_FFFF_FFFF
    raise RowExtractionError(f"unsupported instruction length: {length}")


def _sext(value: int, width: int) -> int:
    sign = 1 << (width - 1)
    mask = (1 << width) - 1
    value &= mask
    if value & sign:
        return value | (MASK64 ^ mask)
    return value


def _classify(row: dict[str, int]) -> str | None:
    insn = _mask_insn(row["insn"], row["len"])
    if row["len"] == 4:
        key = insn & 0x707F
        if key == 0x0005:
            return "ADD"
        if key == 0x0025:
            return "ADDW"
        if key == 0x1005:
            return "SUB"
        if key == 0x0015:
            return "ADDI"
        if key == 0x1015:
            return "SUBI"
        if key == 0x2015:
            return "ANDI"
        if key == 0x2035:
            return "ANDIW"
        if key == 0x3015:
            return "ORI"
        if (insn & 0xFFF) == 0x0507:
            return None
        if (insn & 0x7F) == 0x0007:
            return "ADDTPC"
        if key == 0x0041:
            return "FENTRY"
        if key == 0x3041:
            return "FRET_STK"
        if key == 0x3019:
            return "LDI"
        if key == 0x3039:
            return "LD.PCR"
        if key == 0x0059:
            return "SBI"
        if key == 0x2059:
            return "SWI"
        if key == 0x3059:
            return "SDI"
        if (insn & SD_MASK) == SD_MATCH:
            return "SD"
        if (insn & SLLI_MASK) == SLLI_MATCH:
            return "SLLI"
        if (insn & SLL_MASK) == SLL_MATCH:
            return "SLL"
        if (insn & SRL_MASK) == SRL_MATCH:
            return "SRL"
        if (insn & SRA_MASK) == SRA_MATCH:
            return "SRA"
        if (insn & MUL_MASK) == MUL_MATCH:
            return "MUL"
        if (insn & MULW_MASK) == MULW_MATCH:
            return "MULW"
        if (insn & OR_MASK) == OR_MATCH:
            return "OR"
        if (insn & SETC_LTUI_MASK) == SETC_LTUI_MATCH:
            return "SETC_LTUI"
        if (insn & SETC_LTU_MASK) == SETC_LTU_MATCH:
            return "SETC_LTU"
        if (insn & SETC_TGT_MASK) == SETC_TGT_MATCH:
            return "SETC_TGT"
    if row["len"] == 2:
        if (insn & 0xF83F) == 0x5016:
            return "C.SETRET"
        if (insn & 0xF83F) == 0x001C:
            return "C.SETC_TGT"
        key = insn & 0x3F
        if key == 0x0008:
            return "C.ADD"
        if key == 0x0018:
            return "C.SUB"
        if key == 0x0028:
            return "C.AND"
        if key == 0x0016:
            return "C.MOVI"
        if key == 0x0006:
            return "C.MOVR"
        if key == 0x001A:
            return "C.LDI"
        if key == 0x003A:
            return "C.SDI"
        if key == 0x0026:
            return "C.SETC_EQ"
        if key == 0x0036:
            return "C.SETC_NE"
    if row["len"] == 6:
        if (insn & HL_LUI_MASK) == HL_LUI_MATCH:
            return "HL.LUI"
        if (insn & HL_LD_PCR_MASK) == HL_LD_PCR_MATCH:
            return "HL.LD.PCR"
    return None


def _classify_block_marker(row: dict[str, int]) -> tuple[str, bool, bool] | None:
    insn = _mask_insn(row["insn"], row["len"])
    if row["len"] == 2:
        if insn == 0:
            return ("C.BSTOP", False, True)
        if (
            insn in C_BSTART_EXACT
            or (insn & 0x000F) == 0x0002  # C.BSTART.DIRECT immediate form
            or (insn & 0x000F) == 0x0004  # C.BSTART.COND immediate form
            or (insn & 0xC7FF) == 0x0080  # C.BSTART.FP
        ):
            return ("C.BSTART", True, False)
    if row["len"] == 6 and (insn & HL_BSTART_MASK) in HL_BSTART_MATCHES:
        return ("HL.BSTART", True, False)
    return None


def _uimm12(row: dict[str, int]) -> int:
    return (_mask_insn(row["insn"], row["len"]) >> 20) & 0xFFF


def _simm12_20_s12_scaled_double(row: dict[str, int]) -> int:
    raw = (_mask_insn(row["insn"], row["len"]) >> 20) & 0xFFF
    return (_sext(raw, 12) << 3) & MASK64


def _simm12_20_s12(row: dict[str, int]) -> int:
    return _sext((_mask_insn(row["insn"], row["len"]) >> 20) & 0xFFF, 12)


def _simm20_shifted(row: dict[str, int]) -> int:
    return (_sext((_mask_insn(row["insn"], row["len"]) >> 12) & 0xFFFFF, 20) << 12) & MASK64


def _simm5_6(row: dict[str, int]) -> int:
    return _sext((_mask_insn(row["insn"], row["len"]) >> 6) & 0x1F, 5)


def _simm5_11(row: dict[str, int]) -> int:
    return _sext((_mask_insn(row["insn"], row["len"]) >> 11) & 0x1F, 5)


def _simm5_11_scaled_double(row: dict[str, int]) -> int:
    raw = (_mask_insn(row["insn"], row["len"]) >> 11) & 0x1F
    signed = raw - 0x20 if raw & 0x10 else raw
    return (signed << 3) & MASK64


def _simm17_unscaled(row: dict[str, int]) -> int:
    return _sext((_mask_insn(row["insn"], row["len"]) >> 15) & 0x1_FFFF, 17)


def _hl_pcr_simm(row: dict[str, int]) -> int:
    insn = _mask_insn(row["insn"], row["len"])
    raw = (((insn >> 4) & 0xFFF) << 17) | ((insn >> 31) & 0x1_FFFF)
    return _sext(raw, 29)


def _simm12_7_s5_25_7_scaled_double(row: dict[str, int]) -> int:
    insn = _mask_insn(row["insn"], row["len"])
    raw = (((insn >> 7) & 0x1F) << 7) | ((insn >> 25) & 0x7F)
    return (_sext(raw, 12) << 3) & MASK64


def _simm12_7_s5_25_7(row: dict[str, int]) -> int:
    insn = _mask_insn(row["insn"], row["len"])
    raw = (((insn >> 7) & 0x1F) << 7) | ((insn >> 25) & 0x7F)
    return _sext(raw, 12)


def _simm12_7_s5_25_7_scaled_word(row: dict[str, int]) -> int:
    return (_simm12_7_s5_25_7(row) << 2) & MASK64


def _c_setret_imm(row: dict[str, int]) -> int:
    return ((_mask_insn(row["insn"], row["len"]) >> 6) & 0x1F) << 1


def _fentry_imm(row: dict[str, int]) -> int:
    insn = _mask_insn(row["insn"], row["len"])
    return (((insn >> 7) & 0x1F) << 10) | (((insn >> 25) & 0x7F) << 3)


def _fentry_m(row: dict[str, int]) -> int:
    return (_mask_insn(row["insn"], row["len"]) >> 15) & 0x1F


def _fentry_n(row: dict[str, int]) -> int:
    return (_mask_insn(row["insn"], row["len"]) >> 20) & 0x1F


def _fentry_save_count(row: dict[str, int]) -> int:
    begin = _fentry_m(row)
    end = _fentry_n(row)
    if end >= begin:
        return end - begin + 1
    return end + 23 - begin


def _sll_src_l(row: dict[str, int]) -> int:
    return (_mask_insn(row["insn"], row["len"]) >> 15) & 0x1F


def _sll_src_r(row: dict[str, int]) -> int:
    return (_mask_insn(row["insn"], row["len"]) >> 20) & 0x1F


def _srcp(row: dict[str, int]) -> int:
    return (_mask_insn(row["insn"], row["len"]) >> 27) & 0x1F


def _shamt_20_25(row: dict[str, int]) -> int:
    return (_mask_insn(row["insn"], row["len"]) >> 20) & 0x3F


def _c_src_l(row: dict[str, int]) -> int:
    return (_mask_insn(row["insn"], row["len"]) >> 6) & 0x1F


def _c_src_r(row: dict[str, int]) -> int:
    return (_mask_insn(row["insn"], row["len"]) >> 11) & 0x1F


def _hl_lui_imm(row: dict[str, int]) -> int:
    insn = _mask_insn(row["insn"], row["len"])
    imm32 = (((insn >> 4) & 0xFFF) << 20) | ((insn >> 28) & 0xFFFFF)
    return _sext(imm32, 32) & MASK64


def _new_local_state() -> dict[str, list[int | None]]:
    return {
        "T": [None] * LOCAL_QUEUE_DEPTH,
        "U": [None] * LOCAL_QUEUE_DEPTH,
    }


def _local_queue_for_reg(reg: int) -> tuple[str, int] | None:
    if 24 <= reg <= 27:
        return ("T", reg - 24)
    if 28 <= reg <= 31:
        return ("U", reg - 28)
    return None


def _is_local_source_reg(reg: int) -> bool:
    return _local_queue_for_reg(reg) is not None


def _local_source_value(state: dict[str, list[int | None]], reg: int, opcode: str) -> int:
    queue_info = _local_queue_for_reg(reg)
    if queue_info is None:
        raise RowExtractionError(f"{opcode} row source reg {reg} is not a reduced T/U local source")
    queue_name, index = queue_info
    value = state[queue_name][index]
    if value is None:
        raise RowExtractionError(f"{opcode} row reads empty {queue_name}{index} local source")
    return value


def _push_local_destination(state: dict[str, list[int | None]], reg: int, value: int) -> None:
    if reg == 31:
        queue = state["T"]
    elif reg == 30:
        queue = state["U"]
    else:
        return
    queue.insert(0, value & MASK64)
    del queue[LOCAL_QUEUE_DEPTH:]


def _require_scalar_reg(row: dict[str, int], field: str, opcode: str) -> None:
    reg = row[field]
    if reg >= 24:
        raise RowExtractionError(
            f"{opcode} row uses non-scalar GPR {field}={reg}; reduced RF/ALU gate supports arch regs 0..23"
        )


def _is_reduced_tu_destination(reg: int) -> bool:
    # FrontendRegAliasClassify maps architectural destination alias 30 to U
    # and 31 to T. Keep admission opcode-specific so future T/U producers are
    # forced through their own QEMU-backed gate.
    return reg == 30 or reg == 31


def _require_sources(row: dict[str, int], opcode: str, src0: bool, src1: bool) -> None:
    if bool(row["src0_valid"]) != src0:
        raise RowExtractionError(f"{opcode} row has src0_valid={row['src0_valid']}, expected {int(src0)}")
    if bool(row["src1_valid"]) != src1:
        raise RowExtractionError(f"{opcode} row has src1_valid={row['src1_valid']}, expected {int(src1)}")
    if src0:
        _require_scalar_reg(row, "src0_reg", opcode)
    if src1:
        _require_scalar_reg(row, "src1_reg", opcode)


def _encoded_source_value(
    row: dict[str, int],
    field: str,
    reg: int,
    opcode: str,
    local_state: dict[str, list[int | None]]) -> int:
    valid_field = f"{field}_valid"
    reg_field = f"{field}_reg"
    data_field = f"{field}_data"
    if _is_local_source_reg(reg):
        if row[valid_field]:
            raise RowExtractionError(f"{opcode} row has {valid_field}=1 for local source reg {reg}")
        return _local_source_value(local_state, reg, opcode)
    _require_scalar_reg(row, reg_field, opcode)
    if not row[valid_field]:
        raise RowExtractionError(f"{opcode} row has {valid_field}=0 for scalar source reg {reg}")
    if row[reg_field] != reg:
        raise RowExtractionError(f"{opcode} row {reg_field}={row[reg_field]} differs from compressed source {reg}")
    return row[data_field] & MASK64


def _require_writeback(row: dict[str, int], opcode: str) -> None:
    if not row["dst_valid"] or not row["wb_valid"]:
        raise RowExtractionError(f"{opcode} row must have destination/writeback valid")
    if row["dst_reg"] != row["wb_rd"]:
        raise RowExtractionError(f"{opcode} row dst_reg={row['dst_reg']} differs from wb_rd={row['wb_rd']}")
    if not _is_reduced_tu_destination(row["dst_reg"]):
        _require_scalar_reg(row, "dst_reg", opcode)
        _require_scalar_reg(row, "wb_rd", opcode)
    elif row["dst_reg"] == 30 and opcode not in {
        "ADD",
        "ADDW",
        "ADDI",
        "ADDTPC",
        "ANDI",
        "ANDIW",
        "SUB",
        "LDI",
        "LD.PCR",
        "HL.LD.PCR",
        "SLL",
        "SLLI",
        "SRL",
        "SRA",
        "OR",
        "ORI",
    }:
        raise RowExtractionError(f"{opcode} row writes reduced U destination alias {row['dst_reg']}")
    elif row["dst_reg"] == 31 and opcode not in {
        "ADDTPC",
        "ADDW",
        "ADDI",
        "ANDI",
        "C.AND",
        "C.SUB",
        "HL.LUI",
        "LD.PCR",
        "HL.LD.PCR",
        "SLL",
        "SLLI",
        "SRL",
        "SRA",
        "OR",
        "ORI",
        "SUB",
        "C.LDI",
        "C.ADD",
        "C.SUB",
        "C.MOVR",
    }:
        raise RowExtractionError(f"{opcode} row writes reduced T destination alias {row['dst_reg']}")
    if row["dst_data"] != row["wb_data"]:
        raise RowExtractionError(f"{opcode} row dst_data={row['dst_data']} differs from wb_data={row['wb_data']}")


def _is_no_writeback_trace_gap(row: dict[str, int]) -> bool:
    return (
        not row["dst_valid"] and
        not row["wb_valid"] and
        row["dst_reg"] == 0 and
        row["wb_rd"] == 0 and
        row["dst_data"] == 0 and
        row["wb_data"] == 0
    )


def _is_setc_immediate_dst_artifact(row: dict[str, int], opcode: str) -> bool:
    return (
        opcode == "SETC_LTUI" and
        row["dst_valid"] and
        not row["wb_valid"] and
        row["dst_reg"] == 0 and
        row["wb_rd"] == 0 and
        row["dst_data"] == 0 and
        row["wb_data"] == 0
    )


def _signed64(value: int) -> int:
    value &= MASK64
    if value & (1 << 63):
        return value - (1 << 64)
    return value


def _expected_result(
    row: dict[str, int],
    opcode: str,
    local_state: dict[str, list[int | None]]) -> int:
    if opcode == "ADD":
        lhs = _encoded_source_value(row, "src0", _sll_src_l(row), opcode, local_state)
        rhs = _encoded_source_value(row, "src1", _sll_src_r(row), opcode, local_state)
        return (lhs + rhs) & MASK64
    if opcode == "ADDW":
        lhs = _encoded_source_value(row, "src0", _sll_src_l(row), opcode, local_state)
        rhs = _encoded_source_value(row, "src1", _sll_src_r(row), opcode, local_state)
        return _sext((lhs + rhs) & 0xFFFF_FFFF, 32) & MASK64
    if opcode == "SUB":
        lhs = _encoded_source_value(row, "src0", _sll_src_l(row), opcode, local_state)
        rhs = _encoded_source_value(row, "src1", _sll_src_r(row), opcode, local_state)
        return (lhs - rhs) & MASK64
    if opcode == "ADDI":
        src0 = _encoded_source_value(row, "src0", _sll_src_l(row), opcode, local_state)
        return (src0 + _uimm12(row)) & MASK64
    if opcode == "SUBI":
        src0 = _encoded_source_value(row, "src0", _sll_src_l(row), opcode, local_state)
        return (src0 - _uimm12(row)) & MASK64
    if opcode == "ANDI":
        src0 = _encoded_source_value(row, "src0", _sll_src_l(row), opcode, local_state)
        return (src0 & _simm12_20_s12(row)) & MASK64
    if opcode == "ANDIW":
        src0 = _encoded_source_value(row, "src0", _sll_src_l(row), opcode, local_state)
        return _sext((src0 & _simm12_20_s12(row)) & 0xFFFF_FFFF, 32) & MASK64
    if opcode == "ORI":
        src0 = _encoded_source_value(row, "src0", _sll_src_l(row), opcode, local_state)
        return (src0 | _simm12_20_s12(row)) & MASK64
    if opcode == "ADDTPC":
        return ((row["pc"] & ~0xFFF) + _simm20_shifted(row)) & MASK64
    if opcode == "C.MOVI":
        return _simm5_6(row) & MASK64
    if opcode == "C.MOVR":
        return _encoded_source_value(row, "src0", _c_src_l(row), opcode, local_state)
    if opcode == "C.SETRET":
        return (row["pc"] + _c_setret_imm(row)) & MASK64
    if opcode == "FENTRY":
        return (row["mem_addr"] + (_fentry_save_count(row) << 3) - _fentry_imm(row)) & MASK64
    if opcode == "C.LDI":
        return row["mem_rdata"] & MASK64
    if opcode == "C.SDI":
        return 0
    if opcode == "LDI":
        return row["mem_rdata"] & MASK64
    if opcode in {"LD.PCR", "HL.LD.PCR"}:
        return row["mem_rdata"] & MASK64
    if opcode in {"C.SETC_EQ", "C.SETC_NE"}:
        return 0
    if opcode == "SETC_LTU":
        return 0
    if opcode == "SETC_LTUI":
        return 0
    if opcode in {"C.SETC_TGT", "SETC_TGT"}:
        return 0
    if opcode == "FRET_STK":
        return row["mem_rdata"] & MASK64 if row["mem_valid"] else 0
    if opcode == "SBI":
        return 0
    if opcode == "SWI":
        return 0
    if opcode == "SD":
        return 0
    if opcode == "SDI":
        return 0
    if opcode == "MULW":
        lhs = _encoded_source_value(row, "src0", _sll_src_l(row), opcode, local_state)
        rhs = _encoded_source_value(row, "src1", _sll_src_r(row), opcode, local_state)
        return _sext((lhs * rhs) & 0xFFFF_FFFF, 32) & MASK64
    if opcode == "MUL":
        lhs = _encoded_source_value(row, "src0", _sll_src_l(row), opcode, local_state)
        rhs = _encoded_source_value(row, "src1", _sll_src_r(row), opcode, local_state)
        return (lhs * rhs) & MASK64
    if opcode == "C.ADD":
        lhs = _encoded_source_value(row, "src0", _c_src_l(row), opcode, local_state)
        rhs = _encoded_source_value(row, "src1", _c_src_r(row), opcode, local_state)
        return (lhs + rhs) & MASK64
    if opcode == "C.AND":
        lhs = _encoded_source_value(row, "src0", _c_src_l(row), opcode, local_state)
        rhs = _encoded_source_value(row, "src1", _c_src_r(row), opcode, local_state)
        return (lhs & rhs) & MASK64
    if opcode == "C.SUB":
        lhs = _encoded_source_value(row, "src0", _c_src_l(row), opcode, local_state)
        rhs = _encoded_source_value(row, "src1", _c_src_r(row), opcode, local_state)
        return (lhs - rhs) & MASK64
    if opcode == "HL.LUI":
        return _hl_lui_imm(row)
    if opcode == "SLL":
        lhs = _encoded_source_value(row, "src0", _sll_src_l(row), opcode, local_state)
        rhs = _encoded_source_value(row, "src1", _sll_src_r(row), opcode, local_state)
        return (lhs << (rhs & 0x3F)) & MASK64
    if opcode == "SLLI":
        lhs = _encoded_source_value(row, "src0", _sll_src_l(row), opcode, local_state)
        return (lhs << _shamt_20_25(row)) & MASK64
    if opcode == "SRL":
        lhs = _encoded_source_value(row, "src0", _sll_src_l(row), opcode, local_state)
        rhs = _encoded_source_value(row, "src1", _sll_src_r(row), opcode, local_state)
        return (lhs >> (rhs & 0x3F)) & MASK64
    if opcode == "SRA":
        lhs = _encoded_source_value(row, "src0", _sll_src_l(row), opcode, local_state)
        rhs = _encoded_source_value(row, "src1", _sll_src_r(row), opcode, local_state)
        return (_signed64(lhs) >> (rhs & 0x3F)) & MASK64
    if opcode == "OR":
        lhs = _encoded_source_value(row, "src0", _sll_src_l(row), opcode, local_state)
        rhs = _encoded_source_value(row, "src1", _sll_src_r(row), opcode, local_state)
        return (lhs | rhs) & MASK64
    raise AssertionError(opcode)


def _validate_reduced_row(
    row: dict[str, int],
    index: int,
    local_state: dict[str, list[int | None]]) -> dict[str, int]:
    opcode = _classify(row)
    if opcode is None:
        raise RowExtractionError(
            f"row {index} has unsupported opcode for reduced RF/ALU gate: "
            f"pc=0x{row['pc']:x} insn=0x{_mask_insn(row['insn'], row['len']):x} len={row['len']}"
        )
    if row["trap_valid"]:
        raise RowExtractionError(f"row {index} {opcode} has trap_valid=1")
    if row["mem_valid"] and opcode not in {
        "FENTRY",
        "C.LDI",
        "C.SDI",
        "LDI",
        "LD.PCR",
        "HL.LD.PCR",
        "FRET_STK",
        "SBI",
        "SD",
        "SDI",
        "SWI",
    }:
        raise RowExtractionError(f"row {index} {opcode} has mem_valid=1")
    if opcode != "FRET_STK" and row["next_pc"] != row["pc"] + row["len"]:
        raise RowExtractionError(
            f"row {index} {opcode} is not sequential: next_pc=0x{row['next_pc']:x}, "
            f"expected 0x{row['pc'] + row['len']:x}"
        )

    if opcode in {"ADD", "ADDW", "SUB"}:
        _encoded_source_value(row, "src0", _sll_src_l(row), opcode, local_state)
        _encoded_source_value(row, "src1", _sll_src_r(row), opcode, local_state)
    elif opcode in {"ADDI", "SUBI"}:
        if row["src1_valid"]:
            raise RowExtractionError(f"{opcode} row has src1_valid={row['src1_valid']}, expected 0")
        _encoded_source_value(row, "src0", _sll_src_l(row), opcode, local_state)
    elif opcode == "ADDTPC":
        _require_sources(row, opcode, src0=False, src1=False)
    elif opcode == "C.MOVI":
        _require_sources(row, opcode, src0=False, src1=False)
    elif opcode == "C.MOVR":
        if row["src1_valid"]:
            raise RowExtractionError(f"{opcode} row has src1_valid={row['src1_valid']}, expected 0")
        _encoded_source_value(row, "src0", _c_src_l(row), opcode, local_state)
    elif opcode == "C.SETRET":
        _require_sources(row, opcode, src0=False, src1=False)
    elif opcode == "FENTRY":
        _require_sources(row, opcode, src0=False, src1=False)
        if _fentry_m(row) < 2 or _fentry_m(row) > 23 or _fentry_n(row) < 2 or _fentry_n(row) > 23:
            raise RowExtractionError(f"row {index} FENTRY save register is outside scalar GPR range")
        if not row["mem_valid"] or not row["mem_is_store"] or row["mem_size"] != 8:
            raise RowExtractionError(f"row {index} FENTRY must carry one 8-byte store")
    elif opcode == "FRET_STK":
        _require_sources(row, opcode, src0=False, src1=False)
        if row["mem_valid"]:
            if row["mem_is_store"] or row["mem_size"] != 8:
                raise RowExtractionError(f"row {index} FRET_STK must carry one 8-byte RA load")
            if row["dst_reg"] != 10 or row["wb_rd"] != 10:
                raise RowExtractionError(f"row {index} FRET_STK RA load must write x10")
            if row["dst_data"] != row["mem_rdata"] or row["wb_data"] != row["mem_rdata"]:
                raise RowExtractionError(f"row {index} FRET_STK RA data must match memory read data")
            if row["next_pc"] != row["mem_rdata"]:
                raise RowExtractionError(f"row {index} FRET_STK next_pc must use the loaded RA")
    elif opcode == "C.LDI":
        if row["src1_valid"]:
            raise RowExtractionError(f"{opcode} row has src1_valid={row['src1_valid']}, expected 0")
        src0 = _encoded_source_value(row, "src0", _c_src_l(row), opcode, local_state)
        if not row["mem_valid"] or row["mem_is_store"] or row["mem_size"] != 8:
            raise RowExtractionError(f"row {index} C.LDI must carry one 8-byte load")
        expected_addr = (src0 + _simm5_11_scaled_double(row)) & MASK64
        if row["mem_addr"] != expected_addr:
            raise RowExtractionError(f"row {index} C.LDI load address does not match src0+imm")
        if row["dst_data"] != row["mem_rdata"]:
            raise RowExtractionError(f"row {index} C.LDI destination data does not match load data")
    elif opcode == "C.SDI":
        base = _encoded_source_value(row, "src0", _c_src_l(row), opcode, local_state)
        store_data = _local_source_value(local_state, 24, opcode)
        if row["src0_valid"] or row["src1_valid"]:
            raise RowExtractionError(f"row {index} C.SDI local sources must be suppressed")
        if not row["mem_valid"] or not row["mem_is_store"] or row["mem_size"] != 8:
            raise RowExtractionError(f"row {index} C.SDI must carry one 8-byte store")
        expected_addr = (base + _simm5_11_scaled_double(row)) & MASK64
        if row["mem_addr"] != expected_addr:
            raise RowExtractionError(f"row {index} C.SDI store address does not match src0+imm")
        if row["mem_wdata"] != store_data:
            raise RowExtractionError(f"row {index} C.SDI store data does not match T0")
        if row["mem_rdata"] != 0:
            raise RowExtractionError(f"row {index} C.SDI store row must not carry read data")
    elif opcode == "LDI":
        if row["src1_valid"]:
            raise RowExtractionError(f"{opcode} row has src1_valid={row['src1_valid']}, expected 0")
        _require_scalar_reg(row, "src0_reg", opcode)
        if not row["src0_valid"]:
            raise RowExtractionError(f"{opcode} row has src0_valid=0")
        if not row["mem_valid"] or row["mem_is_store"] or row["mem_size"] != 8:
            raise RowExtractionError(f"row {index} LDI must carry one 8-byte load")
        expected_addr = (row["src0_data"] + _simm12_20_s12_scaled_double(row)) & MASK64
        if row["mem_addr"] != expected_addr:
            raise RowExtractionError(f"row {index} LDI load address does not match src0+imm")
        if row["dst_data"] != row["mem_rdata"]:
            raise RowExtractionError(f"row {index} LDI destination data does not match load data")
    elif opcode in {"ANDI", "ANDIW", "ORI"}:
        _encoded_source_value(row, "src0", _sll_src_l(row), opcode, local_state)
        if row["src1_valid"]:
            raise RowExtractionError(f"{opcode} row has src1_valid={row['src1_valid']}, expected 0")
    elif opcode == "LD.PCR":
        _require_sources(row, opcode, src0=False, src1=False)
        if not row["mem_valid"] or row["mem_is_store"] or row["mem_size"] != 8:
            raise RowExtractionError(f"row {index} LD.PCR must carry one 8-byte load")
        expected_addr = (row["pc"] + _simm17_unscaled(row)) & MASK64
        if row["mem_addr"] != expected_addr:
            raise RowExtractionError(f"row {index} LD.PCR load address does not match pc+imm")
        if row["dst_data"] != row["mem_rdata"]:
            raise RowExtractionError(f"row {index} LD.PCR destination data does not match load data")
    elif opcode == "HL.LD.PCR":
        _require_sources(row, opcode, src0=False, src1=False)
        if not row["mem_valid"] or row["mem_is_store"] or row["mem_size"] != 8:
            raise RowExtractionError(f"row {index} HL.LD.PCR must carry one 8-byte load")
        expected_addr = (row["pc"] + _hl_pcr_simm(row)) & MASK64
        if row["mem_addr"] != expected_addr:
            raise RowExtractionError(f"row {index} HL.LD.PCR load address does not match pc+imm")
        if row["dst_data"] != row["mem_rdata"]:
            raise RowExtractionError(f"row {index} HL.LD.PCR destination data does not match load data")
    elif opcode in {"C.SETC_EQ", "C.SETC_NE"}:
        _encoded_source_value(row, "src0", _c_src_l(row), opcode, local_state)
        _encoded_source_value(row, "src1", _c_src_r(row), opcode, local_state)
    elif opcode == "SETC_LTU":
        _require_sources(row, opcode, src0=True, src1=True)
    elif opcode == "SETC_LTUI":
        _require_sources(row, opcode, src0=True, src1=False)
    elif opcode == "C.SETC_TGT":
        _encoded_source_value(row, "src0", _c_src_l(row), opcode, local_state)
        if row["src1_valid"]:
            raise RowExtractionError(f"{opcode} row has src1_valid={row['src1_valid']}, expected 0")
    elif opcode == "SETC_TGT":
        _require_sources(row, opcode, src0=True, src1=False)
    elif opcode == "SD":
        base = _encoded_source_value(row, "src0", _sll_src_l(row), opcode, local_state)
        index_value = _encoded_source_value(row, "src1", _sll_src_r(row), opcode, local_state)
        store_src = _srcp(row)
        if not _is_local_source_reg(store_src):
            raise RowExtractionError(
                f"row {index} SD uses scalar store data reg {store_src}; reduced RF/ALU row schema has no src2"
            )
        store_data = _local_source_value(local_state, store_src, opcode)
        if not row["mem_valid"] or not row["mem_is_store"] or row["mem_size"] != 8:
            raise RowExtractionError(f"row {index} SD must carry one 8-byte store")
        expected_addr = (base + ((index_value << 3) & MASK64)) & MASK64
        if row["mem_addr"] != expected_addr:
            raise RowExtractionError(f"row {index} SD store address does not match src0+(src1<<3)")
        if row["mem_wdata"] != store_data:
            raise RowExtractionError(f"row {index} SD store data does not match srcp")
        if row["mem_rdata"] != 0:
            raise RowExtractionError(f"row {index} SD store row must not carry read data")
    elif opcode == "SBI":
        store_data = _encoded_source_value(row, "src0", _sll_src_l(row), opcode, local_state)
        base = _encoded_source_value(row, "src1", _sll_src_r(row), opcode, local_state)
        if not row["mem_valid"] or not row["mem_is_store"] or row["mem_size"] != 1:
            raise RowExtractionError(f"row {index} SBI must carry one 1-byte store")
        expected_addr = (base + _simm12_7_s5_25_7(row)) & MASK64
        if row["mem_addr"] != expected_addr:
            raise RowExtractionError(f"row {index} SBI store address does not match src1+imm")
        if row["mem_wdata"] != store_data:
            raise RowExtractionError(f"row {index} SBI store data does not match src0")
        if row["mem_rdata"] != 0:
            raise RowExtractionError(f"row {index} SBI store row must not carry read data")
    elif opcode == "SWI":
        store_data = _encoded_source_value(row, "src0", _sll_src_l(row), opcode, local_state)
        base = _encoded_source_value(row, "src1", _sll_src_r(row), opcode, local_state)
        if not row["mem_valid"] or not row["mem_is_store"] or row["mem_size"] != 4:
            raise RowExtractionError(f"row {index} SWI must carry one 4-byte store")
        expected_addr = (base + _simm12_7_s5_25_7_scaled_word(row)) & MASK64
        if row["mem_addr"] != expected_addr:
            raise RowExtractionError(f"row {index} SWI store address does not match src1+(imm<<2)")
        if row["mem_wdata"] != store_data:
            raise RowExtractionError(f"row {index} SWI store data does not match src0")
        if row["mem_rdata"] != 0:
            raise RowExtractionError(f"row {index} SWI store row must not carry read data")
    elif opcode == "SDI":
        store_data = _encoded_source_value(row, "src0", _sll_src_l(row), opcode, local_state)
        base = _encoded_source_value(row, "src1", _sll_src_r(row), opcode, local_state)
        if not row["mem_valid"] or not row["mem_is_store"] or row["mem_size"] != 8:
            raise RowExtractionError(f"row {index} SDI must carry one 8-byte store")
        expected_addr = (base + _simm12_7_s5_25_7_scaled_double(row)) & MASK64
        if row["mem_addr"] != expected_addr:
            raise RowExtractionError(f"row {index} SDI store address does not match src1+imm")
        if row["mem_wdata"] != store_data:
            raise RowExtractionError(f"row {index} SDI store data does not match src0")
        if row["mem_rdata"] != 0:
            raise RowExtractionError(f"row {index} SDI store row must not carry read data")
    elif opcode in {"C.ADD", "C.AND", "C.SUB"}:
        _encoded_source_value(row, "src0", _c_src_l(row), opcode, local_state)
        _encoded_source_value(row, "src1", _c_src_r(row), opcode, local_state)
    elif opcode == "HL.LUI":
        _require_sources(row, opcode, src0=False, src1=False)
    elif opcode in {"SLL", "SRL", "SRA", "OR"}:
        _encoded_source_value(row, "src0", _sll_src_l(row), opcode, local_state)
        _encoded_source_value(row, "src1", _sll_src_r(row), opcode, local_state)
    elif opcode in {"MUL", "MULW"}:
        _encoded_source_value(row, "src0", _sll_src_l(row), opcode, local_state)
        _encoded_source_value(row, "src1", _sll_src_r(row), opcode, local_state)
    elif opcode == "SLLI":
        if row["src1_valid"]:
            raise RowExtractionError(f"{opcode} row has src1_valid={row['src1_valid']}, expected 0")
        _encoded_source_value(row, "src0", _sll_src_l(row), opcode, local_state)
    synthesize_c_local_writeback = opcode in {"C.ADD", "C.AND", "C.SUB"} and _is_no_writeback_trace_gap(row)
    no_writeback_opcode = opcode in {
        "C.SETC_EQ",
        "C.SETC_NE",
        "C.SETC_TGT",
        "C.SDI",
        "SBI",
        "SETC_LTU",
        "SETC_LTUI",
        "SETC_TGT",
        "SD",
        "SDI",
        "SWI",
    } or (opcode == "FRET_STK" and not row["mem_valid"])
    suppress_setc_immediate_dst = _is_setc_immediate_dst_artifact(row, opcode)
    if no_writeback_opcode and not (_is_no_writeback_trace_gap(row) or suppress_setc_immediate_dst):
        raise RowExtractionError(f"row {index} {opcode} must not write a destination")
    if not synthesize_c_local_writeback and not no_writeback_opcode:
        _require_writeback(row, opcode)
        if opcode in {"C.ADD", "C.AND", "C.SUB"} and row["dst_reg"] != 31:
            raise RowExtractionError(f"row {index} {opcode} must write implicit T destination tag 31")

    expected = _expected_result(row, opcode, local_state)
    if not synthesize_c_local_writeback and not no_writeback_opcode and row["dst_data"] != expected:
        raise RowExtractionError(
            f"row {index} {opcode} result mismatch: dst_data=0x{row['dst_data']:x}, expected 0x{expected:x}"
        )

    out = {field: int(row[field]) for field in REQUIRED_TRACE_FIELDS}
    out["insn"] = _mask_insn(out["insn"], out["len"])
    if suppress_setc_immediate_dst:
        out["dst_valid"] = 0
        out["dst_reg"] = 0
        out["dst_data"] = 0
    if synthesize_c_local_writeback:
        out["dst_valid"] = 1
        out["dst_reg"] = 31
        out["dst_data"] = expected
        out["wb_valid"] = 1
        out["wb_rd"] = 31
        out["wb_data"] = expected
    return out


def _validate_block_marker_row(
    row: dict[str, int],
    index: int,
    marker: tuple[str, bool, bool]) -> dict[str, Any]:
    opcode, boundary, stop = marker
    if row["trap_valid"]:
        raise RowExtractionError(f"row {index} {opcode} has trap_valid=1")
    if row["mem_valid"]:
        raise RowExtractionError(f"row {index} {opcode} has mem_valid=1")
    redirecting_boundary = boundary and not stop and row["next_pc"] != row["pc"] + row["len"]
    if redirecting_boundary and not opcode.endswith("BSTART"):
        raise RowExtractionError(f"row {index} {opcode} has unsupported marker redirect")
    if not stop and not redirecting_boundary and row["next_pc"] != row["pc"] + row["len"]:
        raise RowExtractionError(
            f"row {index} {opcode} is not sequential: next_pc=0x{row['next_pc']:x}, "
            f"expected 0x{row['pc'] + row['len']:x}"
        )

    out: dict[str, Any] = {field: int(row[field]) for field in REQUIRED_TRACE_FIELDS}
    out["insn"] = _mask_insn(out["insn"], out["len"])
    out["skip"] = 1
    out["skip_kind"] = opcode
    out["block_boundary"] = int(boundary)
    out["block_stop"] = int(stop)
    return out


def _is_zero_advance_marker_artifact(row: dict[str, int], marker: tuple[str, bool, bool]) -> bool:
    opcode, boundary, stop = marker
    return (
        boundary and
        not stop and
        opcode.endswith("BSTART") and
        row["next_pc"] == row["pc"] and
        not row["trap_valid"] and
        not row["mem_valid"] and
        not row["wb_valid"]
    )


def _is_fret_stk_zero_advance_load_prefix(row: dict[str, int], next_row: dict[str, int] | None) -> bool:
    if next_row is None or _classify(row) != "FRET_STK" or _classify(next_row) != "FRET_STK":
        return False
    return (
        row["pc"] == next_row["pc"] and
        _mask_insn(row["insn"], row["len"]) == _mask_insn(next_row["insn"], next_row["len"]) and
        row["next_pc"] == row["pc"] and
        not row["trap_valid"] and
        not row["mem_valid"] and
        not row["wb_valid"] and
        not row["dst_valid"] and
        bool(next_row["mem_valid"]) and
        not next_row["mem_is_store"] and
        next_row["mem_size"] == 8 and
        next_row["dst_valid"] and
        next_row["dst_reg"] == 10 and
        next_row["wb_valid"] and
        next_row["wb_rd"] == 10 and
        next_row["next_pc"] == next_row["mem_rdata"]
    )


def _normalized_rows(path: Path) -> Iterable[dict[str, int]]:
    for seq, obj in enumerate(load_jsonl(path)):
        yield normalize_row(obj, seq)


def extract_rows(
    input_path: Path,
    output_path: Path,
    max_rows: int = 0,
    allow_block_markers: bool = False) -> int:
    if max_rows < 0:
        raise RowExtractionError("--max-rows must be non-negative")

    rows: list[dict[str, Any]] = []
    expected_pc: int | None = None
    local_state = _new_local_state()
    reduced_rows = 0
    normalized = list(_normalized_rows(input_path))
    for index, row in enumerate(normalized):
        if max_rows > 0 and reduced_rows >= max_rows:
            break
        if expected_pc is not None and row["pc"] != expected_pc:
            raise RowExtractionError(
                f"row {index} breaks the strict sequential prefix: pc=0x{row['pc']:x}, expected 0x{expected_pc:x}"
            )

        marker = _classify_block_marker(row) if allow_block_markers else None
        if marker is not None and _is_zero_advance_marker_artifact(row, marker):
            continue
        next_row = normalized[index + 1] if index + 1 < len(normalized) else None
        if _is_fret_stk_zero_advance_load_prefix(row, next_row):
            continue
        if marker is not None:
            checked = _validate_block_marker_row(row, index, marker)
        else:
            checked = _validate_reduced_row(row, index, local_state)
            _push_local_destination(local_state, checked["dst_reg"], checked["dst_data"])
            reduced_rows += 1
        rows.append(checked)
        expected_pc = checked["next_pc"]

    if not rows:
        raise RowExtractionError(f"no reduced RF/ALU rows were extracted from {input_path}")

    output_path.parent.mkdir(parents=True, exist_ok=True)
    with output_path.open("w", encoding="utf-8") as out:
        for row in rows:
            out.write(json.dumps(row, sort_keys=True, separators=(",", ":")) + "\n")
    return len(rows)


def _write_jsonl(path: Path, rows: Iterable[dict[str, Any]]) -> None:
    with path.open("w", encoding="utf-8") as out:
        for row in rows:
            out.write(json.dumps(row, sort_keys=True, separators=(",", ":")) + "\n")


def self_test() -> None:
    from frontend_fetch_rf_alu_fixture_rows import fixture_rows

    with tempfile.TemporaryDirectory(prefix="linx-qemu-rows-") as td:
        tmp = Path(td)
        source = tmp / "qemu.jsonl"
        output = tmp / "rows.jsonl"
        rows = fixture_rows()
        _write_jsonl(source, [{"type": "META", "schema": "ignored"}, {"valid": 0}, *rows])

        count = extract_rows(source, output, max_rows=3)
        assert count == 3
        extracted = [json.loads(line) for line in output.read_text(encoding="utf-8").splitlines()]
        assert extracted[0]["pc"] == 0x1000
        assert extracted[0]["dst_data"] == 42
        assert extracted[1]["src0_data"] == 42
        assert extracted[2]["len"] == 2

        addtpc_source = tmp / "addtpc.jsonl"
        addtpc_output = tmp / "addtpc.rows.jsonl"
        addtpc = {
            **rows[0],
            "pc": 0x400054F8,
            "insn": 0x00009187,
            "len": 4,
            "next_pc": 0x400054FC,
            "wb_valid": 1,
            "wb_rd": 3,
            "wb_data": 0x4000E000,
            "dst_valid": 1,
            "dst_reg": 3,
            "dst_data": 0x4000E000,
            "src0_valid": 0,
            "src0_reg": 0,
            "src0_data": 0,
            "src1_valid": 0,
            "src1_reg": 0,
            "src1_data": 0,
        }
        _write_jsonl(addtpc_source, [addtpc])
        count = extract_rows(addtpc_source, addtpc_output)
        assert count == 1
        extracted_addtpc = [json.loads(line) for line in addtpc_output.read_text(encoding="utf-8").splitlines()]
        assert extracted_addtpc[0]["dst_data"] == 0x4000E000

        addtpc_t_source = tmp / "addtpc-t-dst.jsonl"
        addtpc_t_output = tmp / "addtpc-t-dst.rows.jsonl"
        addtpc_t = {
            **addtpc,
            "pc": 0x40005CB2,
            "insn": 0x00009F87,
            "next_pc": 0x40005CB6,
            "wb_rd": 31,
            "dst_reg": 31,
        }
        _write_jsonl(addtpc_t_source, [addtpc_t])
        count = extract_rows(addtpc_t_source, addtpc_t_output)
        assert count == 1
        extracted_addtpc_t = [
            json.loads(line) for line in addtpc_t_output.read_text(encoding="utf-8").splitlines()
        ]
        assert extracted_addtpc_t[0]["dst_reg"] == 31
        assert extracted_addtpc_t[0]["wb_rd"] == 31
        assert extracted_addtpc_t[0]["dst_data"] == 0x4000E000

        c_setret_source = tmp / "c-setret.jsonl"
        c_setret_output = tmp / "c-setret.rows.jsonl"
        c_setret = {
            **rows[0],
            "pc": 0x40005506,
            "insn": 0x5096,
            "len": 2,
            "next_pc": 0x40005508,
            "wb_valid": 1,
            "wb_rd": 10,
            "wb_data": 0x4000550A,
            "dst_valid": 1,
            "dst_reg": 10,
            "dst_data": 0x4000550A,
            "src0_valid": 0,
            "src0_reg": 0,
            "src0_data": 0,
            "src1_valid": 0,
            "src1_reg": 0,
            "src1_data": 0,
        }
        _write_jsonl(c_setret_source, [c_setret])
        count = extract_rows(c_setret_source, c_setret_output)
        assert count == 1
        extracted_c_setret = [json.loads(line) for line in c_setret_output.read_text(encoding="utf-8").splitlines()]
        assert extracted_c_setret[0]["dst_data"] == 0x4000550A

        fentry_source = tmp / "fentry.jsonl"
        fentry_output = tmp / "fentry.rows.jsonl"
        fentry = {
            **rows[0],
            "pc": 0x4000550E,
            "insn": 0x90A50041,
            "len": 4,
            "next_pc": 0x40005512,
            "wb_valid": 1,
            "wb_rd": 1,
            "wb_data": 0x4FFE_FDB0,
            "dst_valid": 1,
            "dst_reg": 1,
            "dst_data": 0x4FFE_FDB0,
            "src0_valid": 0,
            "src0_reg": 0,
            "src0_data": 0,
            "src1_valid": 0,
            "src1_reg": 0,
            "src1_data": 0,
            "mem_valid": 1,
            "mem_is_store": 1,
            "mem_addr": 0x4FFE_FFE8,
            "mem_wdata": 0x4000550A,
            "mem_rdata": 0,
            "mem_size": 8,
        }
        _write_jsonl(fentry_source, [fentry])
        count = extract_rows(fentry_source, fentry_output)
        assert count == 1
        extracted_fentry = [json.loads(line) for line in fentry_output.read_text(encoding="utf-8").splitlines()]
        assert extracted_fentry[0]["dst_data"] == 0x4FFE_FDB0
        assert extracted_fentry[0]["mem_wdata"] == 0x4000550A

        fentry_range_source = tmp / "fentry-range.jsonl"
        fentry_range_output = tmp / "fentry-range.rows.jsonl"
        fentry_range = {
            **rows[0],
            "pc": 0x4000_5F2C,
            "insn": 0x0CD5_0041,
            "len": 4,
            "next_pc": 0x4000_5F30,
            "wb_valid": 1,
            "wb_rd": 1,
            "wb_data": 0x4FFE_FD70,
            "dst_valid": 1,
            "dst_reg": 1,
            "dst_data": 0x4FFE_FD70,
            "src0_valid": 0,
            "src0_reg": 0,
            "src0_data": 0,
            "src1_valid": 0,
            "src1_reg": 0,
            "src1_data": 0,
            "mem_valid": 1,
            "mem_is_store": 1,
            "mem_addr": 0x4FFE_FD80,
            "mem_wdata": 0,
            "mem_rdata": 0,
            "mem_size": 8,
        }
        _write_jsonl(fentry_range_source, [fentry_range])
        count = extract_rows(fentry_range_source, fentry_range_output)
        assert count == 1
        extracted_fentry_range = [
            json.loads(line) for line in fentry_range_output.read_text(encoding="utf-8").splitlines()
        ]
        assert extracted_fentry_range[0]["dst_data"] == 0x4FFE_FD70
        assert extracted_fentry_range[0]["mem_addr"] == 0x4FFE_FD80

        u_dst_source = tmp / "addi-u-dst.jsonl"
        u_dst_output = tmp / "addi-u-dst.rows.jsonl"
        addi_u_dst = {
            **rows[0],
            "pc": 0x40005516,
            "insn": 0x02000F15,
            "len": 4,
            "next_pc": 0x4000551A,
            "wb_valid": 1,
            "wb_rd": 30,
            "wb_data": 32,
            "dst_valid": 1,
            "dst_reg": 30,
            "dst_data": 32,
            "src0_valid": 1,
            "src0_reg": 0,
            "src0_data": 0,
            "src1_valid": 0,
            "src1_reg": 0,
            "src1_data": 0,
        }
        _write_jsonl(u_dst_source, [addi_u_dst])
        count = extract_rows(u_dst_source, u_dst_output)
        assert count == 1
        extracted_u_dst = [json.loads(line) for line in u_dst_output.read_text(encoding="utf-8").splitlines()]
        assert extracted_u_dst[0]["dst_reg"] == 30
        assert extracted_u_dst[0]["wb_rd"] == 30
        assert extracted_u_dst[0]["dst_data"] == 32

        t_dst_source = tmp / "addi-t-dst.jsonl"
        t_dst_output = tmp / "addi-t-dst.rows.jsonl"
        addi_t_dst = {
            **rows[0],
            "pc": 0x400055D6,
            "insn": 0x01038F95,
            "len": 4,
            "next_pc": 0x400055DA,
            "wb_valid": 1,
            "wb_rd": 31,
            "wb_data": 0x4000_ECE8,
            "dst_valid": 1,
            "dst_reg": 31,
            "dst_data": 0x4000_ECE8,
            "src0_valid": 1,
            "src0_reg": 7,
            "src0_data": 0x4000_ECD8,
            "src1_valid": 0,
            "src1_reg": 0,
            "src1_data": 0,
        }
        _write_jsonl(t_dst_source, [addi_t_dst])
        count = extract_rows(t_dst_source, t_dst_output)
        assert count == 1
        extracted_t_dst = [json.loads(line) for line in t_dst_output.read_text(encoding="utf-8").splitlines()]
        assert extracted_t_dst[0]["dst_reg"] == 31
        assert extracted_t_dst[0]["wb_rd"] == 31
        assert extracted_t_dst[0]["dst_data"] == 0x4000_ECE8

        subi_source = tmp / "subi.jsonl"
        subi_output = tmp / "subi.rows.jsonl"
        subi = {
            **rows[0],
            "pc": 0x40005656,
            "insn": 0x01801415,
            "len": 4,
            "next_pc": 0x4000565A,
            "wb_valid": 1,
            "wb_rd": 8,
            "wb_data": MASK64 - 23,
            "dst_valid": 1,
            "dst_reg": 8,
            "dst_data": MASK64 - 23,
            "src0_valid": 1,
            "src0_reg": 0,
            "src0_data": 0,
            "src1_valid": 0,
            "src1_reg": 0,
            "src1_data": 0,
            "mem_valid": 0,
            "mem_is_store": 0,
            "mem_addr": 0,
            "mem_wdata": 0,
            "mem_rdata": 0,
            "mem_size": 0,
        }
        _write_jsonl(subi_source, [subi])
        count = extract_rows(subi_source, subi_output)
        assert count == 1
        extracted_subi = [json.loads(line) for line in subi_output.read_text(encoding="utf-8").splitlines()]
        assert extracted_subi[0]["pc"] == 0x40005656
        assert extracted_subi[0]["dst_reg"] == 8
        assert extracted_subi[0]["dst_data"] == MASK64 - 23

        c_and_source = tmp / "c-and-local.jsonl"
        c_and_output = tmp / "c-and-local.rows.jsonl"
        addi_u_for_c_and = {
            **addi_u_dst,
            "pc": 0x5100,
            "next_pc": 0x5104,
        }
        addi_t_for_c_and = {
            **addi_t_dst,
            "pc": 0x5104,
            "next_pc": 0x5108,
        }
        c_and_local = {
            **rows[0],
            "pc": 0x5108,
            "insn": 0xC728,
            "len": 2,
            "next_pc": 0x510A,
            "wb_valid": 0,
            "wb_rd": 0,
            "wb_data": 0,
            "dst_valid": 0,
            "dst_reg": 0,
            "dst_data": 0,
            "src0_valid": 0,
            "src0_reg": 0,
            "src0_data": 0,
            "src1_valid": 0,
            "src1_reg": 0,
            "src1_data": 0,
            "mem_valid": 0,
            "mem_is_store": 0,
            "mem_addr": 0,
            "mem_wdata": 0,
            "mem_rdata": 0,
            "mem_size": 0,
        }
        _write_jsonl(c_and_source, [addi_u_for_c_and, addi_t_for_c_and, c_and_local])
        count = extract_rows(c_and_source, c_and_output)
        assert count == 3
        extracted_c_and = [
            json.loads(line) for line in c_and_output.read_text(encoding="utf-8").splitlines()
        ]
        assert extracted_c_and[2]["pc"] == 0x5108
        assert extracted_c_and[2]["dst_valid"] == 1
        assert extracted_c_and[2]["dst_reg"] == 31
        assert extracted_c_and[2]["dst_data"] == 32

        add_local_scalar_source = tmp / "add-local-scalar.jsonl"
        add_local_scalar_output = tmp / "add-local-scalar.rows.jsonl"
        addi_t_for_add = {
            **addi_t_dst,
            "pc": 0x5200,
            "next_pc": 0x5204,
        }
        add_t_scalar_to_u = {
            **rows[0],
            "pc": 0x5204,
            "insn": 0x062C0F05,
            "len": 4,
            "next_pc": 0x5208,
            "wb_valid": 1,
            "wb_rd": 30,
            "wb_data": 0x4000_ECE8,
            "dst_valid": 1,
            "dst_reg": 30,
            "dst_data": 0x4000_ECE8,
            "src0_valid": 0,
            "src0_reg": 0,
            "src0_data": 0,
            "src1_valid": 1,
            "src1_reg": 2,
            "src1_data": 0,
            "mem_valid": 0,
            "mem_is_store": 0,
            "mem_addr": 0,
            "mem_wdata": 0,
            "mem_rdata": 0,
            "mem_size": 0,
        }
        _write_jsonl(add_local_scalar_source, [addi_t_for_add, add_t_scalar_to_u])
        count = extract_rows(add_local_scalar_source, add_local_scalar_output)
        assert count == 2
        extracted_add_local = [
            json.loads(line) for line in add_local_scalar_output.read_text(encoding="utf-8").splitlines()
        ]
        assert extracted_add_local[1]["pc"] == 0x5204
        assert extracted_add_local[1]["src0_valid"] == 0
        assert extracted_add_local[1]["src1_valid"] == 1
        assert extracted_add_local[1]["dst_reg"] == 30
        assert extracted_add_local[1]["dst_data"] == 0x4000_ECE8

        c_sdi_source = tmp / "c-sdi-local.jsonl"
        c_sdi_output = tmp / "c-sdi-local.rows.jsonl"
        addi_u_for_c_sdi = {
            **addi_u_dst,
            "pc": 0x5300,
            "next_pc": 0x5304,
        }
        addi_t_for_c_sdi = {
            **addi_t_dst,
            "pc": 0x5304,
            "next_pc": 0x5308,
        }
        c_sdi_local = {
            **rows[0],
            "pc": 0x5308,
            "insn": 0x073A,
            "len": 2,
            "next_pc": 0x530A,
            "wb_valid": 0,
            "wb_rd": 0,
            "wb_data": 0,
            "dst_valid": 0,
            "dst_reg": 0,
            "dst_data": 0,
            "src0_valid": 0,
            "src0_reg": 0,
            "src0_data": 0,
            "src1_valid": 0,
            "src1_reg": 0,
            "src1_data": 0,
            "mem_valid": 1,
            "mem_is_store": 1,
            "mem_addr": 32,
            "mem_wdata": 0x4000_ECE8,
            "mem_rdata": 0,
            "mem_size": 8,
        }
        _write_jsonl(c_sdi_source, [addi_u_for_c_sdi, addi_t_for_c_sdi, c_sdi_local])
        count = extract_rows(c_sdi_source, c_sdi_output)
        assert count == 3
        extracted_c_sdi = [
            json.loads(line) for line in c_sdi_output.read_text(encoding="utf-8").splitlines()
        ]
        assert extracted_c_sdi[2]["pc"] == 0x5308
        assert extracted_c_sdi[2]["dst_valid"] == 0
        assert extracted_c_sdi[2]["mem_valid"] == 1
        assert extracted_c_sdi[2]["mem_is_store"] == 1
        assert extracted_c_sdi[2]["mem_addr"] == 32
        assert extracted_c_sdi[2]["mem_wdata"] == 0x4000_ECE8

        c_movr_t_source = tmp / "c-movr-t-src.jsonl"
        c_movr_t_output = tmp / "c-movr-t-src.rows.jsonl"
        ldi_after_addi_t = {
            **rows[0],
            "pc": 0x400055DA,
            "insn": 0x0003B119,
            "len": 4,
            "next_pc": 0x400055DE,
            "wb_valid": 1,
            "wb_rd": 2,
            "wb_data": 21,
            "dst_valid": 1,
            "dst_reg": 2,
            "dst_data": 21,
            "src0_valid": 1,
            "src0_reg": 7,
            "src0_data": 0x4000_ECD8,
            "src1_valid": 0,
            "src1_reg": 0,
            "src1_data": 0,
            "mem_valid": 1,
            "mem_is_store": 0,
            "mem_addr": 0x4000_ECD8,
            "mem_wdata": 0,
            "mem_rdata": 21,
            "mem_size": 8,
        }
        setc_eq_before_movr = {
            **rows[0],
            "pc": 0x400055DE,
            "insn": 0x28A6,
            "len": 2,
            "next_pc": 0x400055E0,
            "wb_valid": 0,
            "wb_rd": 0,
            "wb_data": 0,
            "dst_valid": 0,
            "dst_reg": 0,
            "dst_data": 0,
            "src0_valid": 1,
            "src0_reg": 2,
            "src0_data": 21,
            "src1_valid": 1,
            "src1_reg": 5,
            "src1_data": 0,
            "mem_valid": 0,
            "mem_is_store": 0,
            "mem_addr": 0,
            "mem_wdata": 0,
            "mem_rdata": 0,
            "mem_size": 0,
        }
        c_movr_t_src = {
            **rows[0],
            "pc": 0x400055E0,
            "insn": 0x3E06,
            "len": 2,
            "next_pc": 0x400055E2,
            "wb_valid": 1,
            "wb_rd": 7,
            "wb_data": 0x4000_ECE8,
            "dst_valid": 1,
            "dst_reg": 7,
            "dst_data": 0x4000_ECE8,
            "src0_valid": 0,
            "src0_reg": 0,
            "src0_data": 0,
            "src1_valid": 0,
            "src1_reg": 0,
            "src1_data": 0,
        }
        _write_jsonl(c_movr_t_source, [addi_t_dst, ldi_after_addi_t, setc_eq_before_movr, c_movr_t_src])
        count = extract_rows(c_movr_t_source, c_movr_t_output)
        assert count == 4
        extracted_c_movr_t = [
            json.loads(line) for line in c_movr_t_output.read_text(encoding="utf-8").splitlines()
        ]
        assert extracted_c_movr_t[1]["mem_rdata"] == 21
        assert extracted_c_movr_t[2]["dst_valid"] == 0
        assert extracted_c_movr_t[3]["src0_valid"] == 0
        assert extracted_c_movr_t[3]["dst_reg"] == 7
        assert extracted_c_movr_t[3]["dst_data"] == 0x4000_ECE8

        hl_lui_source = tmp / "hl-lui.jsonl"
        hl_lui_output = tmp / "hl-lui.rows.jsonl"
        hl_lui = {
            **rows[0],
            "pc": 0x4000551A,
            "insn": 0x1F97000E,
            "len": 6,
            "next_pc": 0x40005520,
            "wb_valid": 1,
            "wb_rd": 31,
            "wb_data": 1,
            "dst_valid": 1,
            "dst_reg": 31,
            "dst_data": 1,
            "src0_valid": 0,
            "src0_reg": 0,
            "src0_data": 0,
            "src1_valid": 0,
            "src1_reg": 0,
            "src1_data": 0,
        }
        _write_jsonl(hl_lui_source, [hl_lui])
        count = extract_rows(hl_lui_source, hl_lui_output)
        assert count == 1
        extracted_hl_lui = [json.loads(line) for line in hl_lui_output.read_text(encoding="utf-8").splitlines()]
        assert extracted_hl_lui[0]["dst_reg"] == 31
        assert extracted_hl_lui[0]["wb_rd"] == 31
        assert extracted_hl_lui[0]["dst_data"] == 1

        sll_source = tmp / "sll-tu.jsonl"
        sll_output = tmp / "sll-tu.rows.jsonl"
        sll_tu = {
            **rows[0],
            "pc": 0x40005520,
            "insn": 0x01CC7F05,
            "len": 4,
            "next_pc": 0x40005524,
            "wb_valid": 1,
            "wb_rd": 30,
            "wb_data": 0x1_0000_0000,
            "dst_valid": 1,
            "dst_reg": 30,
            "dst_data": 0x1_0000_0000,
            "src0_valid": 0,
            "src0_reg": 0,
            "src0_data": 0,
            "src1_valid": 0,
            "src1_reg": 0,
            "src1_data": 0,
        }
        _write_jsonl(sll_source, [addi_u_dst, hl_lui, sll_tu])
        count = extract_rows(sll_source, sll_output)
        assert count == 3
        extracted_sll = [json.loads(line) for line in sll_output.read_text(encoding="utf-8").splitlines()]
        assert extracted_sll[2]["dst_reg"] == 30
        assert extracted_sll[2]["wb_rd"] == 30
        assert extracted_sll[2]["dst_data"] == 0x1_0000_0000

        hl_lui_zero = {
            **hl_lui,
            "pc": 0x40005524,
            "insn": 0x00000F97000E,
            "next_pc": 0x4000552A,
            "wb_data": 0,
            "dst_data": 0,
        }
        sll_t_dst_source = tmp / "sll-t-dst.jsonl"
        sll_t_dst_output = tmp / "sll-t-dst.rows.jsonl"
        sll_t_dst = {
            **rows[0],
            "pc": 0x4000552A,
            "insn": 0x01DC7F85,
            "len": 4,
            "next_pc": 0x4000552E,
            "wb_valid": 1,
            "wb_rd": 31,
            "wb_data": 0,
            "dst_valid": 1,
            "dst_reg": 31,
            "dst_data": 0,
            "src0_valid": 0,
            "src0_reg": 0,
            "src0_data": 0,
            "src1_valid": 0,
            "src1_reg": 0,
            "src1_data": 0,
        }
        _write_jsonl(sll_t_dst_source, [addi_u_dst, hl_lui, sll_tu, hl_lui_zero, sll_t_dst])
        count = extract_rows(sll_t_dst_source, sll_t_dst_output)
        assert count == 5
        extracted_sll_t_dst = [json.loads(line) for line in sll_t_dst_output.read_text(encoding="utf-8").splitlines()]
        assert extracted_sll_t_dst[4]["dst_reg"] == 31
        assert extracted_sll_t_dst[4]["wb_rd"] == 31
        assert extracted_sll_t_dst[4]["dst_data"] == 0

        srl_source = tmp / "srl-tu.jsonl"
        srl_output = tmp / "srl-tu.rows.jsonl"
        srl_tu = {
            **rows[0],
            "pc": 0x4000552E,
            "insn": 0x01DC5F85,
            "len": 4,
            "next_pc": 0x40005532,
            "wb_valid": 1,
            "wb_rd": 31,
            "wb_data": 0,
            "dst_valid": 1,
            "dst_reg": 31,
            "dst_data": 0,
            "src0_valid": 0,
            "src0_reg": 0,
            "src0_data": 0,
            "src1_valid": 0,
            "src1_reg": 0,
            "src1_data": 0,
        }
        _write_jsonl(srl_source, [addi_u_dst, hl_lui, sll_tu, hl_lui_zero, sll_t_dst, srl_tu])
        count = extract_rows(srl_source, srl_output)
        assert count == 6
        extracted_srl = [json.loads(line) for line in srl_output.read_text(encoding="utf-8").splitlines()]
        assert extracted_srl[5]["dst_reg"] == 31
        assert extracted_srl[5]["wb_rd"] == 31
        assert extracted_srl[5]["dst_data"] == 0

        or_source = tmp / "or-tu.jsonl"
        or_output = tmp / "or-tu.rows.jsonl"
        or_tu = {
            **rows[0],
            "pc": 0x40005532,
            "insn": 0x078E3F05,
            "len": 4,
            "next_pc": 0x40005536,
            "wb_valid": 1,
            "wb_rd": 30,
            "wb_data": 0x1_0000_0000,
            "dst_valid": 1,
            "dst_reg": 30,
            "dst_data": 0x1_0000_0000,
            "src0_valid": 0,
            "src0_reg": 0,
            "src0_data": 0,
            "src1_valid": 0,
            "src1_reg": 0,
            "src1_data": 0,
        }
        _write_jsonl(or_source, [addi_u_dst, hl_lui, sll_tu, hl_lui_zero, sll_t_dst, srl_tu, or_tu])
        count = extract_rows(or_source, or_output)
        assert count == 7
        extracted_or = [json.loads(line) for line in or_output.read_text(encoding="utf-8").splitlines()]
        assert extracted_or[6]["dst_reg"] == 30
        assert extracted_or[6]["wb_rd"] == 30
        assert extracted_or[6]["dst_data"] == 0x1_0000_0000

        c_ldi_source = tmp / "c-ldi.jsonl"
        c_ldi_output = tmp / "c-ldi.rows.jsonl"
        c_ldi = {
            **rows[0],
            "pc": 0x40005536,
            "insn": 0x011A,
            "len": 2,
            "next_pc": 0x40005538,
            "wb_valid": 1,
            "wb_rd": 31,
            "wb_data": 0,
            "dst_valid": 1,
            "dst_reg": 31,
            "dst_data": 0,
            "src0_valid": 1,
            "src0_reg": 4,
            "src0_data": 0x4FFE_FDB0,
            "src1_valid": 0,
            "src1_reg": 0,
            "src1_data": 0,
            "mem_valid": 1,
            "mem_is_store": 0,
            "mem_addr": 0x4FFE_FDB0,
            "mem_wdata": 0,
            "mem_rdata": 0,
            "mem_size": 8,
        }
        _write_jsonl(c_ldi_source, [addi_u_dst, hl_lui, sll_tu, hl_lui_zero, sll_t_dst, srl_tu, or_tu, c_ldi])
        count = extract_rows(c_ldi_source, c_ldi_output)
        assert count == 8
        extracted_c_ldi = [json.loads(line) for line in c_ldi_output.read_text(encoding="utf-8").splitlines()]
        assert extracted_c_ldi[7]["dst_reg"] == 31
        assert extracted_c_ldi[7]["wb_rd"] == 31
        assert extracted_c_ldi[7]["dst_data"] == 0
        assert extracted_c_ldi[7]["mem_valid"] == 1
        assert extracted_c_ldi[7]["mem_is_store"] == 0

        c_ldi_nonzero_source = tmp / "c-ldi-nonzero.jsonl"
        c_ldi_nonzero_output = tmp / "c-ldi-nonzero.rows.jsonl"
        c_ldi_nonzero = {
            **c_ldi,
            "pc": 0x400055AA,
            "next_pc": 0x400055AC,
            "wb_data": 0x1234_5678,
            "dst_data": 0x1234_5678,
            "mem_rdata": 0x1234_5678,
        }
        _write_jsonl(c_ldi_nonzero_source, [c_ldi_nonzero])
        count = extract_rows(c_ldi_nonzero_source, c_ldi_nonzero_output)
        assert count == 1
        extracted_c_ldi_nonzero = [
            json.loads(line) for line in c_ldi_nonzero_output.read_text(encoding="utf-8").splitlines()
        ]
        assert extracted_c_ldi_nonzero[0]["dst_data"] == 0x1234_5678
        assert extracted_c_ldi_nonzero[0]["mem_rdata"] == 0x1234_5678

        sll_after_c_ldi = {
            **rows[0],
            "pc": 0x40005538,
            "insn": 0x01EC7F85,
            "len": 4,
            "next_pc": 0x4000553C,
            "wb_valid": 1,
            "wb_rd": 31,
            "wb_data": 0,
            "dst_valid": 1,
            "dst_reg": 31,
            "dst_data": 0,
            "src0_valid": 0,
            "src0_reg": 0,
            "src0_data": 0,
            "src1_valid": 0,
            "src1_reg": 0,
            "src1_data": 0,
        }
        c_add_source = tmp / "c-add-tu.jsonl"
        c_add_output = tmp / "c-add-tu.rows.jsonl"
        c_add_tu = {
            **rows[0],
            "pc": 0x4000553C,
            "insn": 0xE608,
            "len": 2,
            "next_pc": 0x4000553E,
            "wb_valid": 0,
            "wb_rd": 0,
            "wb_data": 0,
            "dst_valid": 0,
            "dst_reg": 0,
            "dst_data": 0,
            "src0_valid": 0,
            "src0_reg": 0,
            "src0_data": 0,
            "src1_valid": 0,
            "src1_reg": 0,
            "src1_data": 0,
        }
        _write_jsonl(
            c_add_source,
            [
                addi_u_dst,
                hl_lui,
                sll_tu,
                hl_lui_zero,
                sll_t_dst,
                srl_tu,
                or_tu,
                c_ldi,
                sll_after_c_ldi,
                c_add_tu,
            ],
        )
        count = extract_rows(c_add_source, c_add_output)
        assert count == 10
        extracted_c_add = [json.loads(line) for line in c_add_output.read_text(encoding="utf-8").splitlines()]
        assert extracted_c_add[9]["dst_reg"] == 31
        assert extracted_c_add[9]["wb_rd"] == 31
        assert extracted_c_add[9]["dst_data"] == 0x1_0000_0000

        sra_source = tmp / "sra-tu.jsonl"
        sra_output = tmp / "sra-tu.rows.jsonl"
        sra_tu = {
            **rows[0],
            "pc": 0x4000553E,
            "insn": 0x01EC6F85,
            "len": 4,
            "next_pc": 0x40005542,
            "wb_valid": 1,
            "wb_rd": 31,
            "wb_data": 1,
            "dst_valid": 1,
            "dst_reg": 31,
            "dst_data": 1,
            "src0_valid": 0,
            "src0_reg": 0,
            "src0_data": 0,
            "src1_valid": 0,
            "src1_reg": 0,
            "src1_data": 0,
        }
        _write_jsonl(
            sra_source,
            [
                addi_u_dst,
                hl_lui,
                sll_tu,
                hl_lui_zero,
                sll_t_dst,
                srl_tu,
                or_tu,
                c_ldi,
                sll_after_c_ldi,
                c_add_tu,
                sra_tu,
            ],
        )
        count = extract_rows(sra_source, sra_output)
        assert count == 11
        extracted_sra = [json.loads(line) for line in sra_output.read_text(encoding="utf-8").splitlines()]
        assert extracted_sra[10]["dst_reg"] == 31
        assert extracted_sra[10]["wb_rd"] == 31
        assert extracted_sra[10]["dst_data"] == 1

        slli_source = tmp / "slli-tu.jsonl"
        slli_output = tmp / "slli-tu.rows.jsonl"
        slli_tu = {
            **rows[0],
            "pc": 0x40005542,
            "insn": 0x003C7F95,
            "len": 4,
            "next_pc": 0x40005546,
            "wb_valid": 1,
            "wb_rd": 31,
            "wb_data": 8,
            "dst_valid": 1,
            "dst_reg": 31,
            "dst_data": 8,
            "src0_valid": 0,
            "src0_reg": 0,
            "src0_data": 0,
            "src1_valid": 0,
            "src1_reg": 0,
            "src1_data": 0,
        }
        _write_jsonl(
            slli_source,
            [
                addi_u_dst,
                hl_lui,
                sll_tu,
                hl_lui_zero,
                sll_t_dst,
                srl_tu,
                or_tu,
                c_ldi,
                sll_after_c_ldi,
                c_add_tu,
                sra_tu,
                slli_tu,
            ],
        )
        count = extract_rows(slli_source, slli_output)
        assert count == 12
        extracted_slli = [json.loads(line) for line in slli_output.read_text(encoding="utf-8").splitlines()]
        assert extracted_slli[11]["dst_reg"] == 31
        assert extracted_slli[11]["wb_rd"] == 31
        assert extracted_slli[11]["dst_data"] == 8

        c_add_mixed_source = tmp / "c-add-mixed.jsonl"
        c_add_mixed_output = tmp / "c-add-mixed.rows.jsonl"
        c_add_mixed = {
            **rows[0],
            "pc": 0x40005546,
            "insn": 0x2608,
            "len": 2,
            "next_pc": 0x40005548,
            "wb_valid": 0,
            "wb_rd": 0,
            "wb_data": 0,
            "dst_valid": 0,
            "dst_reg": 0,
            "dst_data": 0,
            "src0_valid": 0,
            "src0_reg": 0,
            "src0_data": 0,
            "src1_valid": 1,
            "src1_reg": 4,
            "src1_data": 0x4FFE_FDB0,
        }
        _write_jsonl(
            c_add_mixed_source,
            [
                addi_u_dst,
                hl_lui,
                sll_tu,
                hl_lui_zero,
                sll_t_dst,
                srl_tu,
                or_tu,
                c_ldi,
                sll_after_c_ldi,
                c_add_tu,
                sra_tu,
                slli_tu,
                c_add_mixed,
            ],
        )
        count = extract_rows(c_add_mixed_source, c_add_mixed_output)
        assert count == 13
        extracted_c_add_mixed = [
            json.loads(line) for line in c_add_mixed_output.read_text(encoding="utf-8").splitlines()
        ]
        assert extracted_c_add_mixed[12]["src1_valid"] == 1
        assert extracted_c_add_mixed[12]["src1_reg"] == 4
        assert extracted_c_add_mixed[12]["dst_reg"] == 31
        assert extracted_c_add_mixed[12]["wb_rd"] == 31
        assert extracted_c_add_mixed[12]["dst_data"] == 0x4FFE_FDB8

        r116_packet_source = tmp / "r116-packet.jsonl"
        r116_packet_output = tmp / "r116-packet.rows.jsonl"
        addi_local_src = {
            **rows[0],
            "pc": 0x40005548,
            "insn": 0x018C0115,
            "len": 4,
            "next_pc": 0x4000554C,
            "wb_valid": 1,
            "wb_rd": 2,
            "wb_data": 0x4FFE_FDD0,
            "dst_valid": 1,
            "dst_reg": 2,
            "dst_data": 0x4FFE_FDD0,
            "src0_valid": 0,
            "src0_reg": 0,
            "src0_data": 0,
            "src1_valid": 0,
            "src1_reg": 0,
            "src1_data": 0,
        }
        c_movr_x0 = {
            **rows[0],
            "pc": 0x4000554C,
            "insn": 0x2806,
            "len": 2,
            "next_pc": 0x4000554E,
            "wb_valid": 1,
            "wb_rd": 5,
            "wb_data": 0,
            "dst_valid": 1,
            "dst_reg": 5,
            "dst_data": 0,
            "src0_valid": 1,
            "src0_reg": 0,
            "src0_data": 0,
            "src1_valid": 0,
            "src1_reg": 0,
            "src1_data": 0,
        }
        _write_jsonl(
            r116_packet_source,
            [
                addi_u_dst,
                hl_lui,
                sll_tu,
                hl_lui_zero,
                sll_t_dst,
                srl_tu,
                or_tu,
                c_ldi,
                sll_after_c_ldi,
                c_add_tu,
                sra_tu,
                slli_tu,
                c_add_mixed,
                addi_local_src,
                c_movr_x0,
            ],
        )
        count = extract_rows(r116_packet_source, r116_packet_output)
        assert count == 15
        extracted_r116_packet = [
            json.loads(line) for line in r116_packet_output.read_text(encoding="utf-8").splitlines()
        ]
        assert extracted_r116_packet[13]["src0_valid"] == 0
        assert extracted_r116_packet[13]["dst_reg"] == 2
        assert extracted_r116_packet[13]["dst_data"] == 0x4FFE_FDD0
        assert extracted_r116_packet[14]["pc"] == 0x4000554C
        assert extracted_r116_packet[14]["dst_reg"] == 5
        assert extracted_r116_packet[14]["dst_data"] == 0

        r117_packet_source = tmp / "r117-packet.jsonl"
        r117_packet_output = tmp / "r117-packet.rows.jsonl"
        c_movr_t = {
            **rows[0],
            "pc": 0x40005550,
            "insn": 0xF886,
            "len": 2,
            "next_pc": 0x40005552,
            "wb_valid": 1,
            "wb_rd": 31,
            "wb_data": 0x4FFF_0010,
            "dst_valid": 1,
            "dst_reg": 31,
            "dst_data": 0x4FFF_0010,
            "src0_valid": 1,
            "src0_reg": 2,
            "src0_data": 0x4FFF_0010,
            "src1_valid": 0,
            "src1_reg": 0,
            "src1_data": 0,
        }
        addi_local_after_movr = {
            **rows[0],
            "pc": 0x40005552,
            "insn": 0x008C0115,
            "len": 4,
            "next_pc": 0x40005556,
            "wb_valid": 1,
            "wb_rd": 2,
            "wb_data": 0x4FFF_0018,
            "dst_valid": 1,
            "dst_reg": 2,
            "dst_data": 0x4FFF_0018,
            "src0_valid": 0,
            "src0_reg": 0,
            "src0_data": 0,
            "src1_valid": 0,
            "src1_reg": 0,
            "src1_data": 0,
        }
        c_ldi_local = {
            **rows[0],
            "pc": 0x40005556,
            "insn": 0xF61A,
            "len": 2,
            "next_pc": 0x40005558,
            "wb_valid": 1,
            "wb_rd": 31,
            "wb_data": 0,
            "dst_valid": 1,
            "dst_reg": 31,
            "dst_data": 0,
            "src0_valid": 0,
            "src0_reg": 0,
            "src0_data": 0,
            "src1_valid": 0,
            "src1_reg": 0,
            "src1_data": 0,
            "mem_valid": 1,
            "mem_is_store": 0,
            "mem_addr": 0x4FFF_0000,
            "mem_wdata": 0,
            "mem_rdata": 0,
            "mem_size": 8,
        }
        c_setc_ne = {
            **rows[0],
            "pc": 0x40005558,
            "insn": 0x2E36,
            "len": 2,
            "next_pc": 0x4000555A,
            "wb_valid": 0,
            "wb_rd": 0,
            "wb_data": 0,
            "dst_valid": 0,
            "dst_reg": 0,
            "dst_data": 0,
            "src0_valid": 0,
            "src0_reg": 0,
            "src0_data": 0,
            "src1_valid": 1,
            "src1_reg": 5,
            "src1_data": 0,
        }
        _write_jsonl(r117_packet_source, [c_movr_t, addi_local_after_movr, c_ldi_local, c_setc_ne])
        count = extract_rows(r117_packet_source, r117_packet_output)
        assert count == 4
        extracted_r117_packet = [
            json.loads(line) for line in r117_packet_output.read_text(encoding="utf-8").splitlines()
        ]
        assert extracted_r117_packet[0]["dst_reg"] == 31
        assert extracted_r117_packet[0]["dst_data"] == 0x4FFF_0010
        assert extracted_r117_packet[1]["src0_valid"] == 0
        assert extracted_r117_packet[1]["dst_data"] == 0x4FFF_0018
        assert extracted_r117_packet[2]["mem_addr"] == 0x4FFF_0000
        assert extracted_r117_packet[2]["dst_reg"] == 31
        assert extracted_r117_packet[2]["dst_data"] == 0
        assert extracted_r117_packet[3]["pc"] == 0x40005558
        assert extracted_r117_packet[3]["src1_valid"] == 1
        assert extracted_r117_packet[3]["src1_reg"] == 5
        assert extracted_r117_packet[3]["dst_valid"] == 0

        r118_packet_source = tmp / "r118-sdi.jsonl"
        r118_packet_output = tmp / "r118-sdi.rows.jsonl"
        c_movr_t_for_sdi = {
            **c_movr_t,
            "pc": 0x40005560,
            "next_pc": 0x40005562,
            "wb_data": 0x4FFF_0128,
            "dst_data": 0x4FFF_0128,
            "src0_data": 0x4FFF_0128,
        }
        sdi_local_base = {
            **rows[0],
            "pc": 0x40005562,
            "insn": 0x0182B059,
            "len": 4,
            "next_pc": 0x40005566,
            "wb_valid": 0,
            "wb_rd": 0,
            "wb_data": 0,
            "dst_valid": 0,
            "dst_reg": 0,
            "dst_data": 0,
            "src0_valid": 1,
            "src0_reg": 5,
            "src0_data": 0,
            "src1_valid": 0,
            "src1_reg": 0,
            "src1_data": 0,
            "mem_valid": 1,
            "mem_is_store": 1,
            "mem_addr": 0x4FFF_0128,
            "mem_wdata": 0,
            "mem_rdata": 0,
            "mem_size": 8,
        }
        _write_jsonl(r118_packet_source, [c_movr_t_for_sdi, sdi_local_base])
        count = extract_rows(r118_packet_source, r118_packet_output)
        assert count == 2
        extracted_r118_packet = [
            json.loads(line) for line in r118_packet_output.read_text(encoding="utf-8").splitlines()
        ]
        assert extracted_r118_packet[1]["pc"] == 0x40005562
        assert extracted_r118_packet[1]["src0_valid"] == 1
        assert extracted_r118_packet[1]["src0_reg"] == 5
        assert extracted_r118_packet[1]["src1_valid"] == 0
        assert extracted_r118_packet[1]["dst_valid"] == 0
        assert extracted_r118_packet[1]["mem_valid"] == 1
        assert extracted_r118_packet[1]["mem_is_store"] == 1
        assert extracted_r118_packet[1]["mem_addr"] == 0x4FFF_0128
        assert extracted_r118_packet[1]["mem_wdata"] == 0

        r124_sd_source = tmp / "r124-op-sd.jsonl"
        r124_sd_output = tmp / "r124-op-sd.rows.jsonl"
        addi_u_for_sd = {
            **addi_u_dst,
            "pc": 0x5000,
            "next_pc": 0x5004,
        }
        addi_t_for_sd = {
            **addi_t_dst,
            "pc": 0x5004,
            "next_pc": 0x5008,
        }
        indexed_sd = {
            **rows[0],
            "pc": 0x5008,
            "insn": 0xE62C3049,
            "len": 4,
            "next_pc": 0x500C,
            "wb_valid": 0,
            "wb_rd": 0,
            "wb_data": 0,
            "dst_valid": 0,
            "dst_reg": 0,
            "dst_data": 0,
            "src0_valid": 0,
            "src0_reg": 0,
            "src0_data": 0,
            "src1_valid": 1,
            "src1_reg": 2,
            "src1_data": 21,
            "mem_valid": 1,
            "mem_is_store": 1,
            "mem_addr": 0x4000_ED90,
            "mem_wdata": 32,
            "mem_rdata": 0,
            "mem_size": 8,
        }
        _write_jsonl(r124_sd_source, [addi_u_for_sd, addi_t_for_sd, indexed_sd])
        count = extract_rows(r124_sd_source, r124_sd_output)
        assert count == 3
        extracted_r124_sd = [
            json.loads(line) for line in r124_sd_output.read_text(encoding="utf-8").splitlines()
        ]
        assert extracted_r124_sd[2]["pc"] == 0x5008
        assert extracted_r124_sd[2]["src0_valid"] == 0
        assert extracted_r124_sd[2]["src1_valid"] == 1
        assert extracted_r124_sd[2]["src1_reg"] == 2
        assert extracted_r124_sd[2]["dst_valid"] == 0
        assert extracted_r124_sd[2]["mem_valid"] == 1
        assert extracted_r124_sd[2]["mem_is_store"] == 1
        assert extracted_r124_sd[2]["mem_addr"] == 0x4000_ED90
        assert extracted_r124_sd[2]["mem_wdata"] == 32

        r119_branch_source = tmp / "r119-cond-bstart.jsonl"
        r119_branch_output = tmp / "r119-cond-bstart.rows.jsonl"
        setc_ne_loop = {
            **rows[0],
            "pc": 0x40005572,
            "insn": 0x3A36,
            "len": 2,
            "next_pc": 0x40005574,
            "wb_valid": 0,
            "wb_rd": 0,
            "wb_data": 0,
            "dst_valid": 0,
            "dst_reg": 0,
            "dst_data": 0,
            "src0_valid": 1,
            "src0_reg": 8,
            "src0_data": 8,
            "src1_valid": 1,
            "src1_reg": 7,
            "src1_data": 256,
            "mem_valid": 0,
            "mem_is_store": 0,
            "mem_addr": 0,
            "mem_wdata": 0,
            "mem_rdata": 0,
            "mem_size": 0,
        }
        redirecting_bstart = {
            **rows[0],
            "pc": 0x40005574,
            "insn": 0x0194,
            "len": 2,
            "next_pc": 0x40005566,
            "wb_valid": 0,
            "wb_rd": 0,
            "wb_data": 0,
            "dst_valid": 0,
            "dst_reg": 0,
            "dst_data": 0,
            "src0_valid": 0,
            "src0_reg": 0,
            "src0_data": 0,
            "src1_valid": 0,
            "src1_reg": 0,
            "src1_data": 0,
            "mem_valid": 0,
            "mem_is_store": 0,
            "mem_addr": 0,
            "mem_wdata": 0,
            "mem_rdata": 0,
            "mem_size": 0,
        }
        _write_jsonl(r119_branch_source, [setc_ne_loop, redirecting_bstart])
        count = extract_rows(r119_branch_source, r119_branch_output, allow_block_markers=True)
        assert count == 2
        extracted_r119_packet = [
            json.loads(line) for line in r119_branch_output.read_text(encoding="utf-8").splitlines()
        ]
        assert sum(1 for row in extracted_r119_packet if "skip" not in row) == 1
        assert extracted_r119_packet[0]["pc"] == 0x40005572
        assert extracted_r119_packet[0]["dst_valid"] == 0
        assert extracted_r119_packet[0]["src0_reg"] == 8
        assert extracted_r119_packet[0]["src1_reg"] == 7
        assert extracted_r119_packet[1]["skip"] == 1
        assert extracted_r119_packet[1]["block_boundary"] == 1
        assert extracted_r119_packet[1]["block_stop"] == 0
        assert extracted_r119_packet[1]["next_pc"] == 0x40005566

        r121_ldi_source = tmp / "r121-ldi.jsonl"
        r121_ldi_output = tmp / "r121-ldi.rows.jsonl"
        ldi_zero = {
            **rows[0],
            "pc": 0x40005576,
            "insn": 0xFFE13319,
            "len": 4,
            "next_pc": 0x4000557A,
            "wb_valid": 1,
            "wb_rd": 6,
            "wb_data": 0,
            "dst_valid": 1,
            "dst_reg": 6,
            "dst_data": 0,
            "src0_valid": 1,
            "src0_reg": 2,
            "src0_data": 0x4FFF_0048,
            "src1_valid": 0,
            "src1_reg": 0,
            "src1_data": 0,
            "mem_valid": 1,
            "mem_is_store": 0,
            "mem_addr": 0x4FFF_0038,
            "mem_wdata": 0,
            "mem_rdata": 0,
            "mem_size": 8,
        }
        _write_jsonl(r121_ldi_source, [ldi_zero])
        count = extract_rows(r121_ldi_source, r121_ldi_output)
        assert count == 1
        extracted_r121_ldi = [
            json.loads(line) for line in r121_ldi_output.read_text(encoding="utf-8").splitlines()
        ]
        assert extracted_r121_ldi[0]["pc"] == 0x40005576
        assert extracted_r121_ldi[0]["src0_reg"] == 2
        assert extracted_r121_ldi[0]["dst_reg"] == 6
        assert extracted_r121_ldi[0]["mem_addr"] == 0x4FFF_0038
        assert extracted_r121_ldi[0]["mem_rdata"] == 0

        r122_ldi_source = tmp / "r122-ldi-nonzero.jsonl"
        r122_ldi_output = tmp / "r122-ldi-nonzero.rows.jsonl"
        ldi_nonzero = {
            **rows[0],
            "pc": 0x400055C2,
            "insn": 0x0001B119,
            "len": 4,
            "next_pc": 0x400055C6,
            "wb_valid": 1,
            "wb_rd": 2,
            "wb_data": 0x6FFF_FFFB,
            "dst_valid": 1,
            "dst_reg": 2,
            "dst_data": 0x6FFF_FFFB,
            "src0_valid": 1,
            "src0_reg": 3,
            "src0_data": 0x4000_ECC8,
            "src1_valid": 0,
            "src1_reg": 0,
            "src1_data": 0,
            "mem_valid": 1,
            "mem_is_store": 0,
            "mem_addr": 0x4000_ECC8,
            "mem_wdata": 0,
            "mem_rdata": 0x6FFF_FFFB,
            "mem_size": 8,
        }
        _write_jsonl(r122_ldi_source, [ldi_nonzero])
        count = extract_rows(r122_ldi_source, r122_ldi_output)
        assert count == 1
        extracted_r122_ldi = [
            json.loads(line) for line in r122_ldi_output.read_text(encoding="utf-8").splitlines()
        ]
        assert extracted_r122_ldi[0]["pc"] == 0x400055C2
        assert extracted_r122_ldi[0]["dst_data"] == 0x6FFF_FFFB
        assert extracted_r122_ldi[0]["mem_addr"] == 0x4000_ECC8
        assert extracted_r122_ldi[0]["mem_rdata"] == 0x6FFF_FFFB

        r122_ldi_u_source = tmp / "r122-ldi-u-dst.jsonl"
        r122_ldi_u_output = tmp / "r122-ldi-u-dst.rows.jsonl"
        ldi_u_dst = {
            **rows[0],
            "pc": 0x400055EA,
            "insn": 0xFFF3BF19,
            "len": 4,
            "next_pc": 0x400055EE,
            "wb_valid": 1,
            "wb_rd": 30,
            "wb_data": 0x40000238,
            "dst_valid": 1,
            "dst_reg": 30,
            "dst_data": 0x40000238,
            "src0_valid": 1,
            "src0_reg": 7,
            "src0_data": 0x4000_ECF8,
            "src1_valid": 0,
            "src1_reg": 0,
            "src1_data": 0,
            "mem_valid": 1,
            "mem_is_store": 0,
            "mem_addr": 0x4000_ECF0,
            "mem_wdata": 0,
            "mem_rdata": 0x40000238,
            "mem_size": 8,
        }
        _write_jsonl(r122_ldi_u_source, [ldi_u_dst])
        count = extract_rows(r122_ldi_u_source, r122_ldi_u_output)
        assert count == 1
        extracted_r122_ldi_u = [
            json.loads(line) for line in r122_ldi_u_output.read_text(encoding="utf-8").splitlines()
        ]
        assert extracted_r122_ldi_u[0]["dst_reg"] == 30
        assert extracted_r122_ldi_u[0]["dst_data"] == 0x40000238
        assert extracted_r122_ldi_u[0]["mem_addr"] == 0x4000_ECF0

        r122_setc_ltu_source = tmp / "r122-setc-ltu.jsonl"
        r122_setc_ltu_output = tmp / "r122-setc-ltu.rows.jsonl"
        setc_ltu = {
            **rows[0],
            "pc": 0x400055E4,
            "insn": 0x06236065,
            "len": 4,
            "next_pc": 0x400055E8,
            "wb_valid": 0,
            "wb_rd": 0,
            "wb_data": 0,
            "dst_valid": 0,
            "dst_reg": 0,
            "dst_data": 0,
            "src0_valid": 1,
            "src0_reg": 6,
            "src0_data": 36,
            "src1_valid": 1,
            "src1_reg": 2,
            "src1_data": 0x6FFF_FFFB,
            "mem_valid": 0,
            "mem_is_store": 0,
            "mem_addr": 0,
            "mem_wdata": 0,
            "mem_rdata": 0,
            "mem_size": 0,
        }
        _write_jsonl(r122_setc_ltu_source, [setc_ltu])
        count = extract_rows(r122_setc_ltu_source, r122_setc_ltu_output)
        assert count == 1
        extracted_r122_setc_ltu = [
            json.loads(line) for line in r122_setc_ltu_output.read_text(encoding="utf-8").splitlines()
        ]
        assert extracted_r122_setc_ltu[0]["pc"] == 0x400055E4
        assert extracted_r122_setc_ltu[0]["src0_reg"] == 6
        assert extracted_r122_setc_ltu[0]["src1_reg"] == 2
        assert extracted_r122_setc_ltu[0]["dst_valid"] == 0

        r126_c_setc_tgt_source = tmp / "r126-c-setc-tgt.jsonl"
        r126_c_setc_tgt_output = tmp / "r126-c-setc-tgt.rows.jsonl"
        c_setc_tgt = {
            **rows[0],
            "pc": 0x4000_5706,
            "insn": 0x015C,
            "len": 2,
            "next_pc": 0x4000_5708,
            "wb_valid": 0,
            "wb_rd": 0,
            "wb_data": 0,
            "dst_valid": 0,
            "dst_reg": 0,
            "dst_data": 0,
            "src0_valid": 1,
            "src0_reg": 5,
            "src0_data": 0x4000_574C,
            "src1_valid": 0,
            "src1_reg": 0,
            "src1_data": 0,
            "mem_valid": 0,
            "mem_is_store": 0,
            "mem_addr": 0,
            "mem_wdata": 0,
            "mem_rdata": 0,
            "mem_size": 0,
        }
        _write_jsonl(r126_c_setc_tgt_source, [c_setc_tgt])
        count = extract_rows(r126_c_setc_tgt_source, r126_c_setc_tgt_output)
        assert count == 1
        extracted_r126_c_setc_tgt = [
            json.loads(line) for line in r126_c_setc_tgt_output.read_text(encoding="utf-8").splitlines()
        ]
        assert extracted_r126_c_setc_tgt[0]["pc"] == 0x4000_5706
        assert extracted_r126_c_setc_tgt[0]["src0_reg"] == 5
        assert extracted_r126_c_setc_tgt[0]["dst_valid"] == 0

        r126_setc_tgt_source = tmp / "r126-setc-tgt.jsonl"
        r126_setc_tgt_output = tmp / "r126-setc-tgt.rows.jsonl"
        setc_tgt = {
            **rows[0],
            "pc": 0x4000_2000,
            "insn": 0x0002_C03B,
            "len": 4,
            "next_pc": 0x4000_2004,
            "wb_valid": 0,
            "wb_rd": 0,
            "wb_data": 0,
            "dst_valid": 0,
            "dst_reg": 0,
            "dst_data": 0,
            "src0_valid": 1,
            "src0_reg": 5,
            "src0_data": 0x4000_3000,
            "src1_valid": 0,
            "src1_reg": 0,
            "src1_data": 0,
            "mem_valid": 0,
            "mem_is_store": 0,
            "mem_addr": 0,
            "mem_wdata": 0,
            "mem_rdata": 0,
            "mem_size": 0,
        }
        _write_jsonl(r126_setc_tgt_source, [setc_tgt])
        count = extract_rows(r126_setc_tgt_source, r126_setc_tgt_output)
        assert count == 1
        extracted_r126_setc_tgt = [
            json.loads(line) for line in r126_setc_tgt_output.read_text(encoding="utf-8").splitlines()
        ]
        assert extracted_r126_setc_tgt[0]["pc"] == 0x4000_2000
        assert extracted_r126_setc_tgt[0]["src0_reg"] == 5
        assert extracted_r126_setc_tgt[0]["dst_valid"] == 0

        r126_fret_stk_source = tmp / "r126-fret-stk.jsonl"
        r126_fret_stk_output = tmp / "r126-fret-stk.rows.jsonl"
        fret_stk = {
            **rows[0],
            "pc": 0x4000_570A,
            "insn": 0x90A5_3041,
            "len": 4,
            "next_pc": 0x4000_574C,
            "wb_valid": 0,
            "wb_rd": 0,
            "wb_data": 0,
            "dst_valid": 0,
            "dst_reg": 0,
            "dst_data": 0,
            "src0_valid": 0,
            "src0_reg": 0,
            "src0_data": 0,
            "src1_valid": 0,
            "src1_reg": 0,
            "src1_data": 0,
            "mem_valid": 0,
            "mem_is_store": 0,
            "mem_addr": 0,
            "mem_wdata": 0,
            "mem_rdata": 0,
            "mem_size": 0,
        }
        _write_jsonl(r126_fret_stk_source, [fret_stk])
        count = extract_rows(r126_fret_stk_source, r126_fret_stk_output)
        assert count == 1
        extracted_r126_fret_stk = [
            json.loads(line) for line in r126_fret_stk_output.read_text(encoding="utf-8").splitlines()
        ]
        assert extracted_r126_fret_stk[0]["pc"] == 0x4000_570A
        assert extracted_r126_fret_stk[0]["next_pc"] == 0x4000_574C
        assert extracted_r126_fret_stk[0]["dst_valid"] == 0

        r132_fret_stk_source = tmp / "r132-fret-stk-load.jsonl"
        r132_fret_stk_output = tmp / "r132-fret-stk-load.rows.jsonl"
        fret_stk_template_prefix = {
            **fret_stk,
            "pc": 0x4000_D2D4,
            "insn": 0x02A5_3041,
            "next_pc": 0x4000_D2D4,
        }
        fret_stk_ra_load = {
            **fret_stk_template_prefix,
            "next_pc": 0x4000_5CB0,
            "wb_valid": 1,
            "wb_rd": 10,
            "wb_data": 0x4000_5CB0,
            "dst_valid": 1,
            "dst_reg": 10,
            "dst_data": 0x4000_5CB0,
            "mem_valid": 1,
            "mem_is_store": 0,
            "mem_addr": 0x4FFE_FBB8,
            "mem_wdata": 0,
            "mem_rdata": 0x4000_5CB0,
            "mem_size": 8,
        }
        _write_jsonl(r132_fret_stk_source, [fret_stk_template_prefix, fret_stk_ra_load])
        count = extract_rows(r132_fret_stk_source, r132_fret_stk_output)
        assert count == 1
        extracted_r132_fret_stk = [
            json.loads(line) for line in r132_fret_stk_output.read_text(encoding="utf-8").splitlines()
        ]
        assert extracted_r132_fret_stk[0]["pc"] == 0x4000_D2D4
        assert extracted_r132_fret_stk[0]["next_pc"] == 0x4000_5CB0
        assert extracted_r132_fret_stk[0]["dst_valid"] == 1
        assert extracted_r132_fret_stk[0]["dst_reg"] == 10
        assert extracted_r132_fret_stk[0]["mem_valid"] == 1

        r126_addw_slli_source = tmp / "r126-addw-slli.jsonl"
        r126_addw_slli_output = tmp / "r126-addw-slli.rows.jsonl"
        addw_row = {
            **rows[0],
            "pc": 0x4000_5F3E,
            "insn": 0x0606_0125,
            "len": 4,
            "next_pc": 0x4000_5F42,
            "wb_valid": 1,
            "wb_rd": 2,
            "wb_data": 0,
            "dst_valid": 1,
            "dst_reg": 2,
            "dst_data": 0,
            "src0_valid": 1,
            "src0_reg": 12,
            "src0_data": 0,
            "src1_valid": 1,
            "src1_reg": 0,
            "src1_data": 0,
            "mem_valid": 0,
            "mem_is_store": 0,
            "mem_addr": 0,
            "mem_wdata": 0,
            "mem_rdata": 0,
            "mem_size": 0,
        }
        slli_scalar_row = {
            **rows[0],
            "pc": 0x4000_5F42,
            "insn": 0x0031_7115,
            "len": 4,
            "next_pc": 0x4000_5F46,
            "wb_valid": 1,
            "wb_rd": 2,
            "wb_data": 0,
            "dst_valid": 1,
            "dst_reg": 2,
            "dst_data": 0,
            "src0_valid": 1,
            "src0_reg": 2,
            "src0_data": 0,
            "src1_valid": 0,
            "src1_reg": 0,
            "src1_data": 0,
            "mem_valid": 0,
            "mem_is_store": 0,
            "mem_addr": 0,
            "mem_wdata": 0,
            "mem_rdata": 0,
            "mem_size": 0,
        }
        _write_jsonl(r126_addw_slli_source, [addw_row, slli_scalar_row])
        count = extract_rows(r126_addw_slli_source, r126_addw_slli_output)
        assert count == 2
        extracted_r126_addw_slli = [
            json.loads(line) for line in r126_addw_slli_output.read_text(encoding="utf-8").splitlines()
        ]
        assert extracted_r126_addw_slli[0]["pc"] == 0x4000_5F3E
        assert extracted_r126_addw_slli[0]["dst_reg"] == 2
        assert extracted_r126_addw_slli[1]["pc"] == 0x4000_5F42
        assert extracted_r126_addw_slli[1]["src0_reg"] == 2

        r126_sbi_source = tmp / "r126-sbi.jsonl"
        r126_sbi_output = tmp / "r126-sbi.rows.jsonl"
        sbi_zero = {
            **rows[0],
            "pc": 0x4000_D1D8,
            "insn": 0x0021_8059,
            "len": 4,
            "next_pc": 0x4000_D1DC,
            "wb_valid": 0,
            "wb_rd": 0,
            "wb_data": 0,
            "dst_valid": 0,
            "dst_reg": 0,
            "dst_data": 0,
            "src0_valid": 1,
            "src0_reg": 3,
            "src0_data": 0,
            "src1_valid": 1,
            "src1_reg": 2,
            "src1_data": 0x4FFE_FBF8,
            "mem_valid": 1,
            "mem_is_store": 1,
            "mem_addr": 0x4FFE_FBF8,
            "mem_wdata": 0,
            "mem_rdata": 0,
            "mem_size": 1,
        }
        add_for_sbi_negative = {
            **rows[0],
            "pc": 0x4000_D1DC,
            "insn": 0x0641_0305,
            "len": 4,
            "next_pc": 0x4000_D1E0,
            "wb_valid": 1,
            "wb_rd": 6,
            "wb_data": 0x4FFE_FD28,
            "dst_valid": 1,
            "dst_reg": 6,
            "dst_data": 0x4FFE_FD28,
            "src0_valid": 1,
            "src0_reg": 2,
            "src0_data": 0x4FFE_FBF8,
            "src1_valid": 1,
            "src1_reg": 4,
            "src1_data": 304,
            "mem_valid": 0,
            "mem_is_store": 0,
            "mem_addr": 0,
            "mem_wdata": 0,
            "mem_rdata": 0,
            "mem_size": 0,
        }
        sbi_negative = {
            **rows[0],
            "pc": 0x4000_D1E0,
            "insn": 0xFE61_8FD9,
            "len": 4,
            "next_pc": 0x4000_D1E4,
            "wb_valid": 0,
            "wb_rd": 0,
            "wb_data": 0,
            "dst_valid": 0,
            "dst_reg": 0,
            "dst_data": 0,
            "src0_valid": 1,
            "src0_reg": 3,
            "src0_data": 0,
            "src1_valid": 1,
            "src1_reg": 6,
            "src1_data": 0x4FFE_FD28,
            "mem_valid": 1,
            "mem_is_store": 1,
            "mem_addr": 0x4FFE_FD27,
            "mem_wdata": 0,
            "mem_rdata": 0,
            "mem_size": 1,
        }
        _write_jsonl(r126_sbi_source, [sbi_zero, add_for_sbi_negative, sbi_negative])
        count = extract_rows(r126_sbi_source, r126_sbi_output)
        assert count == 3
        extracted_r126_sbi = [
            json.loads(line) for line in r126_sbi_output.read_text(encoding="utf-8").splitlines()
        ]
        assert extracted_r126_sbi[0]["mem_size"] == 1
        assert extracted_r126_sbi[0]["mem_addr"] == 0x4FFE_FBF8
        assert extracted_r126_sbi[2]["mem_addr"] == 0x4FFE_FD27

        r126_ld_pcr_source = tmp / "r126-ld-pcr.jsonl"
        r126_ld_pcr_output = tmp / "r126-ld-pcr.rows.jsonl"
        ld_pcr = {
            **rows[0],
            "pc": 0x4000_1000,
            "insn": 0x0123_32B9,
            "len": 4,
            "next_pc": 0x4000_1004,
            "wb_valid": 1,
            "wb_rd": 5,
            "wb_data": 0x1234_5678,
            "dst_valid": 1,
            "dst_reg": 5,
            "dst_data": 0x1234_5678,
            "src0_valid": 0,
            "src0_reg": 0,
            "src0_data": 0,
            "src1_valid": 0,
            "src1_reg": 0,
            "src1_data": 0,
            "mem_valid": 1,
            "mem_is_store": 0,
            "mem_addr": 0x4000_1246,
            "mem_wdata": 0,
            "mem_rdata": 0x1234_5678,
            "mem_size": 8,
        }
        _write_jsonl(r126_ld_pcr_source, [ld_pcr])
        count = extract_rows(r126_ld_pcr_source, r126_ld_pcr_output)
        assert count == 1
        extracted_r126_ld_pcr = [
            json.loads(line) for line in r126_ld_pcr_output.read_text(encoding="utf-8").splitlines()
        ]
        assert extracted_r126_ld_pcr[0]["pc"] == 0x4000_1000
        assert extracted_r126_ld_pcr[0]["dst_reg"] == 5
        assert extracted_r126_ld_pcr[0]["mem_addr"] == 0x4000_1246
        assert extracted_r126_ld_pcr[0]["mem_rdata"] == 0x1234_5678

        r126_hl_ld_pcr_source = tmp / "r126-hl-ld-pcr.jsonl"
        r126_hl_ld_pcr_output = tmp / "r126-hl-ld-pcr.rows.jsonl"
        hl_ld_pcr = {
            **rows[0],
            "pc": 0x4000_5700,
            "insn": 0x5394_32B9_000E,
            "len": 6,
            "next_pc": 0x4000_5706,
            "wb_valid": 1,
            "wb_rd": 5,
            "wb_data": 0x4000_574C,
            "dst_valid": 1,
            "dst_reg": 5,
            "dst_data": 0x4000_574C,
            "src0_valid": 0,
            "src0_reg": 0,
            "src0_data": 0,
            "src1_valid": 0,
            "src1_reg": 0,
            "src1_data": 0,
            "mem_valid": 1,
            "mem_is_store": 0,
            "mem_addr": 0x4000_FE28,
            "mem_wdata": 0,
            "mem_rdata": 0x4000_574C,
            "mem_size": 8,
        }
        _write_jsonl(r126_hl_ld_pcr_source, [hl_ld_pcr])
        count = extract_rows(r126_hl_ld_pcr_source, r126_hl_ld_pcr_output)
        assert count == 1
        extracted_r126_hl_ld_pcr = [
            json.loads(line) for line in r126_hl_ld_pcr_output.read_text(encoding="utf-8").splitlines()
        ]
        assert extracted_r126_hl_ld_pcr[0]["pc"] == 0x4000_5700
        assert extracted_r126_hl_ld_pcr[0]["dst_reg"] == 5
        assert extracted_r126_hl_ld_pcr[0]["mem_addr"] == 0x4000_FE28
        assert extracted_r126_hl_ld_pcr[0]["mem_rdata"] == 0x4000_574C

        r121_setc_eq_source = tmp / "r121-setc-eq.jsonl"
        r121_setc_eq_output = tmp / "r121-setc-eq.rows.jsonl"
        setc_eq_taken = {
            **rows[0],
            "pc": 0x4000557C,
            "insn": 0x29A6,
            "len": 2,
            "next_pc": 0x4000557E,
            "wb_valid": 0,
            "wb_rd": 0,
            "wb_data": 0,
            "dst_valid": 0,
            "dst_reg": 0,
            "dst_data": 0,
            "src0_valid": 1,
            "src0_reg": 6,
            "src0_data": 0,
            "src1_valid": 1,
            "src1_reg": 5,
            "src1_data": 0,
            "mem_valid": 0,
            "mem_is_store": 0,
            "mem_addr": 0,
            "mem_wdata": 0,
            "mem_rdata": 0,
            "mem_size": 0,
        }
        _write_jsonl(r121_setc_eq_source, [setc_eq_taken])
        count = extract_rows(r121_setc_eq_source, r121_setc_eq_output)
        assert count == 1
        extracted_r121_setc_eq = [
            json.loads(line) for line in r121_setc_eq_output.read_text(encoding="utf-8").splitlines()
        ]
        assert extracted_r121_setc_eq[0]["pc"] == 0x4000557C
        assert extracted_r121_setc_eq[0]["dst_valid"] == 0
        assert extracted_r121_setc_eq[0]["src0_reg"] == 6
        assert extracted_r121_setc_eq[0]["src1_reg"] == 5

        r128_setc_ltui_source = tmp / "r128-setc-ltui.jsonl"
        r128_setc_ltui_output = tmp / "r128-setc-ltui.rows.jsonl"
        setc_ltui = {
            **rows[0],
            "pc": 0x4000D1E4,
            "insn": 0x00326075,
            "len": 4,
            "next_pc": 0x4000D1E8,
            "wb_valid": 0,
            "wb_rd": 0,
            "wb_data": 0,
            "dst_valid": 1,
            "dst_reg": 0,
            "dst_data": 0,
            "src0_valid": 1,
            "src0_reg": 4,
            "src0_data": 0x130,
            "src1_valid": 0,
            "src1_reg": 0,
            "src1_data": 0,
            "mem_valid": 0,
            "mem_is_store": 0,
            "mem_addr": 0,
            "mem_wdata": 0,
            "mem_rdata": 0,
            "mem_size": 0,
        }
        _write_jsonl(r128_setc_ltui_source, [setc_ltui])
        count = extract_rows(r128_setc_ltui_source, r128_setc_ltui_output)
        assert count == 1
        extracted_r128_setc_ltui = [
            json.loads(line) for line in r128_setc_ltui_output.read_text(encoding="utf-8").splitlines()
        ]
        assert extracted_r128_setc_ltui[0]["pc"] == 0x4000D1E4
        assert extracted_r128_setc_ltui[0]["dst_valid"] == 0
        assert extracted_r128_setc_ltui[0]["src0_reg"] == 4
        assert extracted_r128_setc_ltui[0]["src1_valid"] == 0

        r129_andiw_source = tmp / "r129-andiw.jsonl"
        r129_andiw_output = tmp / "r129-andiw.rows.jsonl"
        andiw = {
            **rows[0],
            "pc": 0x4000D210,
            "insn": 0x0FF1AF35,
            "len": 4,
            "next_pc": 0x4000D214,
            "wb_valid": 1,
            "wb_rd": 30,
            "wb_data": 0,
            "dst_valid": 1,
            "dst_reg": 30,
            "dst_data": 0,
            "src0_valid": 1,
            "src0_reg": 3,
            "src0_data": 0,
            "src1_valid": 0,
            "src1_reg": 0,
            "src1_data": 0,
            "mem_valid": 0,
            "mem_is_store": 0,
            "mem_addr": 0,
            "mem_wdata": 0,
            "mem_rdata": 0,
            "mem_size": 0,
        }
        _write_jsonl(r129_andiw_source, [andiw])
        count = extract_rows(r129_andiw_source, r129_andiw_output)
        assert count == 1
        extracted_r129_andiw = [
            json.loads(line) for line in r129_andiw_output.read_text(encoding="utf-8").splitlines()
        ]
        assert extracted_r129_andiw[0]["pc"] == 0x4000D210
        assert extracted_r129_andiw[0]["dst_reg"] == 30
        assert extracted_r129_andiw[0]["dst_data"] == 0
        assert extracted_r129_andiw[0]["src0_reg"] == 3
        assert extracted_r129_andiw[0]["src1_valid"] == 0

        r130_mulw_source = tmp / "r130-mulw.jsonl"
        r130_mulw_output = tmp / "r130-mulw.rows.jsonl"
        addi_u_for_mulw = {
            **rows[0],
            "pc": 0x6000,
            "insn": 0x00600F15,
            "len": 4,
            "next_pc": 0x6004,
            "wb_valid": 1,
            "wb_rd": 30,
            "wb_data": 6,
            "dst_valid": 1,
            "dst_reg": 30,
            "dst_data": 6,
            "src0_valid": 1,
            "src0_reg": 0,
            "src0_data": 0,
            "src1_valid": 0,
            "src1_reg": 0,
            "src1_data": 0,
        }
        addi_t_for_mulw = {
            **rows[0],
            "pc": 0x6004,
            "insn": 0x00700F95,
            "len": 4,
            "next_pc": 0x6008,
            "wb_valid": 1,
            "wb_rd": 31,
            "wb_data": 7,
            "dst_valid": 1,
            "dst_reg": 31,
            "dst_data": 7,
            "src0_valid": 1,
            "src0_reg": 0,
            "src0_data": 0,
            "src1_valid": 0,
            "src1_reg": 0,
            "src1_data": 0,
        }
        mulw = {
            **rows[0],
            "pc": 0x6008,
            "insn": 0x018E21C7,
            "len": 4,
            "next_pc": 0x600C,
            "wb_valid": 1,
            "wb_rd": 3,
            "wb_data": 42,
            "dst_valid": 1,
            "dst_reg": 3,
            "dst_data": 42,
            "src0_valid": 0,
            "src0_reg": 0,
            "src0_data": 0,
            "src1_valid": 0,
            "src1_reg": 0,
            "src1_data": 0,
            "mem_valid": 0,
            "mem_is_store": 0,
            "mem_addr": 0,
            "mem_wdata": 0,
            "mem_rdata": 0,
            "mem_size": 0,
        }
        _write_jsonl(r130_mulw_source, [addi_u_for_mulw, addi_t_for_mulw, mulw])
        count = extract_rows(r130_mulw_source, r130_mulw_output)
        assert count == 3
        extracted_r130_mulw = [
            json.loads(line) for line in r130_mulw_output.read_text(encoding="utf-8").splitlines()
        ]
        assert extracted_r130_mulw[2]["pc"] == 0x6008
        assert extracted_r130_mulw[2]["dst_reg"] == 3
        assert extracted_r130_mulw[2]["dst_data"] == 42
        assert extracted_r130_mulw[2]["src0_valid"] == 0
        assert extracted_r130_mulw[2]["src1_valid"] == 0

        r130_csub_source = tmp / "r130-csub.jsonl"
        r130_csub_output = tmp / "r130-csub.rows.jsonl"
        csub = {
            **rows[0],
            "pc": 0x6100,
            "insn": 0x1158,
            "len": 2,
            "next_pc": 0x6102,
            "wb_valid": 0,
            "wb_rd": 0,
            "wb_data": 0,
            "dst_valid": 0,
            "dst_reg": 0,
            "dst_data": 0,
            "src0_valid": 1,
            "src0_reg": 5,
            "src0_data": 0,
            "src1_valid": 1,
            "src1_reg": 2,
            "src1_data": 0x4FFEFBF8,
            "mem_valid": 0,
            "mem_is_store": 0,
            "mem_addr": 0,
            "mem_wdata": 0,
            "mem_rdata": 0,
            "mem_size": 0,
        }
        _write_jsonl(r130_csub_source, [csub])
        count = extract_rows(r130_csub_source, r130_csub_output)
        assert count == 1
        extracted_r130_csub = [
            json.loads(line) for line in r130_csub_output.read_text(encoding="utf-8").splitlines()
        ]
        assert extracted_r130_csub[0]["pc"] == 0x6100
        assert extracted_r130_csub[0]["dst_valid"] == 1
        assert extracted_r130_csub[0]["dst_reg"] == 31
        assert extracted_r130_csub[0]["dst_data"] == 0xFFFF_FFFFB001_0408
        assert extracted_r130_csub[0]["wb_valid"] == 1
        assert extracted_r130_csub[0]["wb_rd"] == 31

        r131_andi_source = tmp / "r131-andi.jsonl"
        r131_andi_output = tmp / "r131-andi.rows.jsonl"
        andi = {
            **rows[0],
            "pc": 0x6102,
            "insn": 0x003C2F15,
            "len": 4,
            "next_pc": 0x6106,
            "wb_valid": 1,
            "wb_rd": 30,
            "wb_data": 0,
            "dst_valid": 1,
            "dst_reg": 30,
            "dst_data": 0,
            "src0_valid": 0,
            "src0_reg": 0,
            "src0_data": 0,
            "src1_valid": 0,
            "src1_reg": 0,
            "src1_data": 0,
            "mem_valid": 0,
            "mem_is_store": 0,
            "mem_addr": 0,
            "mem_wdata": 0,
            "mem_rdata": 0,
            "mem_size": 0,
        }
        andi_t = {
            **andi,
            "pc": 0x6106,
            "insn": 0x00422F95,
            "next_pc": 0x610A,
            "wb_rd": 31,
            "dst_reg": 31,
            "src0_valid": 1,
            "src0_reg": 4,
            "src0_data": 0x4FFEFBF8,
        }
        ori = {
            **andi,
            "pc": 0x610A,
            "insn": 0x018C3315,
            "next_pc": 0x610E,
            "wb_rd": 6,
            "wb_data": 0x18,
            "dst_reg": 6,
            "dst_data": 0x18,
        }
        sub = {
            **andi,
            "pc": 0x610E,
            "insn": 0x06629285,
            "next_pc": 0x6112,
            "wb_rd": 5,
            "wb_data": 0x118,
            "dst_reg": 5,
            "dst_data": 0x118,
            "src0_valid": 1,
            "src0_reg": 5,
            "src0_data": 0x130,
            "src1_valid": 1,
            "src1_reg": 6,
            "src1_data": 0x18,
        }
        sll_scalar = {
            **andi,
            "pc": 0x6112,
            "insn": 0x00747F05,
            "next_pc": 0x6116,
            "wb_rd": 30,
            "wb_data": 0x1_0000_0000,
            "dst_reg": 30,
            "dst_data": 0x1_0000_0000,
            "src0_valid": 1,
            "src0_reg": 8,
            "src0_data": 1,
            "src1_valid": 1,
            "src1_reg": 7,
            "src1_data": 0x20,
        }
        mul = {
            **andi,
            "pc": 0x6116,
            "insn": 0x01CC01C7,
            "next_pc": 0x611A,
            "wb_rd": 3,
            "wb_data": 0,
            "dst_reg": 3,
            "dst_data": 0,
        }
        _write_jsonl(r131_andi_source, [csub, andi, andi_t, ori, sub, sll_scalar, mul])
        count = extract_rows(r131_andi_source, r131_andi_output)
        assert count == 7
        extracted_r131_andi = [
            json.loads(line) for line in r131_andi_output.read_text(encoding="utf-8").splitlines()
        ]
        assert extracted_r131_andi[1]["pc"] == 0x6102
        assert extracted_r131_andi[1]["dst_reg"] == 30
        assert extracted_r131_andi[1]["dst_data"] == 0
        assert extracted_r131_andi[1]["src0_valid"] == 0
        assert extracted_r131_andi[1]["src1_valid"] == 0
        assert extracted_r131_andi[2]["pc"] == 0x6106
        assert extracted_r131_andi[2]["dst_reg"] == 31
        assert extracted_r131_andi[2]["dst_data"] == 0
        assert extracted_r131_andi[3]["pc"] == 0x610A
        assert extracted_r131_andi[3]["dst_reg"] == 6
        assert extracted_r131_andi[3]["dst_data"] == 0x18
        assert extracted_r131_andi[4]["pc"] == 0x610E
        assert extracted_r131_andi[4]["dst_reg"] == 5
        assert extracted_r131_andi[4]["dst_data"] == 0x118
        assert extracted_r131_andi[5]["pc"] == 0x6112
        assert extracted_r131_andi[5]["dst_reg"] == 30
        assert extracted_r131_andi[5]["dst_data"] == 0x1_0000_0000
        assert extracted_r131_andi[6]["pc"] == 0x6116
        assert extracted_r131_andi[6]["dst_reg"] == 3
        assert extracted_r131_andi[6]["dst_data"] == 0

        r131_swi_source = tmp / "r131-swi.jsonl"
        r131_swi_output = tmp / "r131-swi.rows.jsonl"
        swi = {
            **rows[0],
            "pc": 0x6200,
            "insn": 0x0041A059,
            "len": 4,
            "next_pc": 0x6204,
            "wb_valid": 0,
            "wb_rd": 0,
            "wb_data": 0,
            "dst_valid": 0,
            "dst_reg": 0,
            "dst_data": 0,
            "src0_valid": 1,
            "src0_reg": 3,
            "src0_data": 0,
            "src1_valid": 1,
            "src1_reg": 4,
            "src1_data": 0x4FFEFBF8,
            "mem_valid": 1,
            "mem_is_store": 1,
            "mem_addr": 0x4FFEFBF8,
            "mem_wdata": 0,
            "mem_rdata": 0,
            "mem_size": 4,
        }
        swi_neg = {
            **swi,
            "pc": 0x6204,
            "insn": 0xFE61AFD9,
            "next_pc": 0x6208,
            "src1_reg": 6,
            "src1_data": 0x4FFEFD28,
            "mem_addr": 0x4FFEFD24,
        }
        _write_jsonl(r131_swi_source, [swi, swi_neg])
        count = extract_rows(r131_swi_source, r131_swi_output)
        assert count == 2
        extracted_r131_swi = [
            json.loads(line) for line in r131_swi_output.read_text(encoding="utf-8").splitlines()
        ]
        assert extracted_r131_swi[0]["pc"] == 0x6200
        assert extracted_r131_swi[0]["dst_valid"] == 0
        assert extracted_r131_swi[0]["mem_valid"] == 1
        assert extracted_r131_swi[0]["mem_size"] == 4
        assert extracted_r131_swi[0]["mem_addr"] == 0x4FFEFBF8
        assert extracted_r131_swi[1]["pc"] == 0x6204
        assert extracted_r131_swi[1]["mem_addr"] == 0x4FFEFD24

        unsupported = tmp / "unsupported.jsonl"
        bad = dict(rows[0])
        bad["mem_valid"] = 1
        _write_jsonl(unsupported, [bad])
        try:
            extract_rows(unsupported, tmp / "bad.jsonl")
        except RowExtractionError as exc:
            assert "mem_valid" in str(exc)
        else:
            raise AssertionError("unsupported row did not fail")

        block_source = tmp / "block-prefix.jsonl"
        block_output = tmp / "block-prefix.rows.jsonl"
        bstart = {
            **rows[0],
            "pc": 0x2000,
            "insn": 0x0800,
            "len": 2,
            "next_pc": 0x2002,
            "wb_valid": 0,
            "dst_valid": 0,
            "src0_valid": 0,
            "src1_valid": 0,
        }
        scalar = {**rows[0], "pc": 0x2002, "next_pc": 0x2006}
        bstop = {
            **rows[0],
            "pc": 0x2006,
            "insn": 0,
            "len": 2,
            "next_pc": 0x2008,
            "wb_valid": 0,
            "dst_valid": 0,
            "src0_valid": 0,
            "src1_valid": 0,
        }
        _write_jsonl(block_source, [bstart, scalar, bstop])
        count = extract_rows(block_source, block_output, allow_block_markers=True)
        assert count == 3
        block_rows = [json.loads(line) for line in block_output.read_text(encoding="utf-8").splitlines()]
        assert block_rows[0]["skip"] == 1
        assert block_rows[0]["block_boundary"] == 1
        assert "skip" not in block_rows[1]
        assert block_rows[2]["block_stop"] == 1

        hl_block_source = tmp / "hl-block-prefix.jsonl"
        hl_block_output = tmp / "hl-block-prefix.rows.jsonl"
        hl_artifact = {
            **rows[0],
            "pc": 0x40005500,
            "insn": 0x3C001000E,
            "len": 6,
            "next_pc": 0x40005500,
            "wb_valid": 0,
            "dst_valid": 0,
            "src0_valid": 0,
            "src1_valid": 0,
        }
        hl_bstart = {**hl_artifact, "next_pc": 0x40005506}
        c_setret_after_hl = {**c_setret, "pc": 0x40005506, "next_pc": 0x40005508}
        c_bstop_after_hl = {
            **rows[0],
            "pc": 0x40005508,
            "insn": 0,
            "len": 2,
            "next_pc": 0x4000550E,
            "wb_valid": 0,
            "dst_valid": 0,
            "src0_valid": 0,
            "src1_valid": 0,
        }
        _write_jsonl(hl_block_source, [hl_artifact, hl_bstart, c_setret_after_hl, c_bstop_after_hl])
        count = extract_rows(hl_block_source, hl_block_output, allow_block_markers=True)
        assert count == 3
        hl_rows = [json.loads(line) for line in hl_block_output.read_text(encoding="utf-8").splitlines()]
        assert hl_rows[0]["skip"] == 1
        assert hl_rows[0]["skip_kind"] == "HL.BSTART"
        assert hl_rows[1]["pc"] == 0x40005506
        assert hl_rows[2]["block_stop"] == 1
        assert hl_rows[2]["next_pc"] == 0x4000550E


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--input", help="Input QEMU commit JSONL trace")
    ap.add_argument("--output", help="Output reduced expected-row JSONL")
    ap.add_argument("--max-rows", type=int, default=0, help="Maximum reduced rows to extract; 0 means all")
    ap.add_argument("--allow-block-markers", action="store_true",
                    help="Preserve legal BSTART/BSTOP marker rows as skip entries while extracting scalar compare rows")
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()

    if args.self_test:
        self_test()
        print("frontend-fetch-rf-alu-qemu-rows self-test: ok")
        return 0
    if not args.input or not args.output:
        raise SystemExit("error: --input and --output are required unless --self-test is set")

    try:
        count = extract_rows(
            Path(args.input),
            Path(args.output),
            max_rows=args.max_rows,
            allow_block_markers=args.allow_block_markers)
    except RowExtractionError as exc:
        raise SystemExit(f"error: {exc}") from exc
    print(f"frontend-fetch-rf-alu-qemu-rows={args.output} rows={count}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
