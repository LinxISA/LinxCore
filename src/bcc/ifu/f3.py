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
    OP_C_BSTART_COND,
    OP_C_BSTART_DIRECT,
    OP_C_BSTART_STD,
)
from bcc.frontend.ibuffer import build_ibuffer


@module(name="JanusBccIfuF3")
def build_janus_bcc_ifu_f3(m: Circuit, *, ibuf_depth: int = 8) -> None:
    clk_top = m.clock("clk")
    rst_top = m.reset("rst")

    f2_to_f3_stage_pc_f2 = m.input("f2_to_f3_stage_pc_f2", width=64)
    f2_to_f3_stage_window_f2 = m.input("f2_to_f3_stage_window_f2", width=64)
    m.input("f2_to_f3_stage_bundle128_f2", width=1024)
    m.input("f2_to_f3_stage_bundle_base_pc_f2", width=64)
    m.input("f2_to_f3_stage_slot_base_offset_f2", width=7)
    f2_to_f3_stage_pkt_uid_f2 = m.input("f2_to_f3_stage_pkt_uid_f2", width=64)
    f2_to_f3_stage_valid_f2 = m.input("f2_to_f3_stage_valid_f2", width=1)
    m.input("f2_to_f3_stage_miss_f2", width=1)
    m.input("f2_to_f3_stage_stall_f2", width=1)
    ctrl_to_f3_stage_checkpoint_id_f3 = m.input("ctrl_to_f3_stage_checkpoint_id_f3", width=6)

    backend_ready_top = m.input("backend_ready_top", width=1)
    flush_valid_fls = m.input("flush_valid_fls", width=1)

    c = m.const
    dec_bundle_f3 = decode_f4_bundle(m, f2_to_f3_stage_window_f2)
    dec0_f3 = dec_bundle_f3.dec[0]
    dec0_valid_f3 = f2_to_f3_stage_valid_f2 & dec_bundle_f3.valid[0]
    dec0_pc_f3 = f2_to_f3_stage_pc_f2 + dec_bundle_f3.off_bytes[0].zext(width=64)

    op0_f3 = dec0_f3.op
    imm0_f3 = dec0_f3.imm
    is_bstart0_f3 = (
        (op0_f3 == c(OP_C_BSTART_STD, width=12))
        | (op0_f3 == c(OP_C_BSTART_COND, width=12))
        | (op0_f3 == c(OP_C_BSTART_DIRECT, width=12))
        | (op0_f3 == c(OP_BSTART_STD_FALL, width=12))
        | (op0_f3 == c(OP_BSTART_STD_DIRECT, width=12))
        | (op0_f3 == c(OP_BSTART_STD_COND, width=12))
        | (op0_f3 == c(OP_BSTART_STD_CALL, width=12))
    )

    bstart_kind_f3 = c(BK_FALL, width=3)
    bstart_kind_f3 = (op0_f3 == c(OP_C_BSTART_COND, width=12))._select_internal(c(BK_COND, width=3), bstart_kind_f3)
    bstart_kind_f3 = (op0_f3 == c(OP_C_BSTART_DIRECT, width=12))._select_internal(c(BK_DIRECT, width=3), bstart_kind_f3)
    bstart_kind_f3 = (op0_f3 == c(OP_BSTART_STD_FALL, width=12))._select_internal(c(BK_FALL, width=3), bstart_kind_f3)
    bstart_kind_f3 = (op0_f3 == c(OP_BSTART_STD_DIRECT, width=12))._select_internal(c(BK_DIRECT, width=3), bstart_kind_f3)
    bstart_kind_f3 = (op0_f3 == c(OP_BSTART_STD_COND, width=12))._select_internal(c(BK_COND, width=3), bstart_kind_f3)
    bstart_kind_f3 = (op0_f3 == c(OP_BSTART_STD_CALL, width=12))._select_internal(c(BK_CALL, width=3), bstart_kind_f3)
    brtype_f3 = imm0_f3.trunc(width=3)
    bstart_kind_f3 = ((op0_f3 == c(OP_C_BSTART_STD, width=12)) & brtype_f3.eq(c(2, width=3)))._select_internal(
        c(BK_DIRECT, width=3), bstart_kind_f3
    )
    bstart_kind_f3 = ((op0_f3 == c(OP_C_BSTART_STD, width=12)) & brtype_f3.eq(c(3, width=3)))._select_internal(
        c(BK_COND, width=3), bstart_kind_f3
    )
    bstart_kind_f3 = ((op0_f3 == c(OP_C_BSTART_STD, width=12)) & brtype_f3.eq(c(4, width=3)))._select_internal(
        c(BK_CALL, width=3), bstart_kind_f3
    )
    bstart_kind_f3 = ((op0_f3 == c(OP_C_BSTART_STD, width=12)) & brtype_f3.eq(c(5, width=3)))._select_internal(
        c(BK_IND, width=3), bstart_kind_f3
    )
    bstart_kind_f3 = ((op0_f3 == c(OP_C_BSTART_STD, width=12)) & brtype_f3.eq(c(6, width=3)))._select_internal(
        c(BK_ICALL, width=3), bstart_kind_f3
    )
    bstart_kind_f3 = ((op0_f3 == c(OP_C_BSTART_STD, width=12)) & brtype_f3.eq(c(7, width=3)))._select_internal(
        c(BK_RET, width=3), bstart_kind_f3
    )
    bstart_target_f3 = c(0, width=64)
    bstart_target_f3 = (op0_f3 == c(OP_C_BSTART_COND, width=12))._select_internal(dec0_pc_f3 + imm0_f3, bstart_target_f3)
    bstart_target_f3 = (op0_f3 == c(OP_C_BSTART_DIRECT, width=12))._select_internal(dec0_pc_f3 + imm0_f3, bstart_target_f3)
    bstart_target_f3 = (op0_f3 == c(OP_BSTART_STD_DIRECT, width=12))._select_internal(dec0_pc_f3 + imm0_f3, bstart_target_f3)
    bstart_target_f3 = (op0_f3 == c(OP_BSTART_STD_COND, width=12))._select_internal(dec0_pc_f3 + imm0_f3, bstart_target_f3)
    bstart_target_f3 = (op0_f3 == c(OP_BSTART_STD_CALL, width=12))._select_internal(dec0_pc_f3 + imm0_f3, bstart_target_f3)
    cond_pred_take_f3 = bstart_target_f3.ult(dec0_pc_f3)
    pred_take_f3 = c(1, width=1)
    pred_take_f3 = bstart_kind_f3.eq(c(BK_COND, width=3))._select_internal(cond_pred_take_f3, pred_take_f3)
    pred_take_f3 = bstart_kind_f3.eq(c(BK_RET, width=3))._select_internal(c(0, width=1), pred_take_f3)
    bstart_meta_valid_f3 = dec0_valid_f3 & is_bstart0_f3

    ibuf_f3 = m.instance(
        build_ibuffer,
        name="janus_f3_ibuffer",
        module_name="JanusBccIfuF3IBuffer",
        params={"depth": ibuf_depth},
        clk=clk_top,
        rst=rst_top,
        push_valid=f2_to_f3_stage_valid_f2,
        push_pc=f2_to_f3_stage_pc_f2,
        push_window=f2_to_f3_stage_window_f2,
        push_pkt_uid=f2_to_f3_stage_pkt_uid_f2,
        pop_ready=backend_ready_top,
        flush_valid=flush_valid_fls,
    )

    m.output("f3_to_ib_stage_pc_f3", f2_to_f3_stage_pc_f2)
    m.output("f3_to_ib_stage_window_f3", f2_to_f3_stage_window_f2)
    m.output("f3_to_ib_stage_pkt_uid_f3", f2_to_f3_stage_pkt_uid_f2)
    m.output("f3_to_ib_stage_valid_f3", f2_to_f3_stage_valid_f2)
    m.output("f3_to_ib_stage_checkpoint_id_f3", ctrl_to_f3_stage_checkpoint_id_f3)

    m.output("ib_to_f4_stage_pc_ib", ibuf_f3["out_pc"])
    m.output("ib_to_f4_stage_window_ib", ibuf_f3["out_window"])
    m.output("ib_to_f4_stage_pkt_uid_ib", ibuf_f3["out_pkt_uid"])
    m.output("ib_to_f4_stage_valid_ib", ibuf_f3["out_valid"])
    m.output("ib_to_f4_stage_checkpoint_id_ib", ctrl_to_f3_stage_checkpoint_id_f3)

    # Backward-compatible outputs consumed by current top wiring.
    m.output("f3_to_f4_stage_pc_f3", ibuf_f3["out_pc"])
    m.output("f3_to_f4_stage_window_f3", ibuf_f3["out_window"])
    m.output("f3_to_f4_stage_pkt_uid_f3", ibuf_f3["out_pkt_uid"])
    m.output("f3_to_f4_stage_valid_f3", ibuf_f3["out_valid"])
    m.output("f3_to_f4_stage_checkpoint_id_f3", ctrl_to_f3_stage_checkpoint_id_f3)
    m.output("f3_to_pcb_stage_bstart_valid_f3", bstart_meta_valid_f3)
    m.output("f3_to_pcb_stage_bstart_pc_f3", dec0_pc_f3)
    m.output("f3_to_pcb_stage_bstart_kind_f3", bstart_kind_f3)
    m.output("f3_to_pcb_stage_bstart_target_f3", bstart_target_f3)
    m.output("f3_to_pcb_stage_pred_take_f3", pred_take_f3)
    m.output("f3_ibuf_count_f3", ibuf_f3["count_dbg"])
    m.output("f3_ibuf_ready_f3", ibuf_f3["push_ready"])
    m.output("f3_pop_fire_f3", ibuf_f3["pop_fire"])
    m.output("ib_head_pc_f3", ibuf_f3["out_pc"])
    m.output("ib_head_uid_f3", ibuf_f3["out_pkt_uid"])
    m.output("ib_valid_f3", ibuf_f3["out_valid"])
    m.output("f3_one_f3", c(1, width=1))
