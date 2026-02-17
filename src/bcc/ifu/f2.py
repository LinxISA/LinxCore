from __future__ import annotations

from pycircuit import Circuit, module

from common.decode_f4 import decode_f4_bundle


@module(name="JanusBccIfuF2")
def build_janus_bcc_ifu_f2(m: Circuit) -> None:
    f1_to_f2_stage_pc_f1 = m.input("f1_to_f2_stage_pc_f1", width=64)
    f1_to_f2_stage_window_f1 = m.input("f1_to_f2_stage_window_f1", width=64)
    f1_to_f2_stage_valid_f1 = m.input("f1_to_f2_stage_valid_f1", width=1)
    f1_to_f2_stage_pkt_uid_f1 = m.input("f1_to_f2_stage_pkt_uid_f1", width=64)
    f3_to_f2_stage_ready_f3 = m.input("f3_to_f2_stage_ready_f3", width=1)

    c = m.const

    f4_bundle_f2 = decode_f4_bundle(m, f1_to_f2_stage_window_f1)
    advance_bytes_f2 = f4_bundle_f2.total_len_bytes
    advance_bytes_f2 = (advance_bytes_f2 == c(0, width=4))._select_internal(c(2, width=4), advance_bytes_f2)
    next_pc_f2 = f1_to_f2_stage_pc_f1 + advance_bytes_f2
    advance_fire_f2 = f1_to_f2_stage_valid_f1 & f3_to_f2_stage_ready_f3

    m.output("f2_to_f3_stage_pc_f2", f1_to_f2_stage_pc_f1)
    m.output("f2_to_f3_stage_window_f2", f1_to_f2_stage_window_f1)
    m.output("f2_to_f3_stage_next_pc_f2", next_pc_f2)
    m.output("f2_to_f3_stage_pkt_uid_f2", f1_to_f2_stage_pkt_uid_f1)
    m.output("f2_to_f3_stage_valid_f2", advance_fire_f2)

    m.output("f2_to_f0_advance_stage_valid_f2", advance_fire_f2)
    m.output("f2_to_f0_next_pc_stage_pc_f2", next_pc_f2)
