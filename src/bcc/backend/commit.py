from __future__ import annotations

from pycircuit import Circuit, module

from common.isa import (
    BK_CALL,
    BK_COND,
    BK_DIRECT,
    BK_ICALL,
    BK_IND,
    BK_RET,
    OP_BSTART_STD_CALL,
    OP_BSTART_STD_COND,
    OP_BSTART_STD_DIRECT,
    OP_BSTART_STD_FALL,
    OP_C_BSTART_COND,
    OP_C_BSTART_DIRECT,
    OP_C_BSTART_STD,
    OP_C_BSTOP,
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


def is_setc_any(op, op_is):
    return op_is(
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


def is_setc_tgt(op, op_is):
    return op_is(op, OP_C_SETC_TGT)


def _op_is(m: Circuit, op, *codes: int):
    c = m.const
    v = c(0, width=1)
    for code in codes:
        v = v | op.eq(c(code, width=12))
    return v


@module(name="LinxCoreCommitHeadStage")
def build_commit_head_stage(m: Circuit) -> None:
    c = m.const
    head_op = m.input("head_op", width=12)
    br_kind = m.input("br_kind", width=3)
    commit_cond = m.input("commit_cond", width=1)

    is_macro = _op_is(m, head_op, OP_FENTRY, OP_FEXIT, OP_FRET_RA, OP_FRET_STK)
    is_start_marker = (
        _op_is(
            m,
            head_op,
            OP_C_BSTART_STD,
            OP_C_BSTART_COND,
            OP_C_BSTART_DIRECT,
            OP_BSTART_STD_FALL,
            OP_BSTART_STD_DIRECT,
            OP_BSTART_STD_COND,
            OP_BSTART_STD_CALL,
        )
        | is_macro
    )
    is_boundary = is_start_marker | _op_is(m, head_op, OP_C_BSTOP)

    br_is_cond = br_kind.eq(c(BK_COND, width=3))
    br_is_call = br_kind.eq(c(BK_CALL, width=3))
    br_is_ret = br_kind.eq(c(BK_RET, width=3))
    br_is_direct = br_kind.eq(c(BK_DIRECT, width=3))
    br_is_ind = br_kind.eq(c(BK_IND, width=3))
    br_is_icall = br_kind.eq(c(BK_ICALL, width=3))

    br_take = (
        br_is_call
        | br_is_direct
        | br_is_ind
        | br_is_icall
        | (br_is_cond & commit_cond)
        | (br_is_ret & commit_cond)
    )
    head_skip = is_boundary & br_take

    m.output("head_is_macro", is_macro)
    m.output("head_is_start_marker", is_start_marker)
    m.output("head_is_boundary", is_boundary)
    m.output("head_br_take", br_take)
    m.output("head_skip", head_skip)


@module(name="LinxCoreCommitCtrlStage")
def build_commit_ctrl_stage(m: Circuit, *, commit_w: int = 4, rob_w: int = 6) -> None:
    c = m.const
    do_flush = m.input("do_flush", width=1)
    f4_valid = m.input("f4_valid", width=1)
    f4_pc = m.input("f4_pc", width=64)
    commit_redirect = m.input("commit_redirect", width=1)
    redirect_pc = m.input("redirect_pc", width=64)
    redirect_checkpoint_id = m.input("redirect_checkpoint_id", width=6)
    mmio_exit = m.input("mmio_exit", width=1)

    state_fpc = m.input("state_fpc", width=64)
    state_flush_pc = m.input("state_flush_pc", width=64)
    state_flush_checkpoint_id = m.input("state_flush_checkpoint_id", width=6)
    state_flush_pending = m.input("state_flush_pending", width=1)
    state_replay_pending = m.input("state_replay_pending", width=1)
    state_replay_store_rob = m.input("state_replay_store_rob", width=rob_w)
    state_replay_pc = m.input("state_replay_pc", width=64)

    replay_redirect_fire = m.input("replay_redirect_fire", width=1)
    replay_set = m.input("replay_set", width=1)
    replay_set_store_rob = m.input("replay_set_store_rob", width=rob_w)
    replay_set_pc = m.input("replay_set_pc", width=64)

    commit_fires = []
    rob_ops = []
    for slot in range(commit_w):
        commit_fires.append(m.input(f"commit_fire{slot}", width=1))
        rob_ops.append(m.input(f"rob_op{slot}", width=12))

    fpc_next = state_fpc
    fpc_next = f4_valid.select(f4_pc, fpc_next)
    fpc_next = commit_redirect.select(redirect_pc, fpc_next)
    fpc_next = do_flush.select(state_flush_pc, fpc_next)

    flush_pc_next = commit_redirect.select(redirect_pc, state_flush_pc)
    flush_checkpoint_id_next = commit_redirect.select(redirect_checkpoint_id, state_flush_checkpoint_id)
    flush_pending_next = state_flush_pending
    flush_pending_next = do_flush.select(c(0, width=1), flush_pending_next)
    flush_pending_next = commit_redirect.select(c(1, width=1), flush_pending_next)

    replay_pending_next = state_replay_pending
    replay_pending_next = do_flush.select(c(0, width=1), replay_pending_next)
    replay_pending_next = replay_redirect_fire.select(c(0, width=1), replay_pending_next)
    replay_pending_next = replay_set.select(c(1, width=1), replay_pending_next)
    replay_store_rob_next = replay_set.select(replay_set_store_rob, state_replay_store_rob)
    replay_pc_next = replay_set.select(replay_set_pc, state_replay_pc)

    halt_set = mmio_exit
    for slot in range(commit_w):
        op = rob_ops[slot]
        is_halt = op.eq(c(OP_EBREAK, width=12)) | op.eq(c(OP_INVALID, width=12))
        halt_set = halt_set | (commit_fires[slot] & is_halt)

    m.output("fpc_next", fpc_next)
    m.output("flush_pc_next", flush_pc_next)
    m.output("flush_checkpoint_id_next", flush_checkpoint_id_next)
    m.output("flush_pending_next", flush_pending_next)
    m.output("replay_pending_next", replay_pending_next)
    m.output("replay_store_rob_next", replay_store_rob_next)
    m.output("replay_pc_next", replay_pc_next)
    m.output("halt_set", halt_set)
