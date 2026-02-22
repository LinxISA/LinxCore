from __future__ import annotations

from pycircuit import Circuit, module


@module(name="JanusBccIexStd")
def build_janus_bcc_iex_std(m: Circuit) -> None:
    clk_i1 = m.clock("clk")
    rst_i1 = m.reset("rst")

    in_valid_i1 = m.input("in_valid_i1", width=1)
    in_rob_i1 = m.input("in_rob_i1", width=6)
    in_imm_i1 = m.input("in_imm_i1", width=64)

    c = m.const

    req_valid_e1 = m.out("req_valid_e1", clk=clk_i1, rst=rst_i1, width=1, init=c(0, width=1), en=c(1, width=1))
    req_rob_e1 = m.out("req_rob_e1", clk=clk_i1, rst=rst_i1, width=6, init=c(0, width=6), en=c(1, width=1))
    req_data_e1 = m.out("req_data_e1", clk=clk_i1, rst=rst_i1, width=64, init=c(0, width=64), en=c(1, width=1))
    req_size_e1 = m.out("req_size_e1", clk=clk_i1, rst=rst_i1, width=4, init=c(8, width=4), en=c(1, width=1))

    req_valid_e1.set(in_valid_i1)
    req_rob_e1.set(in_rob_i1, when=in_valid_i1)
    req_data_e1.set(in_imm_i1, when=in_valid_i1)
    req_size_e1.set(c(8, width=4), when=in_valid_i1)

    m.output("store_req_valid_e1", req_valid_e1.out())
    m.output("store_req_rob_e1", req_rob_e1.out())
    m.output("store_req_data_e1", req_data_e1.out())
    m.output("store_req_size_e1", req_size_e1.out())
