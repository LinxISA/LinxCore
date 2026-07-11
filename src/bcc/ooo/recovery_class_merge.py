from __future__ import annotations

from math import log2

from pycircuit import Circuit, function, module, u

from bcc.backend.helpers import mux_by_uindex


MISS_PRED_FLUSH = 0
PE_REPLAY = 1
NUKE_FLUSH = 2
INNER_FLUSH = 3
FAST_REPLAY = 4
FAST_FLUSH = 5
SIMT_INNER_FLUSH = 6

SCALAR_IEX = 0

GLOBAL_FLUSH = 0
GLOBAL_REPLAY = 1
PE_SCOPED = 2


def _is_power_of_two(value: int) -> bool:
    return value > 0 and (value & (value - 1)) == 0


@function
def _req_wire(m: Circuit, *, slot: dict[str, object]):
    _ = m
    req = {}
    for field, value in slot.items():
        req[field] = value.out()
    return req


@function
def _prov_wire(m: Circuit, *, slot: dict[str, object]):
    _ = m
    prov = {}
    for field, value in slot.items():
        prov[field] = value.out()
    return prov


@function
def _is_type(m: Circuit, *, req: dict[str, object], value: int):
    return req["type"] == u(3, value)


@function
def _is_flush_type(m: Circuit, *, req: dict[str, object]):
    return (
        _is_type(m, req=req, value=MISS_PRED_FLUSH)
        | _is_type(m, req=req, value=NUKE_FLUSH)
        | _is_type(m, req=req, value=INNER_FLUSH)
        | _is_type(m, req=req, value=FAST_FLUSH)
    )


@function
def _base_on_bid(m: Circuit, *, req: dict[str, object]):
    return (
        _is_type(m, req=req, value=MISS_PRED_FLUSH)
        | _is_type(m, req=req, value=NUKE_FLUSH)
        | _is_type(m, req=req, value=FAST_REPLAY)
        | _is_type(m, req=req, value=FAST_FLUSH)
        | (_is_type(m, req=req, value=PE_REPLAY) & (~req["fetch_tpc_valid"]))
    )


@function
def _base_on_pe(m: Circuit, *, req: dict[str, object]):
    return _is_type(m, req=req, value=PE_REPLAY) | (~(req["exec_engine"] == u(2, SCALAR_IEX)))


@function
def _rob_equal(m: Circuit, *, lhs_value, lhs_wrap, rhs_value, rhs_wrap):
    _ = m
    return (lhs_wrap == rhs_wrap) & (lhs_value == rhs_value)


@function
def _rob_less(m: Circuit, *, lhs_value, lhs_wrap, rhs_value, rhs_wrap):
    _ = m
    same_wrap = lhs_wrap == rhs_wrap
    return lhs_value.ult(rhs_value) if same_wrap else rhs_value.ult(lhs_value)


@function
def _bid_equal(m: Circuit, *, lhs: dict[str, object], rhs: dict[str, object], slot_bits: int):
    return _rob_equal(
        m,
        lhs_value=lhs["block_bid"][0:slot_bits],
        lhs_wrap=lhs["block_bid"][slot_bits : slot_bits + 1],
        rhs_value=rhs["block_bid"][0:slot_bits],
        rhs_wrap=rhs["block_bid"][slot_bits : slot_bits + 1],
    )


@function
def _bid_less(m: Circuit, *, lhs: dict[str, object], rhs: dict[str, object], slot_bits: int):
    return _rob_less(
        m,
        lhs_value=lhs["block_bid"][0:slot_bits],
        lhs_wrap=lhs["block_bid"][slot_bits : slot_bits + 1],
        rhs_value=rhs["block_bid"][0:slot_bits],
        rhs_wrap=rhs["block_bid"][slot_bits : slot_bits + 1],
    )


@function
def _bid_less_equal(m: Circuit, *, lhs: dict[str, object], rhs: dict[str, object], slot_bits: int):
    return _bid_less(m, lhs=lhs, rhs=rhs, slot_bits=slot_bits) | _bid_equal(
        m, lhs=lhs, rhs=rhs, slot_bits=slot_bits
    )


@function
def _bid_rid_less_equal(m: Circuit, *, lhs: dict[str, object], rhs: dict[str, object], slot_bits: int):
    rid_equal = _rob_equal(
        m,
        lhs_value=lhs["rid"],
        lhs_wrap=lhs["rid_wrap"],
        rhs_value=rhs["rid"],
        rhs_wrap=rhs["rid_wrap"],
    )
    rid_less = _rob_less(
        m,
        lhs_value=lhs["rid"],
        lhs_wrap=lhs["rid_wrap"],
        rhs_value=rhs["rid"],
        rhs_wrap=rhs["rid_wrap"],
    )
    return _bid_less(m, lhs=lhs, rhs=rhs, slot_bits=slot_bits) | (
        _bid_equal(m, lhs=lhs, rhs=rhs, slot_bits=slot_bits) & (rid_less | rid_equal)
    )


@function
def _check_older(m: Circuit, *, src, dst, oldest, slot_bits: int):
    same_stid = src["stid"] == dst["stid"]
    uses_bid = _base_on_bid(m, req=src) | _base_on_bid(m, req=dst)
    bids_equal = _bid_equal(m, lhs=src, rhs=dst, slot_bits=slot_bits)
    same_bid_case = uses_bid & bids_equal
    same_rid_case = (~uses_bid) & bids_equal & _rob_equal(
        m,
        lhs_value=src["rid"],
        lhs_wrap=src["rid_wrap"],
        rhs_value=dst["rid"],
        rhs_wrap=dst["rid_wrap"],
    )
    same_bid_result = (
        _is_type(m, req=src, value=MISS_PRED_FLUSH)
        | (_is_type(m, req=src, value=NUKE_FLUSH) & _is_type(m, req=dst, value=INNER_FLUSH))
        | (_is_type(m, req=src, value=NUKE_FLUSH) & _is_type(m, req=dst, value=PE_REPLAY))
        | (_is_type(m, req=src, value=FAST_REPLAY) & _is_type(m, req=dst, value=PE_REPLAY))
    )
    same_rid_result = (
        (_is_type(m, req=src, value=INNER_FLUSH) & _is_type(m, req=dst, value=PE_REPLAY))
        | (_is_type(m, req=src, value=PE_REPLAY) & _is_type(m, req=dst, value=INNER_FLUSH))
        | (_is_type(m, req=src, value=INNER_FLUSH) & _is_type(m, req=dst, value=INNER_FLUSH))
    )
    src_is_oldest = _rob_equal(
        m,
        lhs_value=src["block_bid"][0:slot_bits],
        lhs_wrap=src["block_bid"][slot_bits : slot_bits + 1],
        rhs_value=oldest[0:slot_bits],
        rhs_wrap=oldest[slot_bits : slot_bits + 1],
    )
    age_result = (
        _bid_less_equal(m, lhs=src, rhs=dst, slot_bits=slot_bits) | src_is_oldest
        if uses_bid
        else _bid_rid_less_equal(m, lhs=src, rhs=dst, slot_bits=slot_bits)
    )
    pe_result = u(1, 0) if _is_type(m, req=dst, value=MISS_PRED_FLUSH) else age_result
    pe_result = u(1, 1) if _is_type(m, req=dst, value=FAST_REPLAY) else pe_result
    same_pe_age = (src["pe"] == dst["pe"]) & age_result
    pe_result = same_pe_age if _is_type(m, req=dst, value=PE_REPLAY) else pe_result
    result = pe_result if _is_type(m, req=src, value=PE_REPLAY) else age_result
    result = same_rid_result if same_rid_case else result
    result = same_bid_result if same_bid_case else result
    return same_stid & result


@function
def _merge_valid(m: Circuit, *, src, dst, slot_bits: int):
    uses_bid = _base_on_bid(m, req=src) | _base_on_bid(m, req=dst)
    same_pe = src["pe"] == dst["pe"]
    inner_merge = (
        (~uses_bid)
        & same_pe
        & _bid_rid_less_equal(m, lhs=dst, rhs=src, slot_bits=slot_bits)
        & _is_type(m, req=src, value=INNER_FLUSH)
    )
    nuke_merge = (
        uses_bid
        & same_pe
        & _bid_less(m, lhs=dst, rhs=src, slot_bits=slot_bits)
        & _is_type(m, req=src, value=NUKE_FLUSH)
    )
    return inner_merge | nuke_merge


@function
def _with_inner_type(m: Circuit, *, req):
    merged = dict(req)
    merged["type"] = u(3, INNER_FLUSH)
    merged["valid"] = u(1, 1)
    return merged


@function
def _select_req(m: Circuit, *, index, slots, field_widths):
    result = {
        "valid": mux_by_uindex(m, idx=index, items=[slot["valid"] for slot in slots], default=u(1, 0))
    }
    for field, width in field_widths.items():
        result[field] = mux_by_uindex(m, idx=index, items=[slot[field] for slot in slots], default=u(width, 0))
    return result


@function
def _select_prov(m: Circuit, *, index, slots, source_count: int, source_index_width: int):
    return {
        "cause_mask": mux_by_uindex(
            m,
            idx=index,
            items=[slot["cause_mask"] for slot in slots],
            default=u(source_count, 0),
        ),
        "payload_source_valid": mux_by_uindex(
            m,
            idx=index,
            items=[slot["payload_source_valid"] for slot in slots],
            default=u(1, 0),
        ),
        "payload_source": mux_by_uindex(
            m,
            idx=index,
            items=[slot["payload_source"] for slot in slots],
            default=u(source_index_width, 0),
        ),
    }


@function
def _set_valid(m: Circuit, *, slot_state, value, when):
    _ = m
    result = dict(slot_state)
    result["valid"] = value if when else slot_state["valid"]
    return result


@function
def _store(m: Circuit, *, slot_state, req, when, field_widths):
    result = dict(slot_state)
    result["valid"] = u(1, 1) if when else slot_state["valid"]
    for field in field_widths:
        result[field] = req[field] if when else slot_state[field]
    return result


@function
def _clear_prov(m: Circuit, *, prov_state, when, source_count: int, source_index_width: int):
    _ = m
    result = dict(prov_state)
    result["cause_mask"] = u(source_count, 0) if when else prov_state["cause_mask"]
    result["payload_source_valid"] = u(1, 0) if when else prov_state["payload_source_valid"]
    result["payload_source"] = u(source_index_width, 0) if when else prov_state["payload_source"]
    return result


@function
def _store_prov(m: Circuit, *, prov_state, prov, when):
    _ = m
    result = dict(prov_state)
    for field in prov_state:
        result[field] = prov[field] if when else prov_state[field]
    return result


@function
def _merge_prov(m: Circuit, *, src, dst):
    _ = m
    return {
        "cause_mask": src["cause_mask"] | dst["cause_mask"],
        "payload_source_valid": dst["payload_source_valid"],
        "payload_source": dst["payload_source"],
    }


@function
def _resolve_mask(m: Circuit, *, current, cause_mask, when, source_count: int):
    _ = m
    return current | (cause_mask if when else u(source_count, 0))


@function
def _pack_mask(m: Circuit, *, bits):
    _ = m
    mask = u(len(bits), 1) if bits[0] else u(len(bits), 0)
    for index in range(1, len(bits)):
        bit = bits[index]
        mask = mask | (u(len(bits), 1 << index) if bit else u(len(bits), 0))
    return mask


@module(name="LinxBccOooRecoveryClassMerge")
def build_linx_bcc_ooo_recovery_class_merge(
    m: Circuit,
    *,
    stid_count: int = 2,
    pe_count: int = 2,
    rob_entries: int = 8,
    bid_width: int = 16,
    tpc_width: int = 64,
    stid_width: int = 8,
    pe_width: int = 8,
    tid_width: int = 8,
    rid_width: int = 3,
    source_count: int = 4,
) -> None:
    if stid_count <= 0:
        raise ValueError("recovery class merge must expose at least one STID")
    if pe_count <= 0:
        raise ValueError("recovery class merge must expose at least one PE")
    if not _is_power_of_two(rob_entries) or rob_entries <= 1:
        raise ValueError("recovery class merge ROB entries must be a power of two greater than one")
    if stid_count > (1 << stid_width):
        raise ValueError("recovery class merge STID count must fit stid_width")
    if pe_count > (1 << pe_width):
        raise ValueError("recovery class merge PE count must fit pe_width")
    if bid_width <= int(log2(rob_entries)):
        raise ValueError("full BID must include a uniqueness bit above the ROB slot")
    if rid_width <= 0 or tid_width <= 0 or tpc_width <= 0:
        raise ValueError("recovery class merge RID, TID, and TPC widths must be positive")
    if source_count <= 0:
        raise ValueError("recovery provenance must expose at least one source")

    clk = m.clock("clk")
    rst = m.reset("rst")
    slot_bits = int(log2(rob_entries))
    stid_index_width = max(1, (stid_count - 1).bit_length())
    pe_index_width = max(1, (pe_count - 1).bit_length())
    source_index_width = max(1, (source_count - 1).bit_length())

    in_valid = m.input("in_valid", width=1)
    in_type = m.input("in_type", width=3)
    in_block_bid = m.input("in_block_bid", width=bid_width)
    in_stid = m.input("in_stid", width=stid_width)
    in_pe = m.input("in_pe", width=pe_width)
    in_tid = m.input("in_tid", width=tid_width)
    in_gid = m.input("in_gid", width=rid_width)
    in_gid_wrap = m.input("in_gid_wrap", width=1)
    in_rid = m.input("in_rid", width=rid_width)
    in_rid_wrap = m.input("in_rid_wrap", width=1)
    in_lsid = m.input("in_lsid", width=rid_width)
    in_lsid_wrap = m.input("in_lsid_wrap", width=1)
    in_exec_engine = m.input("in_exec_engine", width=2)
    in_fetch_tpc_valid = m.input("in_fetch_tpc_valid", width=1)
    in_fetch_tpc = m.input("in_fetch_tpc", width=tpc_width)
    in_immediate_flush = m.input("in_immediate_flush", width=1)
    in_source = m.input("in_source", width=source_index_width)
    oldest_block_complete = m.input("oldest_block_complete", width=stid_count)
    out_ready = m.input("out_ready", width=1)
    oldest_bid = [m.input(f"oldest_bid{stid}", width=slot_bits + 1) for stid in range(stid_count)]

    field_widths = {
        "type": 3,
        "block_bid": bid_width,
        "stid": stid_width,
        "pe": pe_width,
        "tid": tid_width,
        "gid": rid_width,
        "gid_wrap": 1,
        "rid": rid_width,
        "rid_wrap": 1,
        "lsid": rid_width,
        "lsid_wrap": 1,
        "exec_engine": 2,
        "fetch_tpc_valid": 1,
        "fetch_tpc": tpc_width,
        "immediate_flush": 1,
    }

    global_flush = []
    global_replay = []
    pe_scoped = []
    global_flush_prov = []
    global_replay_prov = []
    pe_scoped_prov = []
    for stid in range(stid_count):
        flush_slot = {
            "valid": m.out(
                f"global_flush{stid}_valid", clk=clk, rst=rst, width=1, init=u(1, 0), en=u(1, 1)
            )
        }
        replay_slot = {
            "valid": m.out(
                f"global_replay{stid}_valid", clk=clk, rst=rst, width=1, init=u(1, 0), en=u(1, 1)
            )
        }
        for field, width in field_widths.items():
            flush_slot[field] = m.out(
                f"global_flush{stid}_{field}",
                clk=clk,
                rst=rst,
                width=width,
                init=u(width, 0),
                en=u(1, 1),
            )
            replay_slot[field] = m.out(
                f"global_replay{stid}_{field}",
                clk=clk,
                rst=rst,
                width=width,
                init=u(width, 0),
                en=u(1, 1),
            )
        global_flush.append(flush_slot)
        global_replay.append(replay_slot)

        flush_prov = {}
        replay_prov = {}
        for prefix, slot in [
            (f"global_flush{stid}", flush_prov),
            (f"global_replay{stid}", replay_prov),
        ]:
            slot["cause_mask"] = m.out(
                f"{prefix}_cause_mask",
                clk=clk,
                rst=rst,
                width=source_count,
                init=u(source_count, 0),
                en=u(1, 1),
            )
            slot["payload_source_valid"] = m.out(
                f"{prefix}_payload_source_valid",
                clk=clk,
                rst=rst,
                width=1,
                init=u(1, 0),
                en=u(1, 1),
            )
            slot["payload_source"] = m.out(
                f"{prefix}_payload_source",
                clk=clk,
                rst=rst,
                width=source_index_width,
                init=u(source_index_width, 0),
                en=u(1, 1),
            )
        global_flush_prov.append(flush_prov)
        global_replay_prov.append(replay_prov)

        pe_lanes = []
        pe_prov_lanes = []
        for pe in range(pe_count):
            pe_slot = {
                "valid": m.out(
                    f"pe_scoped{stid}_{pe}_valid",
                    clk=clk,
                    rst=rst,
                    width=1,
                    init=u(1, 0),
                    en=u(1, 1),
                )
            }
            for field, width in field_widths.items():
                pe_slot[field] = m.out(
                    f"pe_scoped{stid}_{pe}_{field}",
                    clk=clk,
                    rst=rst,
                    width=width,
                    init=u(width, 0),
                    en=u(1, 1),
                )
            pe_lanes.append(pe_slot)
            pe_prov = {}
            pe_prov["cause_mask"] = m.out(
                f"pe_scoped{stid}_{pe}_cause_mask",
                clk=clk,
                rst=rst,
                width=source_count,
                init=u(source_count, 0),
                en=u(1, 1),
            )
            pe_prov["payload_source_valid"] = m.out(
                f"pe_scoped{stid}_{pe}_payload_source_valid",
                clk=clk,
                rst=rst,
                width=1,
                init=u(1, 0),
                en=u(1, 1),
            )
            pe_prov["payload_source"] = m.out(
                f"pe_scoped{stid}_{pe}_payload_source",
                clk=clk,
                rst=rst,
                width=source_index_width,
                init=u(source_index_width, 0),
                en=u(1, 1),
            )
            pe_prov_lanes.append(pe_prov)
        pe_scoped.append(pe_lanes)
        pe_scoped_prov.append(pe_prov_lanes)

    next_stid = m.out(
        "next_stid", clk=clk, rst=rst, width=stid_index_width, init=u(stid_index_width, 0), en=u(1, 1)
    )
    out_valid_q = m.out("out_valid_q", clk=clk, rst=rst, width=1, init=u(1, 0), en=u(1, 1))
    out_class_q = m.out("out_class_q", clk=clk, rst=rst, width=2, init=u(2, 0), en=u(1, 1))
    out_stid_q = m.out(
        "out_stid_q", clk=clk, rst=rst, width=stid_index_width, init=u(stid_index_width, 0), en=u(1, 1)
    )
    out_pe_q = m.out(
        "out_pe_q", clk=clk, rst=rst, width=pe_index_width, init=u(pe_index_width, 0), en=u(1, 1)
    )
    out_cause_mask_q = m.out(
        "out_cause_mask_q", clk=clk, rst=rst, width=source_count, init=u(source_count, 0), en=u(1, 1)
    )
    out_payload_source_valid_q = m.out(
        "out_payload_source_valid_q", clk=clk, rst=rst, width=1, init=u(1, 0), en=u(1, 1)
    )
    out_payload_source_q = m.out(
        "out_payload_source_q",
        clk=clk,
        rst=rst,
        width=source_index_width,
        init=u(source_index_width, 0),
        en=u(1, 1),
    )
    out_fields = {}
    for field, width in field_widths.items():
        out_fields[field] = m.out(
            f"out_{field}_q", clk=clk, rst=rst, width=width, init=u(width, 0), en=u(1, 1)
        )

    input_req = {
        "valid": in_valid,
        "type": in_type,
        "block_bid": in_block_bid,
        "stid": in_stid,
        "pe": in_pe,
        "tid": in_tid,
        "gid": in_gid,
        "gid_wrap": in_gid_wrap,
        "rid": in_rid,
        "rid_wrap": in_rid_wrap,
        "lsid": in_lsid,
        "lsid_wrap": in_lsid_wrap,
        "exec_engine": in_exec_engine,
        "fetch_tpc_valid": in_fetch_tpc_valid,
        "fetch_tpc": in_fetch_tpc,
        "immediate_flush": in_immediate_flush,
    }
    input_cause_mask = u(source_count, 0)
    for source in range(source_count):
        input_cause_mask = input_cause_mask | (
            u(source_count, 1 << source) if in_source == u(source_index_width, source) else u(source_count, 0)
        )
    input_prov = {
        "cause_mask": input_cause_mask if in_valid else u(source_count, 0),
        "payload_source_valid": in_valid,
        "payload_source": in_source,
    }

    flush_req = [_req_wire(m, slot=slot) for slot in global_flush]
    replay_req = [_req_wire(m, slot=slot) for slot in global_replay]
    pe_req = [[_req_wire(m, slot=slot) for slot in lanes] for lanes in pe_scoped]
    flush_prov = [_prov_wire(m, slot=slot) for slot in global_flush_prov]
    replay_prov = [_prov_wire(m, slot=slot) for slot in global_replay_prov]
    pe_prov = [[_prov_wire(m, slot=slot) for slot in lanes] for lanes in pe_scoped_prov]

    first_pe_valid = []
    first_pe_index = []
    first_pe_req = []
    lane_valid = []
    lane_class = []
    lane_pe = []
    lane_req = []
    lane_prov = []
    replay_cancels_flush = []
    for stid in range(stid_count):
        pe_valid = pe_req[stid][0]["valid"] & 0
        pe_index = in_pe[0:pe_index_width] & 0
        for pe in range(pe_count):
            take = (~pe_valid) & pe_req[stid][pe]["valid"]
            pe_index = u(pe_index_width, pe) if take else pe_index
            pe_valid = pe_valid | pe_req[stid][pe]["valid"]
        selected_pe_req = _select_req(m, index=pe_index, slots=pe_req[stid], field_widths=field_widths)
        selected_pe_prov = _select_prov(
            m,
            index=pe_index,
            slots=pe_prov[stid],
            source_count=source_count,
            source_index_width=source_index_width,
        )
        first_pe_valid.append(pe_valid)
        first_pe_index.append(pe_index)
        first_pe_req.append(selected_pe_req)

        cancel = flush_req[stid]["valid"] & replay_req[stid]["valid"] & _bid_less_equal(
            m, lhs=replay_req[stid], rhs=flush_req[stid], slot_bits=slot_bits
        )
        replay_cancels_flush.append(cancel)
        valid = flush_req[stid]["valid"] | replay_req[stid]["valid"] | pe_valid
        lane_valid.append(valid)

        cls = u(2, PE_SCOPED)
        pe_sel = pe_index
        req = dict(selected_pe_req)
        prov = dict(selected_pe_prov)
        for condition, new_class, new_pe, source in [
            (replay_req[stid]["valid"], GLOBAL_REPLAY, 0, replay_req[stid]),
            (flush_req[stid]["valid"], GLOBAL_FLUSH, 0, flush_req[stid]),
            (replay_req[stid]["valid"] & ((~flush_req[stid]["valid"]) | cancel), GLOBAL_REPLAY, 0, replay_req[stid]),
        ]:
            cls = u(2, new_class) if condition else cls
            pe_sel = u(pe_index_width, new_pe) if condition else pe_sel
            for field in req:
                req[field] = source[field] if condition else req[field]
        for condition, source in [
            (replay_req[stid]["valid"], replay_prov[stid]),
            (flush_req[stid]["valid"], flush_prov[stid]),
            (replay_req[stid]["valid"] & ((~flush_req[stid]["valid"]) | cancel), replay_prov[stid]),
        ]:
            for field in prov:
                prov[field] = source[field] if condition else prov[field]
        lane_class.append(cls)
        lane_pe.append(pe_sel)
        lane_req.append(req)
        lane_prov.append(prov)

    selected_lanes_by_start = []
    selected_valid_by_start = []
    for start in range(stid_count):
        selected_valid = lane_valid[0] & 0
        selected_lane = next_stid.out() & 0
        for offset in range(stid_count):
            lane = (start + offset) % stid_count
            take = (~selected_valid) & lane_valid[lane]
            selected_lane = u(stid_index_width, lane) if take else selected_lane
            selected_valid = selected_valid | lane_valid[lane]
        selected_lanes_by_start.append(selected_lane)
        selected_valid_by_start.append(selected_valid)

    selected_lane = mux_by_uindex(
        m, idx=next_stid.out(), items=selected_lanes_by_start, default=u(stid_index_width, 0)
    )
    selected_valid = mux_by_uindex(
        m, idx=next_stid.out(), items=selected_valid_by_start, default=u(1, 0)
    )
    selected_class = mux_by_uindex(m, idx=selected_lane, items=lane_class, default=u(2, 0))
    selected_pe = mux_by_uindex(m, idx=selected_lane, items=lane_pe, default=u(pe_index_width, 0))
    selected_req = {
        "valid": mux_by_uindex(
            m, idx=selected_lane, items=[req["valid"] for req in lane_req], default=u(1, 0)
        )
    }
    for field, width in field_widths.items():
        selected_req[field] = mux_by_uindex(
            m, idx=selected_lane, items=[req[field] for req in lane_req], default=u(width, 0)
        )
    selected_prov = {
        "cause_mask": mux_by_uindex(
            m,
            idx=selected_lane,
            items=[prov["cause_mask"] for prov in lane_prov],
            default=u(source_count, 0),
        ),
        "payload_source_valid": mux_by_uindex(
            m,
            idx=selected_lane,
            items=[prov["payload_source_valid"] for prov in lane_prov],
            default=u(1, 0),
        ),
        "payload_source": mux_by_uindex(
            m,
            idx=selected_lane,
            items=[prov["payload_source"] for prov in lane_prov],
            default=u(source_index_width, 0),
        ),
    }

    out_slot_ready = (~out_valid_q.out()) | out_ready
    dispatch = out_slot_ready & selected_valid
    out_accepted = out_valid_q.out() & out_ready
    out_valid_next = dispatch if out_slot_ready else out_valid_q.out()
    out_valid_q.set(out_valid_next)
    out_class_q.set(selected_class, when=dispatch)
    out_stid_q.set(selected_lane, when=dispatch)
    out_pe_q.set(selected_pe, when=dispatch)
    out_cause_mask_q.set(selected_prov["cause_mask"], when=dispatch)
    out_payload_source_valid_q.set(selected_prov["payload_source_valid"], when=dispatch)
    out_payload_source_q.set(selected_prov["payload_source"], when=dispatch)
    for field, reg in out_fields.items():
        reg.set(selected_req[field], when=dispatch)

    next_state: dict[int, dict[str, object]] = {}
    next_prov_state: dict[int, dict[str, object]] = {}
    all_slots = []
    for slot in global_flush:
        all_slots.append(slot)
    for slot in global_replay:
        all_slots.append(slot)
    for lanes in pe_scoped:
        for slot in lanes:
            all_slots.append(slot)
    for slot in all_slots:
        slot_state = {}
        for field, value in slot.items():
            slot_state[field] = value.out()
        next_state[id(slot)] = slot_state
    all_prov_slots = []
    for slot in global_flush_prov:
        all_prov_slots.append(slot)
    for slot in global_replay_prov:
        all_prov_slots.append(slot)
    for lanes in pe_scoped_prov:
        for slot in lanes:
            all_prov_slots.append(slot)
    for slot in all_prov_slots:
        prov_state = {}
        for field, value in slot.items():
            prov_state[field] = value.out()
        next_prov_state[id(slot)] = prov_state

    resolved_mask = u(source_count, 0)

    for stid in range(stid_count):
        lane_dispatch = dispatch & (selected_lane == u(stid_index_width, stid))
        clear_flush = lane_dispatch & (
            (selected_class == u(2, GLOBAL_FLUSH))
            | ((selected_class == u(2, GLOBAL_REPLAY)) & replay_cancels_flush[stid])
        )
        clear_replay = lane_dispatch & (selected_class == u(2, GLOBAL_REPLAY))
        next_state[id(global_flush[stid])] = _set_valid(
            m, slot_state=next_state[id(global_flush[stid])], value=u(1, 0), when=clear_flush
        )
        next_prov_state[id(global_flush_prov[stid])] = _clear_prov(
            m,
            prov_state=next_prov_state[id(global_flush_prov[stid])],
            when=clear_flush,
            source_count=source_count,
            source_index_width=source_index_width,
        )
        next_state[id(global_replay[stid])] = _set_valid(
            m, slot_state=next_state[id(global_replay[stid])], value=u(1, 0), when=clear_replay
        )
        next_prov_state[id(global_replay_prov[stid])] = _clear_prov(
            m,
            prov_state=next_prov_state[id(global_replay_prov[stid])],
            when=clear_replay,
            source_count=source_count,
            source_index_width=source_index_width,
        )
        resolved_mask = _resolve_mask(
            m,
            current=resolved_mask,
            cause_mask=flush_prov[stid]["cause_mask"],
            when=lane_dispatch & (selected_class == u(2, GLOBAL_REPLAY)) & replay_cancels_flush[stid],
            source_count=source_count,
        )
        for pe in range(pe_count):
            clear_pe = (
                lane_dispatch
                & (selected_class == u(2, PE_SCOPED))
                & (selected_pe == u(pe_index_width, pe))
            )
            next_state[id(pe_scoped[stid][pe])] = _set_valid(
                m, slot_state=next_state[id(pe_scoped[stid][pe])], value=u(1, 0), when=clear_pe
            )
            next_prov_state[id(pe_scoped_prov[stid][pe])] = _clear_prov(
                m,
                prov_state=next_prov_state[id(pe_scoped_prov[stid][pe])],
                when=clear_pe,
                source_count=source_count,
                source_index_width=source_index_width,
            )

    in_stid_range = in_stid.ult(u(stid_width, stid_count))
    in_pe_range = in_pe.ult(u(pe_width, pe_count))
    in_ready = in_stid_range & in_pe_range
    in_accepted = in_valid & in_ready
    in_is_pe = _base_on_pe(m, req=input_req)
    in_is_global_flush = (~in_is_pe) & _is_flush_type(m, req=input_req)
    in_is_global_replay = (~in_is_pe) & (~_is_flush_type(m, req=input_req))

    dropped_by_older = u(1, 0)
    dropped_by_complete = u(1, 0)
    merged = u(1, 0)

    for stid in range(stid_count):
        lane_input = in_accepted & (in_stid == u(stid_width, stid))
        flush_effective_req = dict(flush_req[stid])
        replay_effective_req = dict(replay_req[stid])
        flush_effective_req["valid"] = next_state[id(global_flush[stid])]["valid"]
        replay_effective_req["valid"] = next_state[id(global_replay[stid])]["valid"]
        flush_effective_prov = dict(flush_prov[stid])
        replay_effective_prov = dict(replay_prov[stid])

        pe_effective_req = []
        for pe in range(pe_count):
            req = dict(pe_req[stid][pe])
            req["valid"] = next_state[id(pe_scoped[stid][pe])]["valid"]
            pe_effective_req.append(req)
        pe_effective_prov = [dict(prov) for prov in pe_prov[stid]]
        target_pe_req = _select_req(
            m, index=in_pe[0:pe_index_width], slots=pe_effective_req, field_widths=field_widths
        )
        target_pe_prov = _select_prov(
            m,
            index=in_pe[0:pe_index_width],
            slots=pe_effective_prov,
            source_count=source_count,
            source_index_width=source_index_width,
        )

        target_pe_older = target_pe_req["valid"] & _check_older(
            m, src=target_pe_req, dst=input_req, oldest=oldest_bid[stid], slot_bits=slot_bits
        )
        flush_older = flush_effective_req["valid"] & _check_older(
            m, src=flush_effective_req, dst=input_req, oldest=oldest_bid[stid], slot_bits=slot_bits
        )
        input_older_than_flush = flush_effective_req["valid"] & _check_older(
            m, src=input_req, dst=flush_effective_req, oldest=oldest_bid[stid], slot_bits=slot_bits
        )
        replay_older = replay_effective_req["valid"] & _check_older(
            m, src=replay_effective_req, dst=input_req, oldest=oldest_bid[stid], slot_bits=slot_bits
        )

        gf_active = lane_input & in_is_global_flush
        gf_target_conflict = (
            gf_active & (~flush_older) & (~_is_type(m, req=input_req, value=MISS_PRED_FLUSH)) & target_pe_older
        )
        gf_merge = gf_target_conflict & _merge_valid(m, src=input_req, dst=target_pe_req, slot_bits=slot_bits)
        gf_drop = (gf_active & flush_older) | (gf_target_conflict & (~gf_merge))
        gf_store = gf_active & (~flush_older) & (~gf_target_conflict)
        dropped_by_older = dropped_by_older | gf_drop
        resolved_mask = _resolve_mask(
            m,
            current=resolved_mask,
            cause_mask=input_prov["cause_mask"],
            when=gf_drop,
            source_count=source_count,
        )

        merged_target = _with_inner_type(m, req=target_pe_req)
        merged_target_is_pe = _base_on_pe(m, req=merged_target)
        merged_target_prov = _merge_prov(m, src=input_prov, dst=target_pe_prov)
        resolved_mask = _resolve_mask(
            m,
            current=resolved_mask,
            cause_mask=flush_effective_prov["cause_mask"],
            when=gf_store & flush_effective_req["valid"],
            source_count=source_count,
        )
        next_state[id(global_flush[stid])] = _store(
            m,
            slot_state=next_state[id(global_flush[stid])],
            req=input_req,
            when=gf_store,
            field_widths=field_widths,
        )
        next_prov_state[id(global_flush_prov[stid])] = _store_prov(
            m,
            prov_state=next_prov_state[id(global_flush_prov[stid])],
            prov=input_prov,
            when=gf_store,
        )
        for pe in range(pe_count):
            pe_is_target = in_pe == u(pe_width, pe)
            next_state[id(pe_scoped[stid][pe])] = _set_valid(
                m,
                slot_state=next_state[id(pe_scoped[stid][pe])],
                value=u(1, 0),
                when=gf_merge & pe_is_target,
            )
            next_prov_state[id(pe_scoped_prov[stid][pe])] = _clear_prov(
                m,
                prov_state=next_prov_state[id(pe_scoped_prov[stid][pe])],
                when=gf_merge & pe_is_target,
                source_count=source_count,
                source_index_width=source_index_width,
            )
            next_state[id(pe_scoped[stid][pe])] = _store(
                m,
                slot_state=next_state[id(pe_scoped[stid][pe])],
                req=merged_target,
                when=gf_merge & merged_target_is_pe & pe_is_target,
                field_widths=field_widths,
            )
            next_prov_state[id(pe_scoped_prov[stid][pe])] = _store_prov(
                m,
                prov_state=next_prov_state[id(pe_scoped_prov[stid][pe])],
                prov=merged_target_prov,
                when=gf_merge & merged_target_is_pe & pe_is_target,
            )
            cancel_pe = gf_store & pe_effective_req[pe]["valid"] & _check_older(
                m, src=input_req, dst=pe_effective_req[pe], oldest=oldest_bid[stid], slot_bits=slot_bits
            )
            resolved_mask = _resolve_mask(
                m,
                current=resolved_mask,
                cause_mask=pe_effective_prov[pe]["cause_mask"],
                when=cancel_pe,
                source_count=source_count,
            )
            next_state[id(pe_scoped[stid][pe])] = _set_valid(
                m,
                slot_state=next_state[id(pe_scoped[stid][pe])],
                value=u(1, 0),
                when=cancel_pe,
            )
            next_prov_state[id(pe_scoped_prov[stid][pe])] = _clear_prov(
                m,
                prov_state=next_prov_state[id(pe_scoped_prov[stid][pe])],
                when=cancel_pe,
                source_count=source_count,
                source_index_width=source_index_width,
            )
        next_state[id(global_flush[stid])] = _store(
            m,
            slot_state=next_state[id(global_flush[stid])],
            req=merged_target,
            when=gf_merge & (~merged_target_is_pe),
            field_widths=field_widths,
        )
        next_prov_state[id(global_flush_prov[stid])] = _store_prov(
            m,
            prov_state=next_prov_state[id(global_flush_prov[stid])],
            prov=merged_target_prov,
            when=gf_merge & (~merged_target_is_pe),
        )
        cancel_replay = gf_store & replay_effective_req["valid"] & _check_older(
            m, src=input_req, dst=replay_effective_req, oldest=oldest_bid[stid], slot_bits=slot_bits
        )
        resolved_mask = _resolve_mask(
            m,
            current=resolved_mask,
            cause_mask=replay_effective_prov["cause_mask"],
            when=cancel_replay,
            source_count=source_count,
        )
        next_state[id(global_replay[stid])] = _set_valid(
            m, slot_state=next_state[id(global_replay[stid])], value=u(1, 0), when=cancel_replay
        )
        next_prov_state[id(global_replay_prov[stid])] = _clear_prov(
            m,
            prov_state=next_prov_state[id(global_replay_prov[stid])],
            when=cancel_replay,
            source_count=source_count,
            source_index_width=source_index_width,
        )

        complete = oldest_block_complete[stid : stid + 1]
        gr_active = lane_input & in_is_global_replay
        gr_drop_complete = gr_active & complete
        gr_drop_flush = (
            gr_active
            & (~complete)
            & flush_effective_req["valid"]
            & _is_type(m, req=flush_effective_req, value=MISS_PRED_FLUSH)
            & flush_older
        )
        gr_drop_replay = gr_active & (~complete) & (~gr_drop_flush) & replay_older
        gr_store = gr_active & (~complete) & (~gr_drop_flush) & (~gr_drop_replay)
        dropped_by_complete = dropped_by_complete | gr_drop_complete
        dropped_by_older = dropped_by_older | gr_drop_flush | gr_drop_replay
        resolved_mask = _resolve_mask(
            m,
            current=resolved_mask,
            cause_mask=input_prov["cause_mask"],
            when=gr_drop_complete | gr_drop_flush | gr_drop_replay,
            source_count=source_count,
        )
        resolved_mask = _resolve_mask(
            m,
            current=resolved_mask,
            cause_mask=replay_effective_prov["cause_mask"],
            when=gr_store & replay_effective_req["valid"],
            source_count=source_count,
        )
        next_state[id(global_replay[stid])] = _store(
            m,
            slot_state=next_state[id(global_replay[stid])],
            req=input_req,
            when=gr_store,
            field_widths=field_widths,
        )
        next_prov_state[id(global_replay_prov[stid])] = _store_prov(
            m,
            prov_state=next_prov_state[id(global_replay_prov[stid])],
            prov=input_prov,
            when=gr_store,
        )
        for pe in range(pe_count):
            cancel_pe = gr_store & pe_effective_req[pe]["valid"] & _check_older(
                m, src=input_req, dst=pe_effective_req[pe], oldest=oldest_bid[stid], slot_bits=slot_bits
            )
            resolved_mask = _resolve_mask(
                m,
                current=resolved_mask,
                cause_mask=pe_effective_prov[pe]["cause_mask"],
                when=cancel_pe,
                source_count=source_count,
            )
            next_state[id(pe_scoped[stid][pe])] = _set_valid(
                m, slot_state=next_state[id(pe_scoped[stid][pe])], value=u(1, 0), when=cancel_pe
            )
            next_prov_state[id(pe_scoped_prov[stid][pe])] = _clear_prov(
                m,
                prov_state=next_prov_state[id(pe_scoped_prov[stid][pe])],
                when=cancel_pe,
                source_count=source_count,
                source_index_width=source_index_width,
            )

        pe_active = lane_input & in_is_pe
        same_flush_pe = flush_effective_req["pe"] == input_req["pe"]
        pe_flush_conflict = pe_active & (
            flush_older | (flush_effective_req["valid"] & same_flush_pe & input_older_than_flush)
        )
        pe_merge = pe_flush_conflict & _merge_valid(m, src=flush_effective_req, dst=input_req, slot_bits=slot_bits)
        pe_drop_flush = pe_flush_conflict & (~pe_merge)
        pe_drop_replay = pe_active & (~pe_flush_conflict) & replay_older
        pe_drop_target = pe_active & (~pe_flush_conflict) & (~replay_older) & target_pe_older
        pe_store = pe_active & (~pe_flush_conflict) & (~replay_older) & (~target_pe_older)
        dropped_by_older = dropped_by_older | pe_drop_flush | pe_drop_replay | pe_drop_target
        resolved_mask = _resolve_mask(
            m,
            current=resolved_mask,
            cause_mask=input_prov["cause_mask"],
            when=pe_drop_flush | pe_drop_replay | pe_drop_target,
            source_count=source_count,
        )
        merged_input = _with_inner_type(m, req=input_req)
        merged_input_is_pe = _base_on_pe(m, req=merged_input)
        merged_input_prov = _merge_prov(m, src=flush_effective_prov, dst=input_prov)
        next_state[id(global_flush[stid])] = _set_valid(
            m, slot_state=next_state[id(global_flush[stid])], value=u(1, 0), when=pe_merge
        )
        next_prov_state[id(global_flush_prov[stid])] = _clear_prov(
            m,
            prov_state=next_prov_state[id(global_flush_prov[stid])],
            when=pe_merge,
            source_count=source_count,
            source_index_width=source_index_width,
        )
        next_state[id(global_flush[stid])] = _store(
            m,
            slot_state=next_state[id(global_flush[stid])],
            req=merged_input,
            when=pe_merge & (~merged_input_is_pe),
            field_widths=field_widths,
        )
        next_prov_state[id(global_flush_prov[stid])] = _store_prov(
            m,
            prov_state=next_prov_state[id(global_flush_prov[stid])],
            prov=merged_input_prov,
            when=pe_merge & (~merged_input_is_pe),
        )
        merged = merged | pe_merge | gf_merge
        for pe in range(pe_count):
            pe_is_target = in_pe == u(pe_width, pe)
            resolved_mask = _resolve_mask(
                m,
                current=resolved_mask,
                cause_mask=pe_effective_prov[pe]["cause_mask"],
                when=pe_store & pe_is_target & pe_effective_req[pe]["valid"],
                source_count=source_count,
            )
            next_state[id(pe_scoped[stid][pe])] = _store(
                m,
                slot_state=next_state[id(pe_scoped[stid][pe])],
                req=input_req,
                when=pe_store & pe_is_target,
                field_widths=field_widths,
            )
            next_prov_state[id(pe_scoped_prov[stid][pe])] = _store_prov(
                m,
                prov_state=next_prov_state[id(pe_scoped_prov[stid][pe])],
                prov=input_prov,
                when=pe_store & pe_is_target,
            )
            resolved_mask = _resolve_mask(
                m,
                current=resolved_mask,
                cause_mask=pe_effective_prov[pe]["cause_mask"],
                when=pe_merge & merged_input_is_pe & pe_is_target & pe_effective_req[pe]["valid"],
                source_count=source_count,
            )
            next_state[id(pe_scoped[stid][pe])] = _store(
                m,
                slot_state=next_state[id(pe_scoped[stid][pe])],
                req=merged_input,
                when=pe_merge & merged_input_is_pe & pe_is_target,
                field_widths=field_widths,
            )
            next_prov_state[id(pe_scoped_prov[stid][pe])] = _store_prov(
                m,
                prov_state=next_prov_state[id(pe_scoped_prov[stid][pe])],
                prov=merged_input_prov,
                when=pe_merge & merged_input_is_pe & pe_is_target,
            )

    for slot in all_slots:
        for field, reg in slot.items():
            reg.set(next_state[id(slot)][field])
    for slot in all_prov_slots:
        for field, reg in slot.items():
            reg.set(next_prov_state[id(slot)][field])

    next_stid_value = selected_lane + u(stid_index_width, 1)
    next_stid_value = (
        u(stid_index_width, 0)
        if selected_lane == u(stid_index_width, stid_count - 1)
        else next_stid_value[0:stid_index_width]
    )
    next_stid.set(next_stid_value, when=dispatch)

    flush_mask = _pack_mask(m, bits=[slot["valid"].out() for slot in global_flush])
    replay_mask = _pack_mask(m, bits=[slot["valid"].out() for slot in global_replay])
    pe_valid_bits = []
    for lanes in pe_scoped:
        for slot in lanes:
            pe_valid_bits.append(slot["valid"].out())
    pe_mask = _pack_mask(m, bits=pe_valid_bits)

    m.output("in_ready", in_ready)
    m.output("in_accepted", in_accepted)
    m.output("in_blocked_by_stid", in_valid & (~in_stid_range))
    m.output("in_blocked_by_pe", in_valid & in_stid_range & (~in_pe_range))
    m.output("in_dropped_by_older", dropped_by_older)
    m.output("in_dropped_by_complete", dropped_by_complete)
    m.output("in_merged", merged)
    m.output("out_valid", out_valid_q.out())
    m.output("out_accepted", out_accepted)
    m.output("out_class", out_class_q.out())
    m.output("out_stid_index", out_stid_q.out())
    m.output("out_pe_index", out_pe_q.out())
    m.output("out_cause_mask", out_cause_mask_q.out())
    m.output("out_payload_source_valid", out_payload_source_valid_q.out())
    m.output("out_payload_source", out_payload_source_q.out())
    m.output("resolved_mask", resolved_mask)
    for field, reg in out_fields.items():
        m.output(f"out_{field}", reg.out())
    m.output("global_flush_pending_mask", flush_mask)
    m.output("global_replay_pending_mask", replay_mask)
    m.output("pe_pending_mask", pe_mask)
    m.output(
        "pending",
        out_valid_q.out()
        | (flush_mask != u(stid_count, 0))
        | (replay_mask != u(stid_count, 0))
        | (pe_mask != u(stid_count * pe_count, 0)),
    )
