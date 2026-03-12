from __future__ import annotations

from pycircuit import Circuit, module

from common.isa import BK_FALL


@module(name="LinxCoreBlockMetaStep")
def build_block_meta_step(m: Circuit) -> None:
    fire_i = m.input("fire_i", width=1)
    redirect_i = m.input("redirect_i", width=1)
    is_boundary_i = m.input("is_boundary_i", width=1)
    is_bstart_head_i = m.input("is_bstart_head_i", width=1)
    is_bstart_mid_i = m.input("is_bstart_mid_i", width=1)
    is_bstop_i = m.input("is_bstop_i", width=1)
    is_macro_i = m.input("is_macro_i", width=1)
    br_take_eff_i = m.input("br_take_eff_i", width=1)
    pc_this_i = m.input("pc_this_i", width=64)
    boundary_kind_i = m.input("boundary_kind_i", width=3)
    boundary_target_i = m.input("boundary_target_i", width=64)
    pred_take_i = m.input("pred_take_i", width=1)
    block_uid_this_i = m.input("block_uid_this_i", width=64)
    block_bid_this_i = m.input("block_bid_this_i", width=64)
    active_block_uid_i = m.input("active_block_uid_i", width=64)
    active_block_bid_i = m.input("active_block_bid_i", width=64)
    br_kind_i = m.input("br_kind_i", width=3)
    br_base_i = m.input("br_base_i", width=64)
    br_off_i = m.input("br_off_i", width=64)
    br_pred_take_i = m.input("br_pred_take_i", width=1)
    block_head_i = m.input("block_head_i", width=1)

    c = m.const

    bstart_commit = fire_i & (is_bstart_head_i | is_bstart_mid_i)
    enter_new_block = bstart_commit & (~br_take_eff_i)
    macro_enter = fire_i & is_macro_i & (~br_take_eff_i)
    boundary_taken = fire_i & is_boundary_i & br_take_eff_i
    bstop_commit = fire_i & is_bstop_i
    meta_off = boundary_target_i - pc_this_i

    active_block_uid_o = bstart_commit._select_internal(block_uid_this_i, active_block_uid_i)
    active_block_bid_o = bstart_commit._select_internal(block_bid_this_i, active_block_bid_i)
    active_block_bid_o = bstop_commit._select_internal(c(0, width=64), active_block_bid_o)

    br_kind_o = br_kind_i
    br_base_o = br_base_i
    br_off_o = br_off_i
    br_pred_take_o = br_pred_take_i

    br_kind_o = boundary_taken._select_internal(c(BK_FALL, width=3), br_kind_o)
    br_base_o = boundary_taken._select_internal(pc_this_i, br_base_o)
    br_off_o = boundary_taken._select_internal(c(0, width=64), br_off_o)
    br_pred_take_o = boundary_taken._select_internal(c(0, width=1), br_pred_take_o)

    br_kind_o = enter_new_block._select_internal(boundary_kind_i, br_kind_o)
    br_base_o = enter_new_block._select_internal(pc_this_i, br_base_o)
    br_off_o = enter_new_block._select_internal(meta_off, br_off_o)
    br_pred_take_o = enter_new_block._select_internal(pred_take_i, br_pred_take_o)

    br_kind_o = macro_enter._select_internal(c(BK_FALL, width=3), br_kind_o)
    br_base_o = macro_enter._select_internal(pc_this_i, br_base_o)
    br_off_o = macro_enter._select_internal(c(0, width=64), br_off_o)
    br_pred_take_o = macro_enter._select_internal(c(0, width=1), br_pred_take_o)

    br_kind_o = bstop_commit._select_internal(c(BK_FALL, width=3), br_kind_o)
    br_base_o = bstop_commit._select_internal(pc_this_i, br_base_o)
    br_off_o = bstop_commit._select_internal(c(0, width=64), br_off_o)
    br_pred_take_o = bstop_commit._select_internal(c(0, width=1), br_pred_take_o)

    block_head_o = block_head_i
    block_head_o = (fire_i & (is_boundary_i | redirect_i))._select_internal(c(1, width=1), block_head_o)
    block_head_o = (fire_i & is_bstart_head_i)._select_internal(c(0, width=1), block_head_o)

    m.output("active_block_uid_o", active_block_uid_o)
    m.output("active_block_bid_o", active_block_bid_o)
    m.output("br_kind_o", br_kind_o)
    m.output("br_base_o", br_base_o)
    m.output("br_off_o", br_off_o)
    m.output("br_pred_take_o", br_pred_take_o)
    m.output("block_head_o", block_head_o)


build_block_meta_step.__pycircuit_name__ = "LinxCoreBlockMetaStep"
