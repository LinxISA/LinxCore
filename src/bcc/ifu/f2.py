from __future__ import annotations

from pycircuit import Circuit, module

from common.decode_f4 import decode_f4_bundle


@module(name="JanusBccIfuF2")
def build_janus_bcc_ifu_f2(m: Circuit) -> None:
    f1_to_f2_stage_pc_f1 = m.input("f1_to_f2_stage_pc_f1", width=64)
    f1_to_f2_stage_bundle128_f1 = m.input("f1_to_f2_stage_bundle128_f1", width=128)
    f1_to_f2_stage_bundle_base_pc_f1 = m.input("f1_to_f2_stage_bundle_base_pc_f1", width=64)
    f1_to_f2_stage_slot_base_offset_f1 = m.input("f1_to_f2_stage_slot_base_offset_f1", width=7)
    f1_to_f2_stage_hit_f1 = m.input("f1_to_f2_stage_hit_f1", width=1)
    f1_to_f2_stage_miss_f1 = m.input("f1_to_f2_stage_miss_f1", width=1)
    f1_to_f2_stage_stall_f1 = m.input("f1_to_f2_stage_stall_f1", width=1)
    f1_to_f2_stage_valid_f1 = m.input("f1_to_f2_stage_valid_f1", width=1)
    f1_to_f2_stage_pkt_uid_f1 = m.input("f1_to_f2_stage_pkt_uid_f1", width=64)
    f3_to_f2_stage_ready_f3 = m.input("f3_to_f2_stage_ready_f3", width=1)

    c = m.const

    # Select the active 64-bit decode window from the 128-bit fetch bundle.
    # Offset is byte-based and supports [0..8] coverage.
    decode_window_f2 = c(0, width=64)
    for byte_off in range(9):
        off_match_f2 = f1_to_f2_stage_slot_base_offset_f1 == c(byte_off, width=7)
        win_f2 = f1_to_f2_stage_bundle128_f1[byte_off * 8 : byte_off * 8 + 64]
        decode_window_f2 = win_f2 if off_match_f2 else decode_window_f2

    fetch_ok_f2 = f1_to_f2_stage_valid_f1 & f1_to_f2_stage_hit_f1 & (~f1_to_f2_stage_stall_f1)
    f4_bundle_f2 = decode_f4_bundle(m, decode_window_f2)
    advance_bytes_f2 = f4_bundle_f2.dec[0].len_bytes
    advance_bytes_f2 = c(2, width=4) if (advance_bytes_f2 == c(0, width=4)) else advance_bytes_f2
    next_pc_f2 = f1_to_f2_stage_pc_f1 + advance_bytes_f2
    advance_fire_f2 = fetch_ok_f2 & f3_to_f2_stage_ready_f3

    m.output("f2_to_f3_stage_pc_f2", f1_to_f2_stage_pc_f1)
    m.output("f2_to_f3_stage_window_f2", decode_window_f2)
    m.output("f2_to_f3_stage_bundle128_f2", f1_to_f2_stage_bundle128_f1)
    m.output("f2_to_f3_stage_bundle_base_pc_f2", f1_to_f2_stage_bundle_base_pc_f1)
    m.output("f2_to_f3_stage_slot_base_offset_f2", f1_to_f2_stage_slot_base_offset_f1)
    m.output("f2_to_f3_stage_next_pc_f2", next_pc_f2)
    m.output("f2_to_f3_stage_pkt_uid_f2", f1_to_f2_stage_pkt_uid_f1)
    m.output("f2_to_f3_stage_valid_f2", advance_fire_f2)
    m.output("f2_to_f3_stage_miss_f2", f1_to_f2_stage_miss_f1)
    m.output("f2_to_f3_stage_stall_f2", f1_to_f2_stage_stall_f1)

    m.output("f2_to_f0_advance_stage_valid_f2", advance_fire_f2)
    m.output("f2_to_f0_next_pc_stage_pc_f2", next_pc_f2)

    # Per-slot visibility for pipeview continuity (F2 -> OOO uid namespace).
    for slot in range(4):
        slot_pc_f2 = f1_to_f2_stage_pc_f1 + f4_bundle_f2.off_bytes[slot]
        slot_uid_f2 = (f1_to_f2_stage_pkt_uid_f1.shl(amount=3)) | c(slot, width=64)
        slot_valid_f2 = advance_fire_f2 & (c(1, width=1) if slot == 0 else c(0, width=1)) & f4_bundle_f2.valid[slot]
        m.output(f"f2_slot_valid{slot}_f2", slot_valid_f2)
        m.output(f"f2_slot_pc{slot}_f2", slot_pc_f2)
        m.output(f"f2_slot_uop_uid{slot}_f2", slot_uid_f2)
