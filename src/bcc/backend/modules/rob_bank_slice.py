from __future__ import annotations

from pycircuit import Circuit, module, u

from ..rob import build_rob_entry_update_stage


@module(name="LinxCoreRobBankSlice")
def build_rob_bank_slice(
    m: Circuit,
    *,
    slice_entries: int = 8,
    dispatch_w: int = 4,
    issue_w: int = 4,
    commit_w: int = 4,
    rob_w: int = 6,
    ptag_w: int = 6,
) -> None:
    """Stateful ROB entry slice.

    This module owns the entry registers for `slice_entries` ROB slots, and
    updates them using the existing combinational `LinxCoreRobEntryUpdateStage`.
    """

    if slice_entries <= 0:
        raise ValueError("slice_entries must be > 0")

    clk = m.clock("clk")
    rst = m.reset("rst")

    slice_base_i = m.input("slice_base_i", width=rob_w)
    do_flush = m.input("do_flush", width=1)

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
    disp_uop_uids = []
    disp_parent_uids = []
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

    valid = []
    done = []
    pc = []
    op = []
    len_bytes = []
    dst_kind = []
    dst_areg = []
    pdst = []
    value = []
    src0_reg = []
    src1_reg = []
    src0_value = []
    src1_value = []
    src0_valid = []
    src1_valid = []
    is_store = []
    store_addr = []
    store_data = []
    store_size = []
    is_load = []
    load_addr = []
    load_data = []
    load_size = []
    is_boundary = []
    is_bstart = []
    is_bstop = []
    boundary_kind = []
    boundary_target = []
    pred_take = []
    block_epoch = []
    block_uid = []
    block_bid = []
    load_store_id = []
    resolved_d2 = []
    insn_raw = []
    checkpoint_id = []
    macro_begin = []
    macro_end = []
    uop_uid = []
    parent_uid = []

    for j in range(slice_entries):
        valid.append(m.out(f"valid{j}", clk=clk, rst=rst, width=1, init=0, en=1))
        done.append(m.out(f"done{j}", clk=clk, rst=rst, width=1, init=0, en=1))
        pc.append(m.out(f"pc{j}", clk=clk, rst=rst, width=64, init=0, en=1))
        op.append(m.out(f"op{j}", clk=clk, rst=rst, width=12, init=0, en=1))
        len_bytes.append(m.out(f"len{j}", clk=clk, rst=rst, width=3, init=0, en=1))
        insn_raw.append(m.out(f"insn_raw{j}", clk=clk, rst=rst, width=64, init=0, en=1))
        checkpoint_id.append(m.out(f"checkpoint_id{j}", clk=clk, rst=rst, width=6, init=0, en=1))

        dst_kind.append(m.out(f"dst_kind{j}", clk=clk, rst=rst, width=2, init=0, en=1))
        dst_areg.append(m.out(f"dst_areg{j}", clk=clk, rst=rst, width=6, init=0, en=1))
        pdst.append(m.out(f"pdst{j}", clk=clk, rst=rst, width=ptag_w, init=0, en=1))
        value.append(m.out(f"value{j}", clk=clk, rst=rst, width=64, init=0, en=1))

        src0_reg.append(m.out(f"src0_reg{j}", clk=clk, rst=rst, width=6, init=0, en=1))
        src1_reg.append(m.out(f"src1_reg{j}", clk=clk, rst=rst, width=6, init=0, en=1))
        src0_value.append(m.out(f"src0_value{j}", clk=clk, rst=rst, width=64, init=0, en=1))
        src1_value.append(m.out(f"src1_value{j}", clk=clk, rst=rst, width=64, init=0, en=1))
        src0_valid.append(m.out(f"src0_valid{j}", clk=clk, rst=rst, width=1, init=0, en=1))
        src1_valid.append(m.out(f"src1_valid{j}", clk=clk, rst=rst, width=1, init=0, en=1))

        is_store.append(m.out(f"is_store{j}", clk=clk, rst=rst, width=1, init=0, en=1))
        store_addr.append(m.out(f"store_addr{j}", clk=clk, rst=rst, width=64, init=0, en=1))
        store_data.append(m.out(f"store_data{j}", clk=clk, rst=rst, width=64, init=0, en=1))
        store_size.append(m.out(f"store_size{j}", clk=clk, rst=rst, width=4, init=0, en=1))

        is_load.append(m.out(f"is_load{j}", clk=clk, rst=rst, width=1, init=0, en=1))
        load_addr.append(m.out(f"load_addr{j}", clk=clk, rst=rst, width=64, init=0, en=1))
        load_data.append(m.out(f"load_data{j}", clk=clk, rst=rst, width=64, init=0, en=1))
        load_size.append(m.out(f"load_size{j}", clk=clk, rst=rst, width=4, init=0, en=1))

        is_boundary.append(m.out(f"is_boundary{j}", clk=clk, rst=rst, width=1, init=0, en=1))
        is_bstart.append(m.out(f"is_bstart{j}", clk=clk, rst=rst, width=1, init=0, en=1))
        is_bstop.append(m.out(f"is_bstop{j}", clk=clk, rst=rst, width=1, init=0, en=1))
        boundary_kind.append(m.out(f"boundary_kind{j}", clk=clk, rst=rst, width=3, init=0, en=1))
        boundary_target.append(m.out(f"boundary_target{j}", clk=clk, rst=rst, width=64, init=0, en=1))
        pred_take.append(m.out(f"pred_take{j}", clk=clk, rst=rst, width=1, init=0, en=1))

        block_epoch.append(m.out(f"block_epoch{j}", clk=clk, rst=rst, width=16, init=0, en=1))
        block_uid.append(m.out(f"block_uid{j}", clk=clk, rst=rst, width=64, init=0, en=1))
        block_bid.append(m.out(f"block_bid{j}", clk=clk, rst=rst, width=64, init=0, en=1))
        load_store_id.append(m.out(f"load_store_id{j}", clk=clk, rst=rst, width=32, init=0, en=1))
        resolved_d2.append(m.out(f"resolved_d2{j}", clk=clk, rst=rst, width=1, init=0, en=1))

        macro_begin.append(m.out(f"macro_begin{j}", clk=clk, rst=rst, width=6, init=0, en=1))
        macro_end.append(m.out(f"macro_end{j}", clk=clk, rst=rst, width=6, init=0, en=1))

        uop_uid.append(m.out(f"uop_uid{j}", clk=clk, rst=rst, width=64, init=0, en=1))
        parent_uid.append(m.out(f"parent_uid{j}", clk=clk, rst=rst, width=64, init=0, en=1))

    for j in range(slice_entries):
        idx = slice_base_i + u(rob_w, j)

        entry_args = {
            "idx": idx,
            "do_flush": do_flush,
            "old_valid": valid[j].out(),
            "old_done": done[j].out(),
            "old_pc": pc[j].out(),
            "old_op": op[j].out(),
            "old_len": len_bytes[j].out(),
            "old_insn_raw": insn_raw[j].out(),
            "old_checkpoint_id": checkpoint_id[j].out(),
            "old_dst_kind": dst_kind[j].out(),
            "old_dst_areg": dst_areg[j].out(),
            "old_pdst": pdst[j].out(),
            "old_value": value[j].out(),
            "old_src0_reg": src0_reg[j].out(),
            "old_src1_reg": src1_reg[j].out(),
            "old_src0_value": src0_value[j].out(),
            "old_src1_value": src1_value[j].out(),
            "old_src0_valid": src0_valid[j].out(),
            "old_src1_valid": src1_valid[j].out(),
            "old_is_store": is_store[j].out(),
            "old_store_addr": store_addr[j].out(),
            "old_store_data": store_data[j].out(),
            "old_store_size": store_size[j].out(),
            "old_is_load": is_load[j].out(),
            "old_load_addr": load_addr[j].out(),
            "old_load_data": load_data[j].out(),
            "old_load_size": load_size[j].out(),
            "old_is_boundary": is_boundary[j].out(),
            "old_is_bstart": is_bstart[j].out(),
            "old_is_bstop": is_bstop[j].out(),
            "old_boundary_kind": boundary_kind[j].out(),
            "old_boundary_target": boundary_target[j].out(),
            "old_pred_take": pred_take[j].out(),
            "old_block_epoch": block_epoch[j].out(),
            "old_block_uid": block_uid[j].out(),
            "old_block_bid": block_bid[j].out(),
            "old_load_store_id": load_store_id[j].out(),
            "old_resolved_d2": resolved_d2[j].out(),
            "old_macro_begin": macro_begin[j].out(),
            "old_macro_end": macro_end[j].out(),
            "old_uop_uid": uop_uid[j].out(),
            "old_parent_uid": parent_uid[j].out(),
        }
        for slot in range(commit_w):
            entry_args[f"commit_fire{slot}"] = commit_fires[slot]
            entry_args[f"commit_idx{slot}"] = commit_idxs[slot]
        for slot in range(dispatch_w):
            entry_args[f"disp_fire{slot}"] = disp_fires[slot]
            entry_args[f"disp_rob_idx{slot}"] = disp_rob_idxs[slot]
            entry_args[f"disp_pc{slot}"] = disp_pcs[slot]
            entry_args[f"disp_op{slot}"] = disp_ops[slot]
            entry_args[f"disp_len{slot}"] = disp_lens[slot]
            entry_args[f"disp_insn_raw{slot}"] = disp_insn_raws[slot]
            entry_args[f"disp_checkpoint_id{slot}"] = disp_checkpoint_ids[slot]
            entry_args[f"disp_dst_kind{slot}"] = disp_dst_kinds[slot]
            entry_args[f"disp_regdst{slot}"] = disp_regdsts[slot]
            entry_args[f"disp_pdst{slot}"] = disp_pdsts[slot]
            entry_args[f"disp_imm{slot}"] = disp_imms[slot]
            entry_args[f"disp_is_store{slot}"] = disp_is_stores[slot]
            entry_args[f"disp_is_boundary{slot}"] = disp_is_boundaries[slot]
            entry_args[f"disp_is_bstart{slot}"] = disp_is_bstarts[slot]
            entry_args[f"disp_is_bstop{slot}"] = disp_is_bstops[slot]
            entry_args[f"disp_boundary_kind{slot}"] = disp_boundary_kinds[slot]
            entry_args[f"disp_boundary_target{slot}"] = disp_boundary_targets[slot]
            entry_args[f"disp_pred_take{slot}"] = disp_pred_takes[slot]
            entry_args[f"disp_block_epoch{slot}"] = disp_block_epochs[slot]
            entry_args[f"disp_block_uid{slot}"] = disp_block_uids[slot]
            entry_args[f"disp_block_bid{slot}"] = disp_block_bids[slot]
            entry_args[f"disp_load_store_id{slot}"] = disp_load_store_ids[slot]
            entry_args[f"disp_resolved_d2{slot}"] = disp_resolved_d2s[slot]
            entry_args[f"disp_srcl{slot}"] = disp_srcls[slot]
            entry_args[f"disp_srcr{slot}"] = disp_srcrs[slot]
            entry_args[f"disp_uop_uid{slot}"] = disp_uop_uids[slot]
            entry_args[f"disp_parent_uid{slot}"] = disp_parent_uids[slot]
        for slot in range(issue_w):
            entry_args[f"wb_fire{slot}"] = wb_fires[slot]
            entry_args[f"wb_rob{slot}"] = wb_robs[slot]
            entry_args[f"wb_value{slot}"] = wb_values[slot]
            entry_args[f"store_fire{slot}"] = store_fires[slot]
            entry_args[f"load_fire{slot}"] = load_fires[slot]
            entry_args[f"ex_addr{slot}"] = ex_addrs[slot]
            entry_args[f"ex_wdata{slot}"] = ex_wdatas[slot]
            entry_args[f"ex_size{slot}"] = ex_sizes[slot]
            entry_args[f"ex_src0{slot}"] = ex_src0s[slot]
            entry_args[f"ex_src1{slot}"] = ex_src1s[slot]

        entry = m.new(
            build_rob_entry_update_stage,
            name=f"entry_update_{j}",
            bind=entry_args,
            params={
                "dispatch_w": dispatch_w,
                "issue_w": issue_w,
                "commit_w": commit_w,
                "rob_w": rob_w,
                "ptag_w": ptag_w,
            },
        ).outputs

        valid[j].set(entry["valid_next"])
        done[j].set(entry["done_next"])
        pc[j].set(entry["pc_next"])
        op[j].set(entry["op_next"])
        len_bytes[j].set(entry["len_next"])
        insn_raw[j].set(entry["insn_raw_next"])
        checkpoint_id[j].set(entry["checkpoint_id_next"])
        dst_kind[j].set(entry["dst_kind_next"])
        dst_areg[j].set(entry["dst_areg_next"])
        pdst[j].set(entry["pdst_next"])
        value[j].set(entry["value_next"])
        src0_reg[j].set(entry["src0_reg_next"])
        src1_reg[j].set(entry["src1_reg_next"])
        src0_value[j].set(entry["src0_value_next"])
        src1_value[j].set(entry["src1_value_next"])
        src0_valid[j].set(entry["src0_valid_next"])
        src1_valid[j].set(entry["src1_valid_next"])
        is_store[j].set(entry["is_store_next"])
        store_addr[j].set(entry["store_addr_next"])
        store_data[j].set(entry["store_data_next"])
        store_size[j].set(entry["store_size_next"])
        is_load[j].set(entry["is_load_next"])
        load_addr[j].set(entry["load_addr_next"])
        load_data[j].set(entry["load_data_next"])
        load_size[j].set(entry["load_size_next"])
        is_boundary[j].set(entry["is_boundary_next"])
        is_bstart[j].set(entry["is_bstart_next"])
        is_bstop[j].set(entry["is_bstop_next"])
        boundary_kind[j].set(entry["boundary_kind_next"])
        boundary_target[j].set(entry["boundary_target_next"])
        pred_take[j].set(entry["pred_take_next"])
        block_epoch[j].set(entry["block_epoch_next"])
        block_uid[j].set(entry["block_uid_next"])
        block_bid[j].set(entry["block_bid_next"])
        load_store_id[j].set(entry["load_store_id_next"])
        resolved_d2[j].set(entry["resolved_d2_next"])
        macro_begin[j].set(entry["macro_begin_next"])
        macro_end[j].set(entry["macro_end_next"])
        uop_uid[j].set(entry["uop_uid_next"])
        parent_uid[j].set(entry["parent_uid_next"])

    for j in range(slice_entries):
        m.output(f"valid{j}", valid[j])
        m.output(f"done{j}", done[j])
        m.output(f"pc{j}", pc[j])
        m.output(f"op{j}", op[j])
        m.output(f"len{j}", len_bytes[j])
        m.output(f"insn_raw{j}", insn_raw[j])
        m.output(f"checkpoint_id{j}", checkpoint_id[j])
        m.output(f"dst_kind{j}", dst_kind[j])
        m.output(f"dst_areg{j}", dst_areg[j])
        m.output(f"pdst{j}", pdst[j])
        m.output(f"value{j}", value[j])
        m.output(f"src0_reg{j}", src0_reg[j])
        m.output(f"src1_reg{j}", src1_reg[j])
        m.output(f"src0_value{j}", src0_value[j])
        m.output(f"src1_value{j}", src1_value[j])
        m.output(f"src0_valid{j}", src0_valid[j])
        m.output(f"src1_valid{j}", src1_valid[j])
        m.output(f"is_store{j}", is_store[j])
        m.output(f"store_addr{j}", store_addr[j])
        m.output(f"store_data{j}", store_data[j])
        m.output(f"store_size{j}", store_size[j])
        m.output(f"is_load{j}", is_load[j])
        m.output(f"load_addr{j}", load_addr[j])
        m.output(f"load_data{j}", load_data[j])
        m.output(f"load_size{j}", load_size[j])
        m.output(f"is_boundary{j}", is_boundary[j])
        m.output(f"is_bstart{j}", is_bstart[j])
        m.output(f"is_bstop{j}", is_bstop[j])
        m.output(f"boundary_kind{j}", boundary_kind[j])
        m.output(f"boundary_target{j}", boundary_target[j])
        m.output(f"pred_take{j}", pred_take[j])
        m.output(f"block_epoch{j}", block_epoch[j])
        m.output(f"block_uid{j}", block_uid[j])
        m.output(f"block_bid{j}", block_bid[j])
        m.output(f"load_store_id{j}", load_store_id[j])
        m.output(f"resolved_d2{j}", resolved_d2[j])
        m.output(f"macro_begin{j}", macro_begin[j])
        m.output(f"macro_end{j}", macro_end[j])
        m.output(f"uop_uid{j}", uop_uid[j])
        m.output(f"parent_uid{j}", parent_uid[j])
