from __future__ import annotations

from pycircuit import Circuit, const, module

from ..util import mux_by_uindex


@module(name="StandaloneOexPcBuffer")
def build_pc_buffer(m: Circuit, *, depth: int = 64, idx_w: int = 6) -> None:
    clk = m.clock("clk")
    rst = m.reset("rst")

    wr_valid = m.input("wr_valid", width=1)
    wr_idx = m.input("wr_idx", width=idx_w)
    wr_pc = m.input("wr_pc", width=64)
    rd_idx = m.input("rd_idx", width=idx_w)

    c = m.const

    rows = []
    for i in range(int(depth)):
        rows.append(m.out(f"pc{i}", clk=clk, rst=rst, width=64, init=c(0, width=64), en=c(1, width=1)))

    for i in range(int(depth)):
        rows[i].set(wr_pc, when=wr_valid & wr_idx.__eq__(c(i, width=idx_w)))

    rd_pc = mux_by_uindex(m, idx=rd_idx, items=rows, default=c(0, width=64))
    m.output("rd_pc", rd_pc)
