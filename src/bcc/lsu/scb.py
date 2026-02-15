from __future__ import annotations

from pycircuit import Circuit, module


@module(name="JanusBccLsuScb")
def build_janus_bcc_lsu_scb(m: Circuit) -> None:
    stq_head_store_valid_stq = m.input("stq_head_store_valid_stq", width=1)
    stq_head_store_addr_stq = m.input("stq_head_store_addr_stq", width=64)
    stq_head_store_data_stq = m.input("stq_head_store_data_stq", width=64)

    c = m.const

    m.output("dmem_wvalid_scb", stq_head_store_valid_stq)
    m.output("dmem_waddr_scb", stq_head_store_addr_stq)
    m.output("dmem_wdata_scb", stq_head_store_data_stq)
    m.output("dmem_wstrb_scb", stq_head_store_valid_stq._select_internal(c(0xFF, width=8), c(0, width=8)))
