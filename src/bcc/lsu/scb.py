from __future__ import annotations

from pycircuit import Circuit, module


@module(name="JanusBccLsuScb")
def build_janus_bcc_lsu_scb(m: Circuit) -> None:
    clk_scb = m.clock("clk")
    rst_scb = m.reset("rst")

    stq_head_store_valid_stq = m.input("stq_head_store_valid_stq", width=1)
    stq_head_store_addr_stq = m.input("stq_head_store_addr_stq", width=64)
    stq_head_store_data_stq = m.input("stq_head_store_data_stq", width=64)

    c = m.const

    hold_valid_scb = m.out("hold_valid_scb", clk=clk_scb, rst=rst_scb, width=1, init=c(0, width=1), en=c(1, width=1))
    hold_addr_scb = m.out("hold_addr_scb", clk=clk_scb, rst=rst_scb, width=64, init=c(0, width=64), en=c(1, width=1))
    hold_data_scb = m.out("hold_data_scb", clk=clk_scb, rst=rst_scb, width=64, init=c(0, width=64), en=c(1, width=1))

    enqueue_scb = stq_head_store_valid_stq & (~hold_valid_scb.out())
    hold_valid_next_scb = hold_valid_scb.out()
    hold_valid_next_scb = enqueue_scb._select_internal(c(1, width=1), hold_valid_next_scb)
    hold_valid_next_scb = hold_valid_scb.out()._select_internal(c(0, width=1), hold_valid_next_scb)

    hold_valid_scb.set(hold_valid_next_scb)
    hold_addr_scb.set(stq_head_store_addr_stq, when=enqueue_scb)
    hold_data_scb.set(stq_head_store_data_stq, when=enqueue_scb)

    dmem_wvalid_scb = hold_valid_scb.out()
    m.output("dmem_wvalid_scb", dmem_wvalid_scb)
    m.output("dmem_waddr_scb", hold_addr_scb.out())
    m.output("dmem_wdata_scb", hold_data_scb.out())
    m.output("dmem_wstrb_scb", dmem_wvalid_scb._select_internal(c(0xFF, width=8), c(0, width=8)))
