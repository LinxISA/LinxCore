from __future__ import annotations

from pycircuit import Circuit, module, u


@module(name="JanusCube")
def build_janus_cube(m: Circuit) -> None:
    clk_cube = m.clock("clk")
    rst_cube = m.reset("rst")

    cmd_valid_cube = m.input("cmd_valid_cube", width=1)
    cmd_tag_cube = m.input("cmd_tag_cube", width=8)
    cmd_payload_cube = m.input("cmd_payload_cube", width=64)
    cmd_bid_cube = m.input("cmd_bid_cube", width=64)

    flush_fire_cube = m.input("flush_fire_cube", width=1)
    flush_bid_cube = m.input("flush_bid_cube", width=64)

    busy_cube = m.out("busy_cube", clk=clk_cube, rst=rst_cube, width=1, init=u(1, 0), en=u(1, 1))
    wait_cube = m.out("wait_cube", clk=clk_cube, rst=rst_cube, width=3, init=u(3, 0), en=u(1, 1))
    tag_q_cube = m.out("tag_q_cube", clk=clk_cube, rst=rst_cube, width=8, init=u(8, 0), en=u(1, 1))
    payload_q_cube = m.out("payload_q_cube", clk=clk_cube, rst=rst_cube, width=64, init=u(64, 0), en=u(1, 1))
    bid_q_cube = m.out("bid_q_cube", clk=clk_cube, rst=rst_cube, width=64, init=u(64, 0), en=u(1, 1))

    cmd_ready_cube = ~busy_cube.out()
    accept_cube = cmd_valid_cube & cmd_ready_cube

    busy_next_cube = busy_cube.out()
    wait_next_cube = wait_cube.out()
    if accept_cube:
        busy_next_cube = u(1, 1)
        wait_next_cube = u(3, 3)

    count_down_cube = busy_cube.out() & (wait_cube.out() > u(3, 0))
    if count_down_cube:
        wait_next_cube = wait_cube.out() - u(3, 1)

    # Flush: kill younger in-flight op (bid > flush_bid).
    flush_kill_cube = flush_fire_cube & busy_cube.out() & (flush_bid_cube < bid_q_cube.out())
    rsp_fire_cube = busy_cube.out() & (wait_cube.out() == u(3, 0))

    if rsp_fire_cube:
        busy_next_cube = u(1, 0)
    if flush_kill_cube:
        busy_next_cube = u(1, 0)
        wait_next_cube = u(3, 0)

    busy_cube.set(busy_next_cube)
    wait_cube.set(wait_next_cube)
    tag_q_cube.set(cmd_tag_cube, when=accept_cube)
    payload_q_cube.set(cmd_payload_cube, when=accept_cube)
    bid_q_cube.set(cmd_bid_cube, when=accept_cube)

    m.output("cmd_ready_cube", cmd_ready_cube)
    m.output("rsp_valid_cube", rsp_fire_cube)
    m.output("rsp_tag_cube", tag_q_cube.out())
    m.output("rsp_bid_cube", bid_q_cube.out())
    m.output("rsp_status_cube", u(4, 0))
    m.output("rsp_data0_cube", ~payload_q_cube.out())
    m.output("rsp_data1_cube", payload_q_cube.out())
