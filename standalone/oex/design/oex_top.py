from __future__ import annotations

from pycircuit import Circuit, Vec, ct, module

from .oex_consts import IQ_AGU, IQ_ALU, IQ_BRU, IQ_CMD, IQ_FSU, IQ_STD, IQ_TPL
from .oex_config import derive_cfg
from .oex_profiles import get_profile, profile_supports
from .stage_specs import trace_row_spec
from .util import mux_by_uindex, pack_bits_lsb, priority_pick_lsb


@module(name="standalone_oex_top")
def build(m: Circuit, *, profile_name: str = "oex_target") -> None:
    clk = m.clock("clk")
    rst = m.reset("rst")
    c = m.const

    cfg = get_profile(m, profile_name=profile_name)
    _ = profile_supports(m, cfg)
    dcfg = derive_cfg(m, cfg)

    # Standalone oracle-row interface.
    trace_s = trace_row_spec(m, aregs_w=int(dcfg.aregs_w))
    dispatch_w = int(dcfg.dispatch_w)
    commit_w = int(dcfg.commit_w)

    in_rows = [m.inputs(trace_s, prefix=f"in{i}_") for i in range(dispatch_w)]

    # -----------------------------------------------------------------------------
    # Core parameters / derived widths.
    # -----------------------------------------------------------------------------
    rob_depth = int(cfg.rob_depth)
    ptag_entries = int(cfg.ptag_rf_entries)
    aregs = int(cfg.aregs)

    rob_w = max(1, int(dcfg.rob_w))
    ptag_w = max(1, int(dcfg.ptag_w))
    aregs_w = max(1, int(dcfg.aregs_w))
    issue_w = int(dcfg.issue_w)
    issueq_depth = int(cfg.issueq_depth)
    issueq_w = max(1, int(dcfg.issueq_w))

    # Lane counts (execution resources).
    alu_lanes = int(cfg.alu_lanes)
    bru_lanes = int(cfg.bru_lanes)
    agu_lanes = int(cfg.agu_lanes)
    std_lanes = int(cfg.std_lanes)
    cmd_lanes = int(cfg.cmd_lanes)
    fsu_lanes = int(cfg.fsu_lanes)
    tpl_lanes = int(cfg.tpl_lanes)

    assert ptag_entries > aregs, "ptag_rf_entries must exceed aregs in superscalar model"

    # Trace row packing for ROB payload storage (single wide bus per entry).
    trace_fields = [
        ("seq", 64),
        ("pc", 64),
        ("insn", 64),
        ("len", 8),
        ("src0_valid", 1),
        ("src0_reg", aregs_w),
        ("src0_data", 64),
        ("src1_valid", 1),
        ("src1_reg", aregs_w),
        ("src1_data", 64),
        ("dst_valid", 1),
        ("dst_reg", aregs_w),
        ("dst_data", 64),
        ("mem_valid", 1),
        ("mem_is_store", 1),
        ("mem_addr", 64),
        ("mem_wdata", 64),
        ("mem_rdata", 64),
        ("mem_size", 8),
        ("trap_valid", 1),
        ("trap_cause", 32),
        ("traparg0", 64),
        ("next_pc", 64),
    ]
    trace_row_w = 0
    for _n, _w in trace_fields:
        trace_row_w = trace_row_w + int(_w)

    trace_lsb: dict[str, int] = {}
    off = 0
    for name, width in reversed(trace_fields):
        trace_lsb[name] = off
        off = off + int(width)

    # -----------------------------------------------------------------------------
    # Global state (ROB pointers/counters, rename + tag tracking).
    # -----------------------------------------------------------------------------
    cycles = m.out("cycles_r", clk=clk, rst=rst, width=64, init=c(0, width=64), en=c(1, width=1))
    commits = m.out("commits_r", clk=clk, rst=rst, width=64, init=c(0, width=64), en=c(1, width=1))

    head = m.out("rob_head_r", clk=clk, rst=rst, width=rob_w, init=c(0, width=rob_w), en=c(1, width=1))
    tail = m.out("rob_tail_r", clk=clk, rst=rst, width=rob_w, init=c(0, width=rob_w), en=c(1, width=1))
    cnt_w = max(1, ct.clog2(rob_depth + 1))
    rob_count = m.out("rob_count_r", clk=clk, rst=rst, width=cnt_w, init=c(0, width=cnt_w), en=c(1, width=1))

    # Free tag mask: bit=1 indicates the physical tag is available for allocation.
    free_init = 0
    for t in range(ptag_entries):
        if t >= aregs and t != 0:
            free_init = free_init | (1 << t)
    free_mask = m.out(
        "free_mask_r",
        clk=clk,
        rst=rst,
        width=ptag_entries,
        init=c(free_init, width=ptag_entries),
        en=c(1, width=1),
    )

    # Ready tag mask: bit=1 indicates the physical tag value is ready.
    # Initialize architectural tags as ready; keep others ready as well (they'll be cleared on alloc).
    ready_init = 0
    for t in range(ptag_entries):
        if t < aregs or t == 0:
            ready_init = ready_init | (1 << t)
    ready_mask = m.out(
        "ready_mask_r",
        clk=clk,
        rst=rst,
        width=ptag_entries,
        init=c(ready_init, width=ptag_entries),
        en=c(1, width=1),
    )

    # Rename map (architectural reg -> physical tag).
    amap = []
    for a in range(aregs):
        amap.append(
            m.out(
                f"amap{a}",
                clk=clk,
                rst=rst,
                width=ptag_w,
                init=c(a & ((1 << ptag_w) - 1), width=ptag_w),
                en=c(1, width=1),
            )
        )

    # -----------------------------------------------------------------------------
    # ROB storage (payload in sync_mem below; metadata in regs).
    # -----------------------------------------------------------------------------
    rob_valid = []
    rob_done = []
    rob_dv = []
    rob_oldt = []

    for i in range(rob_depth):
        rob_valid.append(m.out(f"rob_v{i}", clk=clk, rst=rst, width=1, init=c(0, width=1), en=c(1, width=1)))
        rob_done.append(m.out(f"rob_done{i}", clk=clk, rst=rst, width=1, init=c(0, width=1), en=c(1, width=1)))
        rob_dv.append(m.out(f"rob_dv{i}", clk=clk, rst=rst, width=1, init=c(0, width=1), en=c(1, width=1)))
        rob_oldt.append(m.out(f"rob_oldt{i}", clk=clk, rst=rst, width=ptag_w, init=c(0, width=ptag_w), en=c(1, width=1)))

    # -----------------------------------------------------------------------------
    # Issue queues (per-class), with in-queue operand readiness bits.
    # -----------------------------------------------------------------------------
    iq_groups = [
        ("alu", IQ_ALU, alu_lanes),
        ("bru", IQ_BRU, bru_lanes),
        ("agu", IQ_AGU, agu_lanes),
        ("std", IQ_STD, std_lanes),
        ("cmd", IQ_CMD, cmd_lanes),
        ("fsu", IQ_FSU, fsu_lanes),
        ("tpl", IQ_TPL, tpl_lanes),
    ]

    iqs = []
    for g_name, g_code, g_lanes in iq_groups:
        q_v = []
        q_rob = []
        q_s0v = []
        q_s0t = []
        q_s0r = []
        q_s1v = []
        q_s1t = []
        q_s1r = []
        q_dv = []
        q_dt = []
        for i in range(issueq_depth):
            q_v.append(m.out(f"iq_{g_name}_v{i}", clk=clk, rst=rst, width=1, init=c(0, width=1), en=c(1, width=1)))
            q_rob.append(
                m.out(
                    f"iq_{g_name}_rob{i}",
                    clk=clk,
                    rst=rst,
                    width=rob_w,
                    init=c(0, width=rob_w),
                    en=c(1, width=1),
                )
            )
            q_s0v.append(m.out(f"iq_{g_name}_s0v{i}", clk=clk, rst=rst, width=1, init=c(0, width=1), en=c(1, width=1)))
            q_s0t.append(m.out(f"iq_{g_name}_s0t{i}", clk=clk, rst=rst, width=ptag_w, init=c(0, width=ptag_w), en=c(1, width=1)))
            q_s0r.append(m.out(f"iq_{g_name}_s0r{i}", clk=clk, rst=rst, width=1, init=c(1, width=1), en=c(1, width=1)))
            q_s1v.append(m.out(f"iq_{g_name}_s1v{i}", clk=clk, rst=rst, width=1, init=c(0, width=1), en=c(1, width=1)))
            q_s1t.append(m.out(f"iq_{g_name}_s1t{i}", clk=clk, rst=rst, width=ptag_w, init=c(0, width=ptag_w), en=c(1, width=1)))
            q_s1r.append(m.out(f"iq_{g_name}_s1r{i}", clk=clk, rst=rst, width=1, init=c(1, width=1), en=c(1, width=1)))
            q_dv.append(m.out(f"iq_{g_name}_dv{i}", clk=clk, rst=rst, width=1, init=c(0, width=1), en=c(1, width=1)))
            q_dt.append(m.out(f"iq_{g_name}_dt{i}", clk=clk, rst=rst, width=ptag_w, init=c(0, width=ptag_w), en=c(1, width=1)))

        iqs.append(
            {
                "name": g_name,
                "code": int(g_code),
                "lanes": int(g_lanes),
                "v": q_v,
                "rob": q_rob,
                "s0v": q_s0v,
                "s0t": q_s0t,
                "s0r": q_s0r,
                "s1v": q_s1v,
                "s1t": q_s1t,
                "s1r": q_s1r,
                "dv": q_dv,
                "dt": q_dt,
            }
        )

    # -----------------------------------------------------------------------------
    # Commit selection (in order, width=commit_w).
    # -----------------------------------------------------------------------------
    head_cur = head.out()
    tail_cur = tail.out()
    count_cur = rob_count.out()

    # Dynamic read helpers for head+offset addressing.
    rob_valid_w = [v.out() for v in rob_valid]
    rob_done_w = [d.out() for d in rob_done]
    rob_oldt_w = [t.out() for t in rob_oldt]
    rob_dv_w = [dv.out() for dv in rob_dv]

    commit_fire = []
    commit_idx = []
    can = c(1, width=1)
    for j in range(commit_w):
        idx = head_cur + c(j, width=rob_w)
        vj = mux_by_uindex(m, idx=idx, items=rob_valid_w, default=c(0, width=1))
        dj = mux_by_uindex(m, idx=idx, items=rob_done_w, default=c(0, width=1))
        fire = can & vj & dj
        commit_fire.append(fire)
        commit_idx.append(idx)
        can = can & fire

    commit_n = c(0, width=cnt_w)
    for j in range(commit_w):
        commit_n = commit_fire[j]._select_internal(commit_n + c(1, width=cnt_w), commit_n)

    # -----------------------------------------------------------------------------
    # Dispatch (rename + allocate tags + allocate ROB slots), width=dispatch_w.
    # -----------------------------------------------------------------------------
    # Query current tag readiness for dispatch operands (small number of lookups).
    ready_bits_cur = [ready_mask.out()[t] for t in range(ptag_entries)]
    free_bits_tmp = [free_mask.out()[t] for t in range(ptag_entries)]

    # Issue-queue free masks (per-class), tracked across dispatch slots.
    iq_free_bits_tmp = []
    for q in iqs:
        q_v = q["v"]
        iq_free_bits_tmp.append([~q_v[i].out() for i in range(issueq_depth)])

    # Compute available ROB slots after commit in the same cycle.
    count_after_commit = count_cur - commit_n
    free_rob_slots = c(rob_depth, width=cnt_w) - count_after_commit

    # Dispatch fires are a valid-prefix with structural backpressure.
    in_ready = []
    dispatch_fire = []
    disp_dst_need = []
    disp_alloc_ok = []
    disp_alloc_tag = []
    disp_old_tag = []
    disp_s0_ptag = []
    disp_s1_ptag = []
    disp_s0_rdy = []
    disp_s1_rdy = []
    disp_iq = []
    disp_iq_enq_idx = []
    disp_payload = []

    # Temporary rename map for within-cycle rename chaining.
    amap_tmp = [r.out() for r in amap]

    # Running "slot budget" for ROB space.
    rob_slots_left = free_rob_slots

    stop = c(0, width=1)
    for slot in range(dispatch_w):
        row = in_rows[slot]
        v_in = row["valid"].read()

        # Compute src tags from current temporary map.
        s0_ptag = mux_by_uindex(m, idx=row["src0_reg"].read(), items=amap_tmp, default=c(0, width=ptag_w))
        s1_ptag = mux_by_uindex(m, idx=row["src1_reg"].read(), items=amap_tmp, default=c(0, width=ptag_w))
        s0_ptag = row["src0_valid"].read()._select_internal(s0_ptag, c(0, width=ptag_w))
        s1_ptag = row["src1_valid"].read()._select_internal(s1_ptag, c(0, width=ptag_w))

        s0_rdy = (~row["src0_valid"].read()) | mux_by_uindex(m, idx=s0_ptag, items=ready_bits_cur, default=c(0, width=1))
        s1_rdy = (~row["src1_valid"].read()) | mux_by_uindex(m, idx=s1_ptag, items=ready_bits_cur, default=c(0, width=1))

        # Destination allocation (ignore x0).
        dst_need = row["dst_valid"].read() & (~row["dst_reg"].read().__eq__(c(0, width=aregs_w)))

        # Find a free ptag if needed.
        found, alloc_tag = priority_pick_lsb(m, bits_lsb=free_bits_tmp, idx_w=ptag_w)
        alloc_ok = (~dst_need) | found

        # Issue queue classification heuristic.
        insn = row["insn"].read()
        ln = row["len"].read()
        raw_lo2 = insn._trunc(width=2)
        raw_lo7 = insn._trunc(width=7)

        is_branch = raw_lo2.__eq__(c(2, width=2))
        is_tpl = ln.__eq__(c(4, width=8)) & raw_lo7.__eq__(c(0x41, width=7))
        is_cmd = raw_lo7.__eq__(c(0x6B, width=7))
        is_fsu = raw_lo7.__eq__(c(0x53, width=7))
        is_mem = row["mem_valid"].read()
        is_store = is_mem & row["mem_is_store"].read()

        iq = c(IQ_ALU, width=3)
        if bru_lanes > 0:
            iq = is_branch._select_internal(c(IQ_BRU, width=3), iq)
        if agu_lanes > 0:
            iq = is_mem._select_internal(c(IQ_AGU, width=3), iq)
        if std_lanes > 0:
            iq = is_store._select_internal(c(IQ_STD, width=3), iq)
        if tpl_lanes > 0:
            iq = is_tpl._select_internal(c(IQ_TPL, width=3), iq)
        if cmd_lanes > 0:
            iq = is_cmd._select_internal(c(IQ_CMD, width=3), iq)
        if fsu_lanes > 0:
            iq = is_fsu._select_internal(c(IQ_FSU, width=3), iq)

        # Issue-queue allocation for this uop (based on current per-class free masks).
        iq_found = c(0, width=1)
        iq_enq_idx = c(0, width=issueq_w)
        for qi, q in enumerate(iqs):
            found_q, idx_q = priority_pick_lsb(m, bits_lsb=iq_free_bits_tmp[qi], idx_w=issueq_w)
            hit = iq.__eq__(c(int(q["code"]), width=3))
            iq_found = hit._select_internal(found_q, iq_found)
            iq_enq_idx = hit._select_internal(idx_q, iq_enq_idx)

        has_rob = ~rob_slots_left.__eq__(c(0, width=cnt_w))
        ready = (~stop) & v_in & has_rob & alloc_ok & iq_found
        fire = ready

        in_ready.append(ready)

        # Stop the prefix on first hole or structural stall.
        stop = stop | (~v_in) | (v_in & (~ready))

        # Bookkeeping: ROB slots consumed.
        rob_slots_left = fire._select_internal(rob_slots_left - c(1, width=cnt_w), rob_slots_left)

        # Clear allocated tag bit in temporary free mask when used.
        do_alloc = fire & dst_need
        new_free_bits = []
        for t in range(ptag_entries):
            is_t = alloc_tag.__eq__(c(t, width=ptag_w))
            new_free_bits.append((do_alloc & is_t)._select_internal(c(0, width=1), free_bits_tmp[t]))
        free_bits_tmp = new_free_bits

        # Rename map update for within-cycle chaining (dst sees new mapping).
        old_tag = mux_by_uindex(m, idx=row["dst_reg"].read(), items=amap_tmp, default=c(0, width=ptag_w))
        new_amap = []
        for a in range(aregs):
            hit = do_alloc & row["dst_reg"].read().__eq__(c(a, width=aregs_w))
            new_amap.append(hit._select_internal(alloc_tag, amap_tmp[a]))
        amap_tmp = new_amap

        # Consume issue-queue slot only when the uop is accepted.
        for qgi, q in enumerate(iqs):
            do_iq_alloc = fire & iq.__eq__(c(int(q["code"]), width=3))
            new_bits = []
            for ei in range(issueq_depth):
                is_i = iq_enq_idx.__eq__(c(ei, width=issueq_w))
                new_bits.append(
                    (do_iq_alloc & is_i)._select_internal(c(0, width=1), iq_free_bits_tmp[qgi][ei])
                )
            iq_free_bits_tmp[qgi] = new_bits

        dispatch_fire.append(fire)
        disp_dst_need.append(dst_need)
        disp_alloc_ok.append(alloc_ok)
        disp_alloc_tag.append(alloc_tag)
        disp_old_tag.append(old_tag)
        disp_s0_ptag.append(s0_ptag)
        disp_s1_ptag.append(s1_ptag)
        disp_s0_rdy.append(s0_rdy)
        disp_s1_rdy.append(s1_rdy)
        disp_iq.append(iq)
        disp_iq_enq_idx.append(iq_enq_idx)
        parts = []
        for name, _w in trace_fields:
            parts.append(row[name].read())
        disp_payload.append(Vec(tuple(parts)).pack())

    dispatch_n = c(0, width=cnt_w)
    for slot in range(dispatch_w):
        dispatch_n = dispatch_fire[slot]._select_internal(dispatch_n + c(1, width=cnt_w), dispatch_n)

    # -----------------------------------------------------------------------------
    # ROB payload storage (oracle row) using banked sync memories.
    #
    # This avoids a huge per-entry wide payload reg array with per-cycle muxing,
    # which is extremely expensive in emitted C++ simulation for long traces.
    # -----------------------------------------------------------------------------
    max_rw = max(int(dispatch_w), int(commit_w))
    banks = 1 << ct.clog2(max_rw)
    bank_shift = int(ct.clog2(banks))
    bank_sel_w = max(1, bank_shift)

    assert rob_depth >= banks, "rob_depth must be >= payload bank count"
    assert (rob_depth % banks) == 0, "rob_depth must be divisible by payload bank count"

    bank_depth = int(rob_depth // banks)
    bank_addr_w = max(1, int(ct.clog2(bank_depth)))

    # pyc.sync_mem currently supports data widths up to 64 bits, so store the
    # packed trace row as multiple 64-bit words (zext padded).
    payload_words = int((trace_row_w + 63) // 64)
    payload_ext_w = int(payload_words * 64)
    disp_payload_words = []
    for slot in range(dispatch_w):
        ext = disp_payload[slot]._zext(width=payload_ext_w)
        words = []
        for wi in range(payload_words):
            words.append(ext.slice(lsb=int(wi * 64), width=64))
        disp_payload_words.append(words)

    word_strb = c(0xFF, width=8)

    # Delay commit valids + head bank selection to align with registered read data.
    commit_fire_q = []
    for j in range(commit_w):
        q = m.out(f"commit_fire_q{j}", clk=clk, rst=rst, width=1, init=c(0, width=1), en=c(1, width=1))
        q.set(commit_fire[j])
        commit_fire_q.append(q)

    head_bank_q = m.out(
        "head_bank_q",
        clk=clk,
        rst=rst,
        width=bank_sel_w,
        init=c(0, width=bank_sel_w),
        en=c(1, width=1),
    )
    if bank_shift == 0:
        head_bank_q.set(c(0, width=bank_sel_w))
    else:
        head_bank_q.set(head_cur._trunc(width=bank_sel_w))

    # Commit-head read selectors per slot (head+offset).
    rd_sel = []
    rd_addr = []
    for j in range(commit_w):
        idx = commit_idx[j]
        if bank_shift == 0:
            rd_sel.append(c(0, width=bank_sel_w))
            rd_addr.append(idx._trunc(width=bank_addr_w))
        else:
            rd_sel.append(idx._trunc(width=bank_sel_w))
            rd_addr.append((idx >> bank_shift)._trunc(width=bank_addr_w))

    rob_pl_rdata = []
    for b in range(banks):
        # Read address: pick the slot mapping to this bank (consecutive head window).
        raddr_b = c(0, width=bank_addr_w)
        for j in range(commit_w):
            hit = rd_sel[j].__eq__(c(b, width=bank_sel_w))
            raddr_b = hit._select_internal(rd_addr[j], raddr_b)

        # Write address/data: pick the dispatch slot mapping to this bank (consecutive tail window).
        wvalid_b = c(0, width=1)
        waddr_b = c(0, width=bank_addr_w)
        whits = []
        for slot in range(dispatch_w):
            widx = tail_cur + c(slot, width=rob_w)
            if bank_shift == 0:
                wsel = c(0, width=bank_sel_w)
                waddr = widx._trunc(width=bank_addr_w)
            else:
                wsel = widx._trunc(width=bank_sel_w)
                waddr = (widx >> bank_shift)._trunc(width=bank_addr_w)
            whit = dispatch_fire[slot] & wsel.__eq__(c(b, width=bank_sel_w))
            whits.append(whit)
            wvalid_b = wvalid_b | whit
            waddr_b = whit._select_internal(waddr, waddr_b)

        bank_words = []
        for wi in range(payload_words):
            wdata_wi = c(0, width=64)
            for slot in range(dispatch_w):
                wdata_wi = whits[slot]._select_internal(disp_payload_words[slot][wi], wdata_wi)
            bank_words.append(
                m.sync_mem(
                    clk,
                    rst,
                    ren=c(1, width=1),
                    raddr=raddr_b,
                    wvalid=wvalid_b,
                    waddr=waddr_b,
                    wdata=wdata_wi,
                    wstrb=word_strb,
                    depth=bank_depth,
                    name=f"rob_payload_bank{b}_w{wi}",
                )
            )

        bank_bus = Vec(tuple(reversed(bank_words))).pack()
        rob_pl_rdata.append(bank_bus._trunc(width=trace_row_w))

    # -----------------------------------------------------------------------------
    # Issue selection (issue queues).
    # -----------------------------------------------------------------------------
    # Pick up to issue_w uops/cycle across all queues.
    #
    # Fairness:
    # - Rotate the starting queue each cycle (simple RR pointer).
    # - Within a cycle, schedule in "rounds" across lanes:
    #   - Round0 tries to issue at most one uop from each queue (lane0) in RR order.
    #   - Round1 issues lane1 (second-ready entry) only if lane0 was issued, etc.
    #
    # This avoids starving the STD queue (stores) behind AGU traffic (loads), while
    # still allowing multiple issues from a queue when other queues are empty.
    rem_w = max(1, ct.clog2(max(1, issue_w + 1)))
    rem = c(issue_w, width=rem_w)

    # Simple round-robin start pointer over the issue queues.
    issue_rr = m.out(
        "issue_rr_r",
        clk=clk,
        rst=rst,
        width=3,
        init=c(0, width=3),
        en=c(1, width=1),
    )
    issue_rr_cur = issue_rr.out()

    # -------------------------------------------------------------------------
    # Candidate extraction: for each queue, pick up to `lanes` ready entries.
    #
    # We pick the 0th/1st/... ready entry in queue index order by repeated
    # priority-pick + masking. (Not age-perfect, but deterministic and cheap.)
    # -------------------------------------------------------------------------
    lanes_by_q = [alu_lanes, bru_lanes, agu_lanes, std_lanes, cmd_lanes, fsu_lanes, tpl_lanes]
    cand_v_all = []
    cand_qidx_all = []
    cand_rob_all = []
    cand_dv_all = []
    cand_dt_all = []

    for qgi, q in enumerate(iqs):
        lanes = int(lanes_by_q[qgi])
        ready_bits = []
        for i in range(issueq_depth):
            ready_bits.append(q["v"][i].out() & q["s0r"][i].out() & q["s1r"][i].out())
        mask_bits = ready_bits

        cand_v = []
        cand_qidx = []
        cand_rob = []
        cand_dv = []
        cand_dt = []
        for _ln in range(lanes):
            found, idx = priority_pick_lsb(m, bits_lsb=mask_bits, idx_w=issueq_w)
            cand_v.append(found)
            cand_qidx.append(idx)
            cand_rob.append(mux_by_uindex(m, idx=idx, items=q["rob"], default=c(0, width=rob_w)))
            cand_dv.append(mux_by_uindex(m, idx=idx, items=q["dv"], default=c(0, width=1)))
            cand_dt.append(mux_by_uindex(m, idx=idx, items=q["dt"], default=c(0, width=ptag_w)))

            new_mask = []
            for i in range(issueq_depth):
                hit = found & idx.__eq__(c(i, width=issueq_w))
                new_mask.append(hit._select_internal(c(0, width=1), mask_bits[i]))
            mask_bits = new_mask

        cand_v_all.append(cand_v)
        cand_qidx_all.append(cand_qidx)
        cand_rob_all.append(cand_rob)
        cand_dv_all.append(cand_dv)
        cand_dt_all.append(cand_dt)

    cand_alu_v = cand_v_all[0]
    cand_alu_qidx = cand_qidx_all[0]
    cand_alu_rob = cand_rob_all[0]
    cand_alu_dv = cand_dv_all[0]
    cand_alu_dt = cand_dt_all[0]

    cand_bru_v = cand_v_all[1]
    cand_bru_qidx = cand_qidx_all[1]
    cand_bru_rob = cand_rob_all[1]
    cand_bru_dv = cand_dv_all[1]
    cand_bru_dt = cand_dt_all[1]

    cand_agu_v = cand_v_all[2]
    cand_agu_qidx = cand_qidx_all[2]
    cand_agu_rob = cand_rob_all[2]
    cand_agu_dv = cand_dv_all[2]
    cand_agu_dt = cand_dt_all[2]

    cand_std_v = cand_v_all[3]
    cand_std_qidx = cand_qidx_all[3]
    cand_std_rob = cand_rob_all[3]
    cand_std_dv = cand_dv_all[3]
    cand_std_dt = cand_dt_all[3]

    cand_cmd_v = cand_v_all[4]
    cand_cmd_qidx = cand_qidx_all[4]
    cand_cmd_rob = cand_rob_all[4]
    cand_cmd_dv = cand_dv_all[4]
    cand_cmd_dt = cand_dt_all[4]

    cand_fsu_v = cand_v_all[5]
    cand_fsu_qidx = cand_qidx_all[5]
    cand_fsu_rob = cand_rob_all[5]
    cand_fsu_dv = cand_dv_all[5]
    cand_fsu_dt = cand_dt_all[5]

    cand_tpl_v = cand_v_all[6]
    cand_tpl_qidx = cand_qidx_all[6]
    cand_tpl_rob = cand_rob_all[6]
    cand_tpl_dv = cand_dv_all[6]
    cand_tpl_dt = cand_dt_all[6]

    # -------------------------------------------------------------------------
    # Schedule issues in RR order across queues, in rounds across lanes.
    # -------------------------------------------------------------------------
    fire_alu = []
    for _ in range(alu_lanes):
        fire_alu.append(c(0, width=1))
    fire_bru = []
    for _ in range(bru_lanes):
        fire_bru.append(c(0, width=1))
    fire_agu = []
    for _ in range(agu_lanes):
        fire_agu.append(c(0, width=1))
    fire_std = []
    for _ in range(std_lanes):
        fire_std.append(c(0, width=1))
    fire_cmd = []
    for _ in range(cmd_lanes):
        fire_cmd.append(c(0, width=1))
    fire_fsu = []
    for _ in range(fsu_lanes):
        fire_fsu.append(c(0, width=1))
    fire_tpl = []
    for _ in range(tpl_lanes):
        fire_tpl.append(c(0, width=1))

    max_lanes = max(int(alu_lanes), int(bru_lanes), int(agu_lanes), int(std_lanes), int(cmd_lanes), int(fsu_lanes), int(tpl_lanes))

    for ln in range(max_lanes):
        scan_idx = issue_rr_cur
        for _pos in range(len(iqs)):
            rem_nz = ~rem.__eq__(c(0, width=rem_w))
            grant_any = c(0, width=1)

            if alu_lanes > ln:
                req = cand_alu_v[ln]
                if ln > 0:
                    req = req & fire_alu[ln - 1]
                g = rem_nz & req & scan_idx.__eq__(c(0, width=3))
                fire_alu[ln] = fire_alu[ln] | g
                grant_any = grant_any | g

            if bru_lanes > ln:
                req = cand_bru_v[ln]
                if ln > 0:
                    req = req & fire_bru[ln - 1]
                g = rem_nz & req & scan_idx.__eq__(c(1, width=3))
                fire_bru[ln] = fire_bru[ln] | g
                grant_any = grant_any | g

            if agu_lanes > ln:
                req = cand_agu_v[ln]
                if ln > 0:
                    req = req & fire_agu[ln - 1]
                g = rem_nz & req & scan_idx.__eq__(c(2, width=3))
                fire_agu[ln] = fire_agu[ln] | g
                grant_any = grant_any | g

            if std_lanes > ln:
                req = cand_std_v[ln]
                if ln > 0:
                    req = req & fire_std[ln - 1]
                g = rem_nz & req & scan_idx.__eq__(c(3, width=3))
                fire_std[ln] = fire_std[ln] | g
                grant_any = grant_any | g

            if cmd_lanes > ln:
                req = cand_cmd_v[ln]
                if ln > 0:
                    req = req & fire_cmd[ln - 1]
                g = rem_nz & req & scan_idx.__eq__(c(4, width=3))
                fire_cmd[ln] = fire_cmd[ln] | g
                grant_any = grant_any | g

            if fsu_lanes > ln:
                req = cand_fsu_v[ln]
                if ln > 0:
                    req = req & fire_fsu[ln - 1]
                g = rem_nz & req & scan_idx.__eq__(c(5, width=3))
                fire_fsu[ln] = fire_fsu[ln] | g
                grant_any = grant_any | g

            if tpl_lanes > ln:
                req = cand_tpl_v[ln]
                if ln > 0:
                    req = req & fire_tpl[ln - 1]
                g = rem_nz & req & scan_idx.__eq__(c(6, width=3))
                fire_tpl[ln] = fire_tpl[ln] | g
                grant_any = grant_any | g

            rem = grant_any._select_internal(rem - c(1, width=rem_w), rem)
            scan_idx = scan_idx.__eq__(c(6, width=3))._select_internal(c(0, width=3), scan_idx + c(1, width=3))

    # Next RR pointer (advance by 1 when any uop issues).
    issue_any = c(0, width=1)
    for ln in range(alu_lanes):
        issue_any = issue_any | fire_alu[ln]
    for ln in range(bru_lanes):
        issue_any = issue_any | fire_bru[ln]
    for ln in range(agu_lanes):
        issue_any = issue_any | fire_agu[ln]
    for ln in range(std_lanes):
        issue_any = issue_any | fire_std[ln]
    for ln in range(cmd_lanes):
        issue_any = issue_any | fire_cmd[ln]
    for ln in range(fsu_lanes):
        issue_any = issue_any | fire_fsu[ln]
    for ln in range(tpl_lanes):
        issue_any = issue_any | fire_tpl[ln]

    rr_inc = issue_rr_cur + c(1, width=3)
    rr_wrap = issue_rr_cur.__eq__(c(6, width=3))._select_internal(c(0, width=3), rr_inc)
    issue_rr_next = issue_any._select_internal(rr_wrap, issue_rr_cur)

    # -------------------------------------------------------------------------
    # Build per-entry issue-select masks for queue updates.
    # -------------------------------------------------------------------------
    issue_sel_alu = []
    for i in range(issueq_depth):
        hit = c(0, width=1)
        for ln in range(alu_lanes):
            hit = hit | (fire_alu[ln] & cand_alu_qidx[ln].__eq__(c(i, width=issueq_w)))
        issue_sel_alu.append(hit)

    issue_sel_bru = []
    for i in range(issueq_depth):
        hit = c(0, width=1)
        for ln in range(bru_lanes):
            hit = hit | (fire_bru[ln] & cand_bru_qidx[ln].__eq__(c(i, width=issueq_w)))
        issue_sel_bru.append(hit)

    issue_sel_agu = []
    for i in range(issueq_depth):
        hit = c(0, width=1)
        for ln in range(agu_lanes):
            hit = hit | (fire_agu[ln] & cand_agu_qidx[ln].__eq__(c(i, width=issueq_w)))
        issue_sel_agu.append(hit)

    issue_sel_std = []
    for i in range(issueq_depth):
        hit = c(0, width=1)
        for ln in range(std_lanes):
            hit = hit | (fire_std[ln] & cand_std_qidx[ln].__eq__(c(i, width=issueq_w)))
        issue_sel_std.append(hit)

    issue_sel_cmd = []
    for i in range(issueq_depth):
        hit = c(0, width=1)
        for ln in range(cmd_lanes):
            hit = hit | (fire_cmd[ln] & cand_cmd_qidx[ln].__eq__(c(i, width=issueq_w)))
        issue_sel_cmd.append(hit)

    issue_sel_fsu = []
    for i in range(issueq_depth):
        hit = c(0, width=1)
        for ln in range(fsu_lanes):
            hit = hit | (fire_fsu[ln] & cand_fsu_qidx[ln].__eq__(c(i, width=issueq_w)))
        issue_sel_fsu.append(hit)

    issue_sel_tpl = []
    for i in range(issueq_depth):
        hit = c(0, width=1)
        for ln in range(tpl_lanes):
            hit = hit | (fire_tpl[ln] & cand_tpl_qidx[ln].__eq__(c(i, width=issueq_w)))
        issue_sel_tpl.append(hit)

    iq_issue_sel = [
        issue_sel_alu,
        issue_sel_bru,
        issue_sel_agu,
        issue_sel_std,
        issue_sel_cmd,
        issue_sel_fsu,
        issue_sel_tpl,
    ]

    # -----------------------------------------------------------------------------
    # Instantiate lane pipes and collect writebacks.
    # -----------------------------------------------------------------------------
    wb_v = []
    wb_rob = []
    wb_dv = []
    wb_dt = []

    # NOTE: We intentionally avoid submodule instantiation here. Extremely large
    # emitted C++ modules can exhibit unreliable submodule shared_ptr
    # initialization on some toolchains. This inlines the same fixed-latency
    # behavior by flattening pipe regs into the top module.
    pipe_groups = [
        ("alu", alu_lanes, int(cfg.lat_alu), fire_alu, cand_alu_rob, cand_alu_dv, cand_alu_dt),
        ("bru", bru_lanes, int(cfg.lat_bru), fire_bru, cand_bru_rob, cand_bru_dv, cand_bru_dt),
        ("agu", agu_lanes, int(cfg.lat_agu), fire_agu, cand_agu_rob, cand_agu_dv, cand_agu_dt),
        ("std", std_lanes, int(cfg.lat_std), fire_std, cand_std_rob, cand_std_dv, cand_std_dt),
        ("cmd", cmd_lanes, int(cfg.lat_cmd), fire_cmd, cand_cmd_rob, cand_cmd_dv, cand_cmd_dt),
        ("fsu", fsu_lanes, int(cfg.lat_fsu), fire_fsu, cand_fsu_rob, cand_fsu_dv, cand_fsu_dt),
        ("tpl", tpl_lanes, int(cfg.lat_tpl), fire_tpl, cand_tpl_rob, cand_tpl_dv, cand_tpl_dt),
    ]

    for g_name, g_lanes, g_lat, g_v, g_rob, g_dv, g_dt in pipe_groups:
        lat = max(1, int(g_lat))
        for ln in range(int(g_lanes)):
            v = []
            rob = []
            dv = []
            dt = []
            for st in range(lat):
                v.append(m.out(f"{g_name}_pipe{ln}__v{st}", clk=clk, rst=rst, width=1, init=c(0, width=1), en=c(1, width=1)))
                rob.append(
                    m.out(
                        f"{g_name}_pipe{ln}__rob{st}",
                        clk=clk,
                        rst=rst,
                        width=rob_w,
                        init=c(0, width=rob_w),
                        en=c(1, width=1),
                    )
                )
                dv.append(m.out(f"{g_name}_pipe{ln}__dv{st}", clk=clk, rst=rst, width=1, init=c(0, width=1), en=c(1, width=1)))
                dt.append(
                    m.out(
                        f"{g_name}_pipe{ln}__dt{st}",
                        clk=clk,
                        rst=rst,
                        width=ptag_w,
                        init=c(0, width=ptag_w),
                        en=c(1, width=1),
                    )
                )

            # Stage0 capture.
            v[0].set(g_v[ln])
            rob[0].set(g_rob[ln], when=g_v[ln])
            dv[0].set(g_dv[ln], when=g_v[ln])
            dt[0].set(g_dt[ln], when=g_v[ln])

            # Shift pipeline.
            for st in range(1, lat):
                v[st].set(v[st - 1].out())
                rob[st].set(rob[st - 1].out(), when=v[st - 1].out())
                dv[st].set(dv[st - 1].out(), when=v[st - 1].out())
                dt[st].set(dt[st - 1].out(), when=v[st - 1].out())

            wb_v.append(v[-1].out())
            wb_rob.append(rob[-1].out())
            wb_dv.append(dv[-1].out())
            wb_dt.append(dt[-1].out())

    wb_ports = len(wb_v)

    # -----------------------------------------------------------------------------
    # Next-state updates (ROB entries, rename maps, masks, pointers, counters).
    # -----------------------------------------------------------------------------
    # Cycles/commits counters.
    cycles.set(cycles.out() + c(1, width=64))
    commits_inc = commit_n._zext(width=64)
    commits.set(commits.out() + commits_inc)

    # Head/tail/count.
    head_next = head_cur
    for j in range(commit_w):
        head_next = commit_fire[j]._select_internal(head_next + c(1, width=rob_w), head_next)
    head.set(head_next)

    tail_next = tail_cur
    for slot in range(dispatch_w):
        tail_next = dispatch_fire[slot]._select_internal(tail_next + c(1, width=rob_w), tail_next)
    tail.set(tail_next)

    issue_rr.set(issue_rr_next)

    count_next = count_cur - commit_n + dispatch_n
    rob_count.set(count_next)

    # Update rename map regs with final amap_tmp result for this cycle.
    for a in range(aregs):
        amap[a].set(amap_tmp[a])

    # Free mask reg updates: start from temporary cleared-by-alloc mask, then set freed old tags on commit.
    free_bits_next = free_bits_tmp
    for j in range(commit_w):
        old_tag = mux_by_uindex(m, idx=commit_idx[j], items=rob_oldt_w, default=c(0, width=ptag_w))
        dvj = mux_by_uindex(m, idx=commit_idx[j], items=rob_dv_w, default=c(0, width=1))
        do_free = commit_fire[j] & dvj & (~old_tag.__eq__(c(0, width=ptag_w)))
        new_bits = []
        for t in range(ptag_entries):
            is_t = old_tag.__eq__(c(t, width=ptag_w))
            new_bits.append((do_free & is_t)._select_internal(c(1, width=1), free_bits_next[t]))
        free_bits_next = new_bits
    free_mask.set(pack_bits_lsb(m, bits_lsb=free_bits_next))

    # Ready mask updates: clear on alloc, set on writeback.
    ready_bits_next = ready_bits_cur
    for slot in range(dispatch_w):
        do_clr = dispatch_fire[slot] & disp_dst_need[slot]
        tag = disp_alloc_tag[slot]
        new_bits = []
        for t in range(ptag_entries):
            is_t = tag.__eq__(c(t, width=ptag_w))
            new_bits.append((do_clr & is_t)._select_internal(c(0, width=1), ready_bits_next[t]))
        ready_bits_next = new_bits
    for k in range(wb_ports):
        do_set = wb_v[k] & wb_dv[k]
        tag = wb_dt[k]
        new_bits = []
        for t in range(ptag_entries):
            is_t = tag.__eq__(c(t, width=ptag_w))
            new_bits.append((do_set & is_t)._select_internal(c(1, width=1), ready_bits_next[t]))
        ready_bits_next = new_bits
    ready_mask.set(pack_bits_lsb(m, bits_lsb=ready_bits_next))

    # ROB entry updates.
    for i in range(rob_depth):
        idx_c = c(i, width=rob_w)

        # Commit clears.
        commit_hit = c(0, width=1)
        for j in range(commit_w):
            commit_hit = commit_hit | (commit_fire[j] & commit_idx[j].__eq__(idx_c))

        # Dispatch writes (prefix, so idx is tail+slot).
        dispatch_hit = c(0, width=1)
        disp_dv_i = rob_dv[i].out()
        disp_oldt_i = rob_oldt[i].out()

        for slot in range(dispatch_w):
            widx = tail_cur + c(slot, width=rob_w)
            hit = dispatch_fire[slot] & widx.__eq__(idx_c)
            dispatch_hit = dispatch_hit | hit
            disp_dv_i = hit._select_internal(disp_dst_need[slot], disp_dv_i)
            disp_oldt_i = hit._select_internal(disp_old_tag[slot], disp_oldt_i)

        # Writeback hits for this ROB index.
        wb_hit = c(0, width=1)
        for k in range(wb_ports):
            wb_hit = wb_hit | (wb_v[k] & wb_rob[k].__eq__(idx_c))

        # Update ROB valid/done/issued with priority: dispatch > commit > wb.
        v_next = rob_valid[i].out()
        v_next = commit_hit._select_internal(c(0, width=1), v_next)
        v_next = dispatch_hit._select_internal(c(1, width=1), v_next)
        rob_valid[i].set(v_next)

        done_next = rob_done[i].out() | wb_hit
        done_next = commit_hit._select_internal(c(0, width=1), done_next)
        done_next = dispatch_hit._select_internal(c(0, width=1), done_next)
        rob_done[i].set(done_next)

        dv_next = rob_dv[i].out()
        dv_next = commit_hit._select_internal(c(0, width=1), dv_next)
        dv_next = dispatch_hit._select_internal(disp_dv_i, dv_next)
        rob_dv[i].set(dv_next)

        oldt_next = rob_oldt[i].out()
        oldt_next = commit_hit._select_internal(c(0, width=ptag_w), oldt_next)
        oldt_next = dispatch_hit._select_internal(disp_oldt_i, oldt_next)
        rob_oldt[i].set(oldt_next)

    # -----------------------------------------------------------------------------
    # Issue queue updates (dequeue on issue, wakeup on wb, enqueue on dispatch).
    # -----------------------------------------------------------------------------
    for qgi, q in enumerate(iqs):
        code = int(q["code"])

        q_v = q["v"]
        q_rob = q["rob"]
        q_s0v = q["s0v"]
        q_s0t = q["s0t"]
        q_s0r = q["s0r"]
        q_s1v = q["s1v"]
        q_s1t = q["s1t"]
        q_s1r = q["s1r"]
        q_dv = q["dv"]
        q_dt = q["dt"]

        # Snapshot current queue state.
        v_cur = []
        rob_cur = []
        s0v_cur = []
        s0t_cur = []
        s0r_cur = []
        s1v_cur = []
        s1t_cur = []
        s1r_cur = []
        dv_cur = []
        dt_cur = []
        for i in range(issueq_depth):
            v_cur.append(q_v[i].out())
            rob_cur.append(q_rob[i].out())
            s0v_cur.append(q_s0v[i].out())
            s0t_cur.append(q_s0t[i].out())
            s0r_cur.append(q_s0r[i].out())
            s1v_cur.append(q_s1v[i].out())
            s1t_cur.append(q_s1t[i].out())
            s1r_cur.append(q_s1r[i].out())
            dv_cur.append(q_dv[i].out())
            dt_cur.append(q_dt[i].out())

        # Dequeue on issue (clear valid).
        v_after_issue = []
        for i in range(issueq_depth):
            v_after_issue.append(iq_issue_sel[qgi][i]._select_internal(c(0, width=1), v_cur[i]))

        # Wakeup on writeback (existing entries only; enqueue overwrites ready bits later).
        s0r_after_wb = []
        s1r_after_wb = []
        for i in range(issueq_depth):
            s0r_i = s0r_cur[i]
            s1r_i = s1r_cur[i]
            for k in range(wb_ports):
                w0 = (
                    wb_v[k]
                    & wb_dv[k]
                    & v_after_issue[i]
                    & s0v_cur[i]
                    & (~s0r_i)
                    & s0t_cur[i].__eq__(wb_dt[k])
                )
                w1 = (
                    wb_v[k]
                    & wb_dv[k]
                    & v_after_issue[i]
                    & s1v_cur[i]
                    & (~s1r_i)
                    & s1t_cur[i].__eq__(wb_dt[k])
                )
                s0r_i = w0._select_internal(c(1, width=1), s0r_i)
                s1r_i = w1._select_internal(c(1, width=1), s1r_i)
            s0r_after_wb.append(s0r_i)
            s1r_after_wb.append(s1r_i)

        # Start from (after issue, after wakeup).
        v_next = v_after_issue
        rob_next = rob_cur
        s0v_next = s0v_cur
        s0t_next = s0t_cur
        s0r_next = s0r_after_wb
        s1v_next = s1v_cur
        s1t_next = s1t_cur
        s1r_next = s1r_after_wb
        dv_next = dv_cur
        dt_next = dt_cur

        # Enqueue from dispatch (ready/valid prefix, may enqueue multiple per cycle).
        for slot in range(dispatch_w):
            enq = dispatch_fire[slot] & disp_iq[slot].__eq__(c(code, width=3))
            enq_idx = disp_iq_enq_idx[slot]
            wrob = tail_cur + c(slot, width=rob_w)

            # Same-cycle wakeup for newly enqueued entries:
            # dispatch sees ready_mask from the *previous* cycle, so if a producer
            # writes back in the same cycle the consumer is enqueued, we must
            # set operand-ready here to avoid missing the wakeup event.
            enq_s0r = disp_s0_rdy[slot]
            enq_s1r = disp_s1_rdy[slot]
            for k in range(wb_ports):
                enq_s0r = enq_s0r | (
                    wb_v[k]
                    & wb_dv[k]
                    & in_rows[slot]["src0_valid"].read()
                    & disp_s0_ptag[slot].__eq__(wb_dt[k])
                )
                enq_s1r = enq_s1r | (
                    wb_v[k]
                    & wb_dv[k]
                    & in_rows[slot]["src1_valid"].read()
                    & disp_s1_ptag[slot].__eq__(wb_dt[k])
                )

            v_new = []
            rob_new = []
            s0v_new = []
            s0t_new = []
            s0r_new = []
            s1v_new = []
            s1t_new = []
            s1r_new = []
            dv_new = []
            dt_new = []

            for i in range(issueq_depth):
                hit = enq & enq_idx.__eq__(c(i, width=issueq_w))
                v_new.append(hit._select_internal(c(1, width=1), v_next[i]))
                rob_new.append(hit._select_internal(wrob, rob_next[i]))
                s0v_new.append(hit._select_internal(in_rows[slot]["src0_valid"].read(), s0v_next[i]))
                s0t_new.append(hit._select_internal(disp_s0_ptag[slot], s0t_next[i]))
                s0r_new.append(hit._select_internal(enq_s0r, s0r_next[i]))
                s1v_new.append(hit._select_internal(in_rows[slot]["src1_valid"].read(), s1v_next[i]))
                s1t_new.append(hit._select_internal(disp_s1_ptag[slot], s1t_next[i]))
                s1r_new.append(hit._select_internal(enq_s1r, s1r_next[i]))
                dv_new.append(hit._select_internal(disp_dst_need[slot], dv_next[i]))
                dt_new.append(hit._select_internal(disp_alloc_tag[slot], dt_next[i]))

            v_next = v_new
            rob_next = rob_new
            s0v_next = s0v_new
            s0t_next = s0t_new
            s0r_next = s0r_new
            s1v_next = s1v_new
            s1t_next = s1t_new
            s1r_next = s1r_new
            dv_next = dv_new
            dt_next = dt_new

        for i in range(issueq_depth):
            q_v[i].set(v_next[i])
            q_rob[i].set(rob_next[i])
            q_s0v[i].set(s0v_next[i])
            q_s0t[i].set(s0t_next[i])
            q_s0r[i].set(s0r_next[i])
            q_s1v[i].set(s1v_next[i])
            q_s1t[i].set(s1t_next[i])
            q_s1r[i].set(s1r_next[i])
            q_dv[i].set(dv_next[i])
            q_dt[i].set(dt_next[i])

    # -----------------------------------------------------------------------------
    # Outputs: in_ready, retired rows, and stats.
    # -----------------------------------------------------------------------------
    # Input ready: contiguous prefix until structural stall.
    for slot in range(dispatch_w):
        m.output(f"in{slot}_ready", in_ready[slot])

    for j in range(commit_w):
        if bank_shift == 0:
            bank = c(0, width=bank_sel_w)
        else:
            bank = head_bank_q.out() + c(j, width=bank_sel_w)
        pl = mux_by_uindex(m, idx=bank, items=rob_pl_rdata, default=rob_pl_rdata[0])
        m.outputs(
            trace_s,
            {
                "seq": pl.slice(lsb=int(trace_lsb["seq"]), width=64),
                "pc": pl.slice(lsb=int(trace_lsb["pc"]), width=64),
                "insn": pl.slice(lsb=int(trace_lsb["insn"]), width=64),
                "len": pl.slice(lsb=int(trace_lsb["len"]), width=8),
                "src0_valid": pl.slice(lsb=int(trace_lsb["src0_valid"]), width=1),
                "src0_reg": pl.slice(lsb=int(trace_lsb["src0_reg"]), width=aregs_w),
                "src0_data": pl.slice(lsb=int(trace_lsb["src0_data"]), width=64),
                "src1_valid": pl.slice(lsb=int(trace_lsb["src1_valid"]), width=1),
                "src1_reg": pl.slice(lsb=int(trace_lsb["src1_reg"]), width=aregs_w),
                "src1_data": pl.slice(lsb=int(trace_lsb["src1_data"]), width=64),
                "dst_valid": pl.slice(lsb=int(trace_lsb["dst_valid"]), width=1),
                "dst_reg": pl.slice(lsb=int(trace_lsb["dst_reg"]), width=aregs_w),
                "dst_data": pl.slice(lsb=int(trace_lsb["dst_data"]), width=64),
                "mem_valid": pl.slice(lsb=int(trace_lsb["mem_valid"]), width=1),
                "mem_is_store": pl.slice(lsb=int(trace_lsb["mem_is_store"]), width=1),
                "mem_addr": pl.slice(lsb=int(trace_lsb["mem_addr"]), width=64),
                "mem_wdata": pl.slice(lsb=int(trace_lsb["mem_wdata"]), width=64),
                "mem_rdata": pl.slice(lsb=int(trace_lsb["mem_rdata"]), width=64),
                "mem_size": pl.slice(lsb=int(trace_lsb["mem_size"]), width=8),
                "trap_valid": pl.slice(lsb=int(trace_lsb["trap_valid"]), width=1),
                "trap_cause": pl.slice(lsb=int(trace_lsb["trap_cause"]), width=32),
                "traparg0": pl.slice(lsb=int(trace_lsb["traparg0"]), width=64),
                "next_pc": pl.slice(lsb=int(trace_lsb["next_pc"]), width=64),
                "valid": commit_fire_q[j].out(),
            },
            prefix=f"out{j}_",
        )

    m.output("cycles", cycles.out())
    m.output("commits", commits.out())


build.__pycircuit_name__ = "standalone_oex_top"
