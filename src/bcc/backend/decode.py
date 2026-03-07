from __future__ import annotations

from pycircuit import Circuit, function, module

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
    OP_C_SETRET,
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
    OP_SETRET,
    OP_SH,
    OP_SHI,
    OP_SW,
    OP_SWI,
    REG_INVALID,
)
from common.util import lshr_var
from .modules.decode_window import build_decode_window


@function
def _op_is(m: Circuit, op, *codes: int):
    c = m.const
    v = c(0, width=1)
    for code in codes:
        v = v | op.__eq__(c(code, width=12))
    return v


@module(name="LinxCoreDecodeStage")
def build_decode_stage(m: Circuit, *, dispatch_w: int = 4) -> None:
    f4_valid = m.input("f4_valid", width=1)
    f4_pc = m.input("f4_pc", width=64)
    f4_window = m.input("f4_window", width=64)
    f4_checkpoint_id = m.input("f4_checkpoint_id", width=6)
    f4_pkt_uid = m.input("f4_pkt_uid", width=64)

    c = m.const
    z4 = c(0, width=4)
    b8 = c(8, width=4)
    b2 = c(2, width=4)

    decode_wins = []
    decode_wins.append(f4_window)
    decoders = []
    decoders.append(
        m.instance_auto(
            build_decode_window,
            name="decode_window_0",
            window=decode_wins[0],
        )
    )

    dec_lens = []
    dec_lens.append(decoders[0]["len_bytes"]._zext(width=4))
    slot_valids = []
    slot_valids.append(~dec_lens[0].__eq__(z4))
    slot_offsets = []
    slot_offsets.append(z4)

    is_macro0 = (
        decoders[0]["op"].__eq__(c(OP_FENTRY, width=12))
        | decoders[0]["op"].__eq__(c(OP_FEXIT, width=12))
        | decoders[0]["op"].__eq__(c(OP_FRET_RA, width=12))
        | decoders[0]["op"].__eq__(c(OP_FRET_STK, width=12))
    )

    prev_rem = b8 - dec_lens[0]
    prev_valid = slot_valids[0]
    prev_off = dec_lens[0]

    for slot in range(1, dispatch_w):
        shift = prev_off._zext(width=6).shl(amount=3)
        win = lshr_var(m, f4_window, shift)
        decode_wins.append(win)
        dec = m.instance_auto(
            build_decode_window,
            name=f"decode_window_{slot}",
            window=win,
        )
        decoders.append(dec)
        dec_len = dec["len_bytes"]._zext(width=4)
        dec_lens.append(dec_len)
        slot_offsets.append(prev_off)
        if slot == 1:
            slot_valid = prev_valid & (~is_macro0) & prev_rem.uge(b2) & (~dec_len.__eq__(z4)) & dec_len.ule(prev_rem)
        else:
            slot_valid = prev_valid & prev_rem.uge(b2) & (~dec_len.__eq__(z4)) & dec_len.ule(prev_rem)
        slot_valids.append(slot_valid)
        prev_rem = prev_rem - dec_len
        prev_off = prev_off + dec_len
        prev_valid = slot_valid

    disp_count = c(0, width=3)

    for slot in range(dispatch_w):
        dec = decoders[slot]
        v = f4_valid & slot_valids[slot]
        off = slot_offsets[slot]
        pc = f4_pc + off._zext(width=64)

        op = dec["op"]
        ln = dec["len_bytes"]
        regdst = dec["regdst"]
        srcl = dec["srcl"]
        srcr = dec["srcr"]
        srcr_type = dec["srcr_type"]
        shamt = dec["shamt"]
        srcp = dec["srcp"]
        imm = dec["imm"]
        off_sh = off._zext(width=6).shl(amount=3)
        slot_window = lshr_var(m, f4_window, off_sh)
        insn_raw = slot_window
        insn_raw = ln.__eq__(c(2, width=3))._select_internal(slot_window & c(0xFFFF, width=64), insn_raw)
        insn_raw = ln.__eq__(c(4, width=3))._select_internal(slot_window & c(0xFFFF_FFFF, width=64), insn_raw)
        insn_raw = ln.__eq__(c(6, width=3))._select_internal(slot_window & c(0xFFFF_FFFF_FFFF, width=64), insn_raw)

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
        is_bstop = op.__eq__(c(OP_C_BSTOP, width=12))
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
        boundary_kind = op.__eq__(c(OP_C_BSTART_COND, width=12))._select_internal(c(BK_COND, width=3), boundary_kind)
        boundary_kind = op.__eq__(c(OP_C_BSTART_DIRECT, width=12))._select_internal(c(BK_DIRECT, width=3), boundary_kind)
        boundary_kind = op.__eq__(c(OP_BSTART_STD_FALL, width=12))._select_internal(c(BK_FALL, width=3), boundary_kind)
        boundary_kind = op.__eq__(c(OP_BSTART_STD_DIRECT, width=12))._select_internal(c(BK_DIRECT, width=3), boundary_kind)
        boundary_kind = op.__eq__(c(OP_BSTART_STD_COND, width=12))._select_internal(c(BK_COND, width=3), boundary_kind)
        boundary_kind = op.__eq__(c(OP_BSTART_STD_CALL, width=12))._select_internal(c(BK_CALL, width=3), boundary_kind)
        brtype = imm._trunc(width=3)
        boundary_kind = (op.__eq__(c(OP_C_BSTART_STD, width=12)) & brtype.__eq__(c(0, width=3)))._select_internal(c(BK_FALL, width=3), boundary_kind)
        boundary_kind = (op.__eq__(c(OP_C_BSTART_STD, width=12)) & brtype.__eq__(c(2, width=3)))._select_internal(c(BK_DIRECT, width=3), boundary_kind)
        boundary_kind = (op.__eq__(c(OP_C_BSTART_STD, width=12)) & brtype.__eq__(c(3, width=3)))._select_internal(c(BK_COND, width=3), boundary_kind)
        boundary_kind = (op.__eq__(c(OP_C_BSTART_STD, width=12)) & brtype.__eq__(c(4, width=3)))._select_internal(c(BK_CALL, width=3), boundary_kind)
        boundary_kind = (op.__eq__(c(OP_C_BSTART_STD, width=12)) & brtype.__eq__(c(5, width=3)))._select_internal(c(BK_IND, width=3), boundary_kind)
        boundary_kind = (op.__eq__(c(OP_C_BSTART_STD, width=12)) & brtype.__eq__(c(6, width=3)))._select_internal(c(BK_ICALL, width=3), boundary_kind)
        boundary_kind = (op.__eq__(c(OP_C_BSTART_STD, width=12)) & brtype.__eq__(c(7, width=3)))._select_internal(c(BK_RET, width=3), boundary_kind)
        boundary_target = c(0, width=64)
        boundary_target = op.__eq__(c(OP_C_BSTART_COND, width=12))._select_internal(pc + imm, boundary_target)
        boundary_target = op.__eq__(c(OP_C_BSTART_DIRECT, width=12))._select_internal(pc + imm, boundary_target)
        boundary_target = op.__eq__(c(OP_BSTART_STD_DIRECT, width=12))._select_internal(pc + imm, boundary_target)
        boundary_target = op.__eq__(c(OP_BSTART_STD_COND, width=12))._select_internal(pc + imm, boundary_target)
        boundary_target = op.__eq__(c(OP_BSTART_STD_CALL, width=12))._select_internal(pc + imm, boundary_target)
        # Static bring-up predictor:
        # - COND blocks: backward target => taken, forward => not-taken.
        # - RET blocks: keep not-taken baseline (validated by BRU setc path).
        cond_pred_take = boundary_target.ult(pc)
        pred_take = c(1, width=1)
        pred_take = boundary_kind.__eq__(c(BK_COND, width=3))._select_internal(cond_pred_take, pred_take)
        pred_take = boundary_kind.__eq__(c(BK_RET, width=3))._select_internal(c(0, width=1), pred_take)
        resolved_d2 = is_boundary | _op_is(m, op, OP_SETRET, OP_C_SETRET)
        push_t = regdst.__eq__(c(31, width=6)) | op.__eq__(c(OP_C_LWI, width=12))
        push_u = regdst.__eq__(c(30, width=6))
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

        dst_is_invalid = regdst.__eq__(c(REG_INVALID, width=6))
        dst_is_zero = regdst.__eq__(c(0, width=6))
        dst_is_gpr_range = (~regdst[5]) & (~(regdst[4] & regdst[3]))
        dst_is_gpr = dst_is_gpr_range & (~dst_is_invalid) & (~dst_is_zero) & (~push_t) & (~push_u)
        need_pdst = dst_is_gpr | push_t | push_u

        dst_kind = c(0, width=2)
        dst_kind = dst_is_gpr._select_internal(c(1, width=2), dst_kind)
        dst_kind = push_t._select_internal(c(2, width=2), dst_kind)
        dst_kind = push_u._select_internal(c(3, width=2), dst_kind)
        ckpt_tag = is_start._select_internal(f4_checkpoint_id + c(slot, width=6), c(0, width=6))
        # Global UID namespace:
        # lower 3 bits encode dynamic class/lane for pipeview stability.
        # - fetch/decode uops use lane ids [0..3]
        uop_uid = (f4_pkt_uid.shl(amount=3)) | c(slot, width=64)

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
        m.output(f"uop_uid{slot}", uop_uid)
        disp_count = disp_count + v._zext(width=3)

    m.output("dispatch_count", disp_count)
    m.output("dec_op", decoders[0]["op"])
