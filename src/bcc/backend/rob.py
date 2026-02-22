from __future__ import annotations

from pycircuit import Circuit, module

from common.isa import (
    OP_BSTART_STD_CALL,
    OP_BSTART_STD_COND,
    OP_BSTART_STD_DIRECT,
    OP_BSTART_STD_FALL,
    OP_C_BSTART_COND,
    OP_C_BSTART_DIRECT,
    OP_C_BSTART_STD,
    OP_FENTRY,
    OP_FEXIT,
    OP_FRET_RA,
    OP_FRET_STK,
)


def is_macro_op(op, op_is):
    return op_is(op, OP_FENTRY, OP_FEXIT, OP_FRET_RA, OP_FRET_STK)


def is_start_marker_op(op, op_is):
    return op_is(
        op,
        OP_C_BSTART_STD,
        OP_C_BSTART_COND,
        OP_C_BSTART_DIRECT,
        OP_BSTART_STD_FALL,
        OP_BSTART_STD_DIRECT,
        OP_BSTART_STD_COND,
        OP_BSTART_STD_CALL,
    ) | is_macro_op(op, op_is)


@module(name="LinxCoreRobCtrlStage")
def build_rob_ctrl_stage(
    m: Circuit,
    *,
    dispatch_w: int = 4,
    rob_w: int = 6,
) -> None:
    c = m.const
    do_flush = m.input("do_flush", width=1)
    commit_fire = m.input("commit_fire", width=1)
    dispatch_fire = m.input("dispatch_fire", width=1)
    rob_head = m.input("rob_head", width=rob_w)
    rob_tail = m.input("rob_tail", width=rob_w)
    rob_count = m.input("rob_count", width=rob_w + 1)
    commit_count = m.input("commit_count", width=3)
    disp_count = m.input("disp_count", width=3)

    disp_valids = []
    for slot in range(dispatch_w):
        disp_valids.append(m.input(f"disp_valid{slot}", width=1))

    disp_rob_idxs = []
    disp_fires = []
    for slot in range(dispatch_w):
        disp_rob_idxs.append(rob_tail + c(slot, width=rob_w))
        disp_fires.append(dispatch_fire & disp_valids[slot])

    head_next = rob_head
    tail_next = rob_tail
    count_next = rob_count

    head_next = do_flush.select(c(0, width=rob_w), head_next)
    tail_next = do_flush.select(c(0, width=rob_w), tail_next)
    count_next = do_flush.select(c(0, width=rob_w + 1), count_next)

    inc_head = commit_fire & (~do_flush)
    inc_tail = dispatch_fire & (~do_flush)

    head_inc = commit_count
    if rob_w > head_inc.width:
        head_inc = head_inc.zext(width=rob_w)
    elif rob_w < head_inc.width:
        head_inc = head_inc.trunc(width=rob_w)
    head_next = inc_head.select(rob_head + head_inc, head_next)

    disp_tail_inc = disp_count
    if rob_w > disp_tail_inc.width:
        disp_tail_inc = disp_tail_inc.zext(width=rob_w)
    elif rob_w < disp_tail_inc.width:
        disp_tail_inc = disp_tail_inc.trunc(width=rob_w)
    tail_next = inc_tail.select(rob_tail + disp_tail_inc, tail_next)

    commit_dec = commit_count.zext(width=rob_w + 1)
    commit_dec_neg = (~commit_dec) + c(1, width=rob_w + 1)
    count_next = inc_tail.select(count_next + disp_count.zext(width=rob_w + 1), count_next)
    count_next = inc_head.select(count_next + commit_dec_neg, count_next)

    m.output("head_next", head_next)
    m.output("tail_next", tail_next)
    m.output("count_next", count_next)
    for slot in range(dispatch_w):
        m.output(f"disp_rob_idx{slot}", disp_rob_idxs[slot])
        m.output(f"disp_fire{slot}", disp_fires[slot])


@module(name="LinxCoreRobEntryUpdateStage")
def build_rob_entry_update_stage(
    m: Circuit,
    *,
    dispatch_w: int = 4,
    issue_w: int = 4,
    commit_w: int = 4,
    rob_w: int = 6,
    ptag_w: int = 6,
) -> None:
    c = m.const
    idx = m.input("idx", width=rob_w)
    do_flush = m.input("do_flush", width=1)

    old_valid = m.input("old_valid", width=1)
    old_done = m.input("old_done", width=1)
    old_pc = m.input("old_pc", width=64)
    old_op = m.input("old_op", width=12)
    old_len = m.input("old_len", width=3)
    old_insn_raw = m.input("old_insn_raw", width=64)
    old_checkpoint_id = m.input("old_checkpoint_id", width=6)
    old_dst_kind = m.input("old_dst_kind", width=2)
    old_dst_areg = m.input("old_dst_areg", width=6)
    old_pdst = m.input("old_pdst", width=ptag_w)
    old_value = m.input("old_value", width=64)
    old_src0_reg = m.input("old_src0_reg", width=6)
    old_src1_reg = m.input("old_src1_reg", width=6)
    old_src0_value = m.input("old_src0_value", width=64)
    old_src1_value = m.input("old_src1_value", width=64)
    old_src0_valid = m.input("old_src0_valid", width=1)
    old_src1_valid = m.input("old_src1_valid", width=1)
    old_is_store = m.input("old_is_store", width=1)
    old_store_addr = m.input("old_store_addr", width=64)
    old_store_data = m.input("old_store_data", width=64)
    old_store_size = m.input("old_store_size", width=4)
    old_is_load = m.input("old_is_load", width=1)
    old_load_addr = m.input("old_load_addr", width=64)
    old_load_data = m.input("old_load_data", width=64)
    old_load_size = m.input("old_load_size", width=4)
    old_is_boundary = m.input("old_is_boundary", width=1)
    old_is_bstart = m.input("old_is_bstart", width=1)
    old_is_bstop = m.input("old_is_bstop", width=1)
    old_boundary_kind = m.input("old_boundary_kind", width=3)
    old_boundary_target = m.input("old_boundary_target", width=64)
    old_pred_take = m.input("old_pred_take", width=1)
    old_block_epoch = m.input("old_block_epoch", width=16)
    old_block_uid = m.input("old_block_uid", width=64)
    old_block_bid = m.input("old_block_bid", width=64)
    old_load_store_id = m.input("old_load_store_id", width=32)
    old_resolved_d2 = m.input("old_resolved_d2", width=1)
    old_macro_begin = m.input("old_macro_begin", width=6)
    old_macro_end = m.input("old_macro_end", width=6)
    old_uop_uid = m.input("old_uop_uid", width=64)
    old_parent_uid = m.input("old_parent_uid", width=64)

    commit_fires = []
    commit_idxs = []
    for slot in range(commit_w):
        commit_fires.append(m.input(f"commit_fire{slot}", width=1))
        commit_idxs.append(m.input(f"commit_idx{slot}", width=rob_w))

    disp_fires = []
    disp_rob_idxs = []
    disp_pcs = []
    disp_ops = []
    disp_lens = []
    disp_insn_raws = []
    disp_checkpoint_ids = []
    disp_dst_kinds = []
    disp_regdsts = []
    disp_pdsts = []
    disp_imms = []
    disp_is_stores = []
    disp_is_boundaries = []
    disp_is_bstarts = []
    disp_is_bstops = []
    disp_boundary_kinds = []
    disp_boundary_targets = []
    disp_pred_takes = []
    disp_block_epochs = []
    disp_block_uids = []
    disp_block_bids = []
    disp_load_store_ids = []
    disp_resolved_d2s = []
    disp_srcls = []
    disp_srcrs = []
    for slot in range(dispatch_w):
        disp_fires.append(m.input(f"disp_fire{slot}", width=1))
        disp_rob_idxs.append(m.input(f"disp_rob_idx{slot}", width=rob_w))
        disp_pcs.append(m.input(f"disp_pc{slot}", width=64))
        disp_ops.append(m.input(f"disp_op{slot}", width=12))
        disp_lens.append(m.input(f"disp_len{slot}", width=3))
        disp_insn_raws.append(m.input(f"disp_insn_raw{slot}", width=64))
        disp_checkpoint_ids.append(m.input(f"disp_checkpoint_id{slot}", width=6))
        disp_dst_kinds.append(m.input(f"disp_dst_kind{slot}", width=2))
        disp_regdsts.append(m.input(f"disp_regdst{slot}", width=6))
        disp_pdsts.append(m.input(f"disp_pdst{slot}", width=ptag_w))
        disp_imms.append(m.input(f"disp_imm{slot}", width=64))
        disp_is_stores.append(m.input(f"disp_is_store{slot}", width=1))
        disp_is_boundaries.append(m.input(f"disp_is_boundary{slot}", width=1))
        disp_is_bstarts.append(m.input(f"disp_is_bstart{slot}", width=1))
        disp_is_bstops.append(m.input(f"disp_is_bstop{slot}", width=1))
        disp_boundary_kinds.append(m.input(f"disp_boundary_kind{slot}", width=3))
        disp_boundary_targets.append(m.input(f"disp_boundary_target{slot}", width=64))
        disp_pred_takes.append(m.input(f"disp_pred_take{slot}", width=1))
        disp_block_epochs.append(m.input(f"disp_block_epoch{slot}", width=16))
        disp_block_uids.append(m.input(f"disp_block_uid{slot}", width=64))
        disp_block_bids.append(m.input(f"disp_block_bid{slot}", width=64))
        disp_load_store_ids.append(m.input(f"disp_load_store_id{slot}", width=32))
        disp_resolved_d2s.append(m.input(f"disp_resolved_d2{slot}", width=1))
        disp_srcls.append(m.input(f"disp_srcl{slot}", width=6))
        disp_srcrs.append(m.input(f"disp_srcr{slot}", width=6))
    disp_uop_uids = []
    disp_parent_uids = []
    for slot in range(dispatch_w):
        disp_uop_uids.append(m.input(f"disp_uop_uid{slot}", width=64))
        disp_parent_uids.append(m.input(f"disp_parent_uid{slot}", width=64))

    wb_fires = []
    wb_robs = []
    wb_values = []
    store_fires = []
    load_fires = []
    ex_addrs = []
    ex_wdatas = []
    ex_sizes = []
    ex_src0s = []
    ex_src1s = []
    for slot in range(issue_w):
        wb_fires.append(m.input(f"wb_fire{slot}", width=1))
        wb_robs.append(m.input(f"wb_rob{slot}", width=rob_w))
        wb_values.append(m.input(f"wb_value{slot}", width=64))
        store_fires.append(m.input(f"store_fire{slot}", width=1))
        load_fires.append(m.input(f"load_fire{slot}", width=1))
        ex_addrs.append(m.input(f"ex_addr{slot}", width=64))
        ex_wdatas.append(m.input(f"ex_wdata{slot}", width=64))
        ex_sizes.append(m.input(f"ex_size{slot}", width=4))
        ex_src0s.append(m.input(f"ex_src0{slot}", width=64))
        ex_src1s.append(m.input(f"ex_src1{slot}", width=64))

    commit_hit = c(0, width=1)
    for slot in range(commit_w):
        commit_hit = commit_hit | (commit_fires[slot] & commit_idxs[slot].eq(idx))

    disp_hit = c(0, width=1)
    for slot in range(dispatch_w):
        disp_hit = disp_hit | (disp_fires[slot] & disp_rob_idxs[slot].eq(idx))

    wb_hit = c(0, width=1)
    for slot in range(issue_w):
        wb_hit = wb_hit | (wb_fires[slot] & wb_robs[slot].eq(idx))

    valid_next = old_valid
    valid_next = do_flush.select(c(0, width=1), valid_next)
    valid_next = commit_hit.select(c(0, width=1), valid_next)
    valid_next = disp_hit.select(c(1, width=1), valid_next)

    done_next = old_done
    done_next = do_flush.select(c(0, width=1), done_next)
    done_next = commit_hit.select(c(0, width=1), done_next)
    done_next = disp_hit.select(c(0, width=1), done_next)
    for slot in range(dispatch_w):
        hit = disp_fires[slot] & disp_rob_idxs[slot].eq(idx)
        is_macro = disp_ops[slot].eq(c(OP_FENTRY, width=12)) | disp_ops[slot].eq(c(OP_FEXIT, width=12))
        is_macro = is_macro | disp_ops[slot].eq(c(OP_FRET_RA, width=12)) | disp_ops[slot].eq(c(OP_FRET_STK, width=12))
        done_next = (hit & (is_macro | disp_resolved_d2s[slot])).select(c(1, width=1), done_next)
    done_next = wb_hit.select(c(1, width=1), done_next)

    pc_next = old_pc
    op_next = old_op
    len_next = old_len
    insn_raw_next = old_insn_raw
    checkpoint_id_next = old_checkpoint_id
    dst_kind_next = old_dst_kind
    dst_areg_next = old_dst_areg
    pdst_next = old_pdst
    for slot in range(dispatch_w):
        hit = disp_fires[slot] & disp_rob_idxs[slot].eq(idx)
        pc_next = hit.select(disp_pcs[slot], pc_next)
        op_next = hit.select(disp_ops[slot], op_next)
        len_next = hit.select(disp_lens[slot], len_next)
        insn_raw_next = hit.select(disp_insn_raws[slot], insn_raw_next)
        checkpoint_id_next = hit.select(disp_checkpoint_ids[slot], checkpoint_id_next)
        dst_kind_next = hit.select(disp_dst_kinds[slot], dst_kind_next)
        dst_areg_next = hit.select(disp_regdsts[slot], dst_areg_next)
        pdst_next = hit.select(disp_pdsts[slot], pdst_next)

    value_next = old_value
    value_next = disp_hit.select(c(0, width=64), value_next)
    for slot in range(dispatch_w):
        hit = disp_fires[slot] & disp_rob_idxs[slot].eq(idx)
        is_macro = disp_ops[slot].eq(c(OP_FENTRY, width=12)) | disp_ops[slot].eq(c(OP_FEXIT, width=12))
        is_macro = is_macro | disp_ops[slot].eq(c(OP_FRET_RA, width=12)) | disp_ops[slot].eq(c(OP_FRET_STK, width=12))
        value_next = (hit & is_macro).select(disp_imms[slot], value_next)
    for slot in range(issue_w):
        hit = wb_fires[slot] & wb_robs[slot].eq(idx)
        value_next = hit.select(wb_values[slot], value_next)

    src0_reg_next = old_src0_reg
    src1_reg_next = old_src1_reg
    src0_valid_next = old_src0_valid
    src1_valid_next = old_src1_valid
    src0_value_next = old_src0_value
    src1_value_next = old_src1_value

    src0_valid_next = do_flush.select(c(0, width=1), src0_valid_next)
    src1_valid_next = do_flush.select(c(0, width=1), src1_valid_next)
    src0_valid_next = commit_hit.select(c(0, width=1), src0_valid_next)
    src1_valid_next = commit_hit.select(c(0, width=1), src1_valid_next)
    src0_value_next = do_flush.select(c(0, width=64), src0_value_next)
    src1_value_next = do_flush.select(c(0, width=64), src1_value_next)
    src0_value_next = commit_hit.select(c(0, width=64), src0_value_next)
    src1_value_next = commit_hit.select(c(0, width=64), src1_value_next)
    src0_value_next = disp_hit.select(c(0, width=64), src0_value_next)
    src1_value_next = disp_hit.select(c(0, width=64), src1_value_next)

    for slot in range(dispatch_w):
        hit = disp_fires[slot] & disp_rob_idxs[slot].eq(idx)
        src0_reg_next = hit.select(disp_srcls[slot], src0_reg_next)
        src1_reg_next = hit.select(disp_srcrs[slot], src1_reg_next)
        src0_valid_next = hit.select(~disp_srcls[slot].eq(c(63, width=6)), src0_valid_next)
        src1_valid_next = hit.select(~disp_srcrs[slot].eq(c(63, width=6)), src1_valid_next)

    for slot in range(issue_w):
        hit = wb_fires[slot] & wb_robs[slot].eq(idx)
        src0_value_next = hit.select(ex_src0s[slot], src0_value_next)
        src1_value_next = hit.select(ex_src1s[slot], src1_value_next)

    is_store_next = old_is_store
    is_load_next = old_is_load
    for slot in range(dispatch_w):
        hit = disp_fires[slot] & disp_rob_idxs[slot].eq(idx)
        is_store_next = hit.select(disp_is_stores[slot], is_store_next)
        is_load_next = hit.select(c(0, width=1), is_load_next)

    store_addr_next = old_store_addr
    store_data_next = old_store_data
    store_size_next = old_store_size
    store_addr_next = disp_hit.select(c(0, width=64), store_addr_next)
    store_data_next = disp_hit.select(c(0, width=64), store_data_next)
    store_size_next = disp_hit.select(c(0, width=4), store_size_next)
    for slot in range(issue_w):
        hit = store_fires[slot] & wb_robs[slot].eq(idx)
        store_addr_next = hit.select(ex_addrs[slot], store_addr_next)
        store_data_next = hit.select(ex_wdatas[slot], store_data_next)
        store_size_next = hit.select(ex_sizes[slot], store_size_next)

    load_addr_next = old_load_addr
    load_data_next = old_load_data
    load_size_next = old_load_size
    load_addr_next = disp_hit.select(c(0, width=64), load_addr_next)
    load_data_next = disp_hit.select(c(0, width=64), load_data_next)
    load_size_next = disp_hit.select(c(0, width=4), load_size_next)
    for slot in range(issue_w):
        hit = load_fires[slot] & wb_robs[slot].eq(idx)
        load_addr_next = hit.select(ex_addrs[slot], load_addr_next)
        load_data_next = hit.select(wb_values[slot], load_data_next)
        load_size_next = hit.select(ex_sizes[slot], load_size_next)
        is_load_next = hit.select(c(1, width=1), is_load_next)
        is_store_next = hit.select(c(0, width=1), is_store_next)

    is_boundary_next = old_is_boundary
    is_bstart_next = old_is_bstart
    is_bstop_next = old_is_bstop
    boundary_kind_next = old_boundary_kind
    boundary_target_next = old_boundary_target
    pred_take_next = old_pred_take
    block_epoch_next = old_block_epoch
    block_uid_next = old_block_uid
    block_bid_next = old_block_bid
    load_store_id_next = old_load_store_id
    resolved_d2_next = old_resolved_d2
    for slot in range(dispatch_w):
        hit = disp_fires[slot] & disp_rob_idxs[slot].eq(idx)
        is_boundary_next = hit.select(disp_is_boundaries[slot], is_boundary_next)
        is_bstart_next = hit.select(disp_is_bstarts[slot], is_bstart_next)
        is_bstop_next = hit.select(disp_is_bstops[slot], is_bstop_next)
        boundary_kind_next = hit.select(disp_boundary_kinds[slot], boundary_kind_next)
        boundary_target_next = hit.select(disp_boundary_targets[slot], boundary_target_next)
        pred_take_next = hit.select(disp_pred_takes[slot], pred_take_next)
        block_epoch_next = hit.select(disp_block_epochs[slot], block_epoch_next)
        block_uid_next = hit.select(disp_block_uids[slot], block_uid_next)
        block_bid_next = hit.select(disp_block_bids[slot], block_bid_next)
        load_store_id_next = hit.select(disp_load_store_ids[slot], load_store_id_next)
        resolved_d2_next = hit.select(disp_resolved_d2s[slot], resolved_d2_next)
    block_epoch_next = commit_hit.select(c(0, width=16), block_epoch_next)
    block_epoch_next = do_flush.select(c(0, width=16), block_epoch_next)
    block_uid_next = commit_hit.select(c(0, width=64), block_uid_next)
    block_uid_next = do_flush.select(c(0, width=64), block_uid_next)
    block_bid_next = commit_hit.select(c(0, width=64), block_bid_next)
    block_bid_next = do_flush.select(c(0, width=64), block_bid_next)
    load_store_id_next = commit_hit.select(c(0, width=32), load_store_id_next)
    load_store_id_next = do_flush.select(c(0, width=32), load_store_id_next)

    macro_begin_next = old_macro_begin
    macro_end_next = old_macro_end
    uop_uid_next = old_uop_uid
    parent_uid_next = old_parent_uid
    for slot in range(dispatch_w):
        hit = disp_fires[slot] & disp_rob_idxs[slot].eq(idx)
        macro_begin_next = hit.select(disp_srcls[slot], macro_begin_next)
        macro_end_next = hit.select(disp_srcrs[slot], macro_end_next)
        uop_uid_next = hit.select(disp_uop_uids[slot], uop_uid_next)
        parent_uid_next = hit.select(disp_parent_uids[slot], parent_uid_next)
    uop_uid_next = commit_hit.select(c(0, width=64), uop_uid_next)
    parent_uid_next = commit_hit.select(c(0, width=64), parent_uid_next)
    uop_uid_next = do_flush.select(c(0, width=64), uop_uid_next)
    parent_uid_next = do_flush.select(c(0, width=64), parent_uid_next)

    m.output("valid_next", valid_next)
    m.output("done_next", done_next)
    m.output("pc_next", pc_next)
    m.output("op_next", op_next)
    m.output("len_next", len_next)
    m.output("insn_raw_next", insn_raw_next)
    m.output("checkpoint_id_next", checkpoint_id_next)
    m.output("dst_kind_next", dst_kind_next)
    m.output("dst_areg_next", dst_areg_next)
    m.output("pdst_next", pdst_next)
    m.output("value_next", value_next)
    m.output("src0_reg_next", src0_reg_next)
    m.output("src1_reg_next", src1_reg_next)
    m.output("src0_value_next", src0_value_next)
    m.output("src1_value_next", src1_value_next)
    m.output("src0_valid_next", src0_valid_next)
    m.output("src1_valid_next", src1_valid_next)
    m.output("is_store_next", is_store_next)
    m.output("store_addr_next", store_addr_next)
    m.output("store_data_next", store_data_next)
    m.output("store_size_next", store_size_next)
    m.output("is_load_next", is_load_next)
    m.output("load_addr_next", load_addr_next)
    m.output("load_data_next", load_data_next)
    m.output("load_size_next", load_size_next)
    m.output("is_boundary_next", is_boundary_next)
    m.output("is_bstart_next", is_bstart_next)
    m.output("is_bstop_next", is_bstop_next)
    m.output("boundary_kind_next", boundary_kind_next)
    m.output("boundary_target_next", boundary_target_next)
    m.output("pred_take_next", pred_take_next)
    m.output("block_epoch_next", block_epoch_next)
    m.output("block_uid_next", block_uid_next)
    m.output("block_bid_next", block_bid_next)
    m.output("load_store_id_next", load_store_id_next)
    m.output("resolved_d2_next", resolved_d2_next)
    m.output("macro_begin_next", macro_begin_next)
    m.output("macro_end_next", macro_end_next)
    m.output("uop_uid_next", uop_uid_next)
    m.output("parent_uid_next", parent_uid_next)
