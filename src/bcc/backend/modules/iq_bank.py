from __future__ import annotations

from pycircuit import Circuit, module

from common.util import make_consts
from ..helpers import mask_bit, mux_by_uindex
from ..issue import build_iq_update_stage, pick_oldest_from_arrays


def _build_iq_bank_core(
    m: Circuit,
    *,
    iq_depth: int,
    iq_w: int,
    rob_w: int,
    ptag_w: int,
    dispatch_w: int,
    issue_w: int,
    pregs: int,
) -> None:
    clk = m.clock("clk")
    rst = m.reset("rst")
    c = m.const
    consts = make_consts(m)

    do_flush = m.input("do_flush", width=1)

    disp_fire = [m.input(f"disp_fire{slot}", width=1) for slot in range(dispatch_w)]
    disp_to = [m.input(f"disp_to{slot}", width=1) for slot in range(dispatch_w)]
    alloc_idx = [m.input(f"alloc_idx{slot}", width=iq_w) for slot in range(dispatch_w)]
    disp_rob_idx = [m.input(f"disp_rob_idx{slot}", width=rob_w) for slot in range(dispatch_w)]
    disp_op = [m.input(f"disp_op{slot}", width=12) for slot in range(dispatch_w)]
    disp_pc = [m.input(f"disp_pc{slot}", width=64) for slot in range(dispatch_w)]
    disp_imm = [m.input(f"disp_imm{slot}", width=64) for slot in range(dispatch_w)]
    disp_srcl_tag = [m.input(f"disp_srcl_tag{slot}", width=ptag_w) for slot in range(dispatch_w)]
    disp_srcr_tag = [m.input(f"disp_srcr_tag{slot}", width=ptag_w) for slot in range(dispatch_w)]
    disp_srcr_type = [m.input(f"disp_srcr_type{slot}", width=2) for slot in range(dispatch_w)]
    disp_shamt = [m.input(f"disp_shamt{slot}", width=6) for slot in range(dispatch_w)]
    disp_srcp_tag = [m.input(f"disp_srcp_tag{slot}", width=ptag_w) for slot in range(dispatch_w)]
    disp_pdst = [m.input(f"disp_pdst{slot}", width=ptag_w) for slot in range(dispatch_w)]
    disp_need_pdst = [m.input(f"disp_need_pdst{slot}", width=1) for slot in range(dispatch_w)]

    issue_fire = [m.input(f"issue_fire{slot}", width=1) for slot in range(issue_w)]
    issue_idx = [m.input(f"issue_idx{slot}", width=iq_w) for slot in range(issue_w)]
    ready_mask = m.input("ready_mask", width=pregs)
    head_idx = m.input("head_idx", width=rob_w)

    tag0 = c(0, width=ptag_w)

    # Banked IQ state. Bring-up keeps this as a simple allocated+issued FIFO-like
    # structure; more complete scheduler semantics will extend the payload.
    with m.scope("iq"):
        valid = []
        rob = []
        op = []
        pc = []
        imm = []
        srcl = []
        srcr = []
        srcr_type_r = []
        shamt = []
        srcp = []
        pdst = []
        has_dst = []
        for i in range(iq_depth):
            valid.append(m.out(f"valid{i}", clk=clk, rst=rst, width=1, init=consts.zero1, en=consts.one1))
            rob.append(m.out(f"rob{i}", clk=clk, rst=rst, width=rob_w, init=c(0, width=rob_w), en=consts.one1))
            op.append(m.out(f"op{i}", clk=clk, rst=rst, width=12, init=c(0, width=12), en=consts.one1))
            pc.append(m.out(f"pc{i}", clk=clk, rst=rst, width=64, init=consts.zero64, en=consts.one1))
            imm.append(m.out(f"imm{i}", clk=clk, rst=rst, width=64, init=consts.zero64, en=consts.one1))
            srcl.append(m.out(f"srcl{i}", clk=clk, rst=rst, width=ptag_w, init=tag0, en=consts.one1))
            srcr.append(m.out(f"srcr{i}", clk=clk, rst=rst, width=ptag_w, init=tag0, en=consts.one1))
            srcr_type_r.append(m.out(f"srcr_type{i}", clk=clk, rst=rst, width=2, init=c(0, width=2), en=consts.one1))
            shamt.append(m.out(f"shamt{i}", clk=clk, rst=rst, width=6, init=consts.zero6, en=consts.one1))
            srcp.append(m.out(f"srcp{i}", clk=clk, rst=rst, width=ptag_w, init=tag0, en=consts.one1))
            pdst.append(m.out(f"pdst{i}", clk=clk, rst=rst, width=ptag_w, init=tag0, en=consts.one1))
            has_dst.append(m.out(f"has_dst{i}", clk=clk, rst=rst, width=1, init=consts.zero1, en=consts.one1))

    stage_args = {"do_flush": do_flush}
    for i in range(iq_depth):
        stage_args[f"iq_valid{i}"] = valid[i].out()
        stage_args[f"iq_rob{i}"] = rob[i].out()
        stage_args[f"iq_op{i}"] = op[i].out()
        stage_args[f"iq_pc{i}"] = pc[i].out()
        stage_args[f"iq_imm{i}"] = imm[i].out()
        stage_args[f"iq_srcl{i}"] = srcl[i].out()
        stage_args[f"iq_srcr{i}"] = srcr[i].out()
        stage_args[f"iq_srcr_type{i}"] = srcr_type_r[i].out()
        stage_args[f"iq_shamt{i}"] = shamt[i].out()
        stage_args[f"iq_srcp{i}"] = srcp[i].out()
        stage_args[f"iq_pdst{i}"] = pdst[i].out()
        stage_args[f"iq_has_dst{i}"] = has_dst[i].out()

    for slot in range(dispatch_w):
        stage_args[f"disp_fire{slot}"] = disp_fire[slot]
        stage_args[f"disp_to{slot}"] = disp_to[slot]
        stage_args[f"alloc_idx{slot}"] = alloc_idx[slot]
        stage_args[f"disp_rob_idx{slot}"] = disp_rob_idx[slot]
        stage_args[f"disp_op{slot}"] = disp_op[slot]
        stage_args[f"disp_pc{slot}"] = disp_pc[slot]
        stage_args[f"disp_imm{slot}"] = disp_imm[slot]
        stage_args[f"disp_srcl_tag{slot}"] = disp_srcl_tag[slot]
        stage_args[f"disp_srcr_tag{slot}"] = disp_srcr_tag[slot]
        stage_args[f"disp_srcr_type{slot}"] = disp_srcr_type[slot]
        stage_args[f"disp_shamt{slot}"] = disp_shamt[slot]
        stage_args[f"disp_srcp_tag{slot}"] = disp_srcp_tag[slot]
        stage_args[f"disp_pdst{slot}"] = disp_pdst[slot]
        stage_args[f"disp_need_pdst{slot}"] = disp_need_pdst[slot]

    for slot in range(issue_w):
        stage_args[f"issue_fire{slot}"] = issue_fire[slot]
        stage_args[f"issue_idx{slot}"] = issue_idx[slot]

    iq_stage = m.instance_auto(
        build_iq_update_stage,
        name="iq_update_stage",
        params={
            "iq_depth": int(iq_depth),
            "iq_w": int(iq_w),
            "rob_w": int(rob_w),
            "ptag_w": int(ptag_w),
            "dispatch_w": int(dispatch_w),
            "issue_w": int(issue_w),
        },
        **stage_args,
    )

    for i in range(iq_depth):
        valid[i].set(iq_stage[f"iq_valid_next{i}"])
        rob[i].set(iq_stage[f"iq_rob_next{i}"])
        op[i].set(iq_stage[f"iq_op_next{i}"])
        pc[i].set(iq_stage[f"iq_pc_next{i}"])
        imm[i].set(iq_stage[f"iq_imm_next{i}"])
        srcl[i].set(iq_stage[f"iq_srcl_next{i}"])
        srcr[i].set(iq_stage[f"iq_srcr_next{i}"])
        srcr_type_r[i].set(iq_stage[f"iq_srcr_type_next{i}"])
        shamt[i].set(iq_stage[f"iq_shamt_next{i}"])
        srcp[i].set(iq_stage[f"iq_srcp_next{i}"])
        pdst[i].set(iq_stage[f"iq_pdst_next{i}"])
        has_dst[i].set(iq_stage[f"iq_has_dst_next{i}"])

    valid_out = [valid_i.out() for valid_i in valid]
    rob_out = [rob_i.out() for rob_i in rob]
    op_out = [op_i.out() for op_i in op]
    pc_out = [pc_i.out() for pc_i in pc]
    imm_out = [imm_i.out() for imm_i in imm]
    srcl_out = [srcl_i.out() for srcl_i in srcl]
    srcr_out = [srcr_i.out() for srcr_i in srcr]
    srcr_type_out = [srcr_type_i.out() for srcr_type_i in srcr_type_r]
    shamt_out = [shamt_i.out() for shamt_i in shamt]
    srcp_out = [srcp_i.out() for srcp_i in srcp]
    pdst_out = [pdst_i.out() for pdst_i in pdst]
    has_dst_out = [has_dst_i.out() for has_dst_i in has_dst]

    valid_mask = valid_out[0]
    for i in range(1, iq_depth):
        valid_mask = m.cat(valid_out[i], valid_mask)
    m.output("valid_mask_o", valid_mask)

    resident_valid = consts.zero1
    resident_rob = c(0, width=rob_w)
    resident_pc = consts.zero64
    for i in range(iq_depth):
        hit = (~resident_valid) & valid_out[i]
        resident_valid = hit._select_internal(consts.one1, resident_valid)
        resident_rob = hit._select_internal(rob_out[i], resident_rob)
        resident_pc = hit._select_internal(pc_out[i], resident_pc)
    m.output("resident_valid_o", resident_valid)
    m.output("resident_rob_o", resident_rob)
    m.output("resident_pc_o", resident_pc)

    sub_head = (~head_idx) + c(1, width=rob_w)
    can_issue = []
    for i in range(iq_depth):
        sl_rdy = mask_bit(m, mask=ready_mask, idx=srcl[i].out(), width=pregs)
        sr_rdy = mask_bit(m, mask=ready_mask, idx=srcr[i].out(), width=pregs)
        sp_rdy = mask_bit(m, mask=ready_mask, idx=srcp[i].out(), width=pregs)
        can_issue.append(valid[i].out() & sl_rdy & sr_rdy & sp_rdy)
    issue_pick_valids, issue_pick_idxs = pick_oldest_from_arrays(
        m=m,
        iq_depth=iq_depth,
        iq_w=iq_w,
        rob_w=rob_w,
        can_issue=can_issue,
        rob_tags=[rob_i.out() for rob_i in rob],
        width=issue_w,
        sub_head=sub_head,
    )
    for slot in range(issue_w):
        m.output(f"issue_pick_valid{slot}_o", issue_pick_valids[slot])
        m.output(f"issue_pick_idx{slot}_o", issue_pick_idxs[slot])
        m.output(f"issue_pick_rob{slot}_o", mux_by_uindex(m, idx=issue_pick_idxs[slot], items=rob_out, default=c(0, width=rob_w)))
        m.output(f"issue_pick_op{slot}_o", mux_by_uindex(m, idx=issue_pick_idxs[slot], items=op_out, default=c(0, width=12)))
        m.output(f"issue_pick_pc{slot}_o", mux_by_uindex(m, idx=issue_pick_idxs[slot], items=pc_out, default=consts.zero64))
        m.output(f"issue_pick_imm{slot}_o", mux_by_uindex(m, idx=issue_pick_idxs[slot], items=imm_out, default=consts.zero64))
        m.output(f"issue_pick_srcl{slot}_o", mux_by_uindex(m, idx=issue_pick_idxs[slot], items=srcl_out, default=tag0))
        m.output(f"issue_pick_srcr{slot}_o", mux_by_uindex(m, idx=issue_pick_idxs[slot], items=srcr_out, default=tag0))
        m.output(
            f"issue_pick_srcr_type{slot}_o",
            mux_by_uindex(m, idx=issue_pick_idxs[slot], items=srcr_type_out, default=c(0, width=2)),
        )
        m.output(f"issue_pick_shamt{slot}_o", mux_by_uindex(m, idx=issue_pick_idxs[slot], items=shamt_out, default=consts.zero6))
        m.output(f"issue_pick_srcp{slot}_o", mux_by_uindex(m, idx=issue_pick_idxs[slot], items=srcp_out, default=tag0))
        m.output(f"issue_pick_pdst{slot}_o", mux_by_uindex(m, idx=issue_pick_idxs[slot], items=pdst_out, default=tag0))
        m.output(
            f"issue_pick_has_dst{slot}_o",
            mux_by_uindex(m, idx=issue_pick_idxs[slot], items=has_dst_out, default=consts.zero1),
        )

    head_wait_hit = consts.zero1
    head_wait_sl = tag0
    head_wait_sr = tag0
    head_wait_sp = tag0
    for i in range(iq_depth):
        hit = valid[i].out() & rob[i].out().__eq__(head_idx)
        head_wait_hit = hit._select_internal(consts.one1, head_wait_hit)
        head_wait_sl = hit._select_internal(srcl[i].out(), head_wait_sl)
        head_wait_sr = hit._select_internal(srcr[i].out(), head_wait_sr)
        head_wait_sp = hit._select_internal(srcp[i].out(), head_wait_sp)
    m.output("head_wait_hit_o", head_wait_hit)
    m.output("head_wait_sl_o", head_wait_sl)
    m.output("head_wait_sr_o", head_wait_sr)
    m.output("head_wait_sp_o", head_wait_sp)


@module(name="LinxCoreIqBankTop")
def build_iq_bank_top(
    m: Circuit,
    *,
    iq_depth: int = 32,
    iq_w: int = 5,
    rob_w: int = 6,
    ptag_w: int = 6,
    dispatch_w: int = 4,
    issue_w: int = 1,
    pregs: int = 64,
) -> None:
    # Keep @module a thin wrapper; avoid calling helpers that pass hardware
    # objects across the JIT boundary (pyc4 hard-break).
    _build_iq_bank_core(
        m,
        iq_depth=int(iq_depth),
        iq_w=int(iq_w),
        rob_w=int(rob_w),
        ptag_w=int(ptag_w),
        dispatch_w=int(dispatch_w),
        issue_w=int(issue_w),
        pregs=int(pregs),
    )
