from __future__ import annotations

import os
from dataclasses import dataclass
from types import SimpleNamespace

# ENGINE_ORCHESTRATION_ONLY:
# Stage/component ownership is migrating to focused files. Keep this file as
# composition glue; avoid adding new monolithic stage logic.

from pycircuit import Circuit, function, module, u
from pycircuit.dsl import Signal

from common.exec_uop import ExecOut, build_linxcore_exec_uop_comb
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
from .rob import is_macro_op, is_start_marker_op
from .state import make_core_ctrl_regs, make_iq_regs, make_prf, make_rename_regs
from .wakeup import build_head_wait_stage, compose_replay_cause, compose_wakeup_reason
from .modules.rob_bank import build_rob_bank_top
from .modules.commit_trace_stage import build_commit_trace_stage
from .modules.macro_trace_prep_stage import build_macro_trace_prep_stage
from .modules.pcbuf_stage import build_pcbuf_stage
from .modules.stbuf_stage import build_stbuf_stage


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


@function
def op_is_any(m: Circuit, op, *codes: int):
    v = u(1, 0)
    for code in codes:
        v = v | (op == u(12, code))
    return v


@function
def is_macro_op_any(m: Circuit, op):
    return op_is_any(m, op, OP_FENTRY, OP_FEXIT, OP_FRET_RA, OP_FRET_STK)


@function
def is_setc_any_op(m: Circuit, op):
    return op_is_any(
        m,
        op,
        OP_C_SETC_EQ,
        OP_C_SETC_NE,
        OP_SETC_GEUI,
        OP_SETC_EQ,
        OP_SETC_NE,
        OP_SETC_AND,
        OP_SETC_OR,
        OP_SETC_LT,
        OP_SETC_LTU,
        OP_SETC_GE,
        OP_SETC_GEU,
        OP_SETC_EQI,
        OP_SETC_NEI,
        OP_SETC_ANDI,
        OP_SETC_ORI,
        OP_SETC_LTI,
        OP_SETC_GEI,
        OP_SETC_LTUI,
    )


@function
def is_setc_tgt_op(m: Circuit, op):
    return op_is_any(m, op, OP_C_SETC_TGT)


@module(name="LinxCoreCommitSelectStage")
def build_commit_select_stage(
    m: Circuit,
    *,
    commit_w: int = 4,
    rob_w: int = 6,
) -> None:
    """Select up to `commit_w` commit slots and compute commit-driven state updates.

    This stage is split out of the backend engine to reduce JanusBccBackendTop
    compilation hotspots, while keeping the external behavior unchanged.
    """

    if commit_w <= 0:
        raise ValueError("commit_w must be > 0")
    if rob_w <= 0:
        raise ValueError("rob_w must be > 0")

    consts = make_consts(m)

    can_run = m.input("can_run", width=1)
    stbuf_has_space = m.input("stbuf_has_space", width=1)
    ret_ra_val = m.input("ret_ra_val", width=64)

    brob_active_allocated_i = m.input("brob_active_allocated_i", width=1)
    brob_active_ready_i = m.input("brob_active_ready_i", width=1)
    brob_active_exception_i = m.input("brob_active_exception_i", width=1)

    state_pc = m.input("state_pc", width=64)
    state_commit_cond = m.input("state_commit_cond", width=1)
    state_commit_tgt = m.input("state_commit_tgt", width=64)
    state_br_kind = m.input("state_br_kind", width=3)
    state_br_epoch = m.input("state_br_epoch", width=16)
    state_br_base_pc = m.input("state_br_base_pc", width=64)
    state_br_off = m.input("state_br_off", width=64)
    state_br_pred_take = m.input("state_br_pred_take", width=1)
    state_active_block_uid = m.input("state_active_block_uid", width=64)
    state_block_uid_ctr = m.input("state_block_uid_ctr", width=64)
    state_active_block_bid = m.input("state_active_block_bid", width=64)
    state_block_bid_ctr = m.input("state_block_bid_ctr", width=64)
    state_block_head = m.input("state_block_head", width=1)

    state_br_corr_pending = m.input("state_br_corr_pending", width=1)
    state_br_corr_epoch = m.input("state_br_corr_epoch", width=16)
    state_br_corr_take = m.input("state_br_corr_take", width=1)
    state_br_corr_target = m.input("state_br_corr_target", width=64)
    state_br_corr_checkpoint_id = m.input("state_br_corr_checkpoint_id", width=6)

    state_replay_pending = m.input("state_replay_pending", width=1)
    state_replay_store_rob = m.input("state_replay_store_rob", width=rob_w)
    state_replay_pc = m.input("state_replay_pc", width=64)

    commit_idxs = []
    rob_pcs = []
    rob_valids = []
    rob_dones = []
    rob_ops = []
    rob_lens = []
    rob_values = []
    rob_is_stores = []
    rob_st_addrs = []
    rob_st_datas = []
    rob_st_sizes = []
    rob_is_bstarts = []
    rob_is_bstops = []
    rob_boundary_kinds = []
    rob_boundary_targets = []
    rob_pred_takes = []
    rob_checkpoint_ids = []
    for slot in range(commit_w):
        commit_idxs.append(m.input(f"commit_idx{slot}", width=rob_w))
        rob_pcs.append(m.input(f"rob_pc{slot}", width=64))
        rob_valids.append(m.input(f"rob_valid{slot}", width=1))
        rob_dones.append(m.input(f"rob_done{slot}", width=1))
        rob_ops.append(m.input(f"rob_op{slot}", width=12))
        rob_lens.append(m.input(f"rob_len{slot}", width=3))
        rob_values.append(m.input(f"rob_value{slot}", width=64))
        rob_is_stores.append(m.input(f"rob_is_store{slot}", width=1))
        rob_st_addrs.append(m.input(f"rob_store_addr{slot}", width=64))
        rob_st_datas.append(m.input(f"rob_store_data{slot}", width=64))
        rob_st_sizes.append(m.input(f"rob_store_size{slot}", width=4))
        rob_is_bstarts.append(m.input(f"rob_is_bstart{slot}", width=1))
        rob_is_bstops.append(m.input(f"rob_is_bstop{slot}", width=1))
        rob_boundary_kinds.append(m.input(f"rob_boundary_kind{slot}", width=3))
        rob_boundary_targets.append(m.input(f"rob_boundary_target{slot}", width=64))
        rob_pred_takes.append(m.input(f"rob_pred_take{slot}", width=1))
        rob_checkpoint_ids.append(m.input(f"rob_checkpoint_id{slot}", width=6))

    commit_allow = consts.one1
    commit_fires = []
    commit_pcs = []
    commit_next_pcs = []
    commit_is_bstarts = []
    commit_is_bstops = []
    commit_block_uids = []
    commit_block_bids = []
    commit_core_ids = []

    commit_count = u(3, 0)

    redirect_valid = consts.zero1
    redirect_pc = state_pc
    redirect_checkpoint_id = u(6, 0)
    redirect_from_corr = consts.zero1
    replay_redirect_fire = consts.zero1

    commit_store_seen = consts.zero1
    brob_retire_fire = consts.zero1
    brob_retire_bid = consts.zero64

    pc_live = state_pc
    commit_cond_live = state_commit_cond
    commit_tgt_live = state_commit_tgt
    br_kind_live = state_br_kind
    br_epoch_live = state_br_epoch
    br_base_live = state_br_base_pc
    br_off_live = state_br_off
    br_pred_take_live = state_br_pred_take
    active_block_uid_live = state_active_block_uid
    block_uid_ctr_live = state_block_uid_ctr
    active_block_bid_live = state_active_block_bid
    block_bid_ctr_live = state_block_bid_ctr
    block_head_live = state_block_head
    br_corr_pending_live = state_br_corr_pending
    br_corr_epoch_live = state_br_corr_epoch
    br_corr_take_live = state_br_corr_take
    br_corr_target_live = state_br_corr_target
    br_corr_checkpoint_id_live = state_br_corr_checkpoint_id

    for slot in range(commit_w):
        # Use the ROB-captured instruction PC for commit/control-flow math.
        pc_this = rob_pcs[slot] if rob_valids[slot] else pc_live
        commit_pcs.append(pc_this)
        op = rob_ops[slot]
        ln = rob_lens[slot]
        val = rob_values[slot]

        is_macro = is_macro_op_any(m, op)
        is_bstart = rob_is_bstarts[slot]
        is_bstop = rob_is_bstops[slot]
        is_bstart_head = is_bstart & block_head_live
        is_bstart_mid = is_bstart & (~block_head_live)
        is_start_marker = is_bstart_head | is_macro
        is_boundary = is_bstart_mid | is_bstop | is_macro

        br_is_fall = br_kind_live == u(3, BK_FALL)
        br_is_cond = br_kind_live == u(3, BK_COND)
        br_is_call = br_kind_live == u(3, BK_CALL)
        br_is_ret = br_kind_live == u(3, BK_RET)
        br_is_direct = br_kind_live == u(3, BK_DIRECT)
        br_is_ind = br_kind_live == u(3, BK_IND)
        br_is_icall = br_kind_live == u(3, BK_ICALL)

        br_target = br_base_live + br_off_live
        # Dynamic target for RET/IND/ICALL blocks comes from commit_tgt.
        br_target = commit_tgt_live if (br_is_ret | br_is_ind | br_is_icall) else br_target
        # Allow SETC.TGT to override fixed targets for DIRECT/CALL/COND blocks.
        use_commit_tgt = (~(br_is_ret | br_is_ind | br_is_icall)) & (~(commit_tgt_live == consts.zero64))
        br_target = commit_tgt_live if use_commit_tgt else br_target

        br_take = (
            br_is_call
            | br_is_direct
            | br_is_ind
            | br_is_icall
            | (br_is_cond & commit_cond_live)
            | (br_is_ret & commit_cond_live)
        )

        fire = can_run & commit_allow & rob_valids[slot] & rob_dones[slot]

        # Template macro blocks must reach the head of the ROB.
        if slot != 0:
            fire = fire & (~is_macro)

        # Boundary-authoritative correction:
        # BRU mismatch only records correction; redirect is consumed at boundary commit.
        corr_epoch_match = br_corr_pending_live & (br_corr_epoch_live == br_epoch_live)
        corr_for_boundary = fire & is_boundary & corr_epoch_match
        br_take_eff = br_corr_take_live if corr_for_boundary else br_take
        br_target_eff = br_corr_target_live if corr_for_boundary else br_target

        pc_inc = pc_this + ln
        boundary_fallthrough = pc_this if is_bstart_mid else pc_inc
        pc_next = (br_target_eff if br_take_eff else boundary_fallthrough) if is_boundary else pc_inc

        # Stop commit on redirect, store, or halt.
        is_halt = op_is_any(m, op, OP_EBREAK, OP_INVALID)
        # Mid-block BSTART must restart fetch at its own PC even when the
        # previous block resolves not-taken; this mirrors QEMU block-end split.
        redirect = fire & ((is_boundary & br_take_eff) | is_bstart_mid)
        replay_redirect = (
            fire
            & rob_is_stores[slot]
            & state_replay_pending
            & (commit_idxs[slot] == state_replay_store_rob)
        )
        replay_redirect_fire = replay_redirect_fire | replay_redirect

        # FRET.* behave like a taken boundary when the marker is entered.
        is_fret = op_is_any(m, op, OP_FRET_RA, OP_FRET_STK)
        fret_redirect = fire & is_fret & (~redirect)
        pc_next = ret_ra_val if fret_redirect else pc_next
        redirect = redirect | fret_redirect
        pc_next = state_replay_pc if replay_redirect else pc_next
        redirect = redirect | replay_redirect
        commit_next_pcs.append(pc_next)

        # Keep 1-store-per-cycle commit enqueue while allowing other younger
        # non-store commits in the same cycle.
        fire = fire & ((~rob_is_stores[slot]) | ((~commit_store_seen) & stbuf_has_space))
        # Block-authoritative retirement gate:
        bstop_gate_ok = (~is_bstop) | (~brob_active_allocated_i) | brob_active_ready_i | brob_active_exception_i
        fire = fire & bstop_gate_ok
        store_commit = fire & rob_is_stores[slot]
        stop = redirect | (fire & is_halt)

        commit_fires.append(fire)
        commit_count = commit_count + fire

        redirect_valid = redirect_valid | redirect
        redirect_pc = pc_next if redirect else redirect_pc
        redirect_ckpt_sel = br_corr_checkpoint_id_live if corr_for_boundary else rob_checkpoint_ids[slot]
        redirect_checkpoint_id = redirect_ckpt_sel if redirect else redirect_checkpoint_id
        redirect_from_corr = redirect_from_corr | (redirect & corr_for_boundary)

        commit_store_seen = commit_store_seen | store_commit

        bstart_commit = fire & is_bstart
        block_uid_this = block_uid_ctr_live if bstart_commit else active_block_uid_live
        block_bid_this = block_bid_ctr_live if bstart_commit else active_block_bid_live
        commit_is_bstarts.append(bstart_commit)
        bstop_commit = fire & is_bstop
        commit_is_bstops.append(bstop_commit)
        commit_block_uids.append(block_uid_this)
        commit_block_bids.append(block_bid_this)
        commit_core_ids.append(u(2, 0))
        bstop_take = bstop_commit & (~brob_retire_fire)
        brob_retire_fire = brob_retire_fire | bstop_take
        brob_retire_bid = active_block_bid_live if bstop_take else brob_retire_bid
        active_block_uid_live = block_uid_this if bstart_commit else active_block_uid_live
        block_uid_ctr_live = (block_uid_ctr_live + u(64, 1)) if bstart_commit else block_uid_ctr_live
        active_block_bid_live = block_bid_this if bstart_commit else active_block_bid_live
        block_bid_ctr_live = (block_bid_ctr_live + u(64, 1)) if bstart_commit else block_bid_ctr_live
        active_block_bid_live = consts.zero64 if (fire & is_bstop) else active_block_bid_live

        # --- sequential architectural state updates across commit slots ---
        op_setc_any = is_setc_any_op(m, op)
        op_setc_tgt = is_setc_tgt_op(m, op)
        commit_cond_live = consts.zero1 if (fire & is_boundary) else commit_cond_live
        commit_tgt_live = consts.zero64 if (fire & is_boundary) else commit_tgt_live
        commit_cond_live = val[0:1] if (fire & op_setc_any) else commit_cond_live
        commit_tgt_live = val if (fire & op_setc_tgt) else commit_tgt_live
        commit_cond_live = consts.one1 if (fire & op_setc_tgt) else commit_cond_live

        br_kind_live = u(3, BK_FALL) if (fire & is_boundary & br_take_eff) else br_kind_live
        br_base_live = pc_this if (fire & is_boundary & br_take_eff) else br_base_live
        br_off_live = consts.zero64 if (fire & is_boundary & br_take_eff) else br_off_live
        br_pred_take_live = consts.zero1 if (fire & is_boundary & br_take_eff) else br_pred_take_live

        enter_new_block = fire & is_bstart & (~br_take_eff)
        meta_off = rob_boundary_targets[slot] - pc_this
        br_kind_live = rob_boundary_kinds[slot] if enter_new_block else br_kind_live
        br_base_live = pc_this if enter_new_block else br_base_live
        br_off_live = meta_off if enter_new_block else br_off_live
        br_pred_take_live = rob_pred_takes[slot] if enter_new_block else br_pred_take_live

        # Macro blocks are standalone fall-through blocks.
        macro_enter = fire & is_macro & (~br_take_eff)
        br_kind_live = u(3, BK_FALL) if macro_enter else br_kind_live
        br_base_live = pc_this if macro_enter else br_base_live
        br_off_live = consts.zero64 if macro_enter else br_off_live
        br_pred_take_live = consts.zero1 if macro_enter else br_pred_take_live

        br_kind_live = u(3, BK_FALL) if (fire & is_bstop) else br_kind_live
        br_base_live = pc_this if (fire & is_bstop) else br_base_live
        br_off_live = consts.zero64 if (fire & is_bstop) else br_off_live
        br_pred_take_live = consts.zero1 if (fire & is_bstop) else br_pred_take_live

        clear_corr_boundary = fire & is_boundary & corr_epoch_match
        br_corr_pending_live = consts.zero1 if clear_corr_boundary else br_corr_pending_live

        br_epoch_advance = fire & is_boundary
        br_epoch_live = (br_epoch_live + u(16, 1)) if br_epoch_advance else br_epoch_live

        # Track whether next committed instruction should be interpreted as the
        # block head marker.
        block_head_live = consts.one1 if (fire & (is_boundary | redirect)) else block_head_live
        block_head_live = consts.zero1 if (fire & is_bstart_head) else block_head_live

        pc_live = pc_next if fire else pc_live

        commit_allow = commit_allow & fire & (~stop)

    # Canonical retired-store selection from committed slots (oldest first).
    store_sel_fire = consts.zero1
    store_sel_addr = consts.zero64
    store_sel_data = consts.zero64
    store_sel_size = consts.zero4
    for slot in range(commit_w):
        slot_store = commit_fires[slot] & rob_is_stores[slot]
        take = slot_store & (~store_sel_fire)
        store_sel_fire = store_sel_fire | slot_store
        store_sel_addr = rob_st_addrs[slot] if take else store_sel_addr
        store_sel_data = rob_st_datas[slot] if take else store_sel_data
        store_sel_size = rob_st_sizes[slot] if take else store_sel_size

    m.output("commit_count", commit_count)
    m.output("redirect_valid", redirect_valid)
    m.output("redirect_pc", redirect_pc)
    m.output("redirect_checkpoint_id", redirect_checkpoint_id)
    m.output("redirect_from_corr", redirect_from_corr)
    m.output("replay_redirect_fire", replay_redirect_fire)
    m.output("commit_store_fire", store_sel_fire)
    m.output("commit_store_addr", store_sel_addr)
    m.output("commit_store_data", store_sel_data)
    m.output("commit_store_size", store_sel_size)
    m.output("brob_retire_fire", brob_retire_fire)
    m.output("brob_retire_bid", brob_retire_bid)

    m.output("pc_live", pc_live)
    m.output("commit_cond_live", commit_cond_live)
    m.output("commit_tgt_live", commit_tgt_live)
    m.output("br_kind_live", br_kind_live)
    m.output("br_epoch_live", br_epoch_live)
    m.output("br_base_live", br_base_live)
    m.output("br_off_live", br_off_live)
    m.output("br_pred_take_live", br_pred_take_live)
    m.output("active_block_uid_live", active_block_uid_live)
    m.output("block_uid_ctr_live", block_uid_ctr_live)
    m.output("active_block_bid_live", active_block_bid_live)
    m.output("block_bid_ctr_live", block_bid_ctr_live)
    m.output("block_head_live", block_head_live)
    m.output("br_corr_pending_live", br_corr_pending_live)

    for slot in range(commit_w):
        m.output(f"commit_fire{slot}", commit_fires[slot])
        m.output(f"commit_pc{slot}", commit_pcs[slot])
        m.output(f"commit_next_pc{slot}", commit_next_pcs[slot])
        m.output(f"commit_is_bstart{slot}", commit_is_bstarts[slot])
        m.output(f"commit_is_bstop{slot}", commit_is_bstops[slot])
        m.output(f"commit_block_uid{slot}", commit_block_uids[slot])
        m.output(f"commit_block_bid{slot}", commit_block_bids[slot])
        m.output(f"commit_core_id{slot}", commit_core_ids[slot])


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

    # Optional fixed template frame addend knob.
    #
    # Architectural default follows immediate-only frame semantics:
    #   f.entry:   sp -= stacksize
    #   f.exit/*:  sp += stacksize
    #
    # Set LINXCORE_CALLFRAME_SIZE to a non-zero multiple of 8 to include an
    # additional fixed outgoing-call frame.
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
            v = v | op.__eq__(c(code, width=12))
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

    # --- ROB bank (in-order commit; forced hierarchy) ---
    #
    # Instantiate early so the rest of the backend can read ROB state via
    # module outputs. We connect its event inputs later, once the pipeline
    # has produced them (placeholder wires here).
    def _rob_in(width: int):
        return m.new_wire(width=width)

    rob_bank_in = {
        "clk": clk,
        "rst": rst,
        "do_flush": _rob_in(1),
        "commit_fire": _rob_in(1),
        "dispatch_fire": _rob_in(1),
        "commit_count": _rob_in(3),
        "disp_count": _rob_in(3),
    }
    for slot in range(p.dispatch_w):
        rob_bank_in[f"disp_valid{slot}"] = _rob_in(1)
    for slot in range(p.commit_w):
        rob_bank_in[f"commit_fire{slot}"] = _rob_in(1)
        rob_bank_in[f"commit_idx{slot}"] = _rob_in(p.rob_w)
    for slot in range(p.dispatch_w):
        rob_bank_in[f"disp_pc{slot}"] = _rob_in(64)
        rob_bank_in[f"disp_op{slot}"] = _rob_in(12)
        rob_bank_in[f"disp_len{slot}"] = _rob_in(3)
        rob_bank_in[f"disp_insn_raw{slot}"] = _rob_in(64)
        rob_bank_in[f"disp_checkpoint_id{slot}"] = _rob_in(6)
        rob_bank_in[f"disp_dst_kind{slot}"] = _rob_in(2)
        rob_bank_in[f"disp_regdst{slot}"] = _rob_in(6)
        rob_bank_in[f"disp_pdst{slot}"] = _rob_in(p.ptag_w)
        rob_bank_in[f"disp_imm{slot}"] = _rob_in(64)
        rob_bank_in[f"disp_is_store{slot}"] = _rob_in(1)
        rob_bank_in[f"disp_is_boundary{slot}"] = _rob_in(1)
        rob_bank_in[f"disp_is_bstart{slot}"] = _rob_in(1)
        rob_bank_in[f"disp_is_bstop{slot}"] = _rob_in(1)
        rob_bank_in[f"disp_boundary_kind{slot}"] = _rob_in(3)
        rob_bank_in[f"disp_boundary_target{slot}"] = _rob_in(64)
        rob_bank_in[f"disp_pred_take{slot}"] = _rob_in(1)
        rob_bank_in[f"disp_block_epoch{slot}"] = _rob_in(16)
        rob_bank_in[f"disp_block_uid{slot}"] = _rob_in(64)
        rob_bank_in[f"disp_block_bid{slot}"] = _rob_in(64)
        rob_bank_in[f"disp_load_store_id{slot}"] = _rob_in(32)
        rob_bank_in[f"disp_resolved_d2{slot}"] = _rob_in(1)
        rob_bank_in[f"disp_srcl{slot}"] = _rob_in(6)
        rob_bank_in[f"disp_srcr{slot}"] = _rob_in(6)
        rob_bank_in[f"disp_uop_uid{slot}"] = _rob_in(64)
        rob_bank_in[f"disp_parent_uid{slot}"] = _rob_in(64)
    for slot in range(p.issue_w):
        rob_bank_in[f"wb_fire{slot}"] = _rob_in(1)
        rob_bank_in[f"wb_rob{slot}"] = _rob_in(p.rob_w)
        rob_bank_in[f"wb_value{slot}"] = _rob_in(64)
        rob_bank_in[f"store_fire{slot}"] = _rob_in(1)
        rob_bank_in[f"load_fire{slot}"] = _rob_in(1)
        rob_bank_in[f"ex_addr{slot}"] = _rob_in(64)
        rob_bank_in[f"ex_wdata{slot}"] = _rob_in(64)
        rob_bank_in[f"ex_size{slot}"] = _rob_in(4)
        rob_bank_in[f"ex_src0{slot}"] = _rob_in(64)
        rob_bank_in[f"ex_src1{slot}"] = _rob_in(64)

    # Probe interface for lane0 LSU store disambiguation/forwarding against
    # in-flight ROB stores (minimizes port explosion vs scanning in LinxCoreLsuStage).
    rob_bank_in["lsu_probe_fire"] = _rob_in(1)
    rob_bank_in["lsu_probe_addr"] = _rob_in(64)
    rob_bank_in["lsu_probe_rob"] = _rob_in(p.rob_w)
    rob_bank_in["lsu_probe_sub_head"] = _rob_in(p.rob_w)

    rob_bank = m.instance_auto(
        build_rob_bank_top,
        name="rob_bank",
        params={
            "rob_depth": p.rob_depth,
            "slice_entries": 8,
            "dispatch_w": p.dispatch_w,
            "issue_w": p.issue_w,
            "commit_w": p.commit_w,
            "rob_w": p.rob_w,
            "ptag_w": p.ptag_w,
        },
        **rob_bank_in,
    )

    # Build a RobRegs-shaped view out of wires (wires implement `.out()`).
    rob = SimpleNamespace(
        head=rob_bank["head"].read(),
        tail=rob_bank["tail"].read(),
        count=rob_bank["count"].read(),
        valid=[rob_bank[f"valid{i}"].read() for i in range(p.rob_depth)],
        done=[rob_bank[f"done{i}"].read() for i in range(p.rob_depth)],
        pc=[rob_bank[f"pc{i}"].read() for i in range(p.rob_depth)],
        op=[rob_bank[f"op{i}"].read() for i in range(p.rob_depth)],
        len_bytes=[rob_bank[f"len{i}"].read() for i in range(p.rob_depth)],
        dst_kind=[rob_bank[f"dst_kind{i}"].read() for i in range(p.rob_depth)],
        dst_areg=[rob_bank[f"dst_areg{i}"].read() for i in range(p.rob_depth)],
        pdst=[rob_bank[f"pdst{i}"].read() for i in range(p.rob_depth)],
        value=[rob_bank[f"value{i}"].read() for i in range(p.rob_depth)],
        src0_reg=[rob_bank[f"src0_reg{i}"].read() for i in range(p.rob_depth)],
        src1_reg=[rob_bank[f"src1_reg{i}"].read() for i in range(p.rob_depth)],
        src0_value=[rob_bank[f"src0_value{i}"].read() for i in range(p.rob_depth)],
        src1_value=[rob_bank[f"src1_value{i}"].read() for i in range(p.rob_depth)],
        src0_valid=[rob_bank[f"src0_valid{i}"].read() for i in range(p.rob_depth)],
        src1_valid=[rob_bank[f"src1_valid{i}"].read() for i in range(p.rob_depth)],
        store_addr=[rob_bank[f"store_addr{i}"].read() for i in range(p.rob_depth)],
        store_data=[rob_bank[f"store_data{i}"].read() for i in range(p.rob_depth)],
        store_size=[rob_bank[f"store_size{i}"].read() for i in range(p.rob_depth)],
        is_store=[rob_bank[f"is_store{i}"].read() for i in range(p.rob_depth)],
        load_addr=[rob_bank[f"load_addr{i}"].read() for i in range(p.rob_depth)],
        load_data=[rob_bank[f"load_data{i}"].read() for i in range(p.rob_depth)],
        load_size=[rob_bank[f"load_size{i}"].read() for i in range(p.rob_depth)],
        is_load=[rob_bank[f"is_load{i}"].read() for i in range(p.rob_depth)],
        is_boundary=[rob_bank[f"is_boundary{i}"].read() for i in range(p.rob_depth)],
        is_bstart=[rob_bank[f"is_bstart{i}"].read() for i in range(p.rob_depth)],
        is_bstop=[rob_bank[f"is_bstop{i}"].read() for i in range(p.rob_depth)],
        boundary_kind=[rob_bank[f"boundary_kind{i}"].read() for i in range(p.rob_depth)],
        boundary_target=[rob_bank[f"boundary_target{i}"].read() for i in range(p.rob_depth)],
        pred_take=[rob_bank[f"pred_take{i}"].read() for i in range(p.rob_depth)],
        block_epoch=[rob_bank[f"block_epoch{i}"].read() for i in range(p.rob_depth)],
        block_uid=[rob_bank[f"block_uid{i}"].read() for i in range(p.rob_depth)],
        block_bid=[rob_bank[f"block_bid{i}"].read() for i in range(p.rob_depth)],
        load_store_id=[rob_bank[f"load_store_id{i}"].read() for i in range(p.rob_depth)],
        resolved_d2=[rob_bank[f"resolved_d2{i}"].read() for i in range(p.rob_depth)],
        insn_raw=[rob_bank[f"insn_raw{i}"].read() for i in range(p.rob_depth)],
        checkpoint_id=[rob_bank[f"checkpoint_id{i}"].read() for i in range(p.rob_depth)],
        macro_begin=[rob_bank[f"macro_begin{i}"].read() for i in range(p.rob_depth)],
        macro_end=[rob_bank[f"macro_end{i}"].read() for i in range(p.rob_depth)],
        uop_uid=[rob_bank[f"uop_uid{i}"].read() for i in range(p.rob_depth)],
        parent_uid=[rob_bank[f"parent_uid{i}"].read() for i in range(p.rob_depth)],
    )

    disp_rob_idxs = [rob_bank[f"disp_rob_idx{slot}"].read() for slot in range(p.dispatch_w)]
    disp_fires = [rob_bank[f"disp_fire{slot}"].read() for slot in range(p.dispatch_w)]

    # --- issue queues (bring-up split) ---
    iq_alu = make_iq_regs(m, clk, rst, consts=consts, p=p, name="iq_alu")
    iq_bru = make_iq_regs(m, clk, rst, consts=consts, p=p, name="iq_bru")
    iq_lsu = make_iq_regs(m, clk, rst, consts=consts, p=p, name="iq_lsu")
    iq_cmd = make_iq_regs(m, clk, rst, consts=consts, p=p, name="iq_cmd")

    # --- committed store buffer (forced hierarchy) ---
    commit_store_fire_i = m.new_wire(width=1)
    commit_store_addr_i = m.new_wire(width=64)
    commit_store_data_i = m.new_wire(width=64)
    commit_store_size_i = m.new_wire(width=4)
    macro_store_fire_i = m.new_wire(width=1)
    macro_store_addr_i = m.new_wire(width=64)
    macro_store_data_i = m.new_wire(width=64)
    macro_store_size_i = m.new_wire(width=4)
    macro_load_fire_i = m.new_wire(width=1)
    macro_load_addr_i = m.new_wire(width=64)
    lsu_load_fire_i = m.new_wire(width=1)
    lsu_load_addr_i = m.new_wire(width=64)
    lsu_probe_fire_i = m.new_wire(width=1)
    lsu_probe_addr_i = m.new_wire(width=64)

    stbuf_stage = m.instance_auto(
        build_stbuf_stage,
        name="stbuf_stage",
        module_name="LinxCoreStbufStage",
        params={"sq_entries": p.sq_entries, "sq_w": p.sq_w},
        clk=clk,
        rst=rst,
        commit_store_fire=commit_store_fire_i,
        commit_store_addr=commit_store_addr_i,
        commit_store_data=commit_store_data_i,
        commit_store_size=commit_store_size_i,
        macro_store_fire=macro_store_fire_i,
        macro_store_addr=macro_store_addr_i,
        macro_store_data=macro_store_data_i,
        macro_store_size=macro_store_size_i,
        macro_load_fire=macro_load_fire_i,
        macro_load_addr=macro_load_addr_i,
        lsu_load_fire=lsu_load_fire_i,
        lsu_load_addr=lsu_load_addr_i,
        lsu_probe_fire=lsu_probe_fire_i,
        lsu_probe_addr=lsu_probe_addr_i,
        dmem_rdata_i=dmem_rdata_i,
    )
    stbuf_has_space = stbuf_stage["has_space"]
    stbuf_valid = [stbuf_stage[f"valid{i}"] for i in range(p.sq_entries)]
    stbuf_addr = [stbuf_stage[f"addr{i}"] for i in range(p.sq_entries)]
    stbuf_data = [stbuf_stage[f"data{i}"] for i in range(p.sq_entries)]
    stbuf_size = [stbuf_stage[f"size{i}"] for i in range(p.sq_entries)]
    stbuf_enq_fire = stbuf_stage["enq_fire"]
    stbuf_drain_fire = stbuf_stage["drain_fire"]
    commit_store_write_through = stbuf_stage["commit_store_write_through"]
    dmem_raddr = stbuf_stage["dmem_raddr"]
    mem_wvalid = stbuf_stage["dmem_wvalid"]
    mem_waddr = stbuf_stage["dmem_waddr"]
    dmem_wdata = stbuf_stage["dmem_wdata"]
    wstrb = stbuf_stage["dmem_wstrb"]
    dmem_wsrc = stbuf_stage["dmem_wsrc"]
    mmio_uart = stbuf_stage["mmio_uart_valid"]
    mmio_uart_data = stbuf_stage["mmio_uart_data"]
    mmio_exit = stbuf_stage["mmio_exit_valid"]
    mmio_exit_code = stbuf_stage["mmio_exit_code"]
    macro_load_data = stbuf_stage["macro_load_data"]
    stbuf_lsu_fwd_hit = stbuf_stage["lsu_fwd_hit"]
    stbuf_lsu_fwd_data = stbuf_stage["lsu_fwd_data"]

    # --- frontend BSTART metadata buffer (PC buffer equivalent, forced hierarchy) ---
    pcbuf_wr_valid_i = [m.new_wire(width=1) for _ in range(p.dispatch_w)]
    pcbuf_wr_pc_i = [m.new_wire(width=64) for _ in range(p.dispatch_w)]
    pcbuf_wr_kind_i = [m.new_wire(width=3) for _ in range(p.dispatch_w)]
    pcbuf_wr_target_i = [m.new_wire(width=64) for _ in range(p.dispatch_w)]
    pcbuf_wr_pred_take_i = [m.new_wire(width=1) for _ in range(p.dispatch_w)]
    pcbuf_wr_is_bstart_i = [m.new_wire(width=1) for _ in range(p.dispatch_w)]

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
    commit_head = m.instance_auto(
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
    ctu = m.instance_auto(
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

    commit_sel_args = {
        "can_run": can_run,
        "stbuf_has_space": stbuf_has_space,
        "ret_ra_val": ret_ra_val,
        "brob_active_allocated_i": brob_active_allocated_i,
        "brob_active_ready_i": brob_active_ready_i,
        "brob_active_exception_i": brob_active_exception_i,
        "state_pc": state.pc.out(),
        "state_commit_cond": state.commit_cond.out(),
        "state_commit_tgt": state.commit_tgt.out(),
        "state_br_kind": state.br_kind.out(),
        "state_br_epoch": state.br_epoch.out(),
        "state_br_base_pc": state.br_base_pc.out(),
        "state_br_off": state.br_off.out(),
        "state_br_pred_take": state.br_pred_take.out(),
        "state_active_block_uid": state.active_block_uid.out(),
        "state_block_uid_ctr": state.block_uid_ctr.out(),
        "state_active_block_bid": state.active_block_bid.out(),
        "state_block_bid_ctr": state.block_bid_ctr.out(),
        "state_block_head": state.block_head.out(),
        "state_br_corr_pending": state.br_corr_pending.out(),
        "state_br_corr_epoch": state.br_corr_epoch.out(),
        "state_br_corr_take": state.br_corr_take.out(),
        "state_br_corr_target": state.br_corr_target.out(),
        "state_br_corr_checkpoint_id": state.br_corr_checkpoint_id.out(),
        "state_replay_pending": state.replay_pending.out(),
        "state_replay_store_rob": state.replay_store_rob.out(),
        "state_replay_pc": state.replay_pc.out(),
    }
    for slot in range(p.commit_w):
        commit_sel_args[f"commit_idx{slot}"] = commit_idxs[slot]
        commit_sel_args[f"rob_pc{slot}"] = rob_pcs[slot]
        commit_sel_args[f"rob_valid{slot}"] = rob_valids[slot]
        commit_sel_args[f"rob_done{slot}"] = rob_dones[slot]
        commit_sel_args[f"rob_op{slot}"] = rob_ops[slot]
        commit_sel_args[f"rob_len{slot}"] = rob_lens[slot]
        commit_sel_args[f"rob_value{slot}"] = rob_values[slot]
        commit_sel_args[f"rob_is_store{slot}"] = rob_is_stores[slot]
        commit_sel_args[f"rob_store_addr{slot}"] = rob_st_addrs[slot]
        commit_sel_args[f"rob_store_data{slot}"] = rob_st_datas[slot]
        commit_sel_args[f"rob_store_size{slot}"] = rob_st_sizes[slot]
        commit_sel_args[f"rob_is_bstart{slot}"] = rob_is_bstarts[slot]
        commit_sel_args[f"rob_is_bstop{slot}"] = rob_is_bstops[slot]
        commit_sel_args[f"rob_boundary_kind{slot}"] = rob_boundary_kinds[slot]
        commit_sel_args[f"rob_boundary_target{slot}"] = rob_boundary_targets[slot]
        commit_sel_args[f"rob_pred_take{slot}"] = rob_pred_takes[slot]
        commit_sel_args[f"rob_checkpoint_id{slot}"] = rob_checkpoint_ids[slot]

    commit_sel = m.instance_auto(
        build_commit_select_stage,
        name="commit_select_stage",
        params={"commit_w": p.commit_w, "rob_w": p.rob_w},
        **commit_sel_args,
    )

    commit_fires = [commit_sel[f"commit_fire{slot}"] for slot in range(p.commit_w)]
    commit_pcs = [commit_sel[f"commit_pc{slot}"] for slot in range(p.commit_w)]
    commit_next_pcs = [commit_sel[f"commit_next_pc{slot}"] for slot in range(p.commit_w)]
    commit_is_bstarts = [commit_sel[f"commit_is_bstart{slot}"] for slot in range(p.commit_w)]
    commit_is_bstops = [commit_sel[f"commit_is_bstop{slot}"] for slot in range(p.commit_w)]
    commit_block_uids = [commit_sel[f"commit_block_uid{slot}"] for slot in range(p.commit_w)]
    commit_block_bids = [commit_sel[f"commit_block_bid{slot}"] for slot in range(p.commit_w)]
    commit_core_ids = [commit_sel[f"commit_core_id{slot}"] for slot in range(p.commit_w)]

    commit_count = commit_sel["commit_count"]
    redirect_valid = commit_sel["redirect_valid"]
    redirect_pc = commit_sel["redirect_pc"]
    redirect_checkpoint_id = commit_sel["redirect_checkpoint_id"]
    redirect_from_corr = commit_sel["redirect_from_corr"]
    replay_redirect_fire = commit_sel["replay_redirect_fire"]
    commit_store_fire = commit_sel["commit_store_fire"]
    commit_store_addr = commit_sel["commit_store_addr"]
    commit_store_data = commit_sel["commit_store_data"]
    commit_store_size = commit_sel["commit_store_size"]

    # Drive committed store buffer hierarchy inputs from commit-selection outputs.
    m.assign(commit_store_fire_i, commit_store_fire)
    m.assign(commit_store_addr_i, commit_store_addr)
    m.assign(commit_store_data_i, commit_store_data)
    m.assign(commit_store_size_i, commit_store_size)
    brob_retire_fire = commit_sel["brob_retire_fire"]
    brob_retire_bid = commit_sel["brob_retire_bid"]

    pc_live = commit_sel["pc_live"]
    commit_cond_live = commit_sel["commit_cond_live"]
    commit_tgt_live = commit_sel["commit_tgt_live"]
    br_kind_live = commit_sel["br_kind_live"]
    br_epoch_live = commit_sel["br_epoch_live"]
    br_base_live = commit_sel["br_base_live"]
    br_off_live = commit_sel["br_off_live"]
    br_pred_take_live = commit_sel["br_pred_take_live"]
    active_block_uid_live = commit_sel["active_block_uid_live"]
    block_uid_ctr_live = commit_sel["block_uid_ctr_live"]
    active_block_bid_live = commit_sel["active_block_bid_live"]
    block_bid_ctr_live = commit_sel["block_bid_ctr_live"]
    block_head_live = commit_sel["block_head_live"]
    br_corr_pending_live = commit_sel["br_corr_pending_live"]
    br_corr_epoch_live = state.br_corr_epoch.out()
    br_corr_take_live = state.br_corr_take.out()
    br_corr_target_live = state.br_corr_target.out()
    br_corr_checkpoint_id_live = state.br_corr_checkpoint_id.out()

    lsid_issue_ptr_live = state.lsid_issue_ptr.out()
    lsid_complete_ptr_live = state.lsid_complete_ptr.out()

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

    issue_stage = m.instance_auto(
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

    uop_robs[cmd_slot] = cmd_slot_sel._select_internal(cmd_uop_rob, uop_robs[cmd_slot])
    uop_ops[cmd_slot] = cmd_slot_sel._select_internal(cmd_uop_op, uop_ops[cmd_slot])
    uop_pcs[cmd_slot] = cmd_slot_sel._select_internal(cmd_uop_pc, uop_pcs[cmd_slot])
    uop_imms[cmd_slot] = cmd_slot_sel._select_internal(cmd_uop_imm, uop_imms[cmd_slot])
    uop_sls[cmd_slot] = cmd_slot_sel._select_internal(cmd_uop_sl, uop_sls[cmd_slot])
    uop_srs[cmd_slot] = cmd_slot_sel._select_internal(cmd_uop_sr, uop_srs[cmd_slot])
    uop_srcr_types[cmd_slot] = cmd_slot_sel._select_internal(cmd_uop_srcr_type, uop_srcr_types[cmd_slot])
    uop_shamts[cmd_slot] = cmd_slot_sel._select_internal(cmd_uop_shamt, uop_shamts[cmd_slot])
    uop_sps[cmd_slot] = cmd_slot_sel._select_internal(cmd_uop_sp, uop_sps[cmd_slot])
    uop_pdsts[cmd_slot] = cmd_slot_sel._select_internal(cmd_uop_pdst, uop_pdsts[cmd_slot])
    uop_has_dsts[cmd_slot] = cmd_slot_sel._select_internal(cmd_uop_has_dst, uop_has_dsts[cmd_slot])
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
        ex_mod = m.instance_auto(
            build_linxcore_exec_uop_comb,
            name=f"exec_uop{slot}",
            module_name="LinxCoreExecUopComb",
            op=uop_ops[slot],
            pc=uop_pcs[slot],
            imm=uop_imms[slot],
            srcl_val=sl_vals[slot],
            srcr_val=sr_vals[slot],
            srcr_type=uop_srcr_types[slot],
            shamt=uop_shamts[slot],
            srcp_val=sp_vals[slot],
        )
        exs.append(
            ExecOut(
                alu=ex_mod["alu"],
                is_load=ex_mod["is_load"],
                is_store=ex_mod["is_store"],
                size=ex_mod["size"],
                addr=ex_mod["addr"],
                wdata=ex_mod["wdata"],
            )
        )

    # Lane0 values for debug/trace.
    sl_val = sl_vals[0]
    sr_val = sr_vals[0]
    sp_val = sp_vals[0]

    issue_fires_eff = [issue_fires[i] for i in range(p.issue_w)]
    issue_fires_eff[cmd_slot] = cmd_slot_sel._select_internal(cmd_issue_fire_eff, issue_fires_eff[cmd_slot])
    cmd_payload_lane = cmd_uop_imm
    cmd_payload_lane = cmd_uop_op.__eq__(c(OP_BIOR, width=12))._select_internal(sl_vals[cmd_slot] | sr_vals[cmd_slot], cmd_payload_lane)
    cmd_payload_lane = cmd_uop_op.__eq__(c(OP_BLOAD, width=12))._select_internal(sl_vals[cmd_slot] + cmd_uop_imm, cmd_payload_lane)
    cmd_payload_lane = cmd_uop_op.__eq__(c(OP_BSTORE, width=12))._select_internal(sr_vals[cmd_slot], cmd_payload_lane)

    # Memory disambiguation/forwarding for the LSU lane (lane0).
    m.assign(lsu_probe_fire_i, issue_fires[0] & exs[0].is_load)
    m.assign(lsu_probe_addr_i, exs[0].addr)
    m.assign(rob_bank_in["lsu_probe_fire"], lsu_probe_fire_i)
    m.assign(rob_bank_in["lsu_probe_addr"], lsu_probe_addr_i)
    m.assign(rob_bank_in["lsu_probe_rob"], uop_robs[0])
    m.assign(rob_bank_in["lsu_probe_sub_head"], sub_head)

    lsu_stage_args = {
        "issue_fire_lane0_raw": issue_fires[0],
        "ex0_is_load": exs[0].is_load,
        "ex0_is_store": exs[0].is_store,
        "ex0_addr": exs[0].addr,
        "ex0_lsid": mux_by_uindex(m, idx=uop_robs[0], items=rob.load_store_id, default=c(0, width=32)),
        "lsid_issue_ptr": state.lsid_issue_ptr.out(),
        "commit_store_fire": commit_store_fire,
        "commit_store_addr": commit_store_addr,
        "commit_store_data": commit_store_data,
        "rob_older_store_pending_i": rob_bank["lsu_older_store_pending_lane0"],
        "rob_forward_hit_i": rob_bank["lsu_forward_hit_lane0"],
        "rob_forward_data_i": rob_bank["lsu_forward_data_lane0"],
        "stbuf_forward_hit_i": stbuf_lsu_fwd_hit,
        "stbuf_forward_data_i": stbuf_lsu_fwd_data,
    }

    lsu_stage = m.instance_auto(
        build_lsu_stage,
        name="lsu_stage",
        params={"rob_w": p.rob_w},
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
    lsid_issue_ptr_live = lsu_lsid_issue_advance._select_internal(lsid_issue_ptr_live + c(1, width=32), lsid_issue_ptr_live)
    lsid_complete_ptr_live = lsu_lsid_issue_advance._select_internal(lsid_complete_ptr_live + c(1, width=32), lsid_complete_ptr_live)
    # Redirect/flush drops in-flight younger memory ops: rebase LSID issue/complete
    # pointers to the current allocation head so stale IDs cannot deadlock LSU.
    lsid_issue_ptr_live = do_flush._select_internal(state.lsid_alloc_ctr.out(), lsid_issue_ptr_live)
    lsid_complete_ptr_live = do_flush._select_internal(state.lsid_alloc_ctr.out(), lsid_complete_ptr_live)

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
        load_addr = ld_mem._select_internal(exs[slot].addr, load_addr)

    # Provide LSU load requests to StbufStage for D-memory read arbitration.
    m.assign(lsu_load_fire_i, any_load_mem_fire)
    m.assign(lsu_load_addr_i, load_addr)

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
    # Optional fixed callframe addend.
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
    macro_is_fexit = macro_op.__eq__(c(OP_FEXIT, width=12))
    macro_is_fret_ra = macro_op.__eq__(c(OP_FRET_RA, width=12))
    macro_is_fret_stk = macro_op.__eq__(c(OP_FRET_STK, width=12))

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

    # StbufStage owns:
    # - committed-store buffering + MMIO decode
    # - single write port arbitration (macro store / commit WT / drain)
    # - D-memory read arbitration (macro restore-load > LSU load)
    m.assign(macro_load_fire_i, macro_uop_is_load)
    m.assign(macro_load_addr_i, macro_uop_addr)

    # Macro/template uop operand reads.
    cmap_now = [ren.cmap[i].out() for i in range(p.aregs)]
    macro_reg_tag = mux_by_uindex(m, idx=macro_uop_reg, items=cmap_now, default=tag0)
    macro_reg_val = mux_by_uindex(m, idx=macro_reg_tag, items=prf, default=consts.zero64)
    macro_sp_tag = ren.cmap[1].out()
    macro_sp_val = mux_by_uindex(m, idx=macro_sp_tag, items=prf, default=consts.zero64)
    macro_reg_is_gpr = macro_uop_reg.ult(c(24, width=6))
    macro_reg_not_zero = ~macro_uop_reg.__eq__(c(0, width=6))
    macro_store_fire = macro_uop_is_store & macro_reg_is_gpr & macro_reg_not_zero
    macro_store_addr = macro_uop_addr
    macro_store_data = macro_reg_val
    macro_store_size = c(8, width=4)

    m.assign(macro_store_fire_i, macro_store_fire)
    m.assign(macro_store_addr_i, macro_store_addr)
    m.assign(macro_store_data_i, macro_store_data)
    m.assign(macro_store_size_i, macro_store_size)

    dmem_rdata = dmem_rdata_i
    # FRET.STK must consume the loaded stack RA value. Only FRET.RA uses the
    # saved-RA bypass path.
    macro_restore_ra = macro_uop_is_load & op_is(macro_op, OP_FRET_RA) & macro_uop_reg.__eq__(c(10, width=6))
    macro_load_data_eff = macro_restore_ra._select_internal(state.macro_saved_ra.out(), macro_load_data)
    # FRET.STK can finish immediately after restoring RA (e.g. [ra~ra]).
    # In that case there is no standalone SETC_TGT phase; consume the restored
    # RA value as return target on the RA-load step.
    macro_setc_from_fret_stk_ra_load = macro_uop_is_load & macro_is_fret_stk & macro_uop_reg.__eq__(c(10, width=6))
    macro_setc_tgt_fire = macro_uop_is_setc_tgt | macro_setc_from_fret_stk_ra_load
    macro_setc_tgt_data = ret_ra_val
    macro_setc_tgt_data = macro_setc_from_fret_stk_ra_load._select_internal(macro_load_data_eff, macro_setc_tgt_data)
    macro_setc_tgt_data = (macro_uop_is_setc_tgt & macro_is_fret_stk)._select_internal(state.macro_saved_ra.out(), macro_setc_tgt_data)

    macro_is_restore = macro_active & (~macro_is_fentry)

    # Macro PRF write port (one write per cycle).
    macro_reg_write = macro_uop_is_load & macro_reg_is_gpr & macro_reg_not_zero
    macro_sp_write_init = macro_uop_is_sp_sub
    macro_sp_write_restore = macro_uop_is_sp_add

    macro_prf_we = macro_reg_write | macro_sp_write_init | macro_sp_write_restore
    macro_prf_tag = macro_sp_tag
    macro_prf_data = consts.zero64
    macro_prf_tag = macro_reg_write._select_internal(macro_reg_tag, macro_prf_tag)
    macro_prf_data = macro_reg_write._select_internal(macro_load_data_eff, macro_prf_data)
    macro_prf_data = macro_sp_write_restore._select_internal(macro_sp_val + macro_frame_adj, macro_prf_data)
    macro_prf_data = macro_sp_write_init._select_internal(macro_sp_val - macro_frame_adj, macro_prf_data)

    # Load result (uses dmem_rdata in the same cycle raddr is set).
    load8 = dmem_rdata._trunc(width=8)
    load16 = dmem_rdata._trunc(width=16)
    load32 = dmem_rdata._trunc(width=32)
    load_lb = load8._sext(width=64)
    load_lbu = load8._zext(width=64)
    load_lh = load16._sext(width=64)
    load_lhu = load16._zext(width=64)
    load_lw = load32._sext(width=64)
    load_lwu = load32._zext(width=64)
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
        load_val = op_is(op, OP_LB, OP_LBI, OP_HL_LB_PCR)._select_internal(load_lb, load_val)
        load_val = op_is(op, OP_LBU, OP_LBUI, OP_HL_LBU_PCR)._select_internal(load_lbu, load_val)
        load_val = op_is(op, OP_LH, OP_LHI, OP_HL_LH_PCR)._select_internal(load_lh, load_val)
        load_val = op_is(op, OP_LHU, OP_LHUI, OP_HL_LHU_PCR)._select_internal(load_lhu, load_val)
        load_val = op_is(op, OP_LWI, OP_C_LWI, OP_LW, OP_HL_LW_PCR)._select_internal(load_lw, load_val)
        load_val = op_is(op, OP_LWU, OP_LWUI, OP_HL_LWU_PCR)._select_internal(load_lwu, load_val)
        load_val = op_is(op, OP_LD, OP_LDI, OP_C_LDI, OP_HL_LD_PCR)._select_internal(load_ld, load_val)
        if slot == 0:
            fwd8 = lsu_forward_data_lane0._trunc(width=8)
            fwd16 = lsu_forward_data_lane0._trunc(width=16)
            fwd32 = lsu_forward_data_lane0._trunc(width=32)
            load_fwd = fwd32._sext(width=64)
            load_fwd = op_is(op, OP_LB, OP_LBI, OP_HL_LB_PCR)._select_internal(fwd8._sext(width=64), load_fwd)
            load_fwd = op_is(op, OP_LBU, OP_LBUI, OP_HL_LBU_PCR)._select_internal(fwd8._zext(width=64), load_fwd)
            load_fwd = op_is(op, OP_LH, OP_LHI, OP_HL_LH_PCR)._select_internal(fwd16._sext(width=64), load_fwd)
            load_fwd = op_is(op, OP_LHU, OP_LHUI, OP_HL_LHU_PCR)._select_internal(fwd16._zext(width=64), load_fwd)
            load_fwd = op_is(op, OP_LWI, OP_C_LWI, OP_LW, OP_HL_LW_PCR)._select_internal(fwd32._sext(width=64), load_fwd)
            load_fwd = op_is(op, OP_LWU, OP_LWUI, OP_HL_LWU_PCR)._select_internal(fwd32._zext(width=64), load_fwd)
            load_fwd = op_is(op, OP_LD, OP_LDI, OP_C_LDI, OP_HL_LD_PCR)._select_internal(lsu_forward_data_lane0, load_fwd)
            load_val = lsu_forward_active._select_internal(load_fwd, load_val)
        wb_value = load_fires[slot]._select_internal(load_val, exs[slot].alu)
        if slot == cmd_slot:
            wb_value = cmd_slot_sel._select_internal(cmd_payload_lane, wb_value)
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
    bru_target = consts.zero64
    bru_fire = consts.zero1
    bru_op = c(0, width=12)
    bru_rob = c(0, width=p.rob_w)
    bru_epoch = c(0, width=16)
    bru_checkpoint = c(0, width=6)
    bru_actual_take = consts.zero1
    bru_is_setc_cond = consts.zero1
    if p.bru_w > 0:
        bru_slot = p.lsu_w
        bru_fire = issue_fires_eff[bru_slot]
        bru_op = uop_ops[bru_slot]
        bru_rob = uop_robs[bru_slot]
        bru_epoch = mux_by_uindex(m, idx=bru_rob, items=rob.block_epoch, default=c(0, width=16))
        bru_checkpoint = mux_by_uindex(m, idx=bru_rob, items=rob.checkpoint_id, default=c(0, width=6))
        bru_actual_take = wb_values[bru_slot]._trunc(width=1)
        bru_is_setc_cond = is_setc_any(bru_op, op_is) & (~is_setc_tgt(bru_op, op_is))
        bru_validate_fire = bru_fire & bru_is_setc_cond
        bru_actual_take_dbg = bru_actual_take
        bru_pred_take_dbg = state.br_pred_take.out()
        bru_boundary_pc_dbg = state.br_base_pc.out()
        bru_target = state.br_base_pc.out() + state.br_off.out()
        bru_target = state.br_kind.out().__eq__(c(BK_RET, width=3))._select_internal(state.commit_tgt.out(), bru_target)

    pcbuf_args = {
        "clk": clk,
        "rst": rst,
        "lookup_pc_i": bru_target,
    }
    for slot in range(p.dispatch_w):
        pcbuf_args[f"wr_valid{slot}"] = pcbuf_wr_valid_i[slot]
        pcbuf_args[f"wr_pc{slot}"] = pcbuf_wr_pc_i[slot]
        pcbuf_args[f"wr_kind{slot}"] = pcbuf_wr_kind_i[slot]
        pcbuf_args[f"wr_target{slot}"] = pcbuf_wr_target_i[slot]
        pcbuf_args[f"wr_pred_take{slot}"] = pcbuf_wr_pred_take_i[slot]
        pcbuf_args[f"wr_is_bstart{slot}"] = pcbuf_wr_is_bstart_i[slot]
    pcbuf_stage = m.instance_auto(
        build_pcbuf_stage,
        name="pcbuf_stage",
        module_name="LinxCorePcBufStage",
        params={"depth": p.rob_depth, "idx_w": p.rob_w, "dispatch_w": p.dispatch_w},
        **pcbuf_args,
    )
    bru_target_known = pcbuf_stage["lookup_hit"]
    bru_target_is_bstart = pcbuf_stage["lookup_is_bstart"]

    if p.bru_w > 0:
        bru_validate_en = (
            (state.br_kind.out().__eq__(c(BK_COND, width=3)) | state.br_kind.out().__eq__(c(BK_RET, width=3)))
            & bru_target_known
            & bru_epoch.__eq__(state.br_epoch.out())
        )
        bru_mismatch = bru_fire & bru_is_setc_cond & bru_validate_en & (~bru_actual_take.__eq__(state.br_pred_take.out()))
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
            addr_match = rob.load_addr[i].out().__eq__(st_addr)
            cand = st_fire & ld_done & younger & addr_match
            better = (~hit) | dist.ult(hit_age)
            take = cand & better
            hit = take._select_internal(consts.one1, hit)
            hit_age = take._select_internal(dist, hit_age)
            hit_pc = take._select_internal(rob.pc[i].out(), hit_pc)

        set_this = hit & (~state.replay_pending.out()) & (~replay_set)
        replay_set = set_this._select_internal(consts.one1, replay_set)
        replay_set_store_rob = set_this._select_internal(st_rob, replay_set_store_rob)
        replay_set_pc = set_this._select_internal(hit_pc, replay_set_pc)

    lsu_violation_detected = replay_set

    # --- dispatch decode stage ---
    decode_stage = m.instance_auto(
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

    # Frontend-decoded BSTART metadata capture (pcbuf hierarchy inputs).
    for slot in range(p.dispatch_w):
        wr = f4_valid_i & disp_valids[slot] & disp_is_bstart[slot]
        m.assign(pcbuf_wr_valid_i[slot], wr)
        m.assign(pcbuf_wr_pc_i[slot], disp_pcs[slot])
        m.assign(pcbuf_wr_kind_i[slot], disp_boundary_kind[slot])
        m.assign(pcbuf_wr_target_i[slot], disp_boundary_target[slot])
        m.assign(pcbuf_wr_pred_take_i[slot], disp_pred_take[slot])
        m.assign(pcbuf_wr_is_bstart_i[slot], consts.one1)

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

    dispatch_stage = m.instance_auto(
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

    rename_stage = m.instance_auto(
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

    rename_free_after_dispatch = dispatch_fire._select_internal(ren.free_mask.out() & (~disp_alloc_mask), ren.free_mask.out())
    rename_ready_after_dispatch = dispatch_fire._select_internal(ren.ready_mask.out() & (~disp_alloc_mask), ren.ready_mask.out())

    # Snapshot rename/freelist state for branch/start-marker recovery.
    ckpt_write = consts.zero1
    ckpt_write_idx = c(0, width=ckpt_w)
    for slot in range(p.dispatch_w):
        lane_fire = dispatch_fire & disp_valids[slot]
        is_ckpt = lane_fire & disp_is_start_marker[slot]
        ckpt_idx = disp_checkpoint_ids[slot]._trunc(width=ckpt_w)
        ckpt_write = is_ckpt._select_internal(consts.one1, ckpt_write)
        ckpt_write_idx = is_ckpt._select_internal(ckpt_idx, ckpt_write_idx)

    for ci in range(ckpt_entries):
        ciw = c(ci, width=ckpt_w)
        do_ckpt = ckpt_write & ckpt_write_idx.__eq__(ciw)
        valid_next = ren.ckpt_valid[ci].out()
        valid_next = do_ckpt._select_internal(consts.one1, valid_next)
        ren.ckpt_valid[ci].set(valid_next)
        ren.ckpt_free_mask[ci].set(rename_free_after_dispatch, when=do_ckpt)
        ren.ckpt_ready_mask[ci].set(rename_ready_after_dispatch, when=do_ckpt)
        for r in range(p.aregs):
            ren.ckpt_smap[ci][r].set(smap_live[r], when=do_ckpt)

    flush_ckpt_idx = state.flush_checkpoint_id.out()._trunc(width=ckpt_w)
    flush_ckpt_valid = mux_by_uindex(m, idx=flush_ckpt_idx, items=ren.ckpt_valid, default=consts.zero1)
    flush_free_from_ckpt = mux_by_uindex(m, idx=flush_ckpt_idx, items=ren.ckpt_free_mask, default=ren.free_mask.out())
    flush_ready_from_ckpt = mux_by_uindex(m, idx=flush_ckpt_idx, items=ren.ckpt_ready_mask, default=ren.ready_mask.out())
    # Bring-up fallback: recover rename state from committed map on flush.
    # Checkpoint restore remains wired but disabled until checkpoint parity is stable.
    restore_from_ckpt = consts.zero1

    # --- ready table updates ---
    ready_next = ren.ready_mask.out()
    ready_next = dispatch_fire._select_internal(ready_next & (~disp_alloc_mask), ready_next)

    wb_set_mask = c(0, width=p.pregs)
    for slot in range(p.issue_w):
        wb_set_mask = wb_fire_has_dsts[slot]._select_internal(wb_set_mask | wb_onehots[slot], wb_set_mask)
    ready_next = ready_next | wb_set_mask
    ready_next = do_flush._select_internal(c((1 << p.pregs) - 1, width=p.pregs), ready_next)
    ready_next = restore_from_ckpt._select_internal(flush_ready_from_ckpt, ready_next)
    ren.ready_mask.set(ready_next)

    # PRF writes (up to issue_w writebacks per cycle).
    for i in range(p.pregs):
        we = consts.zero1
        wdata = consts.zero64
        for slot in range(p.issue_w):
            hit = wb_fire_has_dsts[slot] & wb_pdsts[slot].__eq__(c(i, width=p.ptag_w))
            we = we | hit
            wdata = hit._select_internal(wb_values[slot], wdata)
        hit_macro = macro_prf_we & macro_prf_tag.__eq__(c(i, width=p.ptag_w))
        we = we | hit_macro
        wdata = hit_macro._select_internal(macro_prf_data, wdata)
        prf[i].set(wdata, when=we)

    # --- ROB bank input connections ---
    m.assign(rob_bank_in["do_flush"], do_flush)
    m.assign(rob_bank_in["commit_fire"], commit_fire)
    m.assign(rob_bank_in["dispatch_fire"], dispatch_fire)
    m.assign(rob_bank_in["commit_count"], commit_count)
    m.assign(rob_bank_in["disp_count"], disp_count)
    for slot in range(p.dispatch_w):
        m.assign(rob_bank_in[f"disp_valid{slot}"], disp_valids[slot])
    for slot in range(p.commit_w):
        m.assign(rob_bank_in[f"commit_fire{slot}"], commit_fires[slot])
        m.assign(rob_bank_in[f"commit_idx{slot}"], commit_idxs[slot])

    disp_uop_uids = []
    disp_parent_uids = []
    disp_block_epochs = []
    for slot in range(p.dispatch_w):
        disp_uop_uids.append(disp_decode_uop_uids[slot])
        disp_parent_uids.append(consts.zero64)
        disp_block_epochs.append(dispatch_stage[f"disp_block_epoch{slot}"])
    for slot in range(p.dispatch_w):
        m.assign(rob_bank_in[f"disp_pc{slot}"], disp_pcs[slot])
        m.assign(rob_bank_in[f"disp_op{slot}"], disp_ops[slot])
        m.assign(rob_bank_in[f"disp_len{slot}"], disp_lens[slot])
        m.assign(rob_bank_in[f"disp_insn_raw{slot}"], disp_insn_raws[slot])
        m.assign(rob_bank_in[f"disp_checkpoint_id{slot}"], disp_checkpoint_ids[slot])
        m.assign(rob_bank_in[f"disp_dst_kind{slot}"], disp_dst_kind[slot])
        m.assign(rob_bank_in[f"disp_regdst{slot}"], disp_regdsts[slot])
        m.assign(rob_bank_in[f"disp_pdst{slot}"], disp_pdsts[slot])
        m.assign(rob_bank_in[f"disp_imm{slot}"], disp_imms[slot])
        m.assign(rob_bank_in[f"disp_is_store{slot}"], disp_is_store[slot])
        m.assign(rob_bank_in[f"disp_is_boundary{slot}"], disp_is_boundary[slot])
        m.assign(rob_bank_in[f"disp_is_bstart{slot}"], disp_is_bstart[slot])
        m.assign(rob_bank_in[f"disp_is_bstop{slot}"], disp_is_bstop[slot])
        m.assign(rob_bank_in[f"disp_boundary_kind{slot}"], disp_boundary_kind[slot])
        m.assign(rob_bank_in[f"disp_boundary_target{slot}"], disp_boundary_target[slot])
        m.assign(rob_bank_in[f"disp_pred_take{slot}"], disp_pred_take[slot])
        m.assign(rob_bank_in[f"disp_block_epoch{slot}"], disp_block_epochs[slot])
        m.assign(rob_bank_in[f"disp_block_uid{slot}"], disp_block_uids[slot])
        m.assign(rob_bank_in[f"disp_block_bid{slot}"], disp_block_bids[slot])
        m.assign(rob_bank_in[f"disp_load_store_id{slot}"], disp_load_store_ids[slot])
        m.assign(rob_bank_in[f"disp_resolved_d2{slot}"], disp_resolved_d2[slot])
        m.assign(rob_bank_in[f"disp_srcl{slot}"], disp_srcls[slot])
        m.assign(rob_bank_in[f"disp_srcr{slot}"], disp_srcrs[slot])
        m.assign(rob_bank_in[f"disp_uop_uid{slot}"], disp_uop_uids[slot])
        m.assign(rob_bank_in[f"disp_parent_uid{slot}"], disp_parent_uids[slot])
    for slot in range(p.issue_w):
        m.assign(rob_bank_in[f"wb_fire{slot}"], wb_fires[slot])
        m.assign(rob_bank_in[f"wb_rob{slot}"], wb_robs[slot])
        m.assign(rob_bank_in[f"wb_value{slot}"], wb_values[slot])
        m.assign(rob_bank_in[f"store_fire{slot}"], store_fires[slot])
        m.assign(rob_bank_in[f"load_fire{slot}"], load_fires[slot])
        m.assign(rob_bank_in[f"ex_addr{slot}"], exs[slot].addr)
        m.assign(rob_bank_in[f"ex_wdata{slot}"], exs[slot].wdata)
        m.assign(rob_bank_in[f"ex_size{slot}"], exs[slot].size)
        m.assign(rob_bank_in[f"ex_src0{slot}"], sl_vals[slot])
        m.assign(rob_bank_in[f"ex_src1{slot}"], sr_vals[slot])

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

        iq_stage = m.instance_auto(
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
        nxt = do_flush._select_internal(ren.cmap[i].out(), nxt)
        ckpt_smap_i = mux_by_uindex(
            m,
            idx=flush_ckpt_idx,
            items=[ren.ckpt_smap[ci][i] for ci in range(ckpt_entries)],
            default=ren.cmap[i].out(),
        )
        nxt = restore_from_ckpt._select_internal(ckpt_smap_i, nxt)
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

    rename_commit = m.instance_auto(
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
    free_next = do_flush._select_internal(free_recomputed, free_live)
    free_next = restore_from_ckpt._select_internal(flush_free_from_ckpt, free_next)
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
    br_corr_pending_n = do_flush._select_internal(consts.zero1, br_corr_pending_n)
    br_corr_pending_n = bru_corr_set._select_internal(consts.one1, br_corr_pending_n)
    br_corr_epoch_n = bru_corr_set._select_internal(bru_corr_epoch, br_corr_epoch_n)
    br_corr_take_n = bru_corr_set._select_internal(bru_corr_take, br_corr_take_n)
    br_corr_target_n = bru_corr_set._select_internal(bru_corr_target, br_corr_target_n)
    br_corr_checkpoint_id_n = bru_corr_set._select_internal(bru_corr_checkpoint_id, br_corr_checkpoint_id_n)
    corr_epoch_stale = br_corr_pending_n & (~br_corr_epoch_n.__eq__(br_epoch_live))
    br_corr_pending_n = corr_epoch_stale._select_internal(consts.zero1, br_corr_pending_n)

    br_corr_fault_pending_n = state.br_corr_fault_pending.out()
    br_corr_fault_rob_n = state.br_corr_fault_rob.out()
    br_corr_fault_pending_n = do_flush._select_internal(consts.zero1, br_corr_fault_pending_n)
    br_corr_fault_pending_n = bru_fault_set._select_internal(consts.one1, br_corr_fault_pending_n)
    br_corr_fault_rob_n = bru_fault_set._select_internal(bru_fault_rob, br_corr_fault_rob_n)

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

    commit_ctrl = m.instance_auto(
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
        trap_retire = trap_retire | (commit_fires[slot] & commit_idxs[slot].__eq__(state.trap_rob.out()))
    trap_retire = trap_retire & state.trap_pending.out()
    trap_pending_n = state.trap_pending.out()
    trap_rob_n = state.trap_rob.out()
    trap_cause_n = state.trap_cause.out()
    trap_pending_n = do_flush._select_internal(consts.zero1, trap_pending_n)
    trap_pending_n = trap_retire._select_internal(consts.zero1, trap_pending_n)
    trap_pending_n = br_corr_fault_pending_n._select_internal(consts.one1, trap_pending_n)
    trap_rob_n = br_corr_fault_pending_n._select_internal(br_corr_fault_rob_n, trap_rob_n)
    trap_cause_n = br_corr_fault_pending_n._select_internal(c(TRAP_BRU_RECOVERY_NOT_BSTART, width=32), trap_cause_n)
    state.trap_pending.set(trap_pending_n)
    state.trap_rob.set(trap_rob_n)
    state.trap_cause.set(trap_cause_n)
    br_corr_fault_pending_n = trap_retire._select_internal(consts.zero1, br_corr_fault_pending_n)
    state.br_corr_fault_pending.set(br_corr_fault_pending_n)
    state.br_corr_fault_rob.set(br_corr_fault_rob_n)
    state.halted.set(consts.one1, when=(commit_ctrl["halt_set"] | trap_retire))

    commit_cond_live = macro_setc_tgt_fire._select_internal(consts.one1, commit_cond_live)
    commit_tgt_live = macro_setc_tgt_fire._select_internal(macro_setc_tgt_data, commit_tgt_live)
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
    state.block_head.set(do_flush._select_internal(consts.one1, block_head_live))
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

    macro_active_n = do_flush._select_internal(consts.zero1, macro_active_n)
    macro_phase_n = do_flush._select_internal(ph_init, macro_phase_n)

    macro_active_n = macro_start._select_internal(consts.one1, macro_active_n)
    macro_phase_n = macro_start._select_internal(ph_init, macro_phase_n)
    macro_op_n = macro_start._select_internal(head_op, macro_op_n)
    macro_begin_n = macro_start._select_internal(head_macro_begin, macro_begin_n)
    macro_end_n = macro_start._select_internal(head_macro_end, macro_end_n)
    macro_stack_n = macro_start._select_internal(head_value, macro_stack_n)
    macro_reg_n = macro_start._select_internal(head_macro_begin, macro_reg_n)
    macro_i_n = macro_start._select_internal(c(0, width=6), macro_i_n)

    macro_phase_is_init = macro_phase_init
    macro_phase_is_mem = macro_phase_mem
    macro_phase_is_sp = macro_phase_sp
    macro_phase_is_setc = macro_phase_setc

    # Init: latch base SP and setup iteration.
    init_fire = macro_active & macro_phase_is_init
    sp_new_init = macro_sp_val - macro_frame_adj
    sp_new_restore = macro_sp_val + macro_frame_adj
    macro_sp_base_n = (init_fire & macro_is_fentry)._select_internal(sp_new_init, macro_sp_base_n)
    macro_sp_base_n = (init_fire & (macro_is_fexit | macro_is_fret_stk))._select_internal(sp_new_restore, macro_sp_base_n)
    macro_reg_n = init_fire._select_internal(macro_begin, macro_reg_n)
    macro_i_n = init_fire._select_internal(c(0, width=6), macro_i_n)
    macro_phase_n = (init_fire & (macro_is_fentry | macro_is_fexit | macro_is_fret_stk))._select_internal(ph_mem, macro_phase_n)
    macro_phase_n = (init_fire & macro_is_fret_ra)._select_internal(ph_sp, macro_phase_n)

    # Mem loop: iterate regs and offsets; save uses store port, restore uses load port.
    step_fire = ctu["loop_fire"]
    step_done = ctu["loop_done"]
    reg_next = ctu["loop_reg_next"]
    i_next = ctu["loop_i_next"]
    macro_reg_n = (step_fire & (~step_done))._select_internal(reg_next, macro_reg_n)
    macro_i_n = (step_fire & (~step_done))._select_internal(i_next, macro_i_n)

    # FRET.STK requires a SETC.TGT immediately after restoring RA.
    step_ra_restore = step_fire & macro_is_fret_stk & macro_uop_is_load & macro_uop_reg.__eq__(c(10, width=6))
    macro_phase_n = (step_ra_restore & (~step_done))._select_internal(ph_setc, macro_phase_n)

    done_macro = step_done & (macro_is_fentry | macro_is_fexit | macro_is_fret_stk | macro_is_fret_ra)
    macro_active_n = done_macro._select_internal(consts.zero1, macro_active_n)
    macro_phase_n = done_macro._select_internal(ph_init, macro_phase_n)

    # FRET.RA has an explicit SP_ADD phase before restore loads.
    sp_fire = macro_active & macro_phase_is_sp & macro_is_fret_ra
    macro_sp_base_n = sp_fire._select_internal(sp_new_restore, macro_sp_base_n)
    macro_phase_n = sp_fire._select_internal(ph_mem, macro_phase_n)

    # FRET.STK emits SETC.TGT as a standalone template uop between RA load
    # and the remaining restore-load loop.
    setc_fire = macro_active & macro_phase_is_setc & macro_is_fret_stk
    macro_phase_n = setc_fire._select_internal(ph_mem, macro_phase_n)

    macro_wait_n = state.macro_wait_commit.out()
    macro_wait_n = do_flush._select_internal(consts.zero1, macro_wait_n)
    macro_wait_n = macro_start._select_internal(consts.one1, macro_wait_n)
    macro_committed = consts.zero1
    for slot in range(p.commit_w):
        op = rob_ops[slot]
        fire = commit_fires[slot]
        macro_committed = macro_committed | (fire & op_is(op, OP_FENTRY, OP_FEXIT, OP_FRET_RA, OP_FRET_STK))
    macro_wait_n = macro_committed._select_internal(consts.zero1, macro_wait_n)

    # Suppress one synthetic C.BSTART boundary-dup right after a macro
    # commit handoff (macro commit advances to a new PC).
    macro_handoff = consts.zero1
    for slot in range(p.commit_w):
        op = rob_ops[slot]
        fire = commit_fires[slot]
        is_macro_evt = op_is(op, OP_FENTRY, OP_FEXIT, OP_FRET_RA, OP_FRET_STK)
        macro_handoff = macro_handoff | (fire & is_macro_evt & (~commit_next_pcs[slot].__eq__(commit_pcs[slot])))
    any_commit_fire = consts.zero1
    for slot in range(p.commit_w):
        any_commit_fire = any_commit_fire | commit_fires[slot]
    post_macro_handoff_n = state.post_macro_handoff.out()
    post_macro_handoff_n = do_flush._select_internal(consts.zero1, post_macro_handoff_n)
    post_macro_handoff_n = macro_handoff._select_internal(consts.one1, post_macro_handoff_n)
    post_macro_handoff_n = (any_commit_fire & (~macro_handoff))._select_internal(consts.zero1, post_macro_handoff_n)

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
    save_ra_fire = macro_store_fire & macro_uop_reg.__eq__(c(10, width=6))
    restore_ra_fire = macro_reg_write & macro_uop_reg.__eq__(c(10, width=6)) & macro_is_fret_stk
    macro_saved_ra_n = save_ra_fire._select_internal(macro_store_data, macro_saved_ra_n)
    macro_saved_ra_n = restore_ra_fire._select_internal(macro_load_data_eff, macro_saved_ra_n)
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
    macro_trace_prep = m.instance_auto(
        build_macro_trace_prep_stage,
        name="macro_trace_prep_stage",
        module_name="LinxCoreMacroTracePrepStage",
        params={"rob_w": p.rob_w},
        pc_i=state.pc.out(),
        rob_head_i=rob.head.out(),
        head_len_i=head_len,
        head_value_i=head_value,
        head_insn_raw_i=head_insn_raw,
        macro_op_i=macro_op,
        macro_uop_valid_i=macro_uop_valid,
        macro_stacksize_i=macro_stacksize,
        macro_frame_adj_i=macro_frame_adj,
        macro_reg_write_i=macro_reg_write,
        macro_uop_reg_i=macro_uop_reg,
        macro_uop_addr_i=macro_uop_addr,
        macro_uop_is_sp_sub_i=macro_uop_is_sp_sub,
        macro_uop_is_sp_add_i=macro_uop_is_sp_add,
        macro_uop_is_store_i=macro_uop_is_store,
        macro_uop_is_load_i=macro_uop_is_load,
        macro_uop_is_setc_tgt_i=macro_uop_is_setc_tgt,
        macro_reg_is_gpr_i=macro_reg_is_gpr,
        macro_reg_not_zero_i=macro_reg_not_zero,
        macro_store_fire_i=macro_store_fire,
        macro_store_data_i=macro_store_data,
        macro_load_data_eff_i=macro_load_data_eff,
        macro_sp_val_i=macro_sp_val,
        step_done_i=step_done,
        commit_tgt_live_i=commit_tgt_live,
    )
    macro_trace_fire = macro_trace_prep["macro_trace_fire"]
    macro_trace_pc = macro_trace_prep["macro_trace_pc"]
    macro_trace_rob = macro_trace_prep["macro_trace_rob"]
    macro_trace_op = macro_trace_prep["macro_trace_op"]
    macro_trace_val = macro_trace_prep["macro_trace_val"]
    macro_trace_len = macro_trace_prep["macro_trace_len"]
    macro_trace_insn = macro_trace_prep["macro_trace_insn_raw"]
    macro_trace_wb_valid = macro_trace_prep["macro_trace_wb_valid"]
    macro_trace_wb_rd = macro_trace_prep["macro_trace_wb_rd"]
    macro_trace_wb_data = macro_trace_prep["macro_trace_wb_data"]
    macro_trace_src0_valid = macro_trace_prep["macro_trace_src0_valid"]
    macro_trace_src0_reg = macro_trace_prep["macro_trace_src0_reg"]
    macro_trace_src0_data = macro_trace_prep["macro_trace_src0_data"]
    macro_trace_src1_valid = macro_trace_prep["macro_trace_src1_valid"]
    macro_trace_src1_reg = macro_trace_prep["macro_trace_src1_reg"]
    macro_trace_src1_data = macro_trace_prep["macro_trace_src1_data"]
    macro_trace_dst_valid = macro_trace_prep["macro_trace_dst_valid"]
    macro_trace_dst_reg = macro_trace_prep["macro_trace_dst_reg"]
    macro_trace_dst_data = macro_trace_prep["macro_trace_dst_data"]
    macro_trace_mem_valid = macro_trace_prep["macro_trace_mem_valid"]
    macro_trace_mem_is_store = macro_trace_prep["macro_trace_mem_is_store"]
    macro_trace_mem_addr = macro_trace_prep["macro_trace_mem_addr"]
    macro_trace_mem_wdata = macro_trace_prep["macro_trace_mem_wdata"]
    macro_trace_mem_rdata = macro_trace_prep["macro_trace_mem_rdata"]
    macro_trace_mem_size = macro_trace_prep["macro_trace_mem_size"]
    macro_trace_next_pc = macro_trace_prep["macro_trace_next_pc"]
    macro_shadow_fire = macro_trace_prep["macro_shadow_fire"]
    macro_shadow_emit_uop = macro_trace_prep["macro_shadow_emit_uop"]
    # Keep retire trace strictly instruction-driven; qemu-specific boundary-only
    # metadata commits are filtered in the lockstep runner.
    shadow_boundary_fire = consts.zero1
    shadow_boundary_fire1 = consts.zero1

    # `commit_fire` / `commit_op` / `commit_value` remain lane0-compatible.
    # These additional signals help debug multi-commit cycles where older
    # commits may not appear in a slot0-only log.
    max_commit_slots = 4
    def _slot(items, idx: int, default):
        return items[idx] if idx < len(items) else default

    commit_trace_args = {
        "shadow_boundary_fire": shadow_boundary_fire,
        "shadow_boundary_fire1": shadow_boundary_fire1,
        "trap_pending": state.trap_pending.out(),
        "trap_rob": state.trap_rob.out(),
        "trap_cause": state.trap_cause.out(),
        "macro_trace_fire": macro_trace_fire,
        "macro_trace_pc": macro_trace_pc,
        "macro_trace_rob": macro_trace_rob,
        "macro_trace_op": macro_trace_op,
        "macro_trace_val": macro_trace_val,
        "macro_trace_len": macro_trace_len,
        "macro_trace_insn_raw": macro_trace_insn,
        "macro_uop_uid": macro_uop_uid,
        "macro_uop_parent_uid": macro_uop_parent_uid,
        "macro_uop_template_kind": macro_uop_template_kind,
        "macro_trace_wb_valid": macro_trace_wb_valid,
        "macro_trace_wb_rd": macro_trace_wb_rd,
        "macro_trace_wb_data": macro_trace_wb_data,
        "macro_trace_src0_valid": macro_trace_src0_valid,
        "macro_trace_src0_reg": macro_trace_src0_reg,
        "macro_trace_src0_data": macro_trace_src0_data,
        "macro_trace_src1_valid": macro_trace_src1_valid,
        "macro_trace_src1_reg": macro_trace_src1_reg,
        "macro_trace_src1_data": macro_trace_src1_data,
        "macro_trace_dst_valid": macro_trace_dst_valid,
        "macro_trace_dst_reg": macro_trace_dst_reg,
        "macro_trace_dst_data": macro_trace_dst_data,
        "macro_trace_mem_valid": macro_trace_mem_valid,
        "macro_trace_mem_is_store": macro_trace_mem_is_store,
        "macro_trace_mem_addr": macro_trace_mem_addr,
        "macro_trace_mem_wdata": macro_trace_mem_wdata,
        "macro_trace_mem_rdata": macro_trace_mem_rdata,
        "macro_trace_mem_size": macro_trace_mem_size,
        "macro_trace_next_pc": macro_trace_next_pc,
        "macro_shadow_fire": macro_shadow_fire,
        "macro_shadow_emit_uop": macro_shadow_emit_uop,
    }

    for i in range(p.sq_entries):
        commit_trace_args[f"stbuf_valid{i}"] = stbuf_valid[i]
        commit_trace_args[f"stbuf_addr{i}"] = stbuf_addr[i]
        commit_trace_args[f"stbuf_data{i}"] = stbuf_data[i]

    commit_w = p.commit_w if p.commit_w <= max_commit_slots else max_commit_slots
    for slot in range(max_commit_slots):
        commit_trace_args[f"commit_fire{slot}_i"] = _slot(commit_fires, slot, consts.zero1)
        commit_trace_args[f"commit_pc{slot}_i"] = _slot(commit_pcs, slot, consts.zero64)
        commit_trace_args[f"commit_next_pc{slot}_i"] = _slot(commit_next_pcs, slot, consts.zero64)
        commit_trace_args[f"commit_idx{slot}_i"] = _slot(commit_idxs, slot, c(0, width=p.rob_w))
        commit_trace_args[f"rob_pc{slot}"] = _slot(rob_pcs, slot, consts.zero64)
        commit_trace_args[f"rob_op{slot}"] = _slot(rob_ops, slot, c(0, width=12))
        commit_trace_args[f"rob_value{slot}"] = _slot(rob_values, slot, consts.zero64)
        commit_trace_args[f"rob_len{slot}"] = _slot(rob_lens, slot, consts.zero3)
        commit_trace_args[f"rob_insn_raw{slot}"] = _slot(rob_insn_raws, slot, consts.zero64)
        commit_trace_args[f"rob_uop_uid{slot}"] = _slot(rob_uop_uids, slot, consts.zero64)
        commit_trace_args[f"rob_parent_uid{slot}"] = _slot(rob_parent_uids, slot, consts.zero64)
        commit_trace_args[f"rob_dst_kind{slot}"] = _slot(rob_dst_kinds, slot, c(0, width=2))
        commit_trace_args[f"rob_dst_areg{slot}"] = _slot(rob_dst_aregs, slot, c(0, width=6))
        commit_trace_args[f"rob_src0_valid{slot}"] = _slot(rob_src0_valids, slot, consts.zero1)
        commit_trace_args[f"rob_src0_reg{slot}"] = _slot(rob_src0_regs, slot, c(0, width=6))
        commit_trace_args[f"rob_src0_data{slot}"] = _slot(rob_src0_values, slot, consts.zero64)
        commit_trace_args[f"rob_src1_valid{slot}"] = _slot(rob_src1_valids, slot, consts.zero1)
        commit_trace_args[f"rob_src1_reg{slot}"] = _slot(rob_src1_regs, slot, c(0, width=6))
        commit_trace_args[f"rob_src1_data{slot}"] = _slot(rob_src1_values, slot, consts.zero64)
        commit_trace_args[f"rob_is_store{slot}"] = _slot(rob_is_stores, slot, consts.zero1)
        commit_trace_args[f"rob_store_addr{slot}"] = _slot(rob_st_addrs, slot, consts.zero64)
        commit_trace_args[f"rob_store_data{slot}"] = _slot(rob_st_datas, slot, consts.zero64)
        commit_trace_args[f"rob_store_size{slot}"] = _slot(rob_st_sizes, slot, consts.zero4)
        commit_trace_args[f"rob_is_load{slot}"] = _slot(rob_is_loads, slot, consts.zero1)
        commit_trace_args[f"rob_load_addr{slot}"] = _slot(rob_ld_addrs, slot, consts.zero64)
        commit_trace_args[f"rob_load_data{slot}"] = _slot(rob_ld_datas, slot, consts.zero64)
        commit_trace_args[f"rob_load_size{slot}"] = _slot(rob_ld_sizes, slot, consts.zero4)
        commit_trace_args[f"rob_checkpoint_id{slot}"] = _slot(rob_checkpoint_ids, slot, c(0, width=6))
        commit_trace_args[f"rob_load_store_id{slot}"] = _slot(rob_load_store_ids, slot, c(0, width=32))

        commit_trace_args[f"commit_block_uid{slot}_i"] = _slot(commit_block_uids, slot, consts.zero64)
        commit_trace_args[f"commit_block_bid{slot}_i"] = _slot(commit_block_bids, slot, consts.zero64)
        commit_trace_args[f"commit_core_id{slot}_i"] = _slot(commit_core_ids, slot, c(0, width=2))
        commit_trace_args[f"commit_is_bstart{slot}_i"] = _slot(commit_is_bstarts, slot, consts.zero1)
        commit_trace_args[f"commit_is_bstop{slot}_i"] = _slot(commit_is_bstops, slot, consts.zero1)

    commit_trace_stage = m.instance_auto(
        build_commit_trace_stage,
        name="commit_trace_stage",
        module_name="LinxCoreCommitTraceStage",
        params={"commit_w": commit_w, "max_commit_slots": max_commit_slots, "rob_w": p.rob_w, "sq_entries": p.sq_entries},
        **commit_trace_args,
    )
    for slot in range(max_commit_slots):
        m.output(f"commit_fire{slot}", commit_trace_stage[f"commit_fire{slot}"])
        m.output(f"commit_pc{slot}", commit_trace_stage[f"commit_pc{slot}"])
        m.output(f"commit_rob{slot}", commit_trace_stage[f"commit_rob{slot}"])
        m.output(f"commit_op{slot}", commit_trace_stage[f"commit_op{slot}"])
        m.output(f"commit_uop_uid{slot}", commit_trace_stage[f"commit_uop_uid{slot}"])
        m.output(f"commit_parent_uop_uid{slot}", commit_trace_stage[f"commit_parent_uop_uid{slot}"])
        m.output(f"commit_block_uid{slot}", commit_trace_stage[f"commit_block_uid{slot}"])
        m.output(f"commit_block_bid{slot}", commit_trace_stage[f"commit_block_bid{slot}"])
        m.output(f"commit_core_id{slot}", commit_trace_stage[f"commit_core_id{slot}"])
        m.output(f"commit_is_bstart{slot}", commit_trace_stage[f"commit_is_bstart{slot}"])
        m.output(f"commit_is_bstop{slot}", commit_trace_stage[f"commit_is_bstop{slot}"])
        m.output(f"commit_load_store_id{slot}", commit_trace_stage[f"commit_load_store_id{slot}"])
        m.output(f"commit_template_kind{slot}", commit_trace_stage[f"commit_template_kind{slot}"])
        m.output(f"commit_value{slot}", commit_trace_stage[f"commit_value{slot}"])
        m.output(f"commit_len{slot}", commit_trace_stage[f"commit_len{slot}"])
        m.output(f"commit_insn_raw{slot}", commit_trace_stage[f"commit_insn_raw{slot}"])
        m.output(f"commit_wb_valid{slot}", commit_trace_stage[f"commit_wb_valid{slot}"])
        m.output(f"commit_wb_rd{slot}", commit_trace_stage[f"commit_wb_rd{slot}"])
        m.output(f"commit_wb_data{slot}", commit_trace_stage[f"commit_wb_data{slot}"])
        m.output(f"commit_src0_valid{slot}", commit_trace_stage[f"commit_src0_valid{slot}"])
        m.output(f"commit_src0_reg{slot}", commit_trace_stage[f"commit_src0_reg{slot}"])
        m.output(f"commit_src0_data{slot}", commit_trace_stage[f"commit_src0_data{slot}"])
        m.output(f"commit_src1_valid{slot}", commit_trace_stage[f"commit_src1_valid{slot}"])
        m.output(f"commit_src1_reg{slot}", commit_trace_stage[f"commit_src1_reg{slot}"])
        m.output(f"commit_src1_data{slot}", commit_trace_stage[f"commit_src1_data{slot}"])
        m.output(f"commit_dst_valid{slot}", commit_trace_stage[f"commit_dst_valid{slot}"])
        m.output(f"commit_dst_reg{slot}", commit_trace_stage[f"commit_dst_reg{slot}"])
        m.output(f"commit_dst_data{slot}", commit_trace_stage[f"commit_dst_data{slot}"])
        m.output(f"commit_mem_valid{slot}", commit_trace_stage[f"commit_mem_valid{slot}"])
        m.output(f"commit_mem_is_store{slot}", commit_trace_stage[f"commit_mem_is_store{slot}"])
        m.output(f"commit_mem_addr{slot}", commit_trace_stage[f"commit_mem_addr{slot}"])
        m.output(f"commit_mem_wdata{slot}", commit_trace_stage[f"commit_mem_wdata{slot}"])
        m.output(f"commit_mem_rdata{slot}", commit_trace_stage[f"commit_mem_rdata{slot}"])
        m.output(f"commit_mem_size{slot}", commit_trace_stage[f"commit_mem_size{slot}"])
        m.output(f"commit_trap_valid{slot}", commit_trace_stage[f"commit_trap_valid{slot}"])
        m.output(f"commit_trap_cause{slot}", commit_trace_stage[f"commit_trap_cause{slot}"])
        m.output(f"commit_next_pc{slot}", commit_trace_stage[f"commit_next_pc{slot}"])
        m.output(f"commit_checkpoint_id{slot}", commit_trace_stage[f"commit_checkpoint_id{slot}"])
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
    # Exported flush indicator for cross-module kill/clear (BISQ/BROB/TMA/etc).
    m.output("do_flush", do_flush)
    m.output("flush_bid", state.flush_bid.out())
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
    head_wait = m.instance_auto(
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
            valid = hit._select_internal(consts.one1, valid)
            rob_idx = hit._select_internal(iq_regs.rob[i].out(), rob_idx)
            pc = hit._select_internal(iq_regs.pc[i].out(), pc)
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
    cmd_kind = cmd_op.__eq__(c(OP_BIOR, width=12))._select_internal(c(1, width=3), cmd_kind)
    cmd_kind = cmd_op.__eq__(c(OP_BLOAD, width=12))._select_internal(c(2, width=3), cmd_kind)
    cmd_kind = cmd_op.__eq__(c(OP_BSTORE, width=12))._select_internal(c(3, width=3), cmd_kind)
    cmd_payload = cmd_payload_lane
    cmd_tile = cmd_payload._trunc(width=6)
    cmd_src_rob = cmd_uop_rob
    cmd_bid = mux_by_uindex(m, idx=cmd_uop_rob, items=rob.block_bid, default=consts.zero64)
    cmd_tag = state.cycles.out()._trunc(width=8)

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
