from __future__ import annotations

from pycircuit import Circuit, module


@module(name="JanusTma")
def build_janus_tma(m: Circuit) -> None:
    clk_tma = m.clock("clk")
    rst_tma = m.reset("rst")

    cmd_valid_tma = m.input("cmd_valid_tma", width=1)
    cmd_tag_tma = m.input("cmd_tag_tma", width=8)
    cmd_payload_tma = m.input("cmd_payload_tma", width=64)

    c = m.const

    busy_tma = m.out("busy_tma", clk=clk_tma, rst=rst_tma, width=1, init=c(0, width=1), en=c(1, width=1))
    wait_tma = m.out("wait_tma", clk=clk_tma, rst=rst_tma, width=3, init=c(0, width=3), en=c(1, width=1))
    tag_q_tma = m.out("tag_q_tma", clk=clk_tma, rst=rst_tma, width=8, init=c(0, width=8), en=c(1, width=1))
    payload_q_tma = m.out("payload_q_tma", clk=clk_tma, rst=rst_tma, width=64, init=c(0, width=64), en=c(1, width=1))

    cmd_ready_tma = ~busy_tma.out()
    accept_tma = cmd_valid_tma & cmd_ready_tma

    busy_next_tma = busy_tma.out()
    wait_next_tma = wait_tma.out()
    busy_next_tma = accept_tma._select_internal(c(1, width=1), busy_next_tma)
    wait_next_tma = accept_tma._select_internal(c(2, width=3), wait_next_tma)

    count_down_tma = busy_tma.out() & wait_tma.out().ugt(c(0, width=3))
    wait_next_tma = count_down_tma._select_internal(wait_tma.out() - c(1, width=3), wait_next_tma)

    rsp_fire_tma = busy_tma.out() & wait_tma.out().eq(c(0, width=3))
    busy_next_tma = rsp_fire_tma._select_internal(c(0, width=1), busy_next_tma)

    busy_tma.set(busy_next_tma)
    wait_tma.set(wait_next_tma)
    tag_q_tma.set(cmd_tag_tma, when=accept_tma)
    payload_q_tma.set(cmd_payload_tma, when=accept_tma)

    m.output("cmd_ready_tma", cmd_ready_tma)
    m.output("rsp_valid_tma", rsp_fire_tma)
    m.output("rsp_tag_tma", tag_q_tma.out())
    m.output("rsp_status_tma", c(0, width=4))
    m.output("rsp_data0_tma", payload_q_tma.out() + c(1, width=64))
    m.output("rsp_data1_tma", payload_q_tma.out())
