from __future__ import annotations

from pycircuit import Circuit, module

from ..decode import build_decode_stage
from ..dispatch import build_dispatch_stage

STAGE_NAMES = ("d1", "d2", "d3", "s1", "s2")
_PACKET_FIELDS = (
    ("valid", 1),
    ("pc", 64),
    ("window", 64),
    ("checkpoint_id", 6),
    ("pkt_uid", 64),
)
_PACKET_PAYLOAD_FIELDS = (
    ("pc", 64),
    ("window", 64),
    ("checkpoint_id", 6),
    ("pkt_uid", 64),
)
DECODE_SLOT_FIELD_SPECS = (
    ("valid", 1),
    ("pc", 64),
    ("op", 12),
    ("len", 3),
    ("regdst", 6),
    ("srcl", 6),
    ("srcr", 6),
    ("srcr_type", 2),
    ("shamt", 6),
    ("srcp", 6),
    ("imm", 64),
    ("insn_raw", 64),
    ("is_start_marker", 1),
    ("push_t", 1),
    ("push_u", 1),
    ("is_store", 1),
    ("is_boundary", 1),
    ("is_bstart", 1),
    ("is_bstop", 1),
    ("boundary_kind", 3),
    ("boundary_target", 64),
    ("pred_take", 1),
    ("resolved_d2", 1),
    ("dst_is_gpr", 1),
    ("need_pdst", 1),
    ("dst_kind", 2),
    ("checkpoint_id", 6),
    ("uop_uid", 64),
)
def _pack_values(m: Circuit, values):
    return m.concat(*reversed(values))


def _pack_fields(m: Circuit, field_specs, values_by_name: dict[str, object]):
    return _pack_values(m, [values_by_name[name] for name, _width in field_specs])


def dispatch_slot_field_specs(*, iq_w: int, ptag_w: int, pregs: int):
    return (
        ("to_alu", 1),
        ("to_bru", 1),
        ("to_lsu", 1),
        ("to_d2", 1),
        ("to_cmd", 1),
        ("alu_alloc_valid", 1),
        ("alu_alloc_idx", iq_w),
        ("bru_alloc_valid", 1),
        ("bru_alloc_idx", iq_w),
        ("lsu_alloc_valid", 1),
        ("lsu_alloc_idx", iq_w),
        ("cmd_alloc_valid", 1),
        ("cmd_alloc_idx", iq_w),
        ("preg_alloc_valid", 1),
        ("preg_alloc_tag", ptag_w),
        ("preg_alloc_oh", pregs),
        ("disp_pdst", ptag_w),
        ("disp_fire", 1),
        ("disp_block_epoch", 16),
        ("disp_block_uid", 64),
        ("disp_block_bid", 64),
        ("disp_load_store_id", 32),
    )


@module(name="LinxCoreDispatchFrontend")
def build_dispatch_frontend(
    m: Circuit,
    *,
    dispatch_w: int = 4,
    iq_depth: int = 32,
    iq_w: int = 5,
    rob_depth: int = 64,
    rob_w: int = 6,
    pregs: int = 64,
    ptag_w: int = 6,
) -> None:
    clk = m.clock("clk")
    rst = m.reset("rst")

    can_run = m.input("can_run", width=1)
    commit_redirect = m.input("commit_redirect", width=1)
    f4_valid = m.input("f4_valid", width=1)
    f4_pc = m.input("f4_pc", width=64)
    f4_window = m.input("f4_window", width=64)
    f4_checkpoint_id = m.input("f4_checkpoint_id", width=6)
    f4_pkt_uid = m.input("f4_pkt_uid", width=64)
    block_head_in = m.input("block_head_in", width=1)
    block_epoch_in = m.input("block_epoch_in", width=16)
    block_uid_in = m.input("block_uid_in", width=64)
    block_bid_in = m.input("block_bid_in", width=64)
    brob_alloc_ready_i = m.input("brob_alloc_ready_i", width=1)
    brob_alloc_bid_i = m.input("brob_alloc_bid_i", width=64)
    lsid_alloc_base = m.input("lsid_alloc_base", width=32)
    rob_count = m.input("rob_count", width=rob_w + 1)
    ren_free_mask = m.input("ren_free_mask", width=pregs)
    iq_alu_valid_mask = m.input("iq_alu_valid_mask", width=iq_depth)
    iq_bru_valid_mask = m.input("iq_bru_valid_mask", width=iq_depth)
    iq_lsu_valid_mask = m.input("iq_lsu_valid_mask", width=iq_depth)
    iq_cmd_valid_mask = m.input("iq_cmd_valid_mask", width=iq_depth)

    c = m.const
    input_payloads = {
        "pc": f4_pc,
        "window": f4_window,
        "checkpoint_id": f4_checkpoint_id,
        "pkt_uid": f4_pkt_uid,
    }

    packet_regs: dict[str, dict[str, object]] = {}
    packet_vals: dict[str, dict[str, object]] = {}
    for stage in STAGE_NAMES:
        regs: dict[str, object] = {}
        vals: dict[str, object] = {}
        with m.scope(f"{stage}_pkt"):
            for field, width in _PACKET_FIELDS:
                reg = m.out(
                    field,
                    clk=clk,
                    rst=rst,
                    width=width,
                    init=c(0, width=width),
                    en=c(1, width=1),
                )
                regs[field] = reg
                vals[field] = reg.out()
        packet_regs[stage] = regs
        packet_vals[stage] = vals

    stage_decodes = {}
    for stage in STAGE_NAMES:
        stage_decodes[stage] = m.instance_auto(
            build_decode_stage,
            name=f"{stage}_decode",
            params={"dispatch_w": dispatch_w},
            f4_valid=packet_vals[stage]["valid"],
            f4_pc=packet_vals[stage]["pc"],
            f4_window=packet_vals[stage]["window"],
            f4_checkpoint_id=packet_vals[stage]["checkpoint_id"],
            f4_pkt_uid=packet_vals[stage]["pkt_uid"],
        )

    s2_decode = stage_decodes["s2"]
    packet_valid_s2 = packet_vals["s2"]["valid"] & (~s2_decode["dispatch_count"].__eq__(c(0, width=3)))

    dispatch_stage_args = {
        "can_run": can_run,
        "commit_redirect": commit_redirect,
        "f4_valid": packet_valid_s2,
        "block_head_in": block_head_in,
        "block_epoch_in": block_epoch_in,
        "block_uid_in": block_uid_in,
        "block_bid_in": block_bid_in,
        "brob_alloc_ready_i": brob_alloc_ready_i,
        "brob_alloc_bid_i": brob_alloc_bid_i,
        "lsid_alloc_base": lsid_alloc_base,
        "rob_count": rob_count,
        "disp_count": s2_decode["dispatch_count"],
        "ren_free_mask": ren_free_mask,
        "iq_alu_valid_mask": iq_alu_valid_mask,
        "iq_bru_valid_mask": iq_bru_valid_mask,
        "iq_lsu_valid_mask": iq_lsu_valid_mask,
        "iq_cmd_valid_mask": iq_cmd_valid_mask,
    }
    for slot in range(dispatch_w):
        dispatch_stage_args[f"disp_valid{slot}"] = s2_decode[f"valid{slot}"]
        dispatch_stage_args[f"disp_op{slot}"] = s2_decode[f"op{slot}"]
        dispatch_stage_args[f"disp_need_pdst{slot}"] = s2_decode[f"need_pdst{slot}"]
        dispatch_stage_args[f"disp_is_bstart{slot}"] = s2_decode[f"is_bstart{slot}"]

    dispatch_stage = m.new(
        build_dispatch_stage,
        name="dispatch_stage",
        bind=dispatch_stage_args,
        params={
            "dispatch_w": dispatch_w,
            "iq_depth": iq_depth,
            "iq_w": iq_w,
            "rob_depth": rob_depth,
            "rob_w": rob_w,
            "pregs": pregs,
            "ptag_w": ptag_w,
        },
    ).outputs

    dispatch_ready_s2 = dispatch_stage["frontend_ready"]
    move_s2 = packet_vals["s2"]["valid"] & dispatch_ready_s2
    s2_ready = (~packet_vals["s2"]["valid"]) | dispatch_ready_s2
    s1_ready = (~packet_vals["s1"]["valid"]) | s2_ready
    d3_ready = (~packet_vals["d3"]["valid"]) | s1_ready
    d2_ready = (~packet_vals["d2"]["valid"]) | d3_ready
    d1_ready = (~packet_vals["d1"]["valid"]) | d2_ready

    accept_input = (~commit_redirect) & d1_ready & f4_valid
    move_d1 = packet_vals["d1"]["valid"] & d2_ready
    move_d2 = packet_vals["d2"]["valid"] & d3_ready
    move_d3 = packet_vals["d3"]["valid"] & s1_ready
    move_s1 = packet_vals["s1"]["valid"] & s2_ready

    packet_regs["d1"]["valid"].set(
        commit_redirect._select_internal(
            c(0, width=1),
            accept_input | (packet_vals["d1"]["valid"] & (~d2_ready)),
        )
    )
    packet_regs["d2"]["valid"].set(
        commit_redirect._select_internal(
            c(0, width=1),
            move_d1 | (packet_vals["d2"]["valid"] & (~d3_ready)),
        )
    )
    packet_regs["d3"]["valid"].set(
        commit_redirect._select_internal(
            c(0, width=1),
            move_d2 | (packet_vals["d3"]["valid"] & (~s1_ready)),
        )
    )
    packet_regs["s1"]["valid"].set(
        commit_redirect._select_internal(
            c(0, width=1),
            move_d3 | (packet_vals["s1"]["valid"] & (~s2_ready)),
        )
    )
    packet_regs["s2"]["valid"].set(
        commit_redirect._select_internal(
            c(0, width=1),
            move_s1 | (packet_vals["s2"]["valid"] & (~dispatch_ready_s2)),
        )
    )

    for field, _width in _PACKET_PAYLOAD_FIELDS:
        packet_regs["d1"][field].set(input_payloads[field], when=accept_input)
        packet_regs["d2"][field].set(packet_vals["d1"][field], when=move_d1)
        packet_regs["d3"][field].set(packet_vals["d2"][field], when=move_d2)
        packet_regs["s1"][field].set(packet_vals["d3"][field], when=move_d3)
        packet_regs["s2"][field].set(packet_vals["s1"][field], when=move_s1)

    m.output("dispatch_count", s2_decode["dispatch_count"])
    m.output("dec_op", s2_decode["op0"])

    stage_ready_map = {
        "d1": d2_ready,
        "d2": d3_ready,
        "d3": s1_ready,
        "s1": s2_ready,
        "s2": dispatch_ready_s2,
    }
    stage_stall_cause_map = {
        "d1": c(1, width=8),
        "d2": c(1, width=8),
        "d3": c(1, width=8),
        "s1": c(1, width=8),
        "s2": c(2, width=8),
    }
    stage_valid_mask_packs = []
    stage_pc_packs = []
    stage_uid_packs = []
    stage_stall_mask_packs = []
    stage_stall_cause_packs = []
    for stage in STAGE_NAMES:
        stage_decode = stage_decodes[stage]
        stage_stall = packet_vals[stage]["valid"] & (~stage_ready_map[stage])
        stage_valids = []
        stage_pcs = []
        stage_uids = []
        stage_stalls = []
        stage_stall_causes = []
        for slot in range(dispatch_w):
            slot_valid = stage_decode[f"valid{slot}"]
            slot_stall = slot_valid & stage_stall
            m.output(f"probe_{stage}_valid_{slot}", slot_valid)
            m.output(f"probe_{stage}_pc_{slot}", stage_decode[f"pc{slot}"])
            m.output(f"probe_{stage}_uid_{slot}", stage_decode[f"uop_uid{slot}"])
            m.output(f"probe_{stage}_stall_{slot}", slot_stall)
            m.output(
                f"probe_{stage}_stall_cause_{slot}",
                slot_stall._select_internal(stage_stall_cause_map[stage], c(0, width=8)),
            )
            stage_valids.append(slot_valid)
            stage_pcs.append(stage_decode[f"pc{slot}"])
            stage_uids.append(stage_decode[f"uop_uid{slot}"])
            stage_stalls.append(slot_stall)
            stage_stall_causes.append(
                slot_stall._select_internal(stage_stall_cause_map[stage], c(0, width=8))
            )
        stage_valid_mask_pack = _pack_values(m, stage_valids)
        stage_pc_pack = _pack_values(m, stage_pcs)
        stage_uid_pack = _pack_values(m, stage_uids)
        stage_stall_mask_pack = _pack_values(m, stage_stalls)
        stage_stall_cause_pack = _pack_values(m, stage_stall_causes)
        stage_valid_mask_packs.append(stage_valid_mask_pack)
        stage_pc_packs.append(stage_pc_pack)
        stage_uid_packs.append(stage_uid_pack)
        stage_stall_mask_packs.append(stage_stall_mask_pack)
        stage_stall_cause_packs.append(stage_stall_cause_pack)
        m.output(f"stage_{stage}_valid_mask", stage_valid_mask_pack)
        m.output(f"stage_{stage}_pc_pack", stage_pc_pack)
        m.output(f"stage_{stage}_uid_pack", stage_uid_pack)
        m.output(f"stage_{stage}_stall_mask", stage_stall_mask_pack)
        m.output(f"stage_{stage}_stall_cause_pack", stage_stall_cause_pack)

    m.output("stage_valid_mask_pack", _pack_values(m, stage_valid_mask_packs))
    m.output("stage_pc_pack", _pack_values(m, stage_pc_packs))
    m.output("stage_uid_pack", _pack_values(m, stage_uid_packs))
    m.output("stage_stall_mask_pack", _pack_values(m, stage_stall_mask_packs))
    m.output("stage_stall_cause_pack", _pack_values(m, stage_stall_cause_packs))

    for name in (
        "rob_space_ok",
        "iq_alloc_ok",
        "preg_alloc_ok",
        "bid_alloc_ok",
        "mem_disp_count",
        "lsid_alloc_next",
        "disp_alloc_mask",
        "brob_alloc_fire",
        "dispatch_fire",
    ):
        m.output(name, dispatch_stage[name])
    m.output("frontend_ready", (~commit_redirect) & d1_ready)

    dispatch_specs = dispatch_slot_field_specs(iq_w=iq_w, ptag_w=ptag_w, pregs=pregs)
    decode_slot_packs = []
    dispatch_slot_packs = []
    for slot in range(dispatch_w):
        decode_values = {}
        for name, _width in DECODE_SLOT_FIELD_SPECS:
            decode_values[name] = s2_decode[f"{name}{slot}"]
        decode_slot_packs.append(
            _pack_fields(
                m,
                DECODE_SLOT_FIELD_SPECS,
                decode_values,
            )
        )
        dispatch_values = {}
        for name, _width in dispatch_specs:
            dispatch_values[name] = dispatch_stage[f"{name}{slot}"]
        dispatch_slot_packs.append(
            _pack_fields(
                m,
                dispatch_specs,
                dispatch_values,
            )
        )
    m.output("decode_pack", _pack_values(m, decode_slot_packs))
    m.output("dispatch_pack", _pack_values(m, dispatch_slot_packs))
