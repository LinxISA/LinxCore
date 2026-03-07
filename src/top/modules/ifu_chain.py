from __future__ import annotations

from pycircuit import Circuit, module


@module(name="LinxCoreTopIfuChain")
def build_ifu_chain(m: Circuit) -> None:
    clk = m.clock("clk")
    rst = m.reset("rst")
    c = m.const

    in_valid = m.input("in_valid", width=1)
    in_pc = m.input("in_pc", width=64)

    hold_valid = m.out("hold_valid", clk=clk, rst=rst, width=1, init=c(0, width=1), en=c(1, width=1))
    hold_pc = m.out("hold_pc", clk=clk, rst=rst, width=64, init=c(0, width=64), en=c(1, width=1))

    hold_valid.set(in_valid)
    hold_pc.set(in_pc, when=in_valid)

    m.output("out_valid", hold_valid.out())
    m.output("out_pc", hold_pc.out())
