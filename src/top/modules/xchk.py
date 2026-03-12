from __future__ import annotations

from pycircuit import Circuit, module


@module(name="LinxCoreXchkStage")
def build_xchk_stage(m: Circuit) -> None:
    valid_i = m.input("valid_i", width=1)
    lane_i = m.input("lane_i", width=3)
    kind_i = m.input("kind_i", width=3)
    rob_i = m.input("rob_i", width=6)
    seq_i = m.input("seq_i", width=32)
    pc_i = m.input("pc_i", width=64)

    m.output("valid_o", valid_i)
    m.output("lane_o", lane_i)
    m.output("kind_o", kind_i)
    m.output("rob_o", rob_i)
    m.output("seq_o", seq_i)
    m.output("pc_o", pc_i)


build_xchk_stage.__pycircuit_name__ = "LinxCoreXchkStage"
