from __future__ import annotations

from pycircuit import Circuit, ct, meta, module

from common.config import frontend_config
from .bpu import build_bpu_lite
from .ftq import build_ftq_lite
from .ibuffer import build_ibuffer
from .ifetch import build_ifetch


@module(name="LinxCoreFrontend")
def build_frontend(m: Circuit, *, ibuf_depth: int = 8, ftq_depth: int = 16) -> None:
    clk = m.clock("clk")
    rst = m.reset("rst")

    cfg = frontend_config(m, ibuf_depth=ibuf_depth, ftq_depth=ftq_depth)
    ibuf_depth_i = int(cfg["ibuf_depth"])
    ftq_depth_i = int(cfg["ftq_depth"])
    ftq_w = max(1, int(ct.clog2(ftq_depth_i)))

    in_spec = (
        meta.bundle("frontend_in")
        .field("boot_pc", width=64)
        .field("imem_rdata", width=64)
        .field("backend_ready", width=1)
        .field("redirect_valid", width=1)
        .field("redirect_pc", width=64)
        .field("flush_valid", width=1)
        .field("flush_pc", width=64)
        .build()
    )
    ins = m.io_in(in_spec, prefix="")

    boot_pc = ins["boot_pc"].read()
    imem_rdata = ins["imem_rdata"].read()
    backend_ready = ins["backend_ready"].read()
    redirect_valid = ins["redirect_valid"].read()
    redirect_pc = ins["redirect_pc"].read()
    flush_valid = ins["flush_valid"].read()
    flush_pc = ins["flush_pc"].read()

    c = m.const

    do_redirect = redirect_valid | flush_valid
    redirect_target = flush_valid._select_internal(flush_pc, redirect_pc)

    ibuf_push_ready_w = m.new_wire(width=1)
    pred_valid_w = m.new_wire(width=1)
    pred_taken_w = m.new_wire(width=1)
    pred_target_w = m.new_wire(width=64)

    ifetch = m.instance(
        build_ifetch,
        name="ifetch",
        clk=m.as_connector(clk, name="clk"),
        rst=m.as_connector(rst, name="rst"),
        boot_pc=m.as_connector(boot_pc, name="boot_pc"),
        imem_rdata=m.as_connector(imem_rdata, name="imem_rdata"),
        stall_i=m.as_connector(~ibuf_push_ready_w, name="stall_i"),
        redirect_valid=m.as_connector(do_redirect, name="redirect_valid"),
        redirect_pc=m.as_connector(redirect_target, name="redirect_pc"),
        pred_valid=m.as_connector(pred_valid_w, name="pred_valid"),
        pred_taken=m.as_connector(pred_taken_w, name="pred_taken"),
        pred_target=m.as_connector(pred_target_w, name="pred_target"),
    )

    bpu = m.instance(
        build_bpu_lite,
        name="bpu",
        params={"tag_bits": 8},
        clk=m.as_connector(clk, name="clk"),
        rst=m.as_connector(rst, name="rst"),
        req_valid=ifetch["fetch_valid"],
        req_pc=ifetch["fetch_pc"],
        update_valid=m.as_connector(do_redirect, name="update_valid"),
        update_pc=m.as_connector(redirect_target, name="update_pc"),
        update_taken=m.as_connector(do_redirect, name="update_taken"),
        update_target=m.as_connector(redirect_target, name="update_target"),
    )

    # Keep prediction disabled for strict lockstep bring-up; FTQ/BPU metadata
    # remains available for later tuning milestones.
    m.assign(pred_valid_w, c(0, width=1))
    m.assign(pred_taken_w, c(0, width=1))
    m.assign(pred_target_w, c(0, width=64))

    ibuf = m.instance(
        build_ibuffer,
        name="ibuffer",
        params={"depth": ibuf_depth_i},
        clk=m.as_connector(clk, name="clk"),
        rst=m.as_connector(rst, name="rst"),
        push_valid=ifetch["fetch_valid"],
        push_pc=ifetch["fetch_pc"],
        push_window=ifetch["fetch_window"],
        pop_ready=m.as_connector(backend_ready, name="backend_ready"),
        flush_valid=m.as_connector(do_redirect, name="flush_valid"),
    )
    m.assign(ibuf_push_ready_w, ibuf["push_ready"])

    ftq = m.instance(
        build_ftq_lite,
        name="ftq",
        params={"depth": ftq_depth_i},
        clk=m.as_connector(clk, name="clk"),
        rst=m.as_connector(rst, name="rst"),
        enq_valid=ifetch["fetch_valid"],
        enq_pc=ifetch["fetch_pc"],
        enq_npc=ifetch["fetch_next_pc"],
        enq_checkpoint=bpu["checkpoint_id"],
        deq_ready=ibuf["pop_fire"],
        flush_valid=m.as_connector(do_redirect, name="flush_valid"),
    )

    out_spec = (
        meta.bundle("frontend_out")
        .field("imem_raddr", width=64)
        .field("f4_valid", width=1)
        .field("f4_pc", width=64)
        .field("f4_window", width=64)
        .field("fpc", width=64)
        .field("ftq_head", width=ftq_w)
        .field("ftq_tail", width=ftq_w)
        .field("ftq_count", width=ftq_w + 1)
        .field("checkpoint_id", width=6)
        .build()
    )
    m.io_out(
        out_spec,
        {
            "imem_raddr": ifetch["imem_raddr"],
            "f4_valid": ibuf["out_valid"],
            "f4_pc": ibuf["out_pc"],
            "f4_window": ibuf["out_window"],
            "fpc": ifetch["fpc_dbg"],
            "ftq_head": ftq["head_dbg"],
            "ftq_tail": ftq["tail_dbg"],
            "ftq_count": ftq["count_dbg"],
            "checkpoint_id": ftq["checkpoint_dbg"],
        },
        prefix="",
    )


build_frontend.__pycircuit_name__ = "LinxCoreFrontend"
