from __future__ import annotations

from pycircuit import Circuit, module

from .pipe import build_janus_tmu_noc_pipe


@module(name="JanusTmuNocNode")
def build_janus_tmu_noc_node(m: Circuit) -> None:
    clk_noc = m.clock("clk")
    rst_noc = m.reset("rst")

    in_valid_noc = m.input("in_valid_noc", width=1)
    in_data_noc = m.input("in_data_noc", width=64)
    out_ready_noc = m.input("out_ready_noc", width=1)

    c = m.const

    ingress_valid_noc = m.out("ingress_valid_noc", clk=clk_noc, rst=rst_noc, width=1, init=c(0, width=1), en=c(1, width=1))
    ingress_data_noc = m.out("ingress_data_noc", clk=clk_noc, rst=rst_noc, width=64, init=c(0, width=64), en=c(1, width=1))

    ingress_accept_noc = (~ingress_valid_noc.out()) | out_ready_noc
    ingress_fire_noc = in_valid_noc & ingress_accept_noc

    ingress_valid_next_noc = ingress_valid_noc.out()
    ingress_valid_next_noc = out_ready_noc._select_internal(c(0, width=1), ingress_valid_next_noc)
    ingress_valid_next_noc = ingress_fire_noc._select_internal(c(1, width=1), ingress_valid_next_noc)

    ingress_valid_noc.set(ingress_valid_next_noc)
    ingress_data_noc.set(in_data_noc, when=ingress_fire_noc)

    pipe_noc = m.instance(
        build_janus_tmu_noc_pipe,
        name="noc_pipe",
        module_name="JanusTmuNocPipeInst",
        clk=clk_noc,
        rst=rst_noc,
        in_valid_noc=ingress_valid_noc.out(),
        in_data_noc=ingress_data_noc.out(),
        out_ready_noc=out_ready_noc,
    )

    m.output("in_ready_noc", ingress_accept_noc)
    m.output("out_valid_noc", pipe_noc["out_valid_noc"])
    m.output("out_data_noc", pipe_noc["out_data_noc"])
