from __future__ import annotations

from pycircuit import Circuit, const, module

from ..stage_specs import d3_spec


@module(name="StandaloneOexOooS1")
def build_s1(m: Circuit) -> None:
    in_s = d3_spec(m)
    out_s = d3_spec(m)

    ins = m.inputs(in_s, prefix="in_")
    iq_enq_ready = m.input("iq_enq_ready", width=1)

    out_valid = ins["valid"].read() & iq_enq_ready
    stall_o = ins["valid"].read() & (~iq_enq_ready)
    m.outputs(
        out_s,
        {
            "seq": ins["seq"],
            "pc": ins["pc"],
            "raw": ins["raw"],
            "len": ins["len"],
            "op_class": ins["op_class"],
            "src0_ptag": ins["src0_ptag"],
            "src1_ptag": ins["src1_ptag"],
            "dst_ptag": ins["dst_ptag"],
            "rob": ins["rob"],
            "iq_class": ins["iq_class"],
            "valid": out_valid,
        },
        prefix="out_",
    )
    m.output("stall_o", stall_o)
