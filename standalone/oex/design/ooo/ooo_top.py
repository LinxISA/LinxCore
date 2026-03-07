from __future__ import annotations

from pycircuit import Circuit, ct, module

from ..stage_specs import d3_spec, inst_event_spec, mem_rsp_spec, retire_spec, s2_issue_spec, wb_spec
from .dec1 import build_dec1
from .dec2 import build_dec2
from .flush_ctrl import build_flush_ctrl
from .pc_buffer import build_pc_buffer
from .ren import build_ren
from .rob import build_rob
from .s1 import build_s1
from .s2 import build_s2


@module(name="StandaloneOexOooTop")
def build_ooo_top(
    m: Circuit,
    *,
    rob_depth: int = 64,
    pcbuf_depth: int = 64,
) -> None:
    clk = m.clock("clk")
    rst = m.reset("rst")
    c = m.const
    rob_idx_w = max(1, ct.clog2(int(rob_depth)))
    pcbuf_idx_w = max(1, ct.clog2(int(pcbuf_depth)))

    inst_s = inst_event_spec(m)
    wb_s = wb_spec(m)
    retire_s = retire_spec(m)
    issue_s = s2_issue_spec(m)

    ins = m.inputs(inst_s, prefix="in_")
    wb = m.inputs(wb_s, prefix="wb_")

    iex_redirect_valid = m.input("iex_redirect_valid", width=1)
    iex_redirect_pc = m.input("iex_redirect_pc", width=64)
    iq_enq_ready = m.input("iq_enq_ready", width=1)

    dec1 = m.instance_auto(
        build_dec1,
        name="dec1",
        module_name="StandaloneOexOooDec1Inst",
        in_seq=ins["seq"],
        in_pc=ins["pc"],
        in_raw=ins["raw"],
        in_len=ins["len"],
        in_valid=ins["valid"],
    )

    dec2 = m.instance_auto(
        build_dec2,
        name="dec2",
        module_name="StandaloneOexOooDec2Inst",
        in_seq=dec1["out_seq"],
        in_pc=dec1["out_pc"],
        in_raw=dec1["out_raw"],
        in_len=dec1["out_len"],
        in_op_class=dec1["out_op_class"],
        in_valid=dec1["out_valid"],
    )

    flush = m.instance_auto(
        build_flush_ctrl,
        name="flush",
        module_name="StandaloneOexFlushCtrlInst",
        clk=clk,
        rst=rst,
        iex_redirect_valid=iex_redirect_valid,
        iex_redirect_pc=iex_redirect_pc,
        rob_redirect_valid=c(0, width=1),
        rob_redirect_pc=c(0, width=64),
    )

    ren = m.instance_auto(
        build_ren,
        name="ren",
        module_name="StandaloneOexOooRenInst",
        params={"rob_w": int(rob_idx_w), "ptag_w": 8},
        clk=clk,
        rst=rst,
        in_seq=dec2["out_seq"],
        in_pc=dec2["out_pc"],
        in_raw=dec2["out_raw"],
        in_len=dec2["out_len"],
        in_op_class=dec2["out_op_class"],
        in_src0_atag=dec2["out_src0_atag"],
        in_src1_atag=dec2["out_src1_atag"],
        in_dst_atag=dec2["out_dst_atag"],
        in_iq_class=dec2["out_iq_class"],
        in_valid=dec2["out_valid"],
        flush_i=flush["flush_valid"],
    )

    s1 = m.instance_auto(
        build_s1,
        name="s1",
        module_name="StandaloneOexOooS1Inst",
        in_seq=ren["out_seq"],
        in_pc=ren["out_pc"],
        in_raw=ren["out_raw"],
        in_len=ren["out_len"],
        in_op_class=ren["out_op_class"],
        in_src0_ptag=ren["out_src0_ptag"],
        in_src1_ptag=ren["out_src1_ptag"],
        in_dst_ptag=ren["out_dst_ptag"],
        in_rob=ren["out_rob"],
        in_iq_class=ren["out_iq_class"],
        in_valid=ren["out_valid"],
        iq_enq_ready=iq_enq_ready,
    )

    s2 = m.instance_auto(
        build_s2,
        name="s2",
        module_name="StandaloneOexOooS2Inst",
        in_seq=s1["out_seq"],
        in_pc=s1["out_pc"],
        in_raw=s1["out_raw"],
        in_len=s1["out_len"],
        in_op_class=s1["out_op_class"],
        in_src0_ptag=s1["out_src0_ptag"],
        in_src1_ptag=s1["out_src1_ptag"],
        in_dst_ptag=s1["out_dst_ptag"],
        in_rob=s1["out_rob"],
        in_iq_class=s1["out_iq_class"],
        in_valid=s1["out_valid"],
    )

    rob = m.instance_auto(
        build_rob,
        name="rob",
        module_name="StandaloneOexRobInst",
        params={"depth": int(rob_depth), "idx_w": int(rob_idx_w)},
        clk=clk,
        rst=rst,
        alloc_seq=ren["out_seq"],
        alloc_pc=ren["out_pc"],
        alloc_raw=ren["out_raw"],
        alloc_len=ren["out_len"],
        alloc_op_class=ren["out_op_class"],
        alloc_src0_ptag=ren["out_src0_ptag"],
        alloc_src1_ptag=ren["out_src1_ptag"],
        alloc_dst_ptag=ren["out_dst_ptag"],
        alloc_rob=ren["out_rob"],
        alloc_iq_class=ren["out_iq_class"],
        alloc_valid=ren["out_valid"],
        wb_seq=wb["seq"],
        wb_rob=wb["rob"],
        wb_dst_valid=wb["dst_valid"],
        wb_dst_reg=wb["dst_reg"],
        wb_dst_data=wb["dst_data"],
        wb_valid=wb["valid"],
        flush_i=flush["flush_valid"],
    )

    # PC buffer side visibility (optional in debug profile).
    _ = m.instance_auto(
        build_pc_buffer,
        name="pcbuf",
        module_name="StandaloneOexPcBufferInst",
        params={"depth": int(pcbuf_depth), "idx_w": int(pcbuf_idx_w)},
        clk=clk,
        rst=rst,
        wr_valid=ren["out_valid"],
        wr_idx=ren["out_rob"][0:pcbuf_idx_w],
        wr_pc=ren["out_pc"],
        rd_idx=rob["rob_head"][0:pcbuf_idx_w],
    )

    # Issue output to IEX.
    m.outputs(
        issue_s,
        {
            "seq": s2["out_seq"],
            "pc": s2["out_pc"],
            "raw": s2["out_raw"],
            "len": s2["out_len"],
            "op_class": s2["out_op_class"],
            "src0_ptag": s2["out_src0_ptag"],
            "src1_ptag": s2["out_src1_ptag"],
            "dst_ptag": s2["out_dst_ptag"],
            "rob": s2["out_rob"],
            "iq_class": s2["out_iq_class"],
            "valid": s2["out_valid"],
        },
        prefix="issue_",
    )

    m.outputs(
        retire_s,
        {
            "seq": rob["out_seq"],
            "pc": rob["out_pc"],
            "raw": rob["out_raw"],
            "len": rob["out_len"],
            "src0_valid": rob["out_src0_valid"],
            "src0_reg": rob["out_src0_reg"],
            "src0_data": rob["out_src0_data"],
            "src1_valid": rob["out_src1_valid"],
            "src1_reg": rob["out_src1_reg"],
            "src1_data": rob["out_src1_data"],
            "dst_valid": rob["out_dst_valid"],
            "dst_reg": rob["out_dst_reg"],
            "dst_data": rob["out_dst_data"],
            "mem_valid": rob["out_mem_valid"],
            "mem_is_store": rob["out_mem_is_store"],
            "mem_addr": rob["out_mem_addr"],
            "mem_wdata": rob["out_mem_wdata"],
            "mem_rdata": rob["out_mem_rdata"],
            "mem_size": rob["out_mem_size"],
            "trap_valid": rob["out_trap_valid"],
            "trap_cause": rob["out_trap_cause"],
            "traparg0": rob["out_traparg0"],
            "next_pc": rob["out_next_pc"],
            "valid": rob["out_valid"],
        },
        prefix="retire_",
    )

    m.output("flush_valid", flush["flush_valid"])
    m.output("flush_pc", flush["flush_pc"])
    m.output("s1_stall_o", s1["stall_o"])
