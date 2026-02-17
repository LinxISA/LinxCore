from __future__ import annotations

from pycircuit import Circuit, module

from .helpers import mask_bit


def compose_wakeup_reason(*, m, p, wb_fire_has_dsts, lsu_forward_active, commit_redirect, dispatch_fire):
    c = m.const
    any_wakeup = c(0, width=1)
    for slot in range(p.issue_w):
        any_wakeup = any_wakeup | wb_fire_has_dsts[slot]
    wakeup_reason = c(0, width=8)
    wakeup_reason = any_wakeup.select(wakeup_reason | c(1 << 0, width=8), wakeup_reason)
    wakeup_reason = lsu_forward_active.select(wakeup_reason | c(1 << 1, width=8), wakeup_reason)
    wakeup_reason = commit_redirect.select(wakeup_reason | c(1 << 2, width=8), wakeup_reason)
    wakeup_reason = dispatch_fire.select(wakeup_reason | c(1 << 3, width=8), wakeup_reason)
    return wakeup_reason


def compose_replay_cause(*, m, lsu_block_lane0, issued_is_load, older_store_pending, lsu_violation_detected, replay_redirect_fire):
    c = m.const
    replay_cause = c(0, width=8)
    replay_cause = lsu_block_lane0.select(replay_cause | c(1 << 0, width=8), replay_cause)
    replay_cause = (issued_is_load & older_store_pending).select(replay_cause | c(1 << 1, width=8), replay_cause)
    replay_cause = lsu_violation_detected.select(replay_cause | c(1 << 2, width=8), replay_cause)
    replay_cause = replay_redirect_fire.select(replay_cause | c(1 << 3, width=8), replay_cause)
    return replay_cause


@module(name="LinxCoreHeadWaitStage")
def build_head_wait_stage(
    m: Circuit,
    *,
    iq_depth: int = 32,
    iq_w: int = 5,
    rob_w: int = 6,
    ptag_w: int = 6,
    pregs: int = 64,
) -> None:
    c = m.const
    ready_mask = m.input("ready_mask", width=pregs)
    head_idx = m.input("head_idx", width=rob_w)
    tag0 = c(0, width=ptag_w)

    lsu_valid = []
    lsu_rob = []
    lsu_srcl = []
    lsu_srcr = []
    lsu_srcp = []
    bru_valid = []
    bru_rob = []
    bru_srcl = []
    bru_srcr = []
    bru_srcp = []
    alu_valid = []
    alu_rob = []
    alu_srcl = []
    alu_srcr = []
    alu_srcp = []
    cmd_valid = []
    cmd_rob = []
    cmd_srcl = []
    cmd_srcr = []
    cmd_srcp = []
    for i in range(iq_depth):
        lsu_valid.append(m.input(f"lsu_valid{i}", width=1))
        lsu_rob.append(m.input(f"lsu_rob{i}", width=rob_w))
        lsu_srcl.append(m.input(f"lsu_srcl{i}", width=ptag_w))
        lsu_srcr.append(m.input(f"lsu_srcr{i}", width=ptag_w))
        lsu_srcp.append(m.input(f"lsu_srcp{i}", width=ptag_w))
        bru_valid.append(m.input(f"bru_valid{i}", width=1))
        bru_rob.append(m.input(f"bru_rob{i}", width=rob_w))
        bru_srcl.append(m.input(f"bru_srcl{i}", width=ptag_w))
        bru_srcr.append(m.input(f"bru_srcr{i}", width=ptag_w))
        bru_srcp.append(m.input(f"bru_srcp{i}", width=ptag_w))
        alu_valid.append(m.input(f"alu_valid{i}", width=1))
        alu_rob.append(m.input(f"alu_rob{i}", width=rob_w))
        alu_srcl.append(m.input(f"alu_srcl{i}", width=ptag_w))
        alu_srcr.append(m.input(f"alu_srcr{i}", width=ptag_w))
        alu_srcp.append(m.input(f"alu_srcp{i}", width=ptag_w))
        cmd_valid.append(m.input(f"cmd_valid{i}", width=1))
        cmd_rob.append(m.input(f"cmd_rob{i}", width=rob_w))
        cmd_srcl.append(m.input(f"cmd_srcl{i}", width=ptag_w))
        cmd_srcr.append(m.input(f"cmd_srcr{i}", width=ptag_w))
        cmd_srcp.append(m.input(f"cmd_srcp{i}", width=ptag_w))

    head_wait_hit = c(0, width=1)
    head_wait_kind = c(0, width=2)
    head_wait_sl = tag0
    head_wait_sr = tag0
    head_wait_sp = tag0
    for i in range(iq_depth):
        hit_lsu = lsu_valid[i] & lsu_rob[i].eq(head_idx)
        hit_bru = bru_valid[i] & bru_rob[i].eq(head_idx)
        hit_alu = alu_valid[i] & alu_rob[i].eq(head_idx)
        hit_cmd = cmd_valid[i] & cmd_rob[i].eq(head_idx)
        head_wait_hit = (hit_lsu | hit_bru | hit_alu | hit_cmd).select(c(1, width=1), head_wait_hit)
        head_wait_kind = hit_lsu.select(c(3, width=2), head_wait_kind)
        head_wait_kind = hit_bru.select(c(2, width=2), head_wait_kind)
        head_wait_kind = hit_alu.select(c(1, width=2), head_wait_kind)
        head_wait_kind = hit_cmd.select(c(0, width=2), head_wait_kind)
        head_wait_sl = hit_lsu.select(lsu_srcl[i], head_wait_sl)
        head_wait_sr = hit_lsu.select(lsu_srcr[i], head_wait_sr)
        head_wait_sp = hit_lsu.select(lsu_srcp[i], head_wait_sp)
        head_wait_sl = hit_bru.select(bru_srcl[i], head_wait_sl)
        head_wait_sr = hit_bru.select(bru_srcr[i], head_wait_sr)
        head_wait_sp = hit_bru.select(bru_srcp[i], head_wait_sp)
        head_wait_sl = hit_alu.select(alu_srcl[i], head_wait_sl)
        head_wait_sr = hit_alu.select(alu_srcr[i], head_wait_sr)
        head_wait_sp = hit_alu.select(alu_srcp[i], head_wait_sp)
        head_wait_sl = hit_cmd.select(cmd_srcl[i], head_wait_sl)
        head_wait_sr = hit_cmd.select(cmd_srcr[i], head_wait_sr)
        head_wait_sp = hit_cmd.select(cmd_srcp[i], head_wait_sp)

    head_wait_sl_rdy = mask_bit(m, mask=ready_mask, idx=head_wait_sl, width=pregs)
    head_wait_sr_rdy = mask_bit(m, mask=ready_mask, idx=head_wait_sr, width=pregs)
    head_wait_sp_rdy = mask_bit(m, mask=ready_mask, idx=head_wait_sp, width=pregs)

    m.output("head_wait_hit", head_wait_hit)
    m.output("head_wait_kind", head_wait_kind)
    m.output("head_wait_sl", head_wait_sl)
    m.output("head_wait_sr", head_wait_sr)
    m.output("head_wait_sp", head_wait_sp)
    m.output("head_wait_sl_rdy", head_wait_sl_rdy)
    m.output("head_wait_sr_rdy", head_wait_sr_rdy)
    m.output("head_wait_sp_rdy", head_wait_sp_rdy)
