from __future__ import annotations

from pycircuit import Circuit, ct, meta, module

from ..stage_specs import issue_uop_spec
from ..util import mux_by_uindex


@module(name="StandaloneOexIssueQueue")
def build_issue_queue(
    m: Circuit,
    *,
    depth: int = 16,
    rob_w: int = 8,
    ptag_w: int = 7,
    wb_ports: int = 4,
) -> None:
    """Single-enqueue / single-issue (per cycle) issue queue with wakeups.

    This module is instantiated per execution lane. Superscalar throughput is
    achieved by having multiple independent issue queues (one per lane), not
    by multi-issue from a single queue.
    """
    clk = m.clock("clk")
    rst = m.reset("rst")
    c = m.const

    depth_i = max(1, int(depth))
    idx_w = max(1, ct.clog2(depth_i))
    wb_ports_i = max(1, int(wb_ports))
    rob_w_i = max(1, int(rob_w))
    ptag_w_i = max(1, int(ptag_w))

    uop_s = issue_uop_spec(m, rob_w=rob_w_i, ptag_w=ptag_w_i)

    enq = m.inputs(uop_s, prefix="enq_")
    pick_i = m.input("pick_i", width=1)
    enq_src0_ready = m.input("enq_src0_ready", width=1)
    enq_src1_ready = m.input("enq_src1_ready", width=1)

    wb_valid = []
    wb_ptag = []
    for k in range(wb_ports_i):
        wb_valid.append(m.input(f"wb{k}_valid", width=1))
        wb_ptag.append(m.input(f"wb{k}_ptag", width=ptag_w_i))

    valid = []
    rob = []
    src0_valid = []
    src0_ptag = []
    src0_rdy = []
    src1_valid = []
    src1_ptag = []
    src1_rdy = []
    dst_valid = []
    dst_ptag = []

    for i in range(depth_i):
        valid.append(m.out(f"v{i}", clk=clk, rst=rst, width=1, init=c(0, width=1), en=c(1, width=1)))
        rob.append(m.out(f"rob{i}", clk=clk, rst=rst, width=rob_w_i, init=c(0, width=rob_w_i), en=c(1, width=1)))

        src0_valid.append(m.out(f"s0v{i}", clk=clk, rst=rst, width=1, init=c(0, width=1), en=c(1, width=1)))
        src0_ptag.append(m.out(f"s0t{i}", clk=clk, rst=rst, width=ptag_w_i, init=c(0, width=ptag_w_i), en=c(1, width=1)))
        src0_rdy.append(m.out(f"s0r{i}", clk=clk, rst=rst, width=1, init=c(1, width=1), en=c(1, width=1)))

        src1_valid.append(m.out(f"s1v{i}", clk=clk, rst=rst, width=1, init=c(0, width=1), en=c(1, width=1)))
        src1_ptag.append(m.out(f"s1t{i}", clk=clk, rst=rst, width=ptag_w_i, init=c(0, width=ptag_w_i), en=c(1, width=1)))
        src1_rdy.append(m.out(f"s1r{i}", clk=clk, rst=rst, width=1, init=c(1, width=1), en=c(1, width=1)))

        dst_valid.append(m.out(f"dtv{i}", clk=clk, rst=rst, width=1, init=c(0, width=1), en=c(1, width=1)))
        dst_ptag.append(m.out(f"dtt{i}", clk=clk, rst=rst, width=ptag_w_i, init=c(0, width=ptag_w_i), en=c(1, width=1)))

    free_found = c(0, width=1)
    free_idx = c(0, width=idx_w)
    ready_found = c(0, width=1)
    ready_idx = c(0, width=idx_w)

    for i in range(depth_i):
        idx = c(i, width=idx_w)
        vi = valid[i].out()

        is_free = (~vi) & (~free_found)
        free_idx = is_free._select_internal(idx, free_idx)
        free_found = is_free._select_internal(c(1, width=1), free_found)

        s0_ok = (~src0_valid[i].out()) | src0_rdy[i].out()
        s1_ok = (~src1_valid[i].out()) | src1_rdy[i].out()
        is_ready = vi & s0_ok & s1_ok & (~ready_found)
        ready_idx = is_ready._select_internal(idx, ready_idx)
        ready_found = is_ready._select_internal(c(1, width=1), ready_found)

    enq_ready = free_found
    enq_fire = enq["valid"].read() & enq_ready
    pick_fire = pick_i & ready_found

    for i in range(depth_i):
        idx = c(i, width=idx_w)
        enq_here = enq_fire & free_idx.__eq__(idx)
        pick_here = pick_fire & ready_idx.__eq__(idx)

        v_next = valid[i].out()
        v_next = pick_here._select_internal(c(0, width=1), v_next)
        v_next = enq_here._select_internal(c(1, width=1), v_next)
        valid[i].set(v_next)

        # Wakeups: any wb tag can mark an operand ready.
        w0 = c(0, width=1)
        w1 = c(0, width=1)
        for k in range(wb_ports_i):
            w0 = w0 | (wb_valid[k] & valid[i].out() & src0_valid[i].out() & (~src0_rdy[i].out()) & src0_ptag[i].out().__eq__(wb_ptag[k]))
            w1 = w1 | (wb_valid[k] & valid[i].out() & src1_valid[i].out() & (~src1_rdy[i].out()) & src1_ptag[i].out().__eq__(wb_ptag[k]))

        s0_next = src0_rdy[i].out()
        s0_next = w0._select_internal(c(1, width=1), s0_next)
        s0_next = enq_here._select_internal(enq_src0_ready | (~enq["src0_valid"].read()), s0_next)
        src0_rdy[i].set(s0_next)

        s1_next = src1_rdy[i].out()
        s1_next = w1._select_internal(c(1, width=1), s1_next)
        s1_next = enq_here._select_internal(enq_src1_ready | (~enq["src1_valid"].read()), s1_next)
        src1_rdy[i].set(s1_next)

        rob[i].set(enq["rob"], when=enq_here)
        src0_valid[i].set(enq["src0_valid"], when=enq_here)
        src0_ptag[i].set(enq["src0_ptag"], when=enq_here)
        src1_valid[i].set(enq["src1_valid"], when=enq_here)
        src1_ptag[i].set(enq["src1_ptag"], when=enq_here)
        dst_valid[i].set(enq["dst_valid"], when=enq_here)
        dst_ptag[i].set(enq["dst_ptag"], when=enq_here)

    # Issue output.
    m.output("enq_ready", enq_ready)
    m.output("out_valid", pick_fire)
    m.output("out_rob", mux_by_uindex(m, idx=ready_idx, items=rob, default=c(0, width=rob_w_i)))
    m.output("out_src0_valid", mux_by_uindex(m, idx=ready_idx, items=src0_valid, default=c(0, width=1)))
    m.output("out_src0_ptag", mux_by_uindex(m, idx=ready_idx, items=src0_ptag, default=c(0, width=ptag_w_i)))
    m.output("out_src1_valid", mux_by_uindex(m, idx=ready_idx, items=src1_valid, default=c(0, width=1)))
    m.output("out_src1_ptag", mux_by_uindex(m, idx=ready_idx, items=src1_ptag, default=c(0, width=ptag_w_i)))
    m.output("out_dst_valid", mux_by_uindex(m, idx=ready_idx, items=dst_valid, default=c(0, width=1)))
    m.output("out_dst_ptag", mux_by_uindex(m, idx=ready_idx, items=dst_ptag, default=c(0, width=ptag_w_i)))

