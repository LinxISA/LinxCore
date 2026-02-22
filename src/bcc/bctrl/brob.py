from __future__ import annotations

from pycircuit import Circuit, module


@module(name="JanusBccBctrlBrob")
def build_janus_bcc_bctrl_brob(m: Circuit) -> None:
    clk_brob = m.clock("clk")
    rst_brob = m.reset("rst")

    issue_fire_brob = m.input("issue_fire_brob", width=1)
    issue_tag_brob = m.input("issue_tag_brob", width=8)
    issue_bid_brob = m.input("issue_bid_brob", width=64)
    issue_src_rob_brob = m.input("issue_src_rob_brob", width=6)
    retire_fire_brob = m.input("retire_fire_brob", width=1)
    retire_bid_brob = m.input("retire_bid_brob", width=64)
    query_bid_brob = m.input("query_bid_brob", width=64)

    rsp_valid_brob = m.input("rsp_valid_brob", width=1)
    rsp_tag_brob = m.input("rsp_tag_brob", width=8)
    rsp_status_brob = m.input("rsp_status_brob", width=4)
    rsp_data0_brob = m.input("rsp_data0_brob", width=64)
    rsp_data1_brob = m.input("rsp_data1_brob", width=64)
    rsp_trap_valid_brob = m.input("rsp_trap_valid_brob", width=1)
    rsp_trap_cause_brob = m.input("rsp_trap_cause_brob", width=32)

    c = m.const

    allocated_brob = m.out("allocated_brob", clk=clk_brob, rst=rst_brob, width=16, init=c(0, width=16), en=c(1, width=1))
    ready_brob = m.out("ready_brob", clk=clk_brob, rst=rst_brob, width=16, init=c(0, width=16), en=c(1, width=1))
    retired_brob = m.out("retired_brob", clk=clk_brob, rst=rst_brob, width=16, init=c(0, width=16), en=c(1, width=1))
    exception_brob = m.out("exception_brob", clk=clk_brob, rst=rst_brob, width=16, init=c(0, width=16), en=c(1, width=1))
    src_rob_slot_brob = []
    bid_slot_brob = []
    for i in range(16):
        src_rob_slot_brob.append(m.out(f"src_rob{i}_brob", clk=clk_brob, rst=rst_brob, width=6, init=c(0, width=6), en=c(1, width=1)))
        bid_slot_brob.append(m.out(f"bid{i}_brob", clk=clk_brob, rst=rst_brob, width=64, init=c(0, width=64), en=c(1, width=1)))

    issue_slot_brob = issue_tag_brob[0:4]
    rsp_slot_brob = rsp_tag_brob[0:4]
    retire_slot_brob = retire_bid_brob[0:4]
    query_slot_brob = query_bid_brob[0:4]

    issue_bit_brob = c(0, width=16)
    rsp_bit_brob = c(0, width=16)
    retire_bit_brob = c(0, width=16)
    rsp_src_rob_brob = c(0, width=6)
    rsp_bid_brob = c(0, width=64)
    rsp_slot_alloc_brob = c(0, width=1)
    rsp_slot_retired_brob = c(0, width=1)
    query_alloc_brob = c(0, width=1)
    query_ready_brob = c(0, width=1)
    query_retired_brob = c(0, width=1)
    query_exception_brob = c(0, width=1)
    for i in range(16):
        slot_hit_issue_brob = issue_slot_brob.eq(c(i, width=4))
        slot_hit_rsp_brob = rsp_slot_brob.eq(c(i, width=4))
        slot_hit_retire_brob = retire_slot_brob.eq(c(i, width=4))
        slot_hit_query_brob = query_slot_brob.eq(c(i, width=4))
        issue_bit_brob = slot_hit_issue_brob._select_internal(c(1 << i, width=16), issue_bit_brob)
        rsp_bit_brob = slot_hit_rsp_brob._select_internal(c(1 << i, width=16), rsp_bit_brob)
        retire_bit_brob = slot_hit_retire_brob._select_internal(c(1 << i, width=16), retire_bit_brob)
        rsp_src_rob_brob = slot_hit_rsp_brob._select_internal(src_rob_slot_brob[i].out(), rsp_src_rob_brob)
        rsp_bid_brob = slot_hit_rsp_brob._select_internal(bid_slot_brob[i].out(), rsp_bid_brob)
        rsp_slot_alloc_brob = slot_hit_rsp_brob._select_internal(allocated_brob.out()[i], rsp_slot_alloc_brob)
        rsp_slot_retired_brob = slot_hit_rsp_brob._select_internal(retired_brob.out()[i], rsp_slot_retired_brob)
        query_alloc_brob = slot_hit_query_brob._select_internal(allocated_brob.out()[i], query_alloc_brob)
        query_ready_brob = slot_hit_query_brob._select_internal(ready_brob.out()[i], query_ready_brob)
        query_retired_brob = slot_hit_query_brob._select_internal(retired_brob.out()[i], query_retired_brob)
        query_exception_brob = slot_hit_query_brob._select_internal(exception_brob.out()[i], query_exception_brob)

        src_rob_slot_brob[i].set(issue_src_rob_brob, when=issue_fire_brob & slot_hit_issue_brob)
        bid_slot_brob[i].set(issue_bid_brob, when=issue_fire_brob & slot_hit_issue_brob)

    rsp_fire_brob = rsp_valid_brob & rsp_slot_alloc_brob & (~rsp_slot_retired_brob)
    rsp_exception_brob = rsp_trap_valid_brob | (~rsp_status_brob.eq(c(0, width=4)))

    alloc_next_brob = allocated_brob.out()
    ready_next_brob = ready_brob.out()
    retired_next_brob = retired_brob.out()
    exception_next_brob = exception_brob.out()

    alloc_next_brob = issue_fire_brob._select_internal(alloc_next_brob | issue_bit_brob, alloc_next_brob)
    ready_next_brob = issue_fire_brob._select_internal(ready_next_brob & (~issue_bit_brob), ready_next_brob)
    retired_next_brob = issue_fire_brob._select_internal(retired_next_brob & (~issue_bit_brob), retired_next_brob)
    exception_next_brob = issue_fire_brob._select_internal(exception_next_brob & (~issue_bit_brob), exception_next_brob)

    ready_next_brob = rsp_fire_brob._select_internal(ready_next_brob | rsp_bit_brob, ready_next_brob)
    exception_next_brob = (rsp_fire_brob & rsp_exception_brob)._select_internal(exception_next_brob | rsp_bit_brob, exception_next_brob)

    retire_fire_ok_brob = retire_fire_brob & query_alloc_brob & (~query_retired_brob)
    alloc_next_brob = retire_fire_ok_brob._select_internal(alloc_next_brob & (~retire_bit_brob), alloc_next_brob)
    retired_next_brob = retire_fire_ok_brob._select_internal(retired_next_brob | retire_bit_brob, retired_next_brob)

    allocated_brob.set(alloc_next_brob)
    ready_brob.set(ready_next_brob)
    retired_brob.set(retired_next_brob)
    exception_brob.set(exception_next_brob)

    pending_brob = alloc_next_brob & (~ready_next_brob) & (~retired_next_brob)
    brob_state_brob = c(0, width=4)
    brob_state_brob = query_alloc_brob._select_internal(brob_state_brob | c(0x1, width=4), brob_state_brob)
    brob_state_brob = query_ready_brob._select_internal(brob_state_brob | c(0x2, width=4), brob_state_brob)
    brob_state_brob = query_retired_brob._select_internal(brob_state_brob | c(0x4, width=4), brob_state_brob)
    brob_state_brob = query_exception_brob._select_internal(brob_state_brob | c(0x8, width=4), brob_state_brob)

    m.output("brob_pending_brob", pending_brob)
    m.output("brob_rsp_fire_brob", rsp_fire_brob)
    m.output("brob_query_state_brob", brob_state_brob)
    m.output("brob_query_allocated_brob", query_alloc_brob)
    m.output("brob_query_ready_brob", query_ready_brob)
    m.output("brob_query_exception_brob", query_exception_brob)
    m.output("brob_query_retired_brob", query_retired_brob)

    m.output("brob_to_rob_stage_rsp_valid_brob", rsp_fire_brob)
    m.output("brob_to_rob_stage_rsp_tag_brob", rsp_tag_brob)
    m.output("brob_to_rob_stage_rsp_status_brob", rsp_status_brob)
    m.output("brob_to_rob_stage_rsp_data0_brob", rsp_data0_brob)
    m.output("brob_to_rob_stage_rsp_data1_brob", rsp_data1_brob)
    m.output("brob_to_rob_stage_rsp_trap_valid_brob", rsp_trap_valid_brob)
    m.output("brob_to_rob_stage_rsp_trap_cause_brob", rsp_trap_cause_brob)
    m.output("brob_to_rob_stage_rsp_src_rob_brob", rsp_src_rob_brob)
    m.output("brob_to_rob_stage_rsp_bid_brob", rsp_bid_brob)
    m.output("brob_to_rob_stage_brob_state_brob", brob_state_brob)
    m.output("brob_to_rob_stage_brob_ready_brob", query_ready_brob)
    m.output("brob_to_rob_stage_brob_exception_brob", query_exception_brob)
    m.output("brob_to_rob_stage_brob_retired_brob", query_retired_brob)
