from __future__ import annotations

from dataclasses import dataclass

from pycircuit import Circuit, Wire, function

from .isa import (
    OP_ADDTPC,
    OP_ADDI,
    OP_ADDIW,
    OP_ADD,
    OP_ADDW,
    OP_AND,
    OP_ANDI,
    OP_ANDIW,
    OP_ANDW,
    OP_BSTART_STD_CALL,
    OP_BSTART_STD_COND,
    OP_BSTART_STD_DIRECT,
    OP_BSTART_STD_FALL,
    OP_BXS,
    OP_BXU,
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
    OP_SETC_ANDI,
    OP_SETC_EQ,
    OP_SETC_EQI,
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
)
from .util import Consts, ashr_var, lshr_var, shl_var


@dataclass(frozen=True)
class ExecOut:
    alu: Wire
    is_load: Wire
    is_store: Wire
    size: Wire
    addr: Wire
    wdata: Wire


def exec_uop_comb(
    m: Circuit,
    *,
    op: Wire,
    pc: Wire,
    imm: Wire,
    srcl_val: Wire,
    srcr_val: Wire,
    srcr_type: Wire,
    shamt: Wire,
    srcp_val: Wire,
    consts: Consts,
) -> ExecOut:
    """Combinational exec for python-mode builders (no `if Wire:` / no `==` on Wire).

    The OOO core builder (`ooo/linxcore.py`) executes as a normal Python helper
    during JIT compilation. That means any use of `if <Wire>:` will raise, and
    `wire == const` is Python dataclass equality (not a hardware compare).

    This function is written in "select-chain" style so it can be called from
    such helpers. It is still safe to inline in JIT mode if desired.
    """
    c = m.const

    z1 = consts.zero1
    z4 = consts.zero4
    z64 = consts.zero64
    one1 = consts.one1
    one64 = consts.one64

    # Sizes (bytes).
    sz1 = c(1, width=4)
    sz2 = c(2, width=4)
    sz4 = c(4, width=4)
    sz8 = c(8, width=4)

    pc = pc.out()
    op = op.out()
    imm = imm.out()
    srcl_val = srcl_val.out()
    srcr_val = srcr_val.out()
    srcr_type = srcr_type.out()
    shamt = shamt.out()
    srcp_val = srcp_val.out()

    # --- op predicates ---
    op_c_bstart_std = op.__eq__(OP_C_BSTART_STD)
    op_c_bstart_cond = op.__eq__(OP_C_BSTART_COND)
    op_c_bstart_direct = op.__eq__(OP_C_BSTART_DIRECT)
    op_bstart_std_fall = op.__eq__(OP_BSTART_STD_FALL)
    op_bstart_std_direct = op.__eq__(OP_BSTART_STD_DIRECT)
    op_bstart_std_cond = op.__eq__(OP_BSTART_STD_COND)
    op_bstart_std_call = op.__eq__(OP_BSTART_STD_CALL)
    op_fentry = op.__eq__(OP_FENTRY)
    op_fexit = op.__eq__(OP_FEXIT)
    op_fret_ra = op.__eq__(OP_FRET_RA)
    op_fret_stk = op.__eq__(OP_FRET_STK)
    op_c_movr = op.__eq__(OP_C_MOVR)
    op_c_movi = op.__eq__(OP_C_MOVI)
    op_c_setret = op.__eq__(OP_C_SETRET)
    op_c_setc_eq = op.__eq__(OP_C_SETC_EQ)
    op_c_setc_ne = op.__eq__(OP_C_SETC_NE)
    op_c_setc_tgt = op.__eq__(OP_C_SETC_TGT)
    op_setret = op.__eq__(OP_SETRET)
    op_addtpc = op.__eq__(OP_ADDTPC)
    op_lui = op.__eq__(OP_LUI)
    op_add = op.__eq__(OP_ADD)
    op_sub = op.__eq__(OP_SUB)
    op_and = op.__eq__(OP_AND)
    op_or = op.__eq__(OP_OR)
    op_xor = op.__eq__(OP_XOR)
    op_addi = op.__eq__(OP_ADDI)
    op_subi = op.__eq__(OP_SUBI)
    op_andi = op.__eq__(OP_ANDI)
    op_ori = op.__eq__(OP_ORI)
    op_addiw = op.__eq__(OP_ADDIW)
    op_subiw = op.__eq__(OP_SUBIW)
    op_andiw = op.__eq__(OP_ANDIW)
    op_oriw = op.__eq__(OP_ORIW)
    op_xori = op.__eq__(OP_XORI)
    op_xoriw = op.__eq__(OP_XORIW)
    op_mul = op.__eq__(OP_MUL)
    op_mulw = op.__eq__(OP_MULW)
    op_madd = op.__eq__(OP_MADD)
    op_maddw = op.__eq__(OP_MADDW)
    op_div = op.__eq__(OP_DIV)
    op_divu = op.__eq__(OP_DIVU)
    op_divw = op.__eq__(OP_DIVW)
    op_divuw = op.__eq__(OP_DIVUW)
    op_rem = op.__eq__(OP_REM)
    op_remu = op.__eq__(OP_REMU)
    op_remw = op.__eq__(OP_REMW)
    op_remuw = op.__eq__(OP_REMUW)
    op_sll = op.__eq__(OP_SLL)
    op_srl = op.__eq__(OP_SRL)
    op_sra = op.__eq__(OP_SRA)
    op_slli = op.__eq__(OP_SLLI)
    op_srli = op.__eq__(OP_SRLI)
    op_srai = op.__eq__(OP_SRAI)
    op_slliw = op.__eq__(OP_SLLIW)
    op_sllw = op.__eq__(OP_SLLW)
    op_srlw = op.__eq__(OP_SRLW)
    op_sraw = op.__eq__(OP_SRAW)
    op_sraiw = op.__eq__(OP_SRAIW)
    op_srliw = op.__eq__(OP_SRLIW)
    op_bxs = op.__eq__(OP_BXS)
    op_bxu = op.__eq__(OP_BXU)
    op_addw = op.__eq__(OP_ADDW)
    op_subw = op.__eq__(OP_SUBW)
    op_orw = op.__eq__(OP_ORW)
    op_andw = op.__eq__(OP_ANDW)
    op_xorw = op.__eq__(OP_XORW)
    op_cmp_eq = op.__eq__(OP_CMP_EQ)
    op_cmp_ne = op.__eq__(OP_CMP_NE)
    op_cmp_lt = op.__eq__(OP_CMP_LT)
    op_cmp_eqi = op.__eq__(OP_CMP_EQI)
    op_cmp_nei = op.__eq__(OP_CMP_NEI)
    op_cmp_andi = op.__eq__(OP_CMP_ANDI)
    op_cmp_ori = op.__eq__(OP_CMP_ORI)
    op_cmp_lti = op.__eq__(OP_CMP_LTI)
    op_cmp_ltu = op.__eq__(OP_CMP_LTU)
    op_cmp_ltui = op.__eq__(OP_CMP_LTUI)
    op_cmp_gei = op.__eq__(OP_CMP_GEI)
    op_cmp_geui = op.__eq__(OP_CMP_GEUI)
    op_feq = op.__eq__(OP_FEQ)
    op_flt = op.__eq__(OP_FLT)
    op_fge = op.__eq__(OP_FGE)
    op_setc_geui = op.__eq__(OP_SETC_GEUI)
    op_setc_eq = op.__eq__(OP_SETC_EQ)
    op_setc_ne = op.__eq__(OP_SETC_NE)
    op_setc_and = op.__eq__(OP_SETC_AND)
    op_setc_or = op.__eq__(OP_SETC_OR)
    op_setc_lt = op.__eq__(OP_SETC_LT)
    op_setc_ltu = op.__eq__(OP_SETC_LTU)
    op_setc_ge = op.__eq__(OP_SETC_GE)
    op_setc_geu = op.__eq__(OP_SETC_GEU)
    op_setc_eqi = op.__eq__(OP_SETC_EQI)
    op_setc_nei = op.__eq__(OP_SETC_NEI)
    op_setc_andi = op.__eq__(OP_SETC_ANDI)
    op_setc_ori = op.__eq__(OP_SETC_ORI)
    op_setc_lti = op.__eq__(OP_SETC_LTI)
    op_setc_gei = op.__eq__(OP_SETC_GEI)
    op_setc_ltui = op.__eq__(OP_SETC_LTUI)
    op_csel = op.__eq__(OP_CSEL)
    op_hl_lui = op.__eq__(OP_HL_LUI)
    op_hl_lb_pcr = op.__eq__(OP_HL_LB_PCR)
    op_hl_lbu_pcr = op.__eq__(OP_HL_LBU_PCR)
    op_hl_lh_pcr = op.__eq__(OP_HL_LH_PCR)
    op_hl_lhu_pcr = op.__eq__(OP_HL_LHU_PCR)
    op_hl_lw_pcr = op.__eq__(OP_HL_LW_PCR)
    op_hl_lwu_pcr = op.__eq__(OP_HL_LWU_PCR)
    op_hl_ld_pcr = op.__eq__(OP_HL_LD_PCR)
    op_hl_sb_pcr = op.__eq__(OP_HL_SB_PCR)
    op_hl_sh_pcr = op.__eq__(OP_HL_SH_PCR)
    op_hl_sw_pcr = op.__eq__(OP_HL_SW_PCR)
    op_hl_sd_pcr = op.__eq__(OP_HL_SD_PCR)
    op_lwi = op.__eq__(OP_LWI)
    op_c_lwi = op.__eq__(OP_C_LWI)
    op_lbi = op.__eq__(OP_LBI)
    op_lbui = op.__eq__(OP_LBUI)
    op_lhi = op.__eq__(OP_LHI)
    op_lhui = op.__eq__(OP_LHUI)
    op_lwui = op.__eq__(OP_LWUI)
    op_lb = op.__eq__(OP_LB)
    op_lbu = op.__eq__(OP_LBU)
    op_lh = op.__eq__(OP_LH)
    op_lhu = op.__eq__(OP_LHU)
    op_lw = op.__eq__(OP_LW)
    op_lwu = op.__eq__(OP_LWU)
    op_ld = op.__eq__(OP_LD)
    op_ldi = op.__eq__(OP_LDI)
    op_c_add = op.__eq__(OP_C_ADD)
    op_c_addi = op.__eq__(OP_C_ADDI)
    op_c_sub = op.__eq__(OP_C_SUB)
    op_c_and = op.__eq__(OP_C_AND)
    op_c_or = op.__eq__(OP_C_OR)
    op_c_ldi = op.__eq__(OP_C_LDI)
    op_sbi = op.__eq__(OP_SBI)
    op_shi = op.__eq__(OP_SHI)
    op_swi = op.__eq__(OP_SWI)
    op_c_swi = op.__eq__(OP_C_SWI)
    op_c_sdi = op.__eq__(OP_C_SDI)
    op_sb = op.__eq__(OP_SB)
    op_sh = op.__eq__(OP_SH)
    op_sw = op.__eq__(OP_SW)
    op_sd = op.__eq__(OP_SD)
    op_c_sext_w = op.__eq__(OP_C_SEXT_W)
    op_c_zext_w = op.__eq__(OP_C_ZEXT_W)
    op_sdi = op.__eq__(OP_SDI)

    # Defaults.
    alu = z64
    is_load = z1
    is_store = z1
    size = z4
    addr = z64
    wdata = z64

    # --- SrcR modifiers (srcr_type is a 2b mode code) ---
    st0 = srcr_type.__eq__(0)
    st1 = srcr_type.__eq__(1)
    st2 = srcr_type.__eq__(2)

    srcr_addsub = srcr_val
    srcr_addsub = st0._select_internal(srcr_val._trunc(width=32)._sext(width=64), srcr_addsub)
    srcr_addsub = st1._select_internal(srcr_val._trunc(width=32)._zext(width=64), srcr_addsub)
    srcr_addsub = st2._select_internal((~srcr_val) + 1, srcr_addsub)

    srcr_logic = srcr_val
    srcr_logic = st0._select_internal(srcr_val._trunc(width=32)._sext(width=64), srcr_logic)
    srcr_logic = st1._select_internal(srcr_val._trunc(width=32)._zext(width=64), srcr_logic)
    srcr_logic = st2._select_internal(~srcr_val, srcr_logic)

    srcr_addsub_nosh = srcr_addsub
    srcr_addsub_shl = shl_var(m, srcr_addsub, shamt)
    srcr_logic_shl = shl_var(m, srcr_logic, shamt)

    idx_mod = srcr_val._trunc(width=32)._zext(width=64)
    idx_mod = st0._select_internal(srcr_val._trunc(width=32)._sext(width=64), idx_mod)
    idx_mod_shl = shl_var(m, idx_mod, shamt)

    # Common offsets.
    off_w = imm.shl(amount=2)
    h_off = imm.shl(amount=1)
    ldi_off = imm.shl(amount=3)

    # --- boundary/macro markers (treat as immediate producers) ---
    is_marker = (
        op_c_bstart_std
        | op_c_bstart_cond
        | op_c_bstart_direct
        | op_bstart_std_fall
        | op_bstart_std_direct
        | op_bstart_std_cond
        | op_bstart_std_call
        | op_fentry
        | op_fexit
        | op_fret_ra
        | op_fret_stk
    )
    alu = is_marker._select_internal(imm, alu)

    # --- basic ALU ops ---
    alu = op_c_movr._select_internal(srcl_val, alu)
    alu = op_c_movi._select_internal(imm, alu)
    alu = op_c_setret._select_internal(pc + imm, alu)

    setc_eq = srcl_val.__eq__(srcr_val)._select_internal(one64, z64)
    alu = op_c_setc_eq._select_internal(setc_eq, alu)
    alu = op_c_setc_ne._select_internal((~srcl_val.__eq__(srcr_val))._select_internal(one64, z64), alu)
    alu = op_c_setc_tgt._select_internal(srcl_val, alu)

    pc_page = pc & 0xFFFF_FFFF_FFFF_F000
    alu = op_addtpc._select_internal(pc_page + imm, alu)

    alu = op_addi._select_internal(srcl_val + imm, alu)
    alu = op_subi._select_internal(srcl_val - imm, alu)

    addiw = (srcl_val._trunc(width=32) + imm._trunc(width=32))._sext(width=64)
    subiw = (srcl_val._trunc(width=32) - imm._trunc(width=32))._sext(width=64)
    alu = op_addiw._select_internal(addiw, alu)
    alu = op_subiw._select_internal(subiw, alu)

    alu = op_lui._select_internal(imm, alu)
    alu = op_setret._select_internal(pc + imm, alu)

    alu = op_add._select_internal(srcl_val + srcr_addsub_shl, alu)
    alu = op_sub._select_internal(srcl_val - srcr_addsub_shl, alu)
    alu = op_and._select_internal(srcl_val & srcr_logic_shl, alu)
    alu = op_or._select_internal(srcl_val | srcr_logic_shl, alu)
    alu = op_xor._select_internal(srcl_val ^ srcr_logic_shl, alu)

    alu = op_andi._select_internal(srcl_val & imm, alu)
    alu = op_ori._select_internal(srcl_val | imm, alu)
    alu = op_andiw._select_internal((srcl_val & imm)._trunc(width=32)._sext(width=64), alu)
    alu = op_oriw._select_internal((srcl_val | imm)._trunc(width=32)._sext(width=64), alu)
    alu = op_xori._select_internal(srcl_val ^ imm, alu)
    alu = op_xoriw._select_internal((srcl_val ^ imm)._trunc(width=32)._sext(width=64), alu)

    alu = op_mul._select_internal(srcl_val * srcr_val, alu)
    alu = op_mulw._select_internal((srcl_val * srcr_val)._trunc(width=32)._sext(width=64), alu)
    alu = op_madd._select_internal(srcp_val + (srcl_val * srcr_val), alu)
    alu = op_maddw._select_internal((srcp_val + (srcl_val * srcr_val))._trunc(width=32)._sext(width=64), alu)

    alu = op_div._select_internal(srcl_val.as_signed() // srcr_val.as_signed(), alu)
    alu = op_divu._select_internal(srcl_val.as_unsigned() // srcr_val.as_unsigned(), alu)

    divw_l32 = srcl_val._trunc(width=32)._sext(width=64).as_signed()
    divw_r32 = srcr_val._trunc(width=32)._sext(width=64).as_signed()
    alu = op_divw._select_internal((divw_l32 // divw_r32)._trunc(width=32)._sext(width=64), alu)

    divuw_l32 = srcl_val._trunc(width=32)._zext(width=64).as_unsigned()
    divuw_r32 = srcr_val._trunc(width=32)._zext(width=64).as_unsigned()
    alu = op_divuw._select_internal((divuw_l32 // divuw_r32)._trunc(width=32)._sext(width=64), alu)

    alu = op_rem._select_internal(srcl_val.as_signed() % srcr_val.as_signed(), alu)
    alu = op_remu._select_internal(srcl_val.as_unsigned() % srcr_val.as_unsigned(), alu)

    remw_l32 = srcl_val._trunc(width=32)._sext(width=64).as_signed()
    remw_r32 = srcr_val._trunc(width=32)._sext(width=64).as_signed()
    alu = op_remw._select_internal((remw_l32 % remw_r32)._trunc(width=32)._sext(width=64), alu)

    remuw_l32 = srcl_val._trunc(width=32)._zext(width=64).as_unsigned()
    remuw_r32 = srcr_val._trunc(width=32)._zext(width=64).as_unsigned()
    alu = op_remuw._select_internal((remuw_l32 % remuw_r32)._trunc(width=32)._sext(width=64), alu)

    alu = op_sll._select_internal(shl_var(m, srcl_val, srcr_val), alu)
    alu = op_srl._select_internal(lshr_var(m, srcl_val, srcr_val), alu)
    alu = op_sra._select_internal(ashr_var(m, srcl_val, srcr_val), alu)
    alu = op_slli._select_internal(shl_var(m, srcl_val, shamt), alu)
    alu = op_srli._select_internal(lshr_var(m, srcl_val, shamt), alu)
    alu = op_srai._select_internal(ashr_var(m, srcl_val, shamt), alu)

    sh5 = shamt & 0x1F
    sh5r = srcr_val & 0x1F
    slliw_val = shl_var(m, srcl_val._trunc(width=32)._zext(width=64), sh5)._trunc(width=32)._sext(width=64)
    sraiw_val = ashr_var(m, srcl_val._trunc(width=32)._sext(width=64), sh5)._trunc(width=32)._sext(width=64)
    srliw_val = lshr_var(m, srcl_val._trunc(width=32)._zext(width=64), sh5)._trunc(width=32)._sext(width=64)
    sllw_val = shl_var(m, srcl_val._trunc(width=32)._zext(width=64), sh5r)._trunc(width=32)._sext(width=64)
    srlw_val = lshr_var(m, srcl_val._trunc(width=32)._zext(width=64), sh5r)._trunc(width=32)._sext(width=64)
    sraw_val = ashr_var(m, srcl_val._trunc(width=32)._sext(width=64), sh5r)._trunc(width=32)._sext(width=64)
    alu = op_slliw._select_internal(slliw_val, alu)
    alu = op_sraiw._select_internal(sraiw_val, alu)
    alu = op_srliw._select_internal(srliw_val, alu)
    alu = op_sllw._select_internal(sllw_val, alu)
    alu = op_srlw._select_internal(srlw_val, alu)
    alu = op_sraw._select_internal(sraw_val, alu)

    # Bit extract ops (BXS: sign-ext, BXU: zero-ext).
    imms = srcr_val
    imml = srcp_val
    shifted = lshr_var(m, srcl_val, imms)
    sh_mask_amt = c(63, width=64) - imml._zext(width=64)
    mask = lshr_var(m, c(0xFFFF_FFFF_FFFF_FFFF, width=64), sh_mask_amt)
    extracted = shifted & mask
    valid_bx = (imms._zext(width=64) + imml._zext(width=64)).ule(63)
    sext_bxs = ashr_var(m, shl_var(m, extracted, sh_mask_amt), sh_mask_amt)
    alu = op_bxs._select_internal(valid_bx._select_internal(sext_bxs, z64), alu)
    alu = op_bxu._select_internal(valid_bx._select_internal(extracted, z64), alu)

    addw = (srcl_val + srcr_addsub_shl)._trunc(width=32)._sext(width=64)
    subw = (srcl_val - srcr_addsub_shl)._trunc(width=32)._sext(width=64)
    orw = (srcl_val | srcr_logic_shl)._trunc(width=32)._sext(width=64)
    andw = (srcl_val & srcr_logic_shl)._trunc(width=32)._sext(width=64)
    xorw = (srcl_val ^ srcr_logic_shl)._trunc(width=32)._sext(width=64)
    alu = op_addw._select_internal(addw, alu)
    alu = op_subw._select_internal(subw, alu)
    alu = op_orw._select_internal(orw, alu)
    alu = op_andw._select_internal(andw, alu)
    alu = op_xorw._select_internal(xorw, alu)

    # --- CMP.* (write 0/1 to RegDst) ---
    alu = op_cmp_eq._select_internal(srcl_val.__eq__(srcr_addsub_nosh)._select_internal(one64, z64), alu)
    alu = op_cmp_ne._select_internal((~srcl_val.__eq__(srcr_addsub_nosh))._select_internal(one64, z64), alu)
    alu = op_cmp_lt._select_internal(srcl_val.slt(srcr_addsub_nosh)._select_internal(one64, z64), alu)
    alu = op_cmp_eqi._select_internal(srcl_val.__eq__(imm)._select_internal(one64, z64), alu)
    alu = op_cmp_nei._select_internal((~srcl_val.__eq__(imm))._select_internal(one64, z64), alu)
    alu = op_cmp_andi._select_internal((~(srcl_val & imm).__eq__(0))._select_internal(one64, z64), alu)
    alu = op_cmp_ori._select_internal((~(srcl_val | imm).__eq__(0))._select_internal(one64, z64), alu)
    alu = op_cmp_lti._select_internal(srcl_val.slt(imm)._select_internal(one64, z64), alu)
    alu = op_cmp_gei._select_internal((~srcl_val.slt(imm))._select_internal(one64, z64), alu)
    alu = op_cmp_ltu._select_internal(srcl_val.ult(srcr_addsub_nosh)._select_internal(one64, z64), alu)
    alu = op_cmp_ltui._select_internal(srcl_val.ult(imm)._select_internal(one64, z64), alu)
    alu = op_cmp_geui._select_internal(srcl_val.uge(imm)._select_internal(one64, z64), alu)

    # --- FP compares (ordered FEQ/FLT/FGE, fd/fs via srcr_type) ---
    fd_exp_l = srcl_val[52:63]
    fd_exp_r = srcr_val[52:63]
    fd_frac_l = srcl_val[0:52]
    fd_frac_r = srcr_val[0:52]
    fd_nan_l = fd_exp_l.__eq__(c(0x7FF, width=11)) & (~fd_frac_l.__eq__(c(0, width=52)))
    fd_nan_r = fd_exp_r.__eq__(c(0x7FF, width=11)) & (~fd_frac_r.__eq__(c(0, width=52)))
    fd_nan = fd_nan_l | fd_nan_r
    fd_zero_l = (srcl_val & c(0x7FFF_FFFF_FFFF_FFFF, width=64)).__eq__(c(0, width=64))
    fd_zero_r = (srcr_val & c(0x7FFF_FFFF_FFFF_FFFF, width=64)).__eq__(c(0, width=64))
    fd_both_zero = fd_zero_l & fd_zero_r
    fd_key_l = srcl_val[63]._select_internal(~srcl_val, srcl_val ^ c(0x8000_0000_0000_0000, width=64))
    fd_key_r = srcr_val[63]._select_internal(~srcr_val, srcr_val ^ c(0x8000_0000_0000_0000, width=64))
    fd_lt = (~fd_nan) & (~fd_both_zero) & fd_key_l.ult(fd_key_r)
    fd_eq = (~fd_nan) & (srcl_val.__eq__(srcr_val) | fd_both_zero)
    fd_ge = (~fd_nan) & (fd_both_zero | fd_key_l.uge(fd_key_r))

    fs_l = srcl_val._trunc(width=32)
    fs_r = srcr_val._trunc(width=32)
    fs_exp_l = fs_l[23:31]
    fs_exp_r = fs_r[23:31]
    fs_frac_l = fs_l[0:23]
    fs_frac_r = fs_r[0:23]
    fs_nan_l = fs_exp_l.__eq__(c(0xFF, width=8)) & (~fs_frac_l.__eq__(c(0, width=23)))
    fs_nan_r = fs_exp_r.__eq__(c(0xFF, width=8)) & (~fs_frac_r.__eq__(c(0, width=23)))
    fs_nan = fs_nan_l | fs_nan_r
    fs_zero_l = (fs_l & c(0x7FFF_FFFF, width=32)).__eq__(c(0, width=32))
    fs_zero_r = (fs_r & c(0x7FFF_FFFF, width=32)).__eq__(c(0, width=32))
    fs_both_zero = fs_zero_l & fs_zero_r
    fs_key_l = fs_l[31]._select_internal(~fs_l, fs_l ^ c(0x8000_0000, width=32))
    fs_key_r = fs_r[31]._select_internal(~fs_r, fs_r ^ c(0x8000_0000, width=32))
    fs_lt = (~fs_nan) & (~fs_both_zero) & fs_key_l.ult(fs_key_r)
    fs_eq = (~fs_nan) & (fs_l.__eq__(fs_r) | fs_both_zero)
    fs_ge = (~fs_nan) & (fs_both_zero | fs_key_l.uge(fs_key_r))

    fp_is_fs = srcr_type.__eq__(c(1, width=2))
    fp_eq = fp_is_fs._select_internal(fs_eq, fd_eq)
    fp_lt = fp_is_fs._select_internal(fs_lt, fd_lt)
    fp_ge = fp_is_fs._select_internal(fs_ge, fd_ge)
    alu = op_feq._select_internal(fp_eq._select_internal(one64, z64), alu)
    alu = op_flt._select_internal(fp_lt._select_internal(one64, z64), alu)
    alu = op_fge._select_internal(fp_ge._select_internal(one64, z64), alu)

    # --- SETC.* (write 0/1; commit stage consumes val[0]) ---
    uimm_sh = shl_var(m, imm, shamt)
    simm_sh = uimm_sh

    alu = op_setc_geui._select_internal(srcl_val.uge(uimm_sh)._select_internal(one64, z64), alu)
    alu = op_setc_eqi._select_internal(srcl_val.__eq__(simm_sh)._select_internal(one64, z64), alu)
    alu = op_setc_nei._select_internal((~srcl_val.__eq__(simm_sh))._select_internal(one64, z64), alu)
    alu = op_setc_andi._select_internal((~(srcl_val & simm_sh).__eq__(0))._select_internal(one64, z64), alu)
    alu = op_setc_ori._select_internal((~(srcl_val | simm_sh).__eq__(0))._select_internal(one64, z64), alu)
    alu = op_setc_lti._select_internal(srcl_val.slt(simm_sh)._select_internal(one64, z64), alu)
    alu = op_setc_gei._select_internal((~srcl_val.slt(simm_sh))._select_internal(one64, z64), alu)
    alu = op_setc_ltui._select_internal(srcl_val.ult(uimm_sh)._select_internal(one64, z64), alu)

    alu = op_setc_eq._select_internal(srcl_val.__eq__(srcr_addsub_nosh)._select_internal(one64, z64), alu)
    alu = op_setc_ne._select_internal((~srcl_val.__eq__(srcr_addsub_nosh))._select_internal(one64, z64), alu)
    alu = op_setc_and._select_internal((~(srcl_val & srcr_logic).__eq__(0))._select_internal(one64, z64), alu)
    alu = op_setc_or._select_internal((~(srcl_val | srcr_logic).__eq__(0))._select_internal(one64, z64), alu)
    alu = op_setc_lt._select_internal(srcl_val.slt(srcr_addsub_nosh)._select_internal(one64, z64), alu)
    alu = op_setc_ltu._select_internal(srcl_val.ult(srcr_addsub_nosh)._select_internal(one64, z64), alu)
    alu = op_setc_ge._select_internal((~srcl_val.slt(srcr_addsub_nosh))._select_internal(one64, z64), alu)
    alu = op_setc_geu._select_internal(srcl_val.uge(srcr_addsub_nosh)._select_internal(one64, z64), alu)

    alu = op_hl_lui._select_internal(imm, alu)

    # CSEL: select srcr (shifted) when SrcP != 0.
    alu = op_csel._select_internal((~srcp_val.__eq__(0))._select_internal(srcr_addsub_nosh, srcl_val), alu)

    # --- memory ops (address/size/data) ---
    is_lwi = op_lwi | op_c_lwi
    lwi_addr = srcl_val + off_w
    is_load = is_lwi._select_internal(one1, is_load)
    size = is_lwi._select_internal(sz4, size)
    addr = is_lwi._select_internal(lwi_addr, addr)

    is_load = op_lwui._select_internal(one1, is_load)
    size = op_lwui._select_internal(sz4, size)
    addr = op_lwui._select_internal(lwi_addr, addr)

    is_lbi_any = op_lbi | op_lbui
    is_load = is_lbi_any._select_internal(one1, is_load)
    size = is_lbi_any._select_internal(sz1, size)
    addr = is_lbi_any._select_internal(srcl_val + imm, addr)

    is_lhi_any = op_lhi | op_lhui
    is_load = is_lhi_any._select_internal(one1, is_load)
    size = is_lhi_any._select_internal(sz2, size)
    addr = is_lhi_any._select_internal(srcl_val + h_off, addr)

    idx_addr = srcl_val + idx_mod_shl
    is_load = (op_lb | op_lbu)._select_internal(one1, is_load)
    size = (op_lb | op_lbu)._select_internal(sz1, size)
    addr = (op_lb | op_lbu)._select_internal(idx_addr, addr)

    is_load = (op_lh | op_lhu)._select_internal(one1, is_load)
    size = (op_lh | op_lhu)._select_internal(sz2, size)
    addr = (op_lh | op_lhu)._select_internal(idx_addr, addr)

    is_load = (op_lw | op_lwu)._select_internal(one1, is_load)
    size = (op_lw | op_lwu)._select_internal(sz4, size)
    addr = (op_lw | op_lwu)._select_internal(idx_addr, addr)

    is_load = op_ld._select_internal(one1, is_load)
    size = op_ld._select_internal(sz8, size)
    addr = op_ld._select_internal(idx_addr, addr)

    is_load = (op_ldi | op_c_ldi)._select_internal(one1, is_load)
    size = (op_ldi | op_c_ldi)._select_internal(sz8, size)
    addr = (op_ldi | op_c_ldi)._select_internal(srcl_val + ldi_off, addr)

    # Stores.
    is_store = op_sbi._select_internal(one1, is_store)
    size = op_sbi._select_internal(sz1, size)
    addr = op_sbi._select_internal(srcr_val + imm, addr)
    wdata = op_sbi._select_internal(srcl_val, wdata)

    is_store = op_shi._select_internal(one1, is_store)
    size = op_shi._select_internal(sz2, size)
    addr = op_shi._select_internal(srcr_val + h_off, addr)
    wdata = op_shi._select_internal(srcl_val, wdata)

    store_addr_def = srcl_val + off_w
    store_data_def = srcr_val
    store_addr = op_swi._select_internal(srcr_val + off_w, store_addr_def)
    store_data = op_swi._select_internal(srcl_val, store_data_def)
    op_swi_any = op_swi | op_c_swi
    is_store = op_swi_any._select_internal(one1, is_store)
    size = op_swi_any._select_internal(sz4, size)
    addr = op_swi_any._select_internal(store_addr, addr)
    wdata = op_swi_any._select_internal(store_data, wdata)

    is_store = op_sb._select_internal(one1, is_store)
    size = op_sb._select_internal(sz1, size)
    addr = op_sb._select_internal(idx_addr, addr)
    wdata = op_sb._select_internal(srcp_val, wdata)

    is_store = op_sh._select_internal(one1, is_store)
    size = op_sh._select_internal(sz2, size)
    addr = op_sh._select_internal(idx_addr, addr)
    wdata = op_sh._select_internal(srcp_val, wdata)

    is_store = op_sw._select_internal(one1, is_store)
    size = op_sw._select_internal(sz4, size)
    addr = op_sw._select_internal(idx_addr, addr)
    wdata = op_sw._select_internal(srcp_val, wdata)

    is_store = op_sd._select_internal(one1, is_store)
    size = op_sd._select_internal(sz8, size)
    addr = op_sd._select_internal(idx_addr, addr)
    wdata = op_sd._select_internal(srcp_val, wdata)

    sdi_off = imm.shl(amount=3)
    is_store = op_c_sdi._select_internal(one1, is_store)
    size = op_c_sdi._select_internal(sz8, size)
    addr = op_c_sdi._select_internal(srcl_val + sdi_off, addr)
    wdata = op_c_sdi._select_internal(srcr_val, wdata)

    sdi_addr = srcr_val + sdi_off
    is_store = op_sdi._select_internal(one1, is_store)
    size = op_sdi._select_internal(sz8, size)
    addr = op_sdi._select_internal(sdi_addr, addr)
    wdata = op_sdi._select_internal(srcl_val, wdata)

    # HL loads/stores (PC-relative).
    hl_addr = pc + imm
    hl_load_b = op_hl_lb_pcr | op_hl_lbu_pcr
    hl_load_h = op_hl_lh_pcr | op_hl_lhu_pcr
    hl_load_w = op_hl_lw_pcr | op_hl_lwu_pcr
    is_load = hl_load_b._select_internal(one1, is_load)
    size = hl_load_b._select_internal(sz1, size)
    addr = hl_load_b._select_internal(hl_addr, addr)
    is_load = hl_load_h._select_internal(one1, is_load)
    size = hl_load_h._select_internal(sz2, size)
    addr = hl_load_h._select_internal(hl_addr, addr)
    is_load = hl_load_w._select_internal(one1, is_load)
    size = hl_load_w._select_internal(sz4, size)
    addr = hl_load_w._select_internal(hl_addr, addr)
    is_load = op_hl_ld_pcr._select_internal(one1, is_load)
    size = op_hl_ld_pcr._select_internal(sz8, size)
    addr = op_hl_ld_pcr._select_internal(hl_addr, addr)

    is_store = op_hl_sb_pcr._select_internal(one1, is_store)
    size = op_hl_sb_pcr._select_internal(sz1, size)
    addr = op_hl_sb_pcr._select_internal(hl_addr, addr)
    wdata = op_hl_sb_pcr._select_internal(srcl_val, wdata)
    is_store = op_hl_sh_pcr._select_internal(one1, is_store)
    size = op_hl_sh_pcr._select_internal(sz2, size)
    addr = op_hl_sh_pcr._select_internal(hl_addr, addr)
    wdata = op_hl_sh_pcr._select_internal(srcl_val, wdata)
    is_store = op_hl_sw_pcr._select_internal(one1, is_store)
    size = op_hl_sw_pcr._select_internal(sz4, size)
    addr = op_hl_sw_pcr._select_internal(hl_addr, addr)
    wdata = op_hl_sw_pcr._select_internal(srcl_val, wdata)
    is_store = op_hl_sd_pcr._select_internal(one1, is_store)
    size = op_hl_sd_pcr._select_internal(sz8, size)
    addr = op_hl_sd_pcr._select_internal(hl_addr, addr)
    wdata = op_hl_sd_pcr._select_internal(srcl_val, wdata)

    # Compressed integer ops.
    alu = op_c_addi._select_internal(srcl_val + imm, alu)
    alu = op_c_add._select_internal(srcl_val + srcr_val, alu)
    alu = op_c_sub._select_internal(srcl_val - srcr_val, alu)
    alu = op_c_and._select_internal(srcl_val & srcr_val, alu)
    alu = op_c_or._select_internal(srcl_val | srcr_val, alu)
    alu = op_c_sext_w._select_internal(srcl_val._trunc(width=32)._sext(width=64), alu)
    alu = op_c_zext_w._select_internal(srcl_val._trunc(width=32)._zext(width=64), alu)

    return ExecOut(alu=alu, is_load=is_load, is_store=is_store, size=size, addr=addr, wdata=wdata)


@function
def exec_uop(
    m: Circuit,
    *,
    op: Wire,
    pc: Wire,
    imm: Wire,
    srcl_val: Wire,
    srcr_val: Wire,
    srcr_type: Wire,
    shamt: Wire,
    srcp_val: Wire,
    consts: Consts,
) -> ExecOut:
    with m.scope("exec"):
        c = m.const
        z1 = consts.zero1
        z4 = consts.zero4
        z64 = consts.zero64

        pc = pc.out()
        op = op.out()
        imm = imm.out()
        srcl_val = srcl_val.out()
        srcr_val = srcr_val.out()
        srcr_type = srcr_type.out()
        shamt = shamt.out()
        srcp_val = srcp_val.out()

        op_c_bstart_std = op == OP_C_BSTART_STD
        op_c_bstart_cond = op == OP_C_BSTART_COND
        op_c_bstart_direct = op == OP_C_BSTART_DIRECT
        op_bstart_std_fall = op == OP_BSTART_STD_FALL
        op_bstart_std_direct = op == OP_BSTART_STD_DIRECT
        op_bstart_std_cond = op == OP_BSTART_STD_COND
        op_bstart_std_call = op == OP_BSTART_STD_CALL
        op_fentry = op == OP_FENTRY
        op_fexit = op == OP_FEXIT
        op_fret_ra = op == OP_FRET_RA
        op_fret_stk = op == OP_FRET_STK
        op_c_movr = op == OP_C_MOVR
        op_c_movi = op == OP_C_MOVI
        op_c_setret = op == OP_C_SETRET
        op_c_setc_eq = op == OP_C_SETC_EQ
        op_c_setc_ne = op == OP_C_SETC_NE
        op_c_setc_tgt = op == OP_C_SETC_TGT
        op_setret = op == OP_SETRET
        op_addtpc = op == OP_ADDTPC
        op_lui = op == OP_LUI
        op_add = op == OP_ADD
        op_sub = op == OP_SUB
        op_and = op == OP_AND
        op_or = op == OP_OR
        op_xor = op == OP_XOR
        op_addi = op == OP_ADDI
        op_subi = op == OP_SUBI
        op_andi = op == OP_ANDI
        op_ori = op == OP_ORI
        op_addiw = op == OP_ADDIW
        op_subiw = op == OP_SUBIW
        op_andiw = op == OP_ANDIW
        op_oriw = op == OP_ORIW
        op_xori = op == OP_XORI
        op_xoriw = op == OP_XORIW
        op_mul = op == OP_MUL
        op_mulw = op == OP_MULW
        op_madd = op == OP_MADD
        op_maddw = op == OP_MADDW
        op_div = op == OP_DIV
        op_divu = op == OP_DIVU
        op_divw = op == OP_DIVW
        op_divuw = op == OP_DIVUW
        op_rem = op == OP_REM
        op_remu = op == OP_REMU
        op_remw = op == OP_REMW
        op_remuw = op == OP_REMUW
        op_sll = op == OP_SLL
        op_srl = op == OP_SRL
        op_sra = op == OP_SRA
        op_slli = op == OP_SLLI
        op_srli = op == OP_SRLI
        op_srai = op == OP_SRAI
        op_slliw = op == OP_SLLIW
        op_sllw = op == OP_SLLW
        op_srlw = op == OP_SRLW
        op_sraw = op == OP_SRAW
        op_sraiw = op == OP_SRAIW
        op_srliw = op == OP_SRLIW
        op_bxs = op == OP_BXS
        op_bxu = op == OP_BXU
        op_addw = op == OP_ADDW
        op_subw = op == OP_SUBW
        op_orw = op == OP_ORW
        op_andw = op == OP_ANDW
        op_xorw = op == OP_XORW
        op_cmp_eq = op == OP_CMP_EQ
        op_cmp_ne = op == OP_CMP_NE
        op_cmp_lt = op == OP_CMP_LT
        op_cmp_eqi = op == OP_CMP_EQI
        op_cmp_nei = op == OP_CMP_NEI
        op_cmp_andi = op == OP_CMP_ANDI
        op_cmp_ori = op == OP_CMP_ORI
        op_cmp_lti = op == OP_CMP_LTI
        op_cmp_ltu = op == OP_CMP_LTU
        op_cmp_ltui = op == OP_CMP_LTUI
        op_cmp_gei = op == OP_CMP_GEI
        op_cmp_geui = op == OP_CMP_GEUI
        op_feq = op == OP_FEQ
        op_flt = op == OP_FLT
        op_fge = op == OP_FGE
        op_setc_geui = op == OP_SETC_GEUI
        op_setc_eq = op == OP_SETC_EQ
        op_setc_ne = op == OP_SETC_NE
        op_setc_and = op == OP_SETC_AND
        op_setc_or = op == OP_SETC_OR
        op_setc_lt = op == OP_SETC_LT
        op_setc_ltu = op == OP_SETC_LTU
        op_setc_ge = op == OP_SETC_GE
        op_setc_geu = op == OP_SETC_GEU
        op_setc_eqi = op == OP_SETC_EQI
        op_setc_nei = op == OP_SETC_NEI
        op_setc_andi = op == OP_SETC_ANDI
        op_setc_ori = op == OP_SETC_ORI
        op_setc_lti = op == OP_SETC_LTI
        op_setc_gei = op == OP_SETC_GEI
        op_setc_ltui = op == OP_SETC_LTUI
        op_csel = op == OP_CSEL
        op_hl_lui = op == OP_HL_LUI
        op_hl_lb_pcr = op == OP_HL_LB_PCR
        op_hl_lbu_pcr = op == OP_HL_LBU_PCR
        op_hl_lh_pcr = op == OP_HL_LH_PCR
        op_hl_lhu_pcr = op == OP_HL_LHU_PCR
        op_hl_lw_pcr = op == OP_HL_LW_PCR
        op_hl_lwu_pcr = op == OP_HL_LWU_PCR
        op_hl_ld_pcr = op == OP_HL_LD_PCR
        op_hl_sb_pcr = op == OP_HL_SB_PCR
        op_hl_sh_pcr = op == OP_HL_SH_PCR
        op_hl_sw_pcr = op == OP_HL_SW_PCR
        op_hl_sd_pcr = op == OP_HL_SD_PCR
        op_lwi = op == OP_LWI
        op_c_lwi = op == OP_C_LWI
        op_lbi = op == OP_LBI
        op_lbui = op == OP_LBUI
        op_lhi = op == OP_LHI
        op_lhui = op == OP_LHUI
        op_lwui = op == OP_LWUI
        op_lb = op == OP_LB
        op_lbu = op == OP_LBU
        op_lh = op == OP_LH
        op_lhu = op == OP_LHU
        op_lw = op == OP_LW
        op_lwu = op == OP_LWU
        op_ld = op == OP_LD
        op_ldi = op == OP_LDI
        op_c_add = op == OP_C_ADD
        op_c_addi = op == OP_C_ADDI
        op_c_sub = op == OP_C_SUB
        op_c_and = op == OP_C_AND
        op_c_or = op == OP_C_OR
        op_c_ldi = op == OP_C_LDI
        op_sbi = op == OP_SBI
        op_shi = op == OP_SHI
        op_swi = op == OP_SWI
        op_c_swi = op == OP_C_SWI
        op_c_sdi = op == OP_C_SDI
        op_sb = op == OP_SB
        op_sh = op == OP_SH
        op_sw = op == OP_SW
        op_sd = op == OP_SD
        op_c_sext_w = op == OP_C_SEXT_W
        op_c_zext_w = op == OP_C_ZEXT_W
        op_sdi = op == OP_SDI

        off = imm.shl(amount=2)

        alu = z64
        is_load = z1
        is_store = z1
        size = z4
        addr = z64
        wdata = z64

        # SrcR modifiers.
        srcr_addsub = srcr_val
        if srcr_type == 0:
            srcr_addsub = srcr_val._trunc(width=32)._sext(width=64)
        if srcr_type == 1:
            srcr_addsub = srcr_val._trunc(width=32)._zext(width=64)
        if srcr_type == 2:
            srcr_addsub = (~srcr_val) + 1

        srcr_logic = srcr_val
        if srcr_type == 0:
            srcr_logic = srcr_val._trunc(width=32)._sext(width=64)
        if srcr_type == 1:
            srcr_logic = srcr_val._trunc(width=32)._zext(width=64)
        if srcr_type == 2:
            srcr_logic = ~srcr_val

        srcr_addsub_shl = shl_var(m, srcr_addsub, shamt)
        srcr_logic_shl = shl_var(m, srcr_logic, shamt)

        idx_mod = srcr_val._trunc(width=32)._zext(width=64)
        if srcr_type == 0:
            idx_mod = srcr_val._trunc(width=32)._sext(width=64)
        idx_mod_shl = shl_var(m, idx_mod, shamt)

        if (
            op_c_bstart_std
            | op_c_bstart_cond
            | op_c_bstart_direct
            | op_bstart_std_fall
            | op_bstart_std_direct
            | op_bstart_std_cond
            | op_bstart_std_call
            | op_fentry
            | op_fexit
            | op_fret_ra
            | op_fret_stk
        ):
            alu = imm
            is_load = z1
            is_store = z1
            size = z4
            addr = z64
            wdata = z64

        if op_c_movr:
            alu = srcl_val
            is_load = z1
            is_store = z1
            size = z4
            addr = z64
            wdata = z64

        if op_c_movi:
            alu = imm
            is_load = z1
            is_store = z1
            size = z4
            addr = z64
            wdata = z64

        if op_c_setret:
            alu = pc + imm
            is_load = z1
            is_store = z1
            size = z4
            addr = z64
            wdata = z64

        setc_eq = z64
        if srcl_val == srcr_val:
            setc_eq = 1
        if op_c_setc_eq:
            alu = setc_eq
            is_load = z1
            is_store = z1
            size = z4
            addr = z64
            wdata = z64
        if op_c_setc_tgt:
            alu = srcl_val
            is_load = z1
            is_store = z1
            size = z4
            addr = z64
            wdata = z64

        pc_page = pc & 0xFFFF_FFFF_FFFF_F000
        if op_addtpc:
            alu = pc_page + imm
            is_load = z1
            is_store = z1
            size = z4
            addr = z64
            wdata = z64

        if op_addi:
            alu = srcl_val + imm
            is_load = z1
            is_store = z1
            size = z4
            addr = z64
            wdata = z64
        subi = srcl_val + ((~imm) + 1)
        if op_subi:
            alu = subi
            is_load = z1
            is_store = z1
            size = z4
            addr = z64
            wdata = z64
        addiw = (srcl_val._trunc(width=32) + imm._trunc(width=32))._sext(width=64)
        if op_addiw:
            alu = addiw
            is_load = z1
            is_store = z1
            size = z4
            addr = z64
            wdata = z64
        subiw = (srcl_val._trunc(width=32) - imm._trunc(width=32))._sext(width=64)
        if op_subiw:
            alu = subiw
            is_load = z1
            is_store = z1
            size = z4
            addr = z64
            wdata = z64

        if op_lui:
            alu = imm
            is_load = z1
            is_store = z1
            size = z4
            addr = z64
            wdata = z64

        if op_setret:
            alu = pc + imm
            is_load = z1
            is_store = z1
            size = z4
            addr = z64
            wdata = z64

        if op_add:
            alu = srcl_val + srcr_addsub_shl
            is_load = z1
            is_store = z1
            size = z4
            addr = z64
            wdata = z64
        if op_sub:
            alu = srcl_val - srcr_addsub_shl
            is_load = z1
            is_store = z1
            size = z4
            addr = z64
            wdata = z64
        if op_and:
            alu = srcl_val & srcr_logic_shl
            is_load = z1
            is_store = z1
            size = z4
            addr = z64
            wdata = z64
        if op_or:
            alu = srcl_val | srcr_logic_shl
            is_load = z1
            is_store = z1
            size = z4
            addr = z64
            wdata = z64
        if op_xor:
            alu = srcl_val ^ srcr_logic_shl
            is_load = z1
            is_store = z1
            size = z4
            addr = z64
            wdata = z64

        if op_andi:
            alu = srcl_val & imm
            is_load = z1
            is_store = z1
            size = z4
            addr = z64
            wdata = z64
        if op_ori:
            alu = srcl_val | imm
            is_load = z1
            is_store = z1
            size = z4
            addr = z64
            wdata = z64

        if op_andiw:
            alu = (srcl_val & imm)._trunc(width=32)._sext(width=64)
            is_load = z1
            is_store = z1
            size = z4
            addr = z64
            wdata = z64
        if op_oriw:
            alu = (srcl_val | imm)._trunc(width=32)._sext(width=64)
            is_load = z1
            is_store = z1
            size = z4
            addr = z64
            wdata = z64
        if op_xori:
            alu = srcl_val ^ imm
            is_load = z1
            is_store = z1
            size = z4
            addr = z64
            wdata = z64
        if op_xoriw:
            alu = (srcl_val ^ imm)._trunc(width=32)._sext(width=64)
            is_load = z1
            is_store = z1
            size = z4
            addr = z64
            wdata = z64

        if op_mul:
            alu = srcl_val * srcr_val
            is_load = z1
            is_store = z1
            size = z4
            addr = z64
            wdata = z64
        if op_mulw:
            alu = (srcl_val * srcr_val)._trunc(width=32)._sext(width=64)
            is_load = z1
            is_store = z1
            size = z4
            addr = z64
            wdata = z64
        if op_madd:
            alu = srcp_val + (srcl_val * srcr_val)
            is_load = z1
            is_store = z1
            size = z4
            addr = z64
            wdata = z64
        if op_maddw:
            alu = (srcp_val + (srcl_val * srcr_val))._trunc(width=32)._sext(width=64)
            is_load = z1
            is_store = z1
            size = z4
            addr = z64
            wdata = z64

        if op_div:
            alu = srcl_val.as_signed() // srcr_val.as_signed()
            is_load = z1
            is_store = z1
            size = z4
            addr = z64
            wdata = z64
        if op_divu:
            alu = srcl_val.as_unsigned() // srcr_val.as_unsigned()
            is_load = z1
            is_store = z1
            size = z4
            addr = z64
            wdata = z64
        if op_divw:
            l32 = srcl_val._trunc(width=32)._sext(width=64).as_signed()
            r32 = srcr_val._trunc(width=32)._sext(width=64).as_signed()
            alu = (l32 // r32)._trunc(width=32)._sext(width=64)
            is_load = z1
            is_store = z1
            size = z4
            addr = z64
            wdata = z64
        if op_divuw:
            l32 = srcl_val._trunc(width=32)._zext(width=64).as_unsigned()
            r32 = srcr_val._trunc(width=32)._zext(width=64).as_unsigned()
            alu = (l32 // r32)._trunc(width=32)._sext(width=64)
            is_load = z1
            is_store = z1
            size = z4
            addr = z64
            wdata = z64
        if op_rem:
            alu = srcl_val.as_signed() % srcr_val.as_signed()
            is_load = z1
            is_store = z1
            size = z4
            addr = z64
            wdata = z64
        if op_remu:
            alu = srcl_val.as_unsigned() % srcr_val.as_unsigned()
            is_load = z1
            is_store = z1
            size = z4
            addr = z64
            wdata = z64
        if op_remw:
            l32 = srcl_val._trunc(width=32)._sext(width=64).as_signed()
            r32 = srcr_val._trunc(width=32)._sext(width=64).as_signed()
            alu = (l32 % r32)._trunc(width=32)._sext(width=64)
            is_load = z1
            is_store = z1
            size = z4
            addr = z64
            wdata = z64
        if op_remuw:
            l32 = srcl_val._trunc(width=32)._zext(width=64).as_unsigned()
            r32 = srcr_val._trunc(width=32)._zext(width=64).as_unsigned()
            alu = (l32 % r32)._trunc(width=32)._sext(width=64)
            is_load = z1
            is_store = z1
            size = z4
            addr = z64
            wdata = z64

        if op_sll:
            alu = shl_var(m, srcl_val, srcr_val)
            is_load = z1
            is_store = z1
            size = z4
            addr = z64
            wdata = z64
        if op_srl:
            alu = lshr_var(m, srcl_val, srcr_val)
            is_load = z1
            is_store = z1
            size = z4
            addr = z64
            wdata = z64
        if op_sra:
            alu = ashr_var(m, srcl_val, srcr_val)
            is_load = z1
            is_store = z1
            size = z4
            addr = z64
            wdata = z64
        if op_slli:
            alu = shl_var(m, srcl_val, shamt)
            is_load = z1
            is_store = z1
            size = z4
            addr = z64
            wdata = z64
        if op_srli:
            alu = lshr_var(m, srcl_val, shamt)
            is_load = z1
            is_store = z1
            size = z4
            addr = z64
            wdata = z64
        if op_srai:
            alu = ashr_var(m, srcl_val, shamt)
            is_load = z1
            is_store = z1
            size = z4
            addr = z64
            wdata = z64
        if op_slliw:
            l32 = srcl_val._trunc(width=32)._zext(width=64)
            sh5 = shamt & 0x1F
            shifted = shl_var(m, l32, sh5)
            alu = shifted._trunc(width=32)._sext(width=64)
            is_load = z1
            is_store = z1
            size = z4
            addr = z64
            wdata = z64
        if op_sraiw:
            l32 = srcl_val._trunc(width=32)._sext(width=64)
            sh5 = shamt & 0x1F
            shifted = ashr_var(m, l32, sh5)
            alu = shifted._trunc(width=32)._sext(width=64)
            is_load = z1
            is_store = z1
            size = z4
            addr = z64
            wdata = z64
        if op_srliw:
            l32 = srcl_val._trunc(width=32)._zext(width=64)
            sh5 = shamt & 0x1F
            shifted = lshr_var(m, l32, sh5)
            alu = shifted._trunc(width=32)._sext(width=64)
            is_load = z1
            is_store = z1
            size = z4
            addr = z64
            wdata = z64
        if op_sllw:
            l32 = srcl_val._trunc(width=32)._zext(width=64)
            sh5 = srcr_val & 0x1F
            shifted = shl_var(m, l32, sh5)
            alu = shifted._trunc(width=32)._sext(width=64)
            is_load = z1
            is_store = z1
            size = z4
            addr = z64
            wdata = z64
        if op_srlw:
            l32 = srcl_val._trunc(width=32)._zext(width=64)
            sh5 = srcr_val & 0x1F
            shifted = lshr_var(m, l32, sh5)
            alu = shifted._trunc(width=32)._sext(width=64)
            is_load = z1
            is_store = z1
            size = z4
            addr = z64
            wdata = z64
        if op_sraw:
            l32 = srcl_val._trunc(width=32)._sext(width=64)
            sh5 = srcr_val & 0x1F
            shifted = ashr_var(m, l32, sh5)
            alu = shifted._trunc(width=32)._sext(width=64)
            is_load = z1
            is_store = z1
            size = z4
            addr = z64
            wdata = z64

        if op_bxs:
            imms = srcr_val
            imml = srcp_val
            shifted = lshr_var(m, srcl_val, imms)
            sh_mask_amt = m.const(63, width=64) - imml._zext(width=64)
            mask = lshr_var(m, m.const(0xFFFF_FFFF_FFFF_FFFF, width=64), sh_mask_amt)
            extracted = shifted & mask
            valid = (imms._zext(width=64) + imml._zext(width=64)).ule(63)
            sh_ext = sh_mask_amt
            sext = ashr_var(m, shl_var(m, extracted, sh_ext), sh_ext)
            alu = valid._select_internal(sext, z64)
            is_load = z1
            is_store = z1
            size = z4
            addr = z64
            wdata = z64

        if op_bxu:
            imms = srcr_val
            imml = srcp_val
            shifted = lshr_var(m, srcl_val, imms)
            sh_mask_amt = m.const(63, width=64) - imml._zext(width=64)
            mask = lshr_var(m, m.const(0xFFFF_FFFF_FFFF_FFFF, width=64), sh_mask_amt)
            extracted = shifted & mask
            valid = (imms._zext(width=64) + imml._zext(width=64)).ule(63)
            alu = valid._select_internal(extracted, z64)
            is_load = z1
            is_store = z1
            size = z4
            addr = z64
            wdata = z64

        addw = (srcl_val + srcr_addsub_shl)._trunc(width=32)._sext(width=64)
        subw = (srcl_val - srcr_addsub_shl)._trunc(width=32)._sext(width=64)
        orw = (srcl_val | srcr_logic_shl)._trunc(width=32)._sext(width=64)
        andw = (srcl_val & srcr_logic_shl)._trunc(width=32)._sext(width=64)
        xorw = (srcl_val ^ srcr_logic_shl)._trunc(width=32)._sext(width=64)
        if op_addw:
            alu = addw
            is_load = z1
            is_store = z1
            size = z4
            addr = z64
            wdata = z64
        if op_subw:
            alu = subw
            is_load = z1
            is_store = z1
            size = z4
            addr = z64
            wdata = z64
        if op_orw:
            alu = orw
            is_load = z1
            is_store = z1
            size = z4
            addr = z64
            wdata = z64
        if op_andw:
            alu = andw
            is_load = z1
            is_store = z1
            size = z4
            addr = z64
            wdata = z64
        if op_xorw:
            alu = xorw
            is_load = z1
            is_store = z1
            size = z4
            addr = z64
            wdata = z64

        srcr_addsub_nosh = srcr_addsub
        cmp = z64
        if srcl_val == srcr_addsub_nosh:
            cmp = 1
        if op_cmp_eq:
            alu = cmp
            is_load = z1
            is_store = z1
            size = z4
            addr = z64
            wdata = z64

        cmp_ne = z64
        if srcl_val != srcr_addsub_nosh:
            cmp_ne = 1
        if op_cmp_ne:
            alu = cmp_ne
            is_load = z1
            is_store = z1
            size = z4
            addr = z64
            wdata = z64

        cmp_lt = z64
        if srcl_val.slt(srcr_addsub_nosh):
            cmp_lt = 1
        if op_cmp_lt:
            alu = cmp_lt
            is_load = z1
            is_store = z1
            size = z4
            addr = z64
            wdata = z64

        cmp_eqi = z64
        if srcl_val == imm:
            cmp_eqi = 1
        if op_cmp_eqi:
            alu = cmp_eqi
            is_load = z1
            is_store = z1
            size = z4
            addr = z64
            wdata = z64

        cmp_nei = z64
        if srcl_val != imm:
            cmp_nei = 1
        if op_cmp_nei:
            alu = cmp_nei
            is_load = z1
            is_store = z1
            size = z4
            addr = z64
            wdata = z64

        cmp_andi = z64
        if (srcl_val & imm) != 0:
            cmp_andi = 1
        if op_cmp_andi:
            alu = cmp_andi
            is_load = z1
            is_store = z1
            size = z4
            addr = z64
            wdata = z64

        cmp_ori = z64
        if (srcl_val | imm) != 0:
            cmp_ori = 1
        if op_cmp_ori:
            alu = cmp_ori
            is_load = z1
            is_store = z1
            size = z4
            addr = z64
            wdata = z64

        cmp_lti = z64
        if srcl_val.slt(imm):
            cmp_lti = 1
        if op_cmp_lti:
            alu = cmp_lti
            is_load = z1
            is_store = z1
            size = z4
            addr = z64
            wdata = z64

        cmp_gei = z64
        if ~srcl_val.slt(imm):
            cmp_gei = 1
        if op_cmp_gei:
            alu = cmp_gei
            is_load = z1
            is_store = z1
            size = z4
            addr = z64
            wdata = z64

        cmp_ltu = z64
        if srcl_val.ult(srcr_addsub_nosh):
            cmp_ltu = 1
        if op_cmp_ltu:
            alu = cmp_ltu
            is_load = z1
            is_store = z1
            size = z4
            addr = z64
            wdata = z64

        cmp_ltui = z64
        if srcl_val.ult(imm):
            cmp_ltui = 1
        if op_cmp_ltui:
            alu = cmp_ltui
            is_load = z1
            is_store = z1
            size = z4
            addr = z64
            wdata = z64

        cmp_geui = z64
        if srcl_val.uge(imm):
            cmp_geui = 1
        if op_cmp_geui:
            alu = cmp_geui
            is_load = z1
            is_store = z1
            size = z4
            addr = z64
            wdata = z64

        fd_exp_l = srcl_val[52:63]
        fd_exp_r = srcr_val[52:63]
        fd_frac_l = srcl_val[0:52]
        fd_frac_r = srcr_val[0:52]
        fd_nan_l = fd_exp_l.__eq__(c(0x7FF, width=11)) & (~fd_frac_l.__eq__(c(0, width=52)))
        fd_nan_r = fd_exp_r.__eq__(c(0x7FF, width=11)) & (~fd_frac_r.__eq__(c(0, width=52)))
        fd_nan = fd_nan_l | fd_nan_r
        fd_zero_l = (srcl_val & c(0x7FFF_FFFF_FFFF_FFFF, width=64)).__eq__(c(0, width=64))
        fd_zero_r = (srcr_val & c(0x7FFF_FFFF_FFFF_FFFF, width=64)).__eq__(c(0, width=64))
        fd_both_zero = fd_zero_l & fd_zero_r
        fd_key_l = srcl_val[63]._select_internal(~srcl_val, srcl_val ^ c(0x8000_0000_0000_0000, width=64))
        fd_key_r = srcr_val[63]._select_internal(~srcr_val, srcr_val ^ c(0x8000_0000_0000_0000, width=64))
        fd_lt = (~fd_nan) & (~fd_both_zero) & fd_key_l.ult(fd_key_r)
        fd_eq = (~fd_nan) & (srcl_val.__eq__(srcr_val) | fd_both_zero)
        fd_ge = (~fd_nan) & (fd_both_zero | fd_key_l.uge(fd_key_r))

        fs_l = srcl_val._trunc(width=32)
        fs_r = srcr_val._trunc(width=32)
        fs_exp_l = fs_l[23:31]
        fs_exp_r = fs_r[23:31]
        fs_frac_l = fs_l[0:23]
        fs_frac_r = fs_r[0:23]
        fs_nan_l = fs_exp_l.__eq__(c(0xFF, width=8)) & (~fs_frac_l.__eq__(c(0, width=23)))
        fs_nan_r = fs_exp_r.__eq__(c(0xFF, width=8)) & (~fs_frac_r.__eq__(c(0, width=23)))
        fs_nan = fs_nan_l | fs_nan_r
        fs_zero_l = (fs_l & c(0x7FFF_FFFF, width=32)).__eq__(c(0, width=32))
        fs_zero_r = (fs_r & c(0x7FFF_FFFF, width=32)).__eq__(c(0, width=32))
        fs_both_zero = fs_zero_l & fs_zero_r
        fs_key_l = fs_l[31]._select_internal(~fs_l, fs_l ^ c(0x8000_0000, width=32))
        fs_key_r = fs_r[31]._select_internal(~fs_r, fs_r ^ c(0x8000_0000, width=32))
        fs_lt = (~fs_nan) & (~fs_both_zero) & fs_key_l.ult(fs_key_r)
        fs_eq = (~fs_nan) & (fs_l.__eq__(fs_r) | fs_both_zero)
        fs_ge = (~fs_nan) & (fs_both_zero | fs_key_l.uge(fs_key_r))

        fp_is_fs = srcr_type.__eq__(c(1, width=2))
        fp_eq = fp_is_fs._select_internal(fs_eq, fd_eq)
        fp_lt = fp_is_fs._select_internal(fs_lt, fd_lt)
        fp_ge = fp_is_fs._select_internal(fs_ge, fd_ge)
        if op_feq:
            alu = fp_eq._select_internal(1, z64)
            is_load = z1
            is_store = z1
            size = z4
            addr = z64
            wdata = z64
        if op_flt:
            alu = fp_lt._select_internal(1, z64)
            is_load = z1
            is_store = z1
            size = z4
            addr = z64
            wdata = z64
        if op_fge:
            alu = fp_ge._select_internal(1, z64)
            is_load = z1
            is_store = z1
            size = z4
            addr = z64
            wdata = z64

        if op_setc_geui:
            setc_bit = z64
            uimm = shl_var(m, imm, shamt)
            if srcl_val.uge(uimm):
                setc_bit = 1
            alu = setc_bit
            is_load = z1
            is_store = z1
            size = z4
            addr = z64
            wdata = z64
        if op_setc_eqi:
            setc_bit = z64
            simm = shl_var(m, imm, shamt)
            if srcl_val == simm:
                setc_bit = 1
            alu = setc_bit
            is_load = z1
            is_store = z1
            size = z4
            addr = z64
            wdata = z64
        if op_setc_nei:
            setc_bit = z64
            simm = shl_var(m, imm, shamt)
            if srcl_val != simm:
                setc_bit = 1
            alu = setc_bit
            is_load = z1
            is_store = z1
            size = z4
            addr = z64
            wdata = z64
        if op_setc_andi:
            setc_bit = z64
            simm = shl_var(m, imm, shamt)
            if (srcl_val & simm) != 0:
                setc_bit = 1
            alu = setc_bit
            is_load = z1
            is_store = z1
            size = z4
            addr = z64
            wdata = z64
        if op_setc_ori:
            setc_bit = z64
            simm = shl_var(m, imm, shamt)
            if (srcl_val | simm) != 0:
                setc_bit = 1
            alu = setc_bit
            is_load = z1
            is_store = z1
            size = z4
            addr = z64
            wdata = z64
        if op_setc_lti:
            setc_bit = z64
            simm = shl_var(m, imm, shamt)
            if srcl_val.slt(simm):
                setc_bit = 1
            alu = setc_bit
            is_load = z1
            is_store = z1
            size = z4
            addr = z64
            wdata = z64
        if op_setc_gei:
            setc_bit = z64
            simm = shl_var(m, imm, shamt)
            if ~srcl_val.slt(simm):
                setc_bit = 1
            alu = setc_bit
            is_load = z1
            is_store = z1
            size = z4
            addr = z64
            wdata = z64
        if op_setc_ltui:
            setc_bit = z64
            uimm = shl_var(m, imm, shamt)
            if srcl_val.ult(uimm):
                setc_bit = 1
            alu = setc_bit
            is_load = z1
            is_store = z1
            size = z4
            addr = z64
            wdata = z64
        if op_setc_eq:
            setc_bit = z64
            if srcl_val == srcr_addsub_nosh:
                setc_bit = 1
            alu = setc_bit
            is_load = z1
            is_store = z1
            size = z4
            addr = z64
            wdata = z64
        if op_setc_ne:
            setc_bit = z64
            if srcl_val != srcr_addsub_nosh:
                setc_bit = 1
            alu = setc_bit
            is_load = z1
            is_store = z1
            size = z4
            addr = z64
            wdata = z64
        if op_setc_and:
            setc_bit = z64
            if (srcl_val & srcr_logic) != 0:
                setc_bit = 1
            alu = setc_bit
            is_load = z1
            is_store = z1
            size = z4
            addr = z64
            wdata = z64
        if op_setc_or:
            setc_bit = z64
            if (srcl_val | srcr_logic) != 0:
                setc_bit = 1
            alu = setc_bit
            is_load = z1
            is_store = z1
            size = z4
            addr = z64
            wdata = z64
        if op_setc_lt:
            setc_bit = z64
            if srcl_val.slt(srcr_addsub_nosh):
                setc_bit = 1
            alu = setc_bit
            is_load = z1
            is_store = z1
            size = z4
            addr = z64
            wdata = z64
        if op_setc_ltu:
            setc_bit = z64
            if srcl_val.ult(srcr_addsub_nosh):
                setc_bit = 1
            alu = setc_bit
            is_load = z1
            is_store = z1
            size = z4
            addr = z64
            wdata = z64
        if op_setc_ge:
            setc_bit = z64
            if ~srcl_val.slt(srcr_addsub_nosh):
                setc_bit = 1
            alu = setc_bit
            is_load = z1
            is_store = z1
            size = z4
            addr = z64
            wdata = z64
        if op_setc_geu:
            setc_bit = z64
            if srcl_val.uge(srcr_addsub_nosh):
                setc_bit = 1
            alu = setc_bit
            is_load = z1
            is_store = z1
            size = z4
            addr = z64
            wdata = z64
        if op_c_setc_ne:
            setc_bit = z64
            if srcl_val != srcr_val:
                setc_bit = 1
            alu = setc_bit
            is_load = z1
            is_store = z1
            size = z4
            addr = z64
            wdata = z64

        if op_hl_lui:
            alu = imm
            is_load = z1
            is_store = z1
            size = z4
            addr = z64
            wdata = z64

        csel_srcr = srcr_addsub_nosh
        csel_val = srcl_val
        if srcp_val != 0:
            csel_val = csel_srcr
        if op_csel:
            alu = csel_val
            is_load = z1
            is_store = z1
            size = z4
            addr = z64
            wdata = z64

        is_lwi = op_lwi | op_c_lwi
        lwi_addr = srcl_val + off
        if is_lwi:
            alu = z64
            is_load = 1
            is_store = z1
            size = 4
            addr = lwi_addr
            wdata = z64

        if op_lwui:
            alu = z64
            is_load = 1
            is_store = z1
            size = 4
            addr = lwi_addr
            wdata = z64

        if op_lbi | op_lbui:
            alu = z64
            is_load = 1
            is_store = z1
            size = 1
            addr = srcl_val + imm
            wdata = z64

        h_off = imm.shl(amount=1)
        if op_lhi | op_lhui:
            alu = z64
            is_load = 1
            is_store = z1
            size = 2
            addr = srcl_val + h_off
            wdata = z64

        idx_addr = srcl_val + idx_mod_shl
        if op_lb:
            alu = z64
            is_load = 1
            is_store = z1
            size = 1
            addr = idx_addr
            wdata = z64
        if op_lbu:
            alu = z64
            is_load = 1
            is_store = z1
            size = 1
            addr = idx_addr
            wdata = z64
        if op_lh:
            alu = z64
            is_load = 1
            is_store = z1
            size = 2
            addr = idx_addr
            wdata = z64
        if op_lhu:
            alu = z64
            is_load = 1
            is_store = z1
            size = 2
            addr = idx_addr
            wdata = z64
        if op_lw:
            alu = z64
            is_load = 1
            is_store = z1
            size = 4
            addr = idx_addr
            wdata = z64
        if op_lwu:
            alu = z64
            is_load = 1
            is_store = z1
            size = 4
            addr = idx_addr
            wdata = z64
        if op_ld:
            alu = z64
            is_load = 1
            is_store = z1
            size = 8
            addr = idx_addr
            wdata = z64

        ldi_off = imm.shl(amount=3)
        if op_ldi | op_c_ldi:
            alu = z64
            is_load = 1
            is_store = z1
            size = 8
            addr = srcl_val + ldi_off
            wdata = z64

        if op_sbi:
            alu = z64
            is_load = z1
            is_store = 1
            size = 1
            addr = srcr_val + imm
            wdata = srcl_val

        if op_shi:
            alu = z64
            is_load = z1
            is_store = 1
            size = 2
            addr = srcr_val + h_off
            wdata = srcl_val

        store_addr = srcl_val + off
        store_data = srcr_val
        if op_swi:
            store_addr = srcr_val + off
            store_data = srcl_val
        if op_swi | op_c_swi:
            alu = z64
            is_load = z1
            is_store = 1
            size = 4
            addr = store_addr
            wdata = store_data

        if op_sb:
            alu = z64
            is_load = z1
            is_store = 1
            size = 1
            addr = idx_addr
            wdata = srcp_val
        if op_sh:
            alu = z64
            is_load = z1
            is_store = 1
            size = 2
            addr = idx_addr
            wdata = srcp_val
        if op_sw:
            alu = z64
            is_load = z1
            is_store = 1
            size = 4
            addr = idx_addr
            wdata = srcp_val
        if op_sd:
            alu = z64
            is_load = z1
            is_store = 1
            size = 8
            addr = idx_addr
            wdata = srcp_val

        sdi_off = imm.shl(amount=3)
        if op_c_sdi:
            alu = z64
            is_load = z1
            is_store = 1
            size = 8
            addr = srcl_val + sdi_off
            wdata = srcr_val

        sdi_addr = srcr_val + sdi_off
        if op_sdi:
            alu = z64
            is_load = z1
            is_store = 1
            size = 8
            addr = sdi_addr
            wdata = srcl_val

        if op_hl_lb_pcr | op_hl_lbu_pcr:
            alu = z64
            is_load = 1
            is_store = z1
            size = 1
            addr = pc + imm
            wdata = z64
        if op_hl_lh_pcr | op_hl_lhu_pcr:
            alu = z64
            is_load = 1
            is_store = z1
            size = 2
            addr = pc + imm
            wdata = z64
        if op_hl_lw_pcr | op_hl_lwu_pcr:
            alu = z64
            is_load = 1
            is_store = z1
            size = 4
            addr = pc + imm
            wdata = z64
        if op_hl_ld_pcr:
            alu = z64
            is_load = 1
            is_store = z1
            size = 8
            addr = pc + imm
            wdata = z64

        if op_hl_sb_pcr:
            alu = z64
            is_load = z1
            is_store = 1
            size = 1
            addr = pc + imm
            wdata = srcl_val
        if op_hl_sh_pcr:
            alu = z64
            is_load = z1
            is_store = 1
            size = 2
            addr = pc + imm
            wdata = srcl_val
        if op_hl_sw_pcr:
            alu = z64
            is_load = z1
            is_store = 1
            size = 4
            addr = pc + imm
            wdata = srcl_val
        if op_hl_sd_pcr:
            alu = z64
            is_load = z1
            is_store = 1
            size = 8
            addr = pc + imm
            wdata = srcl_val

        if op_c_addi:
            alu = srcl_val + imm
            is_load = z1
            is_store = z1
            size = z4
            addr = z64
            wdata = z64
        if op_c_add:
            alu = srcl_val + srcr_val
            is_load = z1
            is_store = z1
            size = z4
            addr = z64
            wdata = z64
        if op_c_sub:
            alu = srcl_val - srcr_val
            is_load = z1
            is_store = z1
            size = z4
            addr = z64
            wdata = z64
        if op_c_and:
            alu = srcl_val & srcr_val
            is_load = z1
            is_store = z1
            size = z4
            addr = z64
            wdata = z64
        if op_c_or:
            alu = srcl_val | srcr_val
            is_load = z1
            is_store = z1
            size = z4
            addr = z64
            wdata = z64

        if op_c_sext_w:
            alu = srcl_val._trunc(width=32)._sext(width=64)
            is_load = z1
            is_store = z1
            size = z4
            addr = z64
            wdata = z64
        if op_c_zext_w:
            alu = srcl_val._trunc(width=32)._zext(width=64)
            is_load = z1
            is_store = z1
            size = z4
            addr = z64
            wdata = z64

        return ExecOut(alu=alu, is_load=is_load, is_store=is_store, size=size, addr=addr, wdata=wdata)
