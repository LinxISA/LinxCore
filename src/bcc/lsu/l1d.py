from __future__ import annotations

from pycircuit import Circuit, module


@module(name="JanusBccLsuL1D")
def build_janus_bcc_lsu_l1d(m: Circuit) -> None:
    clk_l1d = m.clock("clk")
    rst_l1d = m.reset("rst")

    load_req_valid_l1d = m.input("load_req_valid_l1d", width=1)
    load_req_rob_l1d = m.input("load_req_rob_l1d", width=6)
    load_req_addr_l1d = m.input("load_req_addr_l1d", width=64)
    dmem_rdata_top = m.input("dmem_rdata_top", width=64)

    c = m.const

    req_valid_l1d = m.out("req_valid_l1d", clk=clk_l1d, rst=rst_l1d, width=1, init=c(0, width=1), en=c(1, width=1))
    req_rob_l1d = m.out("req_rob_l1d", clk=clk_l1d, rst=rst_l1d, width=6, init=c(0, width=6), en=c(1, width=1))
    req_addr_l1d = m.out("req_addr_l1d", clk=clk_l1d, rst=rst_l1d, width=64, init=c(0, width=64), en=c(1, width=1))

    req_valid_l1d.set(load_req_valid_l1d)
    req_rob_l1d.set(load_req_rob_l1d, when=load_req_valid_l1d)
    req_addr_l1d.set(load_req_addr_l1d, when=load_req_valid_l1d)

    m.output("dmem_raddr_l1d", req_addr_l1d.out())
    m.output("lsu_to_rob_stage_load_valid_l1d", req_valid_l1d.out())
    m.output("lsu_to_rob_stage_load_rob_l1d", req_rob_l1d.out())
    m.output("lsu_to_rob_stage_load_addr_l1d", req_addr_l1d.out())
    m.output("lsu_to_rob_stage_load_data_l1d", dmem_rdata_top)
