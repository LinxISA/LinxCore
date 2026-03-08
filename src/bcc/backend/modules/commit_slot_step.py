from __future__ import annotations

from pycircuit import Circuit, module

from common.isa import (
    BK_CALL,
    BK_COND,
    BK_DIRECT,
    BK_FALL,
    BK_ICALL,
    BK_IND,
    BK_RET,
    OP_C_SETC_EQ,
    OP_C_SETC_NE,
    OP_C_SETC_TGT,
    OP_EBREAK,
    OP_FENTRY,
    OP_FEXIT,
    OP_FRET_RA,
    OP_FRET_STK,
    OP_INVALID,
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
)
from ..commit import _op_is
from .block_meta_step import build_block_meta_step


COMMIT_SLOT_INPUT_FIELD_SPECS = (
    ("can_run", 1),
    ("allow_macro", 1),
    ("commit_allow", 1),
    ("commit_count", 3),
    ("redirect_valid", 1),
    ("redirect_pc", 64),
    ("redirect_bid", 64),
    ("redirect_checkpoint_id", 6),
    ("redirect_from_corr", 1),
    ("replay_redirect_fire", 1),
    ("commit_store_seen", 1),
    ("stbuf_has_space", 1),
    ("brob_retire_fire", 1),
    ("brob_retire_bid", 64),
    ("pc_live", 64),
    ("commit_cond", 1),
    ("commit_tgt", 64),
    ("br_kind", 3),
    ("br_epoch", 16),
    ("br_base", 64),
    ("br_off", 64),
    ("br_pred_take", 1),
    ("active_block_uid", 64),
    ("active_block_bid", 64),
    ("block_head", 1),
    ("br_corr_pending", 1),
    ("br_corr_epoch", 16),
    ("br_corr_take", 1),
    ("br_corr_target", 64),
    ("br_corr_checkpoint_id", 6),
    ("replay_pending", 1),
    ("replay_store_rob", 6),
    ("replay_pc", 64),
    ("ret_ra_val", 64),
    ("macro_saved_ra", 64),
    ("brob_active_allocated", 1),
    ("brob_active_ready", 1),
    ("brob_active_exception", 1),
    ("rob_valid", 1),
    ("rob_done", 1),
    ("rob_pc", 64),
    ("rob_op", 12),
    ("rob_len", 3),
    ("rob_value", 64),
    ("rob_is_store", 1),
    ("rob_store_addr", 64),
    ("rob_store_data", 64),
    ("rob_store_size", 4),
    ("rob_is_bstart", 1),
    ("rob_is_bstop", 1),
    ("rob_boundary_kind", 3),
    ("rob_boundary_target", 64),
    ("rob_pred_take", 1),
    ("rob_block_uid", 64),
    ("rob_block_bid", 64),
    ("rob_checkpoint_id", 6),
    ("commit_idx", 6),
)


COMMIT_SLOT_TRACE_FIELD_SPECS = (
    ("commit_fire", 1),
    ("pc", 64),
    ("pc_next", 64),
    ("commit_is_bstart", 1),
    ("commit_is_bstop", 1),
    ("commit_block_uid", 64),
    ("commit_block_bid", 64),
)


COMMIT_SLOT_REDIRECT_FIELD_SPECS = (
    ("redirect_valid", 1),
    ("redirect_pc", 64),
    ("redirect_bid", 64),
    ("redirect_checkpoint_id", 6),
    ("redirect_from_corr", 1),
    ("replay_redirect_fire", 1),
)


COMMIT_SLOT_LIVE_FIELD_SPECS = (
    ("commit_allow", 1),
    ("commit_count", 3),
    ("commit_store_seen", 1),
    ("brob_retire_fire", 1),
    ("brob_retire_bid", 64),
    ("pc_live", 64),
    ("commit_cond", 1),
    ("commit_tgt", 64),
    ("br_kind", 3),
    ("br_epoch", 16),
    ("br_base", 64),
    ("br_off", 64),
    ("br_pred_take", 1),
    ("active_block_uid", 64),
    ("active_block_bid", 64),
    ("block_head", 1),
    ("br_corr_pending", 1),
)


def _field_width_sum(field_defs) -> int:
    total = 0
    for _name, width in field_defs:
        total += int(width)
    return total


def _pack_fields(m: Circuit, field_defs, values_by_name: dict[str, object]):
    return m.concat(*reversed([values_by_name[name] for name, _width in field_defs]))


def _unpack_fields(pack, field_defs):
    fields = {}
    lsb = 0
    for name, width in field_defs:
        fields[name] = pack.slice(lsb=lsb, width=width)
        lsb += width
    return fields


@module(name="LinxCoreCommitSlotStep")
def build_commit_slot_step(m: Circuit) -> None:
    c = m.const

    pack_i = m.input("pack_i", width=_field_width_sum(COMMIT_SLOT_INPUT_FIELD_SPECS))
    fields = _unpack_fields(pack_i, COMMIT_SLOT_INPUT_FIELD_SPECS)

    can_run = fields["can_run"]
    allow_macro = fields["allow_macro"]
    commit_allow = fields["commit_allow"]
    commit_count = fields["commit_count"]
    redirect_valid = fields["redirect_valid"]
    redirect_pc = fields["redirect_pc"]
    redirect_bid = fields["redirect_bid"]
    redirect_checkpoint_id = fields["redirect_checkpoint_id"]
    redirect_from_corr = fields["redirect_from_corr"]
    replay_redirect_fire = fields["replay_redirect_fire"]
    commit_store_seen = fields["commit_store_seen"]
    stbuf_has_space = fields["stbuf_has_space"]
    brob_retire_fire = fields["brob_retire_fire"]
    brob_retire_bid = fields["brob_retire_bid"]
    pc_live = fields["pc_live"]
    commit_cond = fields["commit_cond"]
    commit_tgt = fields["commit_tgt"]
    br_kind = fields["br_kind"]
    br_epoch = fields["br_epoch"]
    br_base = fields["br_base"]
    br_off = fields["br_off"]
    br_pred_take = fields["br_pred_take"]
    active_block_uid = fields["active_block_uid"]
    active_block_bid = fields["active_block_bid"]
    block_head = fields["block_head"]
    br_corr_pending = fields["br_corr_pending"]
    br_corr_epoch = fields["br_corr_epoch"]
    br_corr_take = fields["br_corr_take"]
    br_corr_target = fields["br_corr_target"]
    br_corr_checkpoint_id = fields["br_corr_checkpoint_id"]
    replay_pending = fields["replay_pending"]
    replay_store_rob = fields["replay_store_rob"]
    replay_pc = fields["replay_pc"]
    ret_ra_val = fields["ret_ra_val"]
    macro_saved_ra = fields["macro_saved_ra"]
    brob_active_allocated = fields["brob_active_allocated"]
    brob_active_ready = fields["brob_active_ready"]
    brob_active_exception = fields["brob_active_exception"]
    rob_valid = fields["rob_valid"]
    rob_done = fields["rob_done"]
    rob_pc = fields["rob_pc"]
    rob_op = fields["rob_op"]
    rob_len = fields["rob_len"]
    rob_value = fields["rob_value"]
    rob_is_store = fields["rob_is_store"]
    rob_store_addr = fields["rob_store_addr"]
    rob_store_data = fields["rob_store_data"]
    rob_store_size = fields["rob_store_size"]
    rob_is_bstart = fields["rob_is_bstart"]
    rob_is_bstop = fields["rob_is_bstop"]
    rob_boundary_kind = fields["rob_boundary_kind"]
    rob_boundary_target = fields["rob_boundary_target"]
    rob_pred_take = fields["rob_pred_take"]
    rob_block_uid = fields["rob_block_uid"]
    rob_block_bid = fields["rob_block_bid"]
    rob_checkpoint_id = fields["rob_checkpoint_id"]
    commit_idx = fields["commit_idx"]

    pc_this = rob_valid._select_internal(rob_pc, pc_live)

    op = rob_op
    ln = rob_len
    val = rob_value

    is_macro = _op_is(m, op, OP_FENTRY, OP_FEXIT, OP_FRET_RA, OP_FRET_STK)
    is_fret = _op_is(m, op, OP_FRET_RA, OP_FRET_STK)
    is_fret_stk = op.__eq__(c(OP_FRET_STK, width=12))
    is_bstart = rob_is_bstart
    is_bstop = rob_is_bstop
    bstart_bid_diff = ~(rob_block_bid.__eq__(active_block_bid))
    is_bstart_head = is_bstart & (block_head | bstart_bid_diff)
    is_bstart_mid = is_bstart & (~is_bstart_head)
    is_boundary = is_bstart_mid | is_bstop | is_macro

    br_is_fall = br_kind.__eq__(c(BK_FALL, width=3))
    br_is_cond = br_kind.__eq__(c(BK_COND, width=3))
    br_is_call = br_kind.__eq__(c(BK_CALL, width=3))
    br_is_ret = br_kind.__eq__(c(BK_RET, width=3))
    br_is_direct = br_kind.__eq__(c(BK_DIRECT, width=3))
    br_is_ind = br_kind.__eq__(c(BK_IND, width=3))
    br_is_icall = br_kind.__eq__(c(BK_ICALL, width=3))

    br_target = br_base + br_off
    # `commit_tgt` carries dynamic targets produced by SETC_TGT / FRET.*
    # Only RET/IND/ICALL boundaries may consume it. Direct/CALL block targets
    # must continue using the decoded block metadata and must not be hijacked by
    # a stale template return target from an older block.
    br_target = (br_is_ret | br_is_ind | br_is_icall)._select_internal(commit_tgt, br_target)

    br_take_nonmacro = br_is_call | br_is_direct | br_is_ind | br_is_icall | (br_is_cond & commit_cond) | (br_is_ret & commit_cond)
    # Macro headers are their own control domain. FENTRY/FEXIT retire as
    # fall-through markers, while FRET.* redirects to the template-produced
    # return target. That target may live either in committed SETC state or,
    # on older handoff timing, only in the restored-RA side state.
    br_take_eff = is_macro._select_internal(is_fret & commit_cond, br_take_nonmacro)

    fire_pre = can_run & commit_allow & rob_valid & rob_done
    fire_pre = fire_pre & (allow_macro | (~is_macro))

    corr_epoch_match = br_corr_pending & br_corr_epoch.__eq__(br_epoch)
    # Commit is the architectural authority for SETC-driven block direction.
    # The committed `commit_cond/commit_tgt` state already reflects the youngest
    # retired SETC producer, while deferred BRU correction can lag by one loop
    # iteration and must not override a newer committed condition at retire.
    corr_for_boundary = c(0, width=1)
    fret_target_base = is_fret_stk._select_internal(macro_saved_ra, ret_ra_val)
    fret_target = commit_tgt.__eq__(c(0, width=64))._select_internal(fret_target_base, commit_tgt)
    br_target_eff = br_target
    br_target_eff = is_fret._select_internal(fret_target, br_target_eff)

    pc_inc = pc_this + ln._zext(width=64)
    boundary_fallthrough = is_bstart_mid._select_internal(pc_this, pc_inc)
    pc_next = is_boundary._select_internal(br_take_eff._select_internal(br_target_eff, boundary_fallthrough), pc_inc)

    is_halt = _op_is(m, op, OP_EBREAK, OP_INVALID)
    redirect_pre = fire_pre & ((is_boundary & br_take_eff) | is_bstart_mid)
    replay_redirect_pre = fire_pre & rob_is_store & replay_pending & commit_idx.__eq__(replay_store_rob)
    replay_redirect_fire = replay_redirect_pre._select_internal(c(1, width=1), replay_redirect_fire)

    fret_redirect = fire_pre & is_fret & (~redirect_pre)
    pc_next = fret_redirect._select_internal(fret_target, pc_next)
    redirect_pre = redirect_pre | fret_redirect
    pc_next = replay_redirect_pre._select_internal(replay_pc, pc_next)
    redirect_pre = redirect_pre | replay_redirect_pre

    fire = fire_pre & ((~rob_is_store) | ((~commit_store_seen) & stbuf_has_space))
    bstop_gate_ok = (~is_bstop) | (~brob_active_allocated) | brob_active_ready | brob_active_exception
    fire = fire & bstop_gate_ok
    store_commit = fire & rob_is_store
    stop = redirect_pre | (fire & is_halt)

    commit_count = commit_count + fire._zext(width=3)

    redirect_valid = redirect_pre._select_internal(c(1, width=1), redirect_valid)
    redirect_pc = redirect_pre._select_internal(pc_next, redirect_pc)
    redirect_bid = redirect_pre._select_internal(active_block_bid, redirect_bid)
    redirect_ckpt_sel = rob_checkpoint_id
    redirect_checkpoint_id = redirect_pre._select_internal(redirect_ckpt_sel, redirect_checkpoint_id)
    redirect_from_corr = (redirect_pre & corr_for_boundary)._select_internal(c(1, width=1), redirect_from_corr)

    commit_store_seen = store_commit._select_internal(c(1, width=1), commit_store_seen)

    bstop_commit = fire & is_bstop
    bstop_take = bstop_commit & (~brob_retire_fire)
    brob_retire_fire = bstop_take._select_internal(c(1, width=1), brob_retire_fire)
    brob_retire_bid = bstop_take._select_internal(active_block_bid, brob_retire_bid)

    op_setc_any = _op_is(
        m,
        op,
        OP_C_SETC_EQ,
        OP_C_SETC_NE,
        OP_SETC_GEUI,
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
    )
    op_setc_tgt = _op_is(m, op, OP_C_SETC_TGT)
    commit_cond = (fire & is_boundary)._select_internal(c(0, width=1), commit_cond)
    commit_tgt = (fire & is_boundary)._select_internal(c(0, width=64), commit_tgt)
    commit_cond = (fire & op_setc_any)._select_internal(val._trunc(width=1), commit_cond)
    commit_tgt = (fire & op_setc_tgt)._select_internal(val, commit_tgt)
    commit_cond = (fire & op_setc_tgt)._select_internal(c(1, width=1), commit_cond)

    block_meta_step = m.instance_auto(
        build_block_meta_step,
        name="block_meta_step",
        fire_i=fire,
        redirect_i=redirect_pre,
        is_boundary_i=is_boundary,
        is_bstart_head_i=is_bstart_head,
        is_bstart_mid_i=is_bstart_mid,
        is_bstop_i=is_bstop,
        is_macro_i=is_macro,
        br_take_eff_i=br_take_eff,
        pc_this_i=pc_this,
        boundary_kind_i=rob_boundary_kind,
        boundary_target_i=rob_boundary_target,
        pred_take_i=rob_pred_take,
        block_uid_this_i=rob_block_uid,
        block_bid_this_i=rob_block_bid,
        active_block_uid_i=active_block_uid,
        active_block_bid_i=active_block_bid,
        br_kind_i=br_kind,
        br_base_i=br_base,
        br_off_i=br_off,
        br_pred_take_i=br_pred_take,
        block_head_i=block_head,
    )
    active_block_uid = block_meta_step["active_block_uid_o"]
    active_block_bid = block_meta_step["active_block_bid_o"]
    br_kind = block_meta_step["br_kind_o"]
    br_base = block_meta_step["br_base_o"]
    br_off = block_meta_step["br_off_o"]
    br_pred_take = block_meta_step["br_pred_take_o"]
    block_head = block_meta_step["block_head_o"]

    # Head BSTART commits begin a fresh block metadata / condition domain even
    # though they are not redirecting boundaries themselves. Advance the BRU
    # correction epoch there as well so a deferred correction from the previous
    # dynamic block instance cannot leak into the next loop iteration.
    corr_epoch_advance = fire & (is_boundary | is_bstart_head)
    clear_corr_boundary = corr_epoch_advance & corr_epoch_match
    br_corr_pending = clear_corr_boundary._select_internal(c(0, width=1), br_corr_pending)
    br_epoch = corr_epoch_advance._select_internal(br_epoch + c(1, width=16), br_epoch)

    pc_live = fire._select_internal(pc_next, pc_live)
    commit_allow = commit_allow & fire & (~stop)

    bstart_commit = fire & is_bstart
    bstop_commit = fire & is_bstop

    m.output(
        "trace_pack_o",
        _pack_fields(
            m,
            COMMIT_SLOT_TRACE_FIELD_SPECS,
            {
                "commit_fire": fire,
                "pc": pc_this,
                "pc_next": pc_next,
                "commit_is_bstart": bstart_commit,
                "commit_is_bstop": bstop_commit,
                "commit_block_uid": rob_block_uid,
                "commit_block_bid": rob_block_bid,
            },
        ),
    )
    m.output(
        "redirect_pack_o",
        _pack_fields(
            m,
            COMMIT_SLOT_REDIRECT_FIELD_SPECS,
            {
                "redirect_valid": redirect_valid,
                "redirect_pc": redirect_pc,
                "redirect_bid": redirect_bid,
                "redirect_checkpoint_id": redirect_checkpoint_id,
                "redirect_from_corr": redirect_from_corr,
                "replay_redirect_fire": replay_redirect_fire,
            },
        ),
    )
    m.output(
        "live_pack_o",
        _pack_fields(
            m,
            COMMIT_SLOT_LIVE_FIELD_SPECS,
            {
                "commit_allow": commit_allow,
                "commit_count": commit_count,
                "commit_store_seen": commit_store_seen,
                "brob_retire_fire": brob_retire_fire,
                "brob_retire_bid": brob_retire_bid,
                "pc_live": pc_live,
                "commit_cond": commit_cond,
                "commit_tgt": commit_tgt,
                "br_kind": br_kind,
                "br_epoch": br_epoch,
                "br_base": br_base,
                "br_off": br_off,
                "br_pred_take": br_pred_take,
                "active_block_uid": active_block_uid,
                "active_block_bid": active_block_bid,
                "block_head": block_head,
                "br_corr_pending": br_corr_pending,
            },
        ),
    )
