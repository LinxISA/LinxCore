from __future__ import annotations

from pycircuit import Circuit, module


def _build_janus_bcc_bctrl_brob_core(m: Circuit, *, entries: int) -> None:
    """Block Reorder Buffer (BROB).

    Strict contract (see $linx-core skill doc):
    - default entries=128
    - BID encoding: slot_id=BID[6:0], uniq=BID[63:7]
    - cmd_tag == BID[7:0]
    - flush: keep bid<=flush_bid, kill bid>flush_bid (roll back tail)
    """

    if entries != 128:
        raise ValueError("strict LinxCore BROB requires entries=128")

    clk = m.clock("clk")
    rst = m.reset("rst")
    c = m.const

    # --- BID allocation (BSTART dispatch) ---
    alloc_fire_brob = m.input("alloc_fire_brob", width=1)

    # --- Engine command issue/response tracking (per active block BID) ---
    issue_fire_brob = m.input("issue_fire_brob", width=1)
    issue_tag_brob = m.input("issue_tag_brob", width=8)
    issue_bid_brob = m.input("issue_bid_brob", width=64)
    issue_src_rob_brob = m.input("issue_src_rob_brob", width=6)

    retire_fire_brob = m.input("retire_fire_brob", width=1)
    retire_bid_brob = m.input("retire_bid_brob", width=64)
    query_bid_brob = m.input("query_bid_brob", width=64)

    # Flush younger blocks: keep bid<=flush_bid, kill bid>flush_bid.
    # `flush_valid_brob` freezes the queue for the redirect/flush cycle(s).
    # `flush_fire_brob` performs the kill/update when asserted.
    flush_valid_brob = m.input("flush_valid_brob", width=1)
    flush_fire_brob = m.input("flush_fire_brob", width=1)
    flush_bid_brob = m.input("flush_bid_brob", width=64)

    rsp_valid_brob = m.input("rsp_valid_brob", width=1)
    rsp_tag_brob = m.input("rsp_tag_brob", width=8)
    rsp_status_brob = m.input("rsp_status_brob", width=4)
    rsp_data0_brob = m.input("rsp_data0_brob", width=64)
    rsp_data1_brob = m.input("rsp_data1_brob", width=64)
    rsp_trap_valid_brob = m.input("rsp_trap_valid_brob", width=1)
    rsp_trap_cause_brob = m.input("rsp_trap_cause_brob", width=32)

    # --- Queue state ---
    # tail_bid is the next BID to allocate (BID=0 reserved for "no block").
    tail_bid = m.out("tail_bid_brob", clk=clk, rst=rst, width=64, init=c(1, width=64), en=c(1, width=1))
    count = m.out("count_brob", clk=clk, rst=rst, width=8, init=c(0, width=8), en=c(1, width=1))

    has_cmd = m.out("allocated_brob", clk=clk, rst=rst, width=entries, init=c(0, width=entries), en=c(1, width=1))
    ready = m.out("ready_brob", clk=clk, rst=rst, width=entries, init=c(0, width=entries), en=c(1, width=1))
    retired = m.out("retired_brob", clk=clk, rst=rst, width=entries, init=c(0, width=entries), en=c(1, width=1))
    exception = m.out("exception_brob", clk=clk, rst=rst, width=entries, init=c(0, width=entries), en=c(1, width=1))

    # Debug: src ROB per slot (packed).
    src_rob_w = 6
    src_rob_packed_w = entries * src_rob_w
    src_rob_packed = m.out(
        "src_rob_packed_brob",
        clk=clk,
        rst=rst,
        width=src_rob_packed_w,
        init=c(0, width=src_rob_packed_w),
        en=c(1, width=1),
    )

    head_bid = tail_bid.out() - count.out()._zext(width=64)
    head_slot = head_bid[0:7]

    # Bring-up safety fallback:
    # keep allocation non-blocking and recycle oldest slot on saturation.
    alloc_ready = c(1, width=1)
    alloc_bid = tail_bid.out()
    alloc_slot = alloc_bid[0:7]

    def slot_live_and_bid(slot7, *, bid_in):
        # Because entries=128, (slot7 - head_slot) wraps modulo 128 naturally.
        dist7 = slot7 - head_slot
        dist8 = dist7._zext(width=8)
        live = dist8.ult(count.out())
        bid_calc = head_bid + dist8._zext(width=64)
        match = live & bid_calc.__eq__(bid_in)
        return live, bid_calc, match

    def slot_live_and_calc_bid(slot7):
        dist7 = slot7 - head_slot
        dist8 = dist7._zext(width=8)
        live = dist8.ult(count.out())
        bid_calc = head_bid + dist8._zext(width=64)
        return live, bid_calc

    def bit_at_slot(bits, slot7):
        v = c(0, width=1)
        for i in range(entries):
            v = slot7.__eq__(c(i, width=7))._select_internal(bits[i], v)
        return v

    def src_rob_at_slot(slot7):
        v = c(0, width=src_rob_w)
        for i in range(entries):
            lo = i * src_rob_w
            hi = lo + src_rob_w
            v = slot7.__eq__(c(i, width=7))._select_internal(src_rob_packed.out()[lo:hi], v)
        return v

    def onehot_for_slot(slot7):
        oh = c(0, width=entries)
        for i in range(entries):
            oh = slot7.__eq__(c(i, width=7))._select_internal(c(1 << i, width=entries), oh)
        return oh

    # --- Query (active block state) ---
    query_slot = query_bid_brob[0:7]
    _, _, query_match = slot_live_and_bid(query_slot, bid_in=query_bid_brob)
    query_has_cmd = query_match._select_internal(bit_at_slot(has_cmd.out(), query_slot), c(0, width=1))
    query_ready = query_match._select_internal(bit_at_slot(ready.out(), query_slot), c(0, width=1))
    query_retired = query_match._select_internal(bit_at_slot(retired.out(), query_slot), c(0, width=1))
    query_exception = query_match._select_internal(bit_at_slot(exception.out(), query_slot), c(0, width=1))

    brob_state = c(0, width=4)
    brob_state = query_has_cmd._select_internal(brob_state | c(0x1, width=4), brob_state)
    brob_state = query_ready._select_internal(brob_state | c(0x2, width=4), brob_state)
    brob_state = query_retired._select_internal(brob_state | c(0x4, width=4), brob_state)
    brob_state = query_exception._select_internal(brob_state | c(0x8, width=4), brob_state)

    # --- Issue (command enqueue) ---
    issue_slot = issue_bid_brob[0:7]
    _, issue_bid_calc, issue_bid_match = slot_live_and_bid(issue_slot, bid_in=issue_bid_brob)
    issue_tag_ok = issue_tag_brob.__eq__(issue_bid_calc._trunc(width=8))
    issue_fire_ok = issue_fire_brob & issue_bid_match & issue_tag_ok & (~flush_valid_brob)

    # --- Response (PE -> BROB) ---
    rsp_slot = rsp_tag_brob[0:7]
    rsp_live, rsp_bid_calc = slot_live_and_calc_bid(rsp_slot)
    rsp_tag_ok = rsp_tag_brob.__eq__(rsp_bid_calc._trunc(width=8))
    rsp_has_cmd = bit_at_slot(has_cmd.out(), rsp_slot)
    rsp_retired = bit_at_slot(retired.out(), rsp_slot)
    rsp_fire_ok = rsp_valid_brob & rsp_live & rsp_tag_ok & rsp_has_cmd & (~rsp_retired) & (~flush_valid_brob)
    rsp_exception = rsp_trap_valid_brob | (~rsp_status_brob.__eq__(c(0, width=4)))
    rsp_src_rob = src_rob_at_slot(rsp_slot)

    # --- Retire (BSTOP commit) ---
    retire_slot = retire_bid_brob[0:7]
    # Compatibility path for bring-up:
    # some older producer paths can emit retire_bid=0 on valid BSTOP retirement.
    # Treat BID=0 as "retire current head" to avoid BROB saturation deadlock.
    retire_bid_wildcard = retire_bid_brob.__eq__(c(0, width=64))
    retire_bid_match = retire_bid_brob.__eq__(head_bid) | retire_bid_wildcard
    retire_ok = retire_fire_brob & retire_bid_match & count.out().ugt(c(0, width=8)) & (~flush_valid_brob)

    # --- Flush (BID rollback) ---
    # Keep bid <= flush_bid, kill bid > flush_bid.
    #
    # Clamp the kept range to the live window [head_bid, tail_bid):
    # this avoids count underflow/wrap if flush_bid is older than head.
    flush_tail_bid = flush_bid_brob + c(1, width=64)
    flush_active = flush_fire_brob & flush_valid_brob
    keep_count_flush = count.out()
    keep_count_flush = flush_tail_bid.ule(head_bid)._select_internal(c(0, width=8), keep_count_flush)
    flush_mid = flush_tail_bid.ugt(head_bid) & flush_tail_bid.ult(tail_bid.out())
    keep_mid = (flush_tail_bid - head_bid)._trunc(width=8)
    keep_count_flush = flush_mid._select_internal(keep_mid, keep_count_flush)
    count_after_flush = flush_active._select_internal(keep_count_flush, count.out())
    tail_bid_after_flush = flush_active._select_internal(head_bid + keep_count_flush._zext(width=64), tail_bid.out())

    # --- Next-state ---
    tail_bid_n = tail_bid_after_flush
    count_n = count_after_flush

    has_cmd_n = has_cmd.out()
    ready_n = ready.out()
    retired_n = retired.out()
    exception_n = exception.out()
    src_rob_packed_n = src_rob_packed.out()

    # Allocation consumes the current alloc_bid.
    alloc_fire_ok = alloc_fire_brob & (~flush_valid_brob)
    alloc_oh = onehot_for_slot(alloc_slot)
    has_cmd_n = alloc_fire_ok._select_internal(has_cmd_n & (~alloc_oh), has_cmd_n)
    ready_n = alloc_fire_ok._select_internal(ready_n & (~alloc_oh), ready_n)
    retired_n = alloc_fire_ok._select_internal(retired_n & (~alloc_oh), retired_n)
    exception_n = alloc_fire_ok._select_internal(exception_n & (~alloc_oh), exception_n)
    alloc_can_grow = count_n.ult(c(entries, width=8))
    count_n = (alloc_fire_ok & alloc_can_grow)._select_internal(count_n + c(1, width=8), count_n)
    tail_bid_n = alloc_fire_ok._select_internal(tail_bid_n + c(1, width=64), tail_bid_n)

    # Retire clears command state for the retiring slot and advances head via count--.
    retire_oh = onehot_for_slot(retire_slot)
    has_cmd_n = retire_ok._select_internal(has_cmd_n & (~retire_oh), has_cmd_n)
    ready_n = retire_ok._select_internal(ready_n & (~retire_oh), ready_n)
    exception_n = retire_ok._select_internal(exception_n & (~retire_oh), exception_n)
    retired_n = retire_ok._select_internal(retired_n | retire_oh, retired_n)
    count_n = retire_ok._select_internal(count_n - c(1, width=8), count_n)

    # Command issue: mark active block "has_cmd", clear done flags, capture src_rob.
    issue_oh = onehot_for_slot(issue_slot)
    has_cmd_n = issue_fire_ok._select_internal(has_cmd_n | issue_oh, has_cmd_n)
    ready_n = issue_fire_ok._select_internal(ready_n & (~issue_oh), ready_n)
    exception_n = issue_fire_ok._select_internal(exception_n & (~issue_oh), exception_n)
    retired_n = issue_fire_ok._select_internal(retired_n & (~issue_oh), retired_n)

    # Update src_rob packed (6b slice per slot) when issue fires.
    full_mask = (1 << src_rob_packed_w) - 1
    for i in range(entries):
        hit = issue_fire_ok & issue_slot.__eq__(c(i, width=7))
        shift = i * src_rob_w
        clear_mask = full_mask ^ (((1 << src_rob_w) - 1) << shift)
        cleared = src_rob_packed_n & c(clear_mask, width=src_rob_packed_w)
        inserted = issue_src_rob_brob._zext(width=src_rob_packed_w).shl(amount=shift)
        src_rob_packed_n = hit._select_internal(cleared | inserted, src_rob_packed_n)

    # Response: mark ready/exception for the slot (has_cmd remains set until retire).
    rsp_oh = onehot_for_slot(rsp_slot)
    ready_n = rsp_fire_ok._select_internal(ready_n | rsp_oh, ready_n)
    exception_n = (rsp_fire_ok & rsp_exception)._select_internal(exception_n | rsp_oh, exception_n)

    tail_bid.set(tail_bid_n)
    count.set(count_n)
    has_cmd.set(has_cmd_n)
    ready.set(ready_n)
    retired.set(retired_n)
    exception.set(exception_n)
    src_rob_packed.set(src_rob_packed_n)

    pending = has_cmd_n & (~ready_n) & (~retired_n)

    m.output("brob_alloc_ready_brob", alloc_ready)
    m.output("brob_alloc_bid_brob", alloc_bid)
    m.output("brob_head_bid_brob", head_bid)
    m.output("brob_count_brob", count.out())

    m.output("brob_pending_brob", pending)
    m.output("brob_rsp_fire_brob", rsp_fire_ok)
    m.output("brob_query_state_brob", brob_state)
    m.output("brob_query_allocated_brob", query_has_cmd)
    m.output("brob_query_ready_brob", query_ready)
    m.output("brob_query_exception_brob", query_exception)
    m.output("brob_query_retired_brob", query_retired)

    # Response export (debug/DFX).
    m.output("brob_to_rob_stage_rsp_valid_brob", rsp_fire_ok)
    m.output("brob_to_rob_stage_rsp_tag_brob", rsp_tag_brob)
    m.output("brob_to_rob_stage_rsp_status_brob", rsp_status_brob)
    m.output("brob_to_rob_stage_rsp_data0_brob", rsp_data0_brob)
    m.output("brob_to_rob_stage_rsp_data1_brob", rsp_data1_brob)
    m.output("brob_to_rob_stage_rsp_trap_valid_brob", rsp_trap_valid_brob)
    m.output("brob_to_rob_stage_rsp_trap_cause_brob", rsp_trap_cause_brob)
    m.output("brob_to_rob_stage_rsp_src_rob_brob", rsp_src_rob)
    m.output("brob_to_rob_stage_rsp_bid_brob", rsp_bid_calc)
    m.output("brob_to_rob_stage_brob_state_brob", brob_state)
    m.output("brob_to_rob_stage_brob_ready_brob", query_ready)
    m.output("brob_to_rob_stage_brob_exception_brob", query_exception)
    m.output("brob_to_rob_stage_brob_retired_brob", query_retired)


@module(name="JanusBccBctrlBrob")
def build_janus_bcc_bctrl_brob(m: Circuit, *, entries: int = 128) -> None:
    # Keep the @module body minimal to avoid frontend AST restrictions.
    _build_janus_bcc_bctrl_brob_core(m, entries=entries)
