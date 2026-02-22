from __future__ import annotations

import os
from dataclasses import dataclass
from types import SimpleNamespace

# ENGINE_ORCHESTRATION_ONLY:
# Stage/component ownership is migrating to focused files. Keep this file as
# composition and compatibility glue; avoid adding new monolithic stage logic.

from pycircuit import Circuit, module
from pycircuit.dsl import Signal

from common.exec_uop import exec_uop_comb
from common.isa import (
    OP_BIOR,
    OP_BLOAD,
    BK_CALL,
    BK_COND,
    BK_DIRECT,
    BK_FALL,
    BK_ICALL,
    BK_IND,
    OP_BSTORE,
    OP_BTEXT,
    BK_RET,
    OP_BSTART_STD_COND,
    OP_BSTART_STD_CALL,
    OP_BSTART_STD_DIRECT,
    OP_BSTART_STD_FALL,
    OP_C_BSTART_COND,
    OP_C_BSTART_DIRECT,
    OP_C_BSTART_STD,
    OP_C_BSTOP,
    OP_C_LDI,
    OP_C_LWI,
    OP_C_SETC_NE,
    OP_C_SDI,
    OP_C_SWI,
    OP_C_SETC_EQ,
    OP_C_SETC_TGT,
    OP_EBREAK,
    OP_FENTRY,
    OP_FEXIT,
    OP_FRET_RA,
    OP_FRET_STK,
    OP_INVALID,
    OP_HL_LB_PCR,
    OP_HL_LBU_PCR,
    OP_HL_LD_PCR,
    OP_HL_LH_PCR,
    OP_HL_LHU_PCR,
    OP_HL_LW_PCR,
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
    OP_LW,
    OP_LWU,
    OP_LWUI,
    OP_SB,
    OP_SBI,
    OP_SD,
    OP_SH,
    OP_SHI,
    OP_SW,
    OP_LWI,
    OP_LDI,
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
    OP_SDI,
    OP_SWI,
    REG_INVALID,
    TRAP_BRU_RECOVERY_NOT_BSTART,
)
from common.util import make_consts
from .code_template_unit import build_code_template_unit
from .commit import build_commit_ctrl_stage, build_commit_head_stage, is_setc_any, is_setc_tgt
from .decode import build_decode_stage
from .dispatch import build_dispatch_stage
from .helpers import mask_bit, mux_by_uindex, onehot_from_tag
from .issue import build_iq_update_stage, build_issue_stage, pick_oldest_from_arrays
from .lsu import build_lsu_stage
from .params import OooParams
from .rename import build_commit_rename_stage, build_rename_stage
from .rob import build_rob_ctrl_stage, build_rob_entry_update_stage, is_macro_op, is_start_marker_op
from .state import make_core_ctrl_regs, make_iq_regs, make_prf, make_rename_regs, make_rob_regs
from .wakeup import build_head_wait_stage, compose_replay_cause, compose_wakeup_reason


@dataclass(frozen=True)
class BccOooExports:
    clk: Signal
    rst: Signal
    block_cmd_valid: Signal
    block_cmd_kind: Signal
    block_cmd_payload: Signal
    block_cmd_tile: Signal
    block_cmd_tag: Signal
    block_cmd_bid: Signal
    cycles: Signal
    halted: Signal


def build_bcc_ooo(m: Circuit, *, mem_bytes: int, params: OooParams | None = None) -> BccOooExports:
    p = params or OooParams()

    clk = m.clock("clk")
    rst = m.reset("rst")

    boot_pc = m.input("boot_pc", width=64)
    boot_sp = m.input("boot_sp", width=64)
    boot_ra = m.input("boot_ra", width=64)

    # Frontend handoff (F4 bundle + ready/redirect handshake).
    f4_valid_i = m.input("f4_valid_i", width=1)
    f4_pc_i = m.input("f4_pc_i", width=64)
    f4_window_i = m.input("f4_window_i", width=64)
    f4_checkpoint_i = m.input("f4_checkpoint_i", width=6)
    f4_pkt_uid_i = m.input("f4_pkt_uid_i", width=64)

    # Data-memory read data from LinxCoreMem2R1W.
    dmem_rdata_i = m.input("dmem_rdata_i", width=64)
    # BISQ enqueue ready (CMD issue lane can retire only on successful enqueue).
    bisq_enq_ready_i = m.input("bisq_enq_ready_i", width=1)
    # BROB active-block state query (for BSTOP retirement gate).
    brob_active_allocated_i = m.input("brob_active_allocated_i", width=1)
    brob_active_ready_i = m.input("brob_active_ready_i", width=1)
    brob_active_exception_i = m.input("brob_active_exception_i", width=1)
    brob_active_retired_i = m.input("brob_active_retired_i", width=1)
    # Global dynamic-UID allocator input for template-child uops.
    template_uid_i = m.input("template_uid_i", width=64)

    c = m.const
    consts = make_consts(m)

    # Template frame addend compatibility knob.
    #
    # Architectural default follows immediate-only frame semantics:
    #   f.entry:   sp -= stacksize
    #   f.exit/*:  sp += stacksize
    #
    # Set LINXCORE_CALLFRAME_SIZE to a non-zero multiple of 8 only for
    # compatibility with older binaries that require an additional fixed
    # outgoing-call frame.
    callframe_env = os.getenv("LINXCORE_CALLFRAME_SIZE", "0")
    try:
        callframe_size_cfg = int(callframe_env, 0)
    except ValueError:
        callframe_size_cfg = 0
    if callframe_size_cfg < 0 or (callframe_size_cfg & 0x7) != 0:
        callframe_size_cfg = 0
    callframe_size_cfg &= (1 << 64) - 1

    def op_is(op, *codes: int):
        v = consts.zero1
        for code in codes:
            v = v | op.eq(c(code, width=12))
        return v

    tag0 = c(0, width=p.ptag_w)

    # --- core state (architectural) ---
    state = make_core_ctrl_regs(m, clk, rst, boot_pc=boot_pc, consts=consts, p=p)

    base_can_run = (~state.halted.out()) & (~state.flush_pending.out())
    do_flush = state.flush_pending.out()

    # --- physical register file (PRF) ---
    prf = make_prf(m, clk, rst, boot_sp=boot_sp, boot_ra=boot_ra, consts=consts, p=p)

    # --- rename state ---
    ren = make_rename_regs(m, clk, rst, consts=consts, p=p)
    ckpt_entries = len(ren.ckpt_valid)
    ckpt_w = (ckpt_entries - 1).bit_length()

    # --- ROB (in-order commit) ---
    rob = make_rob_regs(m, clk, rst, consts=consts, p=p)

    # --- issue queues (bring-up split) ---
    iq_alu = make_iq_regs(m, clk, rst, consts=consts, p=p, name="iq_alu")
    iq_bru = make_iq_regs(m, clk, rst, consts=consts, p=p, name="iq_bru")
    iq_lsu = make_iq_regs(m, clk, rst, consts=consts, p=p, name="iq_lsu")
    iq_cmd = make_iq_regs(m, clk, rst, consts=consts, p=p, name="iq_cmd")

    # --- committed store buffer (drains stores to D-memory) ---
    with m.scope("stbuf"):
        stbuf_head = m.out("head", clk=clk, rst=rst, width=p.sq_w, init=c(0, width=p.sq_w), en=consts.one1)
        stbuf_tail = m.out("tail", clk=clk, rst=rst, width=p.sq_w, init=c(0, width=p.sq_w), en=consts.one1)
        stbuf_count = m.out("count", clk=clk, rst=rst, width=p.sq_w + 1, init=c(0, width=p.sq_w + 1), en=consts.one1)
        stbuf_valid = []
        stbuf_addr = []
        stbuf_data = []
        stbuf_size = []
        for i in range(p.sq_entries):
            stbuf_valid.append(m.out(f"v{i}", clk=clk, rst=rst, width=1, init=consts.zero1, en=consts.one1))
            stbuf_addr.append(m.out(f"a{i}", clk=clk, rst=rst, width=64, init=consts.zero64, en=consts.one1))
            stbuf_data.append(m.out(f"d{i}", clk=clk, rst=rst, width=64, init=consts.zero64, en=consts.one1))
            stbuf_size.append(m.out(f"s{i}", clk=clk, rst=rst, width=4, init=consts.zero4, en=consts.one1))

    # --- frontend BSTART metadata buffer (PC buffer equivalent) ---
    pcb_depth = p.rob_depth
    pcb_w = p.rob_w
    with m.scope("pcbuf"):
        pcb_tail = m.out("tail", clk=clk, rst=rst, width=pcb_w, init=c(0, width=pcb_w), en=consts.one1)
        pcb_valid = []
        pcb_pc = []
        pcb_kind = []
        pcb_target = []
        pcb_pred_take = []
        pcb_is_bstart = []
        for i in range(pcb_depth):
            pcb_valid.append(m.out(f"v{i}", clk=clk, rst=rst, width=1, init=consts.zero1, en=consts.one1))
            pcb_pc.append(m.out(f"pc{i}", clk=clk, rst=rst, width=64, init=consts.zero64, en=consts.one1))
            pcb_kind.append(m.out(f"k{i}", clk=clk, rst=rst, width=3, init=c(BK_FALL, width=3), en=consts.one1))
            pcb_target.append(m.out(f"t{i}", clk=clk, rst=rst, width=64, init=consts.zero64, en=consts.one1))
            pcb_pred_take.append(m.out(f"p{i}", clk=clk, rst=rst, width=1, init=consts.zero1, en=consts.one1))
            pcb_is_bstart.append(m.out(f"b{i}", clk=clk, rst=rst, width=1, init=consts.zero1, en=consts.one1))

    # --- commit selection (up to commit_w, stop on redirect/store/halt) ---
    commit_idxs = []
    rob_pcs = []
    rob_valids = []
    rob_dones = []
    rob_ops = []
    rob_lens = []
    rob_dst_kinds = []
    rob_dst_aregs = []
    rob_pdsts = []
    rob_values = []
    rob_src0_regs = []
    rob_src1_regs = []
    rob_src0_values = []
    rob_src1_values = []
    rob_src0_valids = []
    rob_src1_valids = []
    rob_is_stores = []
    rob_st_addrs = []
    rob_st_datas = []
    rob_st_sizes = []
    rob_is_loads = []
    rob_ld_addrs = []
    rob_ld_datas = []
    rob_ld_sizes = []
    rob_is_boundaries = []
    rob_is_bstarts = []
    rob_is_bstops = []
    rob_boundary_kinds = []
    rob_boundary_targets = []
    rob_pred_takes = []
    rob_block_epochs = []
    rob_block_bids = []
    rob_load_store_ids = []
    rob_resolved_d2s = []
    rob_insn_raws = []
    rob_checkpoint_ids = []
    rob_macro_begins = []
    rob_macro_ends = []
    rob_uop_uids = []
    rob_parent_uids = []
    for slot in range(p.commit_w):
        idx = rob.head.out() + c(slot, width=p.rob_w)
        commit_idxs.append(idx)
        rob_pcs.append(mux_by_uindex(m, idx=idx, items=rob.pc, default=consts.zero64))
        rob_valids.append(mux_by_uindex(m, idx=idx, items=rob.valid, default=consts.zero1))
        rob_dones.append(mux_by_uindex(m, idx=idx, items=rob.done, default=consts.zero1))
        rob_ops.append(mux_by_uindex(m, idx=idx, items=rob.op, default=c(0, width=12)))
        rob_lens.append(mux_by_uindex(m, idx=idx, items=rob.len_bytes, default=consts.zero3))
        rob_dst_kinds.append(mux_by_uindex(m, idx=idx, items=rob.dst_kind, default=c(0, width=2)))
        rob_dst_aregs.append(mux_by_uindex(m, idx=idx, items=rob.dst_areg, default=c(REG_INVALID, width=6)))
        rob_pdsts.append(mux_by_uindex(m, idx=idx, items=rob.pdst, default=tag0))
        rob_values.append(mux_by_uindex(m, idx=idx, items=rob.value, default=consts.zero64))
        rob_src0_regs.append(mux_by_uindex(m, idx=idx, items=rob.src0_reg, default=c(0, width=6)))
        rob_src1_regs.append(mux_by_uindex(m, idx=idx, items=rob.src1_reg, default=c(0, width=6)))
        rob_src0_values.append(mux_by_uindex(m, idx=idx, items=rob.src0_value, default=consts.zero64))
        rob_src1_values.append(mux_by_uindex(m, idx=idx, items=rob.src1_value, default=consts.zero64))
        rob_src0_valids.append(mux_by_uindex(m, idx=idx, items=rob.src0_valid, default=consts.zero1))
        rob_src1_valids.append(mux_by_uindex(m, idx=idx, items=rob.src1_valid, default=consts.zero1))
        rob_is_stores.append(mux_by_uindex(m, idx=idx, items=rob.is_store, default=consts.zero1))
        rob_st_addrs.append(mux_by_uindex(m, idx=idx, items=rob.store_addr, default=consts.zero64))
        rob_st_datas.append(mux_by_uindex(m, idx=idx, items=rob.store_data, default=consts.zero64))
        rob_st_sizes.append(mux_by_uindex(m, idx=idx, items=rob.store_size, default=consts.zero4))
        rob_is_loads.append(mux_by_uindex(m, idx=idx, items=rob.is_load, default=consts.zero1))
        rob_ld_addrs.append(mux_by_uindex(m, idx=idx, items=rob.load_addr, default=consts.zero64))
        rob_ld_datas.append(mux_by_uindex(m, idx=idx, items=rob.load_data, default=consts.zero64))
        rob_ld_sizes.append(mux_by_uindex(m, idx=idx, items=rob.load_size, default=consts.zero4))
        rob_is_boundaries.append(mux_by_uindex(m, idx=idx, items=rob.is_boundary, default=consts.zero1))
        rob_is_bstarts.append(mux_by_uindex(m, idx=idx, items=rob.is_bstart, default=consts.zero1))
        rob_is_bstops.append(mux_by_uindex(m, idx=idx, items=rob.is_bstop, default=consts.zero1))
        rob_boundary_kinds.append(mux_by_uindex(m, idx=idx, items=rob.boundary_kind, default=c(BK_FALL, width=3)))
        rob_boundary_targets.append(mux_by_uindex(m, idx=idx, items=rob.boundary_target, default=consts.zero64))
        rob_pred_takes.append(mux_by_uindex(m, idx=idx, items=rob.pred_take, default=consts.zero1))
        rob_block_epochs.append(mux_by_uindex(m, idx=idx, items=rob.block_epoch, default=c(0, width=16)))
        rob_block_bids.append(mux_by_uindex(m, idx=idx, items=rob.block_bid, default=consts.zero64))
        rob_load_store_ids.append(mux_by_uindex(m, idx=idx, items=rob.load_store_id, default=c(0, width=32)))
        rob_resolved_d2s.append(mux_by_uindex(m, idx=idx, items=rob.resolved_d2, default=consts.zero1))
        rob_insn_raws.append(mux_by_uindex(m, idx=idx, items=rob.insn_raw, default=consts.zero64))
        rob_checkpoint_ids.append(mux_by_uindex(m, idx=idx, items=rob.checkpoint_id, default=c(0, width=6)))
        rob_macro_begins.append(mux_by_uindex(m, idx=idx, items=rob.macro_begin, default=c(0, width=6)))
        rob_macro_ends.append(mux_by_uindex(m, idx=idx, items=rob.macro_end, default=c(0, width=6)))
        rob_uop_uids.append(mux_by_uindex(m, idx=idx, items=rob.uop_uid, default=consts.zero64))
        rob_parent_uids.append(mux_by_uindex(m, idx=idx, items=rob.parent_uid, default=consts.zero64))

    head_op = rob_ops[0]
    head_len = rob_lens[0]
    head_dst_kind = rob_dst_kinds[0]
    head_dst_areg = rob_dst_aregs[0]
    head_pdst = rob_pdsts[0]
    head_value = rob_values[0]
    head_is_store = rob_is_stores[0]
    head_st_addr = rob_st_addrs[0]
    head_st_data = rob_st_datas[0]
    head_st_size = rob_st_sizes[0]
    head_is_load = rob_is_loads[0]
    head_ld_addr = rob_ld_addrs[0]
    head_ld_data = rob_ld_datas[0]
    head_ld_size = rob_ld_sizes[0]
    head_insn_raw = rob_insn_raws[0]
    head_checkpoint_id = rob_checkpoint_ids[0]
    head_macro_begin = rob_macro_begins[0]
    head_macro_end = rob_macro_ends[0]
    head_uop_uid = rob_uop_uids[0]

    # Commit-time branch/control decisions (BlockISA markers) for the head.
    commit_head = m.instance(
        build_commit_head_stage,
        name="commit_head_stage",
        head_op=head_op,
        br_kind=state.br_kind.out(),
        commit_cond=state.commit_cond.out(),
    )
    head_is_macro = commit_head["head_is_macro"]
    head_is_start_marker = commit_head["head_is_start_marker"]
    head_is_boundary = commit_head["head_is_boundary"]
    head_br_take = commit_head["head_br_take"]
    head_skip = commit_head["head_skip"]

    # Template blocks (FENTRY/FEXIT/FRET.*) expand into template-uops through
    # CodeTemplateUnit, which blocks IFU while active/starting.
    # UID class encoding:
    # [2:0] 0..3 = decoded slot, 4 = template child, 5 = replay clone.
    template_uid_base = (template_uid_i.shl(amount=3)) | c(4, width=64)
    ctu = m.instance(
        build_code_template_unit,
        name="code_template_unit",
        base_can_run=base_can_run,
        head_is_macro=head_is_macro,
        head_skip=head_skip,
        head_valid=rob_valids[0],
        head_done=rob_dones[0],
        macro_active_i=state.macro_active.out(),
        macro_wait_commit_i=state.macro_wait_commit.out(),
        macro_phase_i=state.macro_phase.out(),
        macro_op_i=state.macro_op.out(),
        macro_end_i=state.macro_end.out(),
        macro_stacksize_i=state.macro_stacksize.out(),
        macro_reg_i=state.macro_reg.out(),
        macro_i_i=state.macro_i.out(),
        macro_sp_base_i=state.macro_sp_base.out(),
        macro_uop_uid_i=template_uid_base,
        macro_uop_parent_uid_i=head_uop_uid,
    )
    macro_start = ctu["start_fire"]
    macro_block = ctu["block_ifu"]

    can_run = base_can_run & (~macro_block)

    # Return target for FRET.* (via RA, possibly restored by the macro engine).
    ret_ra_tag = ren.cmap[10].out()
    ret_ra_val = mux_by_uindex(m, idx=ret_ra_tag, items=prf, default=consts.zero64)

    commit_allow = consts.one1
    commit_fires = []
    commit_pcs = []
    commit_next_pcs = []
    commit_is_bstarts = []
    commit_is_bstops = []
    commit_block_uids = []
    commit_block_bids = []
    commit_core_ids = []

    commit_count = c(0, width=3)

    redirect_valid = consts.zero1
    redirect_pc = state.pc.out()
    redirect_checkpoint_id = c(0, width=6)
    redirect_from_corr = consts.zero1
    replay_redirect_fire = consts.zero1

    commit_store_fire = consts.zero1
    commit_store_addr = consts.zero64
    commit_store_data = consts.zero64
    commit_store_size = consts.zero4
    commit_store_seen = consts.zero1
    brob_retire_fire = consts.zero1
    brob_retire_bid = consts.zero64

    pc_live = state.pc.out()
    commit_cond_live = state.commit_cond.out()
    commit_tgt_live = state.commit_tgt.out()
    br_kind_live = state.br_kind.out()
    br_epoch_live = state.br_epoch.out()
    br_base_live = state.br_base_pc.out()
    br_off_live = state.br_off.out()
    br_pred_take_live = state.br_pred_take.out()
    active_block_uid_live = state.active_block_uid.out()
    block_uid_ctr_live = state.block_uid_ctr.out()
    active_block_bid_live = state.active_block_bid.out()
    block_bid_ctr_live = state.block_bid_ctr.out()
    lsid_issue_ptr_live = state.lsid_issue_ptr.out()
    lsid_complete_ptr_live = state.lsid_complete_ptr.out()
    block_head_live = state.block_head.out()
    br_corr_pending_live = state.br_corr_pending.out()
    br_corr_epoch_live = state.br_corr_epoch.out()
    br_corr_take_live = state.br_corr_take.out()
    br_corr_target_live = state.br_corr_target.out()
    br_corr_checkpoint_id_live = state.br_corr_checkpoint_id.out()

    for slot in range(p.commit_w):
        # Use the ROB-captured instruction PC for commit/control-flow math.
        # Deriving from `pc_live` can drift when hidden/internal retire events
        # are present (e.g. macro template expansion), which can misalign fetch.
        pc_this = rob_valids[slot].select(rob_pcs[slot], pc_live)
        commit_pcs.append(pc_this)
        op = rob_ops[slot]
        ln = rob_lens[slot]
        val = rob_values[slot]

        is_macro = is_macro_op(op, op_is)
        is_bstart = rob_is_bstarts[slot]
        is_bstop = rob_is_bstops[slot]
        is_bstart_head = is_bstart & block_head_live
        is_bstart_mid = is_bstart & (~block_head_live)
        is_start_marker = is_bstart_head | is_macro
        is_boundary = is_bstart_mid | is_bstop | is_macro

        br_is_fall = br_kind_live.eq(c(BK_FALL, width=3))
        br_is_cond = br_kind_live.eq(c(BK_COND, width=3))
        br_is_call = br_kind_live.eq(c(BK_CALL, width=3))
        br_is_ret = br_kind_live.eq(c(BK_RET, width=3))
        br_is_direct = br_kind_live.eq(c(BK_DIRECT, width=3))
        br_is_ind = br_kind_live.eq(c(BK_IND, width=3))
        br_is_icall = br_kind_live.eq(c(BK_ICALL, width=3))

        br_target = br_base_live + br_off_live
        # Dynamic target for RET/IND/ICALL blocks comes from commit_tgt.
        br_target = (br_is_ret | br_is_ind | br_is_icall).select(commit_tgt_live, br_target)
        # Allow SETC.TGT to override fixed targets for DIRECT/CALL/COND blocks.
        br_target = (~(br_is_ret | br_is_ind | br_is_icall) & (~commit_tgt_live.eq(consts.zero64))).select(commit_tgt_live, br_target)

        br_take = (
            br_is_call
            | br_is_direct
            | br_is_ind
            | br_is_icall
            | (br_is_cond & commit_cond_live)
            | (br_is_ret & commit_cond_live)
        )

        fire = can_run & commit_allow & rob_valids[slot] & rob_dones[slot]

        # Template macro blocks (FENTRY/FEXIT/FRET.*) must reach the head of the
        # ROB so the macro microcode engine can run before the macro commits.
        # With commit_w>1, a macro could otherwise commit in the same cycle as
        # an older non-macro (slot>0) and skip the required save/restore.
        if slot != 0:
            fire = fire & (~is_macro)

        # Boundary-authoritative correction:
        # BRU mismatch only records correction; redirect is consumed at boundary commit.
        corr_epoch_match = br_corr_pending_live & br_corr_epoch_live.eq(br_epoch_live)
        corr_for_boundary = fire & is_boundary & corr_epoch_match
        br_take_eff = corr_for_boundary.select(br_corr_take_live, br_take)
        br_target_eff = corr_for_boundary.select(br_corr_target_live, br_target)

        pc_inc = pc_this + ln.zext(width=64)
        boundary_fallthrough = is_bstart_mid.select(pc_this, pc_inc)
        pc_next = is_boundary.select(br_take_eff.select(br_target_eff, boundary_fallthrough), pc_inc)

        # Stop commit on redirect, store, or halt.
        is_halt = op_is(op, OP_EBREAK, OP_INVALID)
        # Mid-block BSTART must restart fetch at its own PC even when the
        # previous block resolves not-taken; this mirrors QEMU block-end split.
        redirect = fire & ((is_boundary & br_take_eff) | is_bstart_mid)
        replay_redirect = (
            fire
            & rob_is_stores[slot]
            & state.replay_pending.out()
            & commit_idxs[slot].eq(state.replay_store_rob.out())
        )
        replay_redirect_fire = replay_redirect.select(consts.one1, replay_redirect_fire)

        # FRET.* are explicit control-flow ops (return via RA). They behave like
        # a taken boundary when the marker is entered (i.e., not skipped by a
        # prior taken branch at this boundary).
        is_fret = op_is(op, OP_FRET_RA, OP_FRET_STK)
        fret_redirect = fire & is_fret & (~redirect)
        pc_next = fret_redirect.select(ret_ra_val, pc_next)
        redirect = redirect | fret_redirect
        pc_next = replay_redirect.select(state.replay_pc.out(), pc_next)
        redirect = redirect | replay_redirect
        commit_next_pcs.append(pc_next)

        stbuf_has_space = stbuf_count.out().ult(c(p.sq_entries, width=p.sq_w + 1))
        # This milestone keeps a 1-store-per-cycle commit enqueue path while
        # allowing other younger non-store commits in the same cycle.
        fire = fire & ((~rob_is_stores[slot]) | ((~commit_store_seen) & stbuf_has_space))
        # Block-authoritative retirement gate:
        # BSTOP can retire only when the active block has no BROB work pending,
        # or BROB marked the block ready/exception.
        bstop_gate_ok = (~is_bstop) | (~brob_active_allocated_i) | brob_active_ready_i | brob_active_exception_i
        fire = fire & bstop_gate_ok
        store_commit = fire & rob_is_stores[slot]
        stop = redirect | (fire & is_halt)

        commit_fires.append(fire)
        commit_count = commit_count + fire.zext(width=3)

        redirect_valid = redirect.select(consts.one1, redirect_valid)
        redirect_pc = redirect.select(pc_next, redirect_pc)
        redirect_ckpt_sel = corr_for_boundary.select(br_corr_checkpoint_id_live, rob_checkpoint_ids[slot])
        redirect_checkpoint_id = redirect.select(redirect_ckpt_sel, redirect_checkpoint_id)
        redirect_from_corr = (redirect & corr_for_boundary).select(consts.one1, redirect_from_corr)

        commit_store_fire = store_commit.select(consts.one1, commit_store_fire)
        commit_store_addr = store_commit.select(rob_st_addrs[slot], commit_store_addr)
        commit_store_data = store_commit.select(rob_st_datas[slot], commit_store_data)
        commit_store_size = store_commit.select(rob_st_sizes[slot], commit_store_size)
        commit_store_seen = store_commit.select(consts.one1, commit_store_seen)
        bstart_commit = fire & is_bstart
        block_uid_this = bstart_commit.select(block_uid_ctr_live, active_block_uid_live)
        block_bid_this = bstart_commit.select(block_bid_ctr_live, active_block_bid_live)
        commit_is_bstarts.append(bstart_commit)
        bstop_commit = fire & is_bstop
        commit_is_bstops.append(bstop_commit)
        commit_block_uids.append(block_uid_this)
        commit_block_bids.append(block_bid_this)
        commit_core_ids.append(c(0, width=2))
        bstop_take = bstop_commit & (~brob_retire_fire)
        brob_retire_fire = bstop_take.select(consts.one1, brob_retire_fire)
        brob_retire_bid = bstop_take.select(active_block_bid_live, brob_retire_bid)
        active_block_uid_live = bstart_commit.select(block_uid_this, active_block_uid_live)
        block_uid_ctr_live = bstart_commit.select(block_uid_ctr_live + c(1, width=64), block_uid_ctr_live)
        active_block_bid_live = bstart_commit.select(block_bid_this, active_block_bid_live)
        block_bid_ctr_live = bstart_commit.select(block_bid_ctr_live + c(1, width=64), block_bid_ctr_live)
        active_block_bid_live = (fire & is_bstop).select(consts.zero64, active_block_bid_live)

        # --- sequential architectural state updates across commit slots ---
        op_setc_any = is_setc_any(op, op_is)
        op_setc_tgt = is_setc_tgt(op, op_is)
        commit_cond_live = (fire & is_boundary).select(consts.zero1, commit_cond_live)
        commit_tgt_live = (fire & is_boundary).select(consts.zero64, commit_tgt_live)
        commit_cond_live = (fire & op_setc_any).select(val.trunc(width=1), commit_cond_live)
        commit_tgt_live = (fire & op_setc_tgt).select(val, commit_tgt_live)
        commit_cond_live = (fire & op_setc_tgt).select(consts.one1, commit_cond_live)

        br_kind_live = (fire & is_boundary & br_take_eff).select(c(BK_FALL, width=3), br_kind_live)
        br_base_live = (fire & is_boundary & br_take_eff).select(pc_this, br_base_live)
        br_off_live = (fire & is_boundary & br_take_eff).select(consts.zero64, br_off_live)
        br_pred_take_live = (fire & is_boundary & br_take_eff).select(consts.zero1, br_pred_take_live)

        enter_new_block = fire & is_bstart & (~br_take_eff)
        meta_off = rob_boundary_targets[slot] - pc_this
        br_kind_live = enter_new_block.select(rob_boundary_kinds[slot], br_kind_live)
        br_base_live = enter_new_block.select(pc_this, br_base_live)
        br_off_live = enter_new_block.select(meta_off, br_off_live)
        br_pred_take_live = enter_new_block.select(rob_pred_takes[slot], br_pred_take_live)

        # Macro blocks (FENTRY/FEXIT/FRET.*) are standalone fall-through blocks.
        macro_enter = fire & is_macro & (~br_take_eff)
        br_kind_live = macro_enter.select(c(BK_FALL, width=3), br_kind_live)
        br_base_live = macro_enter.select(pc_this, br_base_live)
        br_off_live = macro_enter.select(consts.zero64, br_off_live)
        br_pred_take_live = macro_enter.select(consts.zero1, br_pred_take_live)

        br_kind_live = (fire & is_bstop).select(c(BK_FALL, width=3), br_kind_live)
        br_base_live = (fire & is_bstop).select(pc_this, br_base_live)
        br_off_live = (fire & is_bstop).select(consts.zero64, br_off_live)
        br_pred_take_live = (fire & is_bstop).select(consts.zero1, br_pred_take_live)

        clear_corr_boundary = fire & is_boundary & corr_epoch_match
        br_corr_pending_live = clear_corr_boundary.select(consts.zero1, br_corr_pending_live)

        br_epoch_advance = fire & is_boundary
        br_epoch_live = br_epoch_advance.select(br_epoch_live + c(1, width=16), br_epoch_live)

        # Track whether next committed instruction should be interpreted as the
        # block head marker.
        block_head_live = (fire & (is_boundary | redirect)).select(consts.one1, block_head_live)
        block_head_live = (fire & is_bstart_head).select(consts.zero1, block_head_live)

        pc_live = fire.select(pc_next, pc_live)

        commit_allow = commit_allow & fire & (~stop)

    # Canonical retired-store selection from committed slots (oldest first).
    # This is the single source used for memory side effects and same-cycle
    # forwarding to keep side effects aligned with retire trace semantics.
    store_sel_fire = consts.zero1
    store_sel_addr = consts.zero64
    store_sel_data = consts.zero64
    store_sel_size = consts.zero4
    for slot in range(p.commit_w):
        slot_store = commit_fires[slot] & rob_is_stores[slot]
        take = slot_store & (~store_sel_fire)
        store_sel_fire = slot_store.select(consts.one1, store_sel_fire)
        store_sel_addr = take.select(rob_st_addrs[slot], store_sel_addr)
        store_sel_data = take.select(rob_st_datas[slot], store_sel_data)
        store_sel_size = take.select(rob_st_sizes[slot], store_sel_size)
    commit_store_fire = store_sel_fire
    commit_store_addr = store_sel_addr
    commit_store_data = store_sel_data
    commit_store_size = store_sel_size

    commit_fire = commit_fires[0]
    commit_redirect = redirect_valid

    # --- store tracking (for conservative load ordering) ---
    store_pending = consts.zero1
    for i in range(p.rob_depth):
        store_pending = store_pending | (rob.valid[i].out() & rob.is_store[i].out())

    # --- issue selection (up to issue_w ready IQ entries) ---
    issue_stage_args = {
        "can_run": can_run,
        "commit_redirect": commit_redirect,
        "ready_mask": ren.ready_mask.out(),
        "rob_head": rob.head.out(),
    }
    for i in range(p.iq_depth):
        issue_stage_args[f"iq_alu_valid{i}"] = iq_alu.valid[i].out()
        issue_stage_args[f"iq_alu_rob{i}"] = iq_alu.rob[i].out()
        issue_stage_args[f"iq_alu_srcl{i}"] = iq_alu.srcl[i].out()
        issue_stage_args[f"iq_alu_srcr{i}"] = iq_alu.srcr[i].out()
        issue_stage_args[f"iq_alu_srcp{i}"] = iq_alu.srcp[i].out()
        issue_stage_args[f"iq_bru_valid{i}"] = iq_bru.valid[i].out()
        issue_stage_args[f"iq_bru_rob{i}"] = iq_bru.rob[i].out()
        issue_stage_args[f"iq_bru_srcl{i}"] = iq_bru.srcl[i].out()
        issue_stage_args[f"iq_bru_srcr{i}"] = iq_bru.srcr[i].out()
        issue_stage_args[f"iq_bru_srcp{i}"] = iq_bru.srcp[i].out()
        issue_stage_args[f"iq_lsu_valid{i}"] = iq_lsu.valid[i].out()
        issue_stage_args[f"iq_lsu_rob{i}"] = iq_lsu.rob[i].out()
        issue_stage_args[f"iq_lsu_op{i}"] = iq_lsu.op[i].out()
        issue_stage_args[f"iq_lsu_srcl{i}"] = iq_lsu.srcl[i].out()
        issue_stage_args[f"iq_lsu_srcr{i}"] = iq_lsu.srcr[i].out()
        issue_stage_args[f"iq_lsu_srcp{i}"] = iq_lsu.srcp[i].out()

    issue_stage = m.instance(
        build_issue_stage,
        name="issue_stage",
        params={
            "iq_depth": p.iq_depth,
            "iq_w": p.iq_w,
            "rob_w": p.rob_w,
            "pregs": p.pregs,
            "alu_w": p.alu_w,
            "bru_w": p.bru_w,
            "lsu_w": p.lsu_w,
        },
        **issue_stage_args,
    )

    sub_head = issue_stage["sub_head"]

    alu_issue_valids = [issue_stage[f"alu_issue_valid{i}"] for i in range(p.alu_w)]
    alu_issue_idxs = [issue_stage[f"alu_issue_idx{i}"] for i in range(p.alu_w)]
    bru_issue_valids = [issue_stage[f"bru_issue_valid{i}"] for i in range(p.bru_w)]
    bru_issue_idxs = [issue_stage[f"bru_issue_idx{i}"] for i in range(p.bru_w)]
    lsu_issue_valids = [issue_stage[f"lsu_issue_valid{i}"] for i in range(p.lsu_w)]
    lsu_issue_idxs = [issue_stage[f"lsu_issue_idx{i}"] for i in range(p.lsu_w)]

    # Slot ordering: LSU, BRU, ALU (stable debug lane0 = LSU).
    issue_idxs = lsu_issue_idxs + bru_issue_idxs + alu_issue_idxs
    issue_iqs = ([iq_lsu] * p.lsu_w) + ([iq_bru] * p.bru_w) + ([iq_alu] * p.alu_w)
    issue_fires = [issue_stage[f"issue_fire{slot}"] for slot in range(p.issue_w)]

    # CMD IQ/pipe selection:
    # - command split uops are queued in iq_cmd
    # - command enqueue into BISQ is the completion event
    # - when CMD is present, it overlays the last issue slot
    cmd_can_issue = []
    for i in range(p.iq_depth):
        sl_rdy = mask_bit(m, mask=ren.ready_mask.out(), idx=iq_cmd.srcl[i].out(), width=p.pregs)
        sr_rdy = mask_bit(m, mask=ren.ready_mask.out(), idx=iq_cmd.srcr[i].out(), width=p.pregs)
        sp_rdy = mask_bit(m, mask=ren.ready_mask.out(), idx=iq_cmd.srcp[i].out(), width=p.pregs)
        cmd_can_issue.append(iq_cmd.valid[i].out() & sl_rdy & sr_rdy & sp_rdy)
    cmd_pick_p = SimpleNamespace(iq_depth=p.iq_depth, iq_w=p.iq_w, rob_w=p.rob_w)
    cmd_pick_consts = SimpleNamespace(zero1=consts.zero1, one1=consts.one1)
    cmd_issue_valids, cmd_issue_idxs = pick_oldest_from_arrays(
        m=m,
        p=cmd_pick_p,
        consts=cmd_pick_consts,
        can_issue=cmd_can_issue,
        rob_tags=[iq_cmd.rob[i].out() for i in range(p.iq_depth)],
        width=1,
        sub_head=sub_head,
    )
    cmd_issue_valid = cmd_issue_valids[0]
    cmd_issue_idx = cmd_issue_idxs[0]
    cmd_slot = p.issue_w - 1
    cmd_issue_fire_raw = can_run & (~commit_redirect) & cmd_issue_valid
    cmd_issue_fire_eff = cmd_issue_fire_raw & bisq_enq_ready_i
    # Claim the shared issue slot only when CMD can actually enqueue into BISQ.
    cmd_slot_sel = cmd_issue_fire_eff

    # Lane0 retained for trace/debug outputs.
    issue_fire = issue_fires[0]
    issue_idx = issue_idxs[0]

    uop_robs = []
    uop_ops = []
    uop_pcs = []
    uop_imms = []
    uop_sls = []
    uop_srs = []
    uop_srcr_types = []
    uop_shamts = []
    uop_sps = []
    uop_pdsts = []
    uop_has_dsts = []
    for slot in range(p.issue_w):
        iq = issue_iqs[slot]
        idx = issue_idxs[slot]
        uop_robs.append(mux_by_uindex(m, idx=idx, items=iq.rob, default=c(0, width=p.rob_w)))
        uop_ops.append(mux_by_uindex(m, idx=idx, items=iq.op, default=c(0, width=12)))
        uop_pcs.append(mux_by_uindex(m, idx=idx, items=iq.pc, default=consts.zero64))
        uop_imms.append(mux_by_uindex(m, idx=idx, items=iq.imm, default=consts.zero64))
        uop_sls.append(mux_by_uindex(m, idx=idx, items=iq.srcl, default=tag0))
        uop_srs.append(mux_by_uindex(m, idx=idx, items=iq.srcr, default=tag0))
        uop_srcr_types.append(mux_by_uindex(m, idx=idx, items=iq.srcr_type, default=c(0, width=2)))
        uop_shamts.append(mux_by_uindex(m, idx=idx, items=iq.shamt, default=consts.zero6))
        uop_sps.append(mux_by_uindex(m, idx=idx, items=iq.srcp, default=tag0))
        uop_pdsts.append(mux_by_uindex(m, idx=idx, items=iq.pdst, default=tag0))
        uop_has_dsts.append(mux_by_uindex(m, idx=idx, items=iq.has_dst, default=consts.zero1))

    cmd_uop_rob = mux_by_uindex(m, idx=cmd_issue_idx, items=iq_cmd.rob, default=c(0, width=p.rob_w))
    cmd_uop_op = mux_by_uindex(m, idx=cmd_issue_idx, items=iq_cmd.op, default=c(0, width=12))
    cmd_uop_pc = mux_by_uindex(m, idx=cmd_issue_idx, items=iq_cmd.pc, default=consts.zero64)
    cmd_uop_imm = mux_by_uindex(m, idx=cmd_issue_idx, items=iq_cmd.imm, default=consts.zero64)
    cmd_uop_sl = mux_by_uindex(m, idx=cmd_issue_idx, items=iq_cmd.srcl, default=tag0)
    cmd_uop_sr = mux_by_uindex(m, idx=cmd_issue_idx, items=iq_cmd.srcr, default=tag0)
    cmd_uop_srcr_type = mux_by_uindex(m, idx=cmd_issue_idx, items=iq_cmd.srcr_type, default=c(0, width=2))
    cmd_uop_shamt = mux_by_uindex(m, idx=cmd_issue_idx, items=iq_cmd.shamt, default=consts.zero6)
    cmd_uop_sp = mux_by_uindex(m, idx=cmd_issue_idx, items=iq_cmd.srcp, default=tag0)
    cmd_uop_pdst = mux_by_uindex(m, idx=cmd_issue_idx, items=iq_cmd.pdst, default=tag0)
    cmd_uop_has_dst = mux_by_uindex(m, idx=cmd_issue_idx, items=iq_cmd.has_dst, default=consts.zero1)

    uop_robs[cmd_slot] = cmd_slot_sel.select(cmd_uop_rob, uop_robs[cmd_slot])
    uop_ops[cmd_slot] = cmd_slot_sel.select(cmd_uop_op, uop_ops[cmd_slot])
    uop_pcs[cmd_slot] = cmd_slot_sel.select(cmd_uop_pc, uop_pcs[cmd_slot])
    uop_imms[cmd_slot] = cmd_slot_sel.select(cmd_uop_imm, uop_imms[cmd_slot])
    uop_sls[cmd_slot] = cmd_slot_sel.select(cmd_uop_sl, uop_sls[cmd_slot])
    uop_srs[cmd_slot] = cmd_slot_sel.select(cmd_uop_sr, uop_srs[cmd_slot])
    uop_srcr_types[cmd_slot] = cmd_slot_sel.select(cmd_uop_srcr_type, uop_srcr_types[cmd_slot])
    uop_shamts[cmd_slot] = cmd_slot_sel.select(cmd_uop_shamt, uop_shamts[cmd_slot])
    uop_sps[cmd_slot] = cmd_slot_sel.select(cmd_uop_sp, uop_sps[cmd_slot])
    uop_pdsts[cmd_slot] = cmd_slot_sel.select(cmd_uop_pdst, uop_pdsts[cmd_slot])
    uop_has_dsts[cmd_slot] = cmd_slot_sel.select(cmd_uop_has_dst, uop_has_dsts[cmd_slot])
    uop_uids = []
    uop_parent_uids = []
    for slot in range(p.issue_w):
        uop_uids.append(mux_by_uindex(m, idx=uop_robs[slot], items=rob.uop_uid, default=consts.zero64))
        uop_parent_uids.append(mux_by_uindex(m, idx=uop_robs[slot], items=rob.parent_uid, default=consts.zero64))

    # Lane0 named views (stable trace hooks).
    uop_rob = uop_robs[0]
    uop_op = uop_ops[0]
    uop_pc = uop_pcs[0]
    uop_imm = uop_imms[0]
    uop_sl = uop_sls[0]
    uop_sr = uop_srs[0]
    uop_sp = uop_sps[0]
    uop_pdst = uop_pdsts[0]
    uop_has_dst = uop_has_dsts[0]

    # PRF reads + execute for each issued uop.
    sl_vals = []
    sr_vals = []
    sp_vals = []
    exs = []
    for slot in range(p.issue_w):
        sl_vals.append(mux_by_uindex(m, idx=uop_sls[slot], items=prf, default=consts.zero64))
        sr_vals.append(mux_by_uindex(m, idx=uop_srs[slot], items=prf, default=consts.zero64))
        sp_vals.append(mux_by_uindex(m, idx=uop_sps[slot], items=prf, default=consts.zero64))
        exs.append(
            exec_uop_comb(
                m,
                op=uop_ops[slot],
                pc=uop_pcs[slot],
                imm=uop_imms[slot],
                srcl_val=sl_vals[slot],
                srcr_val=sr_vals[slot],
                srcr_type=uop_srcr_types[slot],
                shamt=uop_shamts[slot],
                srcp_val=sp_vals[slot],
                consts=consts,
            )
        )

    # Lane0 values for debug/trace.
    sl_val = sl_vals[0]
    sr_val = sr_vals[0]
    sp_val = sp_vals[0]

    issue_fires_eff = [issue_fires[i] for i in range(p.issue_w)]
    issue_fires_eff[cmd_slot] = cmd_slot_sel.select(cmd_issue_fire_eff, issue_fires_eff[cmd_slot])
    cmd_payload_lane = cmd_uop_imm
    cmd_payload_lane = cmd_uop_op.eq(c(OP_BIOR, width=12)).select(sl_vals[cmd_slot] | sr_vals[cmd_slot], cmd_payload_lane)
    cmd_payload_lane = cmd_uop_op.eq(c(OP_BLOAD, width=12)).select(sl_vals[cmd_slot] + cmd_uop_imm, cmd_payload_lane)
    cmd_payload_lane = cmd_uop_op.eq(c(OP_BSTORE, width=12)).select(sr_vals[cmd_slot], cmd_payload_lane)

    # Memory disambiguation/forwarding for the LSU lane (lane0).
    lsu_stage_args = {
        "issue_fire_lane0_raw": issue_fires[0],
        "ex0_is_load": exs[0].is_load,
        "ex0_is_store": exs[0].is_store,
        "ex0_addr": exs[0].addr,
        "ex0_rob": uop_robs[0],
        "ex0_lsid": mux_by_uindex(m, idx=uop_robs[0], items=rob.load_store_id, default=c(0, width=32)),
        "lsid_issue_ptr": state.lsid_issue_ptr.out(),
        "sub_head": sub_head,
        "commit_store_fire": commit_store_fire,
        "commit_store_addr": commit_store_addr,
        "commit_store_data": commit_store_data,
    }
    for i in range(p.rob_depth):
        lsu_stage_args[f"rob_valid{i}"] = rob.valid[i].out()
        lsu_stage_args[f"rob_done{i}"] = rob.done[i].out()
        lsu_stage_args[f"rob_is_store{i}"] = rob.is_store[i].out()
        lsu_stage_args[f"rob_store_addr{i}"] = rob.store_addr[i].out()
        lsu_stage_args[f"rob_store_data{i}"] = rob.store_data[i].out()
    for i in range(p.sq_entries):
        lsu_stage_args[f"stbuf_valid{i}"] = stbuf_valid[i].out()
        lsu_stage_args[f"stbuf_addr{i}"] = stbuf_addr[i].out()
        lsu_stage_args[f"stbuf_data{i}"] = stbuf_data[i].out()

    lsu_stage = m.instance(
        build_lsu_stage,
        name="lsu_stage",
        params={"rob_depth": p.rob_depth, "rob_w": p.rob_w, "sq_entries": p.sq_entries, "sq_w": p.sq_w},
        **lsu_stage_args,
    )
    lsu_load_fire_raw = lsu_stage["lsu_load_fire_raw"]
    lsu_older_store_pending_lane0 = lsu_stage["lsu_older_store_pending_lane0"]
    lsu_forward_hit_lane0 = lsu_stage["lsu_forward_hit_lane0"]
    lsu_forward_data_lane0 = lsu_stage["lsu_forward_data_lane0"]
    lsu_lsid_block_lane0 = lsu_stage["lsu_lsid_block_lane0"]
    lsu_block_lane0 = lsu_stage["lsu_block_lane0"]
    lsu_lsid_issue_advance = lsu_stage["lsu_lsid_issue_advance"]
    issue_fires_eff[0] = lsu_stage["issue_fire_lane0_eff"]
    issue_fire = issue_fires_eff[0]
    lsid_issue_ptr_live = lsu_lsid_issue_advance.select(lsid_issue_ptr_live + c(1, width=32), lsid_issue_ptr_live)
    lsid_complete_ptr_live = lsu_lsid_issue_advance.select(lsid_complete_ptr_live + c(1, width=32), lsid_complete_ptr_live)
    # Redirect/flush drops in-flight younger memory ops: rebase LSID issue/complete
    # pointers to the current allocation head so stale IDs cannot deadlock LSU.
    lsid_issue_ptr_live = do_flush.select(state.lsid_alloc_ctr.out(), lsid_issue_ptr_live)
    lsid_complete_ptr_live = do_flush.select(state.lsid_alloc_ctr.out(), lsid_complete_ptr_live)

    load_fires = []
    load_mem_fires = []
    store_fires = []
    any_load_mem_fire = consts.zero1
    load_addr = consts.zero64
    for slot in range(p.issue_w):
        ld = issue_fires_eff[slot] & exs[slot].is_load
        st = issue_fires_eff[slot] & exs[slot].is_store
        ld_mem = ld
        if slot == 0:
            ld_mem = ld & (~lsu_forward_hit_lane0)
        load_fires.append(ld)
        load_mem_fires.append(ld_mem)
        store_fires.append(st)
        any_load_mem_fire = any_load_mem_fire | ld_mem
        load_addr = ld_mem.select(exs[slot].addr, load_addr)

    issued_is_load = load_fires[0]
    issued_is_store = store_fires[0]
    older_store_pending = lsu_older_store_pending_lane0

    # LSU violation replay state (updated after wb metadata is formed).
    replay_set = consts.zero1
    replay_set_store_rob = state.replay_store_rob.out()
    replay_set_pc = state.replay_pc.out()
    lsu_violation_detected = consts.zero1

    # --- template macro engine (FENTRY/FEXIT/FRET.*) ---
    macro_active = state.macro_active.out()
    macro_phase = state.macro_phase.out()
    macro_op = state.macro_op.out()
    macro_begin = state.macro_begin.out()
    macro_end = state.macro_end.out()
    macro_stacksize = state.macro_stacksize.out()
    # Optional fixed callframe addend for legacy compatibility.
    macro_callframe_size = c(callframe_size_cfg, width=64)
    macro_frame_adj = macro_stacksize + macro_callframe_size
    macro_reg = state.macro_reg.out()
    macro_i = state.macro_i.out()
    macro_sp_base = state.macro_sp_base.out()

    macro_is_fentry = ctu["macro_is_fentry"]
    macro_phase_init = ctu["phase_init"]
    macro_phase_mem = ctu["phase_mem"]
    macro_phase_sp = ctu["phase_sp"]
    macro_phase_setc = ctu["phase_setc"]
    macro_off_ok = ctu["off_ok"]
    macro_is_fexit = macro_op.eq(c(OP_FEXIT, width=12))
    macro_is_fret_ra = macro_op.eq(c(OP_FRET_RA, width=12))
    macro_is_fret_stk = macro_op.eq(c(OP_FRET_STK, width=12))

    # CodeTemplateUnit emits one template-uop per cycle while active.
    macro_uop_valid = ctu["uop_valid"]
    macro_uop_kind = ctu["uop_kind"]
    macro_uop_reg = ctu["uop_reg"]
    macro_uop_addr = ctu["uop_addr"]
    macro_uop_uid = ctu["uop_uid"]
    macro_uop_parent_uid = ctu["uop_parent_uid"]
    macro_uop_template_kind = ctu["uop_template_kind"]
    macro_uop_is_sp_sub = ctu["uop_is_sp_sub"]
    macro_uop_is_store = ctu["uop_is_store"]
    macro_uop_is_load = ctu["uop_is_load"]
    macro_uop_is_sp_add = ctu["uop_is_sp_add"]
    macro_uop_is_setc_tgt = ctu["uop_is_setc_tgt"]

    # D-memory read arbitration: macro restore-load > LSU load.
    macro_mem_read = macro_uop_is_load
    dmem_raddr = macro_mem_read.select(macro_uop_addr, any_load_mem_fire.select(load_addr, consts.zero64))

    # Macro/template uop operand reads.
    cmap_now = [ren.cmap[i].out() for i in range(p.aregs)]
    macro_reg_tag = mux_by_uindex(m, idx=macro_uop_reg, items=cmap_now, default=tag0)
    macro_reg_val = mux_by_uindex(m, idx=macro_reg_tag, items=prf, default=consts.zero64)
    macro_sp_tag = ren.cmap[1].out()
    macro_sp_val = mux_by_uindex(m, idx=macro_sp_tag, items=prf, default=consts.zero64)
    macro_reg_is_gpr = macro_uop_reg.ult(c(24, width=6))
    macro_reg_not_zero = ~macro_uop_reg.eq(c(0, width=6))
    macro_store_fire = macro_uop_is_store & macro_reg_is_gpr & macro_reg_not_zero
    macro_store_addr = macro_uop_addr
    macro_store_data = macro_reg_val
    macro_store_size = c(8, width=4)

    # MMIO (QEMU virt compatibility).
    #
    # - UART data: 0x1000_0000 (write low byte)
    # - EXIT:      0x1000_0004 (write exit code; stop simulation)
    mmio_uart = commit_store_fire & commit_store_addr.eq(c(0x1000_0000, width=64))
    mmio_exit = commit_store_fire & commit_store_addr.eq(c(0x1000_0004, width=64))
    mmio_any = mmio_uart | mmio_exit

    mmio_uart_data = mmio_uart.select(commit_store_data.trunc(width=8), c(0, width=8))
    mmio_exit_code = mmio_exit.select(commit_store_data.trunc(width=32), c(0, width=32))

    # Preserve store ordering:
    # - If the committed-store buffer already has older entries, enqueue all new
    #   committed stores (unless MMIO) so younger writes cannot bypass older ones.
    # - If macro uses the single write port this cycle, enqueue as well.
    stbuf_empty = stbuf_count.out().eq(c(0, width=p.sq_w + 1))
    commit_store_defer = commit_store_fire & (~mmio_any) & (macro_store_fire | (~stbuf_empty))
    stbuf_enq_fire = commit_store_defer
    stbuf_enq_idx = stbuf_tail.out()
    stbuf_enq_tail = stbuf_tail.out() + c(1, width=p.sq_w)

    stbuf_drain_fire = (~macro_store_fire) & (~commit_store_fire) & (~stbuf_count.out().eq(c(0, width=p.sq_w + 1)))
    stbuf_drain_addr = mux_by_uindex(m, idx=stbuf_head.out(), items=stbuf_addr, default=consts.zero64)
    stbuf_drain_data = mux_by_uindex(m, idx=stbuf_head.out(), items=stbuf_data, default=consts.zero64)
    stbuf_drain_size = mux_by_uindex(m, idx=stbuf_head.out(), items=stbuf_size, default=consts.zero4)
    stbuf_drain_head = stbuf_head.out() + c(1, width=p.sq_w)

    commit_store_write_through = commit_store_fire & (~mmio_any) & (~commit_store_defer)
    mem_wvalid = macro_store_fire | commit_store_write_through | stbuf_drain_fire
    mem_waddr = macro_store_fire.select(
        macro_store_addr,
        commit_store_write_through.select(commit_store_addr, stbuf_drain_addr),
    )
    dmem_wdata = macro_store_fire.select(
        macro_store_data,
        commit_store_write_through.select(commit_store_data, stbuf_drain_data),
    )
    mem_wsize = macro_store_fire.select(
        macro_store_size,
        commit_store_write_through.select(commit_store_size, stbuf_drain_size),
    )

    dmem_wsrc = c(0, width=2)
    dmem_wsrc = macro_store_fire.select(c(1, width=2), dmem_wsrc)
    dmem_wsrc = commit_store_write_through.select(c(2, width=2), dmem_wsrc)
    dmem_wsrc = stbuf_drain_fire.select(c(3, width=2), dmem_wsrc)

    # Store write port (writes at clk edge). Stop-at-store ensures that at most
    # one store commits per cycle in this bring-up model; the macro engine
    # consumes the same single write port.
    wstrb = consts.zero8
    wstrb = mem_wsize.eq(c(1, width=4)).select(c(0x01, width=8), wstrb)
    wstrb = mem_wsize.eq(c(2, width=4)).select(c(0x03, width=8), wstrb)
    wstrb = mem_wsize.eq(c(4, width=4)).select(c(0x0F, width=8), wstrb)
    wstrb = mem_wsize.eq(c(8, width=4)).select(c(0xFF, width=8), wstrb)

    # Store buffer register updates.
    for i in range(p.sq_entries):
        idx = c(i, width=p.sq_w)
        do_enq = stbuf_enq_fire & stbuf_enq_idx.eq(idx)
        do_drain = stbuf_drain_fire & stbuf_head.out().eq(idx)
        v_next = stbuf_valid[i].out()
        v_next = do_drain.select(consts.zero1, v_next)
        v_next = do_enq.select(consts.one1, v_next)
        stbuf_valid[i].set(v_next)
        stbuf_addr[i].set(commit_store_addr, when=do_enq)
        stbuf_data[i].set(commit_store_data, when=do_enq)
        stbuf_size[i].set(commit_store_size, when=do_enq)

    stbuf_head_next = stbuf_head.out()
    stbuf_tail_next = stbuf_tail.out()
    stbuf_count_next = stbuf_count.out()
    stbuf_tail_next = stbuf_enq_fire.select(stbuf_enq_tail, stbuf_tail_next)
    stbuf_count_next = stbuf_enq_fire.select(stbuf_count_next + c(1, width=p.sq_w + 1), stbuf_count_next)
    stbuf_head_next = stbuf_drain_fire.select(stbuf_drain_head, stbuf_head_next)
    stbuf_count_next = stbuf_drain_fire.select(stbuf_count_next - c(1, width=p.sq_w + 1), stbuf_count_next)
    stbuf_head.set(stbuf_head_next)
    stbuf_tail.set(stbuf_tail_next)
    stbuf_count.set(stbuf_count_next)

    dmem_rdata = dmem_rdata_i
    macro_load_fwd_hit = consts.zero1
    macro_load_fwd_data = consts.zero64
    for i in range(p.sq_entries):
        st_match = stbuf_valid[i].out() & stbuf_addr[i].out().eq(macro_uop_addr)
        macro_load_fwd_hit = (macro_uop_is_load & st_match).select(consts.one1, macro_load_fwd_hit)
        macro_load_fwd_data = (macro_uop_is_load & st_match).select(stbuf_data[i].out(), macro_load_fwd_data)
    macro_load_data = macro_load_fwd_hit.select(macro_load_fwd_data, dmem_rdata)
    # FRET.STK must consume the loaded stack RA value. Only FRET.RA uses the
    # saved-RA bypass path.
    macro_restore_ra = macro_uop_is_load & op_is(macro_op, OP_FRET_RA) & macro_uop_reg.eq(c(10, width=6))
    macro_load_data_eff = macro_restore_ra.select(state.macro_saved_ra.out(), macro_load_data)
    # FRET.STK can finish immediately after restoring RA (e.g. [ra~ra]).
    # In that case there is no standalone SETC_TGT phase; consume the restored
    # RA value as return target on the RA-load step.
    macro_setc_from_fret_stk_ra_load = macro_uop_is_load & macro_is_fret_stk & macro_uop_reg.eq(c(10, width=6))
    macro_setc_tgt_fire = macro_uop_is_setc_tgt | macro_setc_from_fret_stk_ra_load
    macro_setc_tgt_data = ret_ra_val
    macro_setc_tgt_data = macro_setc_from_fret_stk_ra_load.select(macro_load_data_eff, macro_setc_tgt_data)
    macro_setc_tgt_data = (macro_uop_is_setc_tgt & macro_is_fret_stk).select(state.macro_saved_ra.out(), macro_setc_tgt_data)

    macro_is_restore = macro_active & (~macro_is_fentry)

    # Macro PRF write port (one write per cycle).
    macro_reg_write = macro_uop_is_load & macro_reg_is_gpr & macro_reg_not_zero
    macro_sp_write_init = macro_uop_is_sp_sub
    macro_sp_write_restore = macro_uop_is_sp_add

    macro_prf_we = macro_reg_write | macro_sp_write_init | macro_sp_write_restore
    macro_prf_tag = macro_sp_tag
    macro_prf_data = consts.zero64
    macro_prf_tag = macro_reg_write.select(macro_reg_tag, macro_prf_tag)
    macro_prf_data = macro_reg_write.select(macro_load_data_eff, macro_prf_data)
    macro_prf_data = macro_sp_write_restore.select(macro_sp_val + macro_frame_adj, macro_prf_data)
    macro_prf_data = macro_sp_write_init.select(macro_sp_val - macro_frame_adj, macro_prf_data)

    # Load result (uses dmem_rdata in the same cycle raddr is set).
    load8 = dmem_rdata.trunc(width=8)
    load16 = dmem_rdata.trunc(width=16)
    load32 = dmem_rdata.trunc(width=32)
    load_lb = load8.sext(width=64)
    load_lbu = load8.zext(width=64)
    load_lh = load16.sext(width=64)
    load_lhu = load16.zext(width=64)
    load_lw = load32.sext(width=64)
    load_lwu = load32.zext(width=64)
    load_ld = dmem_rdata
    lsu_forward_active = (issue_fires_eff[0] & exs[0].is_load) & lsu_forward_hit_lane0
    wb_fires = []
    wb_robs = []
    wb_pdsts = []
    wb_values = []
    wb_fire_has_dsts = []
    wb_onehots = []
    for slot in range(p.issue_w):
        wb_fire = issue_fires_eff[slot]
        wb_rob = uop_robs[slot]
        wb_pdst = uop_pdsts[slot]
        op = uop_ops[slot]
        load_val = load_lw
        load_val = op_is(op, OP_LB, OP_LBI, OP_HL_LB_PCR).select(load_lb, load_val)
        load_val = op_is(op, OP_LBU, OP_LBUI, OP_HL_LBU_PCR).select(load_lbu, load_val)
        load_val = op_is(op, OP_LH, OP_LHI, OP_HL_LH_PCR).select(load_lh, load_val)
        load_val = op_is(op, OP_LHU, OP_LHUI, OP_HL_LHU_PCR).select(load_lhu, load_val)
        load_val = op_is(op, OP_LWI, OP_C_LWI, OP_LW, OP_HL_LW_PCR).select(load_lw, load_val)
        load_val = op_is(op, OP_LWU, OP_LWUI, OP_HL_LWU_PCR).select(load_lwu, load_val)
        load_val = op_is(op, OP_LD, OP_LDI, OP_C_LDI, OP_HL_LD_PCR).select(load_ld, load_val)
        if slot == 0:
            fwd8 = lsu_forward_data_lane0.trunc(width=8)
            fwd16 = lsu_forward_data_lane0.trunc(width=16)
            fwd32 = lsu_forward_data_lane0.trunc(width=32)
            load_fwd = fwd32.sext(width=64)
            load_fwd = op_is(op, OP_LB, OP_LBI, OP_HL_LB_PCR).select(fwd8.sext(width=64), load_fwd)
            load_fwd = op_is(op, OP_LBU, OP_LBUI, OP_HL_LBU_PCR).select(fwd8.zext(width=64), load_fwd)
            load_fwd = op_is(op, OP_LH, OP_LHI, OP_HL_LH_PCR).select(fwd16.sext(width=64), load_fwd)
            load_fwd = op_is(op, OP_LHU, OP_LHUI, OP_HL_LHU_PCR).select(fwd16.zext(width=64), load_fwd)
            load_fwd = op_is(op, OP_LWI, OP_C_LWI, OP_LW, OP_HL_LW_PCR).select(fwd32.sext(width=64), load_fwd)
            load_fwd = op_is(op, OP_LWU, OP_LWUI, OP_HL_LWU_PCR).select(fwd32.zext(width=64), load_fwd)
            load_fwd = op_is(op, OP_LD, OP_LDI, OP_C_LDI, OP_HL_LD_PCR).select(lsu_forward_data_lane0, load_fwd)
            load_val = lsu_forward_active.select(load_fwd, load_val)
        wb_value = load_fires[slot].select(load_val, exs[slot].alu)
        if slot == cmd_slot:
            wb_value = cmd_slot_sel.select(cmd_payload_lane, wb_value)
        wb_has_dst = uop_has_dsts[slot] & (~store_fires[slot])
        wb_fire_has_dst = wb_fire & wb_has_dst

        wb_fires.append(wb_fire)
        wb_robs.append(wb_rob)
        wb_pdsts.append(wb_pdst)
        wb_values.append(wb_value)
        wb_fire_has_dsts.append(wb_fire_has_dst)
        wb_onehots.append(onehot_from_tag(m, tag=wb_pdst, width=p.pregs, tag_width=p.ptag_w))

    # BRU validation for SETC.cond: compare actual result vs predicted direction.
    # Mismatch is recorded as deferred boundary correction (not immediate redirect).
    bru_corr_set = consts.zero1
    bru_corr_take = consts.zero1
    bru_corr_target = consts.zero64
    bru_corr_checkpoint_id = c(0, width=6)
    bru_corr_epoch = c(0, width=16)
    bru_fault_set = consts.zero1
    bru_fault_rob = c(0, width=p.rob_w)
    bru_validate_fire = consts.zero1
    bru_mismatch_evt = consts.zero1
    bru_actual_take_dbg = consts.zero1
    bru_pred_take_dbg = state.br_pred_take.out()
    bru_boundary_pc_dbg = state.br_base_pc.out()
    if p.bru_w > 0:
        bru_slot = p.lsu_w
        bru_fire = issue_fires_eff[bru_slot]
        bru_op = uop_ops[bru_slot]
        bru_rob = uop_robs[bru_slot]
        bru_epoch = mux_by_uindex(m, idx=bru_rob, items=rob.block_epoch, default=c(0, width=16))
        bru_checkpoint = mux_by_uindex(m, idx=bru_rob, items=rob.checkpoint_id, default=c(0, width=6))
        bru_actual_take = wb_values[bru_slot].trunc(width=1)
        bru_is_setc_cond = is_setc_any(bru_op, op_is) & (~is_setc_tgt(bru_op, op_is))
        bru_validate_fire = bru_fire & bru_is_setc_cond
        bru_actual_take_dbg = bru_actual_take
        bru_pred_take_dbg = state.br_pred_take.out()
        bru_boundary_pc_dbg = state.br_base_pc.out()
        bru_target = state.br_base_pc.out() + state.br_off.out()
        bru_target = state.br_kind.out().eq(c(BK_RET, width=3)).select(state.commit_tgt.out(), bru_target)

        bru_target_known = consts.zero1
        bru_target_is_bstart = consts.zero1
        for i in range(pcb_depth):
            pc_hit = pcb_valid[i].out() & pcb_pc[i].out().eq(bru_target)
            hit = pc_hit & pcb_is_bstart[i].out()
            bru_target_known = pc_hit.select(consts.one1, bru_target_known)
            bru_target_is_bstart = hit.select(consts.one1, bru_target_is_bstart)
        bru_validate_en = (
            (state.br_kind.out().eq(c(BK_COND, width=3)) | state.br_kind.out().eq(c(BK_RET, width=3)))
            & bru_target_known
            & bru_epoch.eq(state.br_epoch.out())
        )
        bru_mismatch = bru_fire & bru_is_setc_cond & bru_validate_en & (~bru_actual_take.eq(state.br_pred_take.out()))
        bru_mismatch_evt = bru_mismatch
        bru_target_ok = (~bru_target_known) | bru_target_is_bstart
        bru_corr_set = bru_mismatch & bru_target_ok
        bru_corr_take = bru_actual_take
        bru_corr_target = bru_target
        bru_corr_checkpoint_id = bru_checkpoint
        bru_corr_epoch = bru_epoch
        bru_fault_set = bru_mismatch & bru_target_known & (~bru_target_is_bstart)
        bru_fault_rob = bru_rob

    # LSU violation detection: if an older store resolves to the same address
    # as a younger already-executed load, request replay from that load PC.
    for slot in range(p.issue_w):
        st_fire = store_fires[slot]
        st_rob = wb_robs[slot]
        st_addr = exs[slot].addr
        st_dist = st_rob + sub_head

        hit = consts.zero1
        hit_pc = consts.zero64
        hit_age = c((1 << p.rob_w) - 1, width=p.rob_w)
        for i in range(p.rob_depth):
            idx = c(i, width=p.rob_w)
            dist = idx + sub_head
            younger = st_dist.ult(dist)
            ld_done = rob.valid[i].out() & rob.done[i].out() & rob.is_load[i].out()
            addr_match = rob.load_addr[i].out().eq(st_addr)
            cand = st_fire & ld_done & younger & addr_match
            better = (~hit) | dist.ult(hit_age)
            take = cand & better
            hit = take.select(consts.one1, hit)
            hit_age = take.select(dist, hit_age)
            hit_pc = take.select(rob.pc[i].out(), hit_pc)

        set_this = hit & (~state.replay_pending.out()) & (~replay_set)
        replay_set = set_this.select(consts.one1, replay_set)
        replay_set_store_rob = set_this.select(st_rob, replay_set_store_rob)
        replay_set_pc = set_this.select(hit_pc, replay_set_pc)

    lsu_violation_detected = replay_set

    # --- dispatch decode stage ---
    decode_stage = m.instance(
        build_decode_stage,
        name="decode_stage",
        params={"dispatch_w": p.dispatch_w},
        f4_valid=f4_valid_i,
        f4_pc=f4_pc_i,
        f4_window=f4_window_i,
        f4_checkpoint_id=f4_checkpoint_i,
        f4_pkt_uid=f4_pkt_uid_i,
    )

    disp_valids = []
    disp_pcs = []
    disp_ops = []
    disp_lens = []
    disp_regdsts = []
    disp_srcls = []
    disp_srcrs = []
    disp_srcr_types = []
    disp_shamts = []
    disp_srcps = []
    disp_imms = []
    disp_insn_raws = []
    disp_is_start_marker = []
    disp_push_t = []
    disp_push_u = []
    disp_is_store = []
    disp_is_boundary = []
    disp_is_bstart = []
    disp_is_bstop = []
    disp_boundary_kind = []
    disp_boundary_target = []
    disp_pred_take = []
    disp_resolved_d2 = []
    disp_dst_is_gpr = []
    disp_need_pdst = []
    disp_dst_kind = []
    disp_checkpoint_ids = []
    disp_decode_uop_uids = []

    for slot in range(p.dispatch_w):
        disp_valids.append(decode_stage[f"valid{slot}"])
        disp_pcs.append(decode_stage[f"pc{slot}"])
        disp_ops.append(decode_stage[f"op{slot}"])
        disp_lens.append(decode_stage[f"len{slot}"])
        disp_regdsts.append(decode_stage[f"regdst{slot}"])
        disp_srcls.append(decode_stage[f"srcl{slot}"])
        disp_srcrs.append(decode_stage[f"srcr{slot}"])
        disp_srcr_types.append(decode_stage[f"srcr_type{slot}"])
        disp_shamts.append(decode_stage[f"shamt{slot}"])
        disp_srcps.append(decode_stage[f"srcp{slot}"])
        disp_imms.append(decode_stage[f"imm{slot}"])
        disp_insn_raws.append(decode_stage[f"insn_raw{slot}"])
        disp_is_start_marker.append(decode_stage[f"is_start_marker{slot}"])
        disp_push_t.append(decode_stage[f"push_t{slot}"])
        disp_push_u.append(decode_stage[f"push_u{slot}"])
        disp_is_store.append(decode_stage[f"is_store{slot}"])
        disp_is_boundary.append(decode_stage[f"is_boundary{slot}"])
        disp_is_bstart.append(decode_stage[f"is_bstart{slot}"])
        disp_is_bstop.append(decode_stage[f"is_bstop{slot}"])
        disp_boundary_kind.append(decode_stage[f"boundary_kind{slot}"])
        disp_boundary_target.append(decode_stage[f"boundary_target{slot}"])
        disp_pred_take.append(decode_stage[f"pred_take{slot}"])
        disp_resolved_d2.append(decode_stage[f"resolved_d2{slot}"])
        disp_dst_is_gpr.append(decode_stage[f"dst_is_gpr{slot}"])
        disp_need_pdst.append(decode_stage[f"need_pdst{slot}"])
        disp_dst_kind.append(decode_stage[f"dst_kind{slot}"])
        disp_checkpoint_ids.append(decode_stage[f"checkpoint_id{slot}"])
        disp_decode_uop_uids.append(decode_stage[f"uop_uid{slot}"])

    # Lane0 decode (stable trace hook).
    dec_op = decode_stage["dec_op"]
    disp_count = decode_stage["dispatch_count"]

    # Frontend-decoded BSTART metadata capture (PC buffer equivalent).
    pcb_wr_valids = []
    pcb_wr_idxs = []
    pcb_wr_pcs = []
    pcb_wr_kinds = []
    pcb_wr_targets = []
    pcb_wr_preds = []
    pcb_tail_tmp = pcb_tail.out()
    for slot in range(p.dispatch_w):
        wr = f4_valid_i & disp_valids[slot] & disp_is_bstart[slot]
        pcb_wr_valids.append(wr)
        pcb_wr_idxs.append(pcb_tail_tmp)
        pcb_wr_pcs.append(disp_pcs[slot])
        pcb_wr_kinds.append(disp_boundary_kind[slot])
        pcb_wr_targets.append(disp_boundary_target[slot])
        pcb_wr_preds.append(disp_pred_take[slot])
        pcb_tail_tmp = wr.select(pcb_tail_tmp + c(1, width=pcb_w), pcb_tail_tmp)
    pcb_tail.set(pcb_tail_tmp)
    for i in range(pcb_depth):
        idx = c(i, width=pcb_w)
        v_next = pcb_valid[i].out()
        pc_next = pcb_pc[i].out()
        kind_next = pcb_kind[i].out()
        tgt_next = pcb_target[i].out()
        pred_next = pcb_pred_take[i].out()
        isb_next = pcb_is_bstart[i].out()
        for slot in range(p.dispatch_w):
            hit = pcb_wr_valids[slot] & pcb_wr_idxs[slot].eq(idx)
            v_next = hit.select(consts.one1, v_next)
            pc_next = hit.select(pcb_wr_pcs[slot], pc_next)
            kind_next = hit.select(pcb_wr_kinds[slot], kind_next)
            tgt_next = hit.select(pcb_wr_targets[slot], tgt_next)
            pred_next = hit.select(pcb_wr_preds[slot], pred_next)
            isb_next = hit.select(consts.one1, isb_next)
        pcb_valid[i].set(v_next)
        pcb_pc[i].set(pc_next)
        pcb_kind[i].set(kind_next)
        pcb_target[i].set(tgt_next)
        pcb_pred_take[i].set(pred_next)
        pcb_is_bstart[i].set(isb_next)

    dispatch_stage_args = {
        "can_run": can_run,
        "commit_redirect": commit_redirect,
        "f4_valid": f4_valid_i,
        "block_epoch_in": state.br_epoch.out(),
        "block_uid_in": state.active_block_uid.out(),
        "block_bid_in": state.active_block_bid.out(),
        "lsid_alloc_base": state.lsid_alloc_ctr.out(),
        "rob_count": rob.count.out(),
        "disp_count": disp_count,
        "ren_free_mask": ren.free_mask.out(),
    }
    for slot in range(p.dispatch_w):
        dispatch_stage_args[f"disp_valid{slot}"] = disp_valids[slot]
        dispatch_stage_args[f"disp_op{slot}"] = disp_ops[slot]
        dispatch_stage_args[f"disp_need_pdst{slot}"] = disp_need_pdst[slot]
    for i in range(p.iq_depth):
        dispatch_stage_args[f"iq_alu_valid{i}"] = iq_alu.valid[i].out()
        dispatch_stage_args[f"iq_bru_valid{i}"] = iq_bru.valid[i].out()
        dispatch_stage_args[f"iq_lsu_valid{i}"] = iq_lsu.valid[i].out()
        dispatch_stage_args[f"iq_cmd_valid{i}"] = iq_cmd.valid[i].out()

    dispatch_stage = m.instance(
        build_dispatch_stage,
        name="dispatch_stage",
        params={
            "dispatch_w": p.dispatch_w,
            "iq_depth": p.iq_depth,
            "iq_w": p.iq_w,
            "rob_depth": p.rob_depth,
            "rob_w": p.rob_w,
            "pregs": p.pregs,
            "ptag_w": p.ptag_w,
        },
        **dispatch_stage_args,
    )

    rob_space_ok = dispatch_stage["rob_space_ok"]
    iq_alloc_ok = dispatch_stage["iq_alloc_ok"]
    preg_alloc_ok = dispatch_stage["preg_alloc_ok"]
    frontend_ready = dispatch_stage["frontend_ready"]
    dispatch_fire = dispatch_stage["dispatch_fire"]
    disp_alloc_mask = dispatch_stage["disp_alloc_mask"]
    lsid_alloc_next = dispatch_stage["lsid_alloc_next"]

    disp_to_alu = []
    disp_to_bru = []
    disp_to_lsu = []
    disp_to_d2 = []
    disp_to_cmd = []
    alu_alloc_valids = []
    alu_alloc_idxs = []
    bru_alloc_valids = []
    bru_alloc_idxs = []
    lsu_alloc_valids = []
    lsu_alloc_idxs = []
    cmd_alloc_valids = []
    cmd_alloc_idxs = []
    disp_pdsts = []
    disp_block_uids = []
    disp_block_bids = []
    disp_load_store_ids = []
    for slot in range(p.dispatch_w):
        disp_to_alu.append(dispatch_stage[f"to_alu{slot}"])
        disp_to_bru.append(dispatch_stage[f"to_bru{slot}"])
        disp_to_lsu.append(dispatch_stage[f"to_lsu{slot}"])
        disp_to_d2.append(dispatch_stage[f"to_d2{slot}"])
        disp_to_cmd.append(dispatch_stage[f"to_cmd{slot}"])
        alu_alloc_valids.append(dispatch_stage[f"alu_alloc_valid{slot}"])
        alu_alloc_idxs.append(dispatch_stage[f"alu_alloc_idx{slot}"])
        bru_alloc_valids.append(dispatch_stage[f"bru_alloc_valid{slot}"])
        bru_alloc_idxs.append(dispatch_stage[f"bru_alloc_idx{slot}"])
        lsu_alloc_valids.append(dispatch_stage[f"lsu_alloc_valid{slot}"])
        lsu_alloc_idxs.append(dispatch_stage[f"lsu_alloc_idx{slot}"])
        cmd_alloc_valids.append(dispatch_stage[f"cmd_alloc_valid{slot}"])
        cmd_alloc_idxs.append(dispatch_stage[f"cmd_alloc_idx{slot}"])
        disp_pdsts.append(dispatch_stage[f"disp_pdst{slot}"])
        disp_block_uids.append(dispatch_stage[f"disp_block_uid{slot}"])
        disp_block_bids.append(dispatch_stage[f"disp_block_bid{slot}"])
        disp_load_store_ids.append(dispatch_stage[f"disp_load_store_id{slot}"])

    # Source PTAGs from SMAP with intra-cycle rename forwarding across lanes.
    rename_stage_args = {"dispatch_fire": dispatch_fire}
    for i in range(p.aregs):
        rename_stage_args[f"smap{i}"] = ren.smap[i].out()
    for slot in range(p.dispatch_w):
        rename_stage_args[f"disp_valid{slot}"] = disp_valids[slot]
        rename_stage_args[f"disp_srcl{slot}"] = disp_srcls[slot]
        rename_stage_args[f"disp_srcr{slot}"] = disp_srcrs[slot]
        rename_stage_args[f"disp_srcp{slot}"] = disp_srcps[slot]
        rename_stage_args[f"disp_is_start_marker{slot}"] = disp_is_start_marker[slot]
        rename_stage_args[f"disp_push_t{slot}"] = disp_push_t[slot]
        rename_stage_args[f"disp_push_u{slot}"] = disp_push_u[slot]
        rename_stage_args[f"disp_dst_is_gpr{slot}"] = disp_dst_is_gpr[slot]
        rename_stage_args[f"disp_regdst{slot}"] = disp_regdsts[slot]
        rename_stage_args[f"disp_pdst{slot}"] = disp_pdsts[slot]

    rename_stage = m.instance(
        build_rename_stage,
        name="rename_stage",
        params={
            "dispatch_w": p.dispatch_w,
            "aregs": p.aregs,
            "ptag_w": p.ptag_w,
            "reg_invalid": REG_INVALID,
        },
        **rename_stage_args,
    )

    disp_srcl_tags = []
    disp_srcr_tags = []
    disp_srcp_tags = []
    for slot in range(p.dispatch_w):
        disp_srcl_tags.append(rename_stage[f"srcl_tag{slot}"])
        disp_srcr_tags.append(rename_stage[f"srcr_tag{slot}"])
        disp_srcp_tags.append(rename_stage[f"srcp_tag{slot}"])
    smap_live = [rename_stage[f"smap_next{i}"] for i in range(p.aregs)]

    rename_free_after_dispatch = dispatch_fire.select(ren.free_mask.out() & (~disp_alloc_mask), ren.free_mask.out())
    rename_ready_after_dispatch = dispatch_fire.select(ren.ready_mask.out() & (~disp_alloc_mask), ren.ready_mask.out())

    # Snapshot rename/freelist state for branch/start-marker recovery.
    ckpt_write = consts.zero1
    ckpt_write_idx = c(0, width=ckpt_w)
    for slot in range(p.dispatch_w):
        lane_fire = dispatch_fire & disp_valids[slot]
        is_ckpt = lane_fire & disp_is_start_marker[slot]
        ckpt_idx = disp_checkpoint_ids[slot].trunc(width=ckpt_w)
        ckpt_write = is_ckpt.select(consts.one1, ckpt_write)
        ckpt_write_idx = is_ckpt.select(ckpt_idx, ckpt_write_idx)

    for ci in range(ckpt_entries):
        ciw = c(ci, width=ckpt_w)
        do_ckpt = ckpt_write & ckpt_write_idx.eq(ciw)
        valid_next = ren.ckpt_valid[ci].out()
        valid_next = do_ckpt.select(consts.one1, valid_next)
        ren.ckpt_valid[ci].set(valid_next)
        ren.ckpt_free_mask[ci].set(rename_free_after_dispatch, when=do_ckpt)
        ren.ckpt_ready_mask[ci].set(rename_ready_after_dispatch, when=do_ckpt)
        for r in range(p.aregs):
            ren.ckpt_smap[ci][r].set(smap_live[r], when=do_ckpt)

    flush_ckpt_idx = state.flush_checkpoint_id.out().trunc(width=ckpt_w)
    flush_ckpt_valid = mux_by_uindex(m, idx=flush_ckpt_idx, items=ren.ckpt_valid, default=consts.zero1)
    flush_free_from_ckpt = mux_by_uindex(m, idx=flush_ckpt_idx, items=ren.ckpt_free_mask, default=ren.free_mask.out())
    flush_ready_from_ckpt = mux_by_uindex(m, idx=flush_ckpt_idx, items=ren.ckpt_ready_mask, default=ren.ready_mask.out())
    # Bring-up fallback: recover rename state from committed map on flush.
    # Checkpoint restore remains wired but disabled until checkpoint parity is stable.
    restore_from_ckpt = consts.zero1

    # --- ready table updates ---
    ready_next = ren.ready_mask.out()
    ready_next = dispatch_fire.select(ready_next & (~disp_alloc_mask), ready_next)

    wb_set_mask = c(0, width=p.pregs)
    for slot in range(p.issue_w):
        wb_set_mask = wb_fire_has_dsts[slot].select(wb_set_mask | wb_onehots[slot], wb_set_mask)
    ready_next = ready_next | wb_set_mask
    ready_next = do_flush.select(c((1 << p.pregs) - 1, width=p.pregs), ready_next)
    ready_next = restore_from_ckpt.select(flush_ready_from_ckpt, ready_next)
    ren.ready_mask.set(ready_next)

    # PRF writes (up to issue_w writebacks per cycle).
    for i in range(p.pregs):
        we = consts.zero1
        wdata = consts.zero64
        for slot in range(p.issue_w):
            hit = wb_fire_has_dsts[slot] & wb_pdsts[slot].eq(c(i, width=p.ptag_w))
            we = we | hit
            wdata = hit.select(wb_values[slot], wdata)
        hit_macro = macro_prf_we & macro_prf_tag.eq(c(i, width=p.ptag_w))
        we = we | hit_macro
        wdata = hit_macro.select(macro_prf_data, wdata)
        prf[i].set(wdata, when=we)

    # --- ROB updates ---
    rob_ctrl_args = {
        "do_flush": do_flush,
        "commit_fire": commit_fire,
        "dispatch_fire": dispatch_fire,
        "rob_head": rob.head.out(),
        "rob_tail": rob.tail.out(),
        "rob_count": rob.count.out(),
        "commit_count": commit_count,
        "disp_count": disp_count,
    }
    for slot in range(p.dispatch_w):
        rob_ctrl_args[f"disp_valid{slot}"] = disp_valids[slot]
    rob_ctrl = m.instance(
        build_rob_ctrl_stage,
        name="rob_ctrl_stage",
        params={"dispatch_w": p.dispatch_w, "rob_w": p.rob_w},
        **rob_ctrl_args,
    )
    disp_rob_idxs = [rob_ctrl[f"disp_rob_idx{slot}"] for slot in range(p.dispatch_w)]
    disp_fires = [rob_ctrl[f"disp_fire{slot}"] for slot in range(p.dispatch_w)]
    disp_uop_uids = []
    disp_parent_uids = []
    disp_block_epochs = []
    for slot in range(p.dispatch_w):
        disp_uop_uids.append(disp_decode_uop_uids[slot])
        disp_parent_uids.append(consts.zero64)
        disp_block_epochs.append(dispatch_stage[f"disp_block_epoch{slot}"])

    for i in range(p.rob_depth):
        rob_entry_args = {
            "idx": c(i, width=p.rob_w),
            "do_flush": do_flush,
            "old_valid": rob.valid[i].out(),
            "old_done": rob.done[i].out(),
            "old_pc": rob.pc[i].out(),
            "old_op": rob.op[i].out(),
            "old_len": rob.len_bytes[i].out(),
            "old_insn_raw": rob.insn_raw[i].out(),
            "old_checkpoint_id": rob.checkpoint_id[i].out(),
            "old_dst_kind": rob.dst_kind[i].out(),
            "old_dst_areg": rob.dst_areg[i].out(),
            "old_pdst": rob.pdst[i].out(),
            "old_value": rob.value[i].out(),
            "old_src0_reg": rob.src0_reg[i].out(),
            "old_src1_reg": rob.src1_reg[i].out(),
            "old_src0_value": rob.src0_value[i].out(),
            "old_src1_value": rob.src1_value[i].out(),
            "old_src0_valid": rob.src0_valid[i].out(),
            "old_src1_valid": rob.src1_valid[i].out(),
            "old_is_store": rob.is_store[i].out(),
            "old_store_addr": rob.store_addr[i].out(),
            "old_store_data": rob.store_data[i].out(),
            "old_store_size": rob.store_size[i].out(),
            "old_is_load": rob.is_load[i].out(),
            "old_load_addr": rob.load_addr[i].out(),
            "old_load_data": rob.load_data[i].out(),
            "old_load_size": rob.load_size[i].out(),
            "old_is_boundary": rob.is_boundary[i].out(),
            "old_is_bstart": rob.is_bstart[i].out(),
            "old_is_bstop": rob.is_bstop[i].out(),
            "old_boundary_kind": rob.boundary_kind[i].out(),
            "old_boundary_target": rob.boundary_target[i].out(),
            "old_pred_take": rob.pred_take[i].out(),
            "old_block_epoch": rob.block_epoch[i].out(),
            "old_block_uid": rob.block_uid[i].out(),
            "old_block_bid": rob.block_bid[i].out(),
            "old_load_store_id": rob.load_store_id[i].out(),
            "old_resolved_d2": rob.resolved_d2[i].out(),
            "old_macro_begin": rob.macro_begin[i].out(),
            "old_macro_end": rob.macro_end[i].out(),
            "old_uop_uid": rob.uop_uid[i].out(),
            "old_parent_uid": rob.parent_uid[i].out(),
        }
        for slot in range(p.commit_w):
            rob_entry_args[f"commit_fire{slot}"] = commit_fires[slot]
            rob_entry_args[f"commit_idx{slot}"] = commit_idxs[slot]
        for slot in range(p.dispatch_w):
            rob_entry_args[f"disp_fire{slot}"] = disp_fires[slot]
            rob_entry_args[f"disp_rob_idx{slot}"] = disp_rob_idxs[slot]
            rob_entry_args[f"disp_pc{slot}"] = disp_pcs[slot]
            rob_entry_args[f"disp_op{slot}"] = disp_ops[slot]
            rob_entry_args[f"disp_len{slot}"] = disp_lens[slot]
            rob_entry_args[f"disp_insn_raw{slot}"] = disp_insn_raws[slot]
            rob_entry_args[f"disp_checkpoint_id{slot}"] = disp_checkpoint_ids[slot]
            rob_entry_args[f"disp_dst_kind{slot}"] = disp_dst_kind[slot]
            rob_entry_args[f"disp_regdst{slot}"] = disp_regdsts[slot]
            rob_entry_args[f"disp_pdst{slot}"] = disp_pdsts[slot]
            rob_entry_args[f"disp_imm{slot}"] = disp_imms[slot]
            rob_entry_args[f"disp_is_store{slot}"] = disp_is_store[slot]
            rob_entry_args[f"disp_is_boundary{slot}"] = disp_is_boundary[slot]
            rob_entry_args[f"disp_is_bstart{slot}"] = disp_is_bstart[slot]
            rob_entry_args[f"disp_is_bstop{slot}"] = disp_is_bstop[slot]
            rob_entry_args[f"disp_boundary_kind{slot}"] = disp_boundary_kind[slot]
            rob_entry_args[f"disp_boundary_target{slot}"] = disp_boundary_target[slot]
            rob_entry_args[f"disp_pred_take{slot}"] = disp_pred_take[slot]
            rob_entry_args[f"disp_block_epoch{slot}"] = disp_block_epochs[slot]
            rob_entry_args[f"disp_block_uid{slot}"] = disp_block_uids[slot]
            rob_entry_args[f"disp_block_bid{slot}"] = disp_block_bids[slot]
            rob_entry_args[f"disp_load_store_id{slot}"] = disp_load_store_ids[slot]
            rob_entry_args[f"disp_resolved_d2{slot}"] = disp_resolved_d2[slot]
            rob_entry_args[f"disp_srcl{slot}"] = disp_srcls[slot]
            rob_entry_args[f"disp_srcr{slot}"] = disp_srcrs[slot]
            rob_entry_args[f"disp_uop_uid{slot}"] = disp_uop_uids[slot]
            rob_entry_args[f"disp_parent_uid{slot}"] = disp_parent_uids[slot]
        for slot in range(p.issue_w):
            rob_entry_args[f"wb_fire{slot}"] = wb_fires[slot]
            rob_entry_args[f"wb_rob{slot}"] = wb_robs[slot]
            rob_entry_args[f"wb_value{slot}"] = wb_values[slot]
            rob_entry_args[f"store_fire{slot}"] = store_fires[slot]
            rob_entry_args[f"load_fire{slot}"] = load_fires[slot]
            rob_entry_args[f"ex_addr{slot}"] = exs[slot].addr
            rob_entry_args[f"ex_wdata{slot}"] = exs[slot].wdata
            rob_entry_args[f"ex_size{slot}"] = exs[slot].size
            rob_entry_args[f"ex_src0{slot}"] = sl_vals[slot]
            rob_entry_args[f"ex_src1{slot}"] = sr_vals[slot]

        rob_entry = m.instance(
            build_rob_entry_update_stage,
            name=f"rob_entry_update_stage_{i}",
            params={
                "dispatch_w": p.dispatch_w,
                "issue_w": p.issue_w,
                "commit_w": p.commit_w,
                "rob_w": p.rob_w,
                "ptag_w": p.ptag_w,
            },
            **rob_entry_args,
        )
        rob.valid[i].set(rob_entry["valid_next"])
        rob.done[i].set(rob_entry["done_next"])
        rob.pc[i].set(rob_entry["pc_next"])
        rob.op[i].set(rob_entry["op_next"])
        rob.len_bytes[i].set(rob_entry["len_next"])
        rob.insn_raw[i].set(rob_entry["insn_raw_next"])
        rob.checkpoint_id[i].set(rob_entry["checkpoint_id_next"])
        rob.dst_kind[i].set(rob_entry["dst_kind_next"])
        rob.dst_areg[i].set(rob_entry["dst_areg_next"])
        rob.pdst[i].set(rob_entry["pdst_next"])
        rob.value[i].set(rob_entry["value_next"])
        rob.src0_reg[i].set(rob_entry["src0_reg_next"])
        rob.src1_reg[i].set(rob_entry["src1_reg_next"])
        rob.src0_value[i].set(rob_entry["src0_value_next"])
        rob.src1_value[i].set(rob_entry["src1_value_next"])
        rob.src0_valid[i].set(rob_entry["src0_valid_next"])
        rob.src1_valid[i].set(rob_entry["src1_valid_next"])
        rob.is_store[i].set(rob_entry["is_store_next"])
        rob.store_addr[i].set(rob_entry["store_addr_next"])
        rob.store_data[i].set(rob_entry["store_data_next"])
        rob.store_size[i].set(rob_entry["store_size_next"])
        rob.is_load[i].set(rob_entry["is_load_next"])
        rob.load_addr[i].set(rob_entry["load_addr_next"])
        rob.load_data[i].set(rob_entry["load_data_next"])
        rob.load_size[i].set(rob_entry["load_size_next"])
        rob.is_boundary[i].set(rob_entry["is_boundary_next"])
        rob.is_bstart[i].set(rob_entry["is_bstart_next"])
        rob.is_bstop[i].set(rob_entry["is_bstop_next"])
        rob.boundary_kind[i].set(rob_entry["boundary_kind_next"])
        rob.boundary_target[i].set(rob_entry["boundary_target_next"])
        rob.pred_take[i].set(rob_entry["pred_take_next"])
        rob.block_epoch[i].set(rob_entry["block_epoch_next"])
        rob.block_uid[i].set(rob_entry["block_uid_next"])
        rob.block_bid[i].set(rob_entry["block_bid_next"])
        rob.load_store_id[i].set(rob_entry["load_store_id_next"])
        rob.resolved_d2[i].set(rob_entry["resolved_d2_next"])
        rob.macro_begin[i].set(rob_entry["macro_begin_next"])
        rob.macro_end[i].set(rob_entry["macro_end_next"])
        rob.uop_uid[i].set(rob_entry["uop_uid_next"])
        rob.parent_uid[i].set(rob_entry["parent_uid_next"])

    # ROB pointers/count.
    rob.head.set(rob_ctrl["head_next"])
    rob.tail.set(rob_ctrl["tail_next"])
    rob.count.set(rob_ctrl["count_next"])

    # --- IQ updates ---
    def update_iq_from_stage(*, name: str, iq, disp_to: list, alloc_idxs: list, issue_fires_q: list, issue_idxs_q: list) -> None:
        stage_args = {"do_flush": do_flush}
        for i in range(p.iq_depth):
            stage_args[f"iq_valid{i}"] = iq.valid[i].out()
            stage_args[f"iq_rob{i}"] = iq.rob[i].out()
            stage_args[f"iq_op{i}"] = iq.op[i].out()
            stage_args[f"iq_pc{i}"] = iq.pc[i].out()
            stage_args[f"iq_imm{i}"] = iq.imm[i].out()
            stage_args[f"iq_srcl{i}"] = iq.srcl[i].out()
            stage_args[f"iq_srcr{i}"] = iq.srcr[i].out()
            stage_args[f"iq_srcr_type{i}"] = iq.srcr_type[i].out()
            stage_args[f"iq_shamt{i}"] = iq.shamt[i].out()
            stage_args[f"iq_srcp{i}"] = iq.srcp[i].out()
            stage_args[f"iq_pdst{i}"] = iq.pdst[i].out()
            stage_args[f"iq_has_dst{i}"] = iq.has_dst[i].out()

        for slot in range(p.dispatch_w):
            stage_args[f"disp_fire{slot}"] = disp_fires[slot]
            stage_args[f"disp_to{slot}"] = disp_to[slot]
            stage_args[f"alloc_idx{slot}"] = alloc_idxs[slot]
            stage_args[f"disp_rob_idx{slot}"] = disp_rob_idxs[slot]
            stage_args[f"disp_op{slot}"] = disp_ops[slot]
            stage_args[f"disp_pc{slot}"] = disp_pcs[slot]
            stage_args[f"disp_imm{slot}"] = disp_imms[slot]
            stage_args[f"disp_srcl_tag{slot}"] = disp_srcl_tags[slot]
            stage_args[f"disp_srcr_tag{slot}"] = disp_srcr_tags[slot]
            stage_args[f"disp_srcr_type{slot}"] = disp_srcr_types[slot]
            stage_args[f"disp_shamt{slot}"] = disp_shamts[slot]
            stage_args[f"disp_srcp_tag{slot}"] = disp_srcp_tags[slot]
            stage_args[f"disp_pdst{slot}"] = disp_pdsts[slot]
            stage_args[f"disp_need_pdst{slot}"] = disp_need_pdst[slot]

        for slot in range(len(issue_fires_q)):
            stage_args[f"issue_fire{slot}"] = issue_fires_q[slot]
            stage_args[f"issue_idx{slot}"] = issue_idxs_q[slot]

        iq_stage = m.instance(
            build_iq_update_stage,
            name=name,
            params={
                "iq_depth": p.iq_depth,
                "iq_w": p.iq_w,
                "rob_w": p.rob_w,
                "ptag_w": p.ptag_w,
                "dispatch_w": p.dispatch_w,
                "issue_w": len(issue_fires_q),
            },
            **stage_args,
        )

        for i in range(p.iq_depth):
            iq.valid[i].set(iq_stage[f"iq_valid_next{i}"])
            iq.rob[i].set(iq_stage[f"iq_rob_next{i}"])
            iq.op[i].set(iq_stage[f"iq_op_next{i}"])
            iq.pc[i].set(iq_stage[f"iq_pc_next{i}"])
            iq.imm[i].set(iq_stage[f"iq_imm_next{i}"])
            iq.srcl[i].set(iq_stage[f"iq_srcl_next{i}"])
            iq.srcr[i].set(iq_stage[f"iq_srcr_next{i}"])
            iq.srcr_type[i].set(iq_stage[f"iq_srcr_type_next{i}"])
            iq.shamt[i].set(iq_stage[f"iq_shamt_next{i}"])
            iq.srcp[i].set(iq_stage[f"iq_srcp_next{i}"])
            iq.pdst[i].set(iq_stage[f"iq_pdst_next{i}"])
            iq.has_dst[i].set(iq_stage[f"iq_has_dst_next{i}"])

    lsu_base = 0
    bru_base = p.lsu_w
    alu_base = p.lsu_w + p.bru_w
    alu_issue_fires_eff = [issue_fires_eff[alu_base + i] for i in range(p.alu_w)]
    cmd_slot_in_alu = cmd_slot - alu_base
    if (cmd_slot_in_alu >= 0) and (cmd_slot_in_alu < p.alu_w):
        alu_issue_fires_eff[cmd_slot_in_alu] = alu_issue_fires_eff[cmd_slot_in_alu] & (~cmd_slot_sel)
    update_iq_from_stage(
        name="iq_lsu_update_stage",
        iq=iq_lsu,
        disp_to=disp_to_lsu,
        alloc_idxs=lsu_alloc_idxs,
        issue_fires_q=issue_fires_eff[lsu_base : lsu_base + p.lsu_w],
        issue_idxs_q=lsu_issue_idxs,
    )
    update_iq_from_stage(
        name="iq_bru_update_stage",
        iq=iq_bru,
        disp_to=disp_to_bru,
        alloc_idxs=bru_alloc_idxs,
        issue_fires_q=issue_fires_eff[bru_base : bru_base + p.bru_w],
        issue_idxs_q=bru_issue_idxs,
    )
    update_iq_from_stage(
        name="iq_alu_update_stage",
        iq=iq_alu,
        disp_to=disp_to_alu,
        alloc_idxs=alu_alloc_idxs,
        issue_fires_q=alu_issue_fires_eff,
        issue_idxs_q=alu_issue_idxs,
    )
    update_iq_from_stage(
        name="iq_cmd_update_stage",
        iq=iq_cmd,
        disp_to=disp_to_cmd,
        alloc_idxs=cmd_alloc_idxs,
        issue_fires_q=[cmd_issue_fire_eff],
        issue_idxs_q=[cmd_issue_idx],
    )

    # --- SMAP updates (rename) ---
    for i in range(p.aregs):
        nxt = smap_live[i]
        nxt = do_flush.select(ren.cmap[i].out(), nxt)
        ckpt_smap_i = mux_by_uindex(
            m,
            idx=flush_ckpt_idx,
            items=[ren.ckpt_smap[ci][i] for ci in range(ckpt_entries)],
            default=ren.cmap[i].out(),
        )
        nxt = restore_from_ckpt.select(ckpt_smap_i, nxt)
        if i == 0:
            nxt = tag0
        ren.smap[i].set(nxt)

    # --- CMAP + freelist updates (commit) ---
    rename_commit_args = {"free_in": rename_free_after_dispatch}
    for i in range(p.aregs):
        rename_commit_args[f"cmap{i}"] = ren.cmap[i].out()
    for slot in range(p.commit_w):
        rename_commit_args[f"commit_fire{slot}"] = commit_fires[slot]
        rename_commit_args[f"commit_is_bstop{slot}"] = commit_is_bstops[slot]
        rename_commit_args[f"rob_dst_kind{slot}"] = rob_dst_kinds[slot]
        rename_commit_args[f"rob_dst_areg{slot}"] = rob_dst_aregs[slot]
        rename_commit_args[f"rob_pdst{slot}"] = rob_pdsts[slot]

    rename_commit = m.instance(
        build_commit_rename_stage,
        name="rename_commit_stage",
        params={"aregs": p.aregs, "pregs": p.pregs, "ptag_w": p.ptag_w, "commit_w": p.commit_w},
        **rename_commit_args,
    )
    for i in range(p.aregs):
        ren.cmap[i].set(rename_commit[f"cmap_out{i}"])
    free_live = rename_commit["free_out"]

    # Flush recomputes freelist from CMAP to drop speculative allocations.
    used = c(0, width=p.pregs)
    for i in range(p.aregs):
        used = used | onehot_from_tag(m, tag=ren.cmap[i].out(), width=p.pregs, tag_width=p.ptag_w)
    free_recomputed = ~used
    free_next = do_flush.select(free_recomputed, free_live)
    free_next = restore_from_ckpt.select(flush_free_from_ckpt, free_next)
    ren.free_mask.set(free_next)

    # --- commit state updates (pc/br/control regs) ---
    state.pc.set(pc_live)
    # Architectural redirect authority is boundary-commit only.
    commit_redirect_any = commit_redirect
    redirect_pc_any = redirect_pc
    redirect_checkpoint_id_any = redirect_checkpoint_id

    # Deferred BRU correction/fault tracking.
    br_corr_pending_n = br_corr_pending_live
    br_corr_epoch_n = br_corr_epoch_live
    br_corr_take_n = br_corr_take_live
    br_corr_target_n = br_corr_target_live
    br_corr_checkpoint_id_n = br_corr_checkpoint_id_live
    br_corr_pending_n = do_flush.select(consts.zero1, br_corr_pending_n)
    br_corr_pending_n = bru_corr_set.select(consts.one1, br_corr_pending_n)
    br_corr_epoch_n = bru_corr_set.select(bru_corr_epoch, br_corr_epoch_n)
    br_corr_take_n = bru_corr_set.select(bru_corr_take, br_corr_take_n)
    br_corr_target_n = bru_corr_set.select(bru_corr_target, br_corr_target_n)
    br_corr_checkpoint_id_n = bru_corr_set.select(bru_corr_checkpoint_id, br_corr_checkpoint_id_n)
    corr_epoch_stale = br_corr_pending_n & (~br_corr_epoch_n.eq(br_epoch_live))
    br_corr_pending_n = corr_epoch_stale.select(consts.zero1, br_corr_pending_n)

    br_corr_fault_pending_n = state.br_corr_fault_pending.out()
    br_corr_fault_rob_n = state.br_corr_fault_rob.out()
    br_corr_fault_pending_n = do_flush.select(consts.zero1, br_corr_fault_pending_n)
    br_corr_fault_pending_n = bru_fault_set.select(consts.one1, br_corr_fault_pending_n)
    br_corr_fault_rob_n = bru_fault_set.select(bru_fault_rob, br_corr_fault_rob_n)

    commit_ctrl_args = {
        "do_flush": do_flush,
        "f4_valid": f4_valid_i,
        "f4_pc": f4_pc_i,
        "commit_redirect": commit_redirect_any,
        "redirect_pc": redirect_pc_any,
        "redirect_checkpoint_id": redirect_checkpoint_id_any,
        "mmio_exit": mmio_exit,
        "state_fpc": state.fpc.out(),
        "state_flush_pc": state.flush_pc.out(),
        "state_flush_checkpoint_id": state.flush_checkpoint_id.out(),
        "state_flush_pending": state.flush_pending.out(),
        "state_replay_pending": state.replay_pending.out(),
        "state_replay_store_rob": state.replay_store_rob.out(),
        "state_replay_pc": state.replay_pc.out(),
        "replay_redirect_fire": replay_redirect_fire,
        "replay_set": replay_set,
        "replay_set_store_rob": replay_set_store_rob,
        "replay_set_pc": replay_set_pc,
    }
    for slot in range(p.commit_w):
        commit_ctrl_args[f"commit_fire{slot}"] = commit_fires[slot]
        commit_ctrl_args[f"rob_op{slot}"] = rob_ops[slot]

    commit_ctrl = m.instance(
        build_commit_ctrl_stage,
        name="commit_ctrl_stage",
        params={"commit_w": p.commit_w, "rob_w": p.rob_w},
        **commit_ctrl_args,
    )
    state.fpc.set(commit_ctrl["fpc_next"])
    state.flush_pc.set(commit_ctrl["flush_pc_next"])
    state.flush_checkpoint_id.set(commit_ctrl["flush_checkpoint_id_next"])
    state.flush_pending.set(commit_ctrl["flush_pending_next"])
    state.replay_pending.set(commit_ctrl["replay_pending_next"])
    state.replay_store_rob.set(commit_ctrl["replay_store_rob_next"])
    state.replay_pc.set(commit_ctrl["replay_pc_next"])
    trap_retire = consts.zero1
    for slot in range(p.commit_w):
        trap_retire = trap_retire | (commit_fires[slot] & commit_idxs[slot].eq(state.trap_rob.out()))
    trap_retire = trap_retire & state.trap_pending.out()
    trap_pending_n = state.trap_pending.out()
    trap_rob_n = state.trap_rob.out()
    trap_cause_n = state.trap_cause.out()
    trap_pending_n = do_flush.select(consts.zero1, trap_pending_n)
    trap_pending_n = trap_retire.select(consts.zero1, trap_pending_n)
    trap_pending_n = br_corr_fault_pending_n.select(consts.one1, trap_pending_n)
    trap_rob_n = br_corr_fault_pending_n.select(br_corr_fault_rob_n, trap_rob_n)
    trap_cause_n = br_corr_fault_pending_n.select(c(TRAP_BRU_RECOVERY_NOT_BSTART, width=32), trap_cause_n)
    state.trap_pending.set(trap_pending_n)
    state.trap_rob.set(trap_rob_n)
    state.trap_cause.set(trap_cause_n)
    br_corr_fault_pending_n = trap_retire.select(consts.zero1, br_corr_fault_pending_n)
    state.br_corr_fault_pending.set(br_corr_fault_pending_n)
    state.br_corr_fault_rob.set(br_corr_fault_rob_n)
    state.halted.set(consts.one1, when=(commit_ctrl["halt_set"] | trap_retire))

    commit_cond_live = macro_setc_tgt_fire.select(consts.one1, commit_cond_live)
    commit_tgt_live = macro_setc_tgt_fire.select(macro_setc_tgt_data, commit_tgt_live)
    state.cycles.set(state.cycles.out() + consts.one64)
    state.commit_cond.set(commit_cond_live)
    state.commit_tgt.set(commit_tgt_live)
    state.br_kind.set(br_kind_live)
    state.br_epoch.set(br_epoch_live)
    state.br_base_pc.set(br_base_live)
    state.br_off.set(br_off_live)
    state.br_pred_take.set(br_pred_take_live)
    state.active_block_uid.set(active_block_uid_live)
    state.block_uid_ctr.set(block_uid_ctr_live)
    state.active_block_bid.set(active_block_bid_live)
    state.block_bid_ctr.set(block_bid_ctr_live)
    state.lsid_alloc_ctr.set(lsid_alloc_next)
    state.lsid_issue_ptr.set(lsid_issue_ptr_live)
    state.lsid_complete_ptr.set(lsid_complete_ptr_live)
    state.block_head.set(do_flush.select(consts.one1, block_head_live))
    state.br_corr_pending.set(br_corr_pending_n)
    state.br_corr_epoch.set(br_corr_epoch_n)
    state.br_corr_take.set(br_corr_take_n)
    state.br_corr_target.set(br_corr_target_n)
    state.br_corr_checkpoint_id.set(br_corr_checkpoint_id_n)

    # --- template macro engine state updates ---
    #
    # Implements the bring-up ABI semantics used by QEMU/LLVM:
    # - FENTRY: SP_SUB, then STORE loop.
    # - FEXIT: SP_ADD, then LOAD loop.
    # - FRET.STK: SP_ADD, LOAD ra, SETC.TGT ra, then remaining LOAD loop.
    # - FRET.RA: SETC.TGT ra, SP_ADD, then LOAD loop.
    ph_init = c(0, width=2)
    ph_mem = c(1, width=2)
    ph_sp = c(2, width=2)
    ph_setc = c(3, width=2)

    macro_active_n = macro_active
    macro_phase_n = macro_phase
    macro_op_n = macro_op
    macro_begin_n = state.macro_begin.out()
    macro_end_n = state.macro_end.out()
    macro_stack_n = macro_stacksize
    macro_reg_n = macro_reg
    macro_i_n = macro_i
    macro_sp_base_n = macro_sp_base

    macro_active_n = do_flush.select(consts.zero1, macro_active_n)
    macro_phase_n = do_flush.select(ph_init, macro_phase_n)

    macro_active_n = macro_start.select(consts.one1, macro_active_n)
    macro_phase_n = macro_start.select(ph_init, macro_phase_n)
    macro_op_n = macro_start.select(head_op, macro_op_n)
    macro_begin_n = macro_start.select(head_macro_begin, macro_begin_n)
    macro_end_n = macro_start.select(head_macro_end, macro_end_n)
    macro_stack_n = macro_start.select(head_value, macro_stack_n)
    macro_reg_n = macro_start.select(head_macro_begin, macro_reg_n)
    macro_i_n = macro_start.select(c(0, width=6), macro_i_n)

    macro_phase_is_init = macro_phase_init
    macro_phase_is_mem = macro_phase_mem
    macro_phase_is_sp = macro_phase_sp
    macro_phase_is_setc = macro_phase_setc

    # Init: latch base SP and setup iteration.
    init_fire = macro_active & macro_phase_is_init
    sp_new_init = macro_sp_val - macro_frame_adj
    sp_new_restore = macro_sp_val + macro_frame_adj
    macro_sp_base_n = (init_fire & macro_is_fentry).select(sp_new_init, macro_sp_base_n)
    macro_sp_base_n = (init_fire & (macro_is_fexit | macro_is_fret_stk)).select(sp_new_restore, macro_sp_base_n)
    macro_reg_n = init_fire.select(macro_begin, macro_reg_n)
    macro_i_n = init_fire.select(c(0, width=6), macro_i_n)
    macro_phase_n = (init_fire & (macro_is_fentry | macro_is_fexit | macro_is_fret_stk)).select(ph_mem, macro_phase_n)
    macro_phase_n = (init_fire & macro_is_fret_ra).select(ph_sp, macro_phase_n)

    # Mem loop: iterate regs and offsets; save uses store port, restore uses load port.
    step_fire = ctu["loop_fire"]
    step_done = ctu["loop_done"]
    reg_next = ctu["loop_reg_next"]
    i_next = ctu["loop_i_next"]
    macro_reg_n = (step_fire & (~step_done)).select(reg_next, macro_reg_n)
    macro_i_n = (step_fire & (~step_done)).select(i_next, macro_i_n)

    # FRET.STK requires a SETC.TGT immediately after restoring RA.
    step_ra_restore = step_fire & macro_is_fret_stk & macro_uop_is_load & macro_uop_reg.eq(c(10, width=6))
    macro_phase_n = (step_ra_restore & (~step_done)).select(ph_setc, macro_phase_n)

    done_macro = step_done & (macro_is_fentry | macro_is_fexit | macro_is_fret_stk | macro_is_fret_ra)
    macro_active_n = done_macro.select(consts.zero1, macro_active_n)
    macro_phase_n = done_macro.select(ph_init, macro_phase_n)

    # FRET.RA has an explicit SP_ADD phase before restore loads.
    sp_fire = macro_active & macro_phase_is_sp & macro_is_fret_ra
    macro_sp_base_n = sp_fire.select(sp_new_restore, macro_sp_base_n)
    macro_phase_n = sp_fire.select(ph_mem, macro_phase_n)

    # FRET.STK emits SETC.TGT as a standalone template uop between RA load
    # and the remaining restore-load loop.
    setc_fire = macro_active & macro_phase_is_setc & macro_is_fret_stk
    macro_phase_n = setc_fire.select(ph_mem, macro_phase_n)

    macro_wait_n = state.macro_wait_commit.out()
    macro_wait_n = do_flush.select(consts.zero1, macro_wait_n)
    macro_wait_n = macro_start.select(consts.one1, macro_wait_n)
    macro_committed = consts.zero1
    for slot in range(p.commit_w):
        op = rob_ops[slot]
        fire = commit_fires[slot]
        macro_committed = macro_committed | (fire & op_is(op, OP_FENTRY, OP_FEXIT, OP_FRET_RA, OP_FRET_STK))
    macro_wait_n = macro_committed.select(consts.zero1, macro_wait_n)

    # Suppress one synthetic C.BSTART boundary-dup right after a macro
    # commit handoff (macro commit advances to a new PC).
    macro_handoff = consts.zero1
    for slot in range(p.commit_w):
        op = rob_ops[slot]
        fire = commit_fires[slot]
        is_macro_evt = op_is(op, OP_FENTRY, OP_FEXIT, OP_FRET_RA, OP_FRET_STK)
        macro_handoff = macro_handoff | (fire & is_macro_evt & (~commit_next_pcs[slot].eq(commit_pcs[slot])))
    any_commit_fire = consts.zero1
    for slot in range(p.commit_w):
        any_commit_fire = any_commit_fire | commit_fires[slot]
    post_macro_handoff_n = state.post_macro_handoff.out()
    post_macro_handoff_n = do_flush.select(consts.zero1, post_macro_handoff_n)
    post_macro_handoff_n = macro_handoff.select(consts.one1, post_macro_handoff_n)
    post_macro_handoff_n = (any_commit_fire & (~macro_handoff)).select(consts.zero1, post_macro_handoff_n)

    state.macro_active.set(macro_active_n)
    state.macro_wait_commit.set(macro_wait_n)
    state.post_macro_handoff.set(post_macro_handoff_n)
    state.macro_phase.set(macro_phase_n)
    state.macro_op.set(macro_op_n)
    state.macro_begin.set(macro_begin_n)
    state.macro_end.set(macro_end_n)
    state.macro_stacksize.set(macro_stack_n)
    state.macro_reg.set(macro_reg_n)
    state.macro_i.set(macro_i_n)
    state.macro_sp_base.set(macro_sp_base_n)
    macro_saved_ra_n = state.macro_saved_ra.out()
    save_ra_fire = macro_store_fire & macro_uop_reg.eq(c(10, width=6))
    restore_ra_fire = macro_reg_write & macro_uop_reg.eq(c(10, width=6)) & macro_is_fret_stk
    macro_saved_ra_n = save_ra_fire.select(macro_store_data, macro_saved_ra_n)
    macro_saved_ra_n = restore_ra_fire.select(macro_load_data_eff, macro_saved_ra_n)
    state.macro_saved_ra.set(macro_saved_ra_n)

    # --- outputs ---
    a0_tag = ren.cmap[2].out()
    a1_tag = ren.cmap[3].out()
    ra_tag = ren.cmap[10].out()
    sp_tag = ren.cmap[1].out()

    m.output("halted", state.halted)
    m.output("cycles", state.cycles)
    m.output("pc", state.pc)
    m.output("fpc", state.fpc)
    m.output("a0", mux_by_uindex(m, idx=a0_tag, items=prf, default=consts.zero64))
    m.output("a1", mux_by_uindex(m, idx=a1_tag, items=prf, default=consts.zero64))
    m.output("ra", mux_by_uindex(m, idx=ra_tag, items=prf, default=consts.zero64))
    m.output("sp", mux_by_uindex(m, idx=sp_tag, items=prf, default=consts.zero64))
    m.output("commit_op", head_op)
    m.output("commit_fire", commit_fire)
    m.output("commit_value", head_value)
    m.output("commit_dst_kind", head_dst_kind)
    m.output("commit_dst_areg", head_dst_areg)
    m.output("commit_pdst", head_pdst)
    m.output("commit_cond", state.commit_cond)
    m.output("commit_tgt", state.commit_tgt)
    m.output("br_kind", state.br_kind)
    m.output("br_base_pc", state.br_base_pc)
    m.output("br_off", state.br_off)
    m.output("commit_store_fire", commit_store_fire)
    m.output("commit_store_addr", commit_store_addr)
    m.output("commit_store_data", commit_store_data)
    m.output("commit_store_size", commit_store_size)
    m.output("rob_head_valid", rob_valids[0])
    m.output("rob_head_done", rob_dones[0])
    m.output("rob_head_pc", mux_by_uindex(m, idx=rob.head.out(), items=rob.pc, default=consts.zero64))
    m.output("rob_head_insn_raw", head_insn_raw)
    m.output("rob_head_len", head_len)
    m.output("rob_head_op", head_op)

    # Commit slot visibility (bring-up tracing): per-slot PC/op/value/fire.
    #
    # Template blocks (FENTRY/FEXIT/FRET.*) are traced as restartable
    # per-step retire events to match QEMU commit semantics exactly.
    # The internal macro ROB commit is hidden from the trace stream.
    macro_trace_fire = macro_uop_valid
    macro_adj_nonzero = ~macro_frame_adj.eq(consts.zero64)
    macro_trace_pc = state.pc.out()
    macro_trace_seq_pc = macro_trace_pc + head_len.zext(width=64)
    macro_trace_op = macro_op
    macro_trace_val = head_value
    macro_trace_rob = rob.head.out()
    macro_trace_len = head_len
    macro_trace_insn = head_insn_raw

    macro_trace_wb_load = macro_reg_write
    macro_trace_wb_sp_sub = macro_uop_is_sp_sub & macro_adj_nonzero
    macro_trace_wb_sp_add = macro_uop_is_sp_add & macro_adj_nonzero
    macro_trace_wb_valid = macro_trace_fire & (macro_trace_wb_load | macro_trace_wb_sp_sub | macro_trace_wb_sp_add)
    macro_trace_wb_rd = c(0, width=6)
    macro_trace_wb_rd = macro_trace_wb_load.select(macro_uop_reg, macro_trace_wb_rd)
    macro_trace_wb_rd = (macro_trace_wb_sp_sub | macro_trace_wb_sp_add).select(c(1, width=6), macro_trace_wb_rd)
    macro_trace_wb_data = consts.zero64
    macro_trace_wb_data = macro_trace_wb_load.select(macro_load_data_eff, macro_trace_wb_data)
    macro_trace_wb_data = macro_trace_wb_sp_add.select(macro_sp_val + macro_frame_adj, macro_trace_wb_data)
    macro_trace_wb_data = macro_trace_wb_sp_sub.select(macro_sp_val - macro_frame_adj, macro_trace_wb_data)

    macro_trace_mem_store = macro_store_fire
    macro_trace_mem_load = macro_uop_is_load & macro_reg_is_gpr & macro_reg_not_zero
    macro_trace_mem_valid = macro_trace_fire & (macro_trace_mem_store | macro_trace_mem_load)
    macro_trace_mem_is_store = macro_trace_fire & macro_trace_mem_store
    macro_trace_mem_addr = macro_uop_addr
    macro_trace_mem_wdata = macro_trace_mem_store.select(macro_store_data, consts.zero64)
    macro_trace_mem_rdata = macro_trace_mem_load.select(macro_load_data_eff, consts.zero64)
    macro_trace_mem_size = macro_trace_mem_valid.select(c(8, width=4), consts.zero4)
    macro_trace_src0_valid = macro_trace_fire & (macro_uop_is_sp_sub | macro_uop_is_sp_add | macro_store_fire | macro_uop_is_load)
    macro_trace_src0_reg = c(1, width=6)
    macro_trace_src0_reg = macro_store_fire.select(macro_uop_reg, macro_trace_src0_reg)
    macro_trace_src0_data = macro_sp_val
    macro_trace_src0_data = macro_store_fire.select(macro_store_data, macro_trace_src0_data)
    macro_trace_src1_valid = consts.zero1
    macro_trace_src1_reg = c(0, width=6)
    macro_trace_src1_data = consts.zero64
    macro_trace_dst_valid = macro_trace_wb_valid
    macro_trace_dst_reg = macro_trace_wb_rd
    macro_trace_dst_data = macro_trace_wb_data

    macro_trace_is_fentry = macro_op.eq(c(OP_FENTRY, width=12))
    macro_trace_is_fexit = macro_op.eq(c(OP_FEXIT, width=12))
    macro_trace_is_fret = op_is(macro_op, OP_FRET_RA, OP_FRET_STK)
    macro_trace_done_fentry = (macro_uop_is_sp_sub & macro_stacksize.eq(consts.zero64)) | (macro_uop_is_store & step_done)
    macro_trace_done_fexit = macro_uop_is_load & step_done & macro_trace_is_fexit
    macro_trace_done_fret = macro_uop_is_load & step_done & macro_trace_is_fret
    macro_trace_next_pc = macro_trace_pc
    macro_trace_next_pc = macro_trace_done_fentry.select(macro_trace_seq_pc, macro_trace_next_pc)
    macro_trace_next_pc = macro_trace_done_fexit.select(macro_trace_seq_pc, macro_trace_next_pc)
    macro_trace_next_pc = macro_trace_done_fret.select(commit_tgt_live, macro_trace_next_pc)
    # QEMU commit-trace emits a side-effect-free template marker before the
    # first architecturally visible FRET template micro-op.
    macro_shadow_fire = macro_trace_fire & (
        (macro_is_fret_stk & macro_uop_is_sp_add) |
        (macro_is_fret_ra & macro_uop_is_setc_tgt)
    )
    # Keep dynamic uop IDs unique even when we emit both a shadow marker and
    # the architecturally visible template uop in the same cycle.
    macro_shadow_uid = macro_uop_uid | c(1 << 62, width=64)
    macro_shadow_uid_alt = macro_shadow_uid | c(1, width=64)

    # Keep retire trace strictly instruction-driven; qemu-specific boundary-only
    # metadata commits are filtered in the lockstep runner.
    shadow_boundary_fire = consts.zero1
    shadow_boundary_fire1 = consts.zero1

    # `commit_fire` / `commit_op` / `commit_value` remain lane0-compatible.
    # These additional signals help debug multi-commit cycles where older
    # commits may not appear in a slot0-only log.
    max_commit_slots = 4
    for slot in range(max_commit_slots):
        fire = consts.zero1
        pc = consts.zero64
        rob_idx = c(0, width=p.rob_w)
        uop_uid = consts.zero64
        parent_uid = consts.zero64
        template_kind = c(0, width=3)
        op = c(0, width=12)
        val = consts.zero64
        ln = consts.zero3
        insn_raw = consts.zero64
        wb_valid = consts.zero1
        wb_rd = c(0, width=6)
        wb_data = consts.zero64
        src0_valid = consts.zero1
        src0_reg = c(0, width=6)
        src0_data = consts.zero64
        src1_valid = consts.zero1
        src1_reg = c(0, width=6)
        src1_data = consts.zero64
        dst_valid = consts.zero1
        dst_reg = c(0, width=6)
        dst_data = consts.zero64
        mem_valid = consts.zero1
        mem_is_store = consts.zero1
        mem_addr = consts.zero64
        mem_wdata = consts.zero64
        mem_rdata = consts.zero64
        mem_size = consts.zero4
        trap_valid = consts.zero1
        trap_cause = c(0, width=32)
        next_pc = consts.zero64
        checkpoint_id = c(0, width=6)
        if slot < p.commit_w:
            fire_raw = commit_fires[slot]
            pc = rob_pcs[slot]
            rob_idx = commit_idxs[slot]
            op = rob_ops[slot]
            val = rob_values[slot]
            ln = rob_lens[slot]
            insn_raw = rob_insn_raws[slot]
            uop_uid = rob_uop_uids[slot]
            parent_uid = rob_parent_uids[slot]
            is_macro_commit = op_is(op, OP_FENTRY, OP_FEXIT, OP_FRET_RA, OP_FRET_STK)
            fire = fire_raw & (~is_macro_commit)
            is_gpr_dst = rob_dst_kinds[slot].eq(c(1, width=2))
            wb_trace_suppress = op_is(
                op,
                OP_C_BSTART_STD,
                OP_C_BSTART_COND,
                OP_C_BSTART_DIRECT,
                OP_BSTART_STD_FALL,
                OP_BSTART_STD_DIRECT,
                OP_BSTART_STD_COND,
                OP_BSTART_STD_CALL,
                OP_C_BSTOP,
            )
            rd = rob_dst_aregs[slot]
            wb_valid = fire & is_gpr_dst & (~rd.eq(c(0, width=6))) & (~wb_trace_suppress)
            wb_rd = rd
            wb_data = rob_values[slot]
            src0_valid = fire & rob_src0_valids[slot]
            src0_reg = rob_src0_regs[slot]
            src0_data = rob_src0_values[slot]
            src1_valid = fire & rob_src1_valids[slot]
            src1_reg = rob_src1_regs[slot]
            src1_data = rob_src1_values[slot]
            dst_valid = wb_valid
            dst_reg = wb_rd
            dst_data = wb_data
            next_pc_slot = commit_next_pcs[slot]
            is_store = rob_is_stores[slot]
            is_load = rob_is_loads[slot]
            ld_trace_data = rob_ld_datas[slot]
            for i in range(p.sq_entries):
                st_hit = stbuf_valid[i].out() & stbuf_addr[i].out().eq(rob_ld_addrs[slot])
                ld_trace_data = st_hit.select(stbuf_data[i].out(), ld_trace_data)
            mem_valid = fire & (is_store | is_load)
            mem_is_store = fire & is_store
            mem_addr = is_store.select(rob_st_addrs[slot], rob_ld_addrs[slot])
            mem_wdata = is_store.select(rob_st_datas[slot], consts.zero64)
            mem_rdata = is_load.select(ld_trace_data, consts.zero64)
            mem_size = is_store.select(rob_st_sizes[slot], rob_ld_sizes[slot])
            next_pc = next_pc_slot
            checkpoint_id = rob_checkpoint_ids[slot]
            trap_hit = state.trap_pending.out() & commit_idxs[slot].eq(state.trap_rob.out())
            trap_valid = fire & trap_hit
            trap_cause = trap_hit.select(state.trap_cause.out(), trap_cause)

        # When `shadow_boundary_fire` is active, shift real retire records up
        # by one slot so slot0 can carry the synthetic boundary marker event.
        if slot > 0 and (slot - 1) < p.commit_w:
            prev = slot - 1
            fire_prev_raw = commit_fires[prev]
            pc_prev = commit_pcs[prev]
            rob_prev = commit_idxs[prev]
            op_prev = rob_ops[prev]
            val_prev = rob_values[prev]
            ln_prev = rob_lens[prev]
            insn_prev = rob_insn_raws[prev]
            uid_prev = rob_uop_uids[prev]
            parent_prev = rob_parent_uids[prev]
            is_macro_prev = op_is(op_prev, OP_FENTRY, OP_FEXIT, OP_FRET_RA, OP_FRET_STK)
            fire_prev = fire_prev_raw & (~is_macro_prev)
            is_gpr_prev = rob_dst_kinds[prev].eq(c(1, width=2))
            wb_suppress_prev = op_is(
                op_prev,
                OP_C_BSTART_STD,
                OP_C_BSTART_COND,
                OP_C_BSTART_DIRECT,
                OP_BSTART_STD_FALL,
                OP_BSTART_STD_DIRECT,
                OP_BSTART_STD_COND,
                OP_BSTART_STD_CALL,
                OP_C_BSTOP,
            )
            rd_prev = rob_dst_aregs[prev]
            wb_valid_prev = fire_prev & is_gpr_prev & (~rd_prev.eq(c(0, width=6))) & (~wb_suppress_prev)
            wb_rd_prev = rd_prev
            wb_data_prev = rob_values[prev]
            src0_valid_prev = fire_prev & rob_src0_valids[prev]
            src0_reg_prev = rob_src0_regs[prev]
            src0_data_prev = rob_src0_values[prev]
            src1_valid_prev = fire_prev & rob_src1_valids[prev]
            src1_reg_prev = rob_src1_regs[prev]
            src1_data_prev = rob_src1_values[prev]
            dst_valid_prev = wb_valid_prev
            dst_reg_prev = wb_rd_prev
            dst_data_prev = wb_data_prev
            next_pc_prev = commit_next_pcs[prev]
            is_store_prev = rob_is_stores[prev]
            is_load_prev = rob_is_loads[prev]
            ld_trace_prev = rob_ld_datas[prev]
            for i in range(p.sq_entries):
                st_hit_prev = stbuf_valid[i].out() & stbuf_addr[i].out().eq(rob_ld_addrs[prev])
                ld_trace_prev = st_hit_prev.select(stbuf_data[i].out(), ld_trace_prev)
            mem_valid_prev = fire_prev & (is_store_prev | is_load_prev)
            mem_is_store_prev = fire_prev & is_store_prev
            mem_addr_prev = is_store_prev.select(rob_st_addrs[prev], rob_ld_addrs[prev])
            mem_wdata_prev = is_store_prev.select(rob_st_datas[prev], consts.zero64)
            mem_rdata_prev = is_load_prev.select(ld_trace_prev, consts.zero64)
            mem_size_prev = is_store_prev.select(rob_st_sizes[prev], rob_ld_sizes[prev])
            checkpoint_prev = rob_checkpoint_ids[prev]
            shift_active = shadow_boundary_fire
            if slot > 1:
                shift_active = shift_active | shadow_boundary_fire1

            fire = shift_active.select(fire_prev, fire)
            pc = shift_active.select(pc_prev, pc)
            rob_idx = shift_active.select(rob_prev, rob_idx)
            op = shift_active.select(op_prev, op)
            val = shift_active.select(val_prev, val)
            ln = shift_active.select(ln_prev, ln)
            insn_raw = shift_active.select(insn_prev, insn_raw)
            uop_uid = shift_active.select(uid_prev, uop_uid)
            parent_uid = shift_active.select(parent_prev, parent_uid)
            wb_valid = shift_active.select(wb_valid_prev, wb_valid)
            wb_rd = shift_active.select(wb_rd_prev, wb_rd)
            wb_data = shift_active.select(wb_data_prev, wb_data)
            src0_valid = shift_active.select(src0_valid_prev, src0_valid)
            src0_reg = shift_active.select(src0_reg_prev, src0_reg)
            src0_data = shift_active.select(src0_data_prev, src0_data)
            src1_valid = shift_active.select(src1_valid_prev, src1_valid)
            src1_reg = shift_active.select(src1_reg_prev, src1_reg)
            src1_data = shift_active.select(src1_data_prev, src1_data)
            dst_valid = shift_active.select(dst_valid_prev, dst_valid)
            dst_reg = shift_active.select(dst_reg_prev, dst_reg)
            dst_data = shift_active.select(dst_data_prev, dst_data)
            mem_valid = shift_active.select(mem_valid_prev, mem_valid)
            mem_is_store = shift_active.select(mem_is_store_prev, mem_is_store)
            mem_addr = shift_active.select(mem_addr_prev, mem_addr)
            mem_wdata = shift_active.select(mem_wdata_prev, mem_wdata)
            mem_rdata = shift_active.select(mem_rdata_prev, mem_rdata)
            mem_size = shift_active.select(mem_size_prev, mem_size)
            next_pc = shift_active.select(next_pc_prev, next_pc)
            checkpoint_id = shift_active.select(checkpoint_prev, checkpoint_id)

        if slot == 0:
            fire = shadow_boundary_fire.select(consts.one1, fire)
            pc = shadow_boundary_fire.select(commit_pcs[0], pc)
            rob_idx = shadow_boundary_fire.select(commit_idxs[0], rob_idx)
            op = shadow_boundary_fire.select(rob_ops[0], op)
            val = shadow_boundary_fire.select(rob_values[0], val)
            ln = shadow_boundary_fire.select(rob_lens[0], ln)
            insn_raw = shadow_boundary_fire.select(rob_insn_raws[0], insn_raw)
            uop_uid = shadow_boundary_fire.select(rob_uop_uids[0], uop_uid)
            parent_uid = shadow_boundary_fire.select(rob_parent_uids[0], parent_uid)
            wb_valid = shadow_boundary_fire.select(consts.zero1, wb_valid)
            wb_rd = shadow_boundary_fire.select(c(0, width=6), wb_rd)
            wb_data = shadow_boundary_fire.select(consts.zero64, wb_data)
            src0_valid = shadow_boundary_fire.select(consts.zero1, src0_valid)
            src0_reg = shadow_boundary_fire.select(c(0, width=6), src0_reg)
            src0_data = shadow_boundary_fire.select(consts.zero64, src0_data)
            src1_valid = shadow_boundary_fire.select(consts.zero1, src1_valid)
            src1_reg = shadow_boundary_fire.select(c(0, width=6), src1_reg)
            src1_data = shadow_boundary_fire.select(consts.zero64, src1_data)
            dst_valid = shadow_boundary_fire.select(consts.zero1, dst_valid)
            dst_reg = shadow_boundary_fire.select(c(0, width=6), dst_reg)
            dst_data = shadow_boundary_fire.select(consts.zero64, dst_data)
            mem_valid = shadow_boundary_fire.select(consts.zero1, mem_valid)
            mem_is_store = shadow_boundary_fire.select(consts.zero1, mem_is_store)
            mem_addr = shadow_boundary_fire.select(consts.zero64, mem_addr)
            mem_wdata = shadow_boundary_fire.select(consts.zero64, mem_wdata)
            mem_rdata = shadow_boundary_fire.select(consts.zero64, mem_rdata)
            mem_size = shadow_boundary_fire.select(consts.zero4, mem_size)
            next_pc = shadow_boundary_fire.select(commit_pcs[0], next_pc)
            checkpoint_id = shadow_boundary_fire.select(rob_checkpoint_ids[0], checkpoint_id)

            fire = macro_trace_fire.select(consts.one1, fire)
            pc = macro_trace_fire.select(macro_trace_pc, pc)
            rob_idx = macro_trace_fire.select(macro_trace_rob, rob_idx)
            op = macro_trace_fire.select(macro_trace_op, op)
            val = macro_trace_fire.select(macro_trace_val, val)
            ln = macro_trace_fire.select(macro_trace_len, ln)
            insn_raw = macro_trace_fire.select(macro_trace_insn, insn_raw)
            uop_uid = macro_trace_fire.select(macro_uop_uid, uop_uid)
            parent_uid = macro_trace_fire.select(macro_uop_parent_uid, parent_uid)
            template_kind = macro_trace_fire.select(macro_uop_template_kind, template_kind)
            wb_valid = macro_trace_fire.select(macro_trace_wb_valid, wb_valid)
            wb_rd = macro_trace_fire.select(macro_trace_wb_rd, wb_rd)
            wb_data = macro_trace_fire.select(macro_trace_wb_data, wb_data)
            src0_valid = macro_trace_fire.select(macro_trace_src0_valid, src0_valid)
            src0_reg = macro_trace_fire.select(macro_trace_src0_reg, src0_reg)
            src0_data = macro_trace_fire.select(macro_trace_src0_data, src0_data)
            src1_valid = macro_trace_fire.select(macro_trace_src1_valid, src1_valid)
            src1_reg = macro_trace_fire.select(macro_trace_src1_reg, src1_reg)
            src1_data = macro_trace_fire.select(macro_trace_src1_data, src1_data)
            dst_valid = macro_trace_fire.select(macro_trace_dst_valid, dst_valid)
            dst_reg = macro_trace_fire.select(macro_trace_dst_reg, dst_reg)
            dst_data = macro_trace_fire.select(macro_trace_dst_data, dst_data)
            mem_valid = macro_trace_fire.select(macro_trace_mem_valid, mem_valid)
            mem_is_store = macro_trace_fire.select(macro_trace_mem_is_store, mem_is_store)
            mem_addr = macro_trace_fire.select(macro_trace_mem_addr, mem_addr)
            mem_wdata = macro_trace_fire.select(macro_trace_mem_wdata, mem_wdata)
            mem_rdata = macro_trace_fire.select(macro_trace_mem_rdata, mem_rdata)
            mem_size = macro_trace_fire.select(macro_trace_mem_size, mem_size)
            next_pc = macro_trace_fire.select(macro_trace_next_pc, next_pc)
            uop_uid = macro_shadow_fire.select(macro_shadow_uid, uop_uid)
            wb_valid = macro_shadow_fire.select(consts.zero1, wb_valid)
            wb_rd = macro_shadow_fire.select(c(0, width=6), wb_rd)
            wb_data = macro_shadow_fire.select(consts.zero64, wb_data)
            src0_valid = macro_shadow_fire.select(consts.zero1, src0_valid)
            src0_reg = macro_shadow_fire.select(c(0, width=6), src0_reg)
            src0_data = macro_shadow_fire.select(consts.zero64, src0_data)
            src1_valid = macro_shadow_fire.select(consts.zero1, src1_valid)
            src1_reg = macro_shadow_fire.select(c(0, width=6), src1_reg)
            src1_data = macro_shadow_fire.select(consts.zero64, src1_data)
            dst_valid = macro_shadow_fire.select(consts.zero1, dst_valid)
            dst_reg = macro_shadow_fire.select(c(0, width=6), dst_reg)
            dst_data = macro_shadow_fire.select(consts.zero64, dst_data)
            mem_valid = macro_shadow_fire.select(consts.zero1, mem_valid)
            mem_is_store = macro_shadow_fire.select(consts.zero1, mem_is_store)
            mem_addr = macro_shadow_fire.select(consts.zero64, mem_addr)
            mem_wdata = macro_shadow_fire.select(consts.zero64, mem_wdata)
            mem_rdata = macro_shadow_fire.select(consts.zero64, mem_rdata)
            mem_size = macro_shadow_fire.select(consts.zero4, mem_size)
            next_pc = macro_shadow_fire.select(macro_trace_pc, next_pc)
        else:
            if slot == 1 and p.commit_w > 1:
                fire = shadow_boundary_fire1.select(consts.one1, fire)
                pc = shadow_boundary_fire1.select(commit_pcs[1], pc)
                rob_idx = shadow_boundary_fire1.select(commit_idxs[1], rob_idx)
                op = shadow_boundary_fire1.select(rob_ops[1], op)
                val = shadow_boundary_fire1.select(rob_values[1], val)
                ln = shadow_boundary_fire1.select(rob_lens[1], ln)
                insn_raw = shadow_boundary_fire1.select(rob_insn_raws[1], insn_raw)
                uop_uid = shadow_boundary_fire1.select(rob_uop_uids[1], uop_uid)
                parent_uid = shadow_boundary_fire1.select(rob_parent_uids[1], parent_uid)
                wb_valid = shadow_boundary_fire1.select(consts.zero1, wb_valid)
                wb_rd = shadow_boundary_fire1.select(c(0, width=6), wb_rd)
                wb_data = shadow_boundary_fire1.select(consts.zero64, wb_data)
                src0_valid = shadow_boundary_fire1.select(consts.zero1, src0_valid)
                src0_reg = shadow_boundary_fire1.select(c(0, width=6), src0_reg)
                src0_data = shadow_boundary_fire1.select(consts.zero64, src0_data)
                src1_valid = shadow_boundary_fire1.select(consts.zero1, src1_valid)
                src1_reg = shadow_boundary_fire1.select(c(0, width=6), src1_reg)
                src1_data = shadow_boundary_fire1.select(consts.zero64, src1_data)
                dst_valid = shadow_boundary_fire1.select(consts.zero1, dst_valid)
                dst_reg = shadow_boundary_fire1.select(c(0, width=6), dst_reg)
                dst_data = shadow_boundary_fire1.select(consts.zero64, dst_data)
                mem_valid = shadow_boundary_fire1.select(consts.zero1, mem_valid)
                mem_is_store = shadow_boundary_fire1.select(consts.zero1, mem_is_store)
                mem_addr = shadow_boundary_fire1.select(consts.zero64, mem_addr)
                mem_wdata = shadow_boundary_fire1.select(consts.zero64, mem_wdata)
                mem_rdata = shadow_boundary_fire1.select(consts.zero64, mem_rdata)
                mem_size = shadow_boundary_fire1.select(consts.zero4, mem_size)
                next_pc = shadow_boundary_fire1.select(commit_pcs[1], next_pc)
                checkpoint_id = shadow_boundary_fire1.select(rob_checkpoint_ids[1], checkpoint_id)
            if slot == 1:
                fire = macro_shadow_fire.select(consts.one1, fire)
                pc = macro_shadow_fire.select(macro_trace_pc, pc)
                rob_idx = macro_shadow_fire.select(macro_trace_rob, rob_idx)
                op = macro_shadow_fire.select(macro_trace_op, op)
                val = macro_shadow_fire.select(macro_trace_val, val)
                ln = macro_shadow_fire.select(macro_trace_len, ln)
                insn_raw = macro_shadow_fire.select(macro_trace_insn, insn_raw)
                uop_uid = macro_shadow_fire.select(macro_shadow_uid_alt, uop_uid)
                parent_uid = macro_shadow_fire.select(macro_uop_parent_uid, parent_uid)
                template_kind = macro_shadow_fire.select(macro_uop_template_kind, template_kind)
                wb_valid = macro_shadow_fire.select(macro_trace_wb_valid, wb_valid)
                wb_rd = macro_shadow_fire.select(macro_trace_wb_rd, wb_rd)
                wb_data = macro_shadow_fire.select(macro_trace_wb_data, wb_data)
                src0_valid = macro_shadow_fire.select(macro_trace_src0_valid, src0_valid)
                src0_reg = macro_shadow_fire.select(macro_trace_src0_reg, src0_reg)
                src0_data = macro_shadow_fire.select(macro_trace_src0_data, src0_data)
                src1_valid = macro_shadow_fire.select(macro_trace_src1_valid, src1_valid)
                src1_reg = macro_shadow_fire.select(macro_trace_src1_reg, src1_reg)
                src1_data = macro_shadow_fire.select(macro_trace_src1_data, src1_data)
                dst_valid = macro_shadow_fire.select(macro_trace_dst_valid, dst_valid)
                dst_reg = macro_shadow_fire.select(macro_trace_dst_reg, dst_reg)
                dst_data = macro_shadow_fire.select(macro_trace_dst_data, dst_data)
                mem_valid = macro_shadow_fire.select(macro_trace_mem_valid, mem_valid)
                mem_is_store = macro_shadow_fire.select(macro_trace_mem_is_store, mem_is_store)
                mem_addr = macro_shadow_fire.select(macro_trace_mem_addr, mem_addr)
                mem_wdata = macro_shadow_fire.select(macro_trace_mem_wdata, mem_wdata)
                mem_rdata = macro_shadow_fire.select(macro_trace_mem_rdata, mem_rdata)
                mem_size = macro_shadow_fire.select(macro_trace_mem_size, mem_size)
                next_pc = macro_shadow_fire.select(macro_trace_next_pc, next_pc)
            macro_slot_keep = consts.zero1
            if slot == 1:
                macro_slot_keep = macro_shadow_fire
            macro_kill = macro_trace_fire & (~macro_slot_keep)
            fire = macro_kill.select(consts.zero1, fire)
            pc = macro_kill.select(consts.zero64, pc)
            rob_idx = macro_kill.select(c(0, width=p.rob_w), rob_idx)
            op = macro_kill.select(c(0, width=12), op)
            val = macro_kill.select(consts.zero64, val)
            ln = macro_kill.select(consts.zero3, ln)
            insn_raw = macro_kill.select(consts.zero64, insn_raw)
            uop_uid = macro_kill.select(consts.zero64, uop_uid)
            parent_uid = macro_kill.select(consts.zero64, parent_uid)
            template_kind = macro_kill.select(c(0, width=3), template_kind)
            wb_valid = macro_kill.select(consts.zero1, wb_valid)
            wb_rd = macro_kill.select(c(0, width=6), wb_rd)
            wb_data = macro_kill.select(consts.zero64, wb_data)
            src0_valid = macro_kill.select(consts.zero1, src0_valid)
            src0_reg = macro_kill.select(c(0, width=6), src0_reg)
            src0_data = macro_kill.select(consts.zero64, src0_data)
            src1_valid = macro_kill.select(consts.zero1, src1_valid)
            src1_reg = macro_kill.select(c(0, width=6), src1_reg)
            src1_data = macro_kill.select(consts.zero64, src1_data)
            dst_valid = macro_kill.select(consts.zero1, dst_valid)
            dst_reg = macro_kill.select(c(0, width=6), dst_reg)
            dst_data = macro_kill.select(consts.zero64, dst_data)
            mem_valid = macro_kill.select(consts.zero1, mem_valid)
            mem_is_store = macro_kill.select(consts.zero1, mem_is_store)
            mem_addr = macro_kill.select(consts.zero64, mem_addr)
            mem_wdata = macro_kill.select(consts.zero64, mem_wdata)
            mem_rdata = macro_kill.select(consts.zero64, mem_rdata)
            mem_size = macro_kill.select(consts.zero4, mem_size)
            next_pc = macro_kill.select(consts.zero64, next_pc)
            checkpoint_id = macro_kill.select(c(0, width=6), checkpoint_id)
        m.output(f"commit_fire{slot}", fire)
        m.output(f"commit_pc{slot}", pc)
        m.output(f"commit_rob{slot}", rob_idx)
        m.output(f"commit_op{slot}", op)
        m.output(f"commit_uop_uid{slot}", uop_uid)
        m.output(f"commit_parent_uop_uid{slot}", parent_uid)
        m.output(f"commit_block_uid{slot}", commit_block_uids[slot])
        m.output(f"commit_block_bid{slot}", commit_block_bids[slot])
        m.output(f"commit_core_id{slot}", commit_core_ids[slot])
        m.output(f"commit_is_bstart{slot}", commit_is_bstarts[slot])
        m.output(f"commit_is_bstop{slot}", commit_is_bstops[slot])
        m.output(f"commit_load_store_id{slot}", rob_load_store_ids[slot])
        m.output(f"commit_template_kind{slot}", template_kind)
        m.output(f"commit_value{slot}", val)
        m.output(f"commit_len{slot}", ln)
        m.output(f"commit_insn_raw{slot}", insn_raw)
        m.output(f"commit_wb_valid{slot}", wb_valid)
        m.output(f"commit_wb_rd{slot}", wb_rd)
        m.output(f"commit_wb_data{slot}", wb_data)
        m.output(f"commit_src0_valid{slot}", src0_valid)
        m.output(f"commit_src0_reg{slot}", src0_reg)
        m.output(f"commit_src0_data{slot}", src0_data)
        m.output(f"commit_src1_valid{slot}", src1_valid)
        m.output(f"commit_src1_reg{slot}", src1_reg)
        m.output(f"commit_src1_data{slot}", src1_data)
        m.output(f"commit_dst_valid{slot}", dst_valid)
        m.output(f"commit_dst_reg{slot}", dst_reg)
        m.output(f"commit_dst_data{slot}", dst_data)
        m.output(f"commit_mem_valid{slot}", mem_valid)
        m.output(f"commit_mem_is_store{slot}", mem_is_store)
        m.output(f"commit_mem_addr{slot}", mem_addr)
        m.output(f"commit_mem_wdata{slot}", mem_wdata)
        m.output(f"commit_mem_rdata{slot}", mem_rdata)
        m.output(f"commit_mem_size{slot}", mem_size)
        m.output(f"commit_trap_valid{slot}", trap_valid)
        m.output(f"commit_trap_cause{slot}", trap_cause)
        m.output(f"commit_next_pc{slot}", next_pc)
        m.output(f"commit_checkpoint_id{slot}", checkpoint_id)
    m.output("rob_count", rob.count)

    # Debug: committed vs speculative hand tops (T0/U0).
    ct0_tag = ren.cmap[24].out()
    cu0_tag = ren.cmap[28].out()
    st0_tag = ren.smap[24].out()
    su0_tag = ren.smap[28].out()
    m.output("ct0", mux_by_uindex(m, idx=ct0_tag, items=prf, default=consts.zero64))
    m.output("cu0", mux_by_uindex(m, idx=cu0_tag, items=prf, default=consts.zero64))
    m.output("st0", mux_by_uindex(m, idx=st0_tag, items=prf, default=consts.zero64))
    m.output("su0", mux_by_uindex(m, idx=su0_tag, items=prf, default=consts.zero64))

    # Debug: issue/memory arbitration visibility.
    m.output("issue_fire", issue_fire)
    m.output("issue_op", uop_op)
    m.output("issue_pc", uop_pc)
    m.output("issue_rob", uop_rob)
    m.output("issue_sl", uop_sl)
    m.output("issue_sr", uop_sr)
    m.output("issue_sp", uop_sp)
    m.output("issue_pdst", uop_pdst)
    m.output("issue_sl_val", sl_val)
    m.output("issue_sr_val", sr_val)
    m.output("issue_sp_val", sp_val)
    m.output("issue_is_load", issued_is_load)
    m.output("issue_is_store", issued_is_store)
    m.output("store_pending", store_pending)
    m.output("store_pending_older", older_store_pending)
    m.output("mem_raddr", dmem_raddr)
    m.output("dmem_raddr", dmem_raddr)
    m.output("dmem_wvalid", mem_wvalid)
    m.output("dmem_waddr", mem_waddr)
    m.output("dmem_wdata", dmem_wdata)
    m.output("dmem_wstrb", wstrb)
    m.output("dmem_wsrc", dmem_wsrc)
    m.output("stbuf_enq_fire", stbuf_enq_fire)
    m.output("stbuf_drain_fire", stbuf_drain_fire)
    m.output("macro_store_fire_dbg", macro_store_fire)
    m.output("commit_store_wt_fire_dbg", commit_store_write_through)
    m.output("frontend_ready", frontend_ready)
    m.output("redirect_valid", commit_redirect_any)
    m.output("redirect_pc", redirect_pc_any)
    m.output("redirect_checkpoint_id", redirect_checkpoint_id_any)
    m.output("ctu_block_ifu", macro_block)
    m.output("ctu_uop_valid", macro_uop_valid)
    m.output("ctu_uop_kind", macro_uop_kind)
    m.output("ctu_uop_reg", macro_uop_reg)
    m.output("ctu_uop_addr", macro_uop_addr)
    m.output("ctu_uop_uid", macro_uop_uid)
    m.output("ctu_uop_parent_uid", macro_uop_parent_uid)
    m.output("ctu_uop_template_kind", macro_uop_template_kind)
    m.output("bru_validate_fire_dbg", bru_validate_fire)
    m.output("bru_mismatch_dbg", bru_mismatch_evt)
    m.output("bru_actual_take_dbg", bru_actual_take_dbg)
    m.output("bru_pred_take_dbg", bru_pred_take_dbg)
    m.output("bru_boundary_pc_dbg", bru_boundary_pc_dbg)
    m.output("bru_corr_set_dbg", bru_corr_set)
    m.output("bru_corr_pending_dbg", state.br_corr_pending.out())
    m.output("bru_corr_target_dbg", state.br_corr_target.out())
    m.output("bru_corr_epoch_dbg", state.br_corr_epoch.out())
    m.output("br_epoch_dbg", state.br_epoch.out())
    m.output("br_kind_dbg", state.br_kind.out())
    m.output("redirect_from_corr_dbg", redirect_from_corr)
    m.output("redirect_from_boundary_dbg", commit_redirect_any & (~redirect_from_corr))
    m.output("bru_fault_set_dbg", bru_fault_set)

    # Deadlock diagnostics: locate the IQ entry currently backing ROB head.
    head_wait_args = {"ready_mask": ren.ready_mask.out(), "head_idx": rob.head.out()}
    for i in range(p.iq_depth):
        head_wait_args[f"lsu_valid{i}"] = iq_lsu.valid[i].out()
        head_wait_args[f"lsu_rob{i}"] = iq_lsu.rob[i].out()
        head_wait_args[f"lsu_srcl{i}"] = iq_lsu.srcl[i].out()
        head_wait_args[f"lsu_srcr{i}"] = iq_lsu.srcr[i].out()
        head_wait_args[f"lsu_srcp{i}"] = iq_lsu.srcp[i].out()
        head_wait_args[f"bru_valid{i}"] = iq_bru.valid[i].out()
        head_wait_args[f"bru_rob{i}"] = iq_bru.rob[i].out()
        head_wait_args[f"bru_srcl{i}"] = iq_bru.srcl[i].out()
        head_wait_args[f"bru_srcr{i}"] = iq_bru.srcr[i].out()
        head_wait_args[f"bru_srcp{i}"] = iq_bru.srcp[i].out()
        head_wait_args[f"alu_valid{i}"] = iq_alu.valid[i].out()
        head_wait_args[f"alu_rob{i}"] = iq_alu.rob[i].out()
        head_wait_args[f"alu_srcl{i}"] = iq_alu.srcl[i].out()
        head_wait_args[f"alu_srcr{i}"] = iq_alu.srcr[i].out()
        head_wait_args[f"alu_srcp{i}"] = iq_alu.srcp[i].out()
        head_wait_args[f"cmd_valid{i}"] = iq_cmd.valid[i].out()
        head_wait_args[f"cmd_rob{i}"] = iq_cmd.rob[i].out()
        head_wait_args[f"cmd_srcl{i}"] = iq_cmd.srcl[i].out()
        head_wait_args[f"cmd_srcr{i}"] = iq_cmd.srcr[i].out()
        head_wait_args[f"cmd_srcp{i}"] = iq_cmd.srcp[i].out()
    head_wait = m.instance(
        build_head_wait_stage,
        name="head_wait_stage",
        params={"iq_depth": p.iq_depth, "iq_w": p.iq_w, "rob_w": p.rob_w, "ptag_w": p.ptag_w, "pregs": p.pregs},
        **head_wait_args,
    )
    m.output("head_wait_hit", head_wait["head_wait_hit"])
    m.output("head_wait_kind", head_wait["head_wait_kind"])
    m.output("head_wait_sl", head_wait["head_wait_sl"])
    m.output("head_wait_sr", head_wait["head_wait_sr"])
    m.output("head_wait_sp", head_wait["head_wait_sp"])
    m.output("head_wait_sl_rdy", head_wait["head_wait_sl_rdy"])
    m.output("head_wait_sr_rdy", head_wait["head_wait_sr_rdy"])
    m.output("head_wait_sp_rdy", head_wait["head_wait_sp_rdy"])
    # Debug taps for scheduler/LSU replay bring-up.
    wakeup_reason = compose_wakeup_reason(
        m=m,
        p=p,
        wb_fire_has_dsts=wb_fire_has_dsts,
        lsu_forward_active=lsu_forward_active,
        commit_redirect=commit_redirect_any,
        dispatch_fire=dispatch_fire,
    )
    replay_cause = compose_replay_cause(
        m=m,
        lsu_block_lane0=lsu_block_lane0,
        issued_is_load=issued_is_load,
        older_store_pending=older_store_pending,
        lsu_violation_detected=lsu_violation_detected,
        replay_redirect_fire=replay_redirect_fire,
    )
    m.output("wakeup_reason", wakeup_reason)
    m.output("replay_cause", replay_cause)
    m.output("dispatch_fire", dispatch_fire)
    m.output("dec_op", dec_op)

    # Dispatch slot visibility (trace hook): per-slot PC/op/ROB for pipeview tools.
    max_disp_slots = 4
    for slot in range(max_disp_slots):
        fire = consts.zero1
        pc = consts.zero64
        rob_i = c(0, width=p.rob_w)
        op = c(0, width=12)
        uid = consts.zero64
        parent_uid = consts.zero64
        block_uid = consts.zero64
        block_bid = consts.zero64
        load_store_id = c(0, width=32)
        if slot < p.dispatch_w:
            fire = disp_fires[slot]
            pc = disp_pcs[slot]
            rob_i = disp_rob_idxs[slot]
            op = disp_ops[slot]
            uid = disp_uop_uids[slot]
            parent_uid = disp_parent_uids[slot]
            block_uid = disp_block_uids[slot]
            block_bid = disp_block_bids[slot]
            load_store_id = disp_load_store_ids[slot]
        m.output(f"dispatch_fire{slot}", fire)
        m.output(f"dispatch_pc{slot}", pc)
        m.output(f"dispatch_rob{slot}", rob_i)
        m.output(f"dispatch_op{slot}", op)
        m.output(f"dispatch_uop_uid{slot}", uid)
        m.output(f"dispatch_parent_uop_uid{slot}", parent_uid)
        m.output(f"dispatch_block_uid{slot}", block_uid)
        m.output(f"dispatch_block_bid{slot}", block_bid)
        m.output(f"dispatch_load_store_id{slot}", load_store_id)

    # Issue slot visibility (trace hook): per-slot PC/op/ROB for pipeview tools.
    max_issue_slots = 4
    for slot in range(max_issue_slots):
        fire = consts.zero1
        pc = consts.zero64
        rob_i = c(0, width=p.rob_w)
        op = c(0, width=12)
        uid = consts.zero64
        parent_uid = consts.zero64
        block_uid = consts.zero64
        block_bid = consts.zero64
        load_store_id = c(0, width=32)
        if slot < p.issue_w:
            fire = issue_fires_eff[slot]
            pc = uop_pcs[slot]
            rob_i = uop_robs[slot]
            op = uop_ops[slot]
            uid = uop_uids[slot]
            parent_uid = uop_parent_uids[slot]
            block_uid = mux_by_uindex(m, idx=rob_i, items=rob.block_uid, default=consts.zero64)
            block_bid = mux_by_uindex(m, idx=rob_i, items=rob.block_bid, default=consts.zero64)
            load_store_id = mux_by_uindex(m, idx=rob_i, items=rob.load_store_id, default=c(0, width=32))
        m.output(f"issue_fire{slot}", fire)
        m.output(f"issue_pc{slot}", pc)
        m.output(f"issue_rob{slot}", rob_i)
        m.output(f"issue_op{slot}", op)
        m.output(f"issue_uop_uid{slot}", uid)
        m.output(f"issue_parent_uop_uid{slot}", parent_uid)
        m.output(f"issue_block_uid{slot}", block_uid)
        m.output(f"issue_block_bid{slot}", block_bid)
        m.output(f"issue_load_store_id{slot}", load_store_id)

    # Canonical IQ residency visibility: expose up to 4 resident uops
    # (LSU/BRU/ALU/CMD queues) for DFX pipeview IQ-stage tracking.
    def first_iq_entry(iq_regs):
        valid = consts.zero1
        rob_idx = c(0, width=p.rob_w)
        pc = consts.zero64
        for i in range(p.iq_depth):
            hit = (~valid) & iq_regs.valid[i].out()
            valid = hit.select(consts.one1, valid)
            rob_idx = hit.select(iq_regs.rob[i].out(), rob_idx)
            pc = hit.select(iq_regs.pc[i].out(), pc)
        uid = mux_by_uindex(m, idx=rob_idx, items=rob.uop_uid, default=consts.zero64)
        parent_uid = mux_by_uindex(m, idx=rob_idx, items=rob.parent_uid, default=consts.zero64)
        block_uid = mux_by_uindex(m, idx=rob_idx, items=rob.block_uid, default=consts.zero64)
        block_bid = mux_by_uindex(m, idx=rob_idx, items=rob.block_bid, default=consts.zero64)
        load_store_id = mux_by_uindex(m, idx=rob_idx, items=rob.load_store_id, default=c(0, width=32))
        return valid, pc, rob_idx, uid, parent_uid, block_uid, block_bid, load_store_id

    iq_slot_regs = [iq_lsu, iq_bru, iq_alu, iq_cmd]
    for slot, iq_regs in enumerate(iq_slot_regs):
        iq_valid, iq_pc, iq_rob, iq_uid, iq_parent_uid, iq_block_uid, iq_block_bid, iq_load_store_id = first_iq_entry(iq_regs)
        m.output(f"iq_valid{slot}", iq_valid)
        m.output(f"iq_pc{slot}", iq_pc)
        m.output(f"iq_rob{slot}", iq_rob)
        m.output(f"iq_uop_uid{slot}", iq_uid)
        m.output(f"iq_parent_uop_uid{slot}", iq_parent_uid)
        m.output(f"iq_block_uid{slot}", iq_block_uid)
        m.output(f"iq_block_bid{slot}", iq_block_bid)
        m.output(f"iq_load_store_id{slot}", iq_load_store_id)

    # MMIO visibility for testbenches (UART + exit).
    m.output("mmio_uart_valid", mmio_uart)
    m.output("mmio_uart_data", mmio_uart_data)
    m.output("mmio_exit_valid", mmio_exit)
    m.output("mmio_exit_code", mmio_exit_code)

    # Block command export for Janus BCtrl/TMU/PE path.
    # Commands are produced by the CMD issue pipe and retire only when BISQ
    # enqueue succeeds (`cmd_issue_fire_eff`).
    cmd_op = cmd_uop_op
    cmd_kind = c(0, width=3)
    cmd_kind = cmd_op.eq(c(OP_BIOR, width=12)).select(c(1, width=3), cmd_kind)
    cmd_kind = cmd_op.eq(c(OP_BLOAD, width=12)).select(c(2, width=3), cmd_kind)
    cmd_kind = cmd_op.eq(c(OP_BSTORE, width=12)).select(c(3, width=3), cmd_kind)
    cmd_payload = cmd_payload_lane
    cmd_tile = cmd_payload.trunc(width=6)
    cmd_src_rob = cmd_uop_rob
    cmd_bid = mux_by_uindex(m, idx=cmd_uop_rob, items=rob.block_bid, default=consts.zero64)
    cmd_tag = state.cycles.out().trunc(width=8)

    block_cmd_valid = cmd_issue_fire_eff
    block_cmd_kind = cmd_kind
    block_cmd_payload = cmd_payload
    block_cmd_tile = cmd_tile
    block_cmd_bid = cmd_bid
    block_cmd_tag = cmd_tag
    m.output("cmd_to_bisq_stage_cmd_valid", block_cmd_valid)
    m.output("cmd_to_bisq_stage_cmd_kind", block_cmd_kind)
    m.output("cmd_to_bisq_stage_cmd_bid", block_cmd_bid)
    m.output("cmd_to_bisq_stage_cmd_payload", block_cmd_payload)
    m.output("cmd_to_bisq_stage_cmd_tile", block_cmd_tile)
    m.output("cmd_to_bisq_stage_cmd_src_rob", cmd_src_rob)

    ooo_4wide = c(1 if (p.fetch_w == 4 and p.dispatch_w == 4 and p.issue_w == 4 and p.commit_w == 4) else 0, width=1)
    m.output("ooo_4wide", ooo_4wide)
    m.output("block_cmd_valid", block_cmd_valid)
    m.output("block_cmd_kind", block_cmd_kind)
    m.output("block_cmd_payload", block_cmd_payload)
    m.output("block_cmd_tile", block_cmd_tile)
    m.output("block_cmd_bid", block_cmd_bid)
    m.output("block_cmd_tag", block_cmd_tag)
    m.output("active_block_bid", state.active_block_bid.out())
    m.output("brob_retire_fire", brob_retire_fire)
    m.output("brob_retire_bid", brob_retire_bid)
    m.output("lsid_alloc_ctr", state.lsid_alloc_ctr.out())
    m.output("lsid_issue_ptr", state.lsid_issue_ptr.out())
    m.output("lsid_complete_ptr", state.lsid_complete_ptr.out())
    m.output("lsu_lsid_block_lane0", lsu_lsid_block_lane0)

    return BccOooExports(
        clk=clk,
        rst=rst,
        block_cmd_valid=block_cmd_valid.sig,
        block_cmd_kind=block_cmd_kind.sig,
        block_cmd_payload=block_cmd_payload.sig,
        block_cmd_tile=block_cmd_tile.sig,
        block_cmd_tag=block_cmd_tag.sig,
        block_cmd_bid=block_cmd_bid.sig,
        cycles=state.cycles.out().sig,
        halted=state.halted.out().sig,
    )
