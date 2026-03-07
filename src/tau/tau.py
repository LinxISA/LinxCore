from __future__ import annotations

from pycircuit import Circuit, module, u


@module(name="JanusTau")
def build_janus_tau(m: Circuit) -> None:
    clk_tau = m.clock("clk")
    rst_tau = m.reset("rst")

    cmd_valid_tau = m.input("cmd_valid_tau", width=1)
    cmd_tag_tau = m.input("cmd_tag_tau", width=8)
    cmd_payload_tau = m.input("cmd_payload_tau", width=64)
    cmd_bid_tau = m.input("cmd_bid_tau", width=64)

    flush_fire_tau = m.input("flush_fire_tau", width=1)
    flush_bid_tau = m.input("flush_bid_tau", width=64)

    busy_tau = m.out("busy_tau", clk=clk_tau, rst=rst_tau, width=1, init=u(1, 0), en=u(1, 1))
    wait_tau = m.out("wait_tau", clk=clk_tau, rst=rst_tau, width=3, init=u(3, 0), en=u(1, 1))
    tag_q_tau = m.out("tag_q_tau", clk=clk_tau, rst=rst_tau, width=8, init=u(8, 0), en=u(1, 1))
    payload_q_tau = m.out("payload_q_tau", clk=clk_tau, rst=rst_tau, width=64, init=u(64, 0), en=u(1, 1))
    bid_q_tau = m.out("bid_q_tau", clk=clk_tau, rst=rst_tau, width=64, init=u(64, 0), en=u(1, 1))

    cmd_ready_tau = ~busy_tau.out()
    accept_tau = cmd_valid_tau & cmd_ready_tau

    busy_next_tau = busy_tau.out()
    wait_next_tau = wait_tau.out()
    if accept_tau:
        busy_next_tau = u(1, 1)
        wait_next_tau = u(3, 4)

    count_down_tau = busy_tau.out() & (wait_tau.out() > u(3, 0))
    if count_down_tau:
        wait_next_tau = wait_tau.out() - u(3, 1)

    # Flush: kill younger in-flight op (bid > flush_bid).
    flush_kill_tau = flush_fire_tau & busy_tau.out() & (flush_bid_tau < bid_q_tau.out())
    rsp_fire_tau = busy_tau.out() & (wait_tau.out() == u(3, 0))

    if rsp_fire_tau:
        busy_next_tau = u(1, 0)
    if flush_kill_tau:
        busy_next_tau = u(1, 0)
        wait_next_tau = u(3, 0)

    busy_tau.set(busy_next_tau)
    wait_tau.set(wait_next_tau)
    tag_q_tau.set(cmd_tag_tau, when=accept_tau)
    payload_q_tau.set(cmd_payload_tau, when=accept_tau)
    bid_q_tau.set(cmd_bid_tau, when=accept_tau)

    m.output("cmd_ready_tau", cmd_ready_tau)
    m.output("rsp_valid_tau", rsp_fire_tau)
    m.output("rsp_tag_tau", tag_q_tau.out())
    m.output("rsp_bid_tau", bid_q_tau.out())
    m.output("rsp_status_tau", u(4, 0))
    m.output("rsp_data0_tau", payload_q_tau.out() + payload_q_tau.out())
    m.output("rsp_data1_tau", payload_q_tau.out())
