from __future__ import annotations

from pycircuit import Circuit, const, module

from ..stage_specs import s2_issue_spec, wb_spec


@module(name="StandaloneOexPipeAlu")
def build_pipe_alu(m: Circuit) -> None:
    in_s = s2_issue_spec(m)
    out_s = wb_spec(m)

    ins = m.inputs(in_s, prefix="in_")

    c = m.const
    raw = ins["raw"].read()
    pc = ins["pc"].read()
    val = raw + pc

    m.outputs(
        out_s,
        {
            "seq": ins["seq"],
            "rob": ins["rob"],
            "dst_valid": ~(ins["dst_ptag"].read().__eq__(c(0, width=8))),
            "dst_reg": ins["dst_ptag"],
            "dst_data": val,
            "valid": ins["valid"],
        },
        prefix="out_",
    )
