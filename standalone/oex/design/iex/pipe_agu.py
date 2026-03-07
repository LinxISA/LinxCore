from __future__ import annotations

from pycircuit import Circuit, const, module

from ..stage_specs import mem_req_spec, s2_issue_spec, wb_spec


@module(name="StandaloneOexPipeAgu")
def build_pipe_agu(m: Circuit) -> None:
    in_s = s2_issue_spec(m)
    wb_s = wb_spec(m)
    mem_s = mem_req_spec(m)

    ins = m.inputs(in_s, prefix="in_")

    c = m.const
    raw = ins["raw"].read()
    addr = ins["pc"].read() + raw[16:48]._zext(width=64)

    m.outputs(
        wb_s,
        {
            "seq": ins["seq"],
            "rob": ins["rob"],
            "dst_valid": c(0, width=1),
            "dst_reg": c(0, width=8),
            "dst_data": c(0, width=64),
            "valid": c(0, width=1),
        },
        prefix="wb_",
    )

    m.outputs(
        mem_s,
        {
            "seq": ins["seq"],
            "mem_idx": c(0, width=16),
            "kind": c(0, width=1),  # load
            "pc": ins["pc"],
            "addr": addr,
            "size": c(8, width=8),
            "data": c(0, width=64),
            "valid": ins["valid"],
        },
        prefix="mem_",
    )
