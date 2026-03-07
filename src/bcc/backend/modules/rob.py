from __future__ import annotations

from pycircuit import Circuit, module


@module(name="LinxCoreRob")
def build_rob(m: Circuit) -> None:
    clk = m.clock("clk")
    rst = m.reset("rst")
    c = m.const

    alloc_i = m.input("alloc_i", width=1)
    commit_i = m.input("commit_i", width=1)

    rob_count = m.out("rob_count", clk=clk, rst=rst, width=8, init=c(0, width=8), en=c(1, width=1))
    next_count = rob_count.out()
    next_count = (alloc_i & (~commit_i))._select_internal(next_count + c(1, width=8), next_count)
    next_count = ((~alloc_i) & commit_i)._select_internal(next_count - c(1, width=8), next_count)
    rob_count.set(next_count)

    rob_empty = rob_count.out().__eq__(c(0, width=8))
    rob_almost_full = rob_count.out().uge(c(250, width=8))
    m.output("rob_count_o", rob_count.out())
    m.output("rob_full_o", rob_count.out().__eq__(c(255, width=8)))
    m.output("rob_empty_o", rob_empty)
    m.output("rob_almost_full_o", rob_almost_full)
