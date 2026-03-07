from __future__ import annotations

from pycircuit import Circuit, module

from common.module_specs import backend_frontend_in_spec


@module(name="LinxCoreIngressDecode")
def build_ingress_decode(m: Circuit) -> None:
    clk = m.clock("clk")
    rst = m.reset("rst")
    c = m.const

    in_spec = backend_frontend_in_spec(m)
    ins = m.inputs(in_spec, prefix="in_")
    ready_i = m.input("ready_i", width=1)

    seen_any = m.out("seen_any", clk=clk, rst=rst, width=1, init=c(0, width=1), en=c(1, width=1))
    fire = ins["f4_valid"].read() & ready_i
    seen_any.set(fire | seen_any.out())

    m.outputs(
        in_spec,
        {
            "f4_valid": fire,
            "f4_pc": ins["f4_pc"].read(),
            "f4_window": ins["f4_window"].read(),
            "f4_checkpoint": ins["f4_checkpoint"].read(),
            "f4_pkt_uid": ins["f4_pkt_uid"].read(),
        },
        prefix="out_",
    )
    m.output("fire_o", fire)
    m.output("seen_any_o", seen_any.out())
