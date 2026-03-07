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

from pycircuit import Circuit, Wire, function, module


def _is_pow2(x: int) -> bool:
    return x > 0 and (x & (x - 1)) == 0


def _clog2(x: int) -> int:
    return (int(x) - 1).bit_length()


@function
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
        out_byte = byte_new if take else byte_old
        out = out | (out_byte._zext(width=512) << lo)
    return out


@module(name="JanusBccLsuScb")
def build_janus_bcc_lsu_scb(
    m: Circuit,
    *,
    scb_entries: int = 16,
    scb_outstanding: int = 16,
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

    # --- DCache-like write request interface (simple abstraction)
    dcache_req_ready = m.input("dcache_req_ready", width=1)

    dcache_resp_valid = m.input("dcache_resp_valid", width=1)
    dcache_resp_entry_id = m.input("dcache_resp_entry_id", width=tid_w)
    dcache_resp_ok = m.input("dcache_resp_ok", width=1)
    dcache_resp_err_code = m.input("dcache_resp_err_code", width=4)

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

    full = count.out() == c(scb_entries, width=idx_w + 1)

    # idx = head + off (mod 2^idx_w)
    tail_idx = (head.out() + count.out()._trunc(width=idx_w))._trunc(width=idx_w)
    last_off = (count.out() - c(1, width=idx_w + 1))._trunc(width=idx_w + 1)
    last_idx = (head.out() + last_off._trunc(width=idx_w))._trunc(width=idx_w)

    # Merge condition: last entry exists, is not yet issued, same line, consecutive SID.
    last_unissued = issued_off.out().ult(count.out())  # there exists an unissued entry

    # Compute can_merge by scanning for last_idx match.
    merge_hit = c(0, width=1)
    merge_old_mask = c(0, width=64)
    merge_old_data = c(0, width=512)
    merge_old_young = c(0, width=sid_w)
    merge_old_line = c(0, width=paddr_w)

    for i in range(int(scb_entries)):
        is_last = last_idx == c(i, width=idx_w)
        merge_old_mask = mask_q[i].out() if is_last else merge_old_mask
        merge_old_data = data_q[i].out() if is_last else merge_old_data
        merge_old_young = youngest_sid_q[i].out() if is_last else merge_old_young
        merge_old_line = line_q[i].out() if is_last else merge_old_line
        merge_hit = valid[i].out() if is_last else merge_hit

    merge_consecutive = enq_sid == (merge_old_young + c(1, width=sid_w))
    merge_same_line = enq_line == merge_old_line
    can_merge = (count.out() != c(0, width=idx_w + 1)) & last_unissued & merge_hit & merge_same_line & merge_consecutive

    # Enqueue ready if can merge OR have space for new entry.
    enq_ready_w = (~full) | can_merge
    enq_fire = enq_valid & enq_ready_w

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
        is_head = head.out() == c(i, width=idx_w)
        head_done = done[i].out() if is_head else head_done
        head_youngest_sid = youngest_sid_q[i].out() if is_head else head_youngest_sid

    deq_fire = (count.out() != c(0, width=idx_w + 1)) & head_done
    head_next = (head.out() + c(1, width=idx_w))._trunc(width=idx_w) if deq_fire else head_next
    count_next = (count.out() - c(1, width=idx_w + 1)) if deq_fire else count_next
    issued_off_dec = c(0, width=idx_w + 1) if (issued_off.out() == c(0, width=idx_w + 1)) else (issued_off.out() - c(1, width=idx_w + 1))
    issued_off_next = issued_off_dec if deq_fire else issued_off_next
    last_drained_sid_next = head_youngest_sid if deq_fire else last_drained_sid_next

    # Enqueue: merge or allocate new at tail.
    do_merge = enq_fire & can_merge
    do_alloc = enq_fire & (~can_merge) & (~full)

    # Count increases only on alloc.
    count_next = (count_next + c(1, width=idx_w + 1)) if do_alloc else count_next

    # Write entry contents
    for i in range(int(scb_entries)):
        is_tail = tail_idx == c(i, width=idx_w)
        is_last = last_idx == c(i, width=idx_w)

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
        valid[i].set(c(0, width=1), when=deq_fire & (head.out() == c(i, width=idx_w)))
        done[i].set(c(0, width=1), when=deq_fire & (head.out() == c(i, width=idx_w)))

    # --- Issue to CHI (1 per cycle), preserving queue order.
    has_unissued = issued_off.out().ult(count.out())
    issue_idx = (head.out() + issued_off.out()._trunc(width=idx_w))._trunc(width=idx_w)

    # Allocate lowest free txnid.
    t_ok = c(0, width=1)
    t_id = c(0, width=tid_w)
    for t in range(int(scb_outstanding)):
        bit = free_tmask.out()[t]
        take = bit & (~t_ok)
        t_ok = c(1, width=1) if take else t_ok
        t_id = c(t, width=tid_w) if take else t_id

    # Extract issue entry payload.
    issue_line = c(0, width=paddr_w)
    issue_mask = c(0, width=64)
    issue_data = c(0, width=512)
    for i in range(int(scb_entries)):
        is_i = issue_idx == c(i, width=idx_w)
        issue_line = line_q[i].out() if is_i else issue_line
        issue_mask = mask_q[i].out() if is_i else issue_mask
        issue_data = data_q[i].out() if is_i else issue_data

    issue_can_fire = has_unissued & t_ok
    dcache_req_valid_w = issue_can_fire
    dcache_req_entry_id_w = t_id
    dcache_req_line_w = issue_line
    dcache_req_mask_w = issue_mask
    dcache_req_data_w = issue_data

    issue_fire = issue_can_fire & dcache_req_ready

    # Record txnid into issued entry and consume txnid.
    for i in range(int(scb_entries)):
        is_i = issue_idx == c(i, width=idx_w)
        txnid_q[i].set(t_id, when=issue_fire & is_i)

    issued_bit = c(0, width=scb_outstanding)
    for t in range(int(scb_outstanding)):
        issued_bit = c(1 << t, width=scb_outstanding) if (t_id == c(t, width=tid_w)) else issued_bit
    free_tmask_next = (free_tmask_next & (~issued_bit)) if issue_fire else free_tmask_next
    issued_off_next = (issued_off_next + c(1, width=idx_w + 1)) if issue_fire else issued_off_next

    # --- WriteResp handling: mark done and free txnid.
    dcache_resp_ready_w = c(1, width=1)
    resp_fire = dcache_resp_valid & dcache_resp_ready_w

    # Free txnid bit.
    # NOTE: assumes txnid is unique among issued entries.
    free_bit = c(0, width=scb_outstanding)
    for t in range(int(scb_outstanding)):
        free_bit = c(1 << t, width=scb_outstanding) if (dcache_resp_entry_id == c(t, width=tid_w)) else free_bit
    free_tmask_next = (free_tmask_next | free_bit) if resp_fire else free_tmask_next

    # Mark matching entry done.
    for i in range(int(scb_entries)):
        match = valid[i].out() & (txnid_q[i].out() == dcache_resp_entry_id)
        done[i].set(c(1, width=1), when=resp_fire & match)

    # Commit state.
    head.set(head_next)
    count.set(count_next)
    issued_off.set(issued_off_next)
    free_tmask.set(free_tmask_next)
    last_drained_sid.set(last_drained_sid_next)

    m.output("enq_ready", enq_ready_w)

    m.output("dcache_req_valid", dcache_req_valid_w)
    m.output("dcache_req_entry_id", dcache_req_entry_id_w)
    m.output("dcache_req_line", dcache_req_line_w)
    m.output("dcache_req_mask", dcache_req_mask_w)
    m.output("dcache_req_data", dcache_req_data_w)
    m.output("dcache_resp_ready", dcache_resp_ready_w)

    m.output("scb_count", count.out())
    m.output("scb_inflight", issued_off.out())
    m.output("scb_resp_ok", dcache_resp_ok)
    m.output("scb_resp_err_code", dcache_resp_err_code)

    m.output("full", full)
    m.output("has_unissued", has_unissued)
    m.output("last_drained_sid", last_drained_sid.out())
