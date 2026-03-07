from __future__ import annotations

from pycircuit import Circuit, module

from ..stage_specs import s2_issue_spec, wb_spec


@module(name="StandaloneOexPipeCmd")
def build_pipe_cmd(m: Circuit) -> None:
    in_s = s2_issue_spec(m)
    out_s = wb_spec(m)

    ins = m.inputs(in_s, prefix="in_")
    c = m.const

    # Phase-3 bootstrap CMD model:
    # encode a deterministic result so rename/ROB/writeback paths are exercised.
    cmd_val = (ins["raw"].read() ^ c(0x00C0D00D, width=64)) + ins["pc"].read()

    m.outputs(
        out_s,
        {
            "seq": ins["seq"],
            "rob": ins["rob"],
            "dst_valid": ~(ins["dst_ptag"].read().__eq__(c(0, width=8))),
            "dst_reg": ins["dst_ptag"],
            "dst_data": cmd_val,
            "valid": ins["valid"],
        },
        prefix="out_",
    )
