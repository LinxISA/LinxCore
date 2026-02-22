from __future__ import annotations

from pycircuit import Circuit, module


@module(name="LinxCoreVec")
def build_linxcore_vec(m: Circuit) -> None:
    clk_vec = m.clock("clk")
    rst_vec = m.reset("rst")

    cmd_valid_vec = m.input("cmd_valid_vec", width=1)
    cmd_tag_vec = m.input("cmd_tag_vec", width=8)
    cmd_payload_vec = m.input("cmd_payload_vec", width=64)

    c = m.const

    busy_vec = m.out("busy_vec", clk=clk_vec, rst=rst_vec, width=1, init=c(0, width=1), en=c(1, width=1))
    wait_vec = m.out("wait_vec", clk=clk_vec, rst=rst_vec, width=3, init=c(0, width=3), en=c(1, width=1))
    tag_q_vec = m.out("tag_q_vec", clk=clk_vec, rst=rst_vec, width=8, init=c(0, width=8), en=c(1, width=1))
    payload_q_vec = m.out("payload_q_vec", clk=clk_vec, rst=rst_vec, width=64, init=c(0, width=64), en=c(1, width=1))

    cmd_ready_vec = ~busy_vec.out()
    accept_vec = cmd_valid_vec & cmd_ready_vec

    busy_next_vec = busy_vec.out()
    wait_next_vec = wait_vec.out()
    busy_next_vec = accept_vec._select_internal(c(1, width=1), busy_next_vec)
    wait_next_vec = accept_vec._select_internal(c(2, width=3), wait_next_vec)

    count_down_vec = busy_vec.out() & wait_vec.out().ugt(c(0, width=3))
    wait_next_vec = count_down_vec._select_internal(wait_vec.out() - c(1, width=3), wait_next_vec)

    rsp_fire_vec = busy_vec.out() & wait_vec.out().eq(c(0, width=3))
    busy_next_vec = rsp_fire_vec._select_internal(c(0, width=1), busy_next_vec)

    busy_vec.set(busy_next_vec)
    wait_vec.set(wait_next_vec)
    tag_q_vec.set(cmd_tag_vec, when=accept_vec)
    payload_q_vec.set(cmd_payload_vec, when=accept_vec)

    m.output("cmd_ready_vec", cmd_ready_vec)
    m.output("rsp_valid_vec", rsp_fire_vec)
    m.output("rsp_tag_vec", tag_q_vec.out())
    m.output("rsp_status_vec", c(0, width=4))
    m.output("rsp_data0_vec", payload_q_vec.out() ^ c(0x55AA55AA55AA55AA, width=64))
    m.output("rsp_data1_vec", payload_q_vec.out())
