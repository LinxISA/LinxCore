from __future__ import annotations

from pycircuit import Circuit, module

from common.decode import decode_window


@module(name="LinxCoreDecodeWindow")
def build_decode_window(m: Circuit) -> None:
    window = m.input("window", width=64)
    dec = decode_window(m, window)

    m.output("op", dec.op)
    m.output("len_bytes", dec.len_bytes)
    m.output("regdst", dec.regdst)
    m.output("srcl", dec.srcl)
    m.output("srcr", dec.srcr)
    m.output("srcr_type", dec.srcr_type)
    m.output("shamt", dec.shamt)
    m.output("srcp", dec.srcp)
    m.output("imm", dec.imm)
