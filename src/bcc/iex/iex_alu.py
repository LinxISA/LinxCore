from __future__ import annotations

from pycircuit import Circuit, module


@module(name="JanusBccIexAlu")
def build_janus_bcc_iex_alu(m: Circuit) -> None:
    clk_i1 = m.clock("clk")
    rst_i1 = m.reset("rst")

    in_valid_i1 = m.input("in_valid_i1", width=1)
    in_rob_i1 = m.input("in_rob_i1", width=6)
    in_pc_i1 = m.input("in_pc_i1", width=64)
    in_imm_i1 = m.input("in_imm_i1", width=64)

    c = m.const

    p_valid_w1 = m.out("p_valid_w1", clk=clk_i1, rst=rst_i1, width=1, init=c(0, width=1), en=c(1, width=1))
    p_rob_w1 = m.out("p_rob_w1", clk=clk_i1, rst=rst_i1, width=6, init=c(0, width=6), en=c(1, width=1))
    p_value_w1 = m.out("p_value_w1", clk=clk_i1, rst=rst_i1, width=64, init=c(0, width=64), en=c(1, width=1))

    result_e1 = in_pc_i1 + in_imm_i1

    p_valid_w1.set(in_valid_i1)
    p_rob_w1.set(in_rob_i1, when=in_valid_i1)
    p_value_w1.set(result_e1, when=in_valid_i1)

    m.output("out_valid_w1", p_valid_w1.out())
    m.output("out_rob_w1", p_rob_w1.out())
    m.output("out_value_w1", p_value_w1.out())
