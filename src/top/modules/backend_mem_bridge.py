from __future__ import annotations

from pycircuit import Circuit, module


@module(name="LinxCoreTopBackendMemBridge")
def build_backend_mem_bridge(m: Circuit) -> None:
    clk = m.clock("clk")
    rst = m.reset("rst")
    c = m.const

    dmem_rdata = m.input("dmem_rdata", width=64)
    dmem_req = m.input("dmem_req", width=1)

    seen_req = m.out("seen_req", clk=clk, rst=rst, width=1, init=c(0, width=1), en=c(1, width=1))
    seen_req.set(dmem_req | seen_req.out())

    m.output("bridge_seen_req", seen_req.out())
    m.output("bridge_rdata", dmem_rdata)
