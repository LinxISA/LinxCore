from __future__ import annotations

from dataclasses import dataclass

from pycircuit import Circuit, Reg, Wire
from pycircuit.dsl import Signal

from common.isa import BK_FALL
from common.util import Consts
from .params import OooParams


@dataclass(frozen=True)
class CoreCtrlRegs:
    halted: Reg
    cycles: Reg
    pc: Reg
    fpc: Reg
    br_kind: Reg
    br_epoch: Reg
    br_base_pc: Reg
    br_off: Reg
    br_pred_take: Reg
    block_uid_ctr: Reg
    active_block_uid: Reg
    block_bid_ctr: Reg
    active_block_bid: Reg
    lsid_alloc_ctr: Reg
    lsid_issue_ptr: Reg
    lsid_complete_ptr: Reg
    block_head: Reg
    br_corr_pending: Reg
    br_corr_epoch: Reg
    br_corr_take: Reg
    br_corr_target: Reg
    br_corr_checkpoint_id: Reg
    br_corr_fault_pending: Reg
    br_corr_fault_rob: Reg
    commit_cond: Reg
    commit_tgt: Reg
    flush_pending: Reg
    flush_pc: Reg
    flush_checkpoint_id: Reg
    replay_pending: Reg
    replay_store_rob: Reg
    replay_pc: Reg
    trap_pending: Reg
    trap_rob: Reg
    trap_cause: Reg
    macro_active: Reg
    macro_wait_commit: Reg
    post_macro_handoff: Reg
    macro_phase: Reg
    macro_op: Reg
    macro_begin: Reg
    macro_end: Reg
    macro_stacksize: Reg
    macro_reg: Reg
    macro_i: Reg
    macro_sp_base: Reg
    macro_saved_ra: Reg


@dataclass(frozen=True)
class IfuRegs:
    f4_valid: Reg
    f4_pc: Reg
    f4_window: Reg


@dataclass(frozen=True)
class RenameRegs:
    smap: list[Reg]
    cmap: list[Reg]
    free_mask: Reg
    ready_mask: Reg
    ckpt_valid: list[Reg]
    ckpt_smap: list[list[Reg]]
    ckpt_free_mask: list[Reg]
    ckpt_ready_mask: list[Reg]


@dataclass(frozen=True)
class RobRegs:
    head: Reg
    tail: Reg
    count: Reg

    valid: list[Reg]
    done: list[Reg]
    pc: list[Reg]
    op: list[Reg]
    len_bytes: list[Reg]

    dst_kind: list[Reg]
    dst_areg: list[Reg]
    pdst: list[Reg]
    value: list[Reg]
    src0_reg: list[Reg]
    src1_reg: list[Reg]
    src0_value: list[Reg]
    src1_value: list[Reg]
    src0_valid: list[Reg]
    src1_valid: list[Reg]

    store_addr: list[Reg]
    store_data: list[Reg]
    store_size: list[Reg]
    is_store: list[Reg]
    load_addr: list[Reg]
    load_data: list[Reg]
    load_size: list[Reg]
    is_load: list[Reg]
    is_boundary: list[Reg]
    is_bstart: list[Reg]
    is_bstop: list[Reg]
    boundary_kind: list[Reg]
    boundary_target: list[Reg]
    pred_take: list[Reg]
    block_epoch: list[Reg]
    block_uid: list[Reg]
    block_bid: list[Reg]
    load_store_id: list[Reg]
    resolved_d2: list[Reg]
    insn_raw: list[Reg]
    checkpoint_id: list[Reg]
    macro_begin: list[Reg]
    macro_end: list[Reg]
    uop_uid: list[Reg]
    parent_uid: list[Reg]


@dataclass(frozen=True)
class IqRegs:
    valid: list[Reg]
    rob: list[Reg]
    op: list[Reg]
    pc: list[Reg]
    imm: list[Reg]
    srcl: list[Reg]
    srcr: list[Reg]
    srcr_type: list[Reg]
    shamt: list[Reg]
    srcp: list[Reg]
    pdst: list[Reg]
    has_dst: list[Reg]


def make_core_ctrl_regs(m: Circuit, clk: Signal, rst: Signal, *, boot_pc: Wire, consts: Consts, p: OooParams) -> CoreCtrlRegs:
    c = m.const
    epoch_w = 16
    with m.scope("state"):
        halted = m.out("halted", clk=clk, rst=rst, width=1, init=consts.zero1, en=consts.one1)
        cycles = m.out("cycles", clk=clk, rst=rst, width=64, init=consts.zero64, en=consts.one1)

        # Commit PC (architectural PC of the next commit).
        pc = m.out("pc", clk=clk, rst=rst, width=64, init=boot_pc, en=consts.one1)

        # Fetch PC (fall-through only; redirected on commit-time boundary).
        fpc = m.out("fpc", clk=clk, rst=rst, width=64, init=boot_pc, en=consts.one1)

        # Block transition kind (must cover BK_DIRECT/BK_IND/BK_ICALL).
        br_kind = m.out("br_kind", clk=clk, rst=rst, width=3, init=c(BK_FALL, width=3), en=consts.one1)
        br_epoch = m.out("br_epoch", clk=clk, rst=rst, width=epoch_w, init=c(0, width=epoch_w), en=consts.one1)
        br_base_pc = m.out("br_base_pc", clk=clk, rst=rst, width=64, init=boot_pc, en=consts.one1)
        br_off = m.out("br_off", clk=clk, rst=rst, width=64, init=consts.zero64, en=consts.one1)
        br_pred_take = m.out("br_pred_take", clk=clk, rst=rst, width=1, init=consts.zero1, en=consts.one1)
        block_uid_ctr = m.out("block_uid_ctr", clk=clk, rst=rst, width=64, init=c(2, width=64), en=consts.one1)
        active_block_uid = m.out("active_block_uid", clk=clk, rst=rst, width=64, init=c(1, width=64), en=consts.one1)
        block_bid_ctr = m.out("block_bid_ctr", clk=clk, rst=rst, width=64, init=c(1, width=64), en=consts.one1)
        active_block_bid = m.out("active_block_bid", clk=clk, rst=rst, width=64, init=c(0, width=64), en=consts.one1)
        lsid_alloc_ctr = m.out("lsid_alloc_ctr", clk=clk, rst=rst, width=32, init=c(0, width=32), en=consts.one1)
        lsid_issue_ptr = m.out("lsid_issue_ptr", clk=clk, rst=rst, width=32, init=c(0, width=32), en=consts.one1)
        lsid_complete_ptr = m.out("lsid_complete_ptr", clk=clk, rst=rst, width=32, init=c(0, width=32), en=consts.one1)
        block_head = m.out("block_head", clk=clk, rst=rst, width=1, init=consts.one1, en=consts.one1)
        br_corr_pending = m.out("br_corr_pending", clk=clk, rst=rst, width=1, init=consts.zero1, en=consts.one1)
        br_corr_epoch = m.out("br_corr_epoch", clk=clk, rst=rst, width=epoch_w, init=c(0, width=epoch_w), en=consts.one1)
        br_corr_take = m.out("br_corr_take", clk=clk, rst=rst, width=1, init=consts.zero1, en=consts.one1)
        br_corr_target = m.out("br_corr_target", clk=clk, rst=rst, width=64, init=consts.zero64, en=consts.one1)
        br_corr_checkpoint_id = m.out(
            "br_corr_checkpoint_id", clk=clk, rst=rst, width=6, init=c(0, width=6), en=consts.one1
        )
        br_corr_fault_pending = m.out(
            "br_corr_fault_pending", clk=clk, rst=rst, width=1, init=consts.zero1, en=consts.one1
        )
        br_corr_fault_rob = m.out(
            "br_corr_fault_rob", clk=clk, rst=rst, width=p.rob_w, init=c(0, width=p.rob_w), en=consts.one1
        )
        commit_cond = m.out("commit_cond", clk=clk, rst=rst, width=1, init=consts.zero1, en=consts.one1)
        commit_tgt = m.out("commit_tgt", clk=clk, rst=rst, width=64, init=consts.zero64, en=consts.one1)

        # Redirect handling (one-cycle "bubble flush" after a taken boundary).
        flush_pending = m.out("flush_pending", clk=clk, rst=rst, width=1, init=consts.zero1, en=consts.one1)
        flush_pc = m.out("flush_pc", clk=clk, rst=rst, width=64, init=boot_pc, en=consts.one1)
        flush_checkpoint_id = m.out("flush_checkpoint_id", clk=clk, rst=rst, width=6, init=c(0, width=6), en=consts.one1)
        replay_pending = m.out("replay_pending", clk=clk, rst=rst, width=1, init=consts.zero1, en=consts.one1)
        replay_store_rob = m.out(
            "replay_store_rob", clk=clk, rst=rst, width=p.rob_w, init=c(0, width=p.rob_w), en=consts.one1
        )
        replay_pc = m.out("replay_pc", clk=clk, rst=rst, width=64, init=boot_pc, en=consts.one1)
        trap_pending = m.out("trap_pending", clk=clk, rst=rst, width=1, init=consts.zero1, en=consts.one1)
        trap_rob = m.out("trap_rob", clk=clk, rst=rst, width=p.rob_w, init=c(0, width=p.rob_w), en=consts.one1)
        trap_cause = m.out("trap_cause", clk=clk, rst=rst, width=32, init=c(0, width=32), en=consts.one1)

        # Template macro blocks (FENTRY/FEXIT/FRET.*) microcode.
        macro_active = m.out("macro_active", clk=clk, rst=rst, width=1, init=consts.zero1, en=consts.one1)
        macro_wait_commit = m.out("macro_wait_commit", clk=clk, rst=rst, width=1, init=consts.zero1, en=consts.one1)
        post_macro_handoff = m.out("post_macro_handoff", clk=clk, rst=rst, width=1, init=consts.zero1, en=consts.one1)
        macro_phase = m.out("macro_phase", clk=clk, rst=rst, width=2, init=consts.zero1.zext(width=2), en=consts.one1)
        macro_op = m.out("macro_op", clk=clk, rst=rst, width=12, init=c(0, width=12), en=consts.one1)
        macro_begin = m.out("macro_begin", clk=clk, rst=rst, width=6, init=c(0, width=6), en=consts.one1)
        macro_end = m.out("macro_end", clk=clk, rst=rst, width=6, init=c(0, width=6), en=consts.one1)
        macro_stacksize = m.out("macro_stacksize", clk=clk, rst=rst, width=64, init=consts.zero64, en=consts.one1)
        macro_reg = m.out("macro_reg", clk=clk, rst=rst, width=6, init=c(0, width=6), en=consts.one1)
        macro_i = m.out("macro_i", clk=clk, rst=rst, width=6, init=c(0, width=6), en=consts.one1)
        macro_sp_base = m.out("macro_sp_base", clk=clk, rst=rst, width=64, init=consts.zero64, en=consts.one1)
        macro_saved_ra = m.out("macro_saved_ra", clk=clk, rst=rst, width=64, init=consts.zero64, en=consts.one1)

    return CoreCtrlRegs(
        halted=halted,
        cycles=cycles,
        pc=pc,
        fpc=fpc,
        br_kind=br_kind,
        br_epoch=br_epoch,
        br_base_pc=br_base_pc,
        br_off=br_off,
        br_pred_take=br_pred_take,
        block_uid_ctr=block_uid_ctr,
        active_block_uid=active_block_uid,
        block_bid_ctr=block_bid_ctr,
        active_block_bid=active_block_bid,
        lsid_alloc_ctr=lsid_alloc_ctr,
        lsid_issue_ptr=lsid_issue_ptr,
        lsid_complete_ptr=lsid_complete_ptr,
        block_head=block_head,
        br_corr_pending=br_corr_pending,
        br_corr_epoch=br_corr_epoch,
        br_corr_take=br_corr_take,
        br_corr_target=br_corr_target,
        br_corr_checkpoint_id=br_corr_checkpoint_id,
        br_corr_fault_pending=br_corr_fault_pending,
        br_corr_fault_rob=br_corr_fault_rob,
        commit_cond=commit_cond,
        commit_tgt=commit_tgt,
        flush_pending=flush_pending,
        flush_pc=flush_pc,
        flush_checkpoint_id=flush_checkpoint_id,
        replay_pending=replay_pending,
        replay_store_rob=replay_store_rob,
        replay_pc=replay_pc,
        trap_pending=trap_pending,
        trap_rob=trap_rob,
        trap_cause=trap_cause,
        macro_active=macro_active,
        macro_wait_commit=macro_wait_commit,
        post_macro_handoff=post_macro_handoff,
        macro_phase=macro_phase,
        macro_op=macro_op,
        macro_begin=macro_begin,
        macro_end=macro_end,
        macro_stacksize=macro_stacksize,
        macro_reg=macro_reg,
        macro_i=macro_i,
        macro_sp_base=macro_sp_base,
        macro_saved_ra=macro_saved_ra,
    )


def make_ifu_regs(m: Circuit, clk: Signal, rst: Signal, *, boot_pc: Wire, consts: Consts) -> IfuRegs:
    with m.scope("ifu"):
        f4_valid = m.out("f4_valid", clk=clk, rst=rst, width=1, init=consts.zero1, en=consts.one1)
        f4_pc = m.out("f4_pc", clk=clk, rst=rst, width=64, init=boot_pc, en=consts.one1)
        f4_window = m.out("f4_window", clk=clk, rst=rst, width=64, init=consts.zero64, en=consts.one1)

    return IfuRegs(f4_valid=f4_valid, f4_pc=f4_pc, f4_window=f4_window)


def make_prf(m: Circuit, clk: Signal, rst: Signal, *, boot_sp: Wire, boot_ra: Wire, consts: Consts, p: OooParams) -> list[Reg]:
    with m.scope("prf"):
        prf: list[Reg] = []
        for i in range(p.pregs):
            init = consts.zero64
            if i == 1:
                init = boot_sp
            if i == 10:
                init = boot_ra
            prf.append(m.out(f"p{i}", clk=clk, rst=rst, width=64, init=init, en=consts.one1))
        return prf


def make_rename_regs(m: Circuit, clk: Signal, rst: Signal, *, consts: Consts, p: OooParams) -> RenameRegs:
    c = m.const
    ckpt_depth = 16
    with m.scope("rename"):
        smap: list[Reg] = []
        cmap: list[Reg] = []
        for i in range(p.aregs):
            smap.append(m.out(f"smap{i}", clk=clk, rst=rst, width=p.ptag_w, init=c(i, width=p.ptag_w), en=consts.one1))
            cmap.append(m.out(f"cmap{i}", clk=clk, rst=rst, width=p.ptag_w, init=c(i, width=p.ptag_w), en=consts.one1))

        free_init = ((1 << p.pregs) - 1) ^ ((1 << p.aregs) - 1)
        free_mask = m.out("free_mask", clk=clk, rst=rst, width=p.pregs, init=c(free_init, width=p.pregs), en=consts.one1)
        ready_mask = m.out("ready_mask", clk=clk, rst=rst, width=p.pregs, init=c((1 << p.pregs) - 1, width=p.pregs), en=consts.one1)

        ckpt_valid: list[Reg] = []
        ckpt_smap: list[list[Reg]] = []
        ckpt_free_mask: list[Reg] = []
        ckpt_ready_mask: list[Reg] = []
        for i in range(ckpt_depth):
            ckpt_valid.append(m.out(f"ckv{i}", clk=clk, rst=rst, width=1, init=consts.zero1, en=consts.one1))
            ckpt_free_mask.append(m.out(f"ckf{i}", clk=clk, rst=rst, width=p.pregs, init=c(free_init, width=p.pregs), en=consts.one1))
            ckpt_ready_mask.append(
                m.out(f"ckr{i}", clk=clk, rst=rst, width=p.pregs, init=c((1 << p.pregs) - 1, width=p.pregs), en=consts.one1)
            )
            smap_i: list[Reg] = []
            for r in range(p.aregs):
                smap_i.append(m.out(f"cks{i}_{r}", clk=clk, rst=rst, width=p.ptag_w, init=c(r, width=p.ptag_w), en=consts.one1))
            ckpt_smap.append(smap_i)

    return RenameRegs(
        smap=smap,
        cmap=cmap,
        free_mask=free_mask,
        ready_mask=ready_mask,
        ckpt_valid=ckpt_valid,
        ckpt_smap=ckpt_smap,
        ckpt_free_mask=ckpt_free_mask,
        ckpt_ready_mask=ckpt_ready_mask,
    )


def make_rob_regs(m: Circuit, clk: Signal, rst: Signal, *, consts: Consts, p: OooParams) -> RobRegs:
    c = m.const
    tag0 = c(0, width=p.ptag_w)
    epoch_w = 16

    with m.scope("rob"):
        head = m.out("head", clk=clk, rst=rst, width=p.rob_w, init=c(0, width=p.rob_w), en=consts.one1)
        tail = m.out("tail", clk=clk, rst=rst, width=p.rob_w, init=c(0, width=p.rob_w), en=consts.one1)
        count = m.out("count", clk=clk, rst=rst, width=p.rob_w + 1, init=c(0, width=p.rob_w + 1), en=consts.one1)

        valid: list[Reg] = []
        done: list[Reg] = []
        pc: list[Reg] = []
        op: list[Reg] = []
        len_bytes: list[Reg] = []
        dst_kind: list[Reg] = []
        dst_areg: list[Reg] = []
        pdst: list[Reg] = []
        value: list[Reg] = []
        src0_reg: list[Reg] = []
        src1_reg: list[Reg] = []
        src0_value: list[Reg] = []
        src1_value: list[Reg] = []
        src0_valid: list[Reg] = []
        src1_valid: list[Reg] = []
        store_addr: list[Reg] = []
        store_data: list[Reg] = []
        store_size: list[Reg] = []
        is_store: list[Reg] = []
        load_addr: list[Reg] = []
        load_data: list[Reg] = []
        load_size: list[Reg] = []
        is_load: list[Reg] = []
        is_boundary: list[Reg] = []
        is_bstart: list[Reg] = []
        is_bstop: list[Reg] = []
        boundary_kind: list[Reg] = []
        boundary_target: list[Reg] = []
        pred_take: list[Reg] = []
        block_epoch: list[Reg] = []
        block_uid: list[Reg] = []
        block_bid: list[Reg] = []
        load_store_id: list[Reg] = []
        resolved_d2: list[Reg] = []
        insn_raw: list[Reg] = []
        checkpoint_id: list[Reg] = []
        macro_begin: list[Reg] = []
        macro_end: list[Reg] = []
        uop_uid: list[Reg] = []
        parent_uid: list[Reg] = []

        for i in range(p.rob_depth):
            valid.append(m.out(f"v{i}", clk=clk, rst=rst, width=1, init=consts.zero1, en=consts.one1))
            done.append(m.out(f"done{i}", clk=clk, rst=rst, width=1, init=consts.zero1, en=consts.one1))
            pc.append(m.out(f"pc{i}", clk=clk, rst=rst, width=64, init=consts.zero64, en=consts.one1))
            op.append(m.out(f"op{i}", clk=clk, rst=rst, width=12, init=c(0, width=12), en=consts.one1))
            len_bytes.append(m.out(f"len{i}", clk=clk, rst=rst, width=3, init=consts.zero3, en=consts.one1))
            dst_kind.append(m.out(f"dk{i}", clk=clk, rst=rst, width=2, init=c(0, width=2), en=consts.one1))
            dst_areg.append(m.out(f"da{i}", clk=clk, rst=rst, width=6, init=c(0, width=6), en=consts.one1))
            pdst.append(m.out(f"pd{i}", clk=clk, rst=rst, width=p.ptag_w, init=tag0, en=consts.one1))
            value.append(m.out(f"val{i}", clk=clk, rst=rst, width=64, init=consts.zero64, en=consts.one1))
            src0_reg.append(m.out(f"s0r{i}", clk=clk, rst=rst, width=6, init=c(0, width=6), en=consts.one1))
            src1_reg.append(m.out(f"s1r{i}", clk=clk, rst=rst, width=6, init=c(0, width=6), en=consts.one1))
            src0_value.append(m.out(f"s0v{i}", clk=clk, rst=rst, width=64, init=consts.zero64, en=consts.one1))
            src1_value.append(m.out(f"s1v{i}", clk=clk, rst=rst, width=64, init=consts.zero64, en=consts.one1))
            src0_valid.append(m.out(f"s0x{i}", clk=clk, rst=rst, width=1, init=consts.zero1, en=consts.one1))
            src1_valid.append(m.out(f"s1x{i}", clk=clk, rst=rst, width=1, init=consts.zero1, en=consts.one1))
            store_addr.append(m.out(f"sta{i}", clk=clk, rst=rst, width=64, init=consts.zero64, en=consts.one1))
            store_data.append(m.out(f"std{i}", clk=clk, rst=rst, width=64, init=consts.zero64, en=consts.one1))
            store_size.append(m.out(f"sts{i}", clk=clk, rst=rst, width=4, init=consts.zero4, en=consts.one1))
            is_store.append(m.out(f"isst{i}", clk=clk, rst=rst, width=1, init=consts.zero1, en=consts.one1))
            load_addr.append(m.out(f"lda{i}", clk=clk, rst=rst, width=64, init=consts.zero64, en=consts.one1))
            load_data.append(m.out(f"ldd{i}", clk=clk, rst=rst, width=64, init=consts.zero64, en=consts.one1))
            load_size.append(m.out(f"lds{i}", clk=clk, rst=rst, width=4, init=consts.zero4, en=consts.one1))
            is_load.append(m.out(f"isld{i}", clk=clk, rst=rst, width=1, init=consts.zero1, en=consts.one1))
            is_boundary.append(m.out(f"isb{i}", clk=clk, rst=rst, width=1, init=consts.zero1, en=consts.one1))
            is_bstart.append(m.out(f"isbs{i}", clk=clk, rst=rst, width=1, init=consts.zero1, en=consts.one1))
            is_bstop.append(m.out(f"isbe{i}", clk=clk, rst=rst, width=1, init=consts.zero1, en=consts.one1))
            boundary_kind.append(m.out(f"bk{i}", clk=clk, rst=rst, width=3, init=c(0, width=3), en=consts.one1))
            boundary_target.append(m.out(f"bt{i}", clk=clk, rst=rst, width=64, init=consts.zero64, en=consts.one1))
            pred_take.append(m.out(f"bpt{i}", clk=clk, rst=rst, width=1, init=consts.zero1, en=consts.one1))
            block_epoch.append(m.out(f"bep{i}", clk=clk, rst=rst, width=epoch_w, init=c(0, width=epoch_w), en=consts.one1))
            block_uid.append(m.out(f"buid{i}", clk=clk, rst=rst, width=64, init=consts.zero64, en=consts.one1))
            block_bid.append(m.out(f"bbid{i}", clk=clk, rst=rst, width=64, init=consts.zero64, en=consts.one1))
            load_store_id.append(m.out(f"lsid{i}", clk=clk, rst=rst, width=32, init=c(0, width=32), en=consts.one1))
            resolved_d2.append(m.out(f"rsd{i}", clk=clk, rst=rst, width=1, init=consts.zero1, en=consts.one1))
            insn_raw.append(m.out(f"insn{i}", clk=clk, rst=rst, width=64, init=consts.zero64, en=consts.one1))
            checkpoint_id.append(m.out(f"ckpt{i}", clk=clk, rst=rst, width=6, init=c(0, width=6), en=consts.one1))
            macro_begin.append(m.out(f"mb{i}", clk=clk, rst=rst, width=6, init=c(0, width=6), en=consts.one1))
            macro_end.append(m.out(f"me{i}", clk=clk, rst=rst, width=6, init=c(0, width=6), en=consts.one1))
            uop_uid.append(m.out(f"uid{i}", clk=clk, rst=rst, width=64, init=consts.zero64, en=consts.one1))
            parent_uid.append(m.out(f"puid{i}", clk=clk, rst=rst, width=64, init=consts.zero64, en=consts.one1))

    return RobRegs(
        head=head,
        tail=tail,
        count=count,
        valid=valid,
        done=done,
        pc=pc,
        op=op,
        len_bytes=len_bytes,
        dst_kind=dst_kind,
        dst_areg=dst_areg,
        pdst=pdst,
        value=value,
        src0_reg=src0_reg,
        src1_reg=src1_reg,
        src0_value=src0_value,
        src1_value=src1_value,
        src0_valid=src0_valid,
        src1_valid=src1_valid,
        store_addr=store_addr,
        store_data=store_data,
        store_size=store_size,
        is_store=is_store,
        load_addr=load_addr,
        load_data=load_data,
        load_size=load_size,
        is_load=is_load,
        is_boundary=is_boundary,
        is_bstart=is_bstart,
        is_bstop=is_bstop,
        boundary_kind=boundary_kind,
        boundary_target=boundary_target,
        pred_take=pred_take,
        block_epoch=block_epoch,
        block_uid=block_uid,
        block_bid=block_bid,
        load_store_id=load_store_id,
        resolved_d2=resolved_d2,
        insn_raw=insn_raw,
        checkpoint_id=checkpoint_id,
        macro_begin=macro_begin,
        macro_end=macro_end,
        uop_uid=uop_uid,
        parent_uid=parent_uid,
    )


def make_iq_regs(m: Circuit, clk: Signal, rst: Signal, *, consts: Consts, p: OooParams, name: str = "iq") -> IqRegs:
    c = m.const
    tag0 = c(0, width=p.ptag_w)

    with m.scope(name):
        valid: list[Reg] = []
        rob: list[Reg] = []
        op: list[Reg] = []
        pc: list[Reg] = []
        imm: list[Reg] = []
        srcl: list[Reg] = []
        srcr: list[Reg] = []
        srcr_type: list[Reg] = []
        shamt: list[Reg] = []
        srcp: list[Reg] = []
        pdst: list[Reg] = []
        has_dst: list[Reg] = []
        for i in range(p.iq_depth):
            valid.append(m.out(f"v{i}", clk=clk, rst=rst, width=1, init=consts.zero1, en=consts.one1))
            rob.append(m.out(f"rob{i}", clk=clk, rst=rst, width=p.rob_w, init=c(0, width=p.rob_w), en=consts.one1))
            op.append(m.out(f"op{i}", clk=clk, rst=rst, width=12, init=c(0, width=12), en=consts.one1))
            pc.append(m.out(f"pc{i}", clk=clk, rst=rst, width=64, init=consts.zero64, en=consts.one1))
            imm.append(m.out(f"imm{i}", clk=clk, rst=rst, width=64, init=consts.zero64, en=consts.one1))
            srcl.append(m.out(f"sl{i}", clk=clk, rst=rst, width=p.ptag_w, init=tag0, en=consts.one1))
            srcr.append(m.out(f"sr{i}", clk=clk, rst=rst, width=p.ptag_w, init=tag0, en=consts.one1))
            srcr_type.append(m.out(f"st{i}", clk=clk, rst=rst, width=2, init=consts.zero1.zext(width=2), en=consts.one1))
            shamt.append(m.out(f"sh{i}", clk=clk, rst=rst, width=6, init=consts.zero6, en=consts.one1))
            srcp.append(m.out(f"sp{i}", clk=clk, rst=rst, width=p.ptag_w, init=tag0, en=consts.one1))
            pdst.append(m.out(f"pd{i}", clk=clk, rst=rst, width=p.ptag_w, init=tag0, en=consts.one1))
            has_dst.append(m.out(f"hd{i}", clk=clk, rst=rst, width=1, init=consts.zero1, en=consts.one1))

    return IqRegs(
        valid=valid,
        rob=rob,
        op=op,
        pc=pc,
        imm=imm,
        srcl=srcl,
        srcr=srcr,
        srcr_type=srcr_type,
        shamt=shamt,
        srcp=srcp,
        pdst=pdst,
        has_dst=has_dst,
    )
