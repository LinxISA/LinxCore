from __future__ import annotations

from pycircuit import Circuit, const, module

from ..stage_specs import d3_spec, s2_issue_spec


@module(name="StandaloneOexOooS2")
def build_s2(m: Circuit) -> None:
    in_s = d3_spec(m)
    out_s = s2_issue_spec(m)

    ins = m.inputs(in_s, prefix="in_")

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
            "valid": ins["valid"],
        },
        prefix="out_",
    )
