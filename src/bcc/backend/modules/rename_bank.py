from __future__ import annotations

from pycircuit import Circuit, module

from ..helpers import mux_by_uindex, onehot_from_tag


@module(name="LinxCoreRenameBank")
def build_rename_bank_top(
    m: Circuit,
    *,
    dispatch_w: int = 4,
    commit_w: int = 4,
    aregs: int = 32,
    pregs: int = 64,
    ptag_w: int = 6,
) -> None:
    clk = m.clock("clk")
    rst = m.reset("rst")
    do_flush = m.input("do_flush", width=1)
    dispatch_fire = m.input("dispatch_fire", width=1)
    disp_alloc_mask = m.input("disp_alloc_mask", width=pregs)
    m.input("flush_checkpoint_id", width=6)
    macro_uop_reg = m.input("macro_uop_reg", width=6)
    wb_set_mask = m.input("wb_set_mask", width=pregs)

    c = m.const
    tag0 = c(0, width=ptag_w)

    disp_valid = []
    disp_srcl = []
    disp_srcr = []
    disp_srcp = []
    disp_is_start_marker = []
    disp_push_t = []
    disp_push_u = []
    disp_dst_is_gpr = []
    disp_regdst = []
    disp_pdst = []
    for slot in range(dispatch_w):
        disp_valid.append(m.input(f"disp_valid{slot}", width=1))
        disp_srcl.append(m.input(f"disp_srcl{slot}", width=6))
        disp_srcr.append(m.input(f"disp_srcr{slot}", width=6))
        disp_srcp.append(m.input(f"disp_srcp{slot}", width=6))
        disp_is_start_marker.append(m.input(f"disp_is_start_marker{slot}", width=1))
        disp_push_t.append(m.input(f"disp_push_t{slot}", width=1))
        disp_push_u.append(m.input(f"disp_push_u{slot}", width=1))
        disp_dst_is_gpr.append(m.input(f"disp_dst_is_gpr{slot}", width=1))
        disp_regdst.append(m.input(f"disp_regdst{slot}", width=6))
        disp_pdst.append(m.input(f"disp_pdst{slot}", width=ptag_w))
        m.input(f"disp_checkpoint_id{slot}", width=6)

    commit_fire = []
    commit_is_bstop = []
    rob_dst_kind = []
    rob_dst_areg = []
    rob_pdst = []
    for slot in range(commit_w):
        commit_fire.append(m.input(f"commit_fire{slot}", width=1))
        commit_is_bstop.append(m.input(f"commit_is_bstop{slot}", width=1))
        rob_dst_kind.append(m.input(f"rob_dst_kind{slot}", width=2))
        rob_dst_areg.append(m.input(f"rob_dst_areg{slot}", width=6))
        rob_pdst.append(m.input(f"rob_pdst{slot}", width=ptag_w))

    # Architectural boot map:
    # - p0  : hardwired zero
    # - p1  : sp  (areg 1)
    # - p10 : ra  (areg 10)
    init_free_mask = ((1 << pregs) - 1) ^ ((1 << 0) | (1 << 1) | (1 << 10))
    init_ready_mask = (1 << 0) | (1 << 1) | (1 << 10)

    smap_regs = []
    cmap_regs = []
    for i in range(aregs):
        init_tag = 0
        if i == 1:
            init_tag = 1
        elif i == 10:
            init_tag = 10
        smap_regs.append(m.out(f"smap{i}", clk=clk, rst=rst, width=ptag_w, init=c(init_tag, width=ptag_w), en=c(1, width=1)))
        cmap_regs.append(m.out(f"cmap{i}", clk=clk, rst=rst, width=ptag_w, init=c(init_tag, width=ptag_w), en=c(1, width=1)))

    free_mask_reg = m.out("free_mask_q", clk=clk, rst=rst, width=pregs, init=c(init_free_mask, width=pregs), en=c(1, width=1))
    ready_mask_reg = m.out("ready_mask_q", clk=clk, rst=rst, width=pregs, init=c(init_ready_mask, width=pregs), en=c(1, width=1))

    smap_live = [r.out() for r in smap_regs]
    cmap_live = [r.out() for r in cmap_regs]

    srcl_tags = []
    srcr_tags = []
    srcp_tags = []
    for slot in range(dispatch_w):
        sl_tag = mux_by_uindex(m, idx=disp_srcl[slot], items=smap_live, default=tag0)
        sr_tag = mux_by_uindex(m, idx=disp_srcr[slot], items=smap_live, default=tag0)
        sp_tag = mux_by_uindex(m, idx=disp_srcp[slot], items=smap_live, default=tag0)
        sl_tag = disp_srcl[slot].__eq__(c(63, width=6))._select_internal(tag0, sl_tag)
        sr_tag = disp_srcr[slot].__eq__(c(63, width=6))._select_internal(tag0, sr_tag)
        sp_tag = disp_srcp[slot].__eq__(c(63, width=6))._select_internal(tag0, sp_tag)
        srcl_tags.append(sl_tag)
        srcr_tags.append(sr_tag)
        srcp_tags.append(sp_tag)

        lane_fire = dispatch_fire & disp_valid[slot]
        t0_old = smap_live[24]
        t1_old = smap_live[25]
        t2_old = smap_live[26]
        u0_old = smap_live[28]
        u1_old = smap_live[29]
        u2_old = smap_live[30]

        smap_next = []
        for i in range(aregs):
            nxt = smap_live[i]
            if 24 <= i <= 31:
                nxt = (lane_fire & disp_is_start_marker[slot])._select_internal(tag0, nxt)
            if i == 24:
                nxt = (lane_fire & disp_push_t[slot])._select_internal(disp_pdst[slot], nxt)
            if i == 25:
                nxt = (lane_fire & disp_push_t[slot])._select_internal(t0_old, nxt)
            if i == 26:
                nxt = (lane_fire & disp_push_t[slot])._select_internal(t1_old, nxt)
            if i == 27:
                nxt = (lane_fire & disp_push_t[slot])._select_internal(t2_old, nxt)
            if i == 28:
                nxt = (lane_fire & disp_push_u[slot])._select_internal(disp_pdst[slot], nxt)
            if i == 29:
                nxt = (lane_fire & disp_push_u[slot])._select_internal(u0_old, nxt)
            if i == 30:
                nxt = (lane_fire & disp_push_u[slot])._select_internal(u1_old, nxt)
            if i == 31:
                nxt = (lane_fire & disp_push_u[slot])._select_internal(u2_old, nxt)
            if i < 24:
                hit = lane_fire & disp_dst_is_gpr[slot] & disp_regdst[slot].__eq__(c(i, width=6))
                nxt = hit._select_internal(disp_pdst[slot], nxt)
            if i == 0:
                nxt = tag0
            smap_next.append(nxt)
        smap_live = smap_next

    free_live = free_mask_reg.out()
    for slot in range(commit_w):
        fire = commit_fire[slot]
        dk = rob_dst_kind[slot]
        areg = rob_dst_areg[slot]
        pdst = rob_pdst[slot]

        old_t0 = cmap_live[24]
        old_t1 = cmap_live[25]
        old_t2 = cmap_live[26]
        old_t3 = cmap_live[27]
        old_u0 = cmap_live[28]
        old_u1 = cmap_live[29]
        old_u2 = cmap_live[30]
        old_u3 = cmap_live[31]

        if_free = commit_is_bstop[slot]
        for old in [old_t0, old_t1, old_t2, old_t3, old_u0, old_u1, old_u2, old_u3]:
            oh = onehot_from_tag(m, tag=old, width=pregs, tag_width=ptag_w)
            free_live = (if_free & (~old.__eq__(tag0)))._select_internal(free_live | oh, free_live)

        push_t = fire & dk.__eq__(c(2, width=2))
        t3_oh = onehot_from_tag(m, tag=old_t3, width=pregs, tag_width=ptag_w)
        free_live = (push_t & (~old_t3.__eq__(tag0)))._select_internal(free_live | t3_oh, free_live)

        push_u = fire & dk.__eq__(c(3, width=2))
        u3_oh = onehot_from_tag(m, tag=old_u3, width=pregs, tag_width=ptag_w)
        free_live = (push_u & (~old_u3.__eq__(tag0)))._select_internal(free_live | u3_oh, free_live)

        is_gpr = fire & dk.__eq__(c(1, width=2))
        cmap_next = []
        for i in range(aregs):
            nxt = cmap_live[i]
            if 24 <= i <= 31:
                nxt = if_free._select_internal(tag0, nxt)
            if i == 24:
                nxt = push_t._select_internal(pdst, nxt)
            if i == 25:
                nxt = push_t._select_internal(old_t0, nxt)
            if i == 26:
                nxt = push_t._select_internal(old_t1, nxt)
            if i == 27:
                nxt = push_t._select_internal(old_t2, nxt)
            if i == 28:
                nxt = push_u._select_internal(pdst, nxt)
            if i == 29:
                nxt = push_u._select_internal(old_u0, nxt)
            if i == 30:
                nxt = push_u._select_internal(old_u1, nxt)
            if i == 31:
                nxt = push_u._select_internal(old_u2, nxt)
            if i < 24:
                hit = is_gpr & areg.__eq__(c(i, width=6))
                old = cmap_live[i]
                old_oh = onehot_from_tag(m, tag=old, width=pregs, tag_width=ptag_w)
                free_live = (hit & (~old.__eq__(tag0)))._select_internal(free_live | old_oh, free_live)
                nxt = hit._select_internal(pdst, nxt)
            if i == 0:
                nxt = tag0
            cmap_next.append(nxt)
        cmap_live = cmap_next

    committed_ready = c(1, width=pregs)
    for tag_i in cmap_live:
        committed_ready = (~tag_i.__eq__(tag0))._select_internal(
            committed_ready | onehot_from_tag(m, tag=tag_i, width=pregs, tag_width=ptag_w),
            committed_ready,
        )
    committed_free = (~committed_ready) & c((1 << pregs) - 1, width=pregs)
    free_next = dispatch_fire._select_internal(free_live & (~disp_alloc_mask), free_live)
    free_next = do_flush._select_internal(committed_free, free_next)
    free_mask_reg.set(free_next)

    ready_next = (ready_mask_reg.out() & (~disp_alloc_mask)) | wb_set_mask
    ready_next = do_flush._select_internal(committed_ready | wb_set_mask, ready_next)
    ready_mask_reg.set(ready_next)

    for i in range(aregs):
        cmap_regs[i].set(cmap_live[i])
        smap_regs[i].set(do_flush._select_internal(cmap_live[i], smap_live[i]))

    macro_reg_tag = mux_by_uindex(m, idx=macro_uop_reg, items=[r.out() for r in smap_regs], default=tag0)

    m.output("free_mask_o", free_mask_reg.out())
    m.output("ready_mask_o", ready_mask_reg.out())
    m.output("cmap_sp_o", cmap_regs[1].out())
    m.output("cmap_a0_o", cmap_regs[2].out())
    m.output("cmap_a1_o", cmap_regs[3].out())
    m.output("cmap_ra_o", cmap_regs[10].out())
    m.output("cmap_ct0_o", cmap_regs[24].out())
    m.output("cmap_cu0_o", cmap_regs[28].out())
    m.output("smap_st0_o", smap_regs[24].out())
    m.output("smap_su0_o", smap_regs[28].out())
    m.output("macro_reg_tag_o", macro_reg_tag)
    for slot in range(dispatch_w):
        m.output(f"disp_srcl_tag{slot}_o", srcl_tags[slot])
        m.output(f"disp_srcr_tag{slot}_o", srcr_tags[slot])
        m.output(f"disp_srcp_tag{slot}_o", srcp_tags[slot])


build_rename_bank_top.__pycircuit_name__ = "LinxCoreRenameBank"
