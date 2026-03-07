from __future__ import annotations

from pycircuit import Circuit, module

from ..helpers import mux_by_uindex
from .age_matrix import build_age_matrix
from .ready_table import build_ready_table


@module(name="LinxCoreIssueQueueAgeMatrix")
def build_issue_queue_age_matrix(
    m: Circuit,
    *,
    depth: int = 16,
    rob_w: int = 6,
    ptag_w: int = 6,
) -> None:
    clk = m.clock("clk")
    rst = m.reset("rst")
    c = m.const

    depth_i = max(1, int(depth))
    rob_w_i = max(1, int(rob_w))
    ptag_w_i = max(1, int(ptag_w))
    idx_w = max(1, (depth_i - 1).bit_length())
    ptag_entries = 1 << ptag_w_i

    enq_valid = m.input("enq_valid", width=1)
    enq_rob = m.input("enq_rob", width=rob_w_i)
    enq_src0_valid = m.input("enq_src0_valid", width=1)
    enq_src0_ptag = m.input("enq_src0_ptag", width=ptag_w_i)
    enq_src1_valid = m.input("enq_src1_valid", width=1)
    enq_src1_ptag = m.input("enq_src1_ptag", width=ptag_w_i)
    pick_i = m.input("pick_i", width=1)
    wb_valid = m.input("wb_valid", width=1)
    wb_ptag = m.input("wb_ptag", width=ptag_w_i)

    ready_table = m.instance_auto(
        build_ready_table,
        name="ready_table",
        module_name="LinxCoreIssueReadyTable",
        params={"entries": ptag_entries, "tag_w": ptag_w_i},
        clk=clk,
        rst=rst,
        set_valid=wb_valid,
        set_tag=wb_ptag,
        clr_valid=c(0, width=1),
        clr_tag=c(0, width=ptag_w_i),
        q0_tag=enq_src0_ptag,
        q1_tag=enq_src1_ptag,
    )

    valid = []
    rob = []
    src0_v = []
    src0_t = []
    src0_r = []
    src1_v = []
    src1_t = []
    src1_r = []

    for i in range(depth_i):
        valid.append(m.out(f"v{i}", clk=clk, rst=rst, width=1, init=c(0, width=1), en=c(1, width=1)))
        rob.append(m.out(f"rob{i}", clk=clk, rst=rst, width=rob_w_i, init=c(0, width=rob_w_i), en=c(1, width=1)))
        src0_v.append(m.out(f"s0v{i}", clk=clk, rst=rst, width=1, init=c(0, width=1), en=c(1, width=1)))
        src0_t.append(m.out(f"s0t{i}", clk=clk, rst=rst, width=ptag_w_i, init=c(0, width=ptag_w_i), en=c(1, width=1)))
        src0_r.append(m.out(f"s0r{i}", clk=clk, rst=rst, width=1, init=c(1, width=1), en=c(1, width=1)))
        src1_v.append(m.out(f"s1v{i}", clk=clk, rst=rst, width=1, init=c(0, width=1), en=c(1, width=1)))
        src1_t.append(m.out(f"s1t{i}", clk=clk, rst=rst, width=ptag_w_i, init=c(0, width=ptag_w_i), en=c(1, width=1)))
        src1_r.append(m.out(f"s1r{i}", clk=clk, rst=rst, width=1, init=c(1, width=1), en=c(1, width=1)))

    free_found = c(0, width=1)
    free_idx = c(0, width=idx_w)
    valid_mask = c(0, width=depth_i)
    cand_mask = c(0, width=depth_i)
    alloc_fire_w = m.new_wire(width=1)
    alloc_idx_w = m.new_wire(width=idx_w)
    free_fire_w = m.new_wire(width=1)
    free_idx_w = m.new_wire(width=idx_w)

    for i in range(depth_i):
        bit = c(1 << i, width=depth_i)
        is_valid = valid[i].out()
        valid_mask = is_valid._select_internal(valid_mask | bit, valid_mask)

        is_free = (~is_valid) & (~free_found)
        free_found = is_free._select_internal(c(1, width=1), free_found)
        free_idx = is_free._select_internal(c(i, width=idx_w), free_idx)

        ready_i = ((~src0_v[i].out()) | src0_r[i].out()) & ((~src1_v[i].out()) | src1_r[i].out())
        can_pick = is_valid & ready_i
        cand_mask = can_pick._select_internal(cand_mask | bit, cand_mask)

    age = m.instance_auto(
        build_age_matrix,
        name="age_matrix",
        module_name="LinxCoreIssueAgeMatrix",
        params={"depth": depth_i, "idx_w": idx_w},
        clk=clk,
        rst=rst,
        alloc_fire=alloc_fire_w,
        alloc_idx=alloc_idx_w,
        free_fire=free_fire_w,
        free_idx=free_idx_w,
        valid_mask=valid_mask,
        cand_mask=cand_mask,
    )

    enq_fire = enq_valid & free_found
    out_valid = pick_i & age["oldest_valid"]
    out_idx = age["oldest_idx"]
    m.assign(alloc_fire_w, enq_fire)
    m.assign(alloc_idx_w, free_idx)
    m.assign(free_fire_w, out_valid)
    m.assign(free_idx_w, out_idx)

    for i in range(depth_i):
        idx = c(i, width=idx_w)
        do_enq = enq_fire & free_idx.__eq__(idx)
        do_deq = out_valid & out_idx.__eq__(idx)

        v_next = valid[i].out()
        v_next = do_deq._select_internal(c(0, width=1), v_next)
        v_next = do_enq._select_internal(c(1, width=1), v_next)
        valid[i].set(v_next)

        w0 = wb_valid & valid[i].out() & src0_v[i].out() & (~src0_r[i].out()) & src0_t[i].out().__eq__(wb_ptag)
        w1 = wb_valid & valid[i].out() & src1_v[i].out() & (~src1_r[i].out()) & src1_t[i].out().__eq__(wb_ptag)

        s0r_next = src0_r[i].out()
        s0r_next = w0._select_internal(c(1, width=1), s0r_next)
        s0r_next = do_enq._select_internal((~enq_src0_valid) | ready_table["q0_ready"], s0r_next)
        src0_r[i].set(s0r_next)

        s1r_next = src1_r[i].out()
        s1r_next = w1._select_internal(c(1, width=1), s1r_next)
        s1r_next = do_enq._select_internal((~enq_src1_valid) | ready_table["q1_ready"], s1r_next)
        src1_r[i].set(s1r_next)

        rob[i].set(enq_rob, when=do_enq)
        src0_v[i].set(enq_src0_valid, when=do_enq)
        src0_t[i].set(enq_src0_ptag, when=do_enq)
        src1_v[i].set(enq_src1_valid, when=do_enq)
        src1_t[i].set(enq_src1_ptag, when=do_enq)

    m.output("enq_ready", free_found)
    m.output("out_valid", out_valid)
    m.output("out_idx", out_idx)
    m.output("out_rob", mux_by_uindex(m, idx=out_idx, items=rob, default=c(0, width=rob_w_i)))
