from __future__ import annotations

from pycircuit import Circuit, module


@module(name="LinxCoreTopBlockFabric")
def build_block_fabric(m: Circuit) -> None:
    clk = m.clock("clk")
    rst = m.reset("rst")
    c = m.const

    block_open = m.input("block_open", width=1)
    block_close = m.input("block_close", width=1)

    block_active = m.out("block_active", clk=clk, rst=rst, width=1, init=c(0, width=1), en=c(1, width=1))
    nxt = block_open._select_internal(c(1, width=1), block_active.out())
    nxt = block_close._select_internal(c(0, width=1), nxt)
    block_active.set(nxt)

    m.output("block_active_o", block_active.out())
