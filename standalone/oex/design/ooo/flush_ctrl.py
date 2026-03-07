from __future__ import annotations

from pycircuit import Circuit, const, module


@module(name="StandaloneOexFlushCtrl")
def build_flush_ctrl(m: Circuit) -> None:
    clk = m.clock("clk")
    rst = m.reset("rst")

    iex_redirect_valid = m.input("iex_redirect_valid", width=1)
    iex_redirect_pc = m.input("iex_redirect_pc", width=64)
    rob_redirect_valid = m.input("rob_redirect_valid", width=1)
    rob_redirect_pc = m.input("rob_redirect_pc", width=64)

    c = m.const

    epoch = m.out("epoch", clk=clk, rst=rst, width=8, init=c(0, width=8), en=c(1, width=1))

    flush_valid = iex_redirect_valid | rob_redirect_valid
    flush_pc = rob_redirect_valid._select_internal(rob_redirect_pc, iex_redirect_pc)

    epoch.set(flush_valid._select_internal(epoch.out() + c(1, width=8), epoch.out()))

    m.output("flush_valid", flush_valid)
    m.output("flush_pc", flush_pc)
    m.output("flush_epoch", epoch.out())
