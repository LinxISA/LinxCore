from __future__ import annotations

from pycircuit import Circuit, module

from common.isa import OP_C_BSTOP, OP_C_BSTART_STD, OP_LD, OP_LW, OP_SD, OP_SW


@module(name="JanusBccOooS1")
def build_janus_bcc_ooo_s1(m: Circuit) -> None:
    d3_to_s1_stage_valid_d3 = m.input("d3_to_s1_stage_valid_d3", width=1)
    d3_to_s1_stage_pc_d3 = m.input("d3_to_s1_stage_pc_d3", width=64)
    d3_to_s1_stage_op_d3 = m.input("d3_to_s1_stage_op_d3", width=12)
    d3_to_s1_stage_pdst_d3 = m.input("d3_to_s1_stage_pdst_d3", width=6)
    d3_to_s1_stage_imm_d3 = m.input("d3_to_s1_stage_imm_d3", width=64)
    d3_to_s1_stage_uop_uid_d3 = m.input("d3_to_s1_stage_uop_uid_d3", width=64)

    c = m.const
    op_s1 = d3_to_s1_stage_op_d3
    is_mem_s1 = (op_s1 == c(OP_LW, width=12)) | (op_s1 == c(OP_LD, width=12))
    is_mem_s1 = is_mem_s1 | (op_s1 == c(OP_SW, width=12)) | (op_s1 == c(OP_SD, width=12))
    is_bru_s1 = (op_s1 == c(OP_C_BSTART_STD, width=12)) | (op_s1 == c(OP_C_BSTOP, width=12))

    iq_class_s1 = c(0, width=2)
    iq_class_s1 = is_bru_s1._select_internal(c(1, width=2), iq_class_s1)
    iq_class_s1 = is_mem_s1._select_internal(c(2, width=2), iq_class_s1)

    m.output("s1_to_s2_stage_valid_s1", d3_to_s1_stage_valid_d3)
    m.output("s1_to_s2_stage_pc_s1", d3_to_s1_stage_pc_d3)
    m.output("s1_to_s2_stage_op_s1", op_s1)
    m.output("s1_to_s2_stage_pdst_s1", d3_to_s1_stage_pdst_d3)
    m.output("s1_to_s2_stage_imm_s1", d3_to_s1_stage_imm_d3)
    m.output("s1_to_s2_stage_iq_class_s1", iq_class_s1)
    m.output("s1_to_s2_stage_uop_uid_s1", d3_to_s1_stage_uop_uid_d3)
