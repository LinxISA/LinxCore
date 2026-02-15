from __future__ import annotations

from pycircuit import Circuit, module


@module(name="JanusBccLsuLiq")
def build_janus_bcc_lsu_liq(m: Circuit, *, depth: int = 32, idx_w: int = 5) -> None:
    clk_top = m.clock("clk")
    rst_top = m.reset("rst")

    load_valid_liq = m.input("load_valid_liq", width=1)
    load_rob_liq = m.input("load_rob_liq", width=6)
    load_addr_liq = m.input("load_addr_liq", width=64)

    c = m.const

    with m.scope("liq_ctrl"):
        head_liq = m.out("head_liq", clk=clk_top, rst=rst_top, width=idx_w, init=c(0, width=idx_w), en=c(1, width=1))
        tail_liq = m.out("tail_liq", clk=clk_top, rst=rst_top, width=idx_w, init=c(0, width=idx_w), en=c(1, width=1))
        count_liq = m.out("count_liq", clk=clk_top, rst=rst_top, width=idx_w + 1, init=c(0, width=idx_w + 1), en=c(1, width=1))

    fire_enq_liq = load_valid_liq & count_liq.out().ult(c(depth, width=idx_w + 1))

    head_liq.set(head_liq.out())
    tail_liq.set(fire_enq_liq._select_internal(tail_liq.out() + c(1, width=idx_w), tail_liq.out()))
    count_liq.set(fire_enq_liq._select_internal(count_liq.out() + c(1, width=idx_w + 1), count_liq.out()))

    m.output("liq_fire_liq", fire_enq_liq)
    m.output("liq_rob_liq", load_rob_liq)
    m.output("liq_addr_liq", load_addr_liq)
    m.output("liq_count_liq", count_liq.out())
