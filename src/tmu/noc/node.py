from __future__ import annotations

from pycircuit import Circuit, module

from .pipe import build_janus_tmu_noc_pipe


@module(name="JanusTmuNocNode")
def build_janus_tmu_noc_node(m: Circuit) -> None:
    in_valid_noc = m.input("in_valid_noc", width=1)
    in_data_noc = m.input("in_data_noc", width=64)

    pipe_noc = m.instance(
        build_janus_tmu_noc_pipe,
        name="noc_pipe",
        module_name="JanusTmuNocPipeInst",
        in_valid_noc=in_valid_noc,
        in_data_noc=in_data_noc,
    )

    m.output("out_valid_noc", pipe_noc["out_valid_noc"])
    m.output("out_data_noc", pipe_noc["out_data_noc"])
