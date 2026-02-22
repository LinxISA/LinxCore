from __future__ import annotations

from pycircuit import Circuit, module


@module(name="JanusBccBctrl")
def build_janus_bcc_bctrl(m: Circuit) -> None:
    bisq_head_valid_bisq = m.input("bisq_head_valid_bisq", width=1)
    bisq_head_kind_bisq = m.input("bisq_head_kind_bisq", width=3)
    bisq_head_bid_bisq = m.input("bisq_head_bid_bisq", width=64)
    bisq_head_payload_bisq = m.input("bisq_head_payload_bisq", width=64)
    bisq_head_tile_bisq = m.input("bisq_head_tile_bisq", width=6)
    bisq_head_rob_bisq = m.input("bisq_head_rob_bisq", width=6)

    brenu_tag_brenu = m.input("brenu_tag_brenu", width=8)
    brenu_bid_brenu = m.input("brenu_bid_brenu", width=64)
    brenu_epoch_brenu = m.input("brenu_epoch_brenu", width=8)
    brenu_issue_ready_brenu = m.input("brenu_issue_ready_brenu", width=1)

    cmd_ready_tma_tma = m.input("cmd_ready_tma_tma", width=1)
    cmd_ready_cube_cube = m.input("cmd_ready_cube_cube", width=1)
    cmd_ready_vec_vec = m.input("cmd_ready_vec_vec", width=1)
    cmd_ready_tau_tau = m.input("cmd_ready_tau_tau", width=1)

    rsp_tma_valid_tma = m.input("rsp_tma_valid_tma", width=1)
    rsp_tma_tag_tma = m.input("rsp_tma_tag_tma", width=8)
    rsp_tma_status_tma = m.input("rsp_tma_status_tma", width=4)
    rsp_tma_data0_tma = m.input("rsp_tma_data0_tma", width=64)
    rsp_tma_data1_tma = m.input("rsp_tma_data1_tma", width=64)

    rsp_cube_valid_cube = m.input("rsp_cube_valid_cube", width=1)
    rsp_cube_tag_cube = m.input("rsp_cube_tag_cube", width=8)
    rsp_cube_status_cube = m.input("rsp_cube_status_cube", width=4)
    rsp_cube_data0_cube = m.input("rsp_cube_data0_cube", width=64)
    rsp_cube_data1_cube = m.input("rsp_cube_data1_cube", width=64)

    rsp_tau_valid_tau = m.input("rsp_tau_valid_tau", width=1)
    rsp_tau_tag_tau = m.input("rsp_tau_tag_tau", width=8)
    rsp_tau_status_tau = m.input("rsp_tau_status_tau", width=4)
    rsp_tau_data0_tau = m.input("rsp_tau_data0_tau", width=64)
    rsp_tau_data1_tau = m.input("rsp_tau_data1_tau", width=64)

    rsp_vec_valid_vec = m.input("rsp_vec_valid_vec", width=1)
    rsp_vec_tag_vec = m.input("rsp_vec_tag_vec", width=8)
    rsp_vec_status_vec = m.input("rsp_vec_status_vec", width=4)
    rsp_vec_data0_vec = m.input("rsp_vec_data0_vec", width=64)
    rsp_vec_data1_vec = m.input("rsp_vec_data1_vec", width=64)

    c = m.const

    cmd_issue_bctrl = bisq_head_valid_bisq
    cmd_kind_bctrl = bisq_head_kind_bisq

    to_tma_bctrl = cmd_issue_bctrl & (cmd_kind_bctrl == c(0, width=3))
    to_cube_bctrl = cmd_issue_bctrl & (cmd_kind_bctrl == c(1, width=3))
    to_vec_bctrl = cmd_issue_bctrl & (cmd_kind_bctrl == c(2, width=3))
    to_tau_bctrl = cmd_issue_bctrl & (~to_tma_bctrl) & (~to_cube_bctrl) & (~to_vec_bctrl)

    selected_ready_bctrl = c(0, width=1)
    selected_ready_bctrl = to_tma_bctrl._select_internal(cmd_ready_tma_tma, selected_ready_bctrl)
    selected_ready_bctrl = to_cube_bctrl._select_internal(cmd_ready_cube_cube, selected_ready_bctrl)
    selected_ready_bctrl = to_vec_bctrl._select_internal(cmd_ready_vec_vec, selected_ready_bctrl)
    selected_ready_bctrl = to_tau_bctrl._select_internal(cmd_ready_tau_tau, selected_ready_bctrl)

    cmd_fire_bctrl = cmd_issue_bctrl & selected_ready_bctrl & brenu_issue_ready_brenu

    m.output("deq_ready_bisq", cmd_fire_bctrl)
    m.output("issue_fire_brenu", cmd_fire_bctrl)
    m.output("issue_fire_brob", cmd_fire_bctrl)
    m.output("issue_tag_brob", brenu_tag_brenu)
    m.output("issue_bid_brob", bisq_head_bid_bisq)
    m.output("issue_src_rob_brob", bisq_head_rob_bisq)

    # Interface-prefix taps for explicit stage-contract visibility.
    m.output("bisq_to_bctrl_stage_cmd_valid_bisq", bisq_head_valid_bisq)
    m.output("bisq_to_bctrl_stage_cmd_ready_bisq", cmd_fire_bctrl)
    m.output("bisq_to_bctrl_stage_cmd_kind_bisq", bisq_head_kind_bisq)
    m.output("bisq_to_bctrl_stage_cmd_tag_bisq", brenu_tag_brenu)
    m.output("bisq_to_bctrl_stage_cmd_tile_bisq", bisq_head_tile_bisq)
    m.output("bisq_to_bctrl_stage_cmd_payload_bisq", bisq_head_payload_bisq)
    m.output("bisq_to_bctrl_stage_cmd_src_rob_bisq", bisq_head_rob_bisq)
    m.output("bisq_to_bctrl_stage_cmd_epoch_bisq", brenu_epoch_brenu)
    m.output("bisq_to_bctrl_stage_cmd_bid_bisq", bisq_head_bid_bisq)

    # Shared command bus contract.
    m.output("bctrl_to_pe_stage_cmd_kind_bctrl", cmd_kind_bctrl)
    m.output("bctrl_to_pe_stage_cmd_payload_bctrl", bisq_head_payload_bisq)
    m.output("bctrl_to_pe_stage_cmd_tile_bctrl", bisq_head_tile_bisq)
    m.output("bctrl_to_pe_stage_cmd_src_rob_bctrl", bisq_head_rob_bisq)
    m.output("bctrl_to_pe_stage_cmd_tag_bctrl", brenu_tag_brenu)
    m.output("bctrl_to_pe_stage_cmd_bid_bctrl", bisq_head_bid_bisq)
    m.output("bctrl_to_pe_stage_cmd_epoch_bctrl", brenu_epoch_brenu)
    m.output("bctrl_to_pe_stage_cmd_valid_bctrl", cmd_fire_bctrl)

    m.output("cmd_tma_valid_bctrl", cmd_fire_bctrl & to_tma_bctrl)
    m.output("cmd_cube_valid_bctrl", cmd_fire_bctrl & to_cube_bctrl)
    m.output("cmd_vec_valid_bctrl", cmd_fire_bctrl & to_vec_bctrl)
    m.output("cmd_tau_valid_bctrl", cmd_fire_bctrl & to_tau_bctrl)

    m.output("bctrl_to_tma_stage_cmd_valid_bctrl", cmd_fire_bctrl & to_tma_bctrl)
    m.output("bctrl_to_tma_stage_cmd_ready_bctrl", cmd_ready_tma_tma)
    m.output("bctrl_to_tma_stage_cmd_kind_bctrl", cmd_kind_bctrl)
    m.output("bctrl_to_tma_stage_cmd_tag_bctrl", brenu_tag_brenu)
    m.output("bctrl_to_tma_stage_cmd_tile_bctrl", bisq_head_tile_bisq)
    m.output("bctrl_to_tma_stage_cmd_payload_bctrl", bisq_head_payload_bisq)
    m.output("bctrl_to_tma_stage_cmd_src_rob_bctrl", bisq_head_rob_bisq)
    m.output("bctrl_to_tma_stage_cmd_bid_bctrl", bisq_head_bid_bisq)
    m.output("bctrl_to_tma_stage_cmd_epoch_bctrl", brenu_epoch_brenu)

    m.output("bctrl_to_cube_stage_cmd_valid_bctrl", cmd_fire_bctrl & to_cube_bctrl)
    m.output("bctrl_to_cube_stage_cmd_ready_bctrl", cmd_ready_cube_cube)
    m.output("bctrl_to_cube_stage_cmd_kind_bctrl", cmd_kind_bctrl)
    m.output("bctrl_to_cube_stage_cmd_tag_bctrl", brenu_tag_brenu)
    m.output("bctrl_to_cube_stage_cmd_tile_bctrl", bisq_head_tile_bisq)
    m.output("bctrl_to_cube_stage_cmd_payload_bctrl", bisq_head_payload_bisq)
    m.output("bctrl_to_cube_stage_cmd_src_rob_bctrl", bisq_head_rob_bisq)
    m.output("bctrl_to_cube_stage_cmd_bid_bctrl", bisq_head_bid_bisq)
    m.output("bctrl_to_cube_stage_cmd_epoch_bctrl", brenu_epoch_brenu)

    m.output("bctrl_to_vec_stage_cmd_valid_bctrl", cmd_fire_bctrl & to_vec_bctrl)
    m.output("bctrl_to_vec_stage_cmd_ready_bctrl", cmd_ready_vec_vec)
    m.output("bctrl_to_vec_stage_cmd_kind_bctrl", cmd_kind_bctrl)
    m.output("bctrl_to_vec_stage_cmd_tag_bctrl", brenu_tag_brenu)
    m.output("bctrl_to_vec_stage_cmd_tile_bctrl", bisq_head_tile_bisq)
    m.output("bctrl_to_vec_stage_cmd_payload_bctrl", bisq_head_payload_bisq)
    m.output("bctrl_to_vec_stage_cmd_src_rob_bctrl", bisq_head_rob_bisq)
    m.output("bctrl_to_vec_stage_cmd_bid_bctrl", bisq_head_bid_bisq)
    m.output("bctrl_to_vec_stage_cmd_epoch_bctrl", brenu_epoch_brenu)

    m.output("bctrl_to_tau_stage_cmd_valid_bctrl", cmd_fire_bctrl & to_tau_bctrl)
    m.output("bctrl_to_tau_stage_cmd_ready_bctrl", cmd_ready_tau_tau)
    m.output("bctrl_to_tau_stage_cmd_kind_bctrl", cmd_kind_bctrl)
    m.output("bctrl_to_tau_stage_cmd_tag_bctrl", brenu_tag_brenu)
    m.output("bctrl_to_tau_stage_cmd_tile_bctrl", bisq_head_tile_bisq)
    m.output("bctrl_to_tau_stage_cmd_payload_bctrl", bisq_head_payload_bisq)
    m.output("bctrl_to_tau_stage_cmd_src_rob_bctrl", bisq_head_rob_bisq)
    m.output("bctrl_to_tau_stage_cmd_bid_bctrl", bisq_head_bid_bisq)
    m.output("bctrl_to_tau_stage_cmd_epoch_bctrl", brenu_epoch_brenu)

    rsp_valid_brob = rsp_tma_valid_tma | rsp_cube_valid_cube | rsp_vec_valid_vec | rsp_tau_valid_tau
    rsp_tag_brob = rsp_vec_valid_vec._select_internal(rsp_vec_tag_vec, rsp_tau_tag_tau)
    rsp_tag_brob = rsp_cube_valid_cube._select_internal(rsp_cube_tag_cube, rsp_tag_brob)
    rsp_tag_brob = rsp_tma_valid_tma._select_internal(rsp_tma_tag_tma, rsp_tag_brob)
    rsp_status_brob = rsp_vec_valid_vec._select_internal(rsp_vec_status_vec, rsp_tau_status_tau)
    rsp_status_brob = rsp_cube_valid_cube._select_internal(rsp_cube_status_cube, rsp_status_brob)
    rsp_status_brob = rsp_tma_valid_tma._select_internal(rsp_tma_status_tma, rsp_status_brob)
    rsp_data0_brob = rsp_vec_valid_vec._select_internal(rsp_vec_data0_vec, rsp_tau_data0_tau)
    rsp_data0_brob = rsp_cube_valid_cube._select_internal(rsp_cube_data0_cube, rsp_data0_brob)
    rsp_data0_brob = rsp_tma_valid_tma._select_internal(rsp_tma_data0_tma, rsp_data0_brob)
    rsp_data1_brob = rsp_vec_valid_vec._select_internal(rsp_vec_data1_vec, rsp_tau_data1_tau)
    rsp_data1_brob = rsp_cube_valid_cube._select_internal(rsp_cube_data1_cube, rsp_data1_brob)
    rsp_data1_brob = rsp_tma_valid_tma._select_internal(rsp_tma_data1_tma, rsp_data1_brob)

    m.output("rsp_valid_brob", rsp_valid_brob)
    m.output("rsp_tag_brob", rsp_tag_brob)
    m.output("rsp_status_brob", rsp_status_brob)
    m.output("rsp_data0_brob", rsp_data0_brob)
    m.output("rsp_data1_brob", rsp_data1_brob)
    m.output("rsp_trap_valid_brob", c(0, width=1))
    m.output("rsp_trap_cause_brob", c(0, width=32))

    m.output("pe_to_brob_stage_rsp_valid_bctrl", rsp_valid_brob)
    m.output("pe_to_brob_stage_rsp_tag_bctrl", rsp_tag_brob)
    m.output("pe_to_brob_stage_rsp_status_bctrl", rsp_status_brob)
    m.output("pe_to_brob_stage_rsp_data0_bctrl", rsp_data0_brob)
    m.output("pe_to_brob_stage_rsp_data1_bctrl", rsp_data1_brob)
    m.output("pe_to_brob_stage_rsp_trap_valid_bctrl", c(0, width=1))
    m.output("pe_to_brob_stage_rsp_trap_cause_bctrl", c(0, width=32))
