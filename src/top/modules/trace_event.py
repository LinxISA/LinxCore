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


@module(name="LinxCoreTopOccFrontend")
def build_top_occ_frontend(m: Circuit) -> None:
    c = m.const

    tb_ifu_stub_enable = m.input("tb_ifu_stub_enable", width=1)
    tb_ifu_stub_valid = m.input("tb_ifu_stub_valid", width=1)
    ib_push_ready = m.input("ib_push_ready", width=1)
    tb_ifu_stub_pkt_uid = m.input("tb_ifu_stub_pkt_uid", width=64)
    tb_ifu_stub_pc = m.input("tb_ifu_stub_pc", width=64)
    ib_pop_valid = m.input("ib_pop_valid", width=1)
    f4_pkt_uid = m.input("f4_pkt_uid", width=64)
    f4_pc = m.input("f4_pc", width=64)
    backend_ready = m.input("backend_ready", width=1)

    dfx_kind_normal = c(0, width=3)
    dfx_kind_packet = c(5, width=3)
    zero_uid = c(0, width=64)
    zero_pc = c(0, width=64)
    zero_rob = c(0, width=6)
    zero_parent = c(0, width=64)
    pkt_uid_tag = c(1, width=64).shl(amount=63)

    emit_occ_debug(m, "f0", 0, c(0, width=1), pkt_uid_tag | zero_uid, zero_pc, zero_rob, dfx_kind_packet, zero_parent)

    f1_fire = tb_ifu_stub_enable & tb_ifu_stub_valid & ib_push_ready
    emit_occ_debug(m, "f1", 0, f1_fire, pkt_uid_tag | tb_ifu_stub_pkt_uid, tb_ifu_stub_pc, zero_rob, dfx_kind_packet, zero_parent)
    emit_occ_debug(
        m,
        "f1",
        1,
        c(0, width=1),
        (tb_ifu_stub_pkt_uid.shl(amount=3)) | zero_uid,
        zero_pc,
        zero_rob,
        dfx_kind_normal,
        zero_parent,
    )

    ib_stall = ib_pop_valid & (~backend_ready)
    ib_stall_cause = ib_stall._select_internal(c(1, width=8), c(0, width=8))
    for lane in range(4):
        uid_top = (f4_pkt_uid.shl(amount=3)) | c(lane, width=64)
        emit_occ_debug(
            m,
            "ib",
            lane,
            ib_pop_valid,
            uid_top,
            f4_pc,
            zero_rob,
            dfx_kind_normal,
            zero_parent,
            stall_top=ib_stall,
            stall_cause_top=ib_stall_cause,
        )


@module(name="LinxCoreTopOccExecMem")
def build_top_occ_exec_mem(m: Circuit) -> None:
    c = m.const
    dfx_kind_normal = c(0, width=3)
    dfx_kind_replay = c(3, width=3)
    zero_uid = c(0, width=64)
    zero_rob = c(0, width=6)
    zero_parent = c(0, width=64)

    for lane in range(4):
        emit_occ_debug(
            m,
            "e1",
            lane,
            m.input(f"e1_valid{lane}", width=1),
            m.input(f"e1_uid{lane}", width=64),
            m.input(f"e1_pc{lane}", width=64),
            m.input(f"e1_rob{lane}", width=6),
            dfx_kind_normal,
            zero_parent,
        )
    emit_occ_debug(m, "e2", 0, m.input("e2_valid", width=1), m.input("e2_uid", width=64), m.input("e2_pc", width=64), m.input("e2_rob", width=6), dfx_kind_normal, zero_parent)
    emit_occ_debug(m, "e3", 0, m.input("e3_valid", width=1), m.input("e3_uid", width=64), m.input("e3_pc", width=64), m.input("e3_rob", width=6), dfx_kind_normal, zero_parent)
    emit_occ_debug(m, "e4", 0, m.input("e4_valid", width=1), zero_uid, m.input("e4_pc", width=64), zero_rob, dfx_kind_replay, zero_parent)

    emit_occ_debug(m, "liq", 0, m.input("liq_valid", width=1), m.input("liq_uid", width=64), m.input("liq_pc", width=64), m.input("liq_rob", width=6), dfx_kind_normal, zero_parent)
    emit_occ_debug(m, "lhq", 0, m.input("lhq_valid", width=1), m.input("lhq_uid", width=64), m.input("lhq_pc", width=64), m.input("lhq_rob", width=6), dfx_kind_normal, zero_parent)
    emit_occ_debug(m, "stq", 0, m.input("stq_valid", width=1), m.input("stq_uid", width=64), m.input("stq_pc", width=64), m.input("stq_rob", width=6), dfx_kind_normal, zero_parent)
    emit_occ_debug(m, "scb", 0, m.input("scb_valid", width=1), m.input("scb_uid", width=64), m.input("scb_pc", width=64), m.input("scb_rob", width=6), dfx_kind_normal, zero_parent)
    emit_occ_debug(m, "mdb", 0, m.input("mdb_valid", width=1), zero_uid, m.input("mdb_pc", width=64), zero_rob, dfx_kind_replay, zero_parent)
    emit_occ_debug(m, "l1d", 0, m.input("l1d_valid", width=1), m.input("l1d_uid", width=64), m.input("l1d_pc", width=64), m.input("l1d_rob", width=6), dfx_kind_normal, zero_parent)


@module(name="LinxCoreTopOccBlockSpecial")
def build_top_occ_block_special(m: Circuit) -> None:
    c = m.const
    dfx_kind_normal = c(0, width=3)
    dfx_kind_flush = c(1, width=3)
    dfx_kind_trap = c(2, width=3)
    dfx_kind_replay = c(3, width=3)
    dfx_kind_template = c(4, width=3)
    zero_uid = c(0, width=64)
    zero_rob = c(0, width=6)
    zero_parent = c(0, width=64)

    for stage_name in ("bisq", "bctrl", "tmu", "tma", "cube", "vec", "tau", "brob"):
        emit_occ_debug(
            m,
            stage_name,
            0,
            m.input(f"{stage_name}_valid", width=1),
            m.input(f"{stage_name}_uid", width=64),
            m.input(f"{stage_name}_pc", width=64),
            m.input(f"{stage_name}_rob", width=6),
            dfx_kind_normal,
            zero_parent,
        )
    emit_occ_debug(m, "fls", 0, m.input("fls_valid", width=1), zero_uid, m.input("fls_pc", width=64), zero_rob, dfx_kind_flush, zero_parent)

    bru_kind = m.input("bru_mismatch", width=1)._select_internal(dfx_kind_replay, dfx_kind_normal)
    emit_occ_debug(
        m,
        "BRU",
        0,
        m.input("bru_valid", width=1),
        m.input("bru_uid", width=64),
        m.input("bru_pc", width=64),
        m.input("bru_rob", width=6),
        bru_kind,
        m.input("bru_parent", width=64),
    )
    redirect_from_corr = m.input("redirect_from_corr", width=1)
    rob_kind = redirect_from_corr._select_internal(dfx_kind_replay, dfx_kind_normal)
    emit_occ_debug(
        m,
        "ROB",
        4,
        m.input("rob_redirect_valid", width=1),
        m.input("rob_uid", width=64),
        m.input("rob_pc", width=64),
        m.input("rob_rob", width=6),
        rob_kind,
        m.input("rob_parent", width=64),
    )
    bru_fault_set = m.input("bru_fault_set", width=1)
    fls_kind = bru_fault_set._select_internal(
        dfx_kind_trap,
        redirect_from_corr._select_internal(dfx_kind_replay, dfx_kind_flush),
    )
    emit_occ_debug(m, "FLS", 1, m.input("fls1_valid", width=1), zero_uid, m.input("fls1_pc", width=64), zero_rob, fls_kind, zero_parent)

    template_valid = m.input("template_valid", width=1)
    template_uid = m.input("template_uid", width=64)
    template_pc = m.input("template_pc", width=64)
    template_parent = m.input("template_parent", width=64)
    for stage_name in ("d1", "d2", "d3", "s1", "e1"):
        emit_occ_debug(m, stage_name, 4, template_valid, template_uid, template_pc, zero_rob, dfx_kind_template, template_parent)
