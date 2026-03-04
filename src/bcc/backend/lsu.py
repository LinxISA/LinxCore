from __future__ import annotations

from pycircuit import Circuit, function, module, u

from common.isa import (
    OP_C_LDI,
    OP_C_LWI,
    OP_C_SDI,
    OP_C_SWI,
    OP_HL_LB_PCR,
    OP_HL_LBU_PCR,
    OP_HL_LD_PCR,
    OP_HL_LH_PCR,
    OP_HL_LHU_PCR,
    OP_HL_LW_PCR,
    OP_HL_LWU_PCR,
    OP_HL_SB_PCR,
    OP_HL_SD_PCR,
    OP_HL_SH_PCR,
    OP_HL_SW_PCR,
    OP_LB,
    OP_LBI,
    OP_LBU,
    OP_LBUI,
    OP_LD,
    OP_LDI,
    OP_LH,
    OP_LHI,
    OP_LHU,
    OP_LHUI,
    OP_LW,
    OP_LWI,
    OP_LWU,
    OP_LWUI,
    OP_SB,
    OP_SBI,
    OP_SD,
    OP_SDI,
    OP_SH,
    OP_SHI,
    OP_SW,
    OP_SWI,
)


@function
def is_load_op(m: Circuit, op, op_is):
    _ = m
    return op_is(
        op,
        OP_LWI,
        OP_C_LWI,
        OP_LBI,
        OP_LBUI,
        OP_LHI,
        OP_LHUI,
        OP_LWUI,
        OP_LB,
        OP_LBU,
        OP_LH,
        OP_LHU,
        OP_LW,
        OP_LWU,
        OP_LD,
        OP_LDI,
        OP_C_LDI,
        OP_HL_LB_PCR,
        OP_HL_LBU_PCR,
        OP_HL_LH_PCR,
        OP_HL_LHU_PCR,
        OP_HL_LW_PCR,
        OP_HL_LWU_PCR,
        OP_HL_LD_PCR,
    )


@function
def is_store_op(m: Circuit, op, op_is):
    _ = m
    return op_is(
        op,
        OP_SBI,
        OP_SHI,
        OP_SWI,
        OP_C_SWI,
        OP_SDI,
        OP_C_SDI,
        OP_SB,
        OP_SH,
        OP_SW,
        OP_SD,
        OP_HL_SB_PCR,
        OP_HL_SH_PCR,
        OP_HL_SW_PCR,
        OP_HL_SD_PCR,
    )


@module(name="LinxCoreLsuStage")
def build_lsu_stage(
    m: Circuit,
    *,
    rob_w: int = 6,
) -> None:
    if rob_w <= 0:
        raise ValueError("rob_w must be > 0")

    issue_fire_lane0_raw = m.input("issue_fire_lane0_raw", width=1)
    ex0_is_load = m.input("ex0_is_load", width=1)
    ex0_is_store = m.input("ex0_is_store", width=1)
    ex0_addr = m.input("ex0_addr", width=64)
    ex0_lsid = m.input("ex0_lsid", width=32)
    lsid_issue_ptr = m.input("lsid_issue_ptr", width=32)
    commit_store_fire = m.input("commit_store_fire", width=1)
    commit_store_addr = m.input("commit_store_addr", width=64)
    commit_store_data = m.input("commit_store_data", width=64)

    rob_older_store_pending_i = m.input("rob_older_store_pending_i", width=1)
    rob_forward_hit_i = m.input("rob_forward_hit_i", width=1)
    rob_forward_data_i = m.input("rob_forward_data_i", width=64)

    stbuf_forward_hit_i = m.input("stbuf_forward_hit_i", width=1)
    stbuf_forward_data_i = m.input("stbuf_forward_data_i", width=64)

    lsu_mem_fire_raw = issue_fire_lane0_raw & (ex0_is_load | ex0_is_store)
    lsu_load_fire_raw = issue_fire_lane0_raw & ex0_is_load
    lsu_lsid_block_lane0 = lsu_mem_fire_raw & (ex0_lsid != lsid_issue_ptr)

    lsu_older_store_pending_lane0 = rob_older_store_pending_i

    commit_store_match_lane0 = lsu_load_fire_raw & commit_store_fire & (commit_store_addr == ex0_addr)
    lsu_forward_hit_lane0 = u(1, 0)
    lsu_forward_data_lane0 = u(64, 0)
    fwd_hit = [rob_forward_hit_i, stbuf_forward_hit_i, commit_store_match_lane0]
    fwd_data = [rob_forward_data_i, stbuf_forward_data_i, commit_store_data]
    for i in range(3):
        hit = fwd_hit[i]
        lsu_forward_hit_lane0 = lsu_forward_hit_lane0 | hit
        lsu_forward_data_lane0 = fwd_data[i] if hit else lsu_forward_data_lane0

    lsu_block_lane0 = lsu_lsid_block_lane0 | (lsu_load_fire_raw & lsu_older_store_pending_lane0)
    issue_fire_lane0_eff = issue_fire_lane0_raw & (~lsu_block_lane0)
    lsu_lsid_issue_advance = issue_fire_lane0_eff & (ex0_is_load | ex0_is_store)

    m.output("lsu_mem_fire_raw", lsu_mem_fire_raw)
    m.output("lsu_load_fire_raw", lsu_load_fire_raw)
    m.output("lsu_lsid_block_lane0", lsu_lsid_block_lane0)
    m.output("lsu_older_store_pending_lane0", lsu_older_store_pending_lane0)
    m.output("lsu_forward_hit_lane0", lsu_forward_hit_lane0)
    m.output("lsu_forward_data_lane0", lsu_forward_data_lane0)
    m.output("lsu_block_lane0", lsu_block_lane0)
    m.output("issue_fire_lane0_eff", issue_fire_lane0_eff)
    m.output("lsu_lsid_issue_advance", lsu_lsid_issue_advance)
