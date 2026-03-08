from __future__ import annotations

from pycircuit import Circuit, const, function, module, spec

from common.util import make_consts
from .index_mux import banked_mux_by_uindex
from .recovery_checks import build_lsu_violation_detect_stage
from ..rob import (
    build_rob_commit_read_stage,
    build_rob_ctrl_stage,
    build_rob_entry_update_stage,
    rob_entry_field_defs,
)


ROB_META_QUERY_FIELD_DEFS = (
    ("block_epoch", 16, 0),
    ("checkpoint_id", 6, 0),
    ("block_uid", 64, 0),
    ("block_bid", 64, 0),
    ("load_store_id", 32, 0),
    ("uop_uid", 64, 0),
    ("parent_uid", 64, 0),
)


def _pack_values(m: Circuit, values):
    return m.concat(*reversed(values))


def _pack_fields(m: Circuit, field_defs, values_by_name: dict[str, object]):
    return _pack_values(m, [values_by_name[name] for name, _width in field_defs])


def _field_width_sum(field_defs) -> int:
    total = 0
    for _name, width in field_defs:
        total += int(width)
    return total


def _unpack_fields(pack, field_defs):
    fields = {}
    lsb = 0
    for name, width in field_defs:
        fields[name] = pack.slice(lsb=lsb, width=width)
        lsb += width
    return fields


def _unpack_slot_pack(pack, field_defs, slot: int):
    slot_width = sum(width for _name, width in field_defs)
    return _unpack_fields(pack.slice(lsb=slot * slot_width, width=slot_width), field_defs)


@const
def rob_dispatch_input_slot_field_defs(m: Circuit, *, rob_w: int, ptag_w: int):
    _ = m
    return (
        ("valid", 1),
        ("pc", 64),
        ("op", 12),
        ("len", 3),
        ("insn_raw", 64),
        ("checkpoint_id", 6),
        ("dst_kind", 2),
        ("regdst", 6),
        ("pdst", int(ptag_w)),
        ("imm", 64),
        ("is_store", 1),
        ("is_boundary", 1),
        ("is_bstart", 1),
        ("is_bstop", 1),
        ("boundary_kind", 3),
        ("boundary_target", 64),
        ("pred_take", 1),
        ("block_epoch", 16),
        ("block_uid", 64),
        ("block_bid", 64),
        ("load_store_id", 32),
        ("resolved_d2", 1),
        ("srcl", 6),
        ("srcr", 6),
        ("uop_uid", 64),
        ("parent_uid", 64),
    )


@const
def rob_wb_input_slot_field_defs(m: Circuit, *, rob_w: int):
    _ = m
    return (
        ("fire", 1),
        ("rob", int(rob_w)),
        ("value", 64),
        ("store_fire", 1),
        ("load_fire", 1),
        ("ex_addr", 64),
        ("ex_wdata", 64),
        ("ex_size", 4),
        ("ex_src0", 64),
        ("ex_src1", 64),
    )


@const
def rob_dispatch_slot_field_defs(m: Circuit, *, rob_w: int):
    _ = m
    return (
        ("rob_idx", int(rob_w)),
        ("fire", 1),
    )


@const
def rob_commit_slot_field_defs(m: Circuit, *, rob_w: int, ptag_w: int):
    _ = m
    return (("idx", int(rob_w)),) + tuple(
        (field_name, width) for field_name, width, _default in rob_entry_field_defs(ptag_w=int(ptag_w))
    )


@const
def rob_commit_result_slot_field_defs(m: Circuit, *, ptag_w: int):
    _ = m
    return tuple((field_name, width) for field_name, width, _default in rob_entry_field_defs(ptag_w=int(ptag_w)))


@const
def rob_meta_query_slot_field_defs(m: Circuit):
    _ = m
    return tuple((field_name, width) for field_name, width, _default in ROB_META_QUERY_FIELD_DEFS)


def _declare_rob_bank_common_ports(
    m: Circuit,
    *,
    dispatch_w: int,
    issue_w: int,
    commit_w: int,
    meta_query_slots: int,
    rob_w: int,
    ptag_w: int,
) -> dict[str, object]:
    ports: dict[str, object] = {
        "clk": m.clock("clk"),
        "rst": m.reset("rst"),
        "do_flush": m.input("do_flush", width=1),
        "commit_fires": [m.input(f"commit_fire{slot}", width=1) for slot in range(commit_w)],
        "commit_idxs": [m.input(f"commit_idx{slot}", width=rob_w) for slot in range(commit_w)],
        "disp_fires": [m.input(f"disp_fire{slot}", width=1) for slot in range(dispatch_w)],
        "disp_rob_idxs": [m.input(f"disp_rob_idx{slot}", width=rob_w) for slot in range(dispatch_w)],
        "disp_pcs": [m.input(f"disp_pc{slot}", width=64) for slot in range(dispatch_w)],
        "disp_ops": [m.input(f"disp_op{slot}", width=12) for slot in range(dispatch_w)],
        "disp_lens": [m.input(f"disp_len{slot}", width=3) for slot in range(dispatch_w)],
        "disp_insn_raws": [m.input(f"disp_insn_raw{slot}", width=64) for slot in range(dispatch_w)],
        "disp_checkpoint_ids": [m.input(f"disp_checkpoint_id{slot}", width=6) for slot in range(dispatch_w)],
        "disp_dst_kinds": [m.input(f"disp_dst_kind{slot}", width=2) for slot in range(dispatch_w)],
        "disp_regdsts": [m.input(f"disp_regdst{slot}", width=6) for slot in range(dispatch_w)],
        "disp_pdsts": [m.input(f"disp_pdst{slot}", width=ptag_w) for slot in range(dispatch_w)],
        "disp_imms": [m.input(f"disp_imm{slot}", width=64) for slot in range(dispatch_w)],
        "disp_is_stores": [m.input(f"disp_is_store{slot}", width=1) for slot in range(dispatch_w)],
        "disp_is_boundaries": [m.input(f"disp_is_boundary{slot}", width=1) for slot in range(dispatch_w)],
        "disp_is_bstarts": [m.input(f"disp_is_bstart{slot}", width=1) for slot in range(dispatch_w)],
        "disp_is_bstops": [m.input(f"disp_is_bstop{slot}", width=1) for slot in range(dispatch_w)],
        "disp_boundary_kinds": [m.input(f"disp_boundary_kind{slot}", width=3) for slot in range(dispatch_w)],
        "disp_boundary_targets": [m.input(f"disp_boundary_target{slot}", width=64) for slot in range(dispatch_w)],
        "disp_pred_takes": [m.input(f"disp_pred_take{slot}", width=1) for slot in range(dispatch_w)],
        "disp_block_epochs": [m.input(f"disp_block_epoch{slot}", width=16) for slot in range(dispatch_w)],
        "disp_block_uids": [m.input(f"disp_block_uid{slot}", width=64) for slot in range(dispatch_w)],
        "disp_block_bids": [m.input(f"disp_block_bid{slot}", width=64) for slot in range(dispatch_w)],
        "disp_load_store_ids": [m.input(f"disp_load_store_id{slot}", width=32) for slot in range(dispatch_w)],
        "disp_resolved_d2s": [m.input(f"disp_resolved_d2{slot}", width=1) for slot in range(dispatch_w)],
        "disp_srcls": [m.input(f"disp_srcl{slot}", width=6) for slot in range(dispatch_w)],
        "disp_srcrs": [m.input(f"disp_srcr{slot}", width=6) for slot in range(dispatch_w)],
        "disp_uop_uids": [m.input(f"disp_uop_uid{slot}", width=64) for slot in range(dispatch_w)],
        "disp_parent_uids": [m.input(f"disp_parent_uid{slot}", width=64) for slot in range(dispatch_w)],
        "wb_fires": [m.input(f"wb_fire{slot}", width=1) for slot in range(issue_w)],
        "wb_robs": [m.input(f"wb_rob{slot}", width=rob_w) for slot in range(issue_w)],
        "wb_values": [m.input(f"wb_value{slot}", width=64) for slot in range(issue_w)],
        "store_fires": [m.input(f"store_fire{slot}", width=1) for slot in range(issue_w)],
        "load_fires": [m.input(f"load_fire{slot}", width=1) for slot in range(issue_w)],
        "ex_addrs": [m.input(f"ex_addr{slot}", width=64) for slot in range(issue_w)],
        "ex_wdatas": [m.input(f"ex_wdata{slot}", width=64) for slot in range(issue_w)],
        "ex_sizes": [m.input(f"ex_size{slot}", width=4) for slot in range(issue_w)],
        "ex_src0s": [m.input(f"ex_src0{slot}", width=64) for slot in range(issue_w)],
        "ex_src1s": [m.input(f"ex_src1{slot}", width=64) for slot in range(issue_w)],
        "meta_query_idxs": [m.input(f"meta_query_idx{slot}", width=rob_w) for slot in range(meta_query_slots)],
    }
    return ports


@function
def _populate_rob_bank_common_bind(
    m: Circuit,
    bind: dict[str, object],
    *,
    do_flush: object,
    commit_fires: list[object],
    commit_idxs: list[object],
    disp_fires: list[object],
    disp_rob_idxs: list[object],
    disp_pcs: list[object],
    disp_ops: list[object],
    disp_lens: list[object],
    disp_insn_raws: list[object],
    disp_checkpoint_ids: list[object],
    disp_dst_kinds: list[object],
    disp_regdsts: list[object],
    disp_pdsts: list[object],
    disp_imms: list[object],
    disp_is_stores: list[object],
    disp_is_boundaries: list[object],
    disp_is_bstarts: list[object],
    disp_is_bstops: list[object],
    disp_boundary_kinds: list[object],
    disp_boundary_targets: list[object],
    disp_pred_takes: list[object],
    disp_block_epochs: list[object],
    disp_block_uids: list[object],
    disp_block_bids: list[object],
    disp_load_store_ids: list[object],
    disp_resolved_d2s: list[object],
    disp_srcls: list[object],
    disp_srcrs: list[object],
    disp_uop_uids: list[object],
    disp_parent_uids: list[object],
    wb_fires: list[object],
    wb_robs: list[object],
    wb_values: list[object],
    store_fires: list[object],
    load_fires: list[object],
    ex_addrs: list[object],
    ex_wdatas: list[object],
    ex_sizes: list[object],
    ex_src0s: list[object],
    ex_src1s: list[object],
    meta_query_idxs: list[object],
) -> None:
    _ = m
    bind["do_flush"] = do_flush
    for slot in range(len(commit_fires)):
        bind[f"commit_fire{slot}"] = commit_fires[slot]
        bind[f"commit_idx{slot}"] = commit_idxs[slot]
    for slot in range(len(disp_fires)):
        bind[f"disp_fire{slot}"] = disp_fires[slot]
        bind[f"disp_rob_idx{slot}"] = disp_rob_idxs[slot]
        bind[f"disp_pc{slot}"] = disp_pcs[slot]
        bind[f"disp_op{slot}"] = disp_ops[slot]
        bind[f"disp_len{slot}"] = disp_lens[slot]
        bind[f"disp_insn_raw{slot}"] = disp_insn_raws[slot]
        bind[f"disp_checkpoint_id{slot}"] = disp_checkpoint_ids[slot]
        bind[f"disp_dst_kind{slot}"] = disp_dst_kinds[slot]
        bind[f"disp_regdst{slot}"] = disp_regdsts[slot]
        bind[f"disp_pdst{slot}"] = disp_pdsts[slot]
        bind[f"disp_imm{slot}"] = disp_imms[slot]
        bind[f"disp_is_store{slot}"] = disp_is_stores[slot]
        bind[f"disp_is_boundary{slot}"] = disp_is_boundaries[slot]
        bind[f"disp_is_bstart{slot}"] = disp_is_bstarts[slot]
        bind[f"disp_is_bstop{slot}"] = disp_is_bstops[slot]
        bind[f"disp_boundary_kind{slot}"] = disp_boundary_kinds[slot]
        bind[f"disp_boundary_target{slot}"] = disp_boundary_targets[slot]
        bind[f"disp_pred_take{slot}"] = disp_pred_takes[slot]
        bind[f"disp_block_epoch{slot}"] = disp_block_epochs[slot]
        bind[f"disp_block_uid{slot}"] = disp_block_uids[slot]
        bind[f"disp_block_bid{slot}"] = disp_block_bids[slot]
        bind[f"disp_load_store_id{slot}"] = disp_load_store_ids[slot]
        bind[f"disp_resolved_d2{slot}"] = disp_resolved_d2s[slot]
        bind[f"disp_srcl{slot}"] = disp_srcls[slot]
        bind[f"disp_srcr{slot}"] = disp_srcrs[slot]
        bind[f"disp_uop_uid{slot}"] = disp_uop_uids[slot]
        bind[f"disp_parent_uid{slot}"] = disp_parent_uids[slot]
    for slot in range(len(wb_fires)):
        bind[f"wb_fire{slot}"] = wb_fires[slot]
        bind[f"wb_rob{slot}"] = wb_robs[slot]
        bind[f"wb_value{slot}"] = wb_values[slot]
        bind[f"store_fire{slot}"] = store_fires[slot]
        bind[f"load_fire{slot}"] = load_fires[slot]
        bind[f"ex_addr{slot}"] = ex_addrs[slot]
        bind[f"ex_wdata{slot}"] = ex_wdatas[slot]
        bind[f"ex_size{slot}"] = ex_sizes[slot]
        bind[f"ex_src0{slot}"] = ex_src0s[slot]
        bind[f"ex_src1{slot}"] = ex_src1s[slot]
    for slot in range(len(meta_query_idxs)):
        bind[f"meta_query_idx{slot}"] = meta_query_idxs[slot]


@const
def _rob_entry_update_vector(
    m: Circuit,
    *,
    bank_depth: int,
    dispatch_w: int,
    issue_w: int,
    commit_w: int,
    rob_w: int,
    ptag_w: int,
):
    _ = m
    family = spec.module_family(
        "rob_entry_update_family",
        module=build_rob_entry_update_stage,
        params={
            "dispatch_w": int(dispatch_w),
            "issue_w": int(issue_w),
            "commit_w": int(commit_w),
            "rob_w": int(rob_w),
            "ptag_w": int(ptag_w),
        },
    )
    return family.vector(int(bank_depth), name="rob_entry_update_vec")


@const
def _rob_bank_slice_vector(
    m: Circuit,
    *,
    bank_count: int,
    bank_depth: int,
    dispatch_w: int,
    issue_w: int,
    commit_w: int,
    rob_w: int,
    ptag_w: int,
):
    _ = m
    family = spec.module_family(
        "rob_bank_slice_family",
        module=build_rob_bank_slice,
        params={
            "bank_depth": int(bank_depth),
            "dispatch_w": int(dispatch_w),
            "issue_w": int(issue_w),
            "commit_w": int(commit_w),
            "rob_w": int(rob_w),
            "ptag_w": int(ptag_w),
        },
    )
    return family.vector(int(bank_count), name="rob_bank_slice_vec")


@const
def _rob_commit_read_vector(
    m: Circuit,
    *,
    commit_w: int,
    rob_depth: int,
    rob_w: int,
    ptag_w: int,
):
    _ = m
    family = spec.module_family(
        "rob_commit_read_family",
        module=build_rob_commit_read_stage,
        params={
            "rob_depth": int(rob_depth),
            "rob_w": int(rob_w),
            "ptag_w": int(ptag_w),
        },
    )
    return family.vector(int(commit_w), name="rob_commit_read_vec")


@const
def _rob_bank_commit_vector(
    m: Circuit,
    *,
    bank_count: int,
    bank_depth: int,
    commit_w: int,
    rob_w: int,
    ptag_w: int,
):
    _ = m
    family = spec.module_family(
        "rob_bank_commit_family",
        module=build_rob_bank_commit_stage,
        params={
            "bank_depth": int(bank_depth),
            "commit_w": int(commit_w),
            "rob_w": int(rob_w),
            "ptag_w": int(ptag_w),
        },
    )
    return family.vector(int(bank_count), name="rob_bank_commit_vec")


@const
def _rob_meta_query_vector(
    m: Circuit,
    *,
    query_slots: int,
    rob_depth: int,
    rob_w: int,
):
    _ = m
    family = spec.module_family(
        "rob_meta_query_family",
        module=build_rob_meta_query_stage,
        params={
            "rob_depth": int(rob_depth),
            "rob_w": int(rob_w),
        },
    )
    return family.vector(int(query_slots), name="rob_meta_query_vec")


@module(name="LinxCoreRobMetaQueryStage")
def build_rob_meta_query_stage(
    m: Circuit,
    *,
    rob_depth: int = 64,
    rob_w: int = 6,
) -> None:
    c = m.const
    idx = m.input("idx", width=rob_w)

    field_inputs: dict[str, list[object]] = {}
    for field_name, width, _default in ROB_META_QUERY_FIELD_DEFS:
        field_inputs[field_name] = [m.input(f"{field_name}{i}", width=width) for i in range(rob_depth)]

    for field_name, width, default_value in ROB_META_QUERY_FIELD_DEFS:
        m.output(
            f"{field_name}_o",
            banked_mux_by_uindex(
                m,
                idx=idx,
                items=field_inputs[field_name],
                default=c(default_value, width=width),
                bank_depth=16,
            ),
        )


@module(name="LinxCoreRobLsuStoreScanStage")
def build_rob_lsu_store_scan_stage(
    m: Circuit,
    *,
    rob_depth: int = 64,
    rob_w: int = 6,
) -> None:
    c = m.const
    issue_fire_lane0_raw = m.input("issue_fire_lane0_raw", width=1)
    ex0_is_load = m.input("ex0_is_load", width=1)
    ex0_addr = m.input("ex0_addr", width=64)
    ex0_rob = m.input("ex0_rob", width=rob_w)
    sub_head = m.input("sub_head", width=rob_w)

    rob_valid = [m.input(f"rob_valid{i}", width=1) for i in range(rob_depth)]
    rob_done = [m.input(f"rob_done{i}", width=1) for i in range(rob_depth)]
    rob_is_store = [m.input(f"rob_is_store{i}", width=1) for i in range(rob_depth)]
    rob_store_addr = [m.input(f"rob_store_addr{i}", width=64) for i in range(rob_depth)]
    rob_store_data = [m.input(f"rob_store_data{i}", width=64) for i in range(rob_depth)]

    lsu_load_fire_raw = issue_fire_lane0_raw & ex0_is_load
    lsu_load_dist = ex0_rob + sub_head
    older_store_pending = c(0, width=1)
    forward_hit = c(0, width=1)
    forward_data = c(0, width=64)

    for i in range(rob_depth):
        idx = c(i, width=rob_w)
        dist = idx + sub_head
        older = dist.ult(lsu_load_dist)
        st = rob_valid[i] & rob_is_store[i] & older
        st_pending = st & (~rob_done[i])
        st_ready = st & rob_done[i]
        st_match = st_ready & rob_store_addr[i].__eq__(ex0_addr)
        older_store_pending = older_store_pending | (lsu_load_fire_raw & st_pending)
        forward_hit = forward_hit | (lsu_load_fire_raw & st_match)
        forward_data = (lsu_load_fire_raw & st_match)._select_internal(rob_store_data[i], forward_data)

    m.output("older_store_pending_o", older_store_pending)
    m.output("forward_hit_o", forward_hit)
    m.output("forward_data_o", forward_data)


@module(name="LinxCoreRobBankSlice")
def build_rob_bank_slice(
    m: Circuit,
    *,
    bank_depth: int = 16,
    dispatch_w: int = 4,
    issue_w: int = 4,
    commit_w: int = 4,
    meta_query_slots: int = 9,
    rob_w: int = 6,
    ptag_w: int = 6,
) -> None:
    clk = m.clock("clk")
    rst = m.reset("rst")
    c = m.const
    consts = make_consts(m)
    field_defs = rob_entry_field_defs(ptag_w=ptag_w)
    commit_result_specs = rob_commit_result_slot_field_defs(m, ptag_w=ptag_w)

    bank_base = m.input("bank_base", width=rob_w)
    do_flush = m.input("do_flush", width=1)

    state_regs: dict[str, list[object]] = {}
    for field_name, _width, _default in field_defs:
        state_regs[field_name] = []
    for i in range(bank_depth):
        for field_name, width, default_value in field_defs:
            state_regs[field_name].append(
                m.out(
                    f"{field_name}{i}",
                    clk=clk,
                    rst=rst,
                    width=width,
                    init=c(default_value, width=width),
                    en=consts.one1,
                )
            )

    entry_bind: dict[str, object] = {"do_flush": do_flush}
    commit_idxs: list[object] = []
    for slot in range(commit_w):
        commit_fire = m.input(f"commit_fire{slot}", width=1)
        commit_idx = m.input(f"commit_idx{slot}", width=rob_w)
        commit_idxs.append(commit_idx)
        entry_bind[f"commit_fire{slot}"] = commit_fire
        entry_bind[f"commit_idx{slot}"] = commit_idx
    for slot in range(dispatch_w):
        entry_bind[f"disp_fire{slot}"] = m.input(f"disp_fire{slot}", width=1)
        entry_bind[f"disp_rob_idx{slot}"] = m.input(f"disp_rob_idx{slot}", width=rob_w)
        entry_bind[f"disp_pc{slot}"] = m.input(f"disp_pc{slot}", width=64)
        entry_bind[f"disp_op{slot}"] = m.input(f"disp_op{slot}", width=12)
        entry_bind[f"disp_len{slot}"] = m.input(f"disp_len{slot}", width=3)
        entry_bind[f"disp_insn_raw{slot}"] = m.input(f"disp_insn_raw{slot}", width=64)
        entry_bind[f"disp_checkpoint_id{slot}"] = m.input(f"disp_checkpoint_id{slot}", width=6)
        entry_bind[f"disp_dst_kind{slot}"] = m.input(f"disp_dst_kind{slot}", width=2)
        entry_bind[f"disp_regdst{slot}"] = m.input(f"disp_regdst{slot}", width=6)
        entry_bind[f"disp_pdst{slot}"] = m.input(f"disp_pdst{slot}", width=ptag_w)
        entry_bind[f"disp_imm{slot}"] = m.input(f"disp_imm{slot}", width=64)
        entry_bind[f"disp_is_store{slot}"] = m.input(f"disp_is_store{slot}", width=1)
        entry_bind[f"disp_is_boundary{slot}"] = m.input(f"disp_is_boundary{slot}", width=1)
        entry_bind[f"disp_is_bstart{slot}"] = m.input(f"disp_is_bstart{slot}", width=1)
        entry_bind[f"disp_is_bstop{slot}"] = m.input(f"disp_is_bstop{slot}", width=1)
        entry_bind[f"disp_boundary_kind{slot}"] = m.input(f"disp_boundary_kind{slot}", width=3)
        entry_bind[f"disp_boundary_target{slot}"] = m.input(f"disp_boundary_target{slot}", width=64)
        entry_bind[f"disp_pred_take{slot}"] = m.input(f"disp_pred_take{slot}", width=1)
        entry_bind[f"disp_block_epoch{slot}"] = m.input(f"disp_block_epoch{slot}", width=16)
        entry_bind[f"disp_block_uid{slot}"] = m.input(f"disp_block_uid{slot}", width=64)
        entry_bind[f"disp_block_bid{slot}"] = m.input(f"disp_block_bid{slot}", width=64)
        entry_bind[f"disp_load_store_id{slot}"] = m.input(f"disp_load_store_id{slot}", width=32)
        entry_bind[f"disp_resolved_d2{slot}"] = m.input(f"disp_resolved_d2{slot}", width=1)
        entry_bind[f"disp_srcl{slot}"] = m.input(f"disp_srcl{slot}", width=6)
        entry_bind[f"disp_srcr{slot}"] = m.input(f"disp_srcr{slot}", width=6)
        entry_bind[f"disp_uop_uid{slot}"] = m.input(f"disp_uop_uid{slot}", width=64)
        entry_bind[f"disp_parent_uid{slot}"] = m.input(f"disp_parent_uid{slot}", width=64)
    for slot in range(issue_w):
        entry_bind[f"wb_fire{slot}"] = m.input(f"wb_fire{slot}", width=1)
        entry_bind[f"wb_rob{slot}"] = m.input(f"wb_rob{slot}", width=rob_w)
        entry_bind[f"wb_value{slot}"] = m.input(f"wb_value{slot}", width=64)
        entry_bind[f"store_fire{slot}"] = m.input(f"store_fire{slot}", width=1)
        entry_bind[f"load_fire{slot}"] = m.input(f"load_fire{slot}", width=1)
        entry_bind[f"ex_addr{slot}"] = m.input(f"ex_addr{slot}", width=64)
        entry_bind[f"ex_wdata{slot}"] = m.input(f"ex_wdata{slot}", width=64)
        entry_bind[f"ex_size{slot}"] = m.input(f"ex_size{slot}", width=4)
        entry_bind[f"ex_src0{slot}"] = m.input(f"ex_src0{slot}", width=64)
        entry_bind[f"ex_src1{slot}"] = m.input(f"ex_src1{slot}", width=64)
    meta_query_idxs = [m.input(f"meta_query_idx{slot}", width=rob_w) for slot in range(meta_query_slots)]

    entry_vec = _rob_entry_update_vector(
        m,
        bank_depth=bank_depth,
        dispatch_w=dispatch_w,
        issue_w=issue_w,
        commit_w=commit_w,
        rob_w=rob_w,
        ptag_w=ptag_w,
    )
    per_entry: dict[str, dict[str, object]] = {}
    for i, key in enumerate(entry_vec.keys()):
        per_slot: dict[str, object] = {
            "idx": (bank_base + c(i, width=rob_w))._trunc(width=rob_w),
        }
        for field_name, _width, _default in field_defs:
            per_slot[f"old_{field_name}"] = state_regs[field_name][i].out()
        per_entry[key] = per_slot

    entry_updates = m.array(
        entry_vec,
        name="rob_entry_update",
        bind=entry_bind,
        per=per_entry,
    )
    for i, key in enumerate(entry_vec.keys()):
        entry_out = entry_updates.output(key)
        for field_name, _width, _default in field_defs:
            state_regs[field_name][i].set(entry_out[f"{field_name}_next"])
            m.output(f"{field_name}{i}_o", state_regs[field_name][i].out())

    commit_bind: dict[str, object] = {"bank_base": bank_base}
    for slot in range(commit_w):
        commit_bind[f"commit_idx{slot}"] = commit_idxs[slot]
    for field_name, _width, _default in field_defs:
        for local_idx in range(bank_depth):
            commit_bind[f"{field_name}{local_idx}"] = state_regs[field_name][local_idx].out()
    local_commit = m.new(
        build_rob_bank_commit_stage,
        name="rob_bank_commit_local",
        bind=commit_bind,
        params={
            "bank_depth": bank_depth,
            "commit_w": commit_w,
            "rob_w": rob_w,
            "ptag_w": ptag_w,
        },
    ).outputs
    m.output("commit_hit_mask_o", local_commit["commit_hit_mask_o"])
    m.output("commit_pack_o", local_commit["commit_pack_o"])

    meta_query_bind: dict[str, object] = {"bank_base": bank_base}
    for slot in range(meta_query_slots):
        meta_query_bind[f"query_idx{slot}"] = meta_query_idxs[slot]
    for field_name, _width, _default in ROB_META_QUERY_FIELD_DEFS:
        for local_idx in range(bank_depth):
            meta_query_bind[f"{field_name}{local_idx}"] = state_regs[field_name][local_idx].out()
    local_meta_query = m.new(
        build_rob_bank_meta_query_stage,
        name="rob_bank_meta_query_local",
        bind=meta_query_bind,
        params={
            "bank_depth": bank_depth,
            "query_slots": meta_query_slots,
            "rob_w": rob_w,
        },
    ).outputs
    m.output("query_hit_mask_o", local_meta_query["query_hit_mask_o"])
    m.output("query_pack_o", local_meta_query["query_pack_o"])


@module(name="LinxCoreRobBankCommitStage")
def build_rob_bank_commit_stage(
    m: Circuit,
    *,
    bank_depth: int = 8,
    commit_w: int = 4,
    rob_w: int = 6,
    ptag_w: int = 6,
) -> None:
    c = m.const
    field_defs = rob_entry_field_defs(ptag_w=ptag_w)
    commit_specs = rob_commit_result_slot_field_defs(m, ptag_w=ptag_w)

    bank_base = m.input("bank_base", width=rob_w)
    commit_idxs = [m.input(f"commit_idx{slot}", width=rob_w) for slot in range(commit_w)]

    local_commit_bind: dict[str, object] = {}
    for field_name, width, _default in field_defs:
        for i in range(bank_depth):
            local_commit_bind[f"{field_name}{i}"] = m.input(f"{field_name}{i}", width=width)

    local_commit_vec = _rob_commit_read_vector(
        m,
        commit_w=commit_w,
        rob_depth=bank_depth,
        rob_w=rob_w,
        ptag_w=ptag_w,
    )
    bank_base_neg = (~bank_base) + c(1, width=rob_w)
    per_commit: dict[str, dict[str, object]] = {}
    for slot, key in enumerate(local_commit_vec.keys()):
        per_commit[key] = {"idx": (commit_idxs[slot] + bank_base_neg)._trunc(width=rob_w)}

    local_commit_reads = m.array(
        local_commit_vec,
        name="rob_commit_read",
        bind=local_commit_bind,
        per=per_commit,
    )

    bank_high = (bank_base + c(bank_depth - 1, width=rob_w))._trunc(width=rob_w)
    commit_hits = []
    commit_slot_packs = []
    for slot, key in enumerate(local_commit_vec.keys()):
        commit_hit = commit_idxs[slot].uge(bank_base) & commit_idxs[slot].ule(bank_high)
        commit_out = local_commit_reads.output(key)
        commit_hits.append(commit_hit)
        commit_values = {}
        for field_name, width, default_value in field_defs:
            commit_values[field_name] = commit_hit._select_internal(
                commit_out[f"{field_name}_o"],
                c(default_value, width=width),
            )
        commit_slot_packs.append(_pack_fields(m, commit_specs, commit_values))
    m.output("commit_hit_mask_o", _pack_values(m, commit_hits))
    m.output("commit_pack_o", _pack_values(m, commit_slot_packs))


@module(name="LinxCoreRobBankMetaQueryStage")
def build_rob_bank_meta_query_stage(
    m: Circuit,
    *,
    bank_depth: int = 8,
    query_slots: int = 9,
    rob_w: int = 6,
) -> None:
    c = m.const
    meta_specs = rob_meta_query_slot_field_defs(m)

    bank_base = m.input("bank_base", width=rob_w)
    query_idxs = [m.input(f"query_idx{slot}", width=rob_w) for slot in range(query_slots)]

    local_query_bind: dict[str, object] = {}
    for field_name, width, _default in ROB_META_QUERY_FIELD_DEFS:
        for local_idx in range(bank_depth):
            local_query_bind[f"{field_name}{local_idx}"] = m.input(f"{field_name}{local_idx}", width=width)

    local_query_vec = _rob_meta_query_vector(
        m,
        query_slots=query_slots,
        rob_depth=bank_depth,
        rob_w=rob_w,
    )
    bank_base_neg = (~bank_base) + c(1, width=rob_w)
    per_query: dict[str, dict[str, object]] = {}
    for slot, key in enumerate(local_query_vec.keys()):
        per_query[key] = {"idx": (query_idxs[slot] + bank_base_neg)._trunc(width=rob_w)}

    local_queries = m.array(
        local_query_vec,
        name="rob_meta_query_local",
        bind=local_query_bind,
        per=per_query,
    )

    bank_high = (bank_base + c(bank_depth - 1, width=rob_w))._trunc(width=rob_w)
    query_hits = []
    query_slot_packs = []
    for slot, key in enumerate(local_query_vec.keys()):
        query_hit = query_idxs[slot].uge(bank_base) & query_idxs[slot].ule(bank_high)
        query_out = local_queries.output(key)
        query_hits.append(query_hit)
        query_values = {}
        for field_name, width, default_value in ROB_META_QUERY_FIELD_DEFS:
            query_values[field_name] = query_hit._select_internal(
                query_out[f"{field_name}_o"],
                c(default_value, width=width),
            )
        query_slot_packs.append(_pack_fields(m, meta_specs, query_values))
    m.output("query_hit_mask_o", _pack_values(m, query_hits))
    m.output("query_pack_o", _pack_values(m, query_slot_packs))


@module(name="LinxCoreRobBankCommitPair")
def build_rob_bank_commit_pair(
    m: Circuit,
    *,
    bank_depth: int = 8,
    commit_w: int = 4,
    rob_w: int = 6,
    ptag_w: int = 6,
) -> None:
    c = m.const
    field_defs = rob_entry_field_defs(ptag_w=ptag_w)
    commit_specs = rob_commit_result_slot_field_defs(m, ptag_w=ptag_w)

    bank_base = m.input("bank_base", width=rob_w)
    commit_idxs = [m.input(f"commit_idx{slot}", width=rob_w) for slot in range(commit_w)]

    left_bind: dict[str, object] = {"bank_base": bank_base}
    right_bind: dict[str, object] = {"bank_base": (bank_base + c(bank_depth, width=rob_w))._trunc(width=rob_w)}
    for slot in range(commit_w):
        left_bind[f"commit_idx{slot}"] = commit_idxs[slot]
        right_bind[f"commit_idx{slot}"] = commit_idxs[slot]
    for field_name, width, _default in field_defs:
        for local_idx in range(bank_depth):
            left_bind[f"{field_name}{local_idx}"] = m.input(f"{field_name}{local_idx}", width=width)
            right_bind[f"{field_name}{local_idx}"] = m.input(f"{field_name}{bank_depth + local_idx}", width=width)

    left_commit = m.new(
        build_rob_bank_commit_stage,
        name="rob_bank_commit_left",
        bind=left_bind,
        params={
            "bank_depth": bank_depth,
            "commit_w": commit_w,
            "rob_w": rob_w,
            "ptag_w": ptag_w,
        },
    ).outputs
    right_commit = m.new(
        build_rob_bank_commit_stage,
        name="rob_bank_commit_right",
        bind=right_bind,
        params={
            "bank_depth": bank_depth,
            "commit_w": commit_w,
            "rob_w": rob_w,
            "ptag_w": ptag_w,
        },
    ).outputs

    commit_hits = []
    commit_slot_packs = []
    for slot in range(commit_w):
        left_hit = left_commit["commit_hit_mask_o"].slice(lsb=slot, width=1)
        right_hit = right_commit["commit_hit_mask_o"].slice(lsb=slot, width=1)
        left_fields = _unpack_slot_pack(left_commit["commit_pack_o"], commit_specs, slot)
        right_fields = _unpack_slot_pack(right_commit["commit_pack_o"], commit_specs, slot)
        commit_hits.append(left_hit | right_hit)
        commit_values = {}
        for field_name, width, default_value in field_defs:
            pair_value = left_hit._select_internal(left_fields[field_name], c(default_value, width=width))
            pair_value = right_hit._select_internal(
                right_fields[field_name],
                pair_value,
            )
            commit_values[field_name] = pair_value
        commit_slot_packs.append(_pack_fields(m, commit_specs, commit_values))
    m.output("commit_hit_mask_o", _pack_values(m, commit_hits))
    m.output("commit_pack_o", _pack_values(m, commit_slot_packs))


@module(name="LinxCoreRobBankCommitQuad")
def build_rob_bank_commit_quad(
    m: Circuit,
    *,
    bank_depth: int = 8,
    commit_w: int = 4,
    rob_w: int = 6,
    ptag_w: int = 6,
) -> None:
    c = m.const
    field_defs = rob_entry_field_defs(ptag_w=ptag_w)
    commit_specs = rob_commit_result_slot_field_defs(m, ptag_w=ptag_w)
    pair_span = 2 * bank_depth

    bank_base = m.input("bank_base", width=rob_w)
    commit_idxs = [m.input(f"commit_idx{slot}", width=rob_w) for slot in range(commit_w)]

    left_bind: dict[str, object] = {"bank_base": bank_base}
    right_bind: dict[str, object] = {"bank_base": (bank_base + c(pair_span, width=rob_w))._trunc(width=rob_w)}
    for slot in range(commit_w):
        left_bind[f"commit_idx{slot}"] = commit_idxs[slot]
        right_bind[f"commit_idx{slot}"] = commit_idxs[slot]
    for field_name, width, _default in field_defs:
        for local_idx in range(pair_span):
            left_bind[f"{field_name}{local_idx}"] = m.input(f"{field_name}{local_idx}", width=width)
            right_bind[f"{field_name}{local_idx}"] = m.input(f"{field_name}{pair_span + local_idx}", width=width)

    left_commit = m.new(
        build_rob_bank_commit_pair,
        name="rob_bank_commit_left_pair",
        bind=left_bind,
        params={
            "bank_depth": bank_depth,
            "commit_w": commit_w,
            "rob_w": rob_w,
            "ptag_w": ptag_w,
        },
    ).outputs
    right_commit = m.new(
        build_rob_bank_commit_pair,
        name="rob_bank_commit_right_pair",
        bind=right_bind,
        params={
            "bank_depth": bank_depth,
            "commit_w": commit_w,
            "rob_w": rob_w,
            "ptag_w": ptag_w,
        },
    ).outputs

    commit_hits = []
    commit_slot_packs = []
    for slot in range(commit_w):
        left_hit = left_commit["commit_hit_mask_o"].slice(lsb=slot, width=1)
        right_hit = right_commit["commit_hit_mask_o"].slice(lsb=slot, width=1)
        left_fields = _unpack_slot_pack(left_commit["commit_pack_o"], commit_specs, slot)
        right_fields = _unpack_slot_pack(right_commit["commit_pack_o"], commit_specs, slot)
        commit_hits.append(left_hit | right_hit)
        commit_values = {}
        for field_name, width, default_value in field_defs:
            quad_value = left_hit._select_internal(left_fields[field_name], c(default_value, width=width))
            quad_value = right_hit._select_internal(
                right_fields[field_name],
                quad_value,
            )
            commit_values[field_name] = quad_value
        commit_slot_packs.append(_pack_fields(m, commit_specs, commit_values))
    m.output("commit_hit_mask_o", _pack_values(m, commit_hits))
    m.output("commit_pack_o", _pack_values(m, commit_slot_packs))


@module(name="LinxCoreRobBankPair")
def build_rob_bank_pair(
    m: Circuit,
    *,
    bank_depth: int = 8,
    dispatch_w: int = 4,
    issue_w: int = 4,
    commit_w: int = 4,
    meta_query_slots: int = 9,
    rob_w: int = 6,
    ptag_w: int = 6,
) -> None:
    c = m.const
    field_defs = rob_entry_field_defs(ptag_w=ptag_w)
    commit_specs = rob_commit_result_slot_field_defs(m, ptag_w=ptag_w)
    meta_specs = rob_meta_query_slot_field_defs(m)
    ports = _declare_rob_bank_common_ports(
        m,
        dispatch_w=dispatch_w,
        issue_w=issue_w,
        commit_w=commit_w,
        meta_query_slots=meta_query_slots,
        rob_w=rob_w,
        ptag_w=ptag_w,
    )
    bank_base = m.input("bank_base", width=rob_w)

    left_bind: dict[str, object] = {"clk": ports["clk"], "rst": ports["rst"]}
    _populate_rob_bank_common_bind(
        m,
        left_bind,
        do_flush=ports["do_flush"],
        commit_fires=ports["commit_fires"],
        commit_idxs=ports["commit_idxs"],
        disp_fires=ports["disp_fires"],
        disp_rob_idxs=ports["disp_rob_idxs"],
        disp_pcs=ports["disp_pcs"],
        disp_ops=ports["disp_ops"],
        disp_lens=ports["disp_lens"],
        disp_insn_raws=ports["disp_insn_raws"],
        disp_checkpoint_ids=ports["disp_checkpoint_ids"],
        disp_dst_kinds=ports["disp_dst_kinds"],
        disp_regdsts=ports["disp_regdsts"],
        disp_pdsts=ports["disp_pdsts"],
        disp_imms=ports["disp_imms"],
        disp_is_stores=ports["disp_is_stores"],
        disp_is_boundaries=ports["disp_is_boundaries"],
        disp_is_bstarts=ports["disp_is_bstarts"],
        disp_is_bstops=ports["disp_is_bstops"],
        disp_boundary_kinds=ports["disp_boundary_kinds"],
        disp_boundary_targets=ports["disp_boundary_targets"],
        disp_pred_takes=ports["disp_pred_takes"],
        disp_block_epochs=ports["disp_block_epochs"],
        disp_block_uids=ports["disp_block_uids"],
        disp_block_bids=ports["disp_block_bids"],
        disp_load_store_ids=ports["disp_load_store_ids"],
        disp_resolved_d2s=ports["disp_resolved_d2s"],
        disp_srcls=ports["disp_srcls"],
        disp_srcrs=ports["disp_srcrs"],
        disp_uop_uids=ports["disp_uop_uids"],
        disp_parent_uids=ports["disp_parent_uids"],
        wb_fires=ports["wb_fires"],
        wb_robs=ports["wb_robs"],
        wb_values=ports["wb_values"],
        store_fires=ports["store_fires"],
        load_fires=ports["load_fires"],
        ex_addrs=ports["ex_addrs"],
        ex_wdatas=ports["ex_wdatas"],
        ex_sizes=ports["ex_sizes"],
        ex_src0s=ports["ex_src0s"],
        ex_src1s=ports["ex_src1s"],
        meta_query_idxs=ports["meta_query_idxs"],
    )
    left_bind["bank_base"] = bank_base
    left_slice = m.new(
        build_rob_bank_slice,
        name="rob_bank_left",
        bind=left_bind,
        params={
            "bank_depth": bank_depth,
            "dispatch_w": dispatch_w,
            "issue_w": issue_w,
            "commit_w": commit_w,
            "meta_query_slots": meta_query_slots,
            "rob_w": rob_w,
            "ptag_w": ptag_w,
        },
    ).outputs

    right_bind: dict[str, object] = {"clk": ports["clk"], "rst": ports["rst"]}
    _populate_rob_bank_common_bind(
        m,
        right_bind,
        do_flush=ports["do_flush"],
        commit_fires=ports["commit_fires"],
        commit_idxs=ports["commit_idxs"],
        disp_fires=ports["disp_fires"],
        disp_rob_idxs=ports["disp_rob_idxs"],
        disp_pcs=ports["disp_pcs"],
        disp_ops=ports["disp_ops"],
        disp_lens=ports["disp_lens"],
        disp_insn_raws=ports["disp_insn_raws"],
        disp_checkpoint_ids=ports["disp_checkpoint_ids"],
        disp_dst_kinds=ports["disp_dst_kinds"],
        disp_regdsts=ports["disp_regdsts"],
        disp_pdsts=ports["disp_pdsts"],
        disp_imms=ports["disp_imms"],
        disp_is_stores=ports["disp_is_stores"],
        disp_is_boundaries=ports["disp_is_boundaries"],
        disp_is_bstarts=ports["disp_is_bstarts"],
        disp_is_bstops=ports["disp_is_bstops"],
        disp_boundary_kinds=ports["disp_boundary_kinds"],
        disp_boundary_targets=ports["disp_boundary_targets"],
        disp_pred_takes=ports["disp_pred_takes"],
        disp_block_epochs=ports["disp_block_epochs"],
        disp_block_uids=ports["disp_block_uids"],
        disp_block_bids=ports["disp_block_bids"],
        disp_load_store_ids=ports["disp_load_store_ids"],
        disp_resolved_d2s=ports["disp_resolved_d2s"],
        disp_srcls=ports["disp_srcls"],
        disp_srcrs=ports["disp_srcrs"],
        disp_uop_uids=ports["disp_uop_uids"],
        disp_parent_uids=ports["disp_parent_uids"],
        wb_fires=ports["wb_fires"],
        wb_robs=ports["wb_robs"],
        wb_values=ports["wb_values"],
        store_fires=ports["store_fires"],
        load_fires=ports["load_fires"],
        ex_addrs=ports["ex_addrs"],
        ex_wdatas=ports["ex_wdatas"],
        ex_sizes=ports["ex_sizes"],
        ex_src0s=ports["ex_src0s"],
        ex_src1s=ports["ex_src1s"],
        meta_query_idxs=ports["meta_query_idxs"],
    )
    right_bind["bank_base"] = (bank_base + c(bank_depth, width=rob_w))._trunc(width=rob_w)
    right_slice = m.new(
        build_rob_bank_slice,
        name="rob_bank_right",
        bind=right_bind,
        params={
            "bank_depth": bank_depth,
            "dispatch_w": dispatch_w,
            "issue_w": issue_w,
            "commit_w": commit_w,
            "meta_query_slots": meta_query_slots,
            "rob_w": rob_w,
            "ptag_w": ptag_w,
        },
    ).outputs

    for field_name, _width, _default in field_defs:
        for local_idx in range(bank_depth):
            m.output(f"{field_name}{local_idx}_o", left_slice[f"{field_name}{local_idx}_o"])
            m.output(f"{field_name}{bank_depth + local_idx}_o", right_slice[f"{field_name}{local_idx}_o"])
    commit_hits = []
    commit_slot_packs = []
    for slot in range(commit_w):
        left_hit = left_slice["commit_hit_mask_o"].slice(lsb=slot, width=1)
        right_hit = right_slice["commit_hit_mask_o"].slice(lsb=slot, width=1)
        left_fields = _unpack_slot_pack(left_slice["commit_pack_o"], commit_specs, slot)
        right_fields = _unpack_slot_pack(right_slice["commit_pack_o"], commit_specs, slot)
        commit_hits.append(left_hit | right_hit)
        commit_values = {}
        for field_name, width, default_value in field_defs:
            pair_commit = left_hit._select_internal(left_fields[field_name], c(default_value, width=width))
            pair_commit = right_hit._select_internal(
                right_fields[field_name],
                pair_commit,
            )
            commit_values[field_name] = pair_commit
        commit_slot_packs.append(_pack_fields(m, commit_specs, commit_values))
    m.output("commit_hit_mask_o", _pack_values(m, commit_hits))
    m.output("commit_pack_o", _pack_values(m, commit_slot_packs))
    query_hits = []
    query_slot_packs = []
    for slot in range(meta_query_slots):
        left_hit = left_slice["query_hit_mask_o"].slice(lsb=slot, width=1)
        right_hit = right_slice["query_hit_mask_o"].slice(lsb=slot, width=1)
        left_fields = _unpack_slot_pack(left_slice["query_pack_o"], meta_specs, slot)
        right_fields = _unpack_slot_pack(right_slice["query_pack_o"], meta_specs, slot)
        query_hits.append(left_hit | right_hit)
        query_values = {}
        for field_name, width, default_value in ROB_META_QUERY_FIELD_DEFS:
            pair_query = left_hit._select_internal(left_fields[field_name], c(default_value, width=width))
            pair_query = right_hit._select_internal(
                right_fields[field_name],
                pair_query,
            )
            query_values[field_name] = pair_query
        query_slot_packs.append(_pack_fields(m, meta_specs, query_values))
    m.output("query_hit_mask_o", _pack_values(m, query_hits))
    m.output("query_pack_o", _pack_values(m, query_slot_packs))


@module(name="LinxCoreRobBankQuad")
def build_rob_bank_quad(
    m: Circuit,
    *,
    bank_depth: int = 8,
    dispatch_w: int = 4,
    issue_w: int = 4,
    commit_w: int = 4,
    meta_query_slots: int = 9,
    rob_w: int = 6,
    ptag_w: int = 6,
) -> None:
    c = m.const
    field_defs = rob_entry_field_defs(ptag_w=ptag_w)
    commit_specs = rob_commit_result_slot_field_defs(m, ptag_w=ptag_w)
    meta_specs = rob_meta_query_slot_field_defs(m)
    ports = _declare_rob_bank_common_ports(
        m,
        dispatch_w=dispatch_w,
        issue_w=issue_w,
        commit_w=commit_w,
        meta_query_slots=meta_query_slots,
        rob_w=rob_w,
        ptag_w=ptag_w,
    )
    bank_base = m.input("bank_base", width=rob_w)

    left_bind: dict[str, object] = {"clk": ports["clk"], "rst": ports["rst"]}
    _populate_rob_bank_common_bind(
        m,
        left_bind,
        do_flush=ports["do_flush"],
        commit_fires=ports["commit_fires"],
        commit_idxs=ports["commit_idxs"],
        disp_fires=ports["disp_fires"],
        disp_rob_idxs=ports["disp_rob_idxs"],
        disp_pcs=ports["disp_pcs"],
        disp_ops=ports["disp_ops"],
        disp_lens=ports["disp_lens"],
        disp_insn_raws=ports["disp_insn_raws"],
        disp_checkpoint_ids=ports["disp_checkpoint_ids"],
        disp_dst_kinds=ports["disp_dst_kinds"],
        disp_regdsts=ports["disp_regdsts"],
        disp_pdsts=ports["disp_pdsts"],
        disp_imms=ports["disp_imms"],
        disp_is_stores=ports["disp_is_stores"],
        disp_is_boundaries=ports["disp_is_boundaries"],
        disp_is_bstarts=ports["disp_is_bstarts"],
        disp_is_bstops=ports["disp_is_bstops"],
        disp_boundary_kinds=ports["disp_boundary_kinds"],
        disp_boundary_targets=ports["disp_boundary_targets"],
        disp_pred_takes=ports["disp_pred_takes"],
        disp_block_epochs=ports["disp_block_epochs"],
        disp_block_uids=ports["disp_block_uids"],
        disp_block_bids=ports["disp_block_bids"],
        disp_load_store_ids=ports["disp_load_store_ids"],
        disp_resolved_d2s=ports["disp_resolved_d2s"],
        disp_srcls=ports["disp_srcls"],
        disp_srcrs=ports["disp_srcrs"],
        disp_uop_uids=ports["disp_uop_uids"],
        disp_parent_uids=ports["disp_parent_uids"],
        wb_fires=ports["wb_fires"],
        wb_robs=ports["wb_robs"],
        wb_values=ports["wb_values"],
        store_fires=ports["store_fires"],
        load_fires=ports["load_fires"],
        ex_addrs=ports["ex_addrs"],
        ex_wdatas=ports["ex_wdatas"],
        ex_sizes=ports["ex_sizes"],
        ex_src0s=ports["ex_src0s"],
        ex_src1s=ports["ex_src1s"],
        meta_query_idxs=ports["meta_query_idxs"],
    )
    left_bind["bank_base"] = bank_base
    left_quad = m.new(
        build_rob_bank_pair,
        name="rob_bank_left_pair",
        bind=left_bind,
        params={
            "bank_depth": bank_depth,
            "dispatch_w": dispatch_w,
            "issue_w": issue_w,
            "commit_w": commit_w,
            "meta_query_slots": meta_query_slots,
            "rob_w": rob_w,
            "ptag_w": ptag_w,
        },
    ).outputs

    right_bind: dict[str, object] = {"clk": ports["clk"], "rst": ports["rst"]}
    _populate_rob_bank_common_bind(
        m,
        right_bind,
        do_flush=ports["do_flush"],
        commit_fires=ports["commit_fires"],
        commit_idxs=ports["commit_idxs"],
        disp_fires=ports["disp_fires"],
        disp_rob_idxs=ports["disp_rob_idxs"],
        disp_pcs=ports["disp_pcs"],
        disp_ops=ports["disp_ops"],
        disp_lens=ports["disp_lens"],
        disp_insn_raws=ports["disp_insn_raws"],
        disp_checkpoint_ids=ports["disp_checkpoint_ids"],
        disp_dst_kinds=ports["disp_dst_kinds"],
        disp_regdsts=ports["disp_regdsts"],
        disp_pdsts=ports["disp_pdsts"],
        disp_imms=ports["disp_imms"],
        disp_is_stores=ports["disp_is_stores"],
        disp_is_boundaries=ports["disp_is_boundaries"],
        disp_is_bstarts=ports["disp_is_bstarts"],
        disp_is_bstops=ports["disp_is_bstops"],
        disp_boundary_kinds=ports["disp_boundary_kinds"],
        disp_boundary_targets=ports["disp_boundary_targets"],
        disp_pred_takes=ports["disp_pred_takes"],
        disp_block_epochs=ports["disp_block_epochs"],
        disp_block_uids=ports["disp_block_uids"],
        disp_block_bids=ports["disp_block_bids"],
        disp_load_store_ids=ports["disp_load_store_ids"],
        disp_resolved_d2s=ports["disp_resolved_d2s"],
        disp_srcls=ports["disp_srcls"],
        disp_srcrs=ports["disp_srcrs"],
        disp_uop_uids=ports["disp_uop_uids"],
        disp_parent_uids=ports["disp_parent_uids"],
        wb_fires=ports["wb_fires"],
        wb_robs=ports["wb_robs"],
        wb_values=ports["wb_values"],
        store_fires=ports["store_fires"],
        load_fires=ports["load_fires"],
        ex_addrs=ports["ex_addrs"],
        ex_wdatas=ports["ex_wdatas"],
        ex_sizes=ports["ex_sizes"],
        ex_src0s=ports["ex_src0s"],
        ex_src1s=ports["ex_src1s"],
        meta_query_idxs=ports["meta_query_idxs"],
    )
    right_bind["bank_base"] = (bank_base + c(2 * bank_depth, width=rob_w))._trunc(width=rob_w)
    right_quad = m.new(
        build_rob_bank_pair,
        name="rob_bank_right_pair",
        bind=right_bind,
        params={
            "bank_depth": bank_depth,
            "dispatch_w": dispatch_w,
            "issue_w": issue_w,
            "commit_w": commit_w,
            "meta_query_slots": meta_query_slots,
            "rob_w": rob_w,
            "ptag_w": ptag_w,
        },
    ).outputs

    pair_span = 2 * bank_depth
    for field_name, _width, _default in field_defs:
        for local_idx in range(pair_span):
            m.output(f"{field_name}{local_idx}_o", left_quad[f"{field_name}{local_idx}_o"])
            m.output(f"{field_name}{pair_span + local_idx}_o", right_quad[f"{field_name}{local_idx}_o"])
    commit_hits = []
    commit_slot_packs = []
    for slot in range(commit_w):
        left_hit = left_quad["commit_hit_mask_o"].slice(lsb=slot, width=1)
        right_hit = right_quad["commit_hit_mask_o"].slice(lsb=slot, width=1)
        left_fields = _unpack_slot_pack(left_quad["commit_pack_o"], commit_specs, slot)
        right_fields = _unpack_slot_pack(right_quad["commit_pack_o"], commit_specs, slot)
        commit_hits.append(left_hit | right_hit)
        commit_values = {}
        for field_name, width, default_value in field_defs:
            quad_commit = left_hit._select_internal(left_fields[field_name], c(default_value, width=width))
            quad_commit = right_hit._select_internal(
                right_fields[field_name],
                quad_commit,
            )
            commit_values[field_name] = quad_commit
        commit_slot_packs.append(_pack_fields(m, commit_specs, commit_values))
    m.output("commit_hit_mask_o", _pack_values(m, commit_hits))
    m.output("commit_pack_o", _pack_values(m, commit_slot_packs))
    query_hits = []
    query_slot_packs = []
    for slot in range(meta_query_slots):
        left_hit = left_quad["query_hit_mask_o"].slice(lsb=slot, width=1)
        right_hit = right_quad["query_hit_mask_o"].slice(lsb=slot, width=1)
        left_fields = _unpack_slot_pack(left_quad["query_pack_o"], meta_specs, slot)
        right_fields = _unpack_slot_pack(right_quad["query_pack_o"], meta_specs, slot)
        query_hits.append(left_hit | right_hit)
        query_values = {}
        for field_name, width, default_value in ROB_META_QUERY_FIELD_DEFS:
            quad_query = left_hit._select_internal(left_fields[field_name], c(default_value, width=width))
            quad_query = right_hit._select_internal(
                right_fields[field_name],
                quad_query,
            )
            query_values[field_name] = quad_query
        query_slot_packs.append(_pack_fields(m, meta_specs, query_values))
    m.output("query_hit_mask_o", _pack_values(m, query_hits))
    m.output("query_pack_o", _pack_values(m, query_slot_packs))


@module(name="LinxCoreRobBankTop")
def build_rob_bank_top(
    m: Circuit,
    *,
    dispatch_w: int = 4,
    issue_w: int = 4,
    commit_w: int = 4,
    rob_depth: int = 64,
    rob_w: int = 6,
    ptag_w: int = 6,
    meta_query_slots: int = 9,
) -> None:
    clk = m.clock("clk")
    rst = m.reset("rst")
    c = m.const
    consts = make_consts(m)
    field_defs = rob_entry_field_defs(ptag_w=ptag_w)
    commit_result_specs = rob_commit_result_slot_field_defs(m, ptag_w=ptag_w)

    do_flush = m.input("do_flush", width=1)

    commit_count = m.input("commit_count", width=3)
    disp_count = m.input("disp_count", width=3)
    dispatch_fire = m.input("dispatch_fire", width=1)
    commit_fire_mask = m.input("commit_fire_mask", width=commit_w)
    disp_specs = rob_dispatch_input_slot_field_defs(m, rob_w=rob_w, ptag_w=ptag_w)
    disp_slot_width = _field_width_sum(disp_specs)
    disp_pack = m.input(
        "disp_pack",
        width=dispatch_w * disp_slot_width,
    )
    wb_specs = rob_wb_input_slot_field_defs(m, rob_w=rob_w)
    wb_slot_width = _field_width_sum(wb_specs)
    wb_pack = m.input(
        "wb_pack",
        width=issue_w * wb_slot_width,
    )
    replay_pending = m.input("replay_pending", width=1)
    issue_fire_lane0_raw = m.input("issue_fire_lane0_raw", width=1)
    ex0_is_load = m.input("ex0_is_load", width=1)
    ex0_addr = m.input("ex0_addr", width=64)
    ex0_rob = m.input("ex0_rob", width=rob_w)
    meta_query_idx_pack = m.input("meta_query_idx_pack", width=meta_query_slots * rob_w)

    disp_valids = []
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
        disp_fields = _unpack_slot_pack(disp_pack, disp_specs, slot)
        disp_valids.append(disp_fields["valid"])
        disp_pcs.append(disp_fields["pc"])
        disp_ops.append(disp_fields["op"])
        disp_lens.append(disp_fields["len"])
        disp_insn_raws.append(disp_fields["insn_raw"])
        disp_checkpoint_ids.append(disp_fields["checkpoint_id"])
        disp_dst_kinds.append(disp_fields["dst_kind"])
        disp_regdsts.append(disp_fields["regdst"])
        disp_pdsts.append(disp_fields["pdst"])
        disp_imms.append(disp_fields["imm"])
        disp_is_stores.append(disp_fields["is_store"])
        disp_is_boundaries.append(disp_fields["is_boundary"])
        disp_is_bstarts.append(disp_fields["is_bstart"])
        disp_is_bstops.append(disp_fields["is_bstop"])
        disp_boundary_kinds.append(disp_fields["boundary_kind"])
        disp_boundary_targets.append(disp_fields["boundary_target"])
        disp_pred_takes.append(disp_fields["pred_take"])
        disp_block_epochs.append(disp_fields["block_epoch"])
        disp_block_uids.append(disp_fields["block_uid"])
        disp_block_bids.append(disp_fields["block_bid"])
        disp_load_store_ids.append(disp_fields["load_store_id"])
        disp_resolved_d2s.append(disp_fields["resolved_d2"])
        disp_srcls.append(disp_fields["srcl"])
        disp_srcrs.append(disp_fields["srcr"])
        disp_uop_uids.append(disp_fields["uop_uid"])
        disp_parent_uids.append(disp_fields["parent_uid"])

    commit_fires = []
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
    for slot in range(commit_w):
        commit_fires.append(commit_fire_mask.slice(lsb=slot, width=1))
    for slot in range(issue_w):
        wb_fields = _unpack_slot_pack(wb_pack, wb_specs, slot)
        wb_fires.append(wb_fields["fire"])
        wb_robs.append(wb_fields["rob"])
        wb_values.append(wb_fields["value"])
        store_fires.append(wb_fields["store_fire"])
        load_fires.append(wb_fields["load_fire"])
        ex_addrs.append(wb_fields["ex_addr"])
        ex_wdatas.append(wb_fields["ex_wdata"])
        ex_sizes.append(wb_fields["ex_size"])
        ex_src0s.append(wb_fields["ex_src0"])
        ex_src1s.append(wb_fields["ex_src1"])
    meta_query_idxs = [meta_query_idx_pack.slice(lsb=slot * rob_w, width=rob_w) for slot in range(meta_query_slots)]

    with m.scope("rob"):
        head = m.out("head", clk=clk, rst=rst, width=rob_w, init=c(0, width=rob_w), en=consts.one1)
        tail = m.out("tail", clk=clk, rst=rst, width=rob_w, init=c(0, width=rob_w), en=consts.one1)
        count = m.out("count", clk=clk, rst=rst, width=rob_w + 1, init=c(0, width=rob_w + 1), en=consts.one1)

    commit_fire = ~commit_count.__eq__(c(0, width=3))
    commit_idxs_now = [(head.out() + c(slot, width=rob_w))._trunc(width=rob_w) for slot in range(commit_w)]

    ctrl_ports = {
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
        ctrl_ports[f"disp_valid{slot}"] = disp_valids[slot]

    rob_ctrl = m.new(
        build_rob_ctrl_stage,
        name="rob_ctrl",
        bind=ctrl_ports,
        params={"dispatch_w": dispatch_w, "rob_w": rob_w},
    )

    head_next = rob_ctrl.outputs["head_next"]
    tail_next = rob_ctrl.outputs["tail_next"]
    count_next = rob_ctrl.outputs["count_next"]
    disp_rob_idxs = [rob_ctrl.outputs[f"disp_rob_idx{slot}"] for slot in range(dispatch_w)]
    disp_fires = [rob_ctrl.outputs[f"disp_fire{slot}"] for slot in range(dispatch_w)]

    bank_depth = 8 if rob_depth > 8 else rob_depth
    bank_count = (rob_depth + bank_depth - 1) // bank_depth

    bank_bind: dict[str, object] = {"clk": clk, "rst": rst, "do_flush": do_flush}
    for slot in range(commit_w):
        bank_bind[f"commit_fire{slot}"] = commit_fires[slot]
        bank_bind[f"commit_idx{slot}"] = commit_idxs_now[slot]
    for slot in range(dispatch_w):
        bank_bind[f"disp_fire{slot}"] = disp_fires[slot]
        bank_bind[f"disp_rob_idx{slot}"] = disp_rob_idxs[slot]
        bank_bind[f"disp_pc{slot}"] = disp_pcs[slot]
        bank_bind[f"disp_op{slot}"] = disp_ops[slot]
        bank_bind[f"disp_len{slot}"] = disp_lens[slot]
        bank_bind[f"disp_insn_raw{slot}"] = disp_insn_raws[slot]
        bank_bind[f"disp_checkpoint_id{slot}"] = disp_checkpoint_ids[slot]
        bank_bind[f"disp_dst_kind{slot}"] = disp_dst_kinds[slot]
        bank_bind[f"disp_regdst{slot}"] = disp_regdsts[slot]
        bank_bind[f"disp_pdst{slot}"] = disp_pdsts[slot]
        bank_bind[f"disp_imm{slot}"] = disp_imms[slot]
        bank_bind[f"disp_is_store{slot}"] = disp_is_stores[slot]
        bank_bind[f"disp_is_boundary{slot}"] = disp_is_boundaries[slot]
        bank_bind[f"disp_is_bstart{slot}"] = disp_is_bstarts[slot]
        bank_bind[f"disp_is_bstop{slot}"] = disp_is_bstops[slot]
        bank_bind[f"disp_boundary_kind{slot}"] = disp_boundary_kinds[slot]
        bank_bind[f"disp_boundary_target{slot}"] = disp_boundary_targets[slot]
        bank_bind[f"disp_pred_take{slot}"] = disp_pred_takes[slot]
        bank_bind[f"disp_block_epoch{slot}"] = disp_block_epochs[slot]
        bank_bind[f"disp_block_uid{slot}"] = disp_block_uids[slot]
        bank_bind[f"disp_block_bid{slot}"] = disp_block_bids[slot]
        bank_bind[f"disp_load_store_id{slot}"] = disp_load_store_ids[slot]
        bank_bind[f"disp_resolved_d2{slot}"] = disp_resolved_d2s[slot]
        bank_bind[f"disp_srcl{slot}"] = disp_srcls[slot]
        bank_bind[f"disp_srcr{slot}"] = disp_srcrs[slot]
        bank_bind[f"disp_uop_uid{slot}"] = disp_uop_uids[slot]
        bank_bind[f"disp_parent_uid{slot}"] = disp_parent_uids[slot]
    for slot in range(issue_w):
        bank_bind[f"wb_fire{slot}"] = wb_fires[slot]
        bank_bind[f"wb_rob{slot}"] = wb_robs[slot]
        bank_bind[f"wb_value{slot}"] = wb_values[slot]
        bank_bind[f"store_fire{slot}"] = store_fires[slot]
        bank_bind[f"load_fire{slot}"] = load_fires[slot]
        bank_bind[f"ex_addr{slot}"] = ex_addrs[slot]
        bank_bind[f"ex_wdata{slot}"] = ex_wdatas[slot]
        bank_bind[f"ex_size{slot}"] = ex_sizes[slot]
        bank_bind[f"ex_src0{slot}"] = ex_src0s[slot]
        bank_bind[f"ex_src1{slot}"] = ex_src1s[slot]
    for slot in range(meta_query_slots):
        bank_bind[f"meta_query_idx{slot}"] = meta_query_idxs[slot]

    entry_outputs: dict[str, list[object]] = {}
    for field_name, _width, _default in field_defs:
        entry_outputs[field_name] = []
    bank_group_outputs: list[object] = []
    quad_group_count = bank_count // 4
    for quad_idx in range(quad_group_count):
        bank_base_idx = quad_idx * 4
        quad_bind = dict(bank_bind)
        quad_bind["bank_base"] = c(bank_base_idx * bank_depth, width=rob_w)
        quad_out = m.new(
            build_rob_bank_quad,
            name=f"rob_bank_quad_{quad_idx}",
            bind=quad_bind,
            params={
                "bank_depth": bank_depth,
                "dispatch_w": dispatch_w,
                "issue_w": issue_w,
                "commit_w": commit_w,
                "meta_query_slots": meta_query_slots,
                "rob_w": rob_w,
                "ptag_w": ptag_w,
            },
        ).outputs
        bank_group_outputs.append(quad_out)
        for local_idx in range(4 * bank_depth):
            global_idx = bank_base_idx * bank_depth + local_idx
            if global_idx >= rob_depth:
                break
            for field_name, _width, _default in field_defs:
                entry_outputs[field_name].append(quad_out[f"{field_name}{local_idx}_o"])

    tail_bank_idx = quad_group_count * 4
    if (bank_count - tail_bank_idx) >= 2:
        pair_bind = dict(bank_bind)
        pair_bind["bank_base"] = c(tail_bank_idx * bank_depth, width=rob_w)
        pair_out = m.new(
            build_rob_bank_pair,
            name="rob_bank_pair_tail",
            bind=pair_bind,
            params={
                "bank_depth": bank_depth,
                "dispatch_w": dispatch_w,
                "issue_w": issue_w,
                "commit_w": commit_w,
                "meta_query_slots": meta_query_slots,
                "rob_w": rob_w,
                "ptag_w": ptag_w,
            },
        ).outputs
        bank_group_outputs.append(pair_out)
        for local_idx in range(2 * bank_depth):
            global_idx = tail_bank_idx * bank_depth + local_idx
            if global_idx >= rob_depth:
                break
            for field_name, _width, _default in field_defs:
                entry_outputs[field_name].append(pair_out[f"{field_name}{local_idx}_o"])
        tail_bank_idx += 2

    if tail_bank_idx < bank_count:
        slice_bind = dict(bank_bind)
        slice_bind["bank_base"] = c(tail_bank_idx * bank_depth, width=rob_w)
        slice_out = m.new(
            build_rob_bank_slice,
            name="rob_bank_slice_tail",
            bind=slice_bind,
            params={
                "bank_depth": bank_depth,
                "dispatch_w": dispatch_w,
                "issue_w": issue_w,
                "commit_w": commit_w,
                "meta_query_slots": meta_query_slots,
                "rob_w": rob_w,
                "ptag_w": ptag_w,
            },
        ).outputs
        bank_group_outputs.append(slice_out)
        for local_idx in range(bank_depth):
            global_idx = tail_bank_idx * bank_depth + local_idx
            if global_idx >= rob_depth:
                break
            for field_name, _width, _default in field_defs:
                entry_outputs[field_name].append(slice_out[f"{field_name}{local_idx}_o"])

    head.set(head_next)
    tail.set(tail_next)
    count.set(count_next)

    commit_idxs = [(head.out() + c(slot, width=rob_w))._trunc(width=rob_w) for slot in range(commit_w)]
    commit_outputs: dict[str, list[object]] = {}
    for field_name, _width, _default in field_defs:
        commit_outputs[field_name] = []
    for slot in range(commit_w):
        commit_group_hits = [bank_group_out["commit_hit_mask_o"].slice(lsb=slot, width=1) for bank_group_out in bank_group_outputs]
        commit_group_fields = [
            _unpack_slot_pack(bank_group_out["commit_pack_o"], commit_result_specs, slot) for bank_group_out in bank_group_outputs
        ]
        for field_name, width, default_value in field_defs:
            commit_value = c(default_value, width=width)
            for group_hit, group_fields in zip(commit_group_hits, commit_group_fields):
                commit_value = group_hit._select_internal(
                    group_fields[field_name],
                    commit_value,
                )
            commit_outputs[field_name].append(commit_value)

    sub_head = (~head.out()) + c(1, width=rob_w)

    lsu_store_scan_bind: dict[str, object] = {
        "issue_fire_lane0_raw": issue_fire_lane0_raw,
        "ex0_is_load": ex0_is_load,
        "ex0_addr": ex0_addr,
        "ex0_rob": ex0_rob,
        "sub_head": sub_head,
    }
    for i in range(rob_depth):
        lsu_store_scan_bind[f"rob_valid{i}"] = entry_outputs["valid"][i]
        lsu_store_scan_bind[f"rob_done{i}"] = entry_outputs["done"][i]
        lsu_store_scan_bind[f"rob_is_store{i}"] = entry_outputs["is_store"][i]
        lsu_store_scan_bind[f"rob_store_addr{i}"] = entry_outputs["store_addr"][i]
        lsu_store_scan_bind[f"rob_store_data{i}"] = entry_outputs["store_data"][i]
    lsu_store_scan = m.new(
        build_rob_lsu_store_scan_stage,
        name="rob_lsu_store_scan",
        bind=lsu_store_scan_bind,
        params={"rob_depth": rob_depth, "rob_w": rob_w},
    ).outputs

    lsu_violation_bind = {
        "replay_pending": replay_pending,
        "sub_head": sub_head,
    }
    for slot in range(issue_w):
        lsu_violation_bind[f"store_fire{slot}"] = store_fires[slot]
        lsu_violation_bind[f"store_rob{slot}"] = wb_robs[slot]
        lsu_violation_bind[f"store_addr{slot}"] = ex_addrs[slot]
    for i in range(rob_depth):
        lsu_violation_bind[f"rob_valid{i}"] = entry_outputs["valid"][i]
        lsu_violation_bind[f"rob_done{i}"] = entry_outputs["done"][i]
        lsu_violation_bind[f"rob_is_load{i}"] = entry_outputs["is_load"][i]
        lsu_violation_bind[f"rob_load_addr{i}"] = entry_outputs["load_addr"][i]
        lsu_violation_bind[f"rob_pc{i}"] = entry_outputs["pc"][i]
    lsu_violation = m.new(
        build_lsu_violation_detect_stage,
        name="rob_lsu_violation",
        bind=lsu_violation_bind,
        params={"issue_w": issue_w, "rob_depth": rob_depth, "rob_w": rob_w},
    ).outputs

    store_pending = consts.zero1
    for i in range(rob_depth):
        store_pending = store_pending | (entry_outputs["valid"][i] & entry_outputs["is_store"][i])

    m.output("head_o", head.out())
    m.output("tail_o", tail.out())
    m.output("count_o", count.out())

    dispatch_specs = rob_dispatch_slot_field_defs(m, rob_w=rob_w)
    dispatch_slot_packs = []
    for slot in range(dispatch_w):
        dispatch_slot_packs.append(
            _pack_fields(
                m,
                dispatch_specs,
                {
                    "rob_idx": disp_rob_idxs[slot],
                    "fire": disp_fires[slot],
                },
            )
        )
    m.output("dispatch_pack_o", _pack_values(m, dispatch_slot_packs))

    commit_specs = rob_commit_slot_field_defs(m, rob_w=rob_w, ptag_w=ptag_w)
    commit_slot_packs = []
    for slot in range(commit_w):
        commit_values = {"idx": commit_idxs[slot]}
        for field_name, _width, _default in field_defs:
            commit_values[field_name] = commit_outputs[field_name][slot]
        commit_slot_packs.append(_pack_fields(m, commit_specs, commit_values))
    m.output("commit_pack_o", _pack_values(m, commit_slot_packs))

    m.output("store_pending_o", store_pending)
    m.output("lsu_older_store_pending_lane0_o", lsu_store_scan["older_store_pending_o"])
    m.output("lsu_forward_hit_lane0_o", lsu_store_scan["forward_hit_o"])
    m.output("lsu_forward_data_lane0_o", lsu_store_scan["forward_data_o"])
    m.output("lsu_violation_replay_set_o", lsu_violation["replay_set"])
    m.output("lsu_violation_replay_store_rob_o", lsu_violation["replay_set_store_rob"])
    m.output("lsu_violation_replay_pc_o", lsu_violation["replay_set_pc"])

    meta_specs = rob_meta_query_slot_field_defs(m)
    meta_slot_packs = []
    for slot in range(meta_query_slots):
        query_group_hits = [bank_group_out["query_hit_mask_o"].slice(lsb=slot, width=1) for bank_group_out in bank_group_outputs]
        query_group_fields = [
            _unpack_slot_pack(bank_group_out["query_pack_o"], meta_specs, slot) for bank_group_out in bank_group_outputs
        ]
        meta_values = {}
        for field_name, width, default_value in ROB_META_QUERY_FIELD_DEFS:
            query_value = c(default_value, width=width)
            for group_hit, group_fields in zip(query_group_hits, query_group_fields):
                query_value = group_hit._select_internal(
                    group_fields[field_name],
                    query_value,
                )
            meta_values[field_name] = query_value
        meta_slot_packs.append(_pack_fields(m, meta_specs, meta_values))
    m.output("meta_query_pack_o", _pack_values(m, meta_slot_packs))
