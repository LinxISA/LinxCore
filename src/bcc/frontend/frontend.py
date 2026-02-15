from __future__ import annotations

from pycircuit import Circuit, module

from .bpu import build_bpu_lite
from .ftq import build_ftq_lite
from .ibuffer import build_ibuffer
from .ifetch import build_ifetch


@module(name="LinxCoreFrontend")
def build_frontend(m: Circuit, *, ibuf_depth: int = 8, ftq_depth: int = 16) -> None:
    clk = m.clock("clk")
    rst = m.reset("rst")

    boot_pc = m.input("boot_pc", width=64)

    imem_rdata = m.input("imem_rdata", width=64)

    backend_ready = m.input("backend_ready", width=1)
    redirect_valid = m.input("redirect_valid", width=1)
    redirect_pc = m.input("redirect_pc", width=64)
    flush_valid = m.input("flush_valid", width=1)
    flush_pc = m.input("flush_pc", width=64)

    c = m.const

    do_redirect = redirect_valid | flush_valid
    redirect_target = flush_valid.select(flush_pc, redirect_pc)

    ibuf_push_ready_w = m.new_wire(width=1)
    pred_valid_w = m.new_wire(width=1)
    pred_taken_w = m.new_wire(width=1)
    pred_target_w = m.new_wire(width=64)

    ifetch = m.instance(
        build_ifetch,
        name="ifetch",
        clk=clk,
        rst=rst,
        boot_pc=boot_pc,
        imem_rdata=imem_rdata,
        stall_i=(~ibuf_push_ready_w),
        redirect_valid=do_redirect,
        redirect_pc=redirect_target,
        pred_valid=pred_valid_w,
        pred_taken=pred_taken_w,
        pred_target=pred_target_w,
    )

    bpu = m.instance(
        build_bpu_lite,
        name="bpu",
        params={"tag_bits": 8},
        clk=clk,
        rst=rst,
        req_valid=ifetch["fetch_valid"],
        req_pc=ifetch["fetch_pc"],
        update_valid=do_redirect,
        update_pc=redirect_target,
        update_taken=do_redirect,
        update_target=redirect_target,
    )

    # Keep prediction disabled for strict lockstep bring-up; FTQ/BPU metadata
    # remains available for later tuning milestones.
    m.assign(pred_valid_w, c(0, width=1))
    m.assign(pred_taken_w, c(0, width=1))
    m.assign(pred_target_w, c(0, width=64))

    ibuf = m.instance(
        build_ibuffer,
        name="ibuffer",
        params={"depth": ibuf_depth},
        clk=clk,
        rst=rst,
        push_valid=ifetch["fetch_valid"],
        push_pc=ifetch["fetch_pc"],
        push_window=ifetch["fetch_window"],
        pop_ready=backend_ready,
        flush_valid=do_redirect,
    )
    m.assign(ibuf_push_ready_w, ibuf["push_ready"])

    ftq = m.instance(
        build_ftq_lite,
        name="ftq",
        params={"depth": ftq_depth},
        clk=clk,
        rst=rst,
        enq_valid=ifetch["fetch_valid"],
        enq_pc=ifetch["fetch_pc"],
        enq_npc=ifetch["fetch_next_pc"],
        enq_checkpoint=bpu["checkpoint_id"],
        deq_ready=ibuf["pop_fire"],
        flush_valid=do_redirect,
    )

    m.output("imem_raddr", ifetch["imem_raddr"])
    m.output("f4_valid", ibuf["out_valid"])
    m.output("f4_pc", ibuf["out_pc"])
    m.output("f4_window", ibuf["out_window"])

    m.output("fpc", ifetch["fpc_dbg"])
    m.output("ftq_head", ftq["head_dbg"])
    m.output("ftq_tail", ftq["tail_dbg"])
    m.output("ftq_count", ftq["count_dbg"])
    m.output("checkpoint_id", ftq["checkpoint_dbg"])


build_frontend.__pycircuit_name__ = "LinxCoreFrontend"
