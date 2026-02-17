from __future__ import annotations

from pycircuit import Circuit, module

from common.isa import OP_C_SETC_EQ, OP_C_SETC_NE
from .iex_agu import build_janus_bcc_iex_agu
from .iex_alu import build_janus_bcc_iex_alu
from .iex_bru import build_janus_bcc_iex_bru
from .iex_fsu import build_janus_bcc_iex_fsu
from .iex_std import build_janus_bcc_iex_std


@module(name="JanusBccIex")
def build_janus_bcc_iex(m: Circuit) -> None:
    clk_i1 = m.clock("clk")
    rst_i1 = m.reset("rst")

    s2_to_iex_stage_valid_s2 = m.input("s2_to_iex_stage_valid_s2", width=1)
    s2_to_iex_stage_op_s2 = m.input("s2_to_iex_stage_op_s2", width=12)
    s2_to_iex_stage_pc_s2 = m.input("s2_to_iex_stage_pc_s2", width=64)
    s2_to_iex_stage_pdst_s2 = m.input("s2_to_iex_stage_pdst_s2", width=6)
    s2_to_iex_stage_imm_s2 = m.input("s2_to_iex_stage_imm_s2", width=64)
    s2_to_iex_stage_iq_class_s2 = m.input("s2_to_iex_stage_iq_class_s2", width=2)
    pcb_to_bru_stage_lookup_hit_pcb = m.input("pcb_to_bru_stage_lookup_hit_pcb", width=1)
    pcb_to_bru_stage_lookup_target_pcb = m.input("pcb_to_bru_stage_lookup_target_pcb", width=64)
    pcb_to_bru_stage_lookup_pred_take_pcb = m.input("pcb_to_bru_stage_lookup_pred_take_pcb", width=1)

    c = m.const

    is_alu_i1 = s2_to_iex_stage_iq_class_s2 == c(0, width=2)
    is_bru_i1 = s2_to_iex_stage_iq_class_s2 == c(1, width=2)
    is_lsu_i1 = s2_to_iex_stage_iq_class_s2 == c(2, width=2)
    is_setc_cond_i1 = (s2_to_iex_stage_op_s2 == c(OP_C_SETC_EQ, width=12)) | (s2_to_iex_stage_op_s2 == c(OP_C_SETC_NE, width=12))

    alu_i1 = m.instance(
        build_janus_bcc_iex_alu,
        name="iex_alu",
        module_name="JanusBccIexAluPipe",
        clk=clk_i1,
        rst=rst_i1,
        in_valid_i1=s2_to_iex_stage_valid_s2 & is_alu_i1,
        in_rob_i1=s2_to_iex_stage_pdst_s2,
        in_pc_i1=s2_to_iex_stage_pc_s2,
        in_imm_i1=s2_to_iex_stage_imm_s2,
    )
    bru_i1 = m.instance(
        build_janus_bcc_iex_bru,
        name="iex_bru",
        module_name="JanusBccIexBruPipe",
        clk=clk_i1,
        rst=rst_i1,
        in_valid_i1=s2_to_iex_stage_valid_s2 & is_bru_i1,
        in_setc_valid_i1=is_setc_cond_i1,
        in_setc_value_i1=s2_to_iex_stage_imm_s2.trunc(width=1),
        pcb_to_bru_stage_lookup_hit_pcb=pcb_to_bru_stage_lookup_hit_pcb,
        pcb_to_bru_stage_lookup_target_pcb=pcb_to_bru_stage_lookup_target_pcb,
        pcb_to_bru_stage_lookup_pred_take_pcb=pcb_to_bru_stage_lookup_pred_take_pcb,
    )
    fsu_i1 = m.instance(
        build_janus_bcc_iex_fsu,
        name="iex_fsu",
        module_name="JanusBccIexFsuPipe",
        clk=clk_i1,
        rst=rst_i1,
        in_valid_i1=s2_to_iex_stage_valid_s2 & is_alu_i1,
        in_rob_i1=s2_to_iex_stage_pdst_s2,
        in_imm_i1=s2_to_iex_stage_imm_s2,
    )
    agu_i1 = m.instance(
        build_janus_bcc_iex_agu,
        name="iex_agu",
        module_name="JanusBccIexAguPipe",
        clk=clk_i1,
        rst=rst_i1,
        in_valid_i1=s2_to_iex_stage_valid_s2 & is_lsu_i1,
        in_rob_i1=s2_to_iex_stage_pdst_s2,
        in_pc_i1=s2_to_iex_stage_pc_s2,
        in_imm_i1=s2_to_iex_stage_imm_s2,
    )
    std_i1 = m.instance(
        build_janus_bcc_iex_std,
        name="iex_std",
        module_name="JanusBccIexStdPipe",
        clk=clk_i1,
        rst=rst_i1,
        in_valid_i1=s2_to_iex_stage_valid_s2 & is_lsu_i1,
        in_rob_i1=s2_to_iex_stage_pdst_s2,
        in_imm_i1=s2_to_iex_stage_imm_s2,
    )

    iex_to_rob_stage_wb_valid_e1 = alu_i1["out_valid_w1"] | fsu_i1["out_valid_w1"]
    iex_to_rob_stage_wb_rob_e1 = alu_i1["out_valid_w1"]._select_internal(alu_i1["out_rob_w1"], fsu_i1["out_rob_w1"])
    iex_to_rob_stage_wb_value_e1 = alu_i1["out_valid_w1"]._select_internal(alu_i1["out_value_w1"], fsu_i1["out_value_w1"])

    m.output("iex_to_rob_stage_wb_valid_e1", iex_to_rob_stage_wb_valid_e1)
    m.output("iex_to_rob_stage_wb_rob_e1", iex_to_rob_stage_wb_rob_e1)
    m.output("iex_to_rob_stage_wb_value_e1", iex_to_rob_stage_wb_value_e1)
    m.output("iex_to_rob_stage_load_valid_e1", agu_i1["load_req_valid_e1"])
    m.output("iex_to_rob_stage_load_rob_e1", agu_i1["load_req_rob_e1"])
    m.output("iex_to_rob_stage_load_addr_e1", agu_i1["load_req_addr_e1"])
    m.output("iex_to_rob_stage_store_valid_e1", std_i1["store_req_valid_e1"])
    m.output("iex_to_rob_stage_store_rob_e1", std_i1["store_req_rob_e1"])
    m.output("iex_to_rob_stage_store_data_e1", std_i1["store_req_data_e1"])

    m.output("iex_to_flush_stage_redirect_valid_e1", bru_i1["redirect_valid_e1"])
    m.output("iex_to_flush_stage_redirect_pc_e1", bru_i1["redirect_pc_e1"])
    m.output("iex_to_flush_stage_fault_valid_e1", bru_i1["fault_valid_e1"])
