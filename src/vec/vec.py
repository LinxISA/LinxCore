from __future__ import annotations

from pycircuit import Circuit, module


@module(name="LinxCoreVec")
def build_linxcore_vec(m: Circuit) -> None:
    cmd_valid_vec = m.input("cmd_valid_vec", width=1)
    cmd_tag_vec = m.input("cmd_tag_vec", width=8)
    cmd_payload_vec = m.input("cmd_payload_vec", width=64)

    m.output("rsp_valid_vec", cmd_valid_vec)
    m.output("rsp_tag_vec", cmd_tag_vec)
    m.output("rsp_status_vec", m.const(0, width=4))
    # Minimal deterministic vector lane behavior for first-class BCtrl/BROB integration.
    m.output("rsp_data0_vec", cmd_payload_vec ^ m.const(0x55AA55AA55AA55AA, width=64))
    m.output("rsp_data1_vec", cmd_payload_vec)
