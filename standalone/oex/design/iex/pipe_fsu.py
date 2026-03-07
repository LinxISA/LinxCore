from __future__ import annotations

from pycircuit import Circuit, module

from ..stage_specs import s2_issue_spec, wb_spec


@module(name="StandaloneOexPipeFsu")
def build_pipe_fsu(m: Circuit) -> None:
    in_s = s2_issue_spec(m)
    out_s = wb_spec(m)

    ins = m.inputs(in_s, prefix="in_")
    c = m.const

    # Phase-3 bootstrap FSU model:
    # keep latency simple while exercising separate issue/writeback path.
    fsu_val = ins["raw"].read() + ins["pc"].read() + c(0xF500, width=64)

    m.outputs(
        out_s,
        {
            "seq": ins["seq"],
            "rob": ins["rob"],
            "dst_valid": ~(ins["dst_ptag"].read().__eq__(c(0, width=8))),
            "dst_reg": ins["dst_ptag"],
            "dst_data": fsu_val,
            "valid": ins["valid"],
        },
        prefix="out_",
    )
