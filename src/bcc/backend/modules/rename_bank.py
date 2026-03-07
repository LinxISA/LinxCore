from __future__ import annotations

from pycircuit import Circuit, module

from common.isa import REG_INVALID
from common.util import make_consts
from ..helpers import mux_by_uindex, onehot_from_tag
from ..rename import build_commit_rename_stage, build_rename_stage
from ..params import OooParams


def _build_rename_bank_core(
    m: Circuit,
    *,
    dispatch_w: int,
    commit_w: int,
    pregs: int,
) -> None:
    clk = m.clock("clk")
    rst = m.reset("rst")
    c = m.const
    consts = make_consts(m)

    # Keep sizing consistent with the backend bring-up core. Rename-only
    # consumers should not need to know about the rest of the pipeline.
    p = OooParams(pregs=int(pregs), dispatch_w=int(dispatch_w), commit_w=int(commit_w))

    do_flush = m.input("do_flush", width=1)
    dispatch_fire = m.input("dispatch_fire", width=1)
    disp_alloc_mask = m.input("disp_alloc_mask", width=p.pregs)

    # Flush checkpoint id is carried from core state for potential checkpoint
    # restore. Bring-up keeps restore disabled, but wiring stays explicit.
    flush_checkpoint_id = m.input("flush_checkpoint_id", width=6)

    # Macro/template uops address CMAP by architectural reg index.
    macro_uop_reg = m.input("macro_uop_reg", width=6)

    # Ready-table updates are driven by writeback fires (ptag onehots).
    wb_set_mask = m.input("wb_set_mask", width=p.pregs)

    disp_valids = []
    disp_srcls = []
    disp_srcrs = []
    disp_srcps = []
    disp_is_start_marker = []
    disp_push_t = []
    disp_push_u = []
    disp_dst_is_gpr = []
    disp_regdsts = []
    disp_pdsts = []
    disp_checkpoint_ids = []
    for slot in range(p.dispatch_w):
        disp_valids.append(m.input(f"disp_valid{slot}", width=1))
        disp_srcls.append(m.input(f"disp_srcl{slot}", width=6))
        disp_srcrs.append(m.input(f"disp_srcr{slot}", width=6))
        disp_srcps.append(m.input(f"disp_srcp{slot}", width=6))
        disp_is_start_marker.append(m.input(f"disp_is_start_marker{slot}", width=1))
        disp_push_t.append(m.input(f"disp_push_t{slot}", width=1))
        disp_push_u.append(m.input(f"disp_push_u{slot}", width=1))
        disp_dst_is_gpr.append(m.input(f"disp_dst_is_gpr{slot}", width=1))
        disp_regdsts.append(m.input(f"disp_regdst{slot}", width=6))
        disp_pdsts.append(m.input(f"disp_pdst{slot}", width=p.ptag_w))
        disp_checkpoint_ids.append(m.input(f"disp_checkpoint_id{slot}", width=6))

    commit_fires = []
    commit_is_bstops = []
    rob_dst_kinds = []
    rob_dst_aregs = []
    rob_pdsts = []
    for slot in range(p.commit_w):
        commit_fires.append(m.input(f"commit_fire{slot}", width=1))
        commit_is_bstops.append(m.input(f"commit_is_bstop{slot}", width=1))
        rob_dst_kinds.append(m.input(f"rob_dst_kind{slot}", width=2))
        rob_dst_aregs.append(m.input(f"rob_dst_areg{slot}", width=6))
        rob_pdsts.append(m.input(f"rob_pdst{slot}", width=p.ptag_w))

    ckpt_depth = 16
    free_init = ((1 << p.pregs) - 1) ^ ((1 << p.aregs) - 1)
    ready_init = (1 << p.pregs) - 1

    with m.scope("rename"):
        smap = []
        cmap = []
        for i in range(p.aregs):
            smap.append(m.out(f"smap{i}", clk=clk, rst=rst, width=p.ptag_w, init=c(i, width=p.ptag_w), en=consts.one1))
            cmap.append(m.out(f"cmap{i}", clk=clk, rst=rst, width=p.ptag_w, init=c(i, width=p.ptag_w), en=consts.one1))

        free_mask = m.out("free_mask", clk=clk, rst=rst, width=p.pregs, init=c(free_init, width=p.pregs), en=consts.one1)
        ready_mask = m.out("ready_mask", clk=clk, rst=rst, width=p.pregs, init=c(ready_init, width=p.pregs), en=consts.one1)

        ckpt_valid = []
        ckpt_smap = []
        ckpt_free_mask = []
        ckpt_ready_mask = []
        for ci in range(ckpt_depth):
            ckpt_valid.append(m.out(f"ckv{ci}", clk=clk, rst=rst, width=1, init=consts.zero1, en=consts.one1))
            ckpt_free_mask.append(
                m.out(f"ckf{ci}", clk=clk, rst=rst, width=p.pregs, init=c(free_init, width=p.pregs), en=consts.one1)
            )
            ckpt_ready_mask.append(
                m.out(f"ckr{ci}", clk=clk, rst=rst, width=p.pregs, init=c(ready_init, width=p.pregs), en=consts.one1)
            )
            smap_i = []
            for r in range(p.aregs):
                smap_i.append(m.out(f"cks{ci}_{r}", clk=clk, rst=rst, width=p.ptag_w, init=c(r, width=p.ptag_w), en=consts.one1))
            ckpt_smap.append(smap_i)

    ckpt_entries = len(ckpt_valid)
    ckpt_w = max(1, (ckpt_entries - 1).bit_length())

    # --- SMAP combinational rename (dispatch-time) ---
    rename_stage_args = {"dispatch_fire": dispatch_fire}
    for i in range(p.aregs):
        rename_stage_args[f"smap{i}"] = smap[i].out()
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
        m.output(f"disp_srcl_tag{slot}_o", disp_srcl_tags[slot])
        m.output(f"disp_srcr_tag{slot}_o", disp_srcr_tags[slot])
        m.output(f"disp_srcp_tag{slot}_o", disp_srcp_tags[slot])

    smap_live = [rename_stage[f"smap_next{i}"] for i in range(p.aregs)]

    # Free/ready masks after dispatch allocation (used for checkpoint snapshots).
    rename_free_after_dispatch = dispatch_fire._select_internal(free_mask.out() & (~disp_alloc_mask), free_mask.out())
    rename_ready_after_dispatch = dispatch_fire._select_internal(ready_mask.out() & (~disp_alloc_mask), ready_mask.out())

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
        valid_next = ckpt_valid[ci].out()
        valid_next = do_ckpt._select_internal(consts.one1, valid_next)
        ckpt_valid[ci].set(valid_next)
        ckpt_free_mask[ci].set(rename_free_after_dispatch, when=do_ckpt)
        ckpt_ready_mask[ci].set(rename_ready_after_dispatch, when=do_ckpt)
        for r in range(p.aregs):
            ckpt_smap[ci][r].set(smap_live[r], when=do_ckpt)

    flush_ckpt_idx = flush_checkpoint_id._trunc(width=ckpt_w)
    flush_ckpt_valid = mux_by_uindex(m, idx=flush_ckpt_idx, items=ckpt_valid, default=consts.zero1)
    flush_free_from_ckpt = mux_by_uindex(m, idx=flush_ckpt_idx, items=ckpt_free_mask, default=free_mask.out())
    flush_ready_from_ckpt = mux_by_uindex(m, idx=flush_ckpt_idx, items=ckpt_ready_mask, default=ready_mask.out())

    # Bring-up fallback: recover rename state from committed map on flush.
    # Checkpoint restore remains wired but disabled until checkpoint parity is stable.
    restore_from_ckpt = consts.zero1
    _ = flush_ckpt_valid  # wired for future parity bring-up

    # --- ready table updates ---
    ready_next = ready_mask.out()
    ready_next = dispatch_fire._select_internal(ready_next & (~disp_alloc_mask), ready_next)
    ready_next = ready_next | wb_set_mask
    ready_next = do_flush._select_internal(c((1 << p.pregs) - 1, width=p.pregs), ready_next)
    ready_next = restore_from_ckpt._select_internal(flush_ready_from_ckpt, ready_next)
    ready_mask.set(ready_next)

    # --- SMAP updates ---
    tag0 = c(0, width=p.ptag_w)
    for i in range(p.aregs):
        nxt = smap_live[i]
        nxt = do_flush._select_internal(cmap[i].out(), nxt)
        ckpt_smap_i = mux_by_uindex(
            m,
            idx=flush_ckpt_idx,
            items=[ckpt_smap[ci][i] for ci in range(ckpt_entries)],
            default=cmap[i].out(),
        )
        nxt = restore_from_ckpt._select_internal(ckpt_smap_i, nxt)
        if i == 0:
            nxt = tag0
        smap[i].set(nxt)

    # --- CMAP + freelist updates (commit) ---
    rename_commit_args = {"free_in": rename_free_after_dispatch}
    for i in range(p.aregs):
        rename_commit_args[f"cmap{i}"] = cmap[i].out()
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
        cmap[i].set(rename_commit[f"cmap_out{i}"])
    free_live = rename_commit["free_out"]

    # Flush recomputes freelist from CMAP to drop speculative allocations.
    used = c(0, width=p.pregs)
    for i in range(p.aregs):
        used = used | onehot_from_tag(m, tag=cmap[i].out(), width=p.pregs, tag_width=p.ptag_w)
    free_from_cmap = c((1 << p.pregs) - 1, width=p.pregs) & (~used)
    free_next = free_live
    free_next = do_flush._select_internal(free_from_cmap, free_next)
    free_next = restore_from_ckpt._select_internal(flush_free_from_ckpt, free_next)
    free_mask.set(free_next)

    # External views (current state). Consumers sample the current-cycle values;
    # updates apply at transfer.
    m.output("free_mask_o", free_mask.out())
    m.output("ready_mask_o", ready_mask.out())
    m.output("cmap_sp_o", cmap[1].out())
    m.output("cmap_a0_o", cmap[2].out())
    m.output("cmap_a1_o", cmap[3].out())
    m.output("cmap_ra_o", cmap[10].out())
    m.output("cmap_ct0_o", cmap[24].out())
    m.output("cmap_cu0_o", cmap[28].out())
    m.output("smap_st0_o", smap[24].out())
    m.output("smap_su0_o", smap[28].out())

    cmap_now = [cmap[i].out() for i in range(p.aregs)]
    macro_reg_tag = mux_by_uindex(m, idx=macro_uop_reg, items=cmap_now, default=tag0)
    m.output("macro_reg_tag_o", macro_reg_tag)

    return None


@module(name="LinxCoreRenameBankTop")
def build_rename_bank_top(
    m: Circuit,
    *,
    dispatch_w: int = 4,
    commit_w: int = 4,
    pregs: int = 64,
) -> None:
    _build_rename_bank_core(m, dispatch_w=int(dispatch_w), commit_w=int(commit_w), pregs=int(pregs))
