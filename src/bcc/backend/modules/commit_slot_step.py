from __future__ import annotations

from pycircuit import Circuit, module

from common.isa import (
    BK_CALL,
    BK_COND,
    BK_DIRECT,
    BK_FALL,
    BK_ICALL,
    BK_IND,
    BK_RET,
    OP_C_SETC_EQ,
    OP_C_SETC_NE,
    OP_C_SETC_TGT,
    OP_EBREAK,
    OP_FENTRY,
    OP_FEXIT,
    OP_FRET_RA,
    OP_FRET_STK,
    OP_INVALID,
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
)
from ..commit import _op_is
from .block_meta_step import build_block_meta_step


@module(name="LinxCoreCommitSlotStep")
def build_commit_slot_step(m: Circuit) -> None:
    c = m.const

    can_run = m.input("can_run_i", width=1)
    allow_macro = m.input("allow_macro_i", width=1)

    commit_allow = m.input("commit_allow_i", width=1)
    commit_count = m.input("commit_count_i", width=3)

    redirect_valid = m.input("redirect_valid_i", width=1)
    redirect_pc = m.input("redirect_pc_i", width=64)
    redirect_bid = m.input("redirect_bid_i", width=64)
    redirect_checkpoint_id = m.input("redirect_checkpoint_id_i", width=6)
    redirect_from_corr = m.input("redirect_from_corr_i", width=1)
    replay_redirect_fire = m.input("replay_redirect_fire_i", width=1)

    commit_store_seen = m.input("commit_store_seen_i", width=1)
    stbuf_has_space = m.input("stbuf_has_space_i", width=1)

    brob_retire_fire = m.input("brob_retire_fire_i", width=1)
    brob_retire_bid = m.input("brob_retire_bid_i", width=64)

    pc_live = m.input("pc_live_i", width=64)
    commit_cond = m.input("commit_cond_i", width=1)
    commit_tgt = m.input("commit_tgt_i", width=64)
    br_kind = m.input("br_kind_i", width=3)
    br_epoch = m.input("br_epoch_i", width=16)
    br_base = m.input("br_base_i", width=64)
    br_off = m.input("br_off_i", width=64)
    br_pred_take = m.input("br_pred_take_i", width=1)
    active_block_uid = m.input("active_block_uid_i", width=64)
    active_block_bid = m.input("active_block_bid_i", width=64)
    block_head = m.input("block_head_i", width=1)

    br_corr_pending = m.input("br_corr_pending_i", width=1)
    br_corr_epoch = m.input("br_corr_epoch_i", width=16)
    br_corr_take = m.input("br_corr_take_i", width=1)
    br_corr_target = m.input("br_corr_target_i", width=64)
    br_corr_checkpoint_id = m.input("br_corr_checkpoint_id_i", width=6)

    replay_pending = m.input("replay_pending_i", width=1)
    replay_store_rob = m.input("replay_store_rob_i", width=6)
    replay_pc = m.input("replay_pc_i", width=64)
    ret_ra_val = m.input("ret_ra_val_i", width=64)

    brob_active_allocated = m.input("brob_active_allocated_i", width=1)
    brob_active_ready = m.input("brob_active_ready_i", width=1)
    brob_active_exception = m.input("brob_active_exception_i", width=1)

    rob_valid = m.input("rob_valid_i", width=1)
    rob_done = m.input("rob_done_i", width=1)
    rob_pc = m.input("rob_pc_i", width=64)
    rob_op = m.input("rob_op_i", width=12)
    rob_len = m.input("rob_len_i", width=3)
    rob_value = m.input("rob_value_i", width=64)
    rob_is_store = m.input("rob_is_store_i", width=1)
    rob_store_addr = m.input("rob_store_addr_i", width=64)
    rob_store_data = m.input("rob_store_data_i", width=64)
    rob_store_size = m.input("rob_store_size_i", width=4)
    rob_is_bstart = m.input("rob_is_bstart_i", width=1)
    rob_is_bstop = m.input("rob_is_bstop_i", width=1)
    rob_boundary_kind = m.input("rob_boundary_kind_i", width=3)
    rob_boundary_target = m.input("rob_boundary_target_i", width=64)
    rob_pred_take = m.input("rob_pred_take_i", width=1)
    rob_block_uid = m.input("rob_block_uid_i", width=64)
    rob_block_bid = m.input("rob_block_bid_i", width=64)
    rob_checkpoint_id = m.input("rob_checkpoint_id_i", width=6)
    commit_idx = m.input("commit_idx_i", width=6)

    pc_this = rob_valid._select_internal(rob_pc, pc_live)

    op = rob_op
    ln = rob_len
    val = rob_value

    is_macro = _op_is(m, op, OP_FENTRY, OP_FEXIT, OP_FRET_RA, OP_FRET_STK)
    is_bstart = rob_is_bstart
    is_bstop = rob_is_bstop
    bstart_bid_diff = ~(rob_block_bid.__eq__(active_block_bid))
    is_bstart_head = is_bstart & (block_head | bstart_bid_diff)
    is_bstart_mid = is_bstart & (~is_bstart_head)
    is_boundary = is_bstart_mid | is_bstop | is_macro

    br_is_fall = br_kind.__eq__(c(BK_FALL, width=3))
    br_is_cond = br_kind.__eq__(c(BK_COND, width=3))
    br_is_call = br_kind.__eq__(c(BK_CALL, width=3))
    br_is_ret = br_kind.__eq__(c(BK_RET, width=3))
    br_is_direct = br_kind.__eq__(c(BK_DIRECT, width=3))
    br_is_ind = br_kind.__eq__(c(BK_IND, width=3))
    br_is_icall = br_kind.__eq__(c(BK_ICALL, width=3))

    br_target = br_base + br_off
    br_target = (br_is_ret | br_is_ind | br_is_icall)._select_internal(commit_tgt, br_target)
    br_target = (~(br_is_ret | br_is_ind | br_is_icall) & (~commit_tgt.__eq__(c(0, width=64))))._select_internal(commit_tgt, br_target)

    br_take = br_is_call | br_is_direct | br_is_ind | br_is_icall | (br_is_cond & commit_cond) | (br_is_ret & commit_cond)

    fire_pre = can_run & commit_allow & rob_valid & rob_done
    fire_pre = fire_pre & (allow_macro | (~is_macro))

    corr_epoch_match = br_corr_pending & br_corr_epoch.__eq__(br_epoch)
    corr_for_boundary = fire_pre & is_boundary & corr_epoch_match
    br_take_eff = corr_for_boundary._select_internal(br_corr_take, br_take)
    br_target_eff = corr_for_boundary._select_internal(br_corr_target, br_target)

    pc_inc = pc_this + ln._zext(width=64)
    boundary_fallthrough = is_bstart_mid._select_internal(pc_this, pc_inc)
    pc_next = is_boundary._select_internal(br_take_eff._select_internal(br_target_eff, boundary_fallthrough), pc_inc)

    is_halt = _op_is(m, op, OP_EBREAK, OP_INVALID)
    redirect_pre = fire_pre & ((is_boundary & br_take_eff) | is_bstart_mid)
    replay_redirect_pre = fire_pre & rob_is_store & replay_pending & commit_idx.__eq__(replay_store_rob)
    replay_redirect_fire = replay_redirect_pre._select_internal(c(1, width=1), replay_redirect_fire)

    is_fret = _op_is(m, op, OP_FRET_RA, OP_FRET_STK)
    fret_redirect = fire_pre & is_fret & (~redirect_pre)
    pc_next = fret_redirect._select_internal(ret_ra_val, pc_next)
    redirect_pre = redirect_pre | fret_redirect
    pc_next = replay_redirect_pre._select_internal(replay_pc, pc_next)
    redirect_pre = redirect_pre | replay_redirect_pre

    fire = fire_pre & ((~rob_is_store) | ((~commit_store_seen) & stbuf_has_space))
    bstop_gate_ok = (~is_bstop) | (~brob_active_allocated) | brob_active_ready | brob_active_exception
    fire = fire & bstop_gate_ok
    store_commit = fire & rob_is_store
    stop = redirect_pre | (fire & is_halt)

    commit_count = commit_count + fire._zext(width=3)

    redirect_valid = redirect_pre._select_internal(c(1, width=1), redirect_valid)
    redirect_pc = redirect_pre._select_internal(pc_next, redirect_pc)
    redirect_bid = redirect_pre._select_internal(active_block_bid, redirect_bid)
    redirect_ckpt_sel = corr_for_boundary._select_internal(br_corr_checkpoint_id, rob_checkpoint_id)
    redirect_checkpoint_id = redirect_pre._select_internal(redirect_ckpt_sel, redirect_checkpoint_id)
    redirect_from_corr = (redirect_pre & corr_for_boundary)._select_internal(c(1, width=1), redirect_from_corr)

    commit_store_seen = store_commit._select_internal(c(1, width=1), commit_store_seen)

    bstop_commit = fire & is_bstop
    bstop_take = bstop_commit & (~brob_retire_fire)
    brob_retire_fire = bstop_take._select_internal(c(1, width=1), brob_retire_fire)
    brob_retire_bid = bstop_take._select_internal(active_block_bid, brob_retire_bid)

    op_setc_any = _op_is(
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
    op_setc_tgt = _op_is(m, op, OP_C_SETC_TGT)
    commit_cond = (fire & is_boundary)._select_internal(c(0, width=1), commit_cond)
    commit_tgt = (fire & is_boundary)._select_internal(c(0, width=64), commit_tgt)
    commit_cond = (fire & op_setc_any)._select_internal(val._trunc(width=1), commit_cond)
    commit_tgt = (fire & op_setc_tgt)._select_internal(val, commit_tgt)
    commit_cond = (fire & op_setc_tgt)._select_internal(c(1, width=1), commit_cond)

    block_meta_step = m.instance_auto(
        build_block_meta_step,
        name="block_meta_step",
        fire_i=fire,
        redirect_i=redirect_pre,
        is_boundary_i=is_boundary,
        is_bstart_head_i=is_bstart_head,
        is_bstart_mid_i=is_bstart_mid,
        is_bstop_i=is_bstop,
        is_macro_i=is_macro,
        br_take_eff_i=br_take_eff,
        pc_this_i=pc_this,
        boundary_kind_i=rob_boundary_kind,
        boundary_target_i=rob_boundary_target,
        pred_take_i=rob_pred_take,
        block_uid_this_i=rob_block_uid,
        block_bid_this_i=rob_block_bid,
        active_block_uid_i=active_block_uid,
        active_block_bid_i=active_block_bid,
        br_kind_i=br_kind,
        br_base_i=br_base,
        br_off_i=br_off,
        br_pred_take_i=br_pred_take,
        block_head_i=block_head,
    )
    active_block_uid = block_meta_step["active_block_uid_o"]
    active_block_bid = block_meta_step["active_block_bid_o"]
    br_kind = block_meta_step["br_kind_o"]
    br_base = block_meta_step["br_base_o"]
    br_off = block_meta_step["br_off_o"]
    br_pred_take = block_meta_step["br_pred_take_o"]
    block_head = block_meta_step["block_head_o"]

    clear_corr_boundary = fire & is_boundary & corr_epoch_match
    br_corr_pending = clear_corr_boundary._select_internal(c(0, width=1), br_corr_pending)
    br_epoch = (fire & is_boundary)._select_internal(br_epoch + c(1, width=16), br_epoch)

    pc_live = fire._select_internal(pc_next, pc_live)
    commit_allow = commit_allow & fire & (~stop)

    bstart_commit = fire & is_bstart
    bstop_commit = fire & is_bstop

    m.output("commit_fire_o", fire)
    m.output("commit_allow_o", commit_allow)
    m.output("commit_count_o", commit_count)

    m.output("pc_o", pc_this)
    m.output("pc_next_o", pc_next)

    m.output("redirect_valid_o", redirect_valid)
    m.output("redirect_pc_o", redirect_pc)
    m.output("redirect_bid_o", redirect_bid)
    m.output("redirect_checkpoint_id_o", redirect_checkpoint_id)
    m.output("redirect_from_corr_o", redirect_from_corr)
    m.output("replay_redirect_fire_o", replay_redirect_fire)

    m.output("commit_store_seen_o", commit_store_seen)
    m.output("brob_retire_fire_o", brob_retire_fire)
    m.output("brob_retire_bid_o", brob_retire_bid)

    m.output("pc_live_o", pc_live)
    m.output("commit_cond_o", commit_cond)
    m.output("commit_tgt_o", commit_tgt)
    m.output("br_kind_o", br_kind)
    m.output("br_epoch_o", br_epoch)
    m.output("br_base_o", br_base)
    m.output("br_off_o", br_off)
    m.output("br_pred_take_o", br_pred_take)
    m.output("active_block_uid_o", active_block_uid)
    m.output("active_block_bid_o", active_block_bid)
    m.output("block_head_o", block_head)
    m.output("br_corr_pending_o", br_corr_pending)

    m.output("commit_is_bstart_o", bstart_commit)
    m.output("commit_is_bstop_o", bstop_commit)
    m.output("commit_block_uid_o", rob_block_uid)
    m.output("commit_block_bid_o", rob_block_bid)
    m.output("commit_store_addr_o", rob_store_addr)
    m.output("commit_store_data_o", rob_store_data)
    m.output("commit_store_size_o", rob_store_size)
