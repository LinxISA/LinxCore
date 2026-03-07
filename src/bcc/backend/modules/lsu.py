from __future__ import annotations

from pycircuit import Circuit, module

from common.module_specs import backend_mem_if_spec


@module(name="LinxCoreLsu")
def build_lsu(m: Circuit) -> None:
    clk = m.clock("clk")
    rst = m.reset("rst")
    c = m.const

    mem_spec = backend_mem_if_spec(m)
    mem = m.inputs(mem_spec, prefix="mem_")
    load_fire = m.input("load_fire", width=1)

    pending = m.out("pending", clk=clk, rst=rst, width=1, init=c(0, width=1), en=c(1, width=1))
    next_pending = load_fire._select_internal(c(1, width=1), pending.out())
    next_pending = mem["dmem_wvalid"].read()._select_internal(c(0, width=1), next_pending)
    pending.set(next_pending)

    idle = (~pending.out()) & (~load_fire)
    busy = pending.out() | load_fire
    m.output("lsu_pending_o", pending.out())
    m.output("lsu_rdata_o", mem["dmem_rdata"].read())
    m.output("lsu_idle_o", idle)
    m.output("lsu_busy_o", busy)
