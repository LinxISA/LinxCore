from __future__ import annotations

from pycircuit import Circuit, module, u

from ..rob import build_rob_ctrl_stage
from .rob_bank_slice import build_rob_bank_slice


@module(name="LinxCoreRobBankTop")
def build_rob_bank_top(
    m: Circuit,
    *,
    rob_depth: int = 64,
    slice_entries: int = 8,
    dispatch_w: int = 4,
    issue_w: int = 4,
    commit_w: int = 4,
    rob_w: int = 6,
    ptag_w: int = 6,
) -> None:
    """Hierarchical ROB bank (pointers + entry slices).

    This module owns:
    - ROB pointers/count (head/tail/count)
    - ROB entry state (split into `slice_entries`-sized submodules)
    """

    if rob_depth <= 0:
        raise ValueError("rob_depth must be > 0")
    if slice_entries <= 0:
        raise ValueError("slice_entries must be > 0")
    if (rob_depth % slice_entries) != 0:
        raise ValueError(f"rob_depth ({rob_depth}) must be divisible by slice_entries ({slice_entries})")

    clk = m.clock("clk")
    rst = m.reset("rst")

    do_flush = m.input("do_flush", width=1)
    commit_fire = m.input("commit_fire", width=1)
    dispatch_fire = m.input("dispatch_fire", width=1)
    commit_count = m.input("commit_count", width=3)
    disp_count = m.input("disp_count", width=3)

    disp_valids = []
    for slot in range(dispatch_w):
        disp_valids.append(m.input(f"disp_valid{slot}", width=1))

    commit_fires = []
    commit_idxs = []
    for slot in range(commit_w):
        commit_fires.append(m.input(f"commit_fire{slot}", width=1))
        commit_idxs.append(m.input(f"commit_idx{slot}", width=rob_w))

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
    disp_uop_uids = []
    disp_parent_uids = []
    for slot in range(dispatch_w):
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

    head = m.out("head", clk=clk, rst=rst, width=rob_w, init=0, en=1)
    tail = m.out("tail", clk=clk, rst=rst, width=rob_w, init=0, en=1)
    count = m.out("count", clk=clk, rst=rst, width=rob_w + 1, init=0, en=1)

    ctrl_args = {
        "do_flush": do_flush,
        "commit_fire": commit_fire,
        "dispatch_fire": dispatch_fire,
        "rob_head": head.out(),
        "rob_tail": tail.out(),
        "rob_count": count.out(),
        "commit_count": commit_count,
        "disp_count": disp_count,
    }
    for slot in range(dispatch_w):
        ctrl_args[f"disp_valid{slot}"] = disp_valids[slot]

    rob_ctrl = m.new(
        build_rob_ctrl_stage,
        name="ctrl",
        bind=ctrl_args,
        params={"dispatch_w": dispatch_w, "rob_w": rob_w},
    ).outputs

    head.set(rob_ctrl["head_next"])
    tail.set(rob_ctrl["tail_next"])
    count.set(rob_ctrl["count_next"])

    m.output("head", head)
    m.output("tail", tail)
    m.output("count", count)
    for slot in range(dispatch_w):
        m.output(f"disp_rob_idx{slot}", rob_ctrl[f"disp_rob_idx{slot}"])
        m.output(f"disp_fire{slot}", rob_ctrl[f"disp_fire{slot}"])

    slices = int(rob_depth // slice_entries)
    for sid in range(slices):
        base = u(rob_w, sid * slice_entries)
        slice_args = {
            "clk": clk,
            "rst": rst,
            "slice_base_i": base,
            "do_flush": do_flush,
        }
        for slot in range(commit_w):
            slice_args[f"commit_fire{slot}"] = commit_fires[slot]
            slice_args[f"commit_idx{slot}"] = commit_idxs[slot]
        for slot in range(dispatch_w):
            slice_args[f"disp_fire{slot}"] = rob_ctrl[f"disp_fire{slot}"]
            slice_args[f"disp_rob_idx{slot}"] = rob_ctrl[f"disp_rob_idx{slot}"]
            slice_args[f"disp_pc{slot}"] = disp_pcs[slot]
            slice_args[f"disp_op{slot}"] = disp_ops[slot]
            slice_args[f"disp_len{slot}"] = disp_lens[slot]
            slice_args[f"disp_insn_raw{slot}"] = disp_insn_raws[slot]
            slice_args[f"disp_checkpoint_id{slot}"] = disp_checkpoint_ids[slot]
            slice_args[f"disp_dst_kind{slot}"] = disp_dst_kinds[slot]
            slice_args[f"disp_regdst{slot}"] = disp_regdsts[slot]
            slice_args[f"disp_pdst{slot}"] = disp_pdsts[slot]
            slice_args[f"disp_imm{slot}"] = disp_imms[slot]
            slice_args[f"disp_is_store{slot}"] = disp_is_stores[slot]
            slice_args[f"disp_is_boundary{slot}"] = disp_is_boundaries[slot]
            slice_args[f"disp_is_bstart{slot}"] = disp_is_bstarts[slot]
            slice_args[f"disp_is_bstop{slot}"] = disp_is_bstops[slot]
            slice_args[f"disp_boundary_kind{slot}"] = disp_boundary_kinds[slot]
            slice_args[f"disp_boundary_target{slot}"] = disp_boundary_targets[slot]
            slice_args[f"disp_pred_take{slot}"] = disp_pred_takes[slot]
            slice_args[f"disp_block_epoch{slot}"] = disp_block_epochs[slot]
            slice_args[f"disp_block_uid{slot}"] = disp_block_uids[slot]
            slice_args[f"disp_block_bid{slot}"] = disp_block_bids[slot]
            slice_args[f"disp_load_store_id{slot}"] = disp_load_store_ids[slot]
            slice_args[f"disp_resolved_d2{slot}"] = disp_resolved_d2s[slot]
            slice_args[f"disp_srcl{slot}"] = disp_srcls[slot]
            slice_args[f"disp_srcr{slot}"] = disp_srcrs[slot]
            slice_args[f"disp_uop_uid{slot}"] = disp_uop_uids[slot]
            slice_args[f"disp_parent_uid{slot}"] = disp_parent_uids[slot]
        for slot in range(issue_w):
            slice_args[f"wb_fire{slot}"] = wb_fires[slot]
            slice_args[f"wb_rob{slot}"] = wb_robs[slot]
            slice_args[f"wb_value{slot}"] = wb_values[slot]
            slice_args[f"store_fire{slot}"] = store_fires[slot]
            slice_args[f"load_fire{slot}"] = load_fires[slot]
            slice_args[f"ex_addr{slot}"] = ex_addrs[slot]
            slice_args[f"ex_wdata{slot}"] = ex_wdatas[slot]
            slice_args[f"ex_size{slot}"] = ex_sizes[slot]
            slice_args[f"ex_src0{slot}"] = ex_src0s[slot]
            slice_args[f"ex_src1{slot}"] = ex_src1s[slot]

        sl = m.new(
            build_rob_bank_slice,
            name=f"slice{sid}",
            bind=slice_args,
            params={
                "slice_entries": slice_entries,
                "dispatch_w": dispatch_w,
                "issue_w": issue_w,
                "commit_w": commit_w,
                "rob_w": rob_w,
                "ptag_w": ptag_w,
            },
        ).outputs

        for j in range(slice_entries):
            gi = (sid * slice_entries) + j
            m.output(f"valid{gi}", sl[f"valid{j}"])
            m.output(f"done{gi}", sl[f"done{j}"])
            m.output(f"pc{gi}", sl[f"pc{j}"])
            m.output(f"op{gi}", sl[f"op{j}"])
            m.output(f"len{gi}", sl[f"len{j}"])
            m.output(f"insn_raw{gi}", sl[f"insn_raw{j}"])
            m.output(f"checkpoint_id{gi}", sl[f"checkpoint_id{j}"])
            m.output(f"dst_kind{gi}", sl[f"dst_kind{j}"])
            m.output(f"dst_areg{gi}", sl[f"dst_areg{j}"])
            m.output(f"pdst{gi}", sl[f"pdst{j}"])
            m.output(f"value{gi}", sl[f"value{j}"])
            m.output(f"src0_reg{gi}", sl[f"src0_reg{j}"])
            m.output(f"src1_reg{gi}", sl[f"src1_reg{j}"])
            m.output(f"src0_value{gi}", sl[f"src0_value{j}"])
            m.output(f"src1_value{gi}", sl[f"src1_value{j}"])
            m.output(f"src0_valid{gi}", sl[f"src0_valid{j}"])
            m.output(f"src1_valid{gi}", sl[f"src1_valid{j}"])
            m.output(f"is_store{gi}", sl[f"is_store{j}"])
            m.output(f"store_addr{gi}", sl[f"store_addr{j}"])
            m.output(f"store_data{gi}", sl[f"store_data{j}"])
            m.output(f"store_size{gi}", sl[f"store_size{j}"])
            m.output(f"is_load{gi}", sl[f"is_load{j}"])
            m.output(f"load_addr{gi}", sl[f"load_addr{j}"])
            m.output(f"load_data{gi}", sl[f"load_data{j}"])
            m.output(f"load_size{gi}", sl[f"load_size{j}"])
            m.output(f"is_boundary{gi}", sl[f"is_boundary{j}"])
            m.output(f"is_bstart{gi}", sl[f"is_bstart{j}"])
            m.output(f"is_bstop{gi}", sl[f"is_bstop{j}"])
            m.output(f"boundary_kind{gi}", sl[f"boundary_kind{j}"])
            m.output(f"boundary_target{gi}", sl[f"boundary_target{j}"])
            m.output(f"pred_take{gi}", sl[f"pred_take{j}"])
            m.output(f"block_epoch{gi}", sl[f"block_epoch{j}"])
            m.output(f"block_uid{gi}", sl[f"block_uid{j}"])
            m.output(f"block_bid{gi}", sl[f"block_bid{j}"])
            m.output(f"load_store_id{gi}", sl[f"load_store_id{j}"])
            m.output(f"resolved_d2{gi}", sl[f"resolved_d2{j}"])
            m.output(f"macro_begin{gi}", sl[f"macro_begin{j}"])
            m.output(f"macro_end{gi}", sl[f"macro_end{j}"])
            m.output(f"uop_uid{gi}", sl[f"uop_uid{j}"])
            m.output(f"parent_uid{gi}", sl[f"parent_uid{j}"])
