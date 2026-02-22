from __future__ import annotations

from pycircuit import Circuit, module


@module(name="JanusTmuNocPipe")
def build_janus_tmu_noc_pipe(m: Circuit) -> None:
    clk_noc = m.clock("clk")
    rst_noc = m.reset("rst")

    in_valid_noc = m.input("in_valid_noc", width=1)
    in_data_noc = m.input("in_data_noc", width=64)
    out_ready_noc = m.input("out_ready_noc", width=1)

    c = m.const

    valid_q_noc = m.out("valid_q_noc", clk=clk_noc, rst=rst_noc, width=1, init=c(0, width=1), en=c(1, width=1))
    data_q_noc = m.out("data_q_noc", clk=clk_noc, rst=rst_noc, width=64, init=c(0, width=64), en=c(1, width=1))

    can_accept_noc = (~valid_q_noc.out()) | out_ready_noc
    in_fire_noc = in_valid_noc & can_accept_noc

    valid_next_noc = valid_q_noc.out()
    valid_next_noc = out_ready_noc._select_internal(c(0, width=1), valid_next_noc)
    valid_next_noc = in_fire_noc._select_internal(c(1, width=1), valid_next_noc)
    data_next_noc = in_fire_noc._select_internal(in_data_noc, data_q_noc.out())

    valid_q_noc.set(valid_next_noc)
    data_q_noc.set(data_next_noc)

    m.output("in_ready_noc", can_accept_noc)
    m.output("out_valid_noc", valid_q_noc.out())
    m.output("out_data_noc", data_q_noc.out())
