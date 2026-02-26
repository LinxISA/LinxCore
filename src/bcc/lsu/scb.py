from __future__ import annotations

"""SCB (Store Coalescing Buffer).

This is a parameterized, JIT-configurable module intended to sit between STQ and
DCache/CHI.

Strict design decisions are documented in linx-skills/linxcore/SKILL.md.
Highlights:
- Accept only non-flushable stores (gated before SCB).
- Coalesce by paddr cacheline.
- Partial write with 64B byte strobe.
- Do not merge into an outstanding entry.
- Same cacheline may queue multiple entries.
- Merge only when SIDs are consecutive (youngest_sid + 1).
- Drain in oldest SID order.
- Fence completion waits for CHI WriteResp; match by TxnID.

NOTE: CHI signaling here is modeled as a simple req/resp handshake with TxnID.
The actual CHI channelization can wrap this interface.
"""

from pycircuit import Circuit, Wire, module


def _is_pow2(x: int) -> bool:
    return x > 0 and (x & (x - 1)) == 0


def _clog2(x: int) -> int:
    return (int(x) - 1).bit_length()


def _merge_by_mask(m: Circuit, *, old_data: Wire, old_mask: Wire, new_data: Wire, new_mask: Wire) -> Wire:
    """Merge per-byte into a 64B (512-bit) line.

    For each byte i:
      if new_mask[i]==1 -> take new_data byte
      else -> keep old
    """

    c = m.const
    out = c(0, width=512)
    for i in range(64):
        lo = i * 8
        hi = lo + 8
        byte_old = old_data[lo:hi]
        byte_new = new_data[lo:hi]
        take = new_mask[i]
        out_byte = take.select(byte_new, byte_old)
        out = out | (out_byte._zext(width=512) << c(lo, width=9))
    return out


@module(name="JanusBccLsuScb")
def build_janus_bcc_lsu_scb(
    m: Circuit,
    *,
    scb_entries: int = 16,
    scb_outstanding: int = 8,
    paddr_w: int = 64,
    sid_w: int = 6,
) -> None:
    if not _is_pow2(scb_entries):
        raise ValueError(f"scb_entries must be power-of-2 for ring buffer: {scb_entries}")
    if not _is_pow2(scb_outstanding):
        raise ValueError(f"scb_outstanding must be power-of-2 for TxnID: {scb_outstanding}")

    idx_w = _clog2(scb_entries)
    tid_w = _clog2(scb_outstanding)

    clk = m.clock("clk")
    rst = m.reset("rst")
    c = m.const

    # --- Enqueue from STQ (already guaranteed non-flushable)
    enq_valid = m.input("enq_valid", width=1)
    enq_line = m.input("enq_line", width=paddr_w)  # cacheline base paddr
    enq_mask = m.input("enq_mask", width=64)  # byte mask within line
    enq_data = m.input("enq_data", width=512)  # 64B data
    enq_sid = m.input("enq_sid", width=sid_w)
    enq_ready = m.output("enq_ready", c(0, width=1))

    # --- CHI-like write request interface (simple abstraction)
    chi_req_valid = m.output("chi_req_valid", c(0, width=1))
    chi_req_ready = m.input("chi_req_ready", width=1)
    chi_req_txnid = m.output("chi_req_txnid", c(0, width=tid_w))
    chi_req_addr = m.output("chi_req_addr", c(0, width=paddr_w))
    chi_req_data = m.output("chi_req_data", c(0, width=512))
    chi_req_strb = m.output("chi_req_strb", c(0, width=64))

    chi_resp_valid = m.input("chi_resp_valid", width=1)
    chi_resp_txnid = m.input("chi_resp_txnid", width=tid_w)

    # --- Queue state
    head = m.out("head", clk=clk, rst=rst, width=idx_w, init=c(0, width=idx_w), en=c(1, width=1))
    count = m.out("count", clk=clk, rst=rst, width=idx_w + 1, init=c(0, width=idx_w + 1), en=c(1, width=1))
    # Number of entries from head that have been issued (<= count).
    issued_off = m.out("issued_off", clk=clk, rst=rst, width=idx_w + 1, init=c(0, width=idx_w + 1), en=c(1, width=1))

    free_tmask = m.out(
        "free_tmask",
        clk=clk,
        rst=rst,
        width=scb_outstanding,
        init=c((1 << scb_outstanding) - 1, width=scb_outstanding),
        en=c(1, width=1),
    )

    last_drained_sid = m.out("last_drained_sid", clk=clk, rst=rst, width=sid_w, init=c(0, width=sid_w), en=c(1, width=1))

    # --- Per-entry storage
    valid = []
    done = []
    line_q = []
    mask_q = []
    data_q = []
    oldest_sid_q = []
    youngest_sid_q = []
    txnid_q = []

    for i in range(int(scb_entries)):
        valid.append(m.out(f"valid{i}", clk=clk, rst=rst, width=1, init=c(0, width=1), en=c(1, width=1)))
        done.append(m.out(f"done{i}", clk=clk, rst=rst, width=1, init=c(0, width=1), en=c(1, width=1)))
        line_q.append(m.out(f"line{i}", clk=clk, rst=rst, width=paddr_w, init=c(0, width=paddr_w), en=c(1, width=1)))
        mask_q.append(m.out(f"mask{i}", clk=clk, rst=rst, width=64, init=c(0, width=64), en=c(1, width=1)))
        data_q.append(m.out(f"data{i}", clk=clk, rst=rst, width=512, init=c(0, width=512), en=c(1, width=1)))
        oldest_sid_q.append(m.out(f"oldest_sid{i}", clk=clk, rst=rst, width=sid_w, init=c(0, width=sid_w), en=c(1, width=1)))
        youngest_sid_q.append(m.out(f"youngest_sid{i}", clk=clk, rst=rst, width=sid_w, init=c(0, width=sid_w), en=c(1, width=1)))
        txnid_q.append(m.out(f"txnid{i}", clk=clk, rst=rst, width=tid_w, init=c(0, width=tid_w), en=c(1, width=1)))

    full = count.out().eq(c(scb_entries, width=idx_w + 1))

    def idx_at(off: Wire) -> Wire:
        # idx = head + off (mod 2^idx_w)
        return (head.out() + off._trunc(width=idx_w))._trunc(width=idx_w)

    tail_idx = idx_at(count.out())
    has_prev = count.out().ult(c(0, width=idx_w + 1)).select(c(0, width=1), count.out() != c(0, width=idx_w + 1))
    last_off = (count.out() - c(1, width=idx_w + 1))._trunc(width=idx_w + 1)
    last_idx = idx_at(last_off)

    # Merge condition: last entry exists, is not yet issued, same line, consecutive SID.
    last_unissued = issued_off.out().ult(count.out())  # there exists an unissued entry
    can_merge = (
        (count.out() != c(0, width=idx_w + 1))
        & last_unissued
        & (line_q[0].out().eq(line_q[0].out()))  # placeholder to keep type
    )

    # Compute can_merge by scanning for last_idx match.
    merge_hit = c(0, width=1)
    merge_old_mask = c(0, width=64)
    merge_old_data = c(0, width=512)
    merge_old_young = c(0, width=sid_w)
    merge_old_line = c(0, width=paddr_w)

    for i in range(int(scb_entries)):
        is_last = last_idx.eq(c(i, width=idx_w))
        merge_old_mask = is_last.select(mask_q[i].out(), merge_old_mask)
        merge_old_data = is_last.select(data_q[i].out(), merge_old_data)
        merge_old_young = is_last.select(youngest_sid_q[i].out(), merge_old_young)
        merge_old_line = is_last.select(line_q[i].out(), merge_old_line)
        merge_hit = is_last.select(valid[i].out(), merge_hit)

    merge_consecutive = enq_sid.eq(merge_old_young + c(1, width=sid_w))
    merge_same_line = enq_line.eq(merge_old_line)
    can_merge = (count.out() != c(0, width=idx_w + 1)) & last_unissued & merge_hit & merge_same_line & merge_consecutive

    enq_fire = enq_valid & enq_ready

    # Enqueue ready if can merge OR have space for new entry.
    enq_ready.set((~full) | can_merge)

    # Next-state head/count/issued_off
    head_next = head.out()
    count_next = count.out()
    issued_off_next = issued_off.out()
    free_tmask_next = free_tmask.out()
    last_drained_sid_next = last_drained_sid.out()

    # Dequeue: pop head if done.
    head_done = c(0, width=1)
    head_youngest_sid = c(0, width=sid_w)
    for i in range(int(scb_entries)):
        is_head = head.out().eq(c(i, width=idx_w))
        head_done = is_head.select(done[i].out(), head_done)
        head_youngest_sid = is_head.select(youngest_sid_q[i].out(), head_youngest_sid)

    deq_fire = (count.out() != c(0, width=idx_w + 1)) & head_done
    head_next = deq_fire.select((head.out() + c(1, width=idx_w))._trunc(width=idx_w), head_next)
    count_next = deq_fire.select(count.out() - c(1, width=idx_w + 1), count_next)
    issued_off_next = deq_fire.select(
        (issued_off.out() == c(0, width=idx_w + 1)).select(c(0, width=idx_w + 1), issued_off.out() - c(1, width=idx_w + 1)),
        issued_off_next,
    )
    last_drained_sid_next = deq_fire.select(head_youngest_sid, last_drained_sid_next)

    # Enqueue: merge or allocate new at tail.
    do_merge = enq_fire & can_merge
    do_alloc = enq_fire & (~can_merge) & (~full)

    # Count increases only on alloc.
    count_next = do_alloc.select(count_next + c(1, width=idx_w + 1), count_next)

    # Write entry contents
    for i in range(int(scb_entries)):
        is_tail = tail_idx.eq(c(i, width=idx_w))
        is_last = last_idx.eq(c(i, width=idx_w))

        # Allocate writes
        valid[i].set(c(1, width=1), when=do_alloc & is_tail)
        done[i].set(c(0, width=1), when=do_alloc & is_tail)
        line_q[i].set(enq_line, when=do_alloc & is_tail)
        mask_q[i].set(enq_mask, when=do_alloc & is_tail)
        data_q[i].set(enq_data, when=do_alloc & is_tail)
        oldest_sid_q[i].set(enq_sid, when=do_alloc & is_tail)
        youngest_sid_q[i].set(enq_sid, when=do_alloc & is_tail)
        txnid_q[i].set(c(0, width=tid_w), when=do_alloc & is_tail)

        # Merge writes (update mask/data/youngest)
        merged_mask = merge_old_mask | enq_mask
        merged_data = _merge_by_mask(m, old_data=merge_old_data, old_mask=merge_old_mask, new_data=enq_data, new_mask=enq_mask)
        mask_q[i].set(merged_mask, when=do_merge & is_last)
        data_q[i].set(merged_data, when=do_merge & is_last)
        youngest_sid_q[i].set(enq_sid, when=do_merge & is_last)

        # Clear head slot on dequeue.
        valid[i].set(c(0, width=1), when=deq_fire & head.out().eq(c(i, width=idx_w)))
        done[i].set(c(0, width=1), when=deq_fire & head.out().eq(c(i, width=idx_w)))

    # --- Issue to CHI (1 per cycle), preserving queue order.
    has_unissued = issued_off.out().ult(count.out())
    issue_idx = idx_at(issued_off.out())

    # Allocate lowest free txnid.
    t_ok = c(0, width=1)
    t_id = c(0, width=tid_w)
    for t in range(int(scb_outstanding)):
        bit = free_tmask.out()[t]
        take = bit & (~t_ok)
        t_ok = take.select(c(1, width=1), t_ok)
        t_id = take.select(c(t, width=tid_w), t_id)

    # Extract issue entry payload.
    issue_line = c(0, width=paddr_w)
    issue_mask = c(0, width=64)
    issue_data = c(0, width=512)
    for i in range(int(scb_entries)):
        is_i = issue_idx.eq(c(i, width=idx_w))
        issue_line = is_i.select(line_q[i].out(), issue_line)
        issue_mask = is_i.select(mask_q[i].out(), issue_mask)
        issue_data = is_i.select(data_q[i].out(), issue_data)

    issue_can_fire = has_unissued & t_ok
    chi_req_valid.set(issue_can_fire)
    chi_req_txnid.set(t_id)
    chi_req_addr.set(issue_line)
    chi_req_data.set(issue_data)
    chi_req_strb.set(issue_mask)

    issue_fire = issue_can_fire & chi_req_ready

    # Record txnid into issued entry and consume txnid.
    for i in range(int(scb_entries)):
        is_i = issue_idx.eq(c(i, width=idx_w))
        txnid_q[i].set(t_id, when=issue_fire & is_i)

    free_tmask_next = issue_fire.select(free_tmask_next & (~(c(1 << 0, width=scb_outstanding) << t_id._zext(width=scb_outstanding))), free_tmask_next)
    issued_off_next = issue_fire.select(issued_off_next + c(1, width=idx_w + 1), issued_off_next)

    # --- WriteResp handling: mark done and free txnid.
    resp_fire = chi_resp_valid

    # Free txnid bit.
    # NOTE: assumes txnid is unique among issued entries.
    free_bit = c(1, width=scb_outstanding) << chi_resp_txnid._zext(width=scb_outstanding)
    free_tmask_next = resp_fire.select(free_tmask_next | free_bit, free_tmask_next)

    # Mark matching entry done.
    for i in range(int(scb_entries)):
        match = valid[i].out() & txnid_q[i].out().eq(chi_resp_txnid)
        done[i].set(c(1, width=1), when=resp_fire & match)

    # Commit state.
    head.set(head_next)
    count.set(count_next)
    issued_off.set(issued_off_next)
    free_tmask.set(free_tmask_next)
    last_drained_sid.set(last_drained_sid_next)

    m.output("full", full)
    m.output("has_unissued", has_unissued)
    m.output("last_drained_sid", last_drained_sid.out())
