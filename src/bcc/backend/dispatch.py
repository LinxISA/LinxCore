from __future__ import annotations

from types import SimpleNamespace

from pycircuit import Circuit, module

from common.isa import (
    OP_BIOR,
    OP_BLOAD,
    OP_BSTART_STD_CALL,
    OP_BSTART_STD_COND,
    OP_BSTART_STD_DIRECT,
    OP_BSTART_STD_FALL,
    OP_BSTORE,
    OP_BTEXT,
    OP_C_BSTART_COND,
    OP_C_BSTART_DIRECT,
    OP_C_BSTART_STD,
    OP_C_BSTOP,
    OP_C_SETRET,
    OP_C_SETC_EQ,
    OP_C_SETC_NE,
    OP_C_SETC_TGT,
    OP_SETC_AND,
    OP_SETC_ANDI,
    OP_SETC_EQ,
    OP_SETC_EQI,
    OP_SETC_GE,
    OP_SETC_GEI,
    OP_SETC_GEU,
    OP_SETC_GEUI,
    OP_SETC_LT,
    OP_SETC_LTI,
    OP_SETC_LTU,
    OP_SETC_LTUI,
    OP_SETC_NE,
    OP_SETC_NEI,
    OP_SETC_OR,
    OP_SETC_ORI,
    OP_FENTRY,
    OP_FEXIT,
    OP_FRET_RA,
    OP_FRET_STK,
)
from .lsu import is_load_op, is_store_op
from .rob import is_macro_op


def _op_is(m: Circuit, op, *codes: int):
    c = m.const
    v = c(0, width=1)
    for code in codes:
        v = v | op.eq(c(code, width=12))
    return v


def classify_dispatch_target(op, op_is):
    is_macro = is_macro_op(op, op_is)
    is_mem = is_load_op(op, op_is) | is_store_op(op, op_is)
    is_boundary_d2 = op_is(
        op,
        OP_C_BSTART_STD,
        OP_C_BSTART_COND,
        OP_C_BSTART_DIRECT,
        OP_C_BSTOP,
        OP_BSTART_STD_FALL,
        OP_BSTART_STD_DIRECT,
        OP_BSTART_STD_COND,
        OP_BSTART_STD_CALL,
    )
    is_bru = op_is(
        op,
        OP_C_SETRET,
        OP_C_SETC_EQ,
        OP_C_SETC_NE,
        OP_C_SETC_TGT,
        OP_SETC_EQ,
        OP_SETC_NE,
        OP_SETC_AND,
        OP_SETC_OR,
        OP_SETC_LT,
        OP_SETC_LTU,
        OP_SETC_GE,
        OP_SETC_GEU,
        OP_SETC_EQI,
        OP_SETC_NEI,
        OP_SETC_ANDI,
        OP_SETC_ORI,
        OP_SETC_LTI,
        OP_SETC_GEI,
        OP_SETC_LTUI,
        OP_SETC_GEUI,
    )
    is_cmd = op_is(
        op,
        OP_BTEXT,
        OP_BIOR,
        OP_BLOAD,
        OP_BSTORE,
    )
    to_lsu = is_mem
    to_d2 = (~to_lsu) & is_boundary_d2 & (~is_macro)
    to_bru = (~to_lsu) & (~to_d2) & is_bru & (~is_macro)
    to_cmd = (~to_lsu) & (~to_d2) & (~to_bru) & is_cmd & (~is_macro)
    to_alu = (~to_lsu) & (~to_d2) & (~to_bru) & (~to_cmd) & (~is_macro)
    return to_alu, to_bru, to_lsu, to_d2, to_cmd


def allocate_iq_slots_from_valids(
    *,
    m,
    p,
    consts,
    disp_valids,
    disp_to_alu,
    disp_to_bru,
    disp_to_lsu,
    disp_to_cmd,
    iq_alu_valids,
    iq_bru_valids,
    iq_lsu_valids,
    iq_cmd_valids,
):
    c = m.const
    alu_alloc_valids = []
    alu_alloc_idxs = []
    bru_alloc_valids = []
    bru_alloc_idxs = []
    lsu_alloc_valids = []
    lsu_alloc_idxs = []
    cmd_alloc_valids = []
    cmd_alloc_idxs = []
    for slot in range(p.dispatch_w):
        req_alu = disp_valids[slot] & disp_to_alu[slot]
        v = consts.zero1
        idx = c(0, width=p.iq_w)
        for i in range(p.iq_depth):
            cidx = c(i, width=p.iq_w)
            free = ~iq_alu_valids[i]
            exclude = consts.zero1
            for prev in range(slot):
                prev_req = disp_valids[prev] & disp_to_alu[prev]
                exclude = exclude | (prev_req & alu_alloc_valids[prev] & alu_alloc_idxs[prev].eq(cidx))
            cand = req_alu & free & (~exclude)
            take = cand & (~v)
            v = take.select(consts.one1, v)
            idx = take.select(cidx, idx)
        alu_alloc_valids.append(v)
        alu_alloc_idxs.append(idx)

        req_bru = disp_valids[slot] & disp_to_bru[slot]
        v = consts.zero1
        idx = c(0, width=p.iq_w)
        for i in range(p.iq_depth):
            cidx = c(i, width=p.iq_w)
            free = ~iq_bru_valids[i]
            exclude = consts.zero1
            for prev in range(slot):
                prev_req = disp_valids[prev] & disp_to_bru[prev]
                exclude = exclude | (prev_req & bru_alloc_valids[prev] & bru_alloc_idxs[prev].eq(cidx))
            cand = req_bru & free & (~exclude)
            take = cand & (~v)
            v = take.select(consts.one1, v)
            idx = take.select(cidx, idx)
        bru_alloc_valids.append(v)
        bru_alloc_idxs.append(idx)

        req_lsu = disp_valids[slot] & disp_to_lsu[slot]
        v = consts.zero1
        idx = c(0, width=p.iq_w)
        for i in range(p.iq_depth):
            cidx = c(i, width=p.iq_w)
            free = ~iq_lsu_valids[i]
            exclude = consts.zero1
            for prev in range(slot):
                prev_req = disp_valids[prev] & disp_to_lsu[prev]
                exclude = exclude | (prev_req & lsu_alloc_valids[prev] & lsu_alloc_idxs[prev].eq(cidx))
            cand = req_lsu & free & (~exclude)
            take = cand & (~v)
            v = take.select(consts.one1, v)
            idx = take.select(cidx, idx)
        lsu_alloc_valids.append(v)
        lsu_alloc_idxs.append(idx)

        req_cmd = disp_valids[slot] & disp_to_cmd[slot]
        v = consts.zero1
        idx = c(0, width=p.iq_w)
        for i in range(p.iq_depth):
            cidx = c(i, width=p.iq_w)
            free = ~iq_cmd_valids[i]
            exclude = consts.zero1
            for prev in range(slot):
                prev_req = disp_valids[prev] & disp_to_cmd[prev]
                exclude = exclude | (prev_req & cmd_alloc_valids[prev] & cmd_alloc_idxs[prev].eq(cidx))
            cand = req_cmd & free & (~exclude)
            take = cand & (~v)
            v = take.select(consts.one1, v)
            idx = take.select(cidx, idx)
        cmd_alloc_valids.append(v)
        cmd_alloc_idxs.append(idx)

    alu_alloc_ok = consts.one1
    bru_alloc_ok = consts.one1
    lsu_alloc_ok = consts.one1
    cmd_alloc_ok = consts.one1
    for slot in range(p.dispatch_w):
        req_alu = disp_valids[slot] & disp_to_alu[slot]
        req_bru = disp_valids[slot] & disp_to_bru[slot]
        req_lsu = disp_valids[slot] & disp_to_lsu[slot]
        req_cmd = disp_valids[slot] & disp_to_cmd[slot]
        alu_alloc_ok = alu_alloc_ok & ((~req_alu) | alu_alloc_valids[slot])
        bru_alloc_ok = bru_alloc_ok & ((~req_bru) | bru_alloc_valids[slot])
        lsu_alloc_ok = lsu_alloc_ok & ((~req_lsu) | lsu_alloc_valids[slot])
        cmd_alloc_ok = cmd_alloc_ok & ((~req_cmd) | cmd_alloc_valids[slot])
    iq_alloc_ok = alu_alloc_ok & bru_alloc_ok & lsu_alloc_ok & cmd_alloc_ok
    return (
        alu_alloc_valids,
        alu_alloc_idxs,
        bru_alloc_valids,
        bru_alloc_idxs,
        lsu_alloc_valids,
        lsu_alloc_idxs,
        cmd_alloc_valids,
        cmd_alloc_idxs,
        iq_alloc_ok,
    )


def allocate_iq_slots(*, m, p, consts, disp_valids, disp_to_alu, disp_to_bru, disp_to_lsu, disp_to_cmd, iq_alu, iq_bru, iq_lsu, iq_cmd):
    return allocate_iq_slots_from_valids(
        m=m,
        p=p,
        consts=consts,
        disp_valids=disp_valids,
        disp_to_alu=disp_to_alu,
        disp_to_bru=disp_to_bru,
        disp_to_lsu=disp_to_lsu,
        disp_to_cmd=disp_to_cmd,
        iq_alu_valids=[iq_alu.valid[i].out() for i in range(p.iq_depth)],
        iq_bru_valids=[iq_bru.valid[i].out() for i in range(p.iq_depth)],
        iq_lsu_valids=[iq_lsu.valid[i].out() for i in range(p.iq_depth)],
        iq_cmd_valids=[iq_cmd.valid[i].out() for i in range(p.iq_depth)],
    )


@module(name="LinxCoreDispatchStage")
def build_dispatch_stage(
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
    c = m.const

    can_run = m.input("can_run", width=1)
    commit_redirect = m.input("commit_redirect", width=1)
    f4_valid = m.input("f4_valid", width=1)
    block_epoch_in = m.input("block_epoch_in", width=16)
    block_uid_in = m.input("block_uid_in", width=64)
    block_bid_in = m.input("block_bid_in", width=64)
    lsid_alloc_base = m.input("lsid_alloc_base", width=32)
    rob_count = m.input("rob_count", width=rob_w + 1)
    disp_count = m.input("disp_count", width=3)
    ren_free_mask = m.input("ren_free_mask", width=pregs)

    disp_valids = []
    disp_ops = []
    disp_need_pdst = []
    for slot in range(dispatch_w):
        disp_valids.append(m.input(f"disp_valid{slot}", width=1))
        disp_ops.append(m.input(f"disp_op{slot}", width=12))
        disp_need_pdst.append(m.input(f"disp_need_pdst{slot}", width=1))

    iq_alu_valids = []
    iq_bru_valids = []
    iq_lsu_valids = []
    iq_cmd_valids = []
    for i in range(iq_depth):
        iq_alu_valids.append(m.input(f"iq_alu_valid{i}", width=1))
        iq_bru_valids.append(m.input(f"iq_bru_valid{i}", width=1))
        iq_lsu_valids.append(m.input(f"iq_lsu_valid{i}", width=1))
        iq_cmd_valids.append(m.input(f"iq_cmd_valid{i}", width=1))

    # ROB space check: rob.count + disp_count <= rob_depth.
    rob_cnt_after = rob_count + disp_count.zext(width=rob_w + 1)
    rob_space_ok = rob_cnt_after.ult(c(rob_depth + 1, width=rob_w + 1))

    # IQ routing/classification.
    disp_to_alu = []
    disp_to_bru = []
    disp_to_lsu = []
    disp_to_d2 = []
    disp_to_cmd = []
    for slot in range(dispatch_w):
        op = disp_ops[slot]
        op_is = lambda x, *codes: _op_is(m, x, *codes)
        to_alu, to_bru, to_lsu, to_d2, to_cmd = classify_dispatch_target(op, op_is)
        disp_to_alu.append(to_alu)
        disp_to_bru.append(to_bru)
        disp_to_lsu.append(to_lsu)
        disp_to_d2.append(to_d2)
        disp_to_cmd.append(to_cmd)

    # IQ slot allocation.
    p_tmp = SimpleNamespace(dispatch_w=dispatch_w, iq_depth=iq_depth, iq_w=iq_w)
    consts_tmp = SimpleNamespace(zero1=c(0, width=1), one1=c(1, width=1))

    (
        alu_alloc_valids,
        alu_alloc_idxs,
        bru_alloc_valids,
        bru_alloc_idxs,
        lsu_alloc_valids,
        lsu_alloc_idxs,
        cmd_alloc_valids,
        cmd_alloc_idxs,
        iq_alloc_ok,
    ) = allocate_iq_slots_from_valids(
        m=m,
        p=p_tmp,
        consts=consts_tmp,
        disp_valids=disp_valids,
        disp_to_alu=disp_to_alu,
        disp_to_bru=disp_to_bru,
        disp_to_lsu=disp_to_lsu,
        disp_to_cmd=disp_to_cmd,
        iq_alu_valids=iq_alu_valids,
        iq_bru_valids=iq_bru_valids,
        iq_lsu_valids=iq_lsu_valids,
        iq_cmd_valids=iq_cmd_valids,
    )

    # Physical-register allocation.
    preg_alloc_valids = []
    preg_alloc_tags = []
    preg_alloc_onehots = []
    free_mask_stage = ren_free_mask
    for slot in range(dispatch_w):
        req = disp_valids[slot] & disp_need_pdst[slot]
        v = c(0, width=1)
        tag = c(0, width=ptag_w)
        oh = c(0, width=pregs)
        for i in range(pregs):
            ii = c(i, width=ptag_w)
            onehot = c(1 << i, width=pregs)
            cand = req & (~v) & free_mask_stage[i]
            v = cand.select(c(1, width=1), v)
            tag = cand.select(ii, tag)
            oh = cand.select(onehot, oh)
        free_mask_stage = req.select(free_mask_stage & (~oh), free_mask_stage)
        preg_alloc_valids.append(v)
        preg_alloc_tags.append(tag)
        preg_alloc_onehots.append(oh)

    preg_alloc_ok = c(1, width=1)
    disp_alloc_mask = c(0, width=pregs)
    disp_pdsts = []
    for slot in range(dispatch_w):
        req = disp_valids[slot] & disp_need_pdst[slot]
        preg_alloc_ok = preg_alloc_ok & ((~req) | preg_alloc_valids[slot])
        pdst = req.select(preg_alloc_tags[slot], c(0, width=ptag_w))
        oh = req.select(preg_alloc_onehots[slot], c(0, width=pregs))
        disp_pdsts.append(pdst)
        disp_alloc_mask = disp_alloc_mask | oh

    frontend_ready = can_run & (~commit_redirect) & rob_space_ok & iq_alloc_ok & preg_alloc_ok
    dispatch_fire = frontend_ready & f4_valid

    mem_disp_count = c(0, width=3)
    disp_load_store_ids = []
    lsid_next_live = lsid_alloc_base
    for slot in range(dispatch_w):
        is_mem = disp_valids[slot] & disp_to_lsu[slot]
        disp_load_store_ids.append(lsid_next_live)
        lsid_next_live = is_mem.select(lsid_next_live + c(1, width=32), lsid_next_live)
        mem_disp_count = mem_disp_count + is_mem.zext(width=3)
    lsid_alloc_next = dispatch_fire.select(lsid_next_live, lsid_alloc_base)

    m.output("rob_space_ok", rob_space_ok)
    m.output("iq_alloc_ok", iq_alloc_ok)
    m.output("preg_alloc_ok", preg_alloc_ok)
    m.output("frontend_ready", frontend_ready)
    m.output("dispatch_fire", dispatch_fire)
    m.output("mem_disp_count", mem_disp_count)
    m.output("lsid_alloc_next", lsid_alloc_next)
    m.output("disp_alloc_mask", disp_alloc_mask)

    for slot in range(dispatch_w):
        m.output(f"to_alu{slot}", disp_to_alu[slot])
        m.output(f"to_bru{slot}", disp_to_bru[slot])
        m.output(f"to_lsu{slot}", disp_to_lsu[slot])
        m.output(f"to_d2{slot}", disp_to_d2[slot])
        m.output(f"to_cmd{slot}", disp_to_cmd[slot])
        m.output(f"alu_alloc_valid{slot}", alu_alloc_valids[slot])
        m.output(f"alu_alloc_idx{slot}", alu_alloc_idxs[slot])
        m.output(f"bru_alloc_valid{slot}", bru_alloc_valids[slot])
        m.output(f"bru_alloc_idx{slot}", bru_alloc_idxs[slot])
        m.output(f"lsu_alloc_valid{slot}", lsu_alloc_valids[slot])
        m.output(f"lsu_alloc_idx{slot}", lsu_alloc_idxs[slot])
        m.output(f"cmd_alloc_valid{slot}", cmd_alloc_valids[slot])
        m.output(f"cmd_alloc_idx{slot}", cmd_alloc_idxs[slot])
        m.output(f"preg_alloc_valid{slot}", preg_alloc_valids[slot])
        m.output(f"preg_alloc_tag{slot}", preg_alloc_tags[slot])
        m.output(f"preg_alloc_oh{slot}", preg_alloc_onehots[slot])
        m.output(f"disp_pdst{slot}", disp_pdsts[slot])
        m.output(f"disp_fire{slot}", dispatch_fire & disp_valids[slot])
        m.output(f"disp_block_epoch{slot}", block_epoch_in)
        m.output(f"disp_block_uid{slot}", block_uid_in)
        m.output(f"disp_block_bid{slot}", block_bid_in)
        m.output(f"disp_load_store_id{slot}", disp_load_store_ids[slot])
