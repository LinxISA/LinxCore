from __future__ import annotations

from pycircuit import Circuit, module

from common.isa import BK_COND, BK_RET
from common.util import make_consts


@module(name="LinxCoreBruRecoveryValidateStage")
def build_bru_recovery_validate_stage(
    m: Circuit,
    *,
    pcb_depth: int = 8,
    pcb_w: int = 3,
    rob_w: int = 6,
) -> None:
    c = m.const

    bru_fire = m.input("bru_fire", width=1)
    bru_is_setc_cond = m.input("bru_is_setc_cond", width=1)
    bru_target = m.input("bru_target", width=64)
    bru_actual_take = m.input("bru_actual_take", width=1)
    bru_checkpoint = m.input("bru_checkpoint", width=6)
    bru_epoch = m.input("bru_epoch", width=16)
    bru_rob = m.input("bru_rob", width=rob_w)
    state_br_kind = m.input("state_br_kind", width=3)
    state_br_epoch = m.input("state_br_epoch", width=16)
    state_br_pred_take = m.input("state_br_pred_take", width=1)

    pcb_valid = []
    pcb_pc = []
    pcb_is_bstart = []
    for i in range(pcb_depth):
        pcb_valid.append(m.input(f"pcb_valid{i}", width=1))
        pcb_pc.append(m.input(f"pcb_pc{i}", width=64))
        pcb_is_bstart.append(m.input(f"pcb_is_bstart{i}", width=1))

    bru_target_known = c(0, width=1)
    bru_target_is_bstart = c(0, width=1)
    for i in range(pcb_depth):
        pc_hit = pcb_valid[i] & pcb_pc[i].__eq__(bru_target)
        hit = pc_hit & pcb_is_bstart[i]
        bru_target_known = pc_hit._select_internal(c(1, width=1), bru_target_known)
        bru_target_is_bstart = hit._select_internal(c(1, width=1), bru_target_is_bstart)

    is_cond_or_ret = state_br_kind.__eq__(c(BK_COND, width=3)) | state_br_kind.__eq__(c(BK_RET, width=3))
    bru_validate_en = is_cond_or_ret & bru_target_known & bru_epoch.__eq__(state_br_epoch)
    bru_mismatch = bru_fire & bru_is_setc_cond & bru_validate_en & (~bru_actual_take.__eq__(state_br_pred_take))
    bru_target_ok = (~bru_target_known) | bru_target_is_bstart
    bru_corr_set = bru_mismatch & bru_target_ok
    bru_fault_set = bru_mismatch & bru_target_known & (~bru_target_is_bstart)

    m.output("bru_mismatch_evt", bru_mismatch)
    m.output("bru_corr_set", bru_corr_set)
    m.output("bru_corr_take", bru_actual_take)
    m.output("bru_corr_target", bru_target)
    m.output("bru_corr_checkpoint_id", bru_checkpoint)
    m.output("bru_corr_epoch", bru_epoch)
    m.output("bru_fault_set", bru_fault_set)
    m.output("bru_fault_rob", bru_rob)


@module(name="LinxCoreBruRecoveryOwner")
def build_bru_recovery_owner(
    m: Circuit,
    *,
    dispatch_w: int = 4,
    pcb_depth: int = 8,
    pcb_w: int = 3,
    rob_w: int = 6,
) -> None:
    clk = m.clock("clk")
    rst = m.reset("rst")
    c = m.const
    consts = make_consts(m)

    f4_valid = m.input("f4_valid", width=1)
    bru_fire = m.input("bru_fire", width=1)
    bru_is_setc_cond = m.input("bru_is_setc_cond", width=1)
    bru_target = m.input("bru_target", width=64)
    bru_actual_take = m.input("bru_actual_take", width=1)
    bru_checkpoint = m.input("bru_checkpoint", width=6)
    bru_epoch = m.input("bru_epoch", width=16)
    bru_rob = m.input("bru_rob", width=rob_w)
    state_br_kind = m.input("state_br_kind", width=3)
    state_br_epoch = m.input("state_br_epoch", width=16)
    state_br_pred_take = m.input("state_br_pred_take", width=1)

    disp_valid = []
    disp_pc = []
    disp_is_bstart = []
    for slot in range(dispatch_w):
        disp_valid.append(m.input(f"disp_valid{slot}", width=1))
        disp_pc.append(m.input(f"disp_pc{slot}", width=64))
        disp_is_bstart.append(m.input(f"disp_is_bstart{slot}", width=1))

    with m.scope("pcbuf"):
        pcb_tail = m.out("tail", clk=clk, rst=rst, width=pcb_w, init=c(0, width=pcb_w), en=consts.one1)
        pcb_valid = []
        pcb_pc = []
        pcb_is_bstart = []
        for i in range(pcb_depth):
            pcb_valid.append(m.out(f"v{i}", clk=clk, rst=rst, width=1, init=consts.zero1, en=consts.one1))
            pcb_pc.append(m.out(f"pc{i}", clk=clk, rst=rst, width=64, init=consts.zero64, en=consts.one1))
            pcb_is_bstart.append(m.out(f"b{i}", clk=clk, rst=rst, width=1, init=consts.zero1, en=consts.one1))

    pcb_wr_valids = []
    pcb_wr_idxs = []
    pcb_wr_pcs = []
    pcb_tail_tmp = pcb_tail.out()
    for slot in range(dispatch_w):
        wr = f4_valid & disp_valid[slot] & disp_is_bstart[slot]
        pcb_wr_valids.append(wr)
        pcb_wr_idxs.append(pcb_tail_tmp)
        pcb_wr_pcs.append(disp_pc[slot])
        pcb_tail_tmp = wr._select_internal(pcb_tail_tmp + c(1, width=pcb_w), pcb_tail_tmp)
    pcb_tail.set(pcb_tail_tmp)

    validate_args = {
        "bru_fire": bru_fire,
        "bru_is_setc_cond": bru_is_setc_cond,
        "bru_target": bru_target,
        "bru_actual_take": bru_actual_take,
        "bru_checkpoint": bru_checkpoint,
        "bru_epoch": bru_epoch,
        "bru_rob": bru_rob,
        "state_br_kind": state_br_kind,
        "state_br_epoch": state_br_epoch,
        "state_br_pred_take": state_br_pred_take,
    }
    for i in range(pcb_depth):
        idx = c(i, width=pcb_w)
        v_next = pcb_valid[i].out()
        pc_next = pcb_pc[i].out()
        isb_next = pcb_is_bstart[i].out()
        for slot in range(dispatch_w):
            hit = pcb_wr_valids[slot] & pcb_wr_idxs[slot].__eq__(idx)
            v_next = hit._select_internal(consts.one1, v_next)
            pc_next = hit._select_internal(pcb_wr_pcs[slot], pc_next)
            isb_next = hit._select_internal(consts.one1, isb_next)
        pcb_valid[i].set(v_next)
        pcb_pc[i].set(pc_next)
        pcb_is_bstart[i].set(isb_next)
        validate_args[f"pcb_valid{i}"] = pcb_valid[i].out()
        validate_args[f"pcb_pc{i}"] = pcb_pc[i].out()
        validate_args[f"pcb_is_bstart{i}"] = pcb_is_bstart[i].out()

    bru_recovery = m.new(
        build_bru_recovery_validate_stage,
        name="bru_recovery_validate",
        bind=validate_args,
        params={
            "pcb_depth": pcb_depth,
            "pcb_w": pcb_w,
            "rob_w": rob_w,
        },
    ).outputs
    m.output("bru_mismatch_evt", bru_recovery["bru_mismatch_evt"])
    m.output("bru_corr_set", bru_recovery["bru_corr_set"])
    m.output("bru_corr_take", bru_recovery["bru_corr_take"])
    m.output("bru_corr_target", bru_recovery["bru_corr_target"])
    m.output("bru_corr_checkpoint_id", bru_recovery["bru_corr_checkpoint_id"])
    m.output("bru_corr_epoch", bru_recovery["bru_corr_epoch"])
    m.output("bru_fault_set", bru_recovery["bru_fault_set"])
    m.output("bru_fault_rob", bru_recovery["bru_fault_rob"])


@module(name="LinxCoreLsuViolationDetectStage")
def build_lsu_violation_detect_stage(
    m: Circuit,
    *,
    issue_w: int = 4,
    rob_depth: int = 64,
    rob_w: int = 6,
) -> None:
    c = m.const

    replay_pending = m.input("replay_pending", width=1)
    sub_head = m.input("sub_head", width=rob_w)

    store_fires = []
    store_robs = []
    store_addrs = []
    for slot in range(issue_w):
        store_fires.append(m.input(f"store_fire{slot}", width=1))
        store_robs.append(m.input(f"store_rob{slot}", width=rob_w))
        store_addrs.append(m.input(f"store_addr{slot}", width=64))

    rob_valid = []
    rob_done = []
    rob_is_load = []
    rob_load_addr = []
    rob_pc = []
    for i in range(rob_depth):
        rob_valid.append(m.input(f"rob_valid{i}", width=1))
        rob_done.append(m.input(f"rob_done{i}", width=1))
        rob_is_load.append(m.input(f"rob_is_load{i}", width=1))
        rob_load_addr.append(m.input(f"rob_load_addr{i}", width=64))
        rob_pc.append(m.input(f"rob_pc{i}", width=64))

    replay_set = c(0, width=1)
    replay_set_store_rob = c(0, width=rob_w)
    replay_set_pc = c(0, width=64)
    max_age = c((1 << rob_w) - 1, width=rob_w)

    for slot in range(issue_w):
        st_fire = store_fires[slot]
        st_rob = store_robs[slot]
        st_addr = store_addrs[slot]
        st_dist = st_rob + sub_head

        hit = c(0, width=1)
        hit_pc = c(0, width=64)
        hit_age = max_age
        for i in range(rob_depth):
            idx = c(i, width=rob_w)
            dist = idx + sub_head
            younger = st_dist.ult(dist)
            ld_done = rob_valid[i] & rob_done[i] & rob_is_load[i]
            addr_match = rob_load_addr[i].__eq__(st_addr)
            cand = st_fire & ld_done & younger & addr_match
            better = (~hit) | dist.ult(hit_age)
            take = cand & better
            hit = take._select_internal(c(1, width=1), hit)
            hit_age = take._select_internal(dist, hit_age)
            hit_pc = take._select_internal(rob_pc[i], hit_pc)

        set_this = hit & (~replay_pending) & (~replay_set)
        replay_set = set_this._select_internal(c(1, width=1), replay_set)
        replay_set_store_rob = set_this._select_internal(st_rob, replay_set_store_rob)
        replay_set_pc = set_this._select_internal(hit_pc, replay_set_pc)

    m.output("replay_set", replay_set)
    m.output("replay_set_store_rob", replay_set_store_rob)
    m.output("replay_set_pc", replay_set_pc)
