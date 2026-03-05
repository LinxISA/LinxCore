from __future__ import annotations

from pycircuit import Circuit, module, u


@module(name="LinxCoreVec")
def build_linxcore_vec(m: Circuit) -> None:
    clk_vec = m.clock("clk")
    rst_vec = m.reset("rst")

    cmd_valid_vec = m.input("cmd_valid_vec", width=1)
    cmd_tag_vec = m.input("cmd_tag_vec", width=8)
    cmd_payload_vec = m.input("cmd_payload_vec", width=64)
    cmd_bid_vec = m.input("cmd_bid_vec", width=64)

    flush_fire_vec = m.input("flush_fire_vec", width=1)
    flush_bid_vec = m.input("flush_bid_vec", width=64)

    busy_vec = m.out("busy_vec", clk=clk_vec, rst=rst_vec, width=1, init=u(1, 0), en=u(1, 1))
    wait_vec = m.out("wait_vec", clk=clk_vec, rst=rst_vec, width=3, init=u(3, 0), en=u(1, 1))
    tag_q_vec = m.out("tag_q_vec", clk=clk_vec, rst=rst_vec, width=8, init=u(8, 0), en=u(1, 1))
    payload_q_vec = m.out("payload_q_vec", clk=clk_vec, rst=rst_vec, width=64, init=u(64, 0), en=u(1, 1))
    bid_q_vec = m.out("bid_q_vec", clk=clk_vec, rst=rst_vec, width=64, init=u(64, 0), en=u(1, 1))

    cmd_ready_vec = ~busy_vec.out()
    accept_vec = cmd_valid_vec & cmd_ready_vec

    busy_next_vec = busy_vec.out()
    wait_next_vec = wait_vec.out()
    if accept_vec:
        busy_next_vec = u(1, 1)
        wait_next_vec = u(3, 2)

    count_down_vec = busy_vec.out() & (wait_vec.out() > u(3, 0))
    if count_down_vec:
        wait_next_vec = wait_vec.out() - u(3, 1)

    # Flush: kill younger in-flight op (bid > flush_bid).
    flush_kill_vec = flush_fire_vec & busy_vec.out() & (flush_bid_vec < bid_q_vec.out())
    rsp_fire_vec = busy_vec.out() & (wait_vec.out() == u(3, 0))

    if rsp_fire_vec:
        busy_next_vec = u(1, 0)
    if flush_kill_vec:
        busy_next_vec = u(1, 0)
        wait_next_vec = u(3, 0)

    busy_vec.set(busy_next_vec)
    wait_vec.set(wait_next_vec)
    tag_q_vec.set(cmd_tag_vec, when=accept_vec)
    payload_q_vec.set(cmd_payload_vec, when=accept_vec)
    bid_q_vec.set(cmd_bid_vec, when=accept_vec)

    m.output("cmd_ready_vec", cmd_ready_vec)
    m.output("rsp_valid_vec", rsp_fire_vec)
    m.output("rsp_tag_vec", tag_q_vec.out())
    m.output("rsp_bid_vec", bid_q_vec.out())
    m.output("rsp_status_vec", u(4, 0))
    m.output("rsp_data0_vec", payload_q_vec.out() ^ u(64, 0x55AA55AA55AA55AA))
    m.output("rsp_data1_vec", payload_q_vec.out())
