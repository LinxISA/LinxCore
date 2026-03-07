from __future__ import annotations

from pycircuit import Circuit, module


@module(name="LinxCoreExecuteLanes")
def build_execute_lanes(m: Circuit) -> None:
    clk = m.clock("clk")
    rst = m.reset("rst")
    c = m.const

    issue_fire = m.input("issue_fire", width=1)
    issue_val = m.input("issue_val", width=64)

    e1_valid = m.out("e1_valid", clk=clk, rst=rst, width=1, init=c(0, width=1), en=c(1, width=1))
    e1_data = m.out("e1_data", clk=clk, rst=rst, width=64, init=c(0, width=64), en=c(1, width=1))
    e1_valid.set(issue_fire)
    e1_data.set(issue_val, when=issue_fire)

    wb_ready = (~e1_valid.out())._select_internal(c(1, width=1), c(0, width=1))
    wb_busy = e1_valid.out() & (~wb_ready)
    wb_idle = (~e1_valid.out()) & wb_ready
    m.output("wb_valid_o", e1_valid.out())
    m.output("wb_data_o", e1_data.out())
    m.output("wb_ready_o", wb_ready)
    m.output("wb_busy_o", wb_busy)
    m.output("wb_idle_o", wb_idle)
