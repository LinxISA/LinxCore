from __future__ import annotations

from pycircuit import Circuit, module


@module(name="JanusTau")
def build_janus_tau(m: Circuit) -> None:
    clk_tau = m.clock("clk")
    rst_tau = m.reset("rst")

    cmd_valid_tau = m.input("cmd_valid_tau", width=1)
    cmd_tag_tau = m.input("cmd_tag_tau", width=8)
    cmd_payload_tau = m.input("cmd_payload_tau", width=64)

    c = m.const

    busy_tau = m.out("busy_tau", clk=clk_tau, rst=rst_tau, width=1, init=c(0, width=1), en=c(1, width=1))
    wait_tau = m.out("wait_tau", clk=clk_tau, rst=rst_tau, width=3, init=c(0, width=3), en=c(1, width=1))
    tag_q_tau = m.out("tag_q_tau", clk=clk_tau, rst=rst_tau, width=8, init=c(0, width=8), en=c(1, width=1))
    payload_q_tau = m.out("payload_q_tau", clk=clk_tau, rst=rst_tau, width=64, init=c(0, width=64), en=c(1, width=1))

    cmd_ready_tau = ~busy_tau.out()
    accept_tau = cmd_valid_tau & cmd_ready_tau

    busy_next_tau = busy_tau.out()
    wait_next_tau = wait_tau.out()
    busy_next_tau = accept_tau._select_internal(c(1, width=1), busy_next_tau)
    wait_next_tau = accept_tau._select_internal(c(4, width=3), wait_next_tau)

    count_down_tau = busy_tau.out() & wait_tau.out().ugt(c(0, width=3))
    wait_next_tau = count_down_tau._select_internal(wait_tau.out() - c(1, width=3), wait_next_tau)

    rsp_fire_tau = busy_tau.out() & wait_tau.out().eq(c(0, width=3))
    busy_next_tau = rsp_fire_tau._select_internal(c(0, width=1), busy_next_tau)

    busy_tau.set(busy_next_tau)
    wait_tau.set(wait_next_tau)
    tag_q_tau.set(cmd_tag_tau, when=accept_tau)
    payload_q_tau.set(cmd_payload_tau, when=accept_tau)

    m.output("cmd_ready_tau", cmd_ready_tau)
    m.output("rsp_valid_tau", rsp_fire_tau)
    m.output("rsp_tag_tau", tag_q_tau.out())
    m.output("rsp_status_tau", c(0, width=4))
    m.output("rsp_data0_tau", payload_q_tau.out() + payload_q_tau.out())
    m.output("rsp_data1_tau", payload_q_tau.out())
