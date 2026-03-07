from __future__ import annotations

from pycircuit import Circuit, module, u


@module(name="JanusTma")
def build_janus_tma(m: Circuit) -> None:
    clk_tma = m.clock("clk")
    rst_tma = m.reset("rst")

    cmd_valid_tma = m.input("cmd_valid_tma", width=1)
    cmd_tag_tma = m.input("cmd_tag_tma", width=8)
    cmd_payload_tma = m.input("cmd_payload_tma", width=64)
    cmd_bid_tma = m.input("cmd_bid_tma", width=64)

    flush_fire_tma = m.input("flush_fire_tma", width=1)
    flush_bid_tma = m.input("flush_bid_tma", width=64)

    busy_tma = m.out("busy_tma", clk=clk_tma, rst=rst_tma, width=1, init=u(1, 0), en=u(1, 1))
    wait_tma = m.out("wait_tma", clk=clk_tma, rst=rst_tma, width=3, init=u(3, 0), en=u(1, 1))
    tag_q_tma = m.out("tag_q_tma", clk=clk_tma, rst=rst_tma, width=8, init=u(8, 0), en=u(1, 1))
    payload_q_tma = m.out("payload_q_tma", clk=clk_tma, rst=rst_tma, width=64, init=u(64, 0), en=u(1, 1))
    bid_q_tma = m.out("bid_q_tma", clk=clk_tma, rst=rst_tma, width=64, init=u(64, 0), en=u(1, 1))

    cmd_ready_tma = ~busy_tma.out()
    accept_tma = cmd_valid_tma & cmd_ready_tma

    busy_next_tma = busy_tma.out()
    wait_next_tma = wait_tma.out()
    if accept_tma:
        busy_next_tma = u(1, 1)
        wait_next_tma = u(3, 2)

    count_down_tma = busy_tma.out() & (wait_tma.out() > u(3, 0))
    if count_down_tma:
        wait_next_tma = wait_tma.out() - u(3, 1)

    flush_kill_tma = flush_fire_tma & busy_tma.out() & (flush_bid_tma < bid_q_tma.out())
    rsp_fire_tma = busy_tma.out() & (wait_tma.out() == u(3, 0))

    if rsp_fire_tma:
        busy_next_tma = u(1, 0)
    if flush_kill_tma:
        busy_next_tma = u(1, 0)
        wait_next_tma = u(3, 0)

    busy_tma.set(busy_next_tma)
    wait_tma.set(wait_next_tma)
    tag_q_tma.set(cmd_tag_tma, when=accept_tma)
    payload_q_tma.set(cmd_payload_tma, when=accept_tma)
    bid_q_tma.set(cmd_bid_tma, when=accept_tma)

    m.output("cmd_ready_tma", cmd_ready_tma)
    m.output("rsp_valid_tma", rsp_fire_tma)
    m.output("rsp_tag_tma", tag_q_tma.out())
    m.output("rsp_bid_tma", bid_q_tma.out())
    m.output("rsp_status_tma", u(4, 0))
    m.output("rsp_data0_tma", payload_q_tma.out() + u(64, 1))
    m.output("rsp_data1_tma", payload_q_tma.out())
