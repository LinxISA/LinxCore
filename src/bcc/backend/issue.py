from __future__ import annotations

from functools import partial
from types import SimpleNamespace

from pycircuit import Circuit, function, module, u

from .helpers import mask_bit
from .lsu import is_load_op, is_store_op


@function
def pick_oldest(m: Circuit, *, p, consts, can_issue: list, iq, width: int, sub_head):
    # TODO: remove this legacy IQ-struct picker once all callers use
    # pick_oldest_from_arrays.
    issue_valids = []
    issue_idxs = []
    for slot in range(width):
        v = consts.zero1
        idx = u(p.iq_w, 0)
        best_age = u(p.rob_w, (1 << p.rob_w) - 1)
        for i in range(p.iq_depth):
            cidx = u(p.iq_w, i)
            exclude = consts.zero1
            for prev in range(slot):
                exclude = exclude | (issue_valids[prev] & (issue_idxs[prev] == cidx))
            cand = can_issue[i] & (~exclude)
            age = iq.rob[i].out() + sub_head
            better = (~v) | (age < best_age)
            take = cand & better
            v = take._select_internal(consts.one1, v)
            idx = take._select_internal(cidx, idx)
            best_age = take._select_internal(age, best_age)
        issue_valids.append(v)
        issue_idxs.append(idx)
    return issue_valids, issue_idxs


@function
def pick_oldest_from_arrays(m: Circuit, *, p, consts, can_issue: list, rob_tags: list, width: int, sub_head):
    issue_valids = []
    issue_idxs = []
    for slot in range(width):
        v = consts.zero1
        idx = u(p.iq_w, 0)
        best_age = u(p.rob_w, (1 << p.rob_w) - 1)
        for i in range(p.iq_depth):
            cidx = u(p.iq_w, i)
            exclude = consts.zero1
            for prev in range(slot):
                exclude = exclude | (issue_valids[prev] & (issue_idxs[prev] == cidx))
            cand = can_issue[i] & (~exclude)
            age = rob_tags[i] + sub_head
            better = (~v) | (age < best_age)
            take = cand & better
            v = take._select_internal(consts.one1, v)
            idx = take._select_internal(cidx, idx)
            best_age = take._select_internal(age, best_age)
        issue_valids.append(v)
        issue_idxs.append(idx)
    return issue_valids, issue_idxs


@module(name="LinxCoreIssueStage")
def build_issue_stage(
    m: Circuit,
    *,
    iq_depth: int = 32,
    iq_w: int = 5,
    rob_w: int = 6,
    pregs: int = 64,
    alu_w: int = 2,
    bru_w: int = 1,
    lsu_w: int = 1,
) -> None:
    can_run = m.input("can_run", width=1)
    # Local const anchors (avoid m.const in this module).
    zero1 = can_run & 0
    one1 = ~zero1
    commit_redirect = m.input("commit_redirect", width=1)
    ready_mask = m.input("ready_mask", width=pregs)
    rob_head = m.input("rob_head", width=rob_w)

    iq_alu_valid = []
    iq_alu_rob = []
    iq_alu_srcl = []
    iq_alu_srcr = []
    iq_alu_srcp = []
    for i in range(iq_depth):
        iq_alu_valid.append(m.input(f"iq_alu_valid{i}", width=1))
        iq_alu_rob.append(m.input(f"iq_alu_rob{i}", width=rob_w))
        iq_alu_srcl.append(m.input(f"iq_alu_srcl{i}", width=(pregs - 1).bit_length()))
        iq_alu_srcr.append(m.input(f"iq_alu_srcr{i}", width=(pregs - 1).bit_length()))
        iq_alu_srcp.append(m.input(f"iq_alu_srcp{i}", width=(pregs - 1).bit_length()))

    iq_bru_valid = []
    iq_bru_rob = []
    iq_bru_srcl = []
    iq_bru_srcr = []
    iq_bru_srcp = []
    for i in range(iq_depth):
        iq_bru_valid.append(m.input(f"iq_bru_valid{i}", width=1))
        iq_bru_rob.append(m.input(f"iq_bru_rob{i}", width=rob_w))
        iq_bru_srcl.append(m.input(f"iq_bru_srcl{i}", width=(pregs - 1).bit_length()))
        iq_bru_srcr.append(m.input(f"iq_bru_srcr{i}", width=(pregs - 1).bit_length()))
        iq_bru_srcp.append(m.input(f"iq_bru_srcp{i}", width=(pregs - 1).bit_length()))

    iq_lsu_valid = []
    iq_lsu_rob = []
    iq_lsu_op = []
    iq_lsu_srcl = []
    iq_lsu_srcr = []
    iq_lsu_srcp = []
    for i in range(iq_depth):
        iq_lsu_valid.append(m.input(f"iq_lsu_valid{i}", width=1))
        iq_lsu_rob.append(m.input(f"iq_lsu_rob{i}", width=rob_w))
        iq_lsu_op.append(m.input(f"iq_lsu_op{i}", width=12))
        iq_lsu_srcl.append(m.input(f"iq_lsu_srcl{i}", width=(pregs - 1).bit_length()))
        iq_lsu_srcr.append(m.input(f"iq_lsu_srcr{i}", width=(pregs - 1).bit_length()))
        iq_lsu_srcp.append(m.input(f"iq_lsu_srcp{i}", width=(pregs - 1).bit_length()))

    sub_head = (~rob_head) + u(rob_w, 1)

    # Compute per-IQ readiness and can-issue masks.
    alu_can_issue = []
    for i in range(iq_depth):
        sl_rdy = mask_bit(m, mask=ready_mask, idx=iq_alu_srcl[i], width=pregs)
        sr_rdy = mask_bit(m, mask=ready_mask, idx=iq_alu_srcr[i], width=pregs)
        sp_rdy = mask_bit(m, mask=ready_mask, idx=iq_alu_srcp[i], width=pregs)
        alu_can_issue.append(iq_alu_valid[i] & sl_rdy & sr_rdy & sp_rdy)

    bru_can_issue = []
    for i in range(iq_depth):
        sl_rdy = mask_bit(m, mask=ready_mask, idx=iq_bru_srcl[i], width=pregs)
        sr_rdy = mask_bit(m, mask=ready_mask, idx=iq_bru_srcr[i], width=pregs)
        sp_rdy = mask_bit(m, mask=ready_mask, idx=iq_bru_srcp[i], width=pregs)
        bru_can_issue.append(iq_bru_valid[i] & sl_rdy & sr_rdy & sp_rdy)

    lsu_can_issue = []
    lsu_is_load = []
    lsu_is_store = []
    for i in range(iq_depth):
        sl_rdy = mask_bit(m, mask=ready_mask, idx=iq_lsu_srcl[i], width=pregs)
        sr_rdy = mask_bit(m, mask=ready_mask, idx=iq_lsu_srcr[i], width=pregs)
        sp_rdy = mask_bit(m, mask=ready_mask, idx=iq_lsu_srcp[i], width=pregs)
        op_is = partial(_op_is, m)
        is_load_i = is_load_op(m, iq_lsu_op[i], op_is)
        is_store_i = is_store_op(m, iq_lsu_op[i], op_is)
        ready = iq_lsu_valid[i] & sl_rdy & sr_rdy & sp_rdy
        lsu_is_load.append(is_load_i)
        lsu_is_store.append(is_store_i)
        lsu_can_issue.append(ready)

    pick_p = SimpleNamespace(iq_depth=iq_depth, iq_w=iq_w, rob_w=rob_w)
    pick_consts = SimpleNamespace(zero1=zero1, one1=one1)
    alu_issue_valids, alu_issue_idxs = pick_oldest_from_arrays(
        m=m,
        p=pick_p,
        consts=pick_consts,
        can_issue=alu_can_issue,
        rob_tags=iq_alu_rob,
        width=alu_w,
        sub_head=sub_head,
    )
    bru_issue_valids, bru_issue_idxs = pick_oldest_from_arrays(
        m=m,
        p=pick_p,
        consts=pick_consts,
        can_issue=bru_can_issue,
        rob_tags=iq_bru_rob,
        width=bru_w,
        sub_head=sub_head,
    )
    lsu_issue_valids, lsu_issue_idxs = pick_oldest_from_arrays(
        m=m,
        p=pick_p,
        consts=pick_consts,
        can_issue=lsu_can_issue,
        rob_tags=iq_lsu_rob,
        width=lsu_w,
        sub_head=sub_head,
    )

    issue_valids = lsu_issue_valids + bru_issue_valids + alu_issue_valids
    issue_fires = []
    for slot in range(lsu_w + bru_w + alu_w):
        issue_fires.append(can_run & (~commit_redirect) & issue_valids[slot])

    for i in range(iq_depth):
        m.output(f"lsu_can_issue{i}", lsu_can_issue[i])
        m.output(f"lsu_is_load{i}", lsu_is_load[i])
        m.output(f"lsu_is_store{i}", lsu_is_store[i])

    for slot in range(lsu_w):
        m.output(f"lsu_issue_valid{slot}", lsu_issue_valids[slot])
        m.output(f"lsu_issue_idx{slot}", lsu_issue_idxs[slot])
        m.output(f"issue_fire{slot}", issue_fires[slot])

    for slot in range(bru_w):
        idx = lsu_w + slot
        m.output(f"bru_issue_valid{slot}", bru_issue_valids[slot])
        m.output(f"bru_issue_idx{slot}", bru_issue_idxs[slot])
        m.output(f"issue_fire{idx}", issue_fires[idx])

    for slot in range(alu_w):
        idx = lsu_w + bru_w + slot
        m.output(f"alu_issue_valid{slot}", alu_issue_valids[slot])
        m.output(f"alu_issue_idx{slot}", alu_issue_idxs[slot])
        m.output(f"issue_fire{idx}", issue_fires[idx])

    # Lane0 bring-up hooks.
    m.output("issue_valid_lane0", issue_valids[0])
    m.output("issue_fire_lane0", issue_fires[0])
    m.output("sub_head", sub_head)


@function
def _op_is(m: Circuit, op, *codes: int):
    v = op[0] & 0
    for code in codes:
        v = v | (op == u(12, code))
    return v


@module(name="LinxCoreIqUpdateStage")
def build_iq_update_stage(
    m: Circuit,
    *,
    iq_depth: int = 32,
    iq_w: int = 5,
    rob_w: int = 6,
    ptag_w: int = 6,
    dispatch_w: int = 4,
    issue_w: int = 1,
) -> None:
    c = m.const
    do_flush = m.input("do_flush", width=1)

    iq_valid = []
    iq_rob = []
    iq_op = []
    iq_pc = []
    iq_imm = []
    iq_srcl = []
    iq_srcr = []
    iq_srcr_type = []
    iq_shamt = []
    iq_srcp = []
    iq_pdst = []
    iq_has_dst = []
    for i in range(iq_depth):
        iq_valid.append(m.input(f"iq_valid{i}", width=1))
        iq_rob.append(m.input(f"iq_rob{i}", width=rob_w))
        iq_op.append(m.input(f"iq_op{i}", width=12))
        iq_pc.append(m.input(f"iq_pc{i}", width=64))
        iq_imm.append(m.input(f"iq_imm{i}", width=64))
        iq_srcl.append(m.input(f"iq_srcl{i}", width=ptag_w))
        iq_srcr.append(m.input(f"iq_srcr{i}", width=ptag_w))
        iq_srcr_type.append(m.input(f"iq_srcr_type{i}", width=2))
        iq_shamt.append(m.input(f"iq_shamt{i}", width=6))
        iq_srcp.append(m.input(f"iq_srcp{i}", width=ptag_w))
        iq_pdst.append(m.input(f"iq_pdst{i}", width=ptag_w))
        iq_has_dst.append(m.input(f"iq_has_dst{i}", width=1))

    disp_fire = []
    disp_to = []
    alloc_idx = []
    disp_rob_idx = []
    disp_op = []
    disp_pc = []
    disp_imm = []
    disp_srcl_tag = []
    disp_srcr_tag = []
    disp_srcr_type = []
    disp_shamt = []
    disp_srcp_tag = []
    disp_pdst = []
    disp_need_pdst = []
    for slot in range(dispatch_w):
        disp_fire.append(m.input(f"disp_fire{slot}", width=1))
        disp_to.append(m.input(f"disp_to{slot}", width=1))
        alloc_idx.append(m.input(f"alloc_idx{slot}", width=iq_w))
        disp_rob_idx.append(m.input(f"disp_rob_idx{slot}", width=rob_w))
        disp_op.append(m.input(f"disp_op{slot}", width=12))
        disp_pc.append(m.input(f"disp_pc{slot}", width=64))
        disp_imm.append(m.input(f"disp_imm{slot}", width=64))
        disp_srcl_tag.append(m.input(f"disp_srcl_tag{slot}", width=ptag_w))
        disp_srcr_tag.append(m.input(f"disp_srcr_tag{slot}", width=ptag_w))
        disp_srcr_type.append(m.input(f"disp_srcr_type{slot}", width=2))
        disp_shamt.append(m.input(f"disp_shamt{slot}", width=6))
        disp_srcp_tag.append(m.input(f"disp_srcp_tag{slot}", width=ptag_w))
        disp_pdst.append(m.input(f"disp_pdst{slot}", width=ptag_w))
        disp_need_pdst.append(m.input(f"disp_need_pdst{slot}", width=1))

    issue_fire = []
    issue_idx = []
    for slot in range(issue_w):
        issue_fire.append(m.input(f"issue_fire{slot}", width=1))
        issue_idx.append(m.input(f"issue_idx{slot}", width=iq_w))

    for i in range(iq_depth):
        idx = c(i, width=iq_w)

        issue_clear = c(0, width=1)
        for slot in range(issue_w):
            issue_clear = issue_clear | (issue_fire[slot] & issue_idx[slot].__eq__(idx))

        disp_alloc_hit = c(0, width=1)
        for slot in range(dispatch_w):
            disp_alloc_hit = disp_alloc_hit | (disp_fire[slot] & disp_to[slot] & alloc_idx[slot].__eq__(idx))

        v_next = iq_valid[i]
        v_next = do_flush._select_internal(c(0, width=1), v_next)
        v_next = issue_clear._select_internal(c(0, width=1), v_next)
        v_next = disp_alloc_hit._select_internal(c(1, width=1), v_next)

        robn = iq_rob[i]
        opn = iq_op[i]
        pcn = iq_pc[i]
        imn = iq_imm[i]
        sln = iq_srcl[i]
        srn = iq_srcr[i]
        stn = iq_srcr_type[i]
        shn = iq_shamt[i]
        spn = iq_srcp[i]
        pdn = iq_pdst[i]
        hdn = iq_has_dst[i]
        for slot in range(dispatch_w):
            hit = disp_fire[slot] & disp_to[slot] & alloc_idx[slot].__eq__(idx)
            robn = hit._select_internal(disp_rob_idx[slot], robn)
            opn = hit._select_internal(disp_op[slot], opn)
            pcn = hit._select_internal(disp_pc[slot], pcn)
            imn = hit._select_internal(disp_imm[slot], imn)
            sln = hit._select_internal(disp_srcl_tag[slot], sln)
            srn = hit._select_internal(disp_srcr_tag[slot], srn)
            stn = hit._select_internal(disp_srcr_type[slot], stn)
            shn = hit._select_internal(disp_shamt[slot], shn)
            spn = hit._select_internal(disp_srcp_tag[slot], spn)
            pdn = hit._select_internal(disp_pdst[slot], pdn)
            hdn = hit._select_internal(disp_need_pdst[slot], hdn)

        m.output(f"iq_valid_next{i}", v_next)
        m.output(f"iq_rob_next{i}", robn)
        m.output(f"iq_op_next{i}", opn)
        m.output(f"iq_pc_next{i}", pcn)
        m.output(f"iq_imm_next{i}", imn)
        m.output(f"iq_srcl_next{i}", sln)
        m.output(f"iq_srcr_next{i}", srn)
        m.output(f"iq_srcr_type_next{i}", stn)
        m.output(f"iq_shamt_next{i}", shn)
        m.output(f"iq_srcp_next{i}", spn)
        m.output(f"iq_pdst_next{i}", pdn)
        m.output(f"iq_has_dst_next{i}", hdn)
