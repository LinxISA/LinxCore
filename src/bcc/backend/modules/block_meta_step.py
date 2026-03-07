from __future__ import annotations

from pycircuit import Circuit, module

from common.isa import BK_FALL


@module(name="LinxCoreBlockMetaStep")
def build_block_meta_step(m: Circuit) -> None:
    c = m.const

    fire = m.input("fire_i", width=1)
    redirect = m.input("redirect_i", width=1)
    is_boundary = m.input("is_boundary_i", width=1)
    is_bstart_head = m.input("is_bstart_head_i", width=1)
    is_bstart_mid = m.input("is_bstart_mid_i", width=1)
    is_bstop = m.input("is_bstop_i", width=1)
    is_macro = m.input("is_macro_i", width=1)
    br_take_eff = m.input("br_take_eff_i", width=1)
    pc_this = m.input("pc_this_i", width=64)
    boundary_kind = m.input("boundary_kind_i", width=3)
    boundary_target = m.input("boundary_target_i", width=64)
    pred_take = m.input("pred_take_i", width=1)
    block_uid_this = m.input("block_uid_this_i", width=64)
    block_bid_this = m.input("block_bid_this_i", width=64)

    active_block_uid = m.input("active_block_uid_i", width=64)
    active_block_bid = m.input("active_block_bid_i", width=64)
    br_kind = m.input("br_kind_i", width=3)
    br_base = m.input("br_base_i", width=64)
    br_off = m.input("br_off_i", width=64)
    br_pred_take = m.input("br_pred_take_i", width=1)
    block_head = m.input("block_head_i", width=1)

    bstart_commit = fire & (is_bstart_head | is_bstart_mid)
    bstart_commit_has_bid = bstart_commit & (~block_bid_this.__eq__(c(0, width=64)))
    next_active_block_uid = bstart_commit_has_bid._select_internal(block_uid_this, active_block_uid)
    next_active_block_bid = bstart_commit_has_bid._select_internal(block_bid_this, active_block_bid)
    next_active_block_bid = (fire & is_bstop)._select_internal(c(0, width=64), next_active_block_bid)

    # Head BSTART must (re)install decoded metadata. Mid-block markers only
    # install on not-taken progression.
    enter_new_block_head = fire & is_bstart_head
    enter_new_block_mid = fire & is_bstart_mid & (~br_take_eff)
    enter_new_block = enter_new_block_head | enter_new_block_mid
    meta_off = boundary_target - pc_this

    # After a taken boundary, clear the live metadata to a conservative
    # fall-through block until the next BSTART head re-installs metadata.
    took_boundary = fire & is_boundary & br_take_eff
    next_br_kind = took_boundary._select_internal(c(BK_FALL, width=3), br_kind)
    next_br_base = took_boundary._select_internal(pc_this, br_base)
    next_br_off = took_boundary._select_internal(c(0, width=64), br_off)
    next_br_pred_take = took_boundary._select_internal(c(0, width=1), br_pred_take)

    next_br_kind = enter_new_block._select_internal(boundary_kind, next_br_kind)
    next_br_base = enter_new_block._select_internal(pc_this, next_br_base)
    next_br_off = enter_new_block._select_internal(meta_off, next_br_off)
    next_br_pred_take = enter_new_block._select_internal(pred_take, next_br_pred_take)

    # Macro blocks (FENTRY/FEXIT/FRET.*) are standalone fall-through blocks.
    macro_enter = fire & is_macro & (~br_take_eff)
    next_br_kind = macro_enter._select_internal(c(BK_FALL, width=3), next_br_kind)
    next_br_base = macro_enter._select_internal(pc_this, next_br_base)
    next_br_off = macro_enter._select_internal(c(0, width=64), next_br_off)
    next_br_pred_take = macro_enter._select_internal(c(0, width=1), next_br_pred_take)

    next_br_kind = (fire & is_bstop)._select_internal(c(BK_FALL, width=3), next_br_kind)
    next_br_base = (fire & is_bstop)._select_internal(pc_this, next_br_base)
    next_br_off = (fire & is_bstop)._select_internal(c(0, width=64), next_br_off)
    next_br_pred_take = (fire & is_bstop)._select_internal(c(0, width=1), next_br_pred_take)

    # Next committed instruction becomes block-head after boundary/redirect;
    # head marker clears this state.
    next_block_head = (fire & (is_boundary | redirect))._select_internal(c(1, width=1), block_head)
    next_block_head = (fire & is_bstart_head)._select_internal(c(0, width=1), next_block_head)

    m.output("active_block_uid_o", next_active_block_uid)
    m.output("active_block_bid_o", next_active_block_bid)
    m.output("br_kind_o", next_br_kind)
    m.output("br_base_o", next_br_base)
    m.output("br_off_o", next_br_off)
    m.output("br_pred_take_o", next_br_pred_take)
    m.output("block_head_o", next_block_head)
