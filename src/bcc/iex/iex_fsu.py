from __future__ import annotations

from pycircuit import Circuit, module


@module(name="JanusBccIexFsu")
def build_janus_bcc_iex_fsu(m: Circuit) -> None:
    clk_i1 = m.clock("clk")
    rst_i1 = m.reset("rst")

    in_valid_i1 = m.input("in_valid_i1", width=1)
    in_rob_i1 = m.input("in_rob_i1", width=6)
    in_imm_i1 = m.input("in_imm_i1", width=64)

    c = m.const

    s0_valid_e1 = m.out("s0_valid_e1", clk=clk_i1, rst=rst_i1, width=1, init=c(0, width=1), en=c(1, width=1))
    s0_rob_e1 = m.out("s0_rob_e1", clk=clk_i1, rst=rst_i1, width=6, init=c(0, width=6), en=c(1, width=1))
    s0_val_e1 = m.out("s0_val_e1", clk=clk_i1, rst=rst_i1, width=64, init=c(0, width=64), en=c(1, width=1))

    s1_valid_w1 = m.out("s1_valid_w1", clk=clk_i1, rst=rst_i1, width=1, init=c(0, width=1), en=c(1, width=1))
    s1_rob_w1 = m.out("s1_rob_w1", clk=clk_i1, rst=rst_i1, width=6, init=c(0, width=6), en=c(1, width=1))
    s1_val_w1 = m.out("s1_val_w1", clk=clk_i1, rst=rst_i1, width=64, init=c(0, width=64), en=c(1, width=1))

    s0_valid_e1.set(in_valid_i1)
    s0_rob_e1.set(in_rob_i1, when=in_valid_i1)
    s0_val_e1.set(~in_imm_i1, when=in_valid_i1)

    s1_valid_w1.set(s0_valid_e1.out())
    s1_rob_w1.set(s0_rob_e1.out(), when=s0_valid_e1.out())
    s1_val_w1.set(s0_val_e1.out(), when=s0_valid_e1.out())

    m.output("out_valid_w1", s1_valid_w1.out())
    m.output("out_rob_w1", s1_rob_w1.out())
    m.output("out_value_w1", s1_val_w1.out())
