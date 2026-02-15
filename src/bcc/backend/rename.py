from __future__ import annotations

from pycircuit import Circuit, module


def resolve_source_tags(*, m, mux_by_uindex, smap_live, srcl_areg, srcr_areg, srcp_areg, reg_invalid: int, tag0):
    c = m.const
    srcl_tag = mux_by_uindex(m, idx=srcl_areg, items=smap_live, default=tag0)
    srcr_tag = mux_by_uindex(m, idx=srcr_areg, items=smap_live, default=tag0)
    srcp_tag = mux_by_uindex(m, idx=srcp_areg, items=smap_live, default=tag0)
    srcl_tag = srcl_areg.eq(c(reg_invalid, width=6)).select(tag0, srcl_tag)
    srcr_tag = srcr_areg.eq(c(reg_invalid, width=6)).select(tag0, srcr_tag)
    srcp_tag = srcp_areg.eq(c(reg_invalid, width=6)).select(tag0, srcp_tag)
    return srcl_tag, srcr_tag, srcp_tag


def _mux_by_uindex(*, m: Circuit, idx, items: list, default):
    v = default
    c = m.const
    for i, it in enumerate(items):
        v = idx.eq(c(i, width=idx.width)).select(it, v)
    return v


def _onehot_from_tag(*, m: Circuit, tag, width: int, tag_width: int):
    c = m.const
    out = c(0, width=width)
    for i in range(width):
        out = tag.eq(c(i, width=tag_width)).select(c(1 << i, width=width), out)
    return out


@module(name="LinxCoreRenameStage")
def build_rename_stage(
    m: Circuit,
    *,
    dispatch_w: int = 4,
    aregs: int = 32,
    ptag_w: int = 6,
    reg_invalid: int = 63,
) -> None:
    c = m.const
    dispatch_fire = m.input("dispatch_fire", width=1)

    smap_live = []
    for i in range(aregs):
        smap_live.append(m.input(f"smap{i}", width=ptag_w))

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
    for slot in range(dispatch_w):
        disp_valids.append(m.input(f"disp_valid{slot}", width=1))
        disp_srcls.append(m.input(f"disp_srcl{slot}", width=6))
        disp_srcrs.append(m.input(f"disp_srcr{slot}", width=6))
        disp_srcps.append(m.input(f"disp_srcp{slot}", width=6))
        disp_is_start_marker.append(m.input(f"disp_is_start_marker{slot}", width=1))
        disp_push_t.append(m.input(f"disp_push_t{slot}", width=1))
        disp_push_u.append(m.input(f"disp_push_u{slot}", width=1))
        disp_dst_is_gpr.append(m.input(f"disp_dst_is_gpr{slot}", width=1))
        disp_regdsts.append(m.input(f"disp_regdst{slot}", width=6))
        disp_pdsts.append(m.input(f"disp_pdst{slot}", width=ptag_w))

    srcl_tags = []
    srcr_tags = []
    srcp_tags = []

    for slot in range(dispatch_w):
        srcl_areg = disp_srcls[slot]
        srcr_areg = disp_srcrs[slot]
        srcp_areg = disp_srcps[slot]
        srcl_tag = _mux_by_uindex(m=m, idx=srcl_areg, items=smap_live, default=c(0, width=ptag_w))
        srcr_tag = _mux_by_uindex(m=m, idx=srcr_areg, items=smap_live, default=c(0, width=ptag_w))
        srcp_tag = _mux_by_uindex(m=m, idx=srcp_areg, items=smap_live, default=c(0, width=ptag_w))
        srcl_tag = srcl_areg.eq(c(reg_invalid, width=6)).select(c(0, width=ptag_w), srcl_tag)
        srcr_tag = srcr_areg.eq(c(reg_invalid, width=6)).select(c(0, width=ptag_w), srcr_tag)
        srcp_tag = srcp_areg.eq(c(reg_invalid, width=6)).select(c(0, width=ptag_w), srcp_tag)
        srcl_tags.append(srcl_tag)
        srcr_tags.append(srcr_tag)
        srcp_tags.append(srcp_tag)

        lane_fire = dispatch_fire & disp_valids[slot]

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
                nxt = (lane_fire & disp_is_start_marker[slot]).select(c(0, width=ptag_w), nxt)

            if i == 24:
                nxt = (lane_fire & disp_push_t[slot]).select(disp_pdsts[slot], nxt)
            if i == 25:
                nxt = (lane_fire & disp_push_t[slot]).select(t0_old, nxt)
            if i == 26:
                nxt = (lane_fire & disp_push_t[slot]).select(t1_old, nxt)
            if i == 27:
                nxt = (lane_fire & disp_push_t[slot]).select(t2_old, nxt)

            if i == 28:
                nxt = (lane_fire & disp_push_u[slot]).select(disp_pdsts[slot], nxt)
            if i == 29:
                nxt = (lane_fire & disp_push_u[slot]).select(u0_old, nxt)
            if i == 30:
                nxt = (lane_fire & disp_push_u[slot]).select(u1_old, nxt)
            if i == 31:
                nxt = (lane_fire & disp_push_u[slot]).select(u2_old, nxt)

            if i < 24:
                dst_match = disp_regdsts[slot].eq(c(i, width=6))
                nxt = (lane_fire & disp_dst_is_gpr[slot] & dst_match).select(disp_pdsts[slot], nxt)

            if i == 0:
                nxt = c(0, width=ptag_w)
            smap_next.append(nxt)
        smap_live = smap_next

    for slot in range(dispatch_w):
        m.output(f"srcl_tag{slot}", srcl_tags[slot])
        m.output(f"srcr_tag{slot}", srcr_tags[slot])
        m.output(f"srcp_tag{slot}", srcp_tags[slot])

    for i in range(aregs):
        m.output(f"smap_next{i}", smap_live[i])


@module(name="LinxCoreCommitRenameStage")
def build_commit_rename_stage(
    m: Circuit,
    *,
    aregs: int = 32,
    pregs: int = 64,
    ptag_w: int = 6,
    commit_w: int = 4,
) -> None:
    c = m.const
    free_in = m.input("free_in", width=pregs)

    cmap_live = []
    for i in range(aregs):
        cmap_live.append(m.input(f"cmap{i}", width=ptag_w))

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

    tag0 = c(0, width=ptag_w)
    free_live = free_in

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
            oh = _onehot_from_tag(m=m, tag=old, width=pregs, tag_width=ptag_w)
            free_live = (if_free & (~old.eq(tag0))).select(free_live | oh, free_live)
        for i in range(24, 32):
            cmap_live[i] = if_free.select(tag0, cmap_live[i])

        push_t = fire & dk.eq(c(2, width=2))
        t3_oh = _onehot_from_tag(m=m, tag=old_t3, width=pregs, tag_width=ptag_w)
        free_live = (push_t & (~old_t3.eq(tag0))).select(free_live | t3_oh, free_live)
        cmap_live[24] = push_t.select(pdst, cmap_live[24])
        cmap_live[25] = push_t.select(old_t0, cmap_live[25])
        cmap_live[26] = push_t.select(old_t1, cmap_live[26])
        cmap_live[27] = push_t.select(old_t2, cmap_live[27])

        push_u = fire & dk.eq(c(3, width=2))
        u3_oh = _onehot_from_tag(m=m, tag=old_u3, width=pregs, tag_width=ptag_w)
        free_live = (push_u & (~old_u3.eq(tag0))).select(free_live | u3_oh, free_live)
        cmap_live[28] = push_u.select(pdst, cmap_live[28])
        cmap_live[29] = push_u.select(old_u0, cmap_live[29])
        cmap_live[30] = push_u.select(old_u1, cmap_live[30])
        cmap_live[31] = push_u.select(old_u2, cmap_live[31])

        is_gpr = fire & dk.eq(c(1, width=2))
        for i in range(24):
            hit = is_gpr & areg.eq(c(i, width=6))
            old = cmap_live[i]
            old_oh = _onehot_from_tag(m=m, tag=old, width=pregs, tag_width=ptag_w)
            free_live = (hit & (~old.eq(tag0))).select(free_live | old_oh, free_live)
            cmap_live[i] = hit.select(pdst, cmap_live[i])

        cmap_live[0] = tag0

    m.output("free_out", free_live)
    for i in range(aregs):
        m.output(f"cmap_out{i}", cmap_live[i])
