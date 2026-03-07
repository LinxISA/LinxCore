from __future__ import annotations

"""Functional-first D$ stub for SCB bring-up.

- Accepts SCB write requests and returns completion responses after a fixed latency.
- Responses are in-order (FIFO order).

This is not a cache. It exists to close the store-drain loop while the real
D$ / CHI plumbing is developed.
"""

from pycircuit import Circuit, module


def _is_pow2(x: int) -> bool:
    return x > 0 and (x & (x - 1)) == 0


def _clog2(x: int) -> int:
    return (int(x) - 1).bit_length()


@module(name="JanusBccLsuDCacheStub")
def build_janus_bcc_lsu_dcache_stub(
    m: Circuit,
    *,
    depth: int = 8,
    latency: int = 2,
    entry_id_w: int = 4,
    paddr_w: int = 64,
    err_w: int = 4,
) -> None:
    if not _is_pow2(depth):
        raise ValueError("depth must be power-of-2")

    clk = m.clock("clk")
    rst = m.reset("rst")
    c = m.const

    req_valid = m.input("dcache_req_valid", width=1)
    req_entry_id = m.input("dcache_req_entry_id", width=entry_id_w)
    req_line = m.input("dcache_req_line", width=paddr_w)
    req_mask = m.input("dcache_req_mask", width=64)
    req_data = m.input("dcache_req_data", width=512)

    resp_ready = m.input("dcache_resp_ready", width=1)

    idx_w = _clog2(depth)
    head = m.out("head", clk=clk, rst=rst, width=idx_w, init=c(0, width=idx_w), en=c(1, width=1))
    tail = m.out("tail", clk=clk, rst=rst, width=idx_w, init=c(0, width=idx_w), en=c(1, width=1))
    count = m.out("count", clk=clk, rst=rst, width=idx_w + 1, init=c(0, width=idx_w + 1), en=c(1, width=1))

    due_w = 16
    cyc = m.out("cyc", clk=clk, rst=rst, width=due_w, init=c(0, width=due_w), en=c(1, width=1))
    cyc.set(cyc.out() + c(1, width=due_w))

    q_v = []
    q_eid = []
    q_due = []
    # keep payload for debug
    q_line = []
    q_mask = []
    q_data = []

    for i in range(depth):
        q_v.append(m.out(f"v{i}", clk=clk, rst=rst, width=1, init=c(0, width=1), en=c(1, width=1)))
        q_eid.append(m.out(f"eid{i}", clk=clk, rst=rst, width=entry_id_w, init=c(0, width=entry_id_w), en=c(1, width=1)))
        q_due.append(m.out(f"due{i}", clk=clk, rst=rst, width=due_w, init=c(0, width=due_w), en=c(1, width=1)))
        q_line.append(m.out(f"line{i}", clk=clk, rst=rst, width=paddr_w, init=c(0, width=paddr_w), en=c(1, width=1)))
        q_mask.append(m.out(f"mask{i}", clk=clk, rst=rst, width=64, init=c(0, width=64), en=c(1, width=1)))
        q_data.append(m.out(f"data{i}", clk=clk, rst=rst, width=512, init=c(0, width=512), en=c(1, width=1)))

    full = count.out() == c(depth, width=idx_w + 1)
    req_ready_w = ~full
    enq_fire = req_valid & req_ready_w

    for i in range(depth):
        is_tail = tail.out() == c(i, width=idx_w)
        q_v[i].set(c(1, width=1), when=enq_fire & is_tail)
        q_eid[i].set(req_entry_id, when=enq_fire & is_tail)
        q_due[i].set((cyc.out() + c(latency, width=due_w))._trunc(width=due_w), when=enq_fire & is_tail)
        q_line[i].set(req_line, when=enq_fire & is_tail)
        q_mask[i].set(req_mask, when=enq_fire & is_tail)
        q_data[i].set(req_data, when=enq_fire & is_tail)

    tail_next = (tail.out() + c(1, width=idx_w))._trunc(width=idx_w) if enq_fire else tail.out()
    count_next = (count.out() + c(1, width=idx_w + 1)) if enq_fire else count.out()

    head_v = c(0, width=1)
    head_eid = c(0, width=entry_id_w)
    head_due = c(0, width=due_w)
    for i in range(depth):
        is_head = head.out() == c(i, width=idx_w)
        head_v = q_v[i].out() if is_head else head_v
        head_eid = q_eid[i].out() if is_head else head_eid
        head_due = q_due[i].out() if is_head else head_due

    can_resp = head_v & head_due.ule(cyc.out())
    resp_valid_w = can_resp
    resp_entry_id_w = head_eid

    resp_fire = resp_valid_w & resp_ready
    for i in range(depth):
        is_head = head.out() == c(i, width=idx_w)
        q_v[i].set(c(0, width=1), when=resp_fire & is_head)

    head_next = (head.out() + c(1, width=idx_w))._trunc(width=idx_w) if resp_fire else head.out()
    count_next = (count_next - c(1, width=idx_w + 1)) if resp_fire else count_next

    head.set(head_next)
    tail.set(tail_next)
    count.set(count_next)

    m.output("dcache_req_ready", req_ready_w)
    m.output("dcache_resp_valid", resp_valid_w)
    m.output("dcache_resp_entry_id", resp_entry_id_w)
    m.output("dcache_resp_ok", c(1, width=1))
    m.output("dcache_resp_err_code", c(0, width=err_w))

    m.output("dcache_stub_count", count.out())
    m.output("dcache_stub_full", full)
