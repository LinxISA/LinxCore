from __future__ import annotations

from pycircuit import Circuit, module


@module(name="JanusBccIfuF4")
def build_janus_bcc_ifu_f4(m: Circuit) -> None:
    f3_to_f4_stage_pc_f3 = m.input("f3_to_f4_stage_pc_f3", width=64)
    f3_to_f4_stage_window_f3 = m.input("f3_to_f4_stage_window_f3", width=64)
    f3_to_f4_stage_valid_f3 = m.input("f3_to_f4_stage_valid_f3", width=1)
    f3_to_f4_stage_checkpoint_id_f3 = m.input("f3_to_f4_stage_checkpoint_id_f3", width=6)

    m.output("f4_to_d1_stage_pc_f4", f3_to_f4_stage_pc_f3)
    m.output("f4_to_d1_stage_window_f4", f3_to_f4_stage_window_f3)
    m.output("f4_to_d1_stage_valid_f4", f3_to_f4_stage_valid_f3)
    m.output("f4_to_d1_stage_checkpoint_id_f4", f3_to_f4_stage_checkpoint_id_f3)
