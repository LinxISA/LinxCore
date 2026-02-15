from __future__ import annotations

from pycircuit import Circuit, module

from bcc.backend.helpers import mux_by_uindex


@module(name="LinxCoreIBuffer")
def build_ibuffer(m: Circuit, *, depth: int = 8) -> None:
    clk = m.clock("clk")
    rst = m.reset("rst")

    push_valid = m.input("push_valid", width=1)
    push_pc = m.input("push_pc", width=64)
    push_window = m.input("push_window", width=64)

    pop_ready = m.input("pop_ready", width=1)
    flush_valid = m.input("flush_valid", width=1)

    c = m.const

    if depth <= 0 or (depth & (depth - 1)) != 0:
        raise ValueError("IBuffer depth must be a positive power-of-two")

    w = (depth - 1).bit_length()

    with m.scope("ibuf"):
        head = m.out("head", clk=clk, rst=rst, width=w, init=c(0, width=w), en=c(1, width=1))
        tail = m.out("tail", clk=clk, rst=rst, width=w, init=c(0, width=w), en=c(1, width=1))
        count = m.out("count", clk=clk, rst=rst, width=w + 1, init=c(0, width=w + 1), en=c(1, width=1))
        q_valid = []
        q_pc = []
        q_window = []
        for i in range(depth):
            q_valid.append(m.out(f"v{i}", clk=clk, rst=rst, width=1, init=c(0, width=1), en=c(1, width=1)))
            q_pc.append(m.out(f"pc{i}", clk=clk, rst=rst, width=64, init=c(0, width=64), en=c(1, width=1)))
            q_window.append(m.out(f"win{i}", clk=clk, rst=rst, width=64, init=c(0, width=64), en=c(1, width=1)))

    push_ready = count.out().ult(c(depth, width=w + 1))
    out_valid = mux_by_uindex(m, idx=head.out(), items=q_valid, default=c(0, width=1))
    out_pc = mux_by_uindex(m, idx=head.out(), items=q_pc, default=c(0, width=64))
    out_window = mux_by_uindex(m, idx=head.out(), items=q_window, default=c(0, width=64))

    push_fire = push_valid & push_ready
    pop_fire = out_valid & pop_ready

    for i in range(depth):
        idx = c(i, width=w)
        do_push = push_fire & tail.out().eq(idx)
        do_pop = pop_fire & head.out().eq(idx)

        v_next = q_valid[i].out()
        v_next = do_pop.select(c(0, width=1), v_next)
        v_next = do_push.select(c(1, width=1), v_next)
        v_next = flush_valid.select(c(0, width=1), v_next)
        q_valid[i].set(v_next)

        q_pc[i].set(push_pc, when=do_push)
        q_window[i].set(push_window, when=do_push)

    head_next = pop_fire.select(head.out() + c(1, width=w), head.out())
    tail_next = push_fire.select(tail.out() + c(1, width=w), tail.out())

    count_next = count.out()
    count_next = (push_fire & (~pop_fire)).select(count.out() + c(1, width=w + 1), count_next)
    count_next = ((~push_fire) & pop_fire).select(count.out() - c(1, width=w + 1), count_next)

    head.set(flush_valid.select(c(0, width=w), head_next))
    tail.set(flush_valid.select(c(0, width=w), tail_next))
    count.set(flush_valid.select(c(0, width=w + 1), count_next))

    m.output("push_ready", push_ready)
    m.output("out_valid", out_valid)
    m.output("out_pc", out_pc)
    m.output("out_window", out_window)
    m.output("pop_fire", pop_fire)
    m.output("head_dbg", head.out())
    m.output("tail_dbg", tail.out())
    m.output("count_dbg", count.out())


build_ibuffer.__pycircuit_name__ = "LinxCoreIBuffer"
