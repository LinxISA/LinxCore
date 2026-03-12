from __future__ import annotations

from pycircuit import Circuit, module

from bcc.lsu.dcache_stub import build_janus_bcc_lsu_dcache_stub
from bcc.lsu.scb import build_janus_bcc_lsu_scb


@module(name="LinxCoreTopExportStoreDrain")
def build_export_store_drain(m: Circuit) -> None:
    clk = m.clock("clk")
    rst = m.reset("rst")

    store_valid_i = m.input("store_valid_i", width=1)
    store_addr_i = m.input("store_addr_i", width=64)
    store_data_i = m.input("store_data_i", width=64)

    c = m.const

    dcache_req_ready_top = m.new_wire(width=1)
    dcache_resp_valid_top = m.new_wire(width=1)
    dcache_resp_entry_id_top = m.new_wire(width=4)
    dcache_resp_ok_top = m.new_wire(width=1)
    dcache_resp_err_code_top = m.new_wire(width=4)

    scb_enq_line_top = store_addr_i & (~c(63, width=64))
    scb_enq_mask_top = c(0xFF, width=64)
    scb_enq_data_top = store_data_i._zext(width=512)
    scb_sid_ctr_top = m.out("scb_sid_ctr_top", clk=clk, rst=rst, width=6, init=c(1, width=6), en=c(1, width=1))

    scb_top = m.instance_auto(
        build_janus_bcc_lsu_scb,
        name="janus_scb",
        module_name="JanusBccLsuScbTop",
        clk=clk,
        rst=rst,
        enq_valid=store_valid_i,
        enq_line=scb_enq_line_top,
        enq_mask=scb_enq_mask_top,
        enq_data=scb_enq_data_top,
        enq_sid=scb_sid_ctr_top.out(),
        chi_req_ready=dcache_req_ready_top,
        chi_resp_valid=dcache_resp_valid_top,
        chi_resp_txnid=dcache_resp_entry_id_top,
    )
    scb_enq_fire_top = store_valid_i & scb_top["enq_ready"]
    scb_sid_ctr_top.set((scb_sid_ctr_top.out() + c(1, width=6))._trunc(width=6), when=scb_enq_fire_top)

    dcache_stub_top = m.instance_auto(
        build_janus_bcc_lsu_dcache_stub,
        name="janus_dcache_stub",
        module_name="JanusBccLsuDCacheStubTop",
        clk=clk,
        rst=rst,
        dcache_req_valid=scb_top["chi_req_valid"],
        dcache_req_entry_id=scb_top["chi_req_txnid"],
        dcache_req_line=scb_top["chi_req_addr"],
        dcache_req_mask=scb_top["chi_req_strb"],
        dcache_req_data=scb_top["chi_req_data"],
        dcache_resp_ready=c(1, width=1),
    )
    m.assign(dcache_req_ready_top, dcache_stub_top["dcache_req_ready"])
    m.assign(dcache_resp_valid_top, dcache_stub_top["dcache_resp_valid"])
    m.assign(dcache_resp_entry_id_top, dcache_stub_top["dcache_resp_entry_id"])
    m.assign(dcache_resp_ok_top, dcache_stub_top["dcache_resp_ok"])
    m.assign(dcache_resp_err_code_top, dcache_stub_top["dcache_resp_err_code"])


build_export_store_drain.__pycircuit_name__ = "LinxCoreTopExportStoreDrain"
