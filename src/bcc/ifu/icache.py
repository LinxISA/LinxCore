from __future__ import annotations

from pycircuit import Circuit, module


@module(name="JanusBccIfuICache")
def build_janus_bcc_ifu_icache(m: Circuit) -> None:
    clk_f1 = m.clock("clk")
    rst_f1 = m.reset("rst")

    f1_to_icache_stage_pc_f1 = m.input("f1_to_icache_stage_pc_f1", width=64)
    f1_to_icache_stage_valid_f1 = m.input("f1_to_icache_stage_valid_f1", width=1)
    f1_to_icache_stage_pkt_uid_f1 = m.input("f1_to_icache_stage_pkt_uid_f1", width=64)
    imem_rdata_top = m.input("imem_rdata_top", width=64)

    c = m.const

    req_pc_icache = m.out("req_pc_icache", clk=clk_f1, rst=rst_f1, width=64, init=c(0, width=64), en=c(1, width=1))
    req_valid_icache = m.out("req_valid_icache", clk=clk_f1, rst=rst_f1, width=1, init=c(0, width=1), en=c(1, width=1))
    req_pkt_uid_icache = m.out("req_pkt_uid_icache", clk=clk_f1, rst=rst_f1, width=64, init=c(0, width=64), en=c(1, width=1))

    rsp_pc_icache = m.out("rsp_pc_icache", clk=clk_f1, rst=rst_f1, width=64, init=c(0, width=64), en=c(1, width=1))
    rsp_window_icache = m.out("rsp_window_icache", clk=clk_f1, rst=rst_f1, width=64, init=c(0, width=64), en=c(1, width=1))
    rsp_valid_icache = m.out("rsp_valid_icache", clk=clk_f1, rst=rst_f1, width=1, init=c(0, width=1), en=c(1, width=1))
    rsp_pkt_uid_icache = m.out("rsp_pkt_uid_icache", clk=clk_f1, rst=rst_f1, width=64, init=c(0, width=64), en=c(1, width=1))

    req_pc_icache.set(f1_to_icache_stage_pc_f1)
    req_valid_icache.set(f1_to_icache_stage_valid_f1)
    req_pkt_uid_icache.set(f1_to_icache_stage_pkt_uid_f1)

    rsp_pc_icache.set(req_pc_icache.out())
    rsp_window_icache.set(imem_rdata_top)
    rsp_valid_icache.set(req_valid_icache.out())
    rsp_pkt_uid_icache.set(req_pkt_uid_icache.out())

    m.output("imem_raddr_top", f1_to_icache_stage_pc_f1)
    # Keep zero-latency external behavior for lockstep stability; internal
    # req/rsp registers remain for stage ownership/debug visibility.
    m.output("f1_to_f2_stage_pc_f1", f1_to_icache_stage_pc_f1)
    m.output("f1_to_f2_stage_window_f1", imem_rdata_top)
    m.output("f1_to_f2_stage_valid_f1", f1_to_icache_stage_valid_f1)
    m.output("f1_to_f2_stage_pkt_uid_f1", f1_to_icache_stage_pkt_uid_f1)
    m.output("icache_to_f2_stage_pc_f1", rsp_pc_icache.out())
    m.output("icache_to_f2_stage_window_f1", rsp_window_icache.out())
    m.output("icache_to_f2_stage_valid_f1", rsp_valid_icache.out())
    m.output("icache_to_f2_stage_pkt_uid_f1", rsp_pkt_uid_icache.out())
