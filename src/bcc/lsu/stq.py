from __future__ import annotations

from pycircuit import Circuit, module


@module(name="JanusBccLsuStq")
def build_janus_bcc_lsu_stq(m: Circuit, *, depth: int = 32, idx_w: int = 5) -> None:
    clk_top = m.clock("clk")
    rst_top = m.reset("rst")

    store_valid_stq = m.input("store_valid_stq", width=1)
    store_rob_stq = m.input("store_rob_stq", width=6)
    store_addr_stq = m.input("store_addr_stq", width=64)
    store_data_stq = m.input("store_data_stq", width=64)

    c = m.const

    with m.scope("stq_ctrl"):
        head_stq = m.out("head_stq", clk=clk_top, rst=rst_top, width=idx_w, init=c(0, width=idx_w), en=c(1, width=1))
        tail_stq = m.out("tail_stq", clk=clk_top, rst=rst_top, width=idx_w, init=c(0, width=idx_w), en=c(1, width=1))
        count_stq = m.out("count_stq", clk=clk_top, rst=rst_top, width=idx_w + 1, init=c(0, width=idx_w + 1), en=c(1, width=1))

    addr_head_stq = m.out("addr_head_stq", clk=clk_top, rst=rst_top, width=64, init=c(0, width=64), en=c(1, width=1))
    data_head_stq = m.out("data_head_stq", clk=clk_top, rst=rst_top, width=64, init=c(0, width=64), en=c(1, width=1))
    rob_head_stq = m.out("rob_head_stq", clk=clk_top, rst=rst_top, width=6, init=c(0, width=6), en=c(1, width=1))

    enq_stq = store_valid_stq & count_stq.out().ult(c(depth, width=idx_w + 1))
    tail_stq.set(enq_stq._select_internal(tail_stq.out() + c(1, width=idx_w), tail_stq.out()))
    count_stq.set(enq_stq._select_internal(count_stq.out() + c(1, width=idx_w + 1), count_stq.out()))

    addr_head_stq.set(store_addr_stq, when=enq_stq)
    data_head_stq.set(store_data_stq, when=enq_stq)
    rob_head_stq.set(store_rob_stq, when=enq_stq)

    m.output("stq_enq_fire_stq", enq_stq)
    m.output("stq_head_store_valid_stq", ~(count_stq.out() == c(0, width=idx_w + 1)))
    m.output("stq_head_store_addr_stq", addr_head_stq.out())
    m.output("stq_head_store_data_stq", data_head_stq.out())
    m.output("stq_head_store_rob_stq", rob_head_stq.out())
