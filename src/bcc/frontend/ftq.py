from __future__ import annotations

from pycircuit import Circuit, module

from bcc.backend.helpers import mux_by_uindex


@module(name="LinxCoreFTQ")
def build_ftq_lite(m: Circuit, *, depth: int = 16) -> None:
    clk = m.clock("clk")
    rst = m.reset("rst")

    enq_valid = m.input("enq_valid", width=1)
    enq_pc = m.input("enq_pc", width=64)
    enq_npc = m.input("enq_npc", width=64)
    enq_checkpoint = m.input("enq_checkpoint", width=6)

    deq_ready = m.input("deq_ready", width=1)
    flush_valid = m.input("flush_valid", width=1)

    c = m.const

    if depth <= 0 or (depth & (depth - 1)) != 0:
        raise ValueError("FTQ depth must be a positive power-of-two")

    w = (depth - 1).bit_length()

    with m.scope("ftq"):
        head = m.out("head", clk=clk, rst=rst, width=w, init=c(0, width=w), en=c(1, width=1))
        tail = m.out("tail", clk=clk, rst=rst, width=w, init=c(0, width=w), en=c(1, width=1))
        count = m.out("count", clk=clk, rst=rst, width=w + 1, init=c(0, width=w + 1), en=c(1, width=1))
        q_valid = []
        q_pc = []
        q_npc = []
        q_checkpoint = []
        for i in range(depth):
            q_valid.append(m.out(f"v{i}", clk=clk, rst=rst, width=1, init=c(0, width=1), en=c(1, width=1)))
            q_pc.append(m.out(f"pc{i}", clk=clk, rst=rst, width=64, init=c(0, width=64), en=c(1, width=1)))
            q_npc.append(m.out(f"npc{i}", clk=clk, rst=rst, width=64, init=c(0, width=64), en=c(1, width=1)))
            q_checkpoint.append(m.out(f"ck{i}", clk=clk, rst=rst, width=6, init=c(0, width=6), en=c(1, width=1)))

    enq_ready = count.out().ult(c(depth, width=w + 1))
    deq_valid = mux_by_uindex(m, idx=head.out(), items=q_valid, default=c(0, width=1))

    deq_pc = mux_by_uindex(m, idx=head.out(), items=q_pc, default=c(0, width=64))
    deq_npc = mux_by_uindex(m, idx=head.out(), items=q_npc, default=c(0, width=64))
    deq_checkpoint = mux_by_uindex(m, idx=head.out(), items=q_checkpoint, default=c(0, width=6))

    enq_fire = enq_valid & enq_ready
    deq_fire = deq_valid & deq_ready

    for i in range(depth):
        idx = c(i, width=w)
        do_enq = enq_fire & tail.out().eq(idx)
        do_deq = deq_fire & head.out().eq(idx)

        v_next = q_valid[i].out()
        v_next = do_deq.select(c(0, width=1), v_next)
        v_next = do_enq.select(c(1, width=1), v_next)
        v_next = flush_valid.select(c(0, width=1), v_next)
        q_valid[i].set(v_next)

        q_pc[i].set(enq_pc, when=do_enq)
        q_npc[i].set(enq_npc, when=do_enq)
        q_checkpoint[i].set(enq_checkpoint, when=do_enq)

    head_next = deq_fire.select(head.out() + c(1, width=w), head.out())
    tail_next = enq_fire.select(tail.out() + c(1, width=w), tail.out())

    count_next = count.out()
    count_next = (enq_fire & (~deq_fire)).select(count.out() + c(1, width=w + 1), count_next)
    count_next = ((~enq_fire) & deq_fire).select(count.out() - c(1, width=w + 1), count_next)

    head.set(flush_valid.select(c(0, width=w), head_next))
    tail.set(flush_valid.select(c(0, width=w), tail_next))
    count.set(flush_valid.select(c(0, width=w + 1), count_next))

    m.output("enq_ready", enq_ready)
    m.output("deq_valid", deq_valid)
    m.output("deq_pc", deq_pc)
    m.output("deq_npc", deq_npc)
    m.output("deq_checkpoint", deq_checkpoint)

    m.output("head_dbg", head.out())
    m.output("tail_dbg", tail.out())
    m.output("count_dbg", count.out())
    m.output("checkpoint_dbg", deq_checkpoint)


build_ftq_lite.__pycircuit_name__ = "LinxCoreFTQ"
