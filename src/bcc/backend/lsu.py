from __future__ import annotations

from pycircuit import Circuit, module

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


def is_load_op(op, op_is):
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


def is_store_op(op, op_is):
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
    rob_depth: int = 64,
    rob_w: int = 6,
    sq_entries: int = 32,
    sq_w: int = 5,
) -> None:
    c = m.const
    issue_fire_lane0_raw = m.input("issue_fire_lane0_raw", width=1)
    ex0_is_load = m.input("ex0_is_load", width=1)
    ex0_addr = m.input("ex0_addr", width=64)
    ex0_rob = m.input("ex0_rob", width=rob_w)
    sub_head = m.input("sub_head", width=rob_w)
    commit_store_fire = m.input("commit_store_fire", width=1)
    commit_store_addr = m.input("commit_store_addr", width=64)
    commit_store_data = m.input("commit_store_data", width=64)

    rob_valid = []
    rob_done = []
    rob_is_store = []
    rob_store_addr = []
    rob_store_data = []
    for i in range(rob_depth):
        rob_valid.append(m.input(f"rob_valid{i}", width=1))
        rob_done.append(m.input(f"rob_done{i}", width=1))
        rob_is_store.append(m.input(f"rob_is_store{i}", width=1))
        rob_store_addr.append(m.input(f"rob_store_addr{i}", width=64))
        rob_store_data.append(m.input(f"rob_store_data{i}", width=64))

    stbuf_valid = []
    stbuf_addr = []
    stbuf_data = []
    for i in range(sq_entries):
        stbuf_valid.append(m.input(f"stbuf_valid{i}", width=1))
        stbuf_addr.append(m.input(f"stbuf_addr{i}", width=64))
        stbuf_data.append(m.input(f"stbuf_data{i}", width=64))

    lsu_load_fire_raw = issue_fire_lane0_raw & ex0_is_load
    lsu_load_dist = ex0_rob + sub_head
    lsu_older_store_pending_lane0 = c(0, width=1)
    lsu_forward_hit_lane0 = c(0, width=1)
    lsu_forward_data_lane0 = c(0, width=64)

    for i in range(rob_depth):
        idx = c(i, width=rob_w)
        dist = idx + sub_head
        older = dist.ult(lsu_load_dist)
        st = rob_valid[i] & rob_is_store[i] & older
        st_pending = st & (~rob_done[i])
        st_ready = st & rob_done[i]
        st_match = st_ready & rob_store_addr[i].eq(ex0_addr)
        lsu_older_store_pending_lane0 = lsu_older_store_pending_lane0 | (lsu_load_fire_raw & st_pending)
        lsu_forward_hit_lane0 = lsu_forward_hit_lane0 | (lsu_load_fire_raw & st_match)
        lsu_forward_data_lane0 = (lsu_load_fire_raw & st_match).select(rob_store_data[i], lsu_forward_data_lane0)

    for i in range(sq_entries):
        st_match = stbuf_valid[i] & stbuf_addr[i].eq(ex0_addr)
        lsu_forward_hit_lane0 = lsu_forward_hit_lane0 | (lsu_load_fire_raw & st_match)
        lsu_forward_data_lane0 = (lsu_load_fire_raw & st_match).select(stbuf_data[i], lsu_forward_data_lane0)

    commit_store_match_lane0 = lsu_load_fire_raw & commit_store_fire & commit_store_addr.eq(ex0_addr)
    lsu_forward_hit_lane0 = lsu_forward_hit_lane0 | commit_store_match_lane0
    lsu_forward_data_lane0 = commit_store_match_lane0.select(commit_store_data, lsu_forward_data_lane0)

    lsu_block_lane0 = lsu_load_fire_raw & lsu_older_store_pending_lane0
    issue_fire_lane0_eff = issue_fire_lane0_raw & (~lsu_block_lane0)

    m.output("lsu_load_fire_raw", lsu_load_fire_raw)
    m.output("lsu_older_store_pending_lane0", lsu_older_store_pending_lane0)
    m.output("lsu_forward_hit_lane0", lsu_forward_hit_lane0)
    m.output("lsu_forward_data_lane0", lsu_forward_data_lane0)
    m.output("lsu_block_lane0", lsu_block_lane0)
    m.output("issue_fire_lane0_eff", issue_fire_lane0_eff)
