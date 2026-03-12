from __future__ import annotations

from pycircuit import Circuit, module

from ..helpers import mask_bit


@module(name="LinxCoreIqBankHeld")
def build_iq_bank_top(
    m: Circuit,
    *,
    iq_depth: int = 32,
    iq_w: int = 5,
    rob_w: int = 6,
    ptag_w: int = 6,
    dispatch_w: int = 4,
    issue_w: int = 2,
    pregs: int = 64,
) -> None:
    clk = m.clock("clk")
    rst = m.reset("rst")
    do_flush = m.input("do_flush", width=1)
    flush_bid = m.input("flush_bid", width=64)
    ready_mask = m.input("ready_mask", width=pregs)
    head_idx = m.input("head_idx", width=rob_w)
    c = m.const

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
    disp_block_bid = []
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
        disp_block_bid.append(m.input(f"disp_block_bid{slot}", width=64))

    issue_fire = []
    issue_idx = []
    for slot in range(issue_w):
        issue_fire.append(m.input(f"issue_fire{slot}", width=1))
        issue_idx.append(m.input(f"issue_idx{slot}", width=iq_w))

    valid = []
    rob = []
    op = []
    pc = []
    imm = []
    srcl = []
    srcr = []
    srcr_type = []
    shamt = []
    srcp = []
    pdst = []
    has_dst = []
    block_bid = []
    for i in range(iq_depth):
        valid.append(m.out(f"valid{i}", clk=clk, rst=rst, width=1, init=c(0, width=1), en=c(1, width=1)))
        rob.append(m.out(f"rob{i}", clk=clk, rst=rst, width=rob_w, init=c(0, width=rob_w), en=c(1, width=1)))
        op.append(m.out(f"op{i}", clk=clk, rst=rst, width=12, init=c(0, width=12), en=c(1, width=1)))
        pc.append(m.out(f"pc{i}", clk=clk, rst=rst, width=64, init=c(0, width=64), en=c(1, width=1)))
        imm.append(m.out(f"imm{i}", clk=clk, rst=rst, width=64, init=c(0, width=64), en=c(1, width=1)))
        srcl.append(m.out(f"srcl{i}", clk=clk, rst=rst, width=ptag_w, init=c(0, width=ptag_w), en=c(1, width=1)))
        srcr.append(m.out(f"srcr{i}", clk=clk, rst=rst, width=ptag_w, init=c(0, width=ptag_w), en=c(1, width=1)))
        srcr_type.append(m.out(f"srcr_type{i}", clk=clk, rst=rst, width=2, init=c(0, width=2), en=c(1, width=1)))
        shamt.append(m.out(f"shamt{i}", clk=clk, rst=rst, width=6, init=c(0, width=6), en=c(1, width=1)))
        srcp.append(m.out(f"srcp{i}", clk=clk, rst=rst, width=ptag_w, init=c(0, width=ptag_w), en=c(1, width=1)))
        pdst.append(m.out(f"pdst{i}", clk=clk, rst=rst, width=ptag_w, init=c(0, width=ptag_w), en=c(1, width=1)))
        has_dst.append(m.out(f"has_dst{i}", clk=clk, rst=rst, width=1, init=c(0, width=1), en=c(1, width=1)))
        block_bid.append(m.out(f"block_bid{i}", clk=clk, rst=rst, width=64, init=c(0, width=64), en=c(1, width=1)))

    sub_head = (~head_idx) + c(1, width=rob_w)
    can_issue = []
    for i in range(iq_depth):
        sl_rdy = mask_bit(m, mask=ready_mask, idx=srcl[i].out(), width=pregs)
        sr_rdy = mask_bit(m, mask=ready_mask, idx=srcr[i].out(), width=pregs)
        sp_rdy = mask_bit(m, mask=ready_mask, idx=srcp[i].out(), width=pregs)
        can_issue.append(valid[i].out() & sl_rdy & sr_rdy & sp_rdy)

    pick_valid = []
    pick_idx = []
    for slot in range(issue_w):
        v = c(0, width=1)
        idx = c(0, width=iq_w)
        best_age = c((1 << rob_w) - 1, width=rob_w)
        for i in range(iq_depth):
            cand_idx = c(i, width=iq_w)
            exclude = c(0, width=1)
            for prev in range(slot):
                exclude = exclude | (pick_valid[prev] & pick_idx[prev].__eq__(cand_idx))
            age = rob[i].out() + sub_head
            take = can_issue[i] & (~exclude) & ((~v) | (age < best_age))
            v = take._select_internal(c(1, width=1), v)
            idx = take._select_internal(cand_idx, idx)
            best_age = take._select_internal(age, best_age)
        pick_valid.append(v)
        pick_idx.append(idx)

    wait_hit = c(0, width=1)
    wait_sl = c(0, width=ptag_w)
    wait_sr = c(0, width=ptag_w)
    wait_sp = c(0, width=ptag_w)
    wait_best_age = c((1 << rob_w) - 1, width=rob_w)
    resident_valid = c(0, width=1)
    resident_pc = c(0, width=64)
    resident_rob = c(0, width=rob_w)
    resident_best_age = c((1 << rob_w) - 1, width=rob_w)
    for i in range(iq_depth):
        sl_rdy = mask_bit(m, mask=ready_mask, idx=srcl[i].out(), width=pregs)
        sr_rdy = mask_bit(m, mask=ready_mask, idx=srcr[i].out(), width=pregs)
        sp_rdy = mask_bit(m, mask=ready_mask, idx=srcp[i].out(), width=pregs)
        blocked = valid[i].out() & (~(sl_rdy & sr_rdy & sp_rdy))
        age = rob[i].out() + sub_head
        resident_take = valid[i].out() & ((~resident_valid) | (age < resident_best_age))
        resident_valid = resident_take._select_internal(c(1, width=1), resident_valid)
        resident_pc = resident_take._select_internal(pc[i].out(), resident_pc)
        resident_rob = resident_take._select_internal(rob[i].out(), resident_rob)
        resident_best_age = resident_take._select_internal(age, resident_best_age)
        take = blocked & ((~wait_hit) | (age < wait_best_age))
        wait_hit = take._select_internal(c(1, width=1), wait_hit)
        wait_sl = take._select_internal(srcl[i].out(), wait_sl)
        wait_sr = take._select_internal(srcr[i].out(), wait_sr)
        wait_sp = take._select_internal(srcp[i].out(), wait_sp)
        wait_best_age = take._select_internal(age, wait_best_age)

    for i in range(iq_depth):
        clear_i = do_flush & valid[i].out() & flush_bid.ult(block_bid[i].out())
        for slot in range(issue_w):
            clear_i = clear_i | (issue_fire[slot] & issue_idx[slot].__eq__(c(i, width=iq_w)))
        next_rob = rob[i].out()
        next_op = op[i].out()
        next_pc = pc[i].out()
        next_imm = imm[i].out()
        next_srcl = srcl[i].out()
        next_srcr = srcr[i].out()
        next_srcr_type = srcr_type[i].out()
        next_shamt = shamt[i].out()
        next_srcp = srcp[i].out()
        next_pdst = pdst[i].out()
        next_has_dst = has_dst[i].out()
        next_block_bid = block_bid[i].out()
        next_rob = clear_i._select_internal(c(0, width=rob_w), next_rob)
        next_op = clear_i._select_internal(c(0, width=12), next_op)
        next_pc = clear_i._select_internal(c(0, width=64), next_pc)
        next_imm = clear_i._select_internal(c(0, width=64), next_imm)
        next_srcl = clear_i._select_internal(c(0, width=ptag_w), next_srcl)
        next_srcr = clear_i._select_internal(c(0, width=ptag_w), next_srcr)
        next_srcr_type = clear_i._select_internal(c(0, width=2), next_srcr_type)
        next_shamt = clear_i._select_internal(c(0, width=6), next_shamt)
        next_srcp = clear_i._select_internal(c(0, width=ptag_w), next_srcp)
        next_pdst = clear_i._select_internal(c(0, width=ptag_w), next_pdst)
        next_has_dst = clear_i._select_internal(c(0, width=1), next_has_dst)
        next_block_bid = clear_i._select_internal(c(0, width=64), next_block_bid)
        set_i = c(0, width=1)
        for slot in range(dispatch_w):
            hit = disp_fire[slot] & disp_to[slot] & alloc_idx[slot].__eq__(c(i, width=iq_w))
            set_i = set_i | hit
            next_rob = hit._select_internal(disp_rob_idx[slot], next_rob)
            next_op = hit._select_internal(disp_op[slot], next_op)
            next_pc = hit._select_internal(disp_pc[slot], next_pc)
            next_imm = hit._select_internal(disp_imm[slot], next_imm)
            next_srcl = hit._select_internal(disp_srcl_tag[slot], next_srcl)
            next_srcr = hit._select_internal(disp_srcr_tag[slot], next_srcr)
            next_srcr_type = hit._select_internal(disp_srcr_type[slot], next_srcr_type)
            next_shamt = hit._select_internal(disp_shamt[slot], next_shamt)
            next_srcp = hit._select_internal(disp_srcp_tag[slot], next_srcp)
            next_pdst = hit._select_internal(disp_pdst[slot], next_pdst)
            next_has_dst = hit._select_internal(disp_need_pdst[slot], next_has_dst)
            next_block_bid = hit._select_internal(disp_block_bid[slot], next_block_bid)
        next_valid = valid[i].out()
        next_valid = clear_i._select_internal(c(0, width=1), next_valid)
        next_valid = set_i._select_internal(c(1, width=1), next_valid)
        valid[i].set(next_valid)
        rob[i].set(next_rob)
        op[i].set(next_op)
        pc[i].set(next_pc)
        imm[i].set(next_imm)
        srcl[i].set(next_srcl)
        srcr[i].set(next_srcr)
        srcr_type[i].set(next_srcr_type)
        shamt[i].set(next_shamt)
        srcp[i].set(next_srcp)
        pdst[i].set(next_pdst)
        has_dst[i].set(next_has_dst)
        block_bid[i].set(next_block_bid)

    valid_bits = []
    for i in range(iq_depth):
        valid_bits.append(valid[iq_depth - 1 - i].out())
    valid_mask = valid_bits[0]
    for i in range(1, iq_depth):
        valid_mask = m.concat(valid_mask, valid_bits[i])
    m.output("valid_mask_o", valid_mask)
    m.output("head_wait_hit_o", wait_hit)
    m.output("head_wait_sl_o", wait_sl)
    m.output("head_wait_sr_o", wait_sr)
    m.output("head_wait_sp_o", wait_sp)
    m.output("resident_valid_o", resident_valid)
    m.output("resident_pc_o", resident_pc)
    m.output("resident_rob_o", resident_rob)
    for slot in range(issue_w):
        idx = pick_idx[slot]
        pick_valid_o = pick_valid[slot]
        pick_rob_o = c(0, width=rob_w)
        pick_op_o = c(0, width=12)
        pick_pc_o = c(0, width=64)
        pick_imm_o = c(0, width=64)
        pick_srcl_o = c(0, width=ptag_w)
        pick_srcr_o = c(0, width=ptag_w)
        pick_srcr_type_o = c(0, width=2)
        pick_shamt_o = c(0, width=6)
        pick_srcp_o = c(0, width=ptag_w)
        pick_pdst_o = c(0, width=ptag_w)
        pick_has_dst_o = c(0, width=1)
        for i in range(iq_depth):
            hit = idx.__eq__(c(i, width=iq_w))
            pick_rob_o = hit._select_internal(rob[i].out(), pick_rob_o)
            pick_op_o = hit._select_internal(op[i].out(), pick_op_o)
            pick_pc_o = hit._select_internal(pc[i].out(), pick_pc_o)
            pick_imm_o = hit._select_internal(imm[i].out(), pick_imm_o)
            pick_srcl_o = hit._select_internal(srcl[i].out(), pick_srcl_o)
            pick_srcr_o = hit._select_internal(srcr[i].out(), pick_srcr_o)
            pick_srcr_type_o = hit._select_internal(srcr_type[i].out(), pick_srcr_type_o)
            pick_shamt_o = hit._select_internal(shamt[i].out(), pick_shamt_o)
            pick_srcp_o = hit._select_internal(srcp[i].out(), pick_srcp_o)
            pick_pdst_o = hit._select_internal(pdst[i].out(), pick_pdst_o)
            pick_has_dst_o = hit._select_internal(has_dst[i].out(), pick_has_dst_o)
        m.output(f"issue_pick_valid{slot}_o", pick_valid_o)
        m.output(f"issue_pick_idx{slot}_o", idx)
        m.output(f"issue_pick_rob{slot}_o", pick_rob_o)
        m.output(f"issue_pick_op{slot}_o", pick_op_o)
        m.output(f"issue_pick_pc{slot}_o", pick_pc_o)
        m.output(f"issue_pick_imm{slot}_o", pick_imm_o)
        m.output(f"issue_pick_srcl{slot}_o", pick_srcl_o)
        m.output(f"issue_pick_srcr{slot}_o", pick_srcr_o)
        m.output(f"issue_pick_srcr_type{slot}_o", pick_srcr_type_o)
        m.output(f"issue_pick_shamt{slot}_o", pick_shamt_o)
        m.output(f"issue_pick_srcp{slot}_o", pick_srcp_o)
        m.output(f"issue_pick_pdst{slot}_o", pick_pdst_o)
        m.output(f"issue_pick_has_dst{slot}_o", pick_has_dst_o)


build_iq_bank_top.__pycircuit_name__ = "LinxCoreIqBankHeld"
