from __future__ import annotations

from pycircuit import Circuit, module


@module(name="JanusBccIfuF4")
def build_janus_bcc_ifu_f4(m: Circuit) -> None:
    clk_f4 = m.clock("clk")
    rst_f4 = m.reset("rst")

    f3_to_f4_stage_pc_f3 = m.input("f3_to_f4_stage_pc_f3", width=64)
    f3_to_f4_stage_window_f3 = m.input("f3_to_f4_stage_window_f3", width=64)
    f3_to_f4_stage_pkt_uid_f3 = m.input("f3_to_f4_stage_pkt_uid_f3", width=64)
    f3_to_f4_stage_valid_f3 = m.input("f3_to_f4_stage_valid_f3", width=1)
    f3_to_f4_stage_checkpoint_id_f3 = m.input("f3_to_f4_stage_checkpoint_id_f3", width=6)
    flush_valid_fls = m.input("flush_valid_fls", width=1)

    c = m.const

    f4_pc_f4 = m.out("f4_pc_f4", clk=clk_f4, rst=rst_f4, width=64, init=c(0, width=64), en=c(1, width=1))
    f4_window_f4 = m.out("f4_window_f4", clk=clk_f4, rst=rst_f4, width=64, init=c(0, width=64), en=c(1, width=1))
    f4_pkt_uid_f4 = m.out("f4_pkt_uid_f4", clk=clk_f4, rst=rst_f4, width=64, init=c(0, width=64), en=c(1, width=1))
    f4_valid_f4 = m.out("f4_valid_f4", clk=clk_f4, rst=rst_f4, width=1, init=c(0, width=1), en=c(1, width=1))
    f4_checkpoint_f4 = m.out("f4_checkpoint_f4", clk=clk_f4, rst=rst_f4, width=6, init=c(0, width=6), en=c(1, width=1))

    f4_pc_f4.set(f3_to_f4_stage_pc_f3)
    f4_window_f4.set(f3_to_f4_stage_window_f3)
    f4_pkt_uid_f4.set(f3_to_f4_stage_pkt_uid_f3)
    f4_checkpoint_f4.set(f3_to_f4_stage_checkpoint_id_f3)
    f4_valid_next_f4 = flush_valid_fls._select_internal(c(0, width=1), f3_to_f4_stage_valid_f3)
    f4_valid_f4.set(f4_valid_next_f4)

    # Keep external handoff combinational for commit-lockstep parity.
    m.output("f4_to_d1_stage_pc_f4", f3_to_f4_stage_pc_f3)
    m.output("f4_to_d1_stage_window_f4", f3_to_f4_stage_window_f3)
    m.output("f4_to_d1_stage_pkt_uid_f4", f3_to_f4_stage_pkt_uid_f3)
    m.output("f4_to_d1_stage_valid_f4", flush_valid_fls._select_internal(c(0, width=1), f3_to_f4_stage_valid_f3))
    m.output("f4_to_d1_stage_checkpoint_id_f4", f3_to_f4_stage_checkpoint_id_f3)

    # Per-slot visibility for pipeview continuity (F4 -> OOO uid namespace).
    f4_bundle = decode_f4_bundle(m, f3_to_f4_stage_window_f3)
    f4_valid = flush_valid_fls._select_internal(c(0, width=1), f3_to_f4_stage_valid_f3)
    for slot in range(4):
        slot_pc = f3_to_f4_stage_pc_f3 + f4_bundle.off_bytes[slot]._zext(width=64)
        slot_uid = (f3_to_f4_stage_pkt_uid_f3.shl(amount=3)) | c(slot, width=64)
        slot_valid = f4_valid & (c(1, width=1) if slot == 0 else c(0, width=1)) & f4_bundle.valid[slot]
        m.output(f"f4_slot_valid{slot}_f4", slot_valid)
        m.output(f"f4_slot_pc{slot}_f4", slot_pc)
        m.output(f"f4_slot_uop_uid{slot}_f4", slot_uid)
