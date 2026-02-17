from __future__ import annotations

from pycircuit import Circuit, module


@module(name="JanusBccIexBru")
def build_janus_bcc_iex_bru(m: Circuit) -> None:
    clk_i1 = m.clock("clk")
    rst_i1 = m.reset("rst")

    in_valid_i1 = m.input("in_valid_i1", width=1)
    in_setc_valid_i1 = m.input("in_setc_valid_i1", width=1)
    in_setc_value_i1 = m.input("in_setc_value_i1", width=1)
    pcb_to_bru_stage_lookup_hit_pcb = m.input("pcb_to_bru_stage_lookup_hit_pcb", width=1)
    pcb_to_bru_stage_lookup_target_pcb = m.input("pcb_to_bru_stage_lookup_target_pcb", width=64)
    pcb_to_bru_stage_lookup_pred_take_pcb = m.input("pcb_to_bru_stage_lookup_pred_take_pcb", width=1)

    c = m.const

    redirect_valid_q_e1 = m.out("redirect_valid_q_e1", clk=clk_i1, rst=rst_i1, width=1, init=c(0, width=1), en=c(1, width=1))
    redirect_pc_q_e1 = m.out("redirect_pc_q_e1", clk=clk_i1, rst=rst_i1, width=64, init=c(0, width=64), en=c(1, width=1))
    fault_valid_q_e1 = m.out("fault_valid_q_e1", clk=clk_i1, rst=rst_i1, width=1, init=c(0, width=1), en=c(1, width=1))
    corr_pending_q_e1 = m.out("corr_pending_q_e1", clk=clk_i1, rst=rst_i1, width=1, init=c(0, width=1), en=c(1, width=1))
    corr_take_q_e1 = m.out("corr_take_q_e1", clk=clk_i1, rst=rst_i1, width=1, init=c(0, width=1), en=c(1, width=1))
    pred_take_q_e1 = m.out("pred_take_q_e1", clk=clk_i1, rst=rst_i1, width=1, init=c(0, width=1), en=c(1, width=1))

    setc_mismatch_e1 = in_valid_i1 & in_setc_valid_i1 & (~in_setc_value_i1.eq(pcb_to_bru_stage_lookup_pred_take_pcb))
    # Stage-authoritative mirror of backend behavior:
    # mismatch emits correction intent; architectural redirect remains boundary-authoritative.
    redirect_valid_e1 = c(0, width=1)
    fault_valid_e1 = setc_mismatch_e1 & (~pcb_to_bru_stage_lookup_hit_pcb)

    redirect_valid_q_e1.set(redirect_valid_e1)
    redirect_pc_q_e1.set(pcb_to_bru_stage_lookup_target_pcb, when=setc_mismatch_e1)
    fault_valid_q_e1.set(fault_valid_e1)
    corr_pending_q_e1.set(setc_mismatch_e1 & pcb_to_bru_stage_lookup_hit_pcb)
    corr_take_q_e1.set(in_setc_value_i1, when=setc_mismatch_e1)
    pred_take_q_e1.set(pcb_to_bru_stage_lookup_pred_take_pcb, when=setc_mismatch_e1)

    m.output("redirect_valid_e1", redirect_valid_q_e1.out())
    m.output("redirect_pc_e1", redirect_pc_q_e1.out())
    m.output("fault_valid_e1", fault_valid_q_e1.out())
    m.output("corr_pending_e1", corr_pending_q_e1.out())
    m.output("corr_take_e1", corr_take_q_e1.out())
    m.output("pred_take_e1", pred_take_q_e1.out())
