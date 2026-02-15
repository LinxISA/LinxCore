from __future__ import annotations

from pycircuit import Circuit, module


@module(name="JanusTmuNocPipe")
def build_janus_tmu_noc_pipe(m: Circuit) -> None:
    in_valid_noc = m.input("in_valid_noc", width=1)
    in_data_noc = m.input("in_data_noc", width=64)

    m.output("out_valid_noc", in_valid_noc)
    m.output("out_data_noc", in_data_noc)
