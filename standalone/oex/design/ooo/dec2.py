from __future__ import annotations

from pycircuit import Circuit, const, module

from ..stage_specs import d1_spec, d2_spec


@module(name="StandaloneOexOooDec2")
def build_dec2(m: Circuit) -> None:
    in_s = d1_spec(m)
    out_s = d2_spec(m)

    ins = m.inputs(in_s, prefix="in_")
    c = m.const

    raw = ins["raw"].read()
    op_class = ins["op_class"].read()

    src0 = raw[7:13]
    src1 = raw[13:19]
    dst = raw[19:25]

    is_bru = op_class.__eq__(c(1, width=4))
    is_mem = op_class.__eq__(c(2, width=4))
    is_tpl = op_class.__eq__(c(4, width=4))
    is_cmd = op_class.__eq__(c(6, width=4))
    is_fsu = op_class.__eq__(c(7, width=4))

    iq_class = c(0, width=3)  # ALU
    iq_class = is_bru._select_internal(c(1, width=3), iq_class)
    iq_class = is_mem._select_internal(c(2, width=3), iq_class)  # AGU by default
    iq_class = (is_mem & raw[2])._select_internal(c(3, width=3), iq_class)  # STD heuristic
    iq_class = is_tpl._select_internal(c(4, width=3), iq_class)
    iq_class = is_cmd._select_internal(c(5, width=3), iq_class)
    iq_class = is_fsu._select_internal(c(6, width=3), iq_class)

    m.outputs(
        out_s,
        {
            "seq": ins["seq"],
            "pc": ins["pc"],
            "raw": ins["raw"],
            "len": ins["len"],
            "op_class": ins["op_class"],
            "src0_atag": src0,
            "src1_atag": src1,
            "dst_atag": dst,
            "iq_class": iq_class,
            "valid": ins["valid"],
        },
        prefix="out_",
    )
