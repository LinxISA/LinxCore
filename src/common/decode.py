from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Mapping

from pycircuit import Circuit, Wire, function, spec

meta = spec

from .decode16 import decode16_meta
from .decode32 import decode32_meta
from .decode48 import decode48_meta
from .decode64 import decode64_meta
from .isa import (
    OP_ADDTPC,
    OP_ADDI,
    OP_ADDIW,
    OP_ADD,
    OP_ADDW,
    OP_AND,
    OP_ANDI,
    OP_ANDIW,
    OP_BSTART_STD_COND,
    OP_BSTART_STD_DIRECT,
    OP_BSTART_STD_FALL,
    OP_BIOR,
    OP_BLOAD,
    OP_BSTORE,
    OP_BTEXT,
    OP_ANDW,
    OP_BXS,
    OP_BXU,
    OP_BSTART_STD_CALL,
    OP_CMP_EQ,
    OP_CMP_EQI,
    OP_CMP_NE,
    OP_CMP_NEI,
    OP_CMP_ANDI,
    OP_CMP_ORI,
    OP_CMP_LT,
    OP_CMP_LTI,
    OP_CMP_LTUI,
    OP_CMP_LTU,
    OP_CMP_GEI,
    OP_CMP_GEUI,
    OP_C_ADD,
    OP_C_ADDI,
    OP_C_AND,
    OP_C_OR,
    OP_C_SUB,
    OP_CSEL,
    OP_C_BSTART_DIRECT,
    OP_C_BSTOP,
    OP_C_BSTART_COND,
    OP_C_BSTART_STD,
    OP_C_LDI,
    OP_C_LWI,
    OP_C_MOVI,
    OP_C_MOVR,
    OP_C_SETC_EQ,
    OP_C_SETC_NE,
    OP_C_SETC_TGT,
    OP_C_SDI,
    OP_C_SEXT_W,
    OP_C_SETRET,
    OP_C_SWI,
    OP_C_ZEXT_W,
    OP_EBREAK,
    OP_FEQ,
    OP_FLT,
    OP_FGE,
    OP_FENTRY,
    OP_FEXIT,
    OP_FRET_RA,
    OP_FRET_STK,
    OP_HL_LB_PCR,
    OP_HL_LBU_PCR,
    OP_HL_LD_PCR,
    OP_HL_LH_PCR,
    OP_HL_LHU_PCR,
    OP_HL_LW_PCR,
    OP_HL_LUI,
    OP_HL_LWU_PCR,
    OP_HL_SB_PCR,
    OP_HL_SD_PCR,
    OP_HL_SH_PCR,
    OP_HL_SW_PCR,
    OP_INVALID,
    OP_LB,
    OP_LBI,
    OP_LBU,
    OP_LBUI,
    OP_LD,
    OP_LH,
    OP_LHI,
    OP_LHU,
    OP_LHUI,
    OP_LDI,
    OP_LUI,
    OP_LW,
    OP_LWI,
    OP_LWU,
    OP_LWUI,
    OP_MADD,
    OP_MADDW,
    OP_MUL,
    OP_MULW,
    OP_OR,
    OP_ORI,
    OP_ORIW,
    OP_ORW,
    OP_XOR,
    OP_XORI,
    OP_XORIW,
    OP_DIV,
    OP_DIVU,
    OP_DIVW,
    OP_DIVUW,
    OP_REM,
    OP_REMU,
    OP_REMW,
    OP_REMUW,
    OP_SB,
    OP_SETC_AND,
    OP_SETC_EQ,
    OP_SETC_GE,
    OP_SETC_GEI,
    OP_SETC_GEU,
    OP_SETC_GEUI,
    OP_SETC_LT,
    OP_SETC_LTI,
    OP_SETC_LTU,
    OP_SETC_LTUI,
    OP_SETC_NE,
    OP_SETC_NEI,
    OP_SETC_OR,
    OP_SETC_ORI,
    OP_SETC_ANDI,
    OP_SETC_EQI,
    OP_SETRET,
    OP_SBI,
    OP_SD,
    OP_SH,
    OP_SHI,
    OP_SDI,
    OP_SLL,
    OP_SLLI,
    OP_SLLIW,
    OP_SLLW,
    OP_SRL,
    OP_SRLI,
    OP_SRLW,
    OP_SRA,
    OP_SRAI,
    OP_SRAW,
    OP_SRAIW,
    OP_SRLIW,
    OP_SW,
    OP_SUB,
    OP_SUBI,
    OP_SUBIW,
    OP_SUBW,
    OP_SWI,
    OP_XORW,
    REG_INVALID,
)
from .util import lshr_var, masked_eq


@dataclass(frozen=True)
class Decode:
    op: Wire
    len_bytes: Wire
    regdst: Wire
    srcl: Wire
    srcr: Wire
    srcr_type: Wire
    shamt: Wire
    srcp: Wire
    imm: Wire


@dataclass(frozen=True)
class DecodeBundle:
    valid: list[Wire]
    off_bytes: list[Wire]
    dec: list[Decode]
    total_len_bytes: Wire


@function
def _decode_aw(m: Circuit, x: Wire | int, width: int) -> Wire:
    c = m.const
    if isinstance(x, Wire):
        if x.width == width:
            return x
        if x.width < width:
            return x._zext(width=width)
        return x._trunc(width=width)
    return c(int(x), width=width)


@function
def _decode_set_if(
    m: Circuit,
    cond: Wire,
    op: Wire,
    len_bytes: Wire,
    regdst: Wire,
    srcl: Wire,
    srcr: Wire,
    srcr_type: Wire,
    shamt: Wire,
    srcp: Wire,
    imm: Wire,
    *,
    op_v: Wire | int | None = None,
    len_v: Wire | int | None = None,
    regdst_v: Wire | int | None = None,
    srcl_v: Wire | int | None = None,
    srcr_v: Wire | int | None = None,
    srcr_type_v: Wire | int | None = None,
    shamt_v: Wire | int | None = None,
    srcp_v: Wire | int | None = None,
    imm_v: Wire | int | None = None,
) -> tuple[Wire, Wire, Wire, Wire, Wire, Wire, Wire, Wire, Wire]:
    cond = m.wire(cond)
    if op_v is not None:
        op = cond._select_internal(_decode_aw(m, op_v, 12), op)
    if len_v is not None:
        len_bytes = cond._select_internal(_decode_aw(m, len_v, 3), len_bytes)
    if regdst_v is not None:
        regdst = cond._select_internal(_decode_aw(m, regdst_v, 6), regdst)
    if srcl_v is not None:
        srcl = cond._select_internal(_decode_aw(m, srcl_v, 6), srcl)
    if srcr_v is not None:
        srcr = cond._select_internal(_decode_aw(m, srcr_v, 6), srcr)
    if srcr_type_v is not None:
        srcr_type = cond._select_internal(_decode_aw(m, srcr_type_v, 2), srcr_type)
    if shamt_v is not None:
        shamt = cond._select_internal(_decode_aw(m, shamt_v, 6), shamt)
    if srcp_v is not None:
        srcp = cond._select_internal(_decode_aw(m, srcp_v, 6), srcp)
    if imm_v is not None:
        imm = cond._select_internal(_decode_aw(m, imm_v, 64), imm)
    return op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm


def _decode_rule_table_16(m: Circuit):
    _ = m
    rule = meta.DecodeRule.from_mapping
    return (
        rule(
            name="c_addi",
            mask=0x003F,
            match=0x000C,
            updates={"op": OP_C_ADDI, "len": 2, "regdst": 31, "srcl": "rs16", "imm": "simm5_11_s64"},
            priority=0,
        ),
        rule(
            name="c_add",
            mask=0x003F,
            match=0x0008,
            updates={"op": OP_C_ADD, "len": 2, "regdst": 31, "srcl": "rs16", "srcr": "rd16"},
            priority=1,
        ),
        rule(
            name="c_sub",
            mask=0x003F,
            match=0x0018,
            updates={"op": OP_C_SUB, "len": 2, "regdst": 31, "srcl": "rs16", "srcr": "rd16"},
            priority=2,
        ),
        rule(
            name="c_and",
            mask=0x003F,
            match=0x0028,
            updates={"op": OP_C_AND, "len": 2, "regdst": 31, "srcl": "rs16", "srcr": "rd16"},
            priority=3,
        ),
        rule(
            name="c_movi",
            mask=0x003F,
            match=0x0016,
            updates={"op": OP_C_MOVI, "len": 2, "regdst": "rd16", "imm": "simm5_6_s64"},
            priority=4,
        ),
        rule(
            name="c_setret",
            mask=0xF83F,
            match=0x5016,
            updates={"op": OP_C_SETRET, "len": 2, "regdst": 10, "imm": "c_setret_imm"},
            priority=5,
        ),
        rule(
            name="c_swi",
            mask=0x003F,
            match=0x002A,
            updates={"op": OP_C_SWI, "len": 2, "srcl": "rs16", "srcr": 24, "imm": "simm5_11_s64"},
            priority=6,
        ),
        rule(
            name="c_sdi",
            mask=0x003F,
            match=0x003A,
            updates={"op": OP_C_SDI, "len": 2, "srcl": "rs16", "srcr": 24, "imm": "simm5_11_s64"},
            priority=7,
        ),
        rule(
            name="c_lwi",
            mask=0x003F,
            match=0x000A,
            updates={"op": OP_C_LWI, "len": 2, "regdst": 31, "srcl": "rs16", "imm": "simm5_11_s64"},
            priority=8,
        ),
        rule(
            name="c_ldi",
            mask=0x003F,
            match=0x001A,
            updates={"op": OP_C_LDI, "len": 2, "regdst": 31, "srcl": "rs16", "imm": "simm5_11_s64"},
            priority=9,
        ),
        rule(
            name="c_movr",
            mask=0x003F,
            match=0x0006,
            updates={"op": OP_C_MOVR, "len": 2, "regdst": "rd16", "srcl": "rs16"},
            priority=10,
        ),
        rule(
            name="c_setc_eq",
            mask=0x003F,
            match=0x0026,
            updates={"op": OP_C_SETC_EQ, "len": 2, "srcl": "rs16", "srcr": "rd16"},
            priority=11,
        ),
        rule(
            name="c_setc_ne",
            mask=0x003F,
            match=0x0036,
            updates={"op": OP_C_SETC_NE, "len": 2, "srcl": "rs16", "srcr": "rd16"},
            priority=12,
        ),
        rule(
            name="c_setc_tgt",
            mask=0xF83F,
            match=0x001C,
            updates={"op": OP_C_SETC_TGT, "len": 2, "srcl": "rs16"},
            priority=13,
        ),
        rule(
            name="c_or",
            mask=0x003F,
            match=0x0038,
            updates={"op": OP_C_OR, "len": 2, "regdst": 31, "srcl": "rs16", "srcr": "rd16"},
            priority=14,
        ),
        rule(
            name="c_sext_w",
            mask=0xF83F,
            match=0x501C,
            updates={"op": OP_C_SEXT_W, "len": 2, "regdst": 31, "srcl": "rs16"},
            priority=15,
        ),
        rule(
            name="c_zext_w",
            mask=0xF83F,
            match=0x681C,
            updates={"op": OP_C_ZEXT_W, "len": 2, "regdst": 31, "srcl": "rs16"},
            priority=16,
        ),
        rule(
            name="c_cmp_eqi",
            mask=0xF83F,
            match=0x002C,
            updates={"op": OP_CMP_EQI, "len": 2, "regdst": 31, "srcl": 24, "imm": "simm5_6_s64"},
            priority=17,
        ),
        rule(
            name="c_cmp_nei",
            mask=0xF83F,
            match=0x082C,
            updates={"op": OP_CMP_NEI, "len": 2, "regdst": 31, "srcl": 24, "imm": "simm5_6_s64"},
            priority=18,
        ),
        rule(
            name="c_bstart_direct",
            mask=0x000F,
            match=0x0002,
            updates={"op": OP_C_BSTART_DIRECT, "len": 2, "regdst": REG_INVALID, "imm": "c_branch_off"},
            priority=19,
        ),
        rule(
            name="c_bstart_cond",
            mask=0x000F,
            match=0x0004,
            updates={"op": OP_C_BSTART_COND, "len": 2, "regdst": REG_INVALID, "imm": "c_branch_off"},
            priority=20,
        ),
        rule(
            name="c_bstart_std",
            mask=0xC7FF,
            match=0x0000,
            updates={"op": OP_C_BSTART_STD, "len": 2, "regdst": REG_INVALID, "imm": "brtype"},
            priority=21,
        ),
        rule(
            name="c_bstop",
            mask=0xFFFF,
            match=0x0000,
            updates={"op": OP_C_BSTOP, "len": 2, "regdst": REG_INVALID},
            priority=22,
        ),
    )


@function
def _decode_apply_rule_table(
    m: Circuit,
    *,
    active: Wire,
    insn: Wire,
    rules,
    value_map: Mapping[str, Wire | int],
    op: Wire,
    len_bytes: Wire,
    regdst: Wire,
    srcl: Wire,
    srcr: Wire,
    srcr_type: Wire,
    shamt: Wire,
    srcp: Wire,
    imm: Wire,
) -> tuple[Wire, Wire, Wire, Wire, Wire, Wire, Wire, Wire, Wire]:
    for rule in rules:
        cond = active & masked_eq(m, insn, mask=int(rule.mask), match=int(rule.match))

        op_v: Any = None
        len_v: Any = None
        regdst_v: Any = None
        srcl_v: Any = None
        srcr_v: Any = None
        srcr_type_v: Any = None
        shamt_v: Any = None
        srcp_v: Any = None
        imm_v: Any = None

        for key, raw_v in rule.updates:
            v = raw_v
            if hasattr(v, "startswith"):
                v = value_map[v]
            if key == "op":
                op_v = v
            elif key == "len":
                len_v = v
            elif key == "regdst":
                regdst_v = v
            elif key == "srcl":
                srcl_v = v
            elif key == "srcr":
                srcr_v = v
            elif key == "srcr_type":
                srcr_type_v = v
            elif key == "shamt":
                shamt_v = v
            elif key == "srcp":
                srcp_v = v
            elif key == "imm":
                imm_v = v
            else:
                raise ValueError(f"unsupported decode update key {key!r} in {rule.name!r}")

        (op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm) = _decode_set_if(
            m,
            cond,
            op,
            len_bytes,
            regdst,
            srcl,
            srcr,
            srcr_type,
            shamt,
            srcp,
            imm,
            op_v=op_v,
            len_v=len_v,
            regdst_v=regdst_v,
            srcl_v=srcl_v,
            srcr_v=srcr_v,
            srcr_type_v=srcr_type_v,
            shamt_v=shamt_v,
            srcp_v=srcp_v,
            imm_v=imm_v,
        )

    return op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm


@function
def decode_window(m: Circuit, window: Wire) -> Decode:
    c = m.const

    zero3 = c(0, width=3)
    zero2 = c(0, width=2)
    zero6 = c(0, width=6)
    zero64 = c(0, width=64)
    reg_invalid = c(REG_INVALID, width=6)

    insn16 = window._trunc(width=16)
    insn32 = window._trunc(width=32)
    insn48 = window._trunc(width=48)

    low4 = insn16[0:4]
    is_hl = low4.__eq__(0xE)

    is32 = insn16[0]
    in32 = (~is_hl) & is32
    in16 = (~is_hl) & (~is32)

    rd32 = insn32[7:12]
    rs1_32 = insn32[15:20]
    rs2_32 = insn32[20:25]
    srcr_type_32 = insn32[25:27]
    shamt5_32 = insn32[27:32]
    srcp_32 = insn32[27:32]
    shamt6_32 = insn32[20:26]

    imm12_u64 = insn32[20:32]
    imm12_s64 = insn32[20:32]._sext(width=64)

    imm20_s64 = insn32[12:32]._sext(width=64)
    imm20_u64 = insn32[12:32]._zext(width=64)

    # SWI simm12 is split: {insn32[11:7], insn32[31:25]}.
    swi_lo5 = insn32[7:12]
    swi_hi7 = insn32[25:32]
    simm12_raw = swi_lo5._zext(width=12).shl(amount=7) | swi_hi7._zext(width=12)
    simm12_s64 = simm12_raw._sext(width=64)
    simm17_s64 = insn32[15:32]._sext(width=64)
    simm25_s64 = insn32[7:32]._sext(width=64)

    # HL.LUI immediate packing (48-bit):
    # pfx = insn48[15:0]; main = insn48[47:16]
    pfx16 = insn48._trunc(width=16)
    main32 = insn48[16:48]
    imm_hi12 = pfx16[4:16]
    imm_lo20 = main32[12:32]
    imm32 = imm_hi12._zext(width=32).shl(amount=20) | imm_lo20._zext(width=32)
    imm_hl_lui = imm32._sext(width=64)

    rd_hl = main32[7:12]

    # 16-bit fields.
    rd16 = insn16[11:16]
    rs16 = insn16[6:11]
    # Immediate fields:
    # - simm5_11_s5: bits[15:11] (used by loads/stores)
    # - simm5_6_s5: bits[10:6] (used by C.MOVI / C.SETRET)
    simm5_11_s64 = insn16[11:16]._sext(width=64)
    simm5_6_s64 = insn16[6:11]._sext(width=64)
    simm12_s64_c = insn16[4:16]._sext(width=64)
    uimm5 = insn16[6:11]
    brtype = insn16[11:14]

    op = c(OP_INVALID, width=12)
    len_bytes = zero3
    regdst = reg_invalid
    srcl = reg_invalid
    srcr = reg_invalid
    srcr_type = zero2
    shamt = zero6
    srcp = reg_invalid
    imm = zero64

    # --- 16-bit decode (rule table; C.BSTOP highest) ---
    c_setret_imm = uimm5._zext(width=6).shl(amount=1)
    c_branch_off = simm12_s64_c.shl(amount=1)
    decode16_vals: dict[str, Wire | int] = {
        "rs16": rs16,
        "rd16": rd16,
        "simm5_11_s64": simm5_11_s64,
        "simm5_6_s64": simm5_6_s64,
        "c_setret_imm": c_setret_imm,
        "c_branch_off": c_branch_off,
        "brtype": brtype,
    }
    (op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm) = _decode_apply_rule_table(
        m,
        active=in16,
        insn=insn16,
        rules=_decode_rule_table_16(m),
        value_map=decode16_vals,
        op=op,
        len_bytes=len_bytes,
        regdst=regdst,
        srcl=srcl,
        srcr=srcr,
        srcr_type=srcr_type,
        shamt=shamt,
        srcp=srcp,
        imm=imm,
    )
    # --- 32-bit decode (reverse priority; EBREAK highest) ---
    cond = in32 & masked_eq(m, insn32, mask=0x0000707F, match=0x00000041)
    uimm_hi = insn32[7:12]._zext(width=64)
    uimm_lo = insn32[25:32]._zext(width=64)
    macro_imm = uimm_hi.shl(amount=10) | uimm_lo.shl(amount=3)
    (op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm) = _decode_set_if(m, cond, op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm,
        op_v=OP_FENTRY,
        len_v=4,
        regdst_v=REG_INVALID,
        srcl_v=insn32[15:20],
        srcr_v=insn32[20:25],
        imm_v=macro_imm,
    )

    cond = in32 & masked_eq(m, insn32, mask=0x0000707F, match=0x00001041)
    (op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm) = _decode_set_if(m, cond, op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm,
        op_v=OP_FEXIT,
        len_v=4,
        regdst_v=REG_INVALID,
        srcl_v=insn32[15:20],
        srcr_v=insn32[20:25],
        imm_v=macro_imm,
    )

    cond = in32 & masked_eq(m, insn32, mask=0x0000707F, match=0x00002041)
    (op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm) = _decode_set_if(m, cond, op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm,
        op_v=OP_FRET_RA,
        len_v=4,
        regdst_v=REG_INVALID,
        srcl_v=insn32[15:20],
        srcr_v=insn32[20:25],
        imm_v=macro_imm,
    )

    cond = in32 & masked_eq(m, insn32, mask=0x0000707F, match=0x00003041)
    (op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm) = _decode_set_if(m, cond, op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm,
        op_v=OP_FRET_STK,
        len_v=4,
        regdst_v=REG_INVALID,
        srcl_v=insn32[15:20],
        srcr_v=insn32[20:25],
        imm_v=macro_imm,
    )

    cond = in32 & masked_eq(m, insn32, mask=0x0000007F, match=0x00000017)
    (op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm) = _decode_set_if(m, cond, op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm, op_v=OP_LUI, len_v=4, regdst_v=rd32, imm_v=imm20_s64.shl(amount=12))

    cond = in32 & masked_eq(m, insn32, mask=0x0000707F, match=0x00000005)
    (op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm) = _decode_set_if(m, cond, op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm,
        op_v=OP_ADD,
        len_v=4,
        regdst_v=rd32,
        srcl_v=rs1_32,
        srcr_v=rs2_32,
        srcr_type_v=srcr_type_32,
        shamt_v=shamt5_32,
    )

    cond = in32 & masked_eq(m, insn32, mask=0x0000707F, match=0x00001005)
    (op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm) = _decode_set_if(m, cond, op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm,
        op_v=OP_SUB,
        len_v=4,
        regdst_v=rd32,
        srcl_v=rs1_32,
        srcr_v=rs2_32,
        srcr_type_v=srcr_type_32,
        shamt_v=shamt5_32,
    )

    cond = in32 & masked_eq(m, insn32, mask=0x0000707F, match=0x00002005)
    (op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm) = _decode_set_if(m, cond, op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm,
        op_v=OP_AND,
        len_v=4,
        regdst_v=rd32,
        srcl_v=rs1_32,
        srcr_v=rs2_32,
        srcr_type_v=srcr_type_32,
        shamt_v=shamt5_32,
    )

    cond = in32 & masked_eq(m, insn32, mask=0x0000707F, match=0x00003005)
    (op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm) = _decode_set_if(m, cond, op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm,
        op_v=OP_OR,
        len_v=4,
        regdst_v=rd32,
        srcl_v=rs1_32,
        srcr_v=rs2_32,
        srcr_type_v=srcr_type_32,
        shamt_v=shamt5_32,
    )

    cond = in32 & masked_eq(m, insn32, mask=0x0000707F, match=0x00004005)
    (op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm) = _decode_set_if(m, cond, op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm,
        op_v=OP_XOR,
        len_v=4,
        regdst_v=rd32,
        srcl_v=rs1_32,
        srcr_v=rs2_32,
        srcr_type_v=srcr_type_32,
        shamt_v=shamt5_32,
    )

    cond = in32 & masked_eq(m, insn32, mask=0x0000707F, match=0x00002015)
    (op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm) = _decode_set_if(m, cond, op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm, op_v=OP_ANDI, len_v=4, regdst_v=rd32, srcl_v=rs1_32, imm_v=imm12_s64)

    cond = in32 & masked_eq(m, insn32, mask=0x0000707F, match=0x00002035)
    (op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm) = _decode_set_if(m, cond, op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm, op_v=OP_ANDIW, len_v=4, regdst_v=rd32, srcl_v=rs1_32, imm_v=imm12_s64)

    cond = in32 & masked_eq(m, insn32, mask=0x0000707F, match=0x00003015)
    (op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm) = _decode_set_if(m, cond, op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm, op_v=OP_ORI, len_v=4, regdst_v=rd32, srcl_v=rs1_32, imm_v=imm12_s64)

    cond = in32 & masked_eq(m, insn32, mask=0x0000707F, match=0x00003035)
    (op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm) = _decode_set_if(m, cond, op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm, op_v=OP_ORIW, len_v=4, regdst_v=rd32, srcl_v=rs1_32, imm_v=imm12_s64)

    cond = in32 & masked_eq(m, insn32, mask=0x0000707F, match=0x00004015)
    (op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm) = _decode_set_if(m, cond, op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm, op_v=OP_XORI, len_v=4, regdst_v=rd32, srcl_v=rs1_32, imm_v=imm12_s64)

    cond = in32 & masked_eq(m, insn32, mask=0x0000707F, match=0x00004035)
    (op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm) = _decode_set_if(m, cond, op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm, op_v=OP_XORIW, len_v=4, regdst_v=rd32, srcl_v=rs1_32, imm_v=imm12_s64)

    # Mul/Div/Rem (benchmarks).
    cond = in32 & masked_eq(m, insn32, mask=0xFE00707F, match=0x00000047)
    (op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm) = _decode_set_if(m, cond, op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm, op_v=OP_MUL, len_v=4, regdst_v=rd32, srcl_v=rs1_32, srcr_v=rs2_32)

    cond = in32 & masked_eq(m, insn32, mask=0xFE00707F, match=0x00002047)
    (op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm) = _decode_set_if(m, cond, op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm, op_v=OP_MULW, len_v=4, regdst_v=rd32, srcl_v=rs1_32, srcr_v=rs2_32)

    # MADD/MADDW: RegDst = SrcD + (SrcL * SrcR). SrcD is in bits[31:27] and is
    # carried through our pipeline in `srcp`.
    cond = in32 & masked_eq(m, insn32, mask=0x0600707F, match=0x00006047)
    (op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm) = _decode_set_if(m, cond, op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm, op_v=OP_MADD, len_v=4, regdst_v=rd32, srcl_v=rs1_32, srcr_v=rs2_32, srcp_v=srcp_32)

    cond = in32 & masked_eq(m, insn32, mask=0x0600707F, match=0x00007047)
    (op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm) = _decode_set_if(m, cond, op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm, op_v=OP_MADDW, len_v=4, regdst_v=rd32, srcl_v=rs1_32, srcr_v=rs2_32, srcp_v=srcp_32)

    cond = in32 & masked_eq(m, insn32, mask=0xFE00707F, match=0x00000057)
    (op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm) = _decode_set_if(m, cond, op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm, op_v=OP_DIV, len_v=4, regdst_v=rd32, srcl_v=rs1_32, srcr_v=rs2_32)

    cond = in32 & masked_eq(m, insn32, mask=0xFE00707F, match=0x00001057)
    (op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm) = _decode_set_if(m, cond, op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm, op_v=OP_DIVU, len_v=4, regdst_v=rd32, srcl_v=rs1_32, srcr_v=rs2_32)

    cond = in32 & masked_eq(m, insn32, mask=0xFE00707F, match=0x00002057)
    (op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm) = _decode_set_if(m, cond, op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm, op_v=OP_DIVW, len_v=4, regdst_v=rd32, srcl_v=rs1_32, srcr_v=rs2_32)

    cond = in32 & masked_eq(m, insn32, mask=0xFE00707F, match=0x00003057)
    (op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm) = _decode_set_if(m, cond, op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm, op_v=OP_DIVUW, len_v=4, regdst_v=rd32, srcl_v=rs1_32, srcr_v=rs2_32)

    cond = in32 & masked_eq(m, insn32, mask=0xFE00707F, match=0x00004057)
    (op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm) = _decode_set_if(m, cond, op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm, op_v=OP_REM, len_v=4, regdst_v=rd32, srcl_v=rs1_32, srcr_v=rs2_32)

    cond = in32 & masked_eq(m, insn32, mask=0xFE00707F, match=0x00005057)
    (op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm) = _decode_set_if(m, cond, op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm, op_v=OP_REMU, len_v=4, regdst_v=rd32, srcl_v=rs1_32, srcr_v=rs2_32)

    cond = in32 & masked_eq(m, insn32, mask=0xFE00707F, match=0x00006057)
    (op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm) = _decode_set_if(m, cond, op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm, op_v=OP_REMW, len_v=4, regdst_v=rd32, srcl_v=rs1_32, srcr_v=rs2_32)

    cond = in32 & masked_eq(m, insn32, mask=0xFE00707F, match=0x00007057)
    (op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm) = _decode_set_if(m, cond, op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm, op_v=OP_REMUW, len_v=4, regdst_v=rd32, srcl_v=rs1_32, srcr_v=rs2_32)

    cond = in32 & masked_eq(m, insn32, mask=0xFE00707F, match=0x00007005)
    (op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm) = _decode_set_if(m, cond, op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm, op_v=OP_SLL, len_v=4, regdst_v=rd32, srcl_v=rs1_32, srcr_v=rs2_32)

    cond = in32 & masked_eq(m, insn32, mask=0xFE00707F, match=0x00005005)
    (op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm) = _decode_set_if(m, cond, op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm, op_v=OP_SRL, len_v=4, regdst_v=rd32, srcl_v=rs1_32, srcr_v=rs2_32)

    cond = in32 & masked_eq(m, insn32, mask=0xFE00707F, match=0x00006005)
    (op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm) = _decode_set_if(m, cond, op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm, op_v=OP_SRA, len_v=4, regdst_v=rd32, srcl_v=rs1_32, srcr_v=rs2_32)

    cond = in32 & masked_eq(m, insn32, mask=0xFC00707F, match=0x00007015)
    (op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm) = _decode_set_if(m, cond, op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm, op_v=OP_SLLI, len_v=4, regdst_v=rd32, srcl_v=rs1_32, shamt_v=shamt6_32)

    cond = in32 & masked_eq(m, insn32, mask=0xFC00707F, match=0x00005015)
    (op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm) = _decode_set_if(m, cond, op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm, op_v=OP_SRLI, len_v=4, regdst_v=rd32, srcl_v=rs1_32, shamt_v=shamt6_32)

    cond = in32 & masked_eq(m, insn32, mask=0xFC00707F, match=0x00006015)
    (op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm) = _decode_set_if(m, cond, op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm, op_v=OP_SRAI, len_v=4, regdst_v=rd32, srcl_v=rs1_32, shamt_v=shamt6_32)

    cond = in32 & masked_eq(m, insn32, mask=0xFE00707F, match=0x00005035)
    (op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm) = _decode_set_if(m, cond, op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm, op_v=OP_SRLIW, len_v=4, regdst_v=rd32, srcl_v=rs1_32, shamt_v=rs2_32)

    cond = in32 & masked_eq(m, insn32, mask=0xFE00707F, match=0x00006035)
    (op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm) = _decode_set_if(m, cond, op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm, op_v=OP_SRAIW, len_v=4, regdst_v=rd32, srcl_v=rs1_32, shamt_v=rs2_32)

    cond = in32 & masked_eq(m, insn32, mask=0xFE00707F, match=0x00007035)
    (op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm) = _decode_set_if(m, cond, op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm, op_v=OP_SLLIW, len_v=4, regdst_v=rd32, srcl_v=rs1_32, shamt_v=rs2_32)

    cond = in32 & masked_eq(m, insn32, mask=0x0000707F, match=0x00000067)
    (op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm) = _decode_set_if(m, cond, op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm, op_v=OP_BXS, len_v=4, regdst_v=rd32, srcl_v=rs1_32, srcr_v=insn32[26:32], srcp_v=insn32[20:26])

    cond = in32 & masked_eq(m, insn32, mask=0x0000707F, match=0x00001067)
    (op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm) = _decode_set_if(m, cond, op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm, op_v=OP_BXU, len_v=4, regdst_v=rd32, srcl_v=rs1_32, srcr_v=insn32[26:32], srcp_v=insn32[20:26])

    cond = in32 & masked_eq(m, insn32, mask=0xF800707F, match=0x00006045)
    (op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm) = _decode_set_if(m, cond, op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm, op_v=OP_CMP_LTU, len_v=4, regdst_v=rd32, srcl_v=rs1_32, srcr_v=rs2_32, srcr_type_v=srcr_type_32)

    # CMP.* immediate variants.
    cond = in32 & masked_eq(m, insn32, mask=0x0000707F, match=0x00000055)
    (op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm) = _decode_set_if(m, cond, op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm, op_v=OP_CMP_EQI, len_v=4, regdst_v=rd32, srcl_v=rs1_32, imm_v=imm12_s64)

    cond = in32 & masked_eq(m, insn32, mask=0x0000707F, match=0x00001055)
    (op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm) = _decode_set_if(m, cond, op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm, op_v=OP_CMP_NEI, len_v=4, regdst_v=rd32, srcl_v=rs1_32, imm_v=imm12_s64)

    cond = in32 & masked_eq(m, insn32, mask=0x0000707F, match=0x00002055)
    (op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm) = _decode_set_if(m, cond, op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm, op_v=OP_CMP_ANDI, len_v=4, regdst_v=rd32, srcl_v=rs1_32, imm_v=imm12_s64)

    cond = in32 & masked_eq(m, insn32, mask=0x0000707F, match=0x00003055)
    (op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm) = _decode_set_if(m, cond, op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm, op_v=OP_CMP_ORI, len_v=4, regdst_v=rd32, srcl_v=rs1_32, imm_v=imm12_s64)

    cond = in32 & masked_eq(m, insn32, mask=0x0000707F, match=0x00004055)
    (op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm) = _decode_set_if(m, cond, op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm, op_v=OP_CMP_LTI, len_v=4, regdst_v=rd32, srcl_v=rs1_32, imm_v=imm12_s64)

    cond = in32 & masked_eq(m, insn32, mask=0x0000707F, match=0x00005055)
    (op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm) = _decode_set_if(m, cond, op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm, op_v=OP_CMP_GEI, len_v=4, regdst_v=rd32, srcl_v=rs1_32, imm_v=imm12_s64)

    cond = in32 & masked_eq(m, insn32, mask=0x0000707F, match=0x00006055)
    (op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm) = _decode_set_if(m, cond, op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm, op_v=OP_CMP_LTUI, len_v=4, regdst_v=rd32, srcl_v=rs1_32, imm_v=imm12_u64)

    cond = in32 & masked_eq(m, insn32, mask=0x0000707F, match=0x00007055)
    (op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm) = _decode_set_if(m, cond, op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm, op_v=OP_CMP_GEUI, len_v=4, regdst_v=rd32, srcl_v=rs1_32, imm_v=imm12_u64)

    # SETC.* immediate variants (opcode 0x75): update commit_cond (committed in WB).
    # These encode shamt in bits[11:7] (RegDst field in the ISA).
    cond = in32 & masked_eq(m, insn32, mask=0x0000707F, match=0x00000075)
    (op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm) = _decode_set_if(m, cond, op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm, op_v=OP_SETC_EQI, len_v=4, srcl_v=rs1_32, shamt_v=rd32, imm_v=imm12_s64)

    cond = in32 & masked_eq(m, insn32, mask=0x0000707F, match=0x00001075)
    (op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm) = _decode_set_if(m, cond, op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm, op_v=OP_SETC_NEI, len_v=4, srcl_v=rs1_32, shamt_v=rd32, imm_v=imm12_s64)

    cond = in32 & masked_eq(m, insn32, mask=0x0000707F, match=0x00002075)
    (op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm) = _decode_set_if(m, cond, op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm, op_v=OP_SETC_ANDI, len_v=4, srcl_v=rs1_32, shamt_v=rd32, imm_v=imm12_s64)

    cond = in32 & masked_eq(m, insn32, mask=0x0000707F, match=0x00003075)
    (op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm) = _decode_set_if(m, cond, op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm, op_v=OP_SETC_ORI, len_v=4, srcl_v=rs1_32, shamt_v=rd32, imm_v=imm12_s64)

    cond = in32 & masked_eq(m, insn32, mask=0x0000707F, match=0x00004075)
    (op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm) = _decode_set_if(m, cond, op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm, op_v=OP_SETC_LTI, len_v=4, srcl_v=rs1_32, shamt_v=rd32, imm_v=imm12_s64)

    cond = in32 & masked_eq(m, insn32, mask=0x0000707F, match=0x00005075)
    (op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm) = _decode_set_if(m, cond, op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm, op_v=OP_SETC_GEI, len_v=4, srcl_v=rs1_32, shamt_v=rd32, imm_v=imm12_s64)

    cond = in32 & masked_eq(m, insn32, mask=0x0000707F, match=0x00006075)
    (op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm) = _decode_set_if(m, cond, op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm, op_v=OP_SETC_LTUI, len_v=4, srcl_v=rs1_32, shamt_v=rd32, imm_v=imm12_u64)

    cond = in32 & masked_eq(m, insn32, mask=0x0000707F, match=0x00007075)
    (op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm) = _decode_set_if(m, cond, op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm, op_v=OP_SETC_GEUI, len_v=4, srcl_v=rs1_32, shamt_v=rd32, imm_v=imm12_u64)

    # SETC.* (register forms): update commit_cond (committed in WB).
    cond = in32 & masked_eq(m, insn32, mask=0xF800707F, match=0x00000065)
    (op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm) = _decode_set_if(m, cond, op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm, op_v=OP_SETC_EQ, len_v=4, srcl_v=rs1_32, srcr_v=rs2_32, srcr_type_v=srcr_type_32)

    cond = in32 & masked_eq(m, insn32, mask=0xF800707F, match=0x00001065)
    (op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm) = _decode_set_if(m, cond, op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm, op_v=OP_SETC_NE, len_v=4, srcl_v=rs1_32, srcr_v=rs2_32, srcr_type_v=srcr_type_32)

    cond = in32 & masked_eq(m, insn32, mask=0xF800707F, match=0x00002065)
    (op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm) = _decode_set_if(m, cond, op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm, op_v=OP_SETC_AND, len_v=4, srcl_v=rs1_32, srcr_v=rs2_32, srcr_type_v=srcr_type_32)

    cond = in32 & masked_eq(m, insn32, mask=0xF800707F, match=0x00003065)
    (op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm) = _decode_set_if(m, cond, op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm, op_v=OP_SETC_OR, len_v=4, srcl_v=rs1_32, srcr_v=rs2_32, srcr_type_v=srcr_type_32)

    cond = in32 & masked_eq(m, insn32, mask=0xF800707F, match=0x00004065)
    (op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm) = _decode_set_if(m, cond, op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm, op_v=OP_SETC_LT, len_v=4, srcl_v=rs1_32, srcr_v=rs2_32, srcr_type_v=srcr_type_32)

    cond = in32 & masked_eq(m, insn32, mask=0xF800707F, match=0x00006065)
    (op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm) = _decode_set_if(m, cond, op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm, op_v=OP_SETC_LTU, len_v=4, srcl_v=rs1_32, srcr_v=rs2_32, srcr_type_v=srcr_type_32)

    cond = in32 & masked_eq(m, insn32, mask=0xF800707F, match=0x00005065)
    (op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm) = _decode_set_if(m, cond, op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm, op_v=OP_SETC_GE, len_v=4, srcl_v=rs1_32, srcr_v=rs2_32, srcr_type_v=srcr_type_32)

    cond = in32 & masked_eq(m, insn32, mask=0xF800707F, match=0x00007065)
    (op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm) = _decode_set_if(m, cond, op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm, op_v=OP_SETC_GEU, len_v=4, srcl_v=rs1_32, srcr_v=rs2_32, srcr_type_v=srcr_type_32)

    # SETC.TGT: set commit target to SrcL (legal only inside a non-FALL block in the ISA).
    cond = in32 & masked_eq(m, insn32, mask=0xFFF07FFF, match=0x0000403B)
    (op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm) = _decode_set_if(m, cond, op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm, op_v=OP_C_SETC_TGT, len_v=4, srcl_v=rs1_32)

    cond = in32 & masked_eq(m, insn32, mask=0x0000707F, match=0x00004019)
    (op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm) = _decode_set_if(m, cond, op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm, op_v=OP_LBUI, len_v=4, regdst_v=rd32, srcl_v=rs1_32, imm_v=imm12_s64)

    cond = in32 & masked_eq(m, insn32, mask=0x0000707F, match=0x00000019)
    (op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm) = _decode_set_if(m, cond, op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm, op_v=OP_LBI, len_v=4, regdst_v=rd32, srcl_v=rs1_32, imm_v=imm12_s64)

    cond = in32 & masked_eq(m, insn32, mask=0x0000707F, match=0x00001019)
    (op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm) = _decode_set_if(m, cond, op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm, op_v=OP_LHI, len_v=4, regdst_v=rd32, srcl_v=rs1_32, imm_v=imm12_s64)

    cond = in32 & masked_eq(m, insn32, mask=0x0000707F, match=0x00005019)
    (op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm) = _decode_set_if(m, cond, op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm, op_v=OP_LHUI, len_v=4, regdst_v=rd32, srcl_v=rs1_32, imm_v=imm12_s64)

    cond = in32 & masked_eq(m, insn32, mask=0x0000707F, match=0x00006019)
    (op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm) = _decode_set_if(m, cond, op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm, op_v=OP_LWUI, len_v=4, regdst_v=rd32, srcl_v=rs1_32, imm_v=imm12_s64)

    cond = in32 & masked_eq(m, insn32, mask=0x0000707F, match=0x00000009)
    (op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm) = _decode_set_if(m, cond, op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm,
        op_v=OP_LB,
        len_v=4,
        regdst_v=rd32,
        srcl_v=rs1_32,
        srcr_v=rs2_32,
        srcr_type_v=srcr_type_32,
        shamt_v=shamt5_32,
    )

    cond = in32 & masked_eq(m, insn32, mask=0x0000707F, match=0x00004009)
    (op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm) = _decode_set_if(m, cond, op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm,
        op_v=OP_LBU,
        len_v=4,
        regdst_v=rd32,
        srcl_v=rs1_32,
        srcr_v=rs2_32,
        srcr_type_v=srcr_type_32,
        shamt_v=shamt5_32,
    )

    cond = in32 & masked_eq(m, insn32, mask=0x0000707F, match=0x00001009)
    (op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm) = _decode_set_if(m, cond, op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm,
        op_v=OP_LH,
        len_v=4,
        regdst_v=rd32,
        srcl_v=rs1_32,
        srcr_v=rs2_32,
        srcr_type_v=srcr_type_32,
        shamt_v=shamt5_32,
    )

    cond = in32 & masked_eq(m, insn32, mask=0x0000707F, match=0x00005009)
    (op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm) = _decode_set_if(m, cond, op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm,
        op_v=OP_LHU,
        len_v=4,
        regdst_v=rd32,
        srcl_v=rs1_32,
        srcr_v=rs2_32,
        srcr_type_v=srcr_type_32,
        shamt_v=shamt5_32,
    )

    cond = in32 & masked_eq(m, insn32, mask=0x0000707F, match=0x00002009)
    (op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm) = _decode_set_if(m, cond, op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm,
        op_v=OP_LW,
        len_v=4,
        regdst_v=rd32,
        srcl_v=rs1_32,
        srcr_v=rs2_32,
        srcr_type_v=srcr_type_32,
        shamt_v=shamt5_32,
    )

    cond = in32 & masked_eq(m, insn32, mask=0x0000707F, match=0x00006009)
    (op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm) = _decode_set_if(m, cond, op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm,
        op_v=OP_LWU,
        len_v=4,
        regdst_v=rd32,
        srcl_v=rs1_32,
        srcr_v=rs2_32,
        srcr_type_v=srcr_type_32,
        shamt_v=shamt5_32,
    )

    cond = in32 & masked_eq(m, insn32, mask=0x0000707F, match=0x00003009)
    (op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm) = _decode_set_if(m, cond, op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm,
        op_v=OP_LD,
        len_v=4,
        regdst_v=rd32,
        srcl_v=rs1_32,
        srcr_v=rs2_32,
        srcr_type_v=srcr_type_32,
        shamt_v=shamt5_32,
    )

    cond = in32 & masked_eq(m, insn32, mask=0x0000707F, match=0x00003019)
    (op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm) = _decode_set_if(m, cond, op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm, op_v=OP_LDI, len_v=4, regdst_v=rd32, srcl_v=rs1_32, imm_v=imm12_s64)

    # Block split command descriptors (CMD IQ -> BISQ).
    cond = in32 & masked_eq(m, insn32, mask=0x0000007F, match=0x00000003)
    (op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm) = _decode_set_if(m, cond, op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm, op_v=OP_BTEXT, len_v=4, srcl_v=rs1_32, imm_v=simm25_s64)

    cond = in32 & masked_eq(m, insn32, mask=0x0600707F, match=0x00000013)
    (op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm) = _decode_set_if(m, cond, op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm, op_v=OP_BIOR, len_v=4, regdst_v=rd32, srcl_v=rs1_32, srcr_v=rs2_32, srcp_v=srcp_32)

    cond = in32 & masked_eq(m, insn32, mask=0x0000607F, match=0x00004013)
    (op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm) = _decode_set_if(m, cond, op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm, op_v=OP_BLOAD, len_v=4, regdst_v=rd32, srcl_v=rs1_32, srcr_v=rs2_32, srcp_v=srcp_32)

    cond = in32 & masked_eq(m, insn32, mask=0x0000607F, match=0x00006013)
    (op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm) = _decode_set_if(m, cond, op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm, op_v=OP_BSTORE, len_v=4, regdst_v=rd32, srcl_v=rs1_32, srcr_v=rs2_32, srcp_v=srcp_32)

    cond = in32 & masked_eq(m, insn32, mask=0x00007FFF, match=0x00001001)
    (op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm) = _decode_set_if(m, cond, op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm, op_v=OP_BSTART_STD_FALL, len_v=4, regdst_v=REG_INVALID)

    cond = in32 & masked_eq(m, insn32, mask=0x00007FFF, match=0x00002001)
    (op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm) = _decode_set_if(m, cond, op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm, op_v=OP_BSTART_STD_DIRECT, len_v=4, regdst_v=REG_INVALID, imm_v=simm17_s64.shl(amount=1))

    cond = in32 & masked_eq(m, insn32, mask=0x00007FFF, match=0x00003001)
    (op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm) = _decode_set_if(m, cond, op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm, op_v=OP_BSTART_STD_COND, len_v=4, regdst_v=REG_INVALID, imm_v=simm17_s64.shl(amount=1))

    # BSTART.STD IND/ICALL/RET: no embedded target; requires SETC.TGT within the block.
    # For these, reuse OP_C_BSTART_STD internal op and carry BrType in `imm`.
    cond = in32 & masked_eq(m, insn32, mask=0x00007FFF, match=0x00005001)
    (op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm) = _decode_set_if(m, cond, op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm, op_v=OP_C_BSTART_STD, len_v=4, regdst_v=REG_INVALID, imm_v=5)

    cond = in32 & masked_eq(m, insn32, mask=0x00007FFF, match=0x00006001)
    (op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm) = _decode_set_if(m, cond, op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm, op_v=OP_C_BSTART_STD, len_v=4, regdst_v=REG_INVALID, imm_v=6)

    cond = in32 & masked_eq(m, insn32, mask=0x00007FFF, match=0x00007001)
    (op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm) = _decode_set_if(m, cond, op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm, op_v=OP_C_BSTART_STD, len_v=4, regdst_v=REG_INVALID, imm_v=7)

    cond = in32 & masked_eq(m, insn32, mask=0x0000707F, match=0x00004025)
    (op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm) = _decode_set_if(m, cond, op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm,
        op_v=OP_XORW,
        len_v=4,
        regdst_v=rd32,
        srcl_v=rs1_32,
        srcr_v=rs2_32,
        srcr_type_v=srcr_type_32,
        shamt_v=shamt5_32,
    )

    cond = in32 & masked_eq(m, insn32, mask=0x0000707F, match=0x00002025)
    (op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm) = _decode_set_if(m, cond, op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm,
        op_v=OP_ANDW,
        len_v=4,
        regdst_v=rd32,
        srcl_v=rs1_32,
        srcr_v=rs2_32,
        srcr_type_v=srcr_type_32,
        shamt_v=shamt5_32,
    )

    cond = in32 & masked_eq(m, insn32, mask=0x0000707F, match=0x00003025)
    (op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm) = _decode_set_if(m, cond, op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm,
        op_v=OP_ORW,
        len_v=4,
        regdst_v=rd32,
        srcl_v=rs1_32,
        srcr_v=rs2_32,
        srcr_type_v=srcr_type_32,
        shamt_v=shamt5_32,
    )

    cond = in32 & masked_eq(m, insn32, mask=0x0000707F, match=0x00000025)
    (op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm) = _decode_set_if(m, cond, op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm,
        op_v=OP_ADDW,
        len_v=4,
        regdst_v=rd32,
        srcl_v=rs1_32,
        srcr_v=rs2_32,
        srcr_type_v=srcr_type_32,
        shamt_v=shamt5_32,
    )

    cond = in32 & masked_eq(m, insn32, mask=0x0000707F, match=0x00001025)
    (op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm) = _decode_set_if(m, cond, op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm,
        op_v=OP_SUBW,
        len_v=4,
        regdst_v=rd32,
        srcl_v=rs1_32,
        srcr_v=rs2_32,
        srcr_type_v=srcr_type_32,
        shamt_v=shamt5_32,
    )

    # 32-bit register shifts.
    cond = in32 & masked_eq(m, insn32, mask=0x0000707F, match=0x00007025)
    (op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm) = _decode_set_if(m, cond, op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm, op_v=OP_SLLW, len_v=4, regdst_v=rd32, srcl_v=rs1_32, srcr_v=rs2_32)

    cond = in32 & masked_eq(m, insn32, mask=0x0000707F, match=0x00005025)
    (op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm) = _decode_set_if(m, cond, op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm, op_v=OP_SRLW, len_v=4, regdst_v=rd32, srcl_v=rs1_32, srcr_v=rs2_32)

    cond = in32 & masked_eq(m, insn32, mask=0x0000707F, match=0x00006025)
    (op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm) = _decode_set_if(m, cond, op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm, op_v=OP_SRAW, len_v=4, regdst_v=rd32, srcl_v=rs1_32, srcr_v=rs2_32)

    cond = in32 & masked_eq(m, insn32, mask=0x0000707F, match=0x00000077)
    (op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm) = _decode_set_if(m, cond, op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm,
        op_v=OP_CSEL,
        len_v=4,
        regdst_v=rd32,
        srcl_v=rs1_32,
        srcr_v=rs2_32,
        srcr_type_v=srcr_type_32,
        srcp_v=srcp_32,
    )

    # Stores (immediate offset).
    cond = in32 & masked_eq(m, insn32, mask=0x0000707F, match=0x00000059)
    (op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm) = _decode_set_if(m, cond, op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm, op_v=OP_SBI, len_v=4, srcl_v=rs1_32, srcr_v=rs2_32, imm_v=simm12_s64)

    cond = in32 & masked_eq(m, insn32, mask=0x0000707F, match=0x00001059)
    (op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm) = _decode_set_if(m, cond, op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm, op_v=OP_SHI, len_v=4, srcl_v=rs1_32, srcr_v=rs2_32, imm_v=simm12_s64)

    cond = in32 & masked_eq(m, insn32, mask=0x0000707F, match=0x00002059)
    (op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm) = _decode_set_if(m, cond, op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm, op_v=OP_SWI, len_v=4, srcl_v=rs1_32, srcr_v=rs2_32, imm_v=simm12_s64)

    cond = in32 & masked_eq(m, insn32, mask=0x0000707F, match=0x00003059)
    (op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm) = _decode_set_if(m, cond, op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm, op_v=OP_SDI, len_v=4, srcl_v=rs1_32, srcr_v=rs2_32, imm_v=simm12_s64)

    # Stores (indexed). Encoding uses SrcD in bits[31:27], SrcL base in bits[19:15], SrcR idx in bits[24:20].
    # We map: srcp=value (SrcD), srcl=base (SrcL), srcr=index (SrcR), srcr_type=SrcRType, shamt=fixed scale.
    cond = in32 & masked_eq(m, insn32, mask=0x00007FFF, match=0x00000049)
    (op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm) = _decode_set_if(m, cond, op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm,
        op_v=OP_SB,
        len_v=4,
        srcp_v=insn32[27:32],
        srcl_v=rs1_32,
        srcr_v=rs2_32,
        srcr_type_v=srcr_type_32,
        shamt_v=0,
    )

    cond = in32 & masked_eq(m, insn32, mask=0x00007FFF, match=0x00001049)
    (op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm) = _decode_set_if(m, cond, op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm,
        op_v=OP_SH,
        len_v=4,
        srcp_v=insn32[27:32],
        srcl_v=rs1_32,
        srcr_v=rs2_32,
        srcr_type_v=srcr_type_32,
        shamt_v=1,
    )

    cond = in32 & masked_eq(m, insn32, mask=0x00007FFF, match=0x00002049)
    (op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm) = _decode_set_if(m, cond, op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm,
        op_v=OP_SW,
        len_v=4,
        srcp_v=insn32[27:32],
        srcl_v=rs1_32,
        srcr_v=rs2_32,
        srcr_type_v=srcr_type_32,
        shamt_v=2,
    )

    cond = in32 & masked_eq(m, insn32, mask=0x00007FFF, match=0x00003049)
    (op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm) = _decode_set_if(m, cond, op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm,
        op_v=OP_SD,
        len_v=4,
        srcp_v=insn32[27:32],
        srcl_v=rs1_32,
        srcr_v=rs2_32,
        srcr_type_v=srcr_type_32,
        shamt_v=3,
    )

    cond = in32 & masked_eq(m, insn32, mask=0x0000707F, match=0x00002019)
    (op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm) = _decode_set_if(m, cond, op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm, op_v=OP_LWI, len_v=4, regdst_v=rd32, srcl_v=rs1_32, imm_v=imm12_s64)

    cond = in32 & masked_eq(m, insn32, mask=0x0000707F, match=0x00000035)
    (op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm) = _decode_set_if(m, cond, op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm, op_v=OP_ADDIW, len_v=4, regdst_v=rd32, srcl_v=rs1_32, imm_v=imm12_u64)

    cond = in32 & masked_eq(m, insn32, mask=0x0000707F, match=0x00001035)
    (op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm) = _decode_set_if(m, cond, op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm, op_v=OP_SUBIW, len_v=4, regdst_v=rd32, srcl_v=rs1_32, imm_v=imm12_u64)

    cond = in32 & masked_eq(m, insn32, mask=0x0000707F, match=0x00000015)
    (op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm) = _decode_set_if(m, cond, op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm, op_v=OP_ADDI, len_v=4, regdst_v=rd32, srcl_v=rs1_32, imm_v=imm12_u64)

    cond = in32 & masked_eq(m, insn32, mask=0x0000707F, match=0x00001015)
    (op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm) = _decode_set_if(m, cond, op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm, op_v=OP_SUBI, len_v=4, regdst_v=rd32, srcl_v=rs1_32, imm_v=imm12_u64)

    cond = in32 & masked_eq(m, insn32, mask=0xF800707F, match=0x00000045)
    (op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm) = _decode_set_if(m, cond, op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm, op_v=OP_CMP_EQ, len_v=4, regdst_v=rd32, srcl_v=rs1_32, srcr_v=rs2_32, srcr_type_v=srcr_type_32)

    cond = in32 & masked_eq(m, insn32, mask=0xF800707F, match=0x00001045)
    (op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm) = _decode_set_if(m, cond, op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm, op_v=OP_CMP_NE, len_v=4, regdst_v=rd32, srcl_v=rs1_32, srcr_v=rs2_32, srcr_type_v=srcr_type_32)

    cond = in32 & masked_eq(m, insn32, mask=0xF800707F, match=0x00004045)
    (op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm) = _decode_set_if(m, cond, op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm, op_v=OP_CMP_LT, len_v=4, regdst_v=rd32, srcl_v=rs1_32, srcr_v=rs2_32, srcr_type_v=srcr_type_32)

    # Floating-point compares (SrcType in bits[26:25]: 0=fd, 1=fs).
    cond = in32 & masked_eq(m, insn32, mask=0x0000707F, match=0x0000005B)
    (op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm) = _decode_set_if(m, cond, op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm, op_v=OP_FEQ, len_v=4, regdst_v=rd32, srcl_v=rs1_32, srcr_v=rs2_32, srcr_type_v=srcr_type_32)

    cond = in32 & masked_eq(m, insn32, mask=0x0000707F, match=0x0000205B)
    (op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm) = _decode_set_if(m, cond, op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm, op_v=OP_FLT, len_v=4, regdst_v=rd32, srcl_v=rs1_32, srcr_v=rs2_32, srcr_type_v=srcr_type_32)

    cond = in32 & masked_eq(m, insn32, mask=0x0000707F, match=0x0000305B)
    (op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm) = _decode_set_if(m, cond, op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm, op_v=OP_FGE, len_v=4, regdst_v=rd32, srcl_v=rs1_32, srcr_v=rs2_32, srcr_type_v=srcr_type_32)

    cond = in32 & masked_eq(m, insn32, mask=0xF0FFFFFF, match=0x0010102B)
    (op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm) = _decode_set_if(m, cond, op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm, op_v=OP_EBREAK, len_v=4)

    # Opcode overlap group (QEMU: insn32.decode):
    # - SETRET: specialized ADDTPC encoding with rd=RA (x10), but different semantics.
    # - ADDTPC: PC-relative page base for any other rd.
    cond = in32 & masked_eq(m, insn32, mask=0x0000007F, match=0x00000007)
    (op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm) = _decode_set_if(m, cond, op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm, op_v=OP_ADDTPC, len_v=4, regdst_v=rd32, imm_v=imm20_s64.shl(amount=12))
    (op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm) = _decode_set_if(m, cond & rd32.__eq__(10), op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm, op_v=OP_SETRET, len_v=4, regdst_v=10, imm_v=imm20_u64.shl(amount=1))

    cond = in32 & masked_eq(m, insn32, mask=0x00007FFF, match=0x00004001)
    (op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm) = _decode_set_if(m, cond, op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm, op_v=OP_BSTART_STD_CALL, len_v=4, regdst_v=REG_INVALID, imm_v=simm17_s64.shl(amount=1))

    # --- 48-bit HL decode (highest priority overall) ---
    hl_bstart_hi12 = pfx16[4:16]._zext(width=64)
    hl_bstart_lo17 = insn48[31:48]._zext(width=64)
    hl_bstart_simm_hw = (hl_bstart_hi12.shl(amount=18) | hl_bstart_lo17.shl(amount=1))._trunc(width=30)._sext(width=64)
    # HL.BSTART simm is in halfwords (QEMU: target = PC + (simm << 1)).
    # Decode emits a byte offset.
    hl_bstart_off = hl_bstart_simm_hw

    cond = is_hl & masked_eq(m, insn48, mask=0x00007FFF000F, match=0x00001001000E)
    (op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm) = _decode_set_if(m, cond, op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm, op_v=OP_BSTART_STD_FALL, len_v=6, regdst_v=REG_INVALID)

    cond = is_hl & masked_eq(m, insn48, mask=0x00007FFF000F, match=0x00002001000E)
    (op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm) = _decode_set_if(m, cond, op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm, op_v=OP_BSTART_STD_DIRECT, len_v=6, regdst_v=REG_INVALID, imm_v=hl_bstart_off)

    cond = is_hl & masked_eq(m, insn48, mask=0x00007FFF000F, match=0x00003001000E)
    (op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm) = _decode_set_if(m, cond, op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm, op_v=OP_BSTART_STD_COND, len_v=6, regdst_v=REG_INVALID, imm_v=hl_bstart_off)

    cond = is_hl & masked_eq(m, insn48, mask=0x00007FFF000F, match=0x00004001000E)
    (op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm) = _decode_set_if(m, cond, op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm, op_v=OP_BSTART_STD_CALL, len_v=6, regdst_v=REG_INVALID, imm_v=hl_bstart_off)

    # HL.<load>.PCR: PC-relative load, funct3 encodes width/signedness.
    cond = is_hl & masked_eq(m, insn48, mask=0x0000007F000F, match=0x00000039000E)
    hl_load_regdst = insn48[23:28]
    hl_load_simm_hi12 = pfx16[4:16]._zext(width=64)
    hl_load_simm_lo17 = insn48[31:48]._zext(width=64)
    hl_load_simm29 = (hl_load_simm_hi12.shl(amount=17) | hl_load_simm_lo17)._trunc(width=29)._sext(width=64)
    hl_load_funct3 = insn48[28:31]
    (op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm) = _decode_set_if(m, cond, op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm, len_v=6, regdst_v=hl_load_regdst, imm_v=hl_load_simm29)
    (op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm) = _decode_set_if(m, cond, op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm, op_v=OP_HL_LW_PCR)
    (op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm) = _decode_set_if(m, cond & hl_load_funct3.__eq__(0), op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm, op_v=OP_HL_LB_PCR)
    (op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm) = _decode_set_if(m, cond & hl_load_funct3.__eq__(1), op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm, op_v=OP_HL_LH_PCR)
    (op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm) = _decode_set_if(m, cond & hl_load_funct3.__eq__(2), op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm, op_v=OP_HL_LW_PCR)
    (op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm) = _decode_set_if(m, cond & hl_load_funct3.__eq__(3), op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm, op_v=OP_HL_LD_PCR)
    (op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm) = _decode_set_if(m, cond & hl_load_funct3.__eq__(4), op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm, op_v=OP_HL_LBU_PCR)
    (op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm) = _decode_set_if(m, cond & hl_load_funct3.__eq__(5), op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm, op_v=OP_HL_LHU_PCR)
    (op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm) = _decode_set_if(m, cond & hl_load_funct3.__eq__(6), op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm, op_v=OP_HL_LWU_PCR)

    # HL.<store>.PCR: PC-relative store, funct3 encodes width.
    cond = is_hl & masked_eq(m, insn48, mask=0x0000007F000F, match=0x00000069000E)
    hl_store_srcl = insn48[31:36]
    hl_store_simm_hi12 = pfx16[4:16]._zext(width=64)
    hl_store_simm_mid5 = insn48[23:28]._zext(width=64)
    hl_store_simm_lo12 = insn48[36:48]._zext(width=64)
    hl_store_simm29 = (
        hl_store_simm_hi12.shl(amount=17) | hl_store_simm_mid5.shl(amount=12) | hl_store_simm_lo12
    )._trunc(width=29)._sext(width=64)
    hl_store_funct3 = insn48[28:31]
    (op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm) = _decode_set_if(m, cond, op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm, len_v=6, srcl_v=hl_store_srcl, imm_v=hl_store_simm29)
    (op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm) = _decode_set_if(m, cond, op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm, op_v=OP_HL_SW_PCR)
    (op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm) = _decode_set_if(m, cond & hl_store_funct3.__eq__(0), op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm, op_v=OP_HL_SB_PCR)
    (op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm) = _decode_set_if(m, cond & hl_store_funct3.__eq__(1), op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm, op_v=OP_HL_SH_PCR)
    (op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm) = _decode_set_if(m, cond & hl_store_funct3.__eq__(2), op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm, op_v=OP_HL_SW_PCR)
    (op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm) = _decode_set_if(m, cond & hl_store_funct3.__eq__(3), op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm, op_v=OP_HL_SD_PCR)

    # HL.ANDI: extended immediate variant of ANDI (simm24).
    cond = is_hl & masked_eq(m, insn48, mask=0x0000707F000F, match=0x00002015000E)
    imm_hi12 = pfx16[4:16]
    imm_lo12 = main32[20:32]
    imm24 = imm_hi12._zext(width=24).shl(amount=12) | imm_lo12._zext(width=24)
    (op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm) = _decode_set_if(m, cond, op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm, op_v=OP_ANDI, len_v=6, regdst_v=rd_hl, srcl_v=main32[15:20], imm_v=imm24._sext(width=64))

    cond = is_hl & masked_eq(m, insn48, mask=0x0000007F000F, match=0x00000017000E)
    (op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm) = _decode_set_if(m, cond, op, len_bytes, regdst, srcl, srcr, srcr_type, shamt, srcp, imm, op_v=OP_HL_LUI, len_v=6, regdst_v=rd_hl, imm_v=imm_hl_lui)

    return Decode(
        op=op,
        len_bytes=len_bytes,
        regdst=regdst,
        srcl=srcl,
        srcr=srcr,
        srcr_type=srcr_type,
        shamt=shamt,
        srcp=srcp,
        imm=imm,
    )


@function
def decode_bundle_8B(m: Circuit, window: Wire) -> DecodeBundle:
    """Decode up to 4 sequential instructions from an 8-byte fetch window.

    Returns per-slot byte offsets (from the window base) and a total length
    suitable for advancing the fetch PC.
    """
    c = m.const

    z4 = c(0, width=4)
    b8 = c(8, width=4)
    b2 = c(2, width=4)

    # Slot 0.
    win0 = window
    dec0 = decode_window(m, win0)
    len0_4 = dec0.len_bytes._zext(width=4)
    off0 = z4
    v0 = ~len0_4.__eq__(z4)

    # Template macro blocks (FENTRY/FEXIT/FRET.*) must execute as standalone
    # blocks at the front-end, so do not include following instructions in the
    # same fetch bundle.
    is_macro0 = (
        dec0.op.__eq__(OP_FENTRY)
        | dec0.op.__eq__(OP_FEXIT)
        | dec0.op.__eq__(OP_FRET_RA)
        | dec0.op.__eq__(OP_FRET_STK)
    )

    # Slot 1.
    sh0 = len0_4._zext(width=6).shl(amount=3)
    win1 = lshr_var(m, win0, sh0)
    dec1 = decode_window(m, win1)
    len1_4 = dec1.len_bytes._zext(width=4)
    off1 = len0_4
    rem0 = b8 - len0_4
    v1 = v0 & (~is_macro0) & rem0.uge(b2) & (~len1_4.__eq__(z4)) & len1_4.ule(rem0)

    # Slot 2.
    off2 = off1 + len1_4
    sh1 = off2._zext(width=6).shl(amount=3)
    win2 = lshr_var(m, win0, sh1)
    dec2 = decode_window(m, win2)
    len2_4 = dec2.len_bytes._zext(width=4)
    rem1 = rem0 - len1_4
    v_slot2 = v1 & rem1.uge(b2) & (~len2_4.__eq__(z4)) & len2_4.ule(rem1)

    # Slot 3.
    off3 = off2 + len2_4
    sh2 = off3._zext(width=6).shl(amount=3)
    win3 = lshr_var(m, win0, sh2)
    dec3 = decode_window(m, win3)
    len3_4 = dec3.len_bytes._zext(width=4)
    rem2 = rem1 - len2_4
    v3 = v_slot2 & rem2.uge(b2) & (~len3_4.__eq__(z4)) & len3_4.ule(rem2)

    total = len0_4
    total = v1._select_internal(off2, total)
    total = v_slot2._select_internal(off3, total)
    total = v3._select_internal(off3 + len3_4, total)

    return DecodeBundle(
        valid=[v0, v1, v_slot2, v3],
        off_bytes=[off0, off1, off2, off3],
        dec=[dec0, dec1, dec2, dec3],
        total_len_bytes=total,
    )


@dataclass(frozen=True)
class DecodeInfo:
    op_id: int
    symbol: str
    mnemonic: str
    major_cat: str
    minor_cat: str
    insn_len: int
    mask: int
    match: int
    rd_kind: str
    rs1_kind: str
    rs2_kind: str
    imm_kind: str
    block_kind: str
    cmd_kind: str
    flags: str


def _to_decode_info(meta) -> DecodeInfo | None:
    if meta is None:
        return None
    return DecodeInfo(
        op_id=meta.op_id,
        symbol=meta.symbol,
        mnemonic=meta.mnemonic,
        major_cat=meta.major_cat,
        minor_cat=meta.minor_cat,
        insn_len=meta.insn_len,
        mask=meta.mask,
        match=meta.match,
        rd_kind=meta.rd_kind,
        rs1_kind=meta.rs1_kind,
        rs2_kind=meta.rs2_kind,
        imm_kind=meta.imm_kind,
        block_kind=meta.block_kind,
        cmd_kind=meta.cmd_kind,
        flags=meta.flags,
    )


def decode_info_from_word(insn_word: int) -> DecodeInfo | None:
    """Software decode metadata helper aligned with QEMU decode trees.

    This path is used for parity/debug tooling and returns the shared contract:
    op/category/mask-match/operand-shape metadata.
    """
    word = insn_word & ((1 << 64) - 1)

    # 48-bit HL packed form has low nibble 0xE and takes priority.
    if (word & 0xF) == 0xE:
        hit48 = _to_decode_info(decode48_meta(word))
        if hit48 is not None:
            return hit48

    low16 = word & 0xFFFF
    is32_or_wider = (low16 & 0x1) != 0
    if is32_or_wider:
        hit64 = _to_decode_info(decode64_meta(word))
        if hit64 is not None:
            return hit64
        hit32 = _to_decode_info(decode32_meta(word))
        if hit32 is not None:
            return hit32
        return None

    return _to_decode_info(decode16_meta(low16))
