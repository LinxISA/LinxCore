from __future__ import annotations

from pycircuit import Circuit, module

from bcc.backend.decode import build_decode_stage


@module(name="JanusBccOooDec1")
def build_janus_bcc_ooo_dec1(m: Circuit) -> None:
    f4_to_d1_stage_valid_f4 = m.input("f4_to_d1_stage_valid_f4", width=1)
    f4_to_d1_stage_pc_f4 = m.input("f4_to_d1_stage_pc_f4", width=64)
    f4_to_d1_stage_window_f4 = m.input("f4_to_d1_stage_window_f4", width=64)
    f4_to_d1_stage_checkpoint_id_f4 = m.input("f4_to_d1_stage_checkpoint_id_f4", width=6)
    f4_to_d1_stage_pkt_uid_f4 = m.input("f4_to_d1_stage_pkt_uid_f4", width=64)

    decode_d1 = m.instance(
        build_decode_stage,
        name="janus_dec1_decode",
        module_name="JanusBccOooDec1Decode",
        params={"dispatch_w": 1},
        f4_valid=f4_to_d1_stage_valid_f4,
        f4_pc=f4_to_d1_stage_pc_f4,
        f4_window=f4_to_d1_stage_window_f4,
        f4_checkpoint_id=f4_to_d1_stage_checkpoint_id_f4,
        f4_pkt_uid=f4_to_d1_stage_pkt_uid_f4,
    )

    m.output("d1_to_d2_stage_valid_d1", decode_d1["valid0"])
    m.output("d1_to_d2_stage_pc_d1", decode_d1["pc0"])
    m.output("d1_to_d2_stage_op_d1", decode_d1["op0"])
    m.output("d1_to_d2_stage_len_d1", decode_d1["len0"])
    m.output("d1_to_d2_stage_regdst_d1", decode_d1["regdst0"])
    m.output("d1_to_d2_stage_srcl_d1", decode_d1["srcl0"])
    m.output("d1_to_d2_stage_srcr_d1", decode_d1["srcr0"])
    m.output("d1_to_d2_stage_srcp_d1", decode_d1["srcp0"])
    m.output("d1_to_d2_stage_imm_d1", decode_d1["imm0"])
    m.output("d1_to_d2_stage_insn_raw_d1", decode_d1["insn_raw0"])
    m.output("d1_to_d2_stage_checkpoint_id_d1", decode_d1["checkpoint_id0"])
    m.output("d1_to_d2_stage_uop_uid_d1", decode_d1["uop_uid0"])
