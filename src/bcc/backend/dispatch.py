from __future__ import annotations

from functools import partial

from pycircuit import Circuit, const, function, module, spec

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
    OP_SETRET,
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


@function
def _op_is(m: Circuit, op, *codes: int):
    c = m.const
    v = c(0, width=1)
    for code in codes:
        v = v | op.__eq__(c(code, width=12))
    return v


@function
def classify_dispatch_target(m: Circuit, op, op_is):
    is_macro = op_is(op, OP_FENTRY, OP_FEXIT, OP_FRET_RA, OP_FRET_STK)
    is_mem = is_load_op(m, op, op_is) | is_store_op(m, op, op_is)
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
        # SETRET is an immediate-only producer; resolve at dispatch (no IQ).
        OP_SETRET,
        OP_C_SETRET,
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


@const
def _dispatch_classify_vector(
    m: Circuit,
    *,
    dispatch_w: int,
):
    _ = m
    family = spec.module_family("dispatch_classify_family", module=build_dispatch_classify_stage)
    return family.vector(int(dispatch_w), name="dispatch_classify_vec")


@const
def _dispatch_iq_alloc_map(
    m: Circuit,
    *,
    dispatch_w: int,
    iq_depth: int,
    iq_w: int,
):
    _ = m
    family = spec.module_family(
        "dispatch_iq_alloc_family",
        module=build_dispatch_iq_alloc_stage,
        params={
            "dispatch_w": int(dispatch_w),
            "iq_depth": int(iq_depth),
            "iq_w": int(iq_w),
        },
    )
    return family.map(("alu", "bru", "lsu", "cmd"), name="dispatch_iq_alloc_map")


@const
def _dispatch_preg_stage_vector(
    m: Circuit,
    *,
    dispatch_w: int,
    pregs: int,
    ptag_w: int,
):
    _ = m
    family = spec.module_family(
        "dispatch_preg_stage_family",
        module=build_dispatch_preg_alloc_stage,
        params={
            "pregs": int(pregs),
            "ptag_w": int(ptag_w),
        },
    )
    return family.vector(int(dispatch_w), name="dispatch_preg_stage_vec")


@module(name="LinxCoreDispatchClassifyStage")
def build_dispatch_classify_stage(m: Circuit) -> None:
    op = m.input("op", width=12)
    op_is = partial(_op_is, m)
    to_alu, to_bru, to_lsu, to_d2, to_cmd = classify_dispatch_target(m, op, op_is)
    m.output("to_alu", to_alu)
    m.output("to_bru", to_bru)
    m.output("to_lsu", to_lsu)
    m.output("to_d2", to_d2)
    m.output("to_cmd", to_cmd)


@module(name="LinxCoreDispatchIqAllocStage")
def build_dispatch_iq_alloc_stage(
    m: Circuit,
    *,
    dispatch_w: int = 4,
    iq_depth: int = 32,
    iq_w: int = 5,
) -> None:
    c = m.const
    zero1 = c(0, width=1)
    one1 = c(1, width=1)

    disp_valids = [m.input(f"disp_valid{slot}", width=1) for slot in range(dispatch_w)]
    disp_targets = [m.input(f"disp_target{slot}", width=1) for slot in range(dispatch_w)]
    iq_valid_mask = m.input("iq_valid_mask", width=iq_depth)

    alloc_valids = []
    alloc_idxs = []
    for slot in range(dispatch_w):
        req = disp_valids[slot] & disp_targets[slot]
        v = zero1
        idx = c(0, width=iq_w)
        for i in range(iq_depth):
            cidx = c(i, width=iq_w)
            free = ~iq_valid_mask[i]
            exclude = zero1
            for prev in range(slot):
                prev_req = disp_valids[prev] & disp_targets[prev]
                exclude = exclude | (prev_req & alloc_valids[prev] & alloc_idxs[prev].__eq__(cidx))
            cand = req & free & (~exclude)
            take = cand & (~v)
            v = take._select_internal(one1, v)
            idx = take._select_internal(cidx, idx)
        alloc_valids.append(v)
        alloc_idxs.append(idx)

    alloc_ok = one1
    for slot in range(dispatch_w):
        req = disp_valids[slot] & disp_targets[slot]
        alloc_ok = alloc_ok & ((~req) | alloc_valids[slot])
        m.output(f"alloc_valid{slot}", alloc_valids[slot])
        m.output(f"alloc_idx{slot}", alloc_idxs[slot])
    m.output("alloc_ok", alloc_ok)


@module(name="LinxCoreDispatchPregAllocStage")
def build_dispatch_preg_alloc_stage(
    m: Circuit,
    *,
    pregs: int = 64,
    ptag_w: int = 6,
) -> None:
    c = m.const

    req = m.input("req", width=1)
    free_mask_in = m.input("free_mask_in", width=pregs)

    alloc_valid = c(0, width=1)
    alloc_tag = c(0, width=ptag_w)
    alloc_oh = c(0, width=pregs)
    for i in range(pregs):
        ii = c(i, width=ptag_w)
        onehot = c(1 << i, width=pregs)
        cand = req & (~alloc_valid) & free_mask_in[i]
        alloc_valid = cand._select_internal(c(1, width=1), alloc_valid)
        alloc_tag = cand._select_internal(ii, alloc_tag)
        alloc_oh = cand._select_internal(onehot, alloc_oh)
    free_mask_out = req._select_internal(free_mask_in & (~alloc_oh), free_mask_in)

    m.output("alloc_valid", alloc_valid)
    m.output("alloc_tag", alloc_tag)
    m.output("alloc_oh", alloc_oh)
    m.output("free_mask_out", free_mask_out)


@module(name="LinxCoreDispatchPregAllocFabric")
def build_dispatch_preg_alloc_fabric(
    m: Circuit,
    *,
    dispatch_w: int = 4,
    pregs: int = 64,
    ptag_w: int = 6,
) -> None:
    c = m.const

    ren_free_mask = m.input("ren_free_mask", width=pregs)
    disp_valids = [m.input(f"disp_valid{slot}", width=1) for slot in range(dispatch_w)]
    disp_need_pdst = [m.input(f"disp_need_pdst{slot}", width=1) for slot in range(dispatch_w)]

    preg_stage_vec = _dispatch_preg_stage_vector(m, dispatch_w=dispatch_w, pregs=pregs, ptag_w=ptag_w)
    preg_stage_params = {"pregs": int(pregs), "ptag_w": int(ptag_w)}

    preg_alloc_valids = []
    preg_alloc_tags = []
    preg_alloc_onehots = []
    free_mask_stage = ren_free_mask
    for slot, key in enumerate(preg_stage_vec.keys()):
        req = disp_valids[slot] & disp_need_pdst[slot]
        stage = m.new(
            preg_stage_vec.family.module,
            name=f"dispatch_preg_stage_{key}",
            bind={
                "req": req,
                "free_mask_in": free_mask_stage,
            },
            params=preg_stage_params,
        )
        stage_out = stage.outputs
        free_mask_stage = stage_out["free_mask_out"]
        preg_alloc_valids.append(stage_out["alloc_valid"])
        preg_alloc_tags.append(stage_out["alloc_tag"])
        preg_alloc_onehots.append(stage_out["alloc_oh"])

    preg_alloc_ok = c(1, width=1)
    disp_alloc_mask = c(0, width=pregs)
    for slot in range(dispatch_w):
        req = disp_valids[slot] & disp_need_pdst[slot]
        preg_alloc_ok = preg_alloc_ok & ((~req) | preg_alloc_valids[slot])
        disp_alloc_mask = disp_alloc_mask | req._select_internal(preg_alloc_onehots[slot], c(0, width=pregs))
        m.output(f"preg_alloc_valid{slot}", preg_alloc_valids[slot])
        m.output(f"preg_alloc_tag{slot}", preg_alloc_tags[slot])
        m.output(f"preg_alloc_oh{slot}", preg_alloc_onehots[slot])
    m.output("preg_alloc_ok", preg_alloc_ok)
    m.output("disp_alloc_mask", disp_alloc_mask)


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
    # block_head_in==1 means the next BSTART is a block head marker. Mid-block
    # BSTART split markers must not allocate a new BID; they are flush/refetch
    # hints only.
    block_head_in = m.input("block_head_in", width=1)
    block_epoch_in = m.input("block_epoch_in", width=16)
    block_uid_in = m.input("block_uid_in", width=64)
    block_bid_in = m.input("block_bid_in", width=64)
    brob_alloc_ready_i = m.input("brob_alloc_ready_i", width=1)
    brob_alloc_bid_i = m.input("brob_alloc_bid_i", width=64)
    lsid_alloc_base = m.input("lsid_alloc_base", width=32)
    rob_count = m.input("rob_count", width=rob_w + 1)
    disp_count = m.input("disp_count", width=3)
    ren_free_mask = m.input("ren_free_mask", width=pregs)

    disp_valids = []
    disp_ops = []
    disp_need_pdst = []
    disp_is_bstart = []
    for slot in range(dispatch_w):
        disp_valids.append(m.input(f"disp_valid{slot}", width=1))
        disp_ops.append(m.input(f"disp_op{slot}", width=12))
        disp_need_pdst.append(m.input(f"disp_need_pdst{slot}", width=1))
        disp_is_bstart.append(m.input(f"disp_is_bstart{slot}", width=1))

    iq_alu_valid_mask = m.input("iq_alu_valid_mask", width=iq_depth)
    iq_bru_valid_mask = m.input("iq_bru_valid_mask", width=iq_depth)
    iq_lsu_valid_mask = m.input("iq_lsu_valid_mask", width=iq_depth)
    iq_cmd_valid_mask = m.input("iq_cmd_valid_mask", width=iq_depth)

    # ROB space check: rob.count + disp_count <= rob_depth.
    rob_cnt_after = rob_count + disp_count._zext(width=rob_w + 1)
    rob_space_ok = rob_cnt_after.ult(c(rob_depth + 1, width=rob_w + 1))

    # IQ routing/classification.
    classify_vec = _dispatch_classify_vector(m, dispatch_w=dispatch_w)
    classify_per = {}
    for slot, key in enumerate(classify_vec.keys()):
        classify_per[key] = {"op": disp_ops[slot]}
    classify_stages = m.array(
        classify_vec,
        name="dispatch_classify",
        bind={},
        per=classify_per,
    )
    disp_to_alu = []
    disp_to_bru = []
    disp_to_lsu = []
    disp_to_d2 = []
    disp_to_cmd = []
    for key in classify_vec.keys():
        classify_out = classify_stages.output(key)
        disp_to_alu.append(classify_out["to_alu"])
        disp_to_bru.append(classify_out["to_bru"])
        disp_to_lsu.append(classify_out["to_lsu"])
        disp_to_d2.append(classify_out["to_d2"])
        disp_to_cmd.append(classify_out["to_cmd"])

    # IQ slot allocation.
    iq_alloc_map = _dispatch_iq_alloc_map(m, dispatch_w=dispatch_w, iq_depth=iq_depth, iq_w=iq_w)
    iq_common_bind = {}
    for slot in range(dispatch_w):
        iq_common_bind[f"disp_valid{slot}"] = disp_valids[slot]
    iq_targets = {
        "alu": disp_to_alu,
        "bru": disp_to_bru,
        "lsu": disp_to_lsu,
        "cmd": disp_to_cmd,
    }
    iq_valid_masks = {
        "alu": iq_alu_valid_mask,
        "bru": iq_bru_valid_mask,
        "lsu": iq_lsu_valid_mask,
        "cmd": iq_cmd_valid_mask,
    }
    iq_per = {}
    for key in iq_alloc_map.keys():
        per_key = {}
        for slot in range(dispatch_w):
            per_key[f"disp_target{slot}"] = iq_targets[key][slot]
        per_key["iq_valid_mask"] = iq_valid_masks[key]
        iq_per[key] = per_key
    iq_allocs = m.array(
        iq_alloc_map,
        name="dispatch_iq_alloc",
        bind=iq_common_bind,
        per=iq_per,
    )
    alu_alloc_out = iq_allocs.output("alu")
    bru_alloc_out = iq_allocs.output("bru")
    lsu_alloc_out = iq_allocs.output("lsu")
    cmd_alloc_out = iq_allocs.output("cmd")
    alu_alloc_valids = [alu_alloc_out[f"alloc_valid{slot}"] for slot in range(dispatch_w)]
    alu_alloc_idxs = [alu_alloc_out[f"alloc_idx{slot}"] for slot in range(dispatch_w)]
    bru_alloc_valids = [bru_alloc_out[f"alloc_valid{slot}"] for slot in range(dispatch_w)]
    bru_alloc_idxs = [bru_alloc_out[f"alloc_idx{slot}"] for slot in range(dispatch_w)]
    lsu_alloc_valids = [lsu_alloc_out[f"alloc_valid{slot}"] for slot in range(dispatch_w)]
    lsu_alloc_idxs = [lsu_alloc_out[f"alloc_idx{slot}"] for slot in range(dispatch_w)]
    cmd_alloc_valids = [cmd_alloc_out[f"alloc_valid{slot}"] for slot in range(dispatch_w)]
    cmd_alloc_idxs = [cmd_alloc_out[f"alloc_idx{slot}"] for slot in range(dispatch_w)]
    iq_alloc_ok = (
        alu_alloc_out["alloc_ok"]
        & bru_alloc_out["alloc_ok"]
        & lsu_alloc_out["alloc_ok"]
        & cmd_alloc_out["alloc_ok"]
    )

    # Physical-register allocation.
    preg_fabric_bind = {"ren_free_mask": ren_free_mask}
    for slot in range(dispatch_w):
        preg_fabric_bind[f"disp_valid{slot}"] = disp_valids[slot]
        preg_fabric_bind[f"disp_need_pdst{slot}"] = disp_need_pdst[slot]
    preg_fabric = m.new(
        build_dispatch_preg_alloc_fabric,
        name="dispatch_preg_fabric",
        bind=preg_fabric_bind,
        params={
            "dispatch_w": int(dispatch_w),
            "pregs": int(pregs),
            "ptag_w": int(ptag_w),
        },
    )
    preg_alloc_out = preg_fabric.outputs
    preg_alloc_valids = [preg_alloc_out[f"preg_alloc_valid{slot}"] for slot in range(dispatch_w)]
    preg_alloc_tags = [preg_alloc_out[f"preg_alloc_tag{slot}"] for slot in range(dispatch_w)]
    preg_alloc_onehots = [preg_alloc_out[f"preg_alloc_oh{slot}"] for slot in range(dispatch_w)]
    preg_alloc_ok = preg_alloc_out["preg_alloc_ok"]
    disp_alloc_mask = preg_alloc_out["disp_alloc_mask"]
    disp_pdsts = []
    for slot in range(dispatch_w):
        req = disp_valids[slot] & disp_need_pdst[slot]
        disp_pdsts.append(req._select_internal(preg_alloc_tags[slot], c(0, width=ptag_w)))

    # BID allocation must be possible whenever a *head* BSTART is dispatched.
    head_live = block_head_in
    bstart_head_present = c(0, width=1)
    for slot in range(dispatch_w):
        v = disp_valids[slot]
        op = disp_ops[slot]
        is_bstop = v & op.__eq__(c(OP_C_BSTOP, width=12))
        op_is = partial(_op_is, m)
        is_macro = v & op_is(op, OP_FENTRY, OP_FEXIT, OP_FRET_RA, OP_FRET_STK)
        is_bstart = v & disp_is_bstart[slot]
        is_bstart_head = is_bstart & head_live
        is_bstart_mid = is_bstart & (~head_live)

        bstart_head_present = bstart_head_present | is_bstart_head

        # Track expected head-marker state across the bundle.
        # - Head BSTART transitions into a block (head_live=0).
        # - BSTOP/macro/mid-block BSTART transition to block head (head_live=1).
        head_live = is_bstart_head._select_internal(c(0, width=1), head_live)
        head_live = (is_bstop | is_macro | is_bstart_mid)._select_internal(c(1, width=1), head_live)

    bid_alloc_ok = (~bstart_head_present) | brob_alloc_ready_i

    frontend_ready = can_run & (~commit_redirect) & rob_space_ok & iq_alloc_ok & preg_alloc_ok & bid_alloc_ok
    dispatch_fire = frontend_ready & f4_valid
    brob_alloc_fire = dispatch_fire & bstart_head_present

    mem_disp_count = c(0, width=3)
    disp_load_store_ids = []
    lsid_next_live = lsid_alloc_base
    for slot in range(dispatch_w):
        is_mem = disp_valids[slot] & disp_to_lsu[slot]
        disp_load_store_ids.append(lsid_next_live)
        lsid_next_live = is_mem._select_internal(lsid_next_live + c(1, width=32), lsid_next_live)
        mem_disp_count = mem_disp_count + is_mem._zext(width=3)
    lsid_alloc_next = dispatch_fire._select_internal(lsid_next_live, lsid_alloc_base)

    m.output("rob_space_ok", rob_space_ok)
    m.output("iq_alloc_ok", iq_alloc_ok)
    m.output("preg_alloc_ok", preg_alloc_ok)
    m.output("bid_alloc_ok", bid_alloc_ok)
    m.output("frontend_ready", frontend_ready)
    m.output("dispatch_fire", dispatch_fire)
    m.output("mem_disp_count", mem_disp_count)
    m.output("lsid_alloc_next", lsid_alloc_next)
    m.output("disp_alloc_mask", disp_alloc_mask)

    # D1-time block identity: head BSTART carries the newly allocated BID.
    cur_bid = block_bid_in
    cur_uid = block_uid_in
    head_live = block_head_in
    for slot in range(dispatch_w):
        v = disp_valids[slot]
        op = disp_ops[slot]
        is_bstop = v & op.__eq__(c(OP_C_BSTOP, width=12))
        op_is = partial(_op_is, m)
        is_macro = v & op_is(op, OP_FENTRY, OP_FEXIT, OP_FRET_RA, OP_FRET_STK)

        is_bstart = v & disp_is_bstart[slot]
        is_bstart_head = is_bstart & head_live
        is_bstart_mid = is_bstart & (~head_live)

        bid_this = is_bstart_head._select_internal(brob_alloc_bid_i, cur_bid)
        uid_this = is_bstart_head._select_internal(brob_alloc_bid_i, cur_uid)
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
        m.output(f"disp_block_uid{slot}", uid_this)
        m.output(f"disp_block_bid{slot}", bid_this)
        m.output(f"disp_load_store_id{slot}", disp_load_store_ids[slot])
        cur_bid = is_bstart_head._select_internal(brob_alloc_bid_i, cur_bid)
        cur_uid = is_bstart_head._select_internal(brob_alloc_bid_i, cur_uid)

        head_live = is_bstart_head._select_internal(c(0, width=1), head_live)
        head_live = (is_bstop | is_macro | is_bstart_mid)._select_internal(c(1, width=1), head_live)

    m.output("brob_alloc_fire", brob_alloc_fire)
