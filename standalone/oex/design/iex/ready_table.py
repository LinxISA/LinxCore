from __future__ import annotations

from pycircuit import Circuit, const, module

from ..util import mux_by_uindex


@module(name="StandaloneOexReadyTable")
def build_ready_table(m: Circuit, *, entries: int = 64, tag_w: int = 8) -> None:
    clk = m.clock("clk")
    rst = m.reset("rst")

    set_valid = m.input("set_valid", width=1)
    set_tag = m.input("set_tag", width=tag_w)
    clr_valid = m.input("clr_valid", width=1)
    clr_tag = m.input("clr_tag", width=tag_w)
    q0_tag = m.input("q0_tag", width=tag_w)
    q1_tag = m.input("q1_tag", width=tag_w)

    c = m.const

    rows = []
    for i in range(int(entries)):
        rows.append(m.out(f"rdy{i}", clk=clk, rst=rst, width=1, init=c(0, width=1), en=c(1, width=1)))

    for i in range(int(entries)):
        idx = c(i, width=tag_w)
        rows[i].set(c(1, width=1), when=set_valid & set_tag.__eq__(idx))
        rows[i].set(c(0, width=1), when=clr_valid & clr_tag.__eq__(idx))

    m.output("q0_ready", mux_by_uindex(m, idx=q0_tag, items=rows, default=c(0, width=1)))
    m.output("q1_ready", mux_by_uindex(m, idx=q1_tag, items=rows, default=c(0, width=1)))
