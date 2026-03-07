from __future__ import annotations

from pycircuit import Circuit, const, module

from ..util import mux_by_uindex


@module(name="StandaloneOexRegFile")
def build_regfile(m: Circuit, *, entries: int = 64, tag_w: int = 8) -> None:
    clk = m.clock("clk")
    rst = m.reset("rst")

    rd0_tag = m.input("rd0_tag", width=tag_w)
    rd1_tag = m.input("rd1_tag", width=tag_w)
    wr_valid = m.input("wr_valid", width=1)
    wr_tag = m.input("wr_tag", width=tag_w)
    wr_data = m.input("wr_data", width=64)

    c = m.const

    rows = []
    for i in range(int(entries)):
        rows.append(m.out(f"rf{i}", clk=clk, rst=rst, width=64, init=c(0, width=64), en=c(1, width=1)))

    for i in range(int(entries)):
        rows[i].set(wr_data, when=wr_valid & wr_tag.__eq__(c(i, width=tag_w)))

    m.output("rd0_data", mux_by_uindex(m, idx=rd0_tag, items=rows, default=c(0, width=64)))
    m.output("rd1_data", mux_by_uindex(m, idx=rd1_tag, items=rows, default=c(0, width=64)))
