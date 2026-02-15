from __future__ import annotations

from pycircuit import Circuit, module


@module(name="JanusCube")
def build_janus_cube(m: Circuit) -> None:
    cmd_valid_cube = m.input("cmd_valid_cube", width=1)
    cmd_tag_cube = m.input("cmd_tag_cube", width=8)
    cmd_payload_cube = m.input("cmd_payload_cube", width=64)

    m.output("rsp_valid_cube", cmd_valid_cube)
    m.output("rsp_tag_cube", cmd_tag_cube)
    m.output("rsp_status_cube", m.const(0, width=4))
    m.output("rsp_data0_cube", ~cmd_payload_cube)
    m.output("rsp_data1_cube", cmd_payload_cube)
