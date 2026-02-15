from __future__ import annotations

from pycircuit import Circuit, module


@module(name="JanusBccIexFsu")
def build_janus_bcc_iex_fsu(m: Circuit) -> None:
    in_valid_i1 = m.input("in_valid_i1", width=1)
    in_rob_i1 = m.input("in_rob_i1", width=6)
    in_imm_i1 = m.input("in_imm_i1", width=64)

    # Keep FSU deterministic in M1: bitwise invert immediate.
    m.output("out_valid_w1", in_valid_i1)
    m.output("out_rob_w1", in_rob_i1)
    m.output("out_value_w1", ~in_imm_i1)
