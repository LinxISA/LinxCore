from __future__ import annotations

from pycircuit import Circuit, module


@module(name="JanusBccOooRen")
def build_janus_bcc_ooo_ren(m: Circuit) -> None:
    d2_to_d3_stage_valid_d2 = m.input("d2_to_d3_stage_valid_d2", width=1)
    d2_to_d3_stage_pc_d2 = m.input("d2_to_d3_stage_pc_d2", width=64)
    d2_to_d3_stage_op_d2 = m.input("d2_to_d3_stage_op_d2", width=12)
    d2_to_d3_stage_regdst_d2 = m.input("d2_to_d3_stage_regdst_d2", width=6)
    d2_to_d3_stage_srcl_d2 = m.input("d2_to_d3_stage_srcl_d2", width=6)
    d2_to_d3_stage_srcr_d2 = m.input("d2_to_d3_stage_srcr_d2", width=6)
    d2_to_d3_stage_srcp_d2 = m.input("d2_to_d3_stage_srcp_d2", width=6)
    d2_to_d3_stage_imm_d2 = m.input("d2_to_d3_stage_imm_d2", width=64)
    d2_to_d3_stage_checkpoint_id_d2 = m.input("d2_to_d3_stage_checkpoint_id_d2", width=6)
    d2_to_d3_stage_uop_uid_d2 = m.input("d2_to_d3_stage_uop_uid_d2", width=64)

    c = m.const
    has_dst_d3 = ~(d2_to_d3_stage_regdst_d2 == c(0, width=6))
    pdst_d3 = has_dst_d3._select_internal(d2_to_d3_stage_regdst_d2, c(0, width=6))

    m.output("d3_to_s1_stage_valid_d3", d2_to_d3_stage_valid_d2)
    m.output("d3_to_s1_stage_pc_d3", d2_to_d3_stage_pc_d2)
    m.output("d3_to_s1_stage_op_d3", d2_to_d3_stage_op_d2)
    m.output("d3_to_s1_stage_srcl_tag_d3", d2_to_d3_stage_srcl_d2)
    m.output("d3_to_s1_stage_srcr_tag_d3", d2_to_d3_stage_srcr_d2)
    m.output("d3_to_s1_stage_srcp_tag_d3", d2_to_d3_stage_srcp_d2)
    m.output("d3_to_s1_stage_pdst_d3", pdst_d3)
    m.output("d3_to_s1_stage_imm_d3", d2_to_d3_stage_imm_d2)
    m.output("d3_to_s1_stage_checkpoint_id_d3", d2_to_d3_stage_checkpoint_id_d2)
    m.output("d3_to_s1_stage_uop_uid_d3", d2_to_d3_stage_uop_uid_d2)
