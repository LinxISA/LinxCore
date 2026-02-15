from __future__ import annotations

from pycircuit import Circuit, module


@module(name="JanusTma")
def build_janus_tma(m: Circuit) -> None:
    cmd_valid_tma = m.input("cmd_valid_tma", width=1)
    cmd_tag_tma = m.input("cmd_tag_tma", width=8)
    cmd_payload_tma = m.input("cmd_payload_tma", width=64)

    m.output("rsp_valid_tma", cmd_valid_tma)
    m.output("rsp_tag_tma", cmd_tag_tma)
    m.output("rsp_status_tma", m.const(0, width=4))
    m.output("rsp_data0_tma", cmd_payload_tma + m.const(1, width=64))
    m.output("rsp_data1_tma", cmd_payload_tma)
