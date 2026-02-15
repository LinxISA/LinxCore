from __future__ import annotations

from pycircuit import Circuit, module

from common.isa import (
    OP_BSTART_STD_CALL,
    OP_BSTART_STD_COND,
    OP_BSTART_STD_DIRECT,
    OP_BSTART_STD_FALL,
    OP_C_BSTART_COND,
    OP_C_BSTART_DIRECT,
    OP_C_BSTART_STD,
    OP_C_BSTOP,
    OP_FENTRY,
    OP_FEXIT,
    OP_FRET_RA,
    OP_FRET_STK,
)


@module(name="JanusBccOooDec2")
def build_janus_bcc_ooo_dec2(m: Circuit) -> None:
    d1_to_d2_stage_valid_d1 = m.input("d1_to_d2_stage_valid_d1", width=1)
    d1_to_d2_stage_pc_d1 = m.input("d1_to_d2_stage_pc_d1", width=64)
    d1_to_d2_stage_op_d1 = m.input("d1_to_d2_stage_op_d1", width=12)
    d1_to_d2_stage_len_d1 = m.input("d1_to_d2_stage_len_d1", width=3)
    d1_to_d2_stage_regdst_d1 = m.input("d1_to_d2_stage_regdst_d1", width=6)
    d1_to_d2_stage_srcl_d1 = m.input("d1_to_d2_stage_srcl_d1", width=6)
    d1_to_d2_stage_srcr_d1 = m.input("d1_to_d2_stage_srcr_d1", width=6)
    d1_to_d2_stage_srcp_d1 = m.input("d1_to_d2_stage_srcp_d1", width=6)
    d1_to_d2_stage_imm_d1 = m.input("d1_to_d2_stage_imm_d1", width=64)
    d1_to_d2_stage_insn_raw_d1 = m.input("d1_to_d2_stage_insn_raw_d1", width=64)
    d1_to_d2_stage_checkpoint_id_d1 = m.input("d1_to_d2_stage_checkpoint_id_d1", width=6)

    c = m.const
    op_d2 = d1_to_d2_stage_op_d1
    is_macro_d2 = (op_d2 == c(OP_FENTRY, width=12)) | (op_d2 == c(OP_FEXIT, width=12))
    is_macro_d2 = is_macro_d2 | (op_d2 == c(OP_FRET_RA, width=12)) | (op_d2 == c(OP_FRET_STK, width=12))
    is_bstart_d2 = (
        (op_d2 == c(OP_C_BSTART_STD, width=12))
        | (op_d2 == c(OP_C_BSTART_COND, width=12))
        | (op_d2 == c(OP_C_BSTART_DIRECT, width=12))
        | (op_d2 == c(OP_BSTART_STD_FALL, width=12))
        | (op_d2 == c(OP_BSTART_STD_DIRECT, width=12))
        | (op_d2 == c(OP_BSTART_STD_COND, width=12))
        | (op_d2 == c(OP_BSTART_STD_CALL, width=12))
    )
    is_bstop_d2 = op_d2 == c(OP_C_BSTOP, width=12)
    is_boundary_d2 = is_bstart_d2 | is_bstop_d2
    resolved_d2 = d1_to_d2_stage_valid_d1 & is_boundary_d2

    m.output("d2_to_d3_stage_valid_d2", d1_to_d2_stage_valid_d1)
    m.output("d2_to_d3_stage_pc_d2", d1_to_d2_stage_pc_d1)
    m.output("d2_to_d3_stage_op_d2", op_d2)
    m.output("d2_to_d3_stage_len_d2", d1_to_d2_stage_len_d1)
    m.output("d2_to_d3_stage_regdst_d2", d1_to_d2_stage_regdst_d1)
    m.output("d2_to_d3_stage_srcl_d2", d1_to_d2_stage_srcl_d1)
    m.output("d2_to_d3_stage_srcr_d2", d1_to_d2_stage_srcr_d1)
    m.output("d2_to_d3_stage_srcp_d2", d1_to_d2_stage_srcp_d1)
    m.output("d2_to_d3_stage_imm_d2", d1_to_d2_stage_imm_d1)
    m.output("d2_to_d3_stage_insn_raw_d2", d1_to_d2_stage_insn_raw_d1)
    m.output("d2_to_d3_stage_checkpoint_id_d2", d1_to_d2_stage_checkpoint_id_d1)
    m.output("d2_to_d3_stage_is_macro_d2", is_macro_d2)
    m.output("d2_to_d3_stage_is_boundary_d2", is_boundary_d2)
    m.output("d2_to_d3_stage_is_bstart_d2", is_bstart_d2)
    m.output("d2_to_d3_stage_is_bstop_d2", is_bstop_d2)
    m.output("d2_to_d3_stage_resolved_d2", resolved_d2)
