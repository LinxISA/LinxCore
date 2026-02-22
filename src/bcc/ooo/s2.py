from __future__ import annotations

from pycircuit import Circuit, module


@module(name="JanusBccOooS2")
def build_janus_bcc_ooo_s2(m: Circuit) -> None:
    clk_top = m.clock("clk")
    rst_top = m.reset("rst")

    s1_to_s2_stage_valid_s1 = m.input("s1_to_s2_stage_valid_s1", width=1)
    s1_to_s2_stage_pc_s1 = m.input("s1_to_s2_stage_pc_s1", width=64)
    s1_to_s2_stage_op_s1 = m.input("s1_to_s2_stage_op_s1", width=12)
    s1_to_s2_stage_pdst_s1 = m.input("s1_to_s2_stage_pdst_s1", width=6)
    s1_to_s2_stage_imm_s1 = m.input("s1_to_s2_stage_imm_s1", width=64)
    s1_to_s2_stage_iq_class_s1 = m.input("s1_to_s2_stage_iq_class_s1", width=2)
    s1_to_s2_stage_uop_uid_s1 = m.input("s1_to_s2_stage_uop_uid_s1", width=64)

    c = m.const

    with m.scope("s2_rr"):
        rr_iq_idx_s2 = m.out("rr_iq_idx_s2", clk=clk_top, rst=rst_top, width=5, init=c(0, width=5), en=c(1, width=1))

    rr_next_s2 = rr_iq_idx_s2.out()
    rr_next_s2 = s1_to_s2_stage_valid_s1._select_internal(rr_iq_idx_s2.out() + c(1, width=5), rr_next_s2)
    rr_iq_idx_s2.set(rr_next_s2)

    m.output("s2_to_iex_stage_valid_s2", s1_to_s2_stage_valid_s1)
    m.output("s2_to_iex_stage_pc_s2", s1_to_s2_stage_pc_s1)
    m.output("s2_to_iex_stage_op_s2", s1_to_s2_stage_op_s1)
    m.output("s2_to_iex_stage_pdst_s2", s1_to_s2_stage_pdst_s1)
    m.output("s2_to_iex_stage_imm_s2", s1_to_s2_stage_imm_s1)
    m.output("s2_to_iex_stage_iq_class_s2", s1_to_s2_stage_iq_class_s1)
    m.output("s2_to_iex_stage_iq_idx_s2", rr_iq_idx_s2.out())
    m.output("s2_to_iex_stage_uop_uid_s2", s1_to_s2_stage_uop_uid_s1)
