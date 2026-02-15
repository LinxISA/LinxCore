from __future__ import annotations

from pycircuit import Circuit, module


@module(name="JanusTau")
def build_janus_tau(m: Circuit) -> None:
    cmd_valid_tau = m.input("cmd_valid_tau", width=1)
    cmd_tag_tau = m.input("cmd_tag_tau", width=8)
    cmd_payload_tau = m.input("cmd_payload_tau", width=64)

    m.output("rsp_valid_tau", cmd_valid_tau)
    m.output("rsp_tag_tau", cmd_tag_tau)
    m.output("rsp_status_tau", m.const(0, width=4))
    m.output("rsp_data0_tau", cmd_payload_tau + cmd_payload_tau)
    m.output("rsp_data1_tau", cmd_payload_tau)
