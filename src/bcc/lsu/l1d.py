from __future__ import annotations

from pycircuit import Circuit, module


@module(name="JanusBccLsuL1D")
def build_janus_bcc_lsu_l1d(m: Circuit) -> None:
    load_req_valid_l1d = m.input("load_req_valid_l1d", width=1)
    load_req_rob_l1d = m.input("load_req_rob_l1d", width=6)
    load_req_addr_l1d = m.input("load_req_addr_l1d", width=64)
    dmem_rdata_top = m.input("dmem_rdata_top", width=64)

    m.output("dmem_raddr_l1d", load_req_addr_l1d)
    m.output("lsu_to_rob_stage_load_valid_l1d", load_req_valid_l1d)
    m.output("lsu_to_rob_stage_load_rob_l1d", load_req_rob_l1d)
    m.output("lsu_to_rob_stage_load_addr_l1d", load_req_addr_l1d)
    m.output("lsu_to_rob_stage_load_data_l1d", dmem_rdata_top)
