from __future__ import annotations

from pycircuit import Circuit, module


@module(name="JanusCube")
def build_janus_cube(m: Circuit) -> None:
    clk_cube = m.clock("clk")
    rst_cube = m.reset("rst")

    cmd_valid_cube = m.input("cmd_valid_cube", width=1)
    cmd_tag_cube = m.input("cmd_tag_cube", width=8)
    cmd_payload_cube = m.input("cmd_payload_cube", width=64)

    c = m.const

    busy_cube = m.out("busy_cube", clk=clk_cube, rst=rst_cube, width=1, init=c(0, width=1), en=c(1, width=1))
    wait_cube = m.out("wait_cube", clk=clk_cube, rst=rst_cube, width=3, init=c(0, width=3), en=c(1, width=1))
    tag_q_cube = m.out("tag_q_cube", clk=clk_cube, rst=rst_cube, width=8, init=c(0, width=8), en=c(1, width=1))
    payload_q_cube = m.out("payload_q_cube", clk=clk_cube, rst=rst_cube, width=64, init=c(0, width=64), en=c(1, width=1))

    cmd_ready_cube = ~busy_cube.out()
    accept_cube = cmd_valid_cube & cmd_ready_cube

    busy_next_cube = busy_cube.out()
    wait_next_cube = wait_cube.out()
    busy_next_cube = accept_cube._select_internal(c(1, width=1), busy_next_cube)
    wait_next_cube = accept_cube._select_internal(c(3, width=3), wait_next_cube)

    count_down_cube = busy_cube.out() & wait_cube.out().ugt(c(0, width=3))
    wait_next_cube = count_down_cube._select_internal(wait_cube.out() - c(1, width=3), wait_next_cube)

    rsp_fire_cube = busy_cube.out() & wait_cube.out().eq(c(0, width=3))
    busy_next_cube = rsp_fire_cube._select_internal(c(0, width=1), busy_next_cube)

    busy_cube.set(busy_next_cube)
    wait_cube.set(wait_next_cube)
    tag_q_cube.set(cmd_tag_cube, when=accept_cube)
    payload_q_cube.set(cmd_payload_cube, when=accept_cube)

    m.output("cmd_ready_cube", cmd_ready_cube)
    m.output("rsp_valid_cube", rsp_fire_cube)
    m.output("rsp_tag_cube", tag_q_cube.out())
    m.output("rsp_status_cube", c(0, width=4))
    m.output("rsp_data0_cube", ~payload_q_cube.out())
    m.output("rsp_data1_cube", payload_q_cube.out())
