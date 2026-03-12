from __future__ import annotations

from pycircuit import Circuit, module

from bcc.backend.helpers import mux_by_uindex


@module(name="LinxCoreTopIb")
def build_ib(m: Circuit, *, depth: int = 8) -> None:
    clk = m.clock("clk")
    rst = m.reset("rst")

    push_valid = m.input("push_valid", width=1)
    push_pc = m.input("push_pc", width=64)
    push_window = m.input("push_window", width=64)
    push_checkpoint = m.input("push_checkpoint", width=6)
    push_pkt_uid = m.input("push_pkt_uid", width=64)

    pop_ready = m.input("pop_ready", width=1)
    flush = m.input("flush", width=1)

    c = m.const

    if depth <= 0 or (depth & (depth - 1)) != 0:
        raise ValueError("IB depth must be a positive power-of-two")

    idx_w = (depth - 1).bit_length()

    with m.scope("ib"):
        head = m.out("head", clk=clk, rst=rst, width=idx_w, init=c(0, width=idx_w), en=c(1, width=1))
        tail = m.out("tail", clk=clk, rst=rst, width=idx_w, init=c(0, width=idx_w), en=c(1, width=1))
        count = m.out("count", clk=clk, rst=rst, width=idx_w + 1, init=c(0, width=idx_w + 1), en=c(1, width=1))

        q_valid = []
        q_pc = []
        q_window = []
        q_checkpoint = []
        q_pkt_uid = []
        for i in range(depth):
            q_valid.append(m.out(f"v{i}", clk=clk, rst=rst, width=1, init=c(0, width=1), en=c(1, width=1)))
            q_pc.append(m.out(f"pc{i}", clk=clk, rst=rst, width=64, init=c(0, width=64), en=c(1, width=1)))
            q_window.append(m.out(f"win{i}", clk=clk, rst=rst, width=64, init=c(0, width=64), en=c(1, width=1)))
            q_checkpoint.append(m.out(f"ckpt{i}", clk=clk, rst=rst, width=6, init=c(0, width=6), en=c(1, width=1)))
            q_pkt_uid.append(m.out(f"uid{i}", clk=clk, rst=rst, width=64, init=c(0, width=64), en=c(1, width=1)))

    push_ready = count.out().ult(c(depth, width=idx_w + 1))
    pop_valid = mux_by_uindex(m, idx=head.out(), items=q_valid, default=c(0, width=1))
    pop_pc = mux_by_uindex(m, idx=head.out(), items=q_pc, default=c(0, width=64))
    pop_window = mux_by_uindex(m, idx=head.out(), items=q_window, default=c(0, width=64))
    pop_checkpoint = mux_by_uindex(m, idx=head.out(), items=q_checkpoint, default=c(0, width=6))
    pop_pkt_uid = mux_by_uindex(m, idx=head.out(), items=q_pkt_uid, default=c(0, width=64))

    push_fire = push_valid & push_ready
    pop_fire = pop_valid & pop_ready

    for i in range(depth):
        idx = c(i, width=idx_w)
        do_push = push_fire & tail.out().__eq__(idx)
        do_pop = pop_fire & head.out().__eq__(idx)

        valid_next = q_valid[i].out()
        valid_next = do_pop._select_internal(c(0, width=1), valid_next)
        valid_next = do_push._select_internal(c(1, width=1), valid_next)
        valid_next = flush._select_internal(c(0, width=1), valid_next)
        q_valid[i].set(valid_next)

        q_pc[i].set(push_pc, when=do_push)
        q_window[i].set(push_window, when=do_push)
        q_checkpoint[i].set(push_checkpoint, when=do_push)
        q_pkt_uid[i].set(push_pkt_uid, when=do_push)

    head_next = pop_fire._select_internal(head.out() + c(1, width=idx_w), head.out())
    tail_next = push_fire._select_internal(tail.out() + c(1, width=idx_w), tail.out())

    count_next = count.out()
    count_next = (push_fire & (~pop_fire))._select_internal(count.out() + c(1, width=idx_w + 1), count_next)
    count_next = ((~push_fire) & pop_fire)._select_internal(count.out() - c(1, width=idx_w + 1), count_next)

    head.set(flush._select_internal(c(0, width=idx_w), head_next))
    tail.set(flush._select_internal(c(0, width=idx_w), tail_next))
    count.set(flush._select_internal(c(0, width=idx_w + 1), count_next))

    m.output("push_ready", push_ready)
    m.output("pop_valid", pop_valid)
    m.output("pop_pc", pop_pc)
    m.output("pop_window", pop_window)
    m.output("pop_checkpoint", pop_checkpoint)
    m.output("pop_pkt_uid", pop_pkt_uid)
    m.output("pop_fire", pop_fire)
    m.output("count_dbg", count.out())


build_ib.__pycircuit_name__ = "LinxCoreTopIb"
