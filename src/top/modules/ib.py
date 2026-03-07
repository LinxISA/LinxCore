from __future__ import annotations

from pycircuit import Circuit, module


@module(name="LinxCoreIb")
def build_ib(m: Circuit, *, depth: int = 32) -> None:
    if depth <= 0:
        raise ValueError("IB depth must be positive")
    if depth & (depth - 1) != 0:
        # Keep pointer math cheap (maskable) and avoid odd corner cases.
        raise ValueError("IB depth must be a power of two")

    clk = m.clock("clk")
    rst = m.reset("rst")
    c = m.const

    push_valid = m.input("push_valid", width=1)
    push_pc = m.input("push_pc", width=64)
    push_window = m.input("push_window", width=64)
    push_checkpoint = m.input("push_checkpoint", width=6)
    push_pkt_uid = m.input("push_pkt_uid", width=64)

    pop_ready = m.input("pop_ready", width=1)
    flush = m.input("flush", width=1)

    idx_w = (depth - 1).bit_length()
    entry_w = 64 + 64 + 6 + 64  # pc, window, checkpoint, pkt_uid

    head = m.out("head", clk=clk, rst=rst, width=idx_w, init=c(0, width=idx_w), en=c(1, width=1))
    tail = m.out("tail", clk=clk, rst=rst, width=idx_w, init=c(0, width=idx_w), en=c(1, width=1))
    count = m.out("count", clk=clk, rst=rst, width=idx_w + 1, init=c(0, width=idx_w + 1), en=c(1, width=1))

    # Packed entry keeps reg-op count small (resource lints count regs by op, not bits).
    entries = [
        m.out(f"entry{i}", clk=clk, rst=rst, width=entry_w, init=c(0, width=entry_w), en=c(1, width=1))
        for i in range(depth)
    ]

    # During flush, the IB drops all buffered entries. Do not acknowledge
    # pushes/pops in the same cycle, otherwise the sender may drop a packet
    # that the IB will clear (and the backend may observe stale head data).
    push_ready = (~flush) & count.out().ult(c(depth, width=idx_w + 1))
    pop_valid = (~flush) & count.out().ugt(c(0, width=idx_w + 1))

    push_fire = push_valid & push_ready
    pop_fire = pop_valid & pop_ready

    # Pack fields.
    packed = push_pc._zext(width=entry_w)
    packed = packed | push_window._zext(width=entry_w).shl(amount=64)
    packed = packed | push_checkpoint._zext(width=entry_w).shl(amount=128)
    packed = packed | push_pkt_uid._zext(width=entry_w).shl(amount=134)

    # Write selected tail entry (and optionally clear on flush).
    for i in range(depth):
        idx = c(i, width=idx_w)
        do_write = push_fire & tail.out().__eq__(idx)
        do_clear = flush
        entries[i].set(c(0, width=entry_w), when=do_clear)
        entries[i].set(packed, when=(~do_clear) & do_write)

    # Read selected head entry.
    head_entry = c(0, width=entry_w)
    for i in range(depth):
        idx = c(i, width=idx_w)
        head_entry = head.out().__eq__(idx)._select_internal(entries[i].out(), head_entry)

    out_pc = head_entry[0:64]
    out_window = head_entry[64:128]
    out_checkpoint = head_entry[128:134]
    out_pkt_uid = head_entry[134:198]

    head_next = head.out()
    tail_next = tail.out()
    count_next = count.out()

    head_next = pop_fire._select_internal(head.out() + c(1, width=idx_w), head_next)
    tail_next = push_fire._select_internal(tail.out() + c(1, width=idx_w), tail_next)

    count_next = (push_fire & (~pop_fire))._select_internal(count_next + c(1, width=idx_w + 1), count_next)
    count_next = ((~push_fire) & pop_fire)._select_internal(count_next - c(1, width=idx_w + 1), count_next)

    head_next = flush._select_internal(c(0, width=idx_w), head_next)
    tail_next = flush._select_internal(c(0, width=idx_w), tail_next)
    count_next = flush._select_internal(c(0, width=idx_w + 1), count_next)

    head.set(head_next)
    tail.set(tail_next)
    count.set(count_next)

    m.output("push_ready", push_ready)
    m.output("pop_valid", pop_valid)
    m.output("count_dbg", count.out())
    m.output("pop_pc", out_pc)
    m.output("pop_window", out_window)
    m.output("pop_checkpoint", out_checkpoint)
    m.output("pop_pkt_uid", out_pkt_uid)
    m.output("pop_fire", pop_fire)
