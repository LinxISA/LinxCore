from __future__ import annotations

from pycircuit import Circuit, module

from common.decode_f4 import decode_f4_bundle
from common.isa import (
    BK_CALL,
    BK_COND,
    BK_DIRECT,
    BK_FALL,
    BK_ICALL,
    BK_IND,
    BK_RET,
    OP_BSTART_STD_CALL,
    OP_BSTART_STD_COND,
    OP_BSTART_STD_DIRECT,
    OP_BSTART_STD_FALL,
    OP_C_BSTOP,
    OP_C_BSTART_COND,
    OP_C_BSTART_DIRECT,
    OP_C_BSTART_STD,
    OP_C_LWI,
    OP_C_SDI,
    OP_C_SWI,
    OP_FENTRY,
    OP_FEXIT,
    OP_FRET_RA,
    OP_FRET_STK,
    OP_HL_SB_PCR,
    OP_HL_SD_PCR,
    OP_HL_SH_PCR,
    OP_HL_SW_PCR,
    OP_SB,
    OP_SBI,
    OP_SD,
    OP_SDI,
    OP_SH,
    OP_SHI,
    OP_SW,
    OP_SWI,
    REG_INVALID,
)
from common.util import lshr_var


def _op_is(m: Circuit, op, *codes: int):
    c = m.const
    v = c(0, width=1)
    for code in codes:
        v = v | op.eq(c(code, width=12))
    return v


@module(name="LinxCoreDecodeStage")
def build_decode_stage(m: Circuit, *, dispatch_w: int = 4) -> None:
    f4_valid = m.input("f4_valid", width=1)
    f4_pc = m.input("f4_pc", width=64)
    f4_window = m.input("f4_window", width=64)
    f4_checkpoint_id = m.input("f4_checkpoint_id", width=6)

    c = m.const
    f4_bundle = decode_f4_bundle(m, f4_window)

    disp_count = c(0, width=3)

    for slot in range(dispatch_w):
        dec = f4_bundle.dec[slot]
        v = f4_valid & f4_bundle.valid[slot]
        off = f4_bundle.off_bytes[slot]
        pc = f4_pc + off.zext(width=64)

        op = dec.op
        ln = dec.len_bytes
        regdst = dec.regdst
        srcl = dec.srcl
        srcr = dec.srcr
        srcr_type = dec.srcr_type
        shamt = dec.shamt
        srcp = dec.srcp
        imm = dec.imm
        off_sh = off.zext(width=6).shl(amount=3)
        slot_window = lshr_var(m, f4_window, off_sh)
        insn_raw = slot_window
        insn_raw = ln.eq(c(2, width=3)).select(slot_window & c(0xFFFF, width=64), insn_raw)
        insn_raw = ln.eq(c(4, width=3)).select(slot_window & c(0xFFFF_FFFF, width=64), insn_raw)
        insn_raw = ln.eq(c(6, width=3)).select(slot_window & c(0xFFFF_FFFF_FFFF, width=64), insn_raw)

        is_macro = _op_is(m, op, OP_FENTRY, OP_FEXIT, OP_FRET_RA, OP_FRET_STK)
        is_bstart = _op_is(
            m,
            op,
            OP_C_BSTART_STD,
            OP_C_BSTART_COND,
            OP_C_BSTART_DIRECT,
            OP_BSTART_STD_FALL,
            OP_BSTART_STD_DIRECT,
            OP_BSTART_STD_COND,
            OP_BSTART_STD_CALL,
        )
        is_bstop = op.eq(c(OP_C_BSTOP, width=12))
        is_boundary = is_bstart | is_bstop
        is_start = (
            _op_is(
                m,
                op,
                OP_C_BSTART_STD,
                OP_C_BSTART_COND,
                OP_C_BSTART_DIRECT,
                OP_BSTART_STD_FALL,
                OP_BSTART_STD_DIRECT,
                OP_BSTART_STD_COND,
                OP_BSTART_STD_CALL,
            )
            | is_macro
        )
        boundary_kind = c(BK_FALL, width=3)
        boundary_kind = op.eq(c(OP_C_BSTART_COND, width=12)).select(c(BK_COND, width=3), boundary_kind)
        boundary_kind = op.eq(c(OP_C_BSTART_DIRECT, width=12)).select(c(BK_DIRECT, width=3), boundary_kind)
        boundary_kind = op.eq(c(OP_BSTART_STD_FALL, width=12)).select(c(BK_FALL, width=3), boundary_kind)
        boundary_kind = op.eq(c(OP_BSTART_STD_DIRECT, width=12)).select(c(BK_DIRECT, width=3), boundary_kind)
        boundary_kind = op.eq(c(OP_BSTART_STD_COND, width=12)).select(c(BK_COND, width=3), boundary_kind)
        boundary_kind = op.eq(c(OP_BSTART_STD_CALL, width=12)).select(c(BK_CALL, width=3), boundary_kind)
        brtype = imm.trunc(width=3)
        boundary_kind = (op.eq(c(OP_C_BSTART_STD, width=12)) & brtype.eq(c(0, width=3))).select(c(BK_FALL, width=3), boundary_kind)
        boundary_kind = (op.eq(c(OP_C_BSTART_STD, width=12)) & brtype.eq(c(2, width=3))).select(c(BK_DIRECT, width=3), boundary_kind)
        boundary_kind = (op.eq(c(OP_C_BSTART_STD, width=12)) & brtype.eq(c(3, width=3))).select(c(BK_COND, width=3), boundary_kind)
        boundary_kind = (op.eq(c(OP_C_BSTART_STD, width=12)) & brtype.eq(c(4, width=3))).select(c(BK_CALL, width=3), boundary_kind)
        boundary_kind = (op.eq(c(OP_C_BSTART_STD, width=12)) & brtype.eq(c(5, width=3))).select(c(BK_IND, width=3), boundary_kind)
        boundary_kind = (op.eq(c(OP_C_BSTART_STD, width=12)) & brtype.eq(c(6, width=3))).select(c(BK_ICALL, width=3), boundary_kind)
        boundary_kind = (op.eq(c(OP_C_BSTART_STD, width=12)) & brtype.eq(c(7, width=3))).select(c(BK_RET, width=3), boundary_kind)
        boundary_target = c(0, width=64)
        boundary_target = op.eq(c(OP_C_BSTART_COND, width=12)).select(pc + imm, boundary_target)
        boundary_target = op.eq(c(OP_C_BSTART_DIRECT, width=12)).select(pc + imm, boundary_target)
        boundary_target = op.eq(c(OP_BSTART_STD_DIRECT, width=12)).select(pc + imm, boundary_target)
        boundary_target = op.eq(c(OP_BSTART_STD_COND, width=12)).select(pc + imm, boundary_target)
        boundary_target = op.eq(c(OP_BSTART_STD_CALL, width=12)).select(pc + imm, boundary_target)
        pred_take = c(1, width=1)
        pred_take = boundary_kind.eq(c(BK_COND, width=3)).select(c(0, width=1), pred_take)
        pred_take = boundary_kind.eq(c(BK_RET, width=3)).select(c(0, width=1), pred_take)
        resolved_d2 = is_boundary
        push_t = regdst.eq(c(31, width=6)) | op.eq(c(OP_C_LWI, width=12))
        push_u = regdst.eq(c(30, width=6))
        is_store = _op_is(
            m,
            op,
            OP_SBI,
            OP_SHI,
            OP_SWI,
            OP_C_SWI,
            OP_SDI,
            OP_C_SDI,
            OP_SB,
            OP_SH,
            OP_SW,
            OP_SD,
            OP_HL_SB_PCR,
            OP_HL_SH_PCR,
            OP_HL_SW_PCR,
            OP_HL_SD_PCR,
        )

        dst_is_invalid = regdst.eq(c(REG_INVALID, width=6))
        dst_is_zero = regdst.eq(c(0, width=6))
        dst_is_gpr_range = (~regdst[5]) & (~(regdst[4] & regdst[3]))
        dst_is_gpr = dst_is_gpr_range & (~dst_is_invalid) & (~dst_is_zero) & (~push_t) & (~push_u)
        need_pdst = dst_is_gpr | push_t | push_u

        dst_kind = c(0, width=2)
        dst_kind = dst_is_gpr.select(c(1, width=2), dst_kind)
        dst_kind = push_t.select(c(2, width=2), dst_kind)
        dst_kind = push_u.select(c(3, width=2), dst_kind)
        ckpt_tag = is_start.select(f4_checkpoint_id + c(slot, width=6), c(0, width=6))

        m.output(f"valid{slot}", v)
        m.output(f"pc{slot}", pc)
        m.output(f"op{slot}", op)
        m.output(f"len{slot}", ln)
        m.output(f"regdst{slot}", regdst)
        m.output(f"srcl{slot}", srcl)
        m.output(f"srcr{slot}", srcr)
        m.output(f"srcr_type{slot}", srcr_type)
        m.output(f"shamt{slot}", shamt)
        m.output(f"srcp{slot}", srcp)
        m.output(f"imm{slot}", imm)
        m.output(f"insn_raw{slot}", insn_raw)
        m.output(f"is_start_marker{slot}", is_start)
        m.output(f"is_boundary{slot}", is_boundary)
        m.output(f"is_bstart{slot}", is_bstart)
        m.output(f"is_bstop{slot}", is_bstop)
        m.output(f"boundary_kind{slot}", boundary_kind)
        m.output(f"boundary_target{slot}", boundary_target)
        m.output(f"pred_take{slot}", pred_take)
        m.output(f"resolved_d2{slot}", resolved_d2)
        m.output(f"push_t{slot}", push_t)
        m.output(f"push_u{slot}", push_u)
        m.output(f"is_store{slot}", is_store)
        m.output(f"dst_is_gpr{slot}", dst_is_gpr)
        m.output(f"need_pdst{slot}", need_pdst)
        m.output(f"dst_kind{slot}", dst_kind)
        m.output(f"checkpoint_id{slot}", ckpt_tag)
        disp_count = disp_count + v.zext(width=3)

    m.output("dispatch_count", disp_count)
    m.output("dec_op", f4_bundle.dec[0].op)
