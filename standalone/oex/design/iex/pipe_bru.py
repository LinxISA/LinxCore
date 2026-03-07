from __future__ import annotations

from pycircuit import Circuit, const, module

from ..stage_specs import s2_issue_spec, wb_spec


@module(name="StandaloneOexPipeBru")
def build_pipe_bru(m: Circuit) -> None:
    in_s = s2_issue_spec(m)
    out_s = wb_spec(m)

    ins = m.inputs(in_s, prefix="in_")
    c = m.const

    redirect_valid = ins["valid"].read() & ins["raw"].read()[0]
    redirect_pc = ins["pc"].read() + ins["len"].read()._zext(width=64)

    m.outputs(
        out_s,
        {
            "seq": ins["seq"],
            "rob": ins["rob"],
            "dst_valid": c(0, width=1),
            "dst_reg": c(0, width=8),
            "dst_data": c(0, width=64),
            "valid": c(0, width=1),
        },
        prefix="out_",
    )

    m.output("redirect_valid", redirect_valid)
    m.output("redirect_pc", redirect_pc)
