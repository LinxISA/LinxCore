from __future__ import annotations

from pycircuit import Circuit, const, module

from ..stage_specs import d1_spec, inst_event_spec


@module(name="StandaloneOexOooDec1")
def build_dec1(m: Circuit) -> None:
    in_s = inst_event_spec(m)
    out_s = d1_spec(m)

    ins = m.inputs(in_s, prefix="in_")
    c = m.const

    raw = ins["raw"].read()
    ln = ins["len"].read()
    raw_lo2 = raw._trunc(width=2)
    raw_lo7 = raw._trunc(width=7)

    is_branch = raw_lo2.__eq__(c(2, width=2))
    is_mem = raw_lo2.__eq__(c(3, width=2))
    is_macro = ln.__eq__(c(4, width=8)) & (raw._trunc(width=7).__eq__(c(0x41, width=7)))
    is_cmd = raw_lo7.__eq__(c(0x6B, width=7))
    is_fsu = raw_lo7.__eq__(c(0x53, width=7))

    op_class = c(0, width=4)
    op_class = is_branch._select_internal(c(1, width=4), op_class)
    op_class = is_mem._select_internal(c(2, width=4), op_class)
    op_class = is_macro._select_internal(c(4, width=4), op_class)
    op_class = is_cmd._select_internal(c(6, width=4), op_class)
    op_class = is_fsu._select_internal(c(7, width=4), op_class)

    m.outputs(
        out_s,
        {
            "seq": ins["seq"],
            "pc": ins["pc"],
            "raw": ins["raw"],
            "len": ins["len"],
            "op_class": op_class,
            "valid": ins["valid"],
        },
        prefix="out_",
    )
