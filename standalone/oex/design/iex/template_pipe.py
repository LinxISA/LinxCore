from __future__ import annotations

from pycircuit import Circuit, const, module

from ..stage_specs import s2_issue_spec, wb_spec


@module(name="StandaloneOexTemplatePipe")
def build_template_pipe(m: Circuit) -> None:
    in_s = s2_issue_spec(m)
    out_s = wb_spec(m)

    ins = m.inputs(in_s, prefix="in_")
    c = m.const

    is_template = ins["iq_class"].read().__eq__(c(4, width=3))
    v = ins["valid"].read() & is_template

    m.outputs(
        out_s,
        {
            "seq": ins["seq"],
            "rob": ins["rob"],
            "dst_valid": c(0, width=1),
            "dst_reg": c(0, width=8),
            "dst_data": ins["raw"],
            "valid": v,
        },
        prefix="out_",
    )
