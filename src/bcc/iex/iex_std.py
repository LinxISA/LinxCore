from __future__ import annotations

from pycircuit import Circuit, module


@module(name="JanusBccIexStd")
def build_janus_bcc_iex_std(m: Circuit) -> None:
    in_valid_i1 = m.input("in_valid_i1", width=1)
    in_rob_i1 = m.input("in_rob_i1", width=6)
    in_imm_i1 = m.input("in_imm_i1", width=64)

    m.output("store_req_valid_e1", in_valid_i1)
    m.output("store_req_rob_e1", in_rob_i1)
    m.output("store_req_data_e1", in_imm_i1)
