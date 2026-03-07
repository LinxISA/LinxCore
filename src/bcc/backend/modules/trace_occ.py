from __future__ import annotations

from pycircuit import Circuit, function, module


@function
def emit_occ_debug(
    m: Circuit,
    stage_top: str,
    lane_top: int,
    valid_top,
    uid_top,
    pc_top,
    rob_top,
    kind_top,
    parent_uid_top,
    block_uid_top=None,
    core_id_top=None,
    stall_top=None,
    stall_cause_top=None,
):
    c = m.const
    m.debug_occ(
        stage_top,
        lane_top,
        {
            "valid": valid_top,
            "uop_uid": uid_top,
            "pc": pc_top,
            "rob": rob_top,
            "kind": kind_top,
            "parent_uid": parent_uid_top,
            "block_uid": c(0, width=64) if block_uid_top is None else block_uid_top,
            "core_id": c(0, width=2) if core_id_top is None else core_id_top,
            "stall": c(0, width=1) if stall_top is None else stall_top,
            "stall_cause": c(0, width=8) if stall_cause_top is None else stall_cause_top,
        },
    )


@module(name="LinxCoreBackendOccFrontend")
def build_backend_occ_frontend(m: Circuit) -> None:
    c = m.const
    dfx_kind_normal = c(0, width=3)
    zero_rob = c(0, width=6)
    zero_parent = c(0, width=64)

    for stage in ("d1", "d2", "d3", "s1", "s2"):
        for lane in range(4):
            valid = m.input(f"{stage}_valid{lane}", width=1)
            uid = m.input(f"{stage}_uid{lane}", width=64)
            pc = m.input(f"{stage}_pc{lane}", width=64)
            stall = m.input(f"{stage}_stall{lane}", width=1)
            stall_cause = m.input(f"{stage}_stall_cause{lane}", width=8)
            emit_occ_debug(
                m,
                stage,
                lane,
                valid,
                uid,
                pc,
                zero_rob,
                dfx_kind_normal,
                zero_parent,
                stall_top=stall,
                stall_cause_top=stall_cause,
            )


@module(name="LinxCoreBackendOccSchedule")
def build_backend_occ_schedule(m: Circuit) -> None:
    c = m.const

    dfx_kind_normal = c(0, width=3)
    zero_core = c(0, width=2)

    for slot in range(4):
        iq_valid = m.input(f"iq_valid{slot}", width=1)
        iq_uid = m.input(f"iq_uid{slot}", width=64)
        iq_pc = m.input(f"iq_pc{slot}", width=64)
        iq_rob = m.input(f"iq_rob{slot}", width=6)
        iq_parent = m.input(f"iq_parent{slot}", width=64)
        iq_block = m.input(f"iq_block{slot}", width=64)
        emit_occ_debug(m, "iq", slot, iq_valid, iq_uid, iq_pc, iq_rob, dfx_kind_normal, iq_parent, iq_block, zero_core)
