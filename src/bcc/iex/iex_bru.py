from __future__ import annotations

from pycircuit import Circuit, module

@module(name="JanusBccIexBru")
def build_janus_bcc_iex_bru(m: Circuit) -> None:
    in_valid_i1 = m.input("in_valid_i1", width=1)
    in_setc_valid_i1 = m.input("in_setc_valid_i1", width=1)
    in_setc_value_i1 = m.input("in_setc_value_i1", width=1)
    pcb_to_bru_stage_lookup_hit_pcb = m.input("pcb_to_bru_stage_lookup_hit_pcb", width=1)
    pcb_to_bru_stage_lookup_target_pcb = m.input("pcb_to_bru_stage_lookup_target_pcb", width=64)
    pcb_to_bru_stage_lookup_pred_take_pcb = m.input("pcb_to_bru_stage_lookup_pred_take_pcb", width=1)

    setc_mismatch_e1 = in_valid_i1 & in_setc_valid_i1 & (~in_setc_value_i1.eq(pcb_to_bru_stage_lookup_pred_take_pcb))
    redirect_valid_e1 = setc_mismatch_e1 & pcb_to_bru_stage_lookup_hit_pcb
    redirect_pc_e1 = pcb_to_bru_stage_lookup_target_pcb
    fault_valid_e1 = setc_mismatch_e1 & (~pcb_to_bru_stage_lookup_hit_pcb)

    m.output("redirect_valid_e1", redirect_valid_e1)
    m.output("redirect_pc_e1", redirect_pc_e1)
    m.output("fault_valid_e1", fault_valid_e1)
