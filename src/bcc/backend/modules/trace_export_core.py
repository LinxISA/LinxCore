from __future__ import annotations

# ENGINE_ORCHESTRATION_ONLY:
# Stage/component ownership is migrating to focused files. Keep this file as
# composition glue; avoid adding new monolithic stage logic.

from pycircuit import Circuit, module

from common.exec_uop import ExecOut
from common.isa import (
    OP_BIOR,
    OP_BLOAD,
    BK_CALL,
    BK_COND,
    BK_DIRECT,
    BK_ICALL,
    BK_IND,
    OP_BSTORE,
    OP_BTEXT,
    BK_RET,
    OP_BSTART_STD_COND,
    OP_BSTART_STD_CALL,
    OP_BSTART_STD_DIRECT,
    OP_BSTART_STD_FALL,
    OP_C_BSTART_COND,
    OP_C_BSTART_DIRECT,
    OP_C_BSTART_STD,
    OP_C_BSTOP,
    OP_C_LDI,
    OP_C_LWI,
    OP_C_SETC_NE,
    OP_C_SDI,
    OP_C_SWI,
    OP_C_SETC_EQ,
    OP_C_SETC_TGT,
    OP_EBREAK,
    OP_FENTRY,
    OP_FEXIT,
    OP_FRET_RA,
    OP_FRET_STK,
    OP_INVALID,
    OP_HL_LB_PCR,
    OP_HL_LBU_PCR,
    OP_HL_LD_PCR,
    OP_HL_LH_PCR,
    OP_HL_LHU_PCR,
    OP_HL_LW_PCR,
    OP_HL_LWU_PCR,
    OP_HL_SB_PCR,
    OP_HL_SD_PCR,
    OP_HL_SH_PCR,
    OP_HL_SW_PCR,
    OP_LB,
    OP_LBI,
    OP_LBU,
    OP_LBUI,
    OP_LD,
    OP_LH,
    OP_LHI,
    OP_LHU,
    OP_LHUI,
    OP_LW,
    OP_LWU,
    OP_LWUI,
    OP_SB,
    OP_SBI,
    OP_SD,
    OP_SH,
    OP_SHI,
    OP_SW,
    OP_LWI,
    OP_LDI,
    OP_SETC_AND,
    OP_SETC_ANDI,
    OP_SETC_EQ,
    OP_SETC_EQI,
    OP_SETC_GE,
    OP_SETC_GEI,
    OP_SETC_GEU,
    OP_SETC_GEUI,
    OP_SETC_LT,
    OP_SETC_LTI,
    OP_SETC_LTU,
    OP_SETC_LTUI,
    OP_SETC_NE,
    OP_SETC_NEI,
    OP_SETC_OR,
    OP_SETC_ORI,
    OP_SDI,
    OP_SWI,
    REG_INVALID,
    TRAP_BRU_RECOVERY_NOT_BSTART,
)
from common.util import make_consts
from ..code_template_unit import build_code_template_unit
from ..commit import build_commit_ctrl_stage, build_commit_head_stage, is_setc_any, is_setc_tgt
from ..helpers import mask_bit, mux_by_uindex, onehot_from_tag
from ..lsu import build_lsu_stage
from .dispatch_frontend import build_dispatch_frontend
from .block_fabric import build_block_fabric
from .commit_slot_step import build_commit_slot_step
from .commit_redirect import build_commit_redirect
from .exec_uop_wrap import build_exec_uop
from .ingress_decode import build_ingress_decode
from .iq_bank import build_iq_bank_top
from .recovery_checks import build_bru_recovery_owner
from .rename_bank import build_rename_bank_top
from .rob_bank import build_rob_bank_top
from .trace_occ import build_backend_occ_frontend, build_backend_occ_schedule
from ..params import OooParams
from ..prf import build_prf
from ..rename import build_commit_rename_stage, build_rename_stage
from ..state import make_core_ctrl_regs
from ..template_uop_encoding import map_template_child_encoding
from ..wakeup import compose_replay_cause, compose_wakeup_reason

def _build_trace_export_core(
    m: Circuit,
    *,
    mem_bytes: int,
    params: OooParams | None = None,
) -> None:
    p = params or OooParams()

    clk = m.clock("clk")
    rst = m.reset("rst")

    boot_pc = m.input("boot_pc", width=64)
    boot_sp = m.input("boot_sp", width=64)
    boot_ra = m.input("boot_ra", width=64)

    # Frontend handoff (F4 bundle + ready/redirect handshake).
    f4_valid_i = m.input("f4_valid_i", width=1)
    f4_pc_i = m.input("f4_pc_i", width=64)
    f4_window_i = m.input("f4_window_i", width=64)
    f4_checkpoint_i = m.input("f4_checkpoint_i", width=6)
    f4_pkt_uid_i = m.input("f4_pkt_uid_i", width=64)

    # Data-memory read data from LinxCoreMem2R1W.
    dmem_rdata_i = m.input("dmem_rdata_i", width=64)
    # BISQ enqueue ready (CMD issue lane can retire only on successful enqueue).
    bisq_enq_ready_i = m.input("bisq_enq_ready_i", width=1)
    # BROB active-block state query (for BSTOP retirement gate).
    brob_active_allocated_i = m.input("brob_active_allocated_i", width=1)
    brob_active_ready_i = m.input("brob_active_ready_i", width=1)
    brob_active_exception_i = m.input("brob_active_exception_i", width=1)
    brob_active_retired_i = m.input("brob_active_retired_i", width=1)
    # BROB allocator (D1-time BID assignment): peek+fire.
    brob_alloc_ready_i = m.input("brob_alloc_ready_i", width=1)
    brob_alloc_bid_i = m.input("brob_alloc_bid_i", width=64)
    # Global dynamic-UID allocator input for template-child uops.
    template_uid_i = m.input("template_uid_i", width=64)
    # Optional fixed callframe addend is a boundary value port.
    callframe_size_i = m.input("callframe_size_i", width=64)

    c = m.const
    consts = make_consts(m)

    def op_is(op, *codes: int):
        v = consts.zero1
        for code in codes:
            v = v | op.__eq__(c(code, width=12))
        return v

    tag0 = c(0, width=p.ptag_w)

    ingress_decode = m.instance_auto(
        build_ingress_decode,
        name="ingress_decode",
        module_name="LinxCoreIngressDecodeBackend",
        clk=clk,
        rst=rst,
        in_f4_valid=f4_valid_i,
        in_f4_pc=f4_pc_i,
        in_f4_window=f4_window_i,
        in_f4_checkpoint=f4_checkpoint_i,
        in_f4_pkt_uid=f4_pkt_uid_i,
        ready_i=consts.one1,
    )
    f4_valid = ingress_decode["out_f4_valid"]
    f4_pc = ingress_decode["out_f4_pc"]
    f4_window = ingress_decode["out_f4_window"]
    f4_checkpoint = ingress_decode["out_f4_checkpoint"]
    f4_pkt_uid = ingress_decode["out_f4_pkt_uid"]

    # --- core state (architectural) ---
    state = make_core_ctrl_regs(m, clk, rst, boot_pc=boot_pc, consts=consts, p=p)

    base_can_run = (~state.halted.out()) & (~state.flush_pending.out())
    do_flush = state.flush_pending.out()

    # --- physical register file (PRF) ---
    # Keep PRF as a dedicated module so codegen can shard it for faster C++
    # builds, and keep backend engine orchestration focused on control flow.
    prf_rd_ret_ra = 0
    prf_rd_op_base = prf_rd_ret_ra + 1
    prf_rd_macro_reg = prf_rd_op_base + (p.issue_w * 3)
    prf_rd_macro_sp = prf_rd_macro_reg + 1
    prf_read_ports = prf_rd_macro_sp + 1

    prf_raddr = [m.new_wire(width=p.ptag_w) for _ in range(prf_read_ports)]
    prf_wen = [m.new_wire(width=1) for _ in range(p.issue_w + 1)]
    prf_waddr = [m.new_wire(width=p.ptag_w) for _ in range(p.issue_w + 1)]
    prf_wdata = [m.new_wire(width=64) for _ in range(p.issue_w + 1)]

    prf_ports = {"clk": clk, "rst": rst, "boot_sp": boot_sp, "boot_ra": boot_ra}
    for i in range(prf_read_ports):
        prf_ports[f"raddr{i}"] = prf_raddr[i]
    for i in range(p.issue_w + 1):
        prf_ports[f"wen{i}"] = prf_wen[i]
        prf_ports[f"waddr{i}"] = prf_waddr[i]
        prf_ports[f"wdata{i}"] = prf_wdata[i]

    prf_inst = m.instance_auto(
        build_prf,
        name="prf",
        module_name="LinxCorePrfTop",
        params={
            "pregs": p.pregs,
            "read_ports": prf_read_ports,
            "write_ports": p.issue_w + 1,
            "init_sp_tag": 1,
            "init_ra_tag": 10,
        },
        **prf_ports,
    )
    prf_rdata = [prf_inst[f"rdata{i}"] for i in range(prf_read_ports)]

    # --- rename bank (hierarchical; owns SMAP/CMAP/freelist/ready/checkpoints) ---
    ren_dispatch_fire = m.new_wire(width=1)
    ren_disp_alloc_mask = m.new_wire(width=p.pregs)
    ren_flush_checkpoint_id = m.new_wire(width=6)
    ren_macro_uop_reg = m.new_wire(width=6)
    ren_wb_set_mask = m.new_wire(width=p.pregs)

    ren_disp_valids = [m.new_wire(width=1) for _ in range(p.dispatch_w)]
    ren_disp_srcls = [m.new_wire(width=6) for _ in range(p.dispatch_w)]
    ren_disp_srcrs = [m.new_wire(width=6) for _ in range(p.dispatch_w)]
    ren_disp_srcps = [m.new_wire(width=6) for _ in range(p.dispatch_w)]
    ren_disp_is_start_markers = [m.new_wire(width=1) for _ in range(p.dispatch_w)]
    ren_disp_push_ts = [m.new_wire(width=1) for _ in range(p.dispatch_w)]
    ren_disp_push_us = [m.new_wire(width=1) for _ in range(p.dispatch_w)]
    ren_disp_dst_is_gprs = [m.new_wire(width=1) for _ in range(p.dispatch_w)]
    ren_disp_regdsts = [m.new_wire(width=6) for _ in range(p.dispatch_w)]
    ren_disp_pdsts = [m.new_wire(width=p.ptag_w) for _ in range(p.dispatch_w)]
    ren_disp_checkpoint_ids = [m.new_wire(width=6) for _ in range(p.dispatch_w)]

    ren_commit_fires = [m.new_wire(width=1) for _ in range(p.commit_w)]
    ren_commit_is_bstops = [m.new_wire(width=1) for _ in range(p.commit_w)]
    ren_rob_dst_kinds = [m.new_wire(width=2) for _ in range(p.commit_w)]
    ren_rob_dst_aregs = [m.new_wire(width=6) for _ in range(p.commit_w)]
    ren_rob_pdsts = [m.new_wire(width=p.ptag_w) for _ in range(p.commit_w)]

    rename_bank_args = {
        "clk": clk,
        "rst": rst,
        "do_flush": do_flush,
        "dispatch_fire": ren_dispatch_fire,
        "disp_alloc_mask": ren_disp_alloc_mask,
        "flush_checkpoint_id": ren_flush_checkpoint_id,
        "macro_uop_reg": ren_macro_uop_reg,
        "wb_set_mask": ren_wb_set_mask,
    }
    for slot in range(p.dispatch_w):
        rename_bank_args[f"disp_valid{slot}"] = ren_disp_valids[slot]
        rename_bank_args[f"disp_srcl{slot}"] = ren_disp_srcls[slot]
        rename_bank_args[f"disp_srcr{slot}"] = ren_disp_srcrs[slot]
        rename_bank_args[f"disp_srcp{slot}"] = ren_disp_srcps[slot]
        rename_bank_args[f"disp_is_start_marker{slot}"] = ren_disp_is_start_markers[slot]
        rename_bank_args[f"disp_push_t{slot}"] = ren_disp_push_ts[slot]
        rename_bank_args[f"disp_push_u{slot}"] = ren_disp_push_us[slot]
        rename_bank_args[f"disp_dst_is_gpr{slot}"] = ren_disp_dst_is_gprs[slot]
        rename_bank_args[f"disp_regdst{slot}"] = ren_disp_regdsts[slot]
        rename_bank_args[f"disp_pdst{slot}"] = ren_disp_pdsts[slot]
        rename_bank_args[f"disp_checkpoint_id{slot}"] = ren_disp_checkpoint_ids[slot]
    for slot in range(p.commit_w):
        rename_bank_args[f"commit_fire{slot}"] = ren_commit_fires[slot]
        rename_bank_args[f"commit_is_bstop{slot}"] = ren_commit_is_bstops[slot]
        rename_bank_args[f"rob_dst_kind{slot}"] = ren_rob_dst_kinds[slot]
        rename_bank_args[f"rob_dst_areg{slot}"] = ren_rob_dst_aregs[slot]
        rename_bank_args[f"rob_pdst{slot}"] = ren_rob_pdsts[slot]

    rename_bank = m.instance_auto(
        build_rename_bank_top,
        name="rename_bank",
        module_name="LinxCoreRenameBankTop",
        params={"dispatch_w": p.dispatch_w, "commit_w": p.commit_w, "pregs": p.pregs},
        **rename_bank_args,
    )

    ren_free_mask = rename_bank["free_mask_o"]
    ren_ready_mask = rename_bank["ready_mask_o"]
    ren_cmap_sp = rename_bank["cmap_sp_o"]
    ren_cmap_a0 = rename_bank["cmap_a0_o"]
    ren_cmap_a1 = rename_bank["cmap_a1_o"]
    ren_cmap_ra = rename_bank["cmap_ra_o"]
    ren_cmap_ct0 = rename_bank["cmap_ct0_o"]
    ren_cmap_cu0 = rename_bank["cmap_cu0_o"]
    ren_smap_st0 = rename_bank["smap_st0_o"]
    ren_smap_su0 = rename_bank["smap_su0_o"]
    ren_macro_reg_tag = rename_bank["macro_reg_tag_o"]
    disp_srcl_tags = [rename_bank[f"disp_srcl_tag{slot}_o"] for slot in range(p.dispatch_w)]
    disp_srcr_tags = [rename_bank[f"disp_srcr_tag{slot}_o"] for slot in range(p.dispatch_w)]
    disp_srcp_tags = [rename_bank[f"disp_srcp_tag{slot}_o"] for slot in range(p.dispatch_w)]

    # --- ROB bank (hierarchical; owns ROB state) ---
    rob_commit_count = m.new_wire(width=3)
    rob_disp_count = m.new_wire(width=3)
    rob_dispatch_fire = m.new_wire(width=1)
    rob_commit_fires = [m.new_wire(width=1) for _ in range(p.commit_w)]
    rob_disp_valids = [m.new_wire(width=1) for _ in range(p.dispatch_w)]

    rob_disp_pcs = [m.new_wire(width=64) for _ in range(p.dispatch_w)]
    rob_disp_ops = [m.new_wire(width=12) for _ in range(p.dispatch_w)]
    rob_disp_lens = [m.new_wire(width=3) for _ in range(p.dispatch_w)]
    rob_disp_insn_raws = [m.new_wire(width=64) for _ in range(p.dispatch_w)]
    rob_disp_checkpoint_ids = [m.new_wire(width=6) for _ in range(p.dispatch_w)]
    rob_disp_dst_kinds = [m.new_wire(width=2) for _ in range(p.dispatch_w)]
    rob_disp_regdsts = [m.new_wire(width=6) for _ in range(p.dispatch_w)]
    rob_disp_pdsts = [m.new_wire(width=p.ptag_w) for _ in range(p.dispatch_w)]
    rob_disp_imms = [m.new_wire(width=64) for _ in range(p.dispatch_w)]
    rob_disp_is_stores = [m.new_wire(width=1) for _ in range(p.dispatch_w)]
    rob_disp_is_boundaries = [m.new_wire(width=1) for _ in range(p.dispatch_w)]
    rob_disp_is_bstarts = [m.new_wire(width=1) for _ in range(p.dispatch_w)]
    rob_disp_is_bstops = [m.new_wire(width=1) for _ in range(p.dispatch_w)]
    rob_disp_boundary_kinds = [m.new_wire(width=3) for _ in range(p.dispatch_w)]
    rob_disp_boundary_targets = [m.new_wire(width=64) for _ in range(p.dispatch_w)]
    rob_disp_pred_takes = [m.new_wire(width=1) for _ in range(p.dispatch_w)]
    rob_disp_block_epochs = [m.new_wire(width=16) for _ in range(p.dispatch_w)]
    rob_disp_block_uids = [m.new_wire(width=64) for _ in range(p.dispatch_w)]
    rob_disp_block_bids = [m.new_wire(width=64) for _ in range(p.dispatch_w)]
    rob_disp_load_store_ids = [m.new_wire(width=32) for _ in range(p.dispatch_w)]
    rob_disp_resolved_d2s = [m.new_wire(width=1) for _ in range(p.dispatch_w)]
    rob_disp_srcls = [m.new_wire(width=6) for _ in range(p.dispatch_w)]
    rob_disp_srcrs = [m.new_wire(width=6) for _ in range(p.dispatch_w)]
    rob_disp_uop_uids = [m.new_wire(width=64) for _ in range(p.dispatch_w)]

    rob_wb_fires = [m.new_wire(width=1) for _ in range(p.issue_w)]
    rob_wb_robs = [m.new_wire(width=p.rob_w) for _ in range(p.issue_w)]
    rob_wb_values = [m.new_wire(width=64) for _ in range(p.issue_w)]
    rob_store_fires = [m.new_wire(width=1) for _ in range(p.issue_w)]
    rob_load_fires = [m.new_wire(width=1) for _ in range(p.issue_w)]
    rob_ex_addrs = [m.new_wire(width=64) for _ in range(p.issue_w)]
    rob_ex_wdatas = [m.new_wire(width=64) for _ in range(p.issue_w)]
    rob_ex_sizes = [m.new_wire(width=4) for _ in range(p.issue_w)]
    rob_ex_src0s = [m.new_wire(width=64) for _ in range(p.issue_w)]
    rob_ex_src1s = [m.new_wire(width=64) for _ in range(p.issue_w)]
    rob_issue_fire_lane0_raw = m.new_wire(width=1)
    rob_ex0_is_load = m.new_wire(width=1)
    rob_ex0_addr = m.new_wire(width=64)
    rob_ex0_rob = m.new_wire(width=p.rob_w)
    rob_meta_query_slots = p.issue_w + 1 + 4
    rob_meta_query_idxs = [m.new_wire(width=p.rob_w) for _ in range(rob_meta_query_slots)]

    rob_bank_args = {
        "clk": clk,
        "rst": rst,
        "do_flush": do_flush,
        "commit_count": rob_commit_count,
        "disp_count": rob_disp_count,
        "dispatch_fire": rob_dispatch_fire,
        "replay_pending": state.replay_pending.out(),
        "issue_fire_lane0_raw": rob_issue_fire_lane0_raw,
        "ex0_is_load": rob_ex0_is_load,
        "ex0_addr": rob_ex0_addr,
        "ex0_rob": rob_ex0_rob,
    }
    for slot in range(p.dispatch_w):
        rob_bank_args[f"disp_valid{slot}"] = rob_disp_valids[slot]
        rob_bank_args[f"disp_pc{slot}"] = rob_disp_pcs[slot]
        rob_bank_args[f"disp_op{slot}"] = rob_disp_ops[slot]
        rob_bank_args[f"disp_len{slot}"] = rob_disp_lens[slot]
        rob_bank_args[f"disp_insn_raw{slot}"] = rob_disp_insn_raws[slot]
        rob_bank_args[f"disp_checkpoint_id{slot}"] = rob_disp_checkpoint_ids[slot]
        rob_bank_args[f"disp_dst_kind{slot}"] = rob_disp_dst_kinds[slot]
        rob_bank_args[f"disp_regdst{slot}"] = rob_disp_regdsts[slot]
        rob_bank_args[f"disp_pdst{slot}"] = rob_disp_pdsts[slot]
        rob_bank_args[f"disp_imm{slot}"] = rob_disp_imms[slot]
        rob_bank_args[f"disp_is_store{slot}"] = rob_disp_is_stores[slot]
        rob_bank_args[f"disp_is_boundary{slot}"] = rob_disp_is_boundaries[slot]
        rob_bank_args[f"disp_is_bstart{slot}"] = rob_disp_is_bstarts[slot]
        rob_bank_args[f"disp_is_bstop{slot}"] = rob_disp_is_bstops[slot]
        rob_bank_args[f"disp_boundary_kind{slot}"] = rob_disp_boundary_kinds[slot]
        rob_bank_args[f"disp_boundary_target{slot}"] = rob_disp_boundary_targets[slot]
        rob_bank_args[f"disp_pred_take{slot}"] = rob_disp_pred_takes[slot]
        rob_bank_args[f"disp_block_epoch{slot}"] = rob_disp_block_epochs[slot]
        rob_bank_args[f"disp_block_uid{slot}"] = rob_disp_block_uids[slot]
        rob_bank_args[f"disp_block_bid{slot}"] = rob_disp_block_bids[slot]
        rob_bank_args[f"disp_load_store_id{slot}"] = rob_disp_load_store_ids[slot]
        rob_bank_args[f"disp_resolved_d2{slot}"] = rob_disp_resolved_d2s[slot]
        rob_bank_args[f"disp_srcl{slot}"] = rob_disp_srcls[slot]
        rob_bank_args[f"disp_srcr{slot}"] = rob_disp_srcrs[slot]
        rob_bank_args[f"disp_uop_uid{slot}"] = rob_disp_uop_uids[slot]
        rob_bank_args[f"disp_parent_uid{slot}"] = consts.zero64

    for slot in range(p.commit_w):
        rob_bank_args[f"commit_fire{slot}"] = rob_commit_fires[slot]

    for slot in range(p.issue_w):
        rob_bank_args[f"wb_fire{slot}"] = rob_wb_fires[slot]
        rob_bank_args[f"wb_rob{slot}"] = rob_wb_robs[slot]
        rob_bank_args[f"wb_value{slot}"] = rob_wb_values[slot]
        rob_bank_args[f"store_fire{slot}"] = rob_store_fires[slot]
        rob_bank_args[f"load_fire{slot}"] = rob_load_fires[slot]
        rob_bank_args[f"ex_addr{slot}"] = rob_ex_addrs[slot]
        rob_bank_args[f"ex_wdata{slot}"] = rob_ex_wdatas[slot]
        rob_bank_args[f"ex_size{slot}"] = rob_ex_sizes[slot]
        rob_bank_args[f"ex_src0{slot}"] = rob_ex_src0s[slot]
        rob_bank_args[f"ex_src1{slot}"] = rob_ex_src1s[slot]
    for slot in range(rob_meta_query_slots):
        rob_bank_args[f"meta_query_idx{slot}"] = rob_meta_query_idxs[slot]

    rob_bank = m.instance_auto(
        build_rob_bank_top,
        name="rob_bank",
        params={
            "dispatch_w": p.dispatch_w,
            "issue_w": p.issue_w,
            "commit_w": p.commit_w,
            "rob_depth": p.rob_depth,
            "rob_w": p.rob_w,
            "ptag_w": p.ptag_w,
            "meta_query_slots": rob_meta_query_slots,
        },
        **rob_bank_args,
    )
    rob_head = rob_bank["head_o"]
    rob_tail = rob_bank["tail_o"]
    rob_count = rob_bank["count_o"]
    disp_rob_idxs = [rob_bank[f"disp_rob_idx{slot}_o"] for slot in range(p.dispatch_w)]
    disp_fires = [rob_bank[f"disp_fire{slot}_o"] for slot in range(p.dispatch_w)]
    rob_lsu_older_store_pending_lane0 = rob_bank["lsu_older_store_pending_lane0_o"]
    rob_lsu_forward_hit_lane0 = rob_bank["lsu_forward_hit_lane0_o"]
    rob_lsu_forward_data_lane0 = rob_bank["lsu_forward_data_lane0_o"]
    rob_lsu_violation_replay_set = rob_bank["lsu_violation_replay_set_o"]
    rob_lsu_violation_replay_store_rob = rob_bank["lsu_violation_replay_store_rob_o"]
    rob_lsu_violation_replay_pc = rob_bank["lsu_violation_replay_pc_o"]
    rob_issue_query_uop_uids = [rob_bank[f"meta_query_uop_uid{slot}_o"] for slot in range(p.issue_w)]
    rob_issue_query_parent_uids = [rob_bank[f"meta_query_parent_uid{slot}_o"] for slot in range(p.issue_w)]
    rob_issue_query_block_uids = [rob_bank[f"meta_query_block_uid{slot}_o"] for slot in range(p.issue_w)]
    rob_issue_query_block_bids = [rob_bank[f"meta_query_block_bid{slot}_o"] for slot in range(p.issue_w)]
    rob_issue_query_load_store_ids = [rob_bank[f"meta_query_load_store_id{slot}_o"] for slot in range(p.issue_w)]
    rob_bru_query_slot = p.issue_w
    rob_bru_query_block_epoch = rob_bank[f"meta_query_block_epoch{rob_bru_query_slot}_o"]
    rob_bru_query_checkpoint_id = rob_bank[f"meta_query_checkpoint_id{rob_bru_query_slot}_o"]
    rob_iq_query_base = p.issue_w + 1
    rob_iq_query_uop_uids = [rob_bank[f"meta_query_uop_uid{rob_iq_query_base + slot}_o"] for slot in range(4)]
    rob_iq_query_parent_uids = [rob_bank[f"meta_query_parent_uid{rob_iq_query_base + slot}_o"] for slot in range(4)]
    rob_iq_query_block_uids = [rob_bank[f"meta_query_block_uid{rob_iq_query_base + slot}_o"] for slot in range(4)]
    rob_iq_query_block_bids = [rob_bank[f"meta_query_block_bid{rob_iq_query_base + slot}_o"] for slot in range(4)]
    rob_iq_query_load_store_ids = [rob_bank[f"meta_query_load_store_id{rob_iq_query_base + slot}_o"] for slot in range(4)]

    # --- issue queues (bring-up split; hierarchical banks) ---
    iq_disp_ops = [m.new_wire(width=12) for _ in range(p.dispatch_w)]
    iq_disp_pcs = [m.new_wire(width=64) for _ in range(p.dispatch_w)]
    iq_disp_imms = [m.new_wire(width=64) for _ in range(p.dispatch_w)]
    iq_disp_srcl_tags = [m.new_wire(width=p.ptag_w) for _ in range(p.dispatch_w)]
    iq_disp_srcr_tags = [m.new_wire(width=p.ptag_w) for _ in range(p.dispatch_w)]
    iq_disp_srcr_types = [m.new_wire(width=2) for _ in range(p.dispatch_w)]
    iq_disp_shamts = [m.new_wire(width=6) for _ in range(p.dispatch_w)]
    iq_disp_srcp_tags = [m.new_wire(width=p.ptag_w) for _ in range(p.dispatch_w)]
    iq_disp_pdsts = [m.new_wire(width=p.ptag_w) for _ in range(p.dispatch_w)]
    iq_disp_need_pdsts = [m.new_wire(width=1) for _ in range(p.dispatch_w)]

    iq_alu_disp_tos = [m.new_wire(width=1) for _ in range(p.dispatch_w)]
    iq_bru_disp_tos = [m.new_wire(width=1) for _ in range(p.dispatch_w)]
    iq_lsu_disp_tos = [m.new_wire(width=1) for _ in range(p.dispatch_w)]
    iq_cmd_disp_tos = [m.new_wire(width=1) for _ in range(p.dispatch_w)]

    iq_alu_alloc_idxs = [m.new_wire(width=p.iq_w) for _ in range(p.dispatch_w)]
    iq_bru_alloc_idxs = [m.new_wire(width=p.iq_w) for _ in range(p.dispatch_w)]
    iq_lsu_alloc_idxs = [m.new_wire(width=p.iq_w) for _ in range(p.dispatch_w)]
    iq_cmd_alloc_idxs = [m.new_wire(width=p.iq_w) for _ in range(p.dispatch_w)]

    iq_alu_issue_fires = [m.new_wire(width=1) for _ in range(p.alu_w)]
    iq_alu_issue_idxs = [m.new_wire(width=p.iq_w) for _ in range(p.alu_w)]
    iq_bru_issue_fires = [m.new_wire(width=1) for _ in range(p.bru_w)]
    iq_bru_issue_idxs = [m.new_wire(width=p.iq_w) for _ in range(p.bru_w)]
    iq_lsu_issue_fires = [m.new_wire(width=1) for _ in range(p.lsu_w)]
    iq_lsu_issue_idxs = [m.new_wire(width=p.iq_w) for _ in range(p.lsu_w)]
    iq_cmd_issue_fires = [m.new_wire(width=1)]
    iq_cmd_issue_idxs = [m.new_wire(width=p.iq_w)]

    iq_common_disp_args = {}
    for slot in range(p.dispatch_w):
        iq_common_disp_args[f"disp_fire{slot}"] = disp_fires[slot]
        iq_common_disp_args[f"disp_rob_idx{slot}"] = disp_rob_idxs[slot]
        iq_common_disp_args[f"disp_op{slot}"] = iq_disp_ops[slot]
        iq_common_disp_args[f"disp_pc{slot}"] = iq_disp_pcs[slot]
        iq_common_disp_args[f"disp_imm{slot}"] = iq_disp_imms[slot]
        iq_common_disp_args[f"disp_srcl_tag{slot}"] = iq_disp_srcl_tags[slot]
        iq_common_disp_args[f"disp_srcr_tag{slot}"] = iq_disp_srcr_tags[slot]
        iq_common_disp_args[f"disp_srcr_type{slot}"] = iq_disp_srcr_types[slot]
        iq_common_disp_args[f"disp_shamt{slot}"] = iq_disp_shamts[slot]
        iq_common_disp_args[f"disp_srcp_tag{slot}"] = iq_disp_srcp_tags[slot]
        iq_common_disp_args[f"disp_pdst{slot}"] = iq_disp_pdsts[slot]
        iq_common_disp_args[f"disp_need_pdst{slot}"] = iq_disp_need_pdsts[slot]

    iq_alu_bank_args = {
        "clk": clk,
        "rst": rst,
        "do_flush": do_flush,
        "ready_mask": ren_ready_mask,
        "head_idx": rob_head,
        **iq_common_disp_args,
    }
    iq_bru_bank_args = {
        "clk": clk,
        "rst": rst,
        "do_flush": do_flush,
        "ready_mask": ren_ready_mask,
        "head_idx": rob_head,
        **iq_common_disp_args,
    }
    iq_lsu_bank_args = {
        "clk": clk,
        "rst": rst,
        "do_flush": do_flush,
        "ready_mask": ren_ready_mask,
        "head_idx": rob_head,
        **iq_common_disp_args,
    }
    iq_cmd_bank_args = {
        "clk": clk,
        "rst": rst,
        "do_flush": do_flush,
        "ready_mask": ren_ready_mask,
        "head_idx": rob_head,
        **iq_common_disp_args,
    }

    for slot in range(p.dispatch_w):
        iq_alu_bank_args[f"disp_to{slot}"] = iq_alu_disp_tos[slot]
        iq_bru_bank_args[f"disp_to{slot}"] = iq_bru_disp_tos[slot]
        iq_lsu_bank_args[f"disp_to{slot}"] = iq_lsu_disp_tos[slot]
        iq_cmd_bank_args[f"disp_to{slot}"] = iq_cmd_disp_tos[slot]

        iq_alu_bank_args[f"alloc_idx{slot}"] = iq_alu_alloc_idxs[slot]
        iq_bru_bank_args[f"alloc_idx{slot}"] = iq_bru_alloc_idxs[slot]
        iq_lsu_bank_args[f"alloc_idx{slot}"] = iq_lsu_alloc_idxs[slot]
        iq_cmd_bank_args[f"alloc_idx{slot}"] = iq_cmd_alloc_idxs[slot]

    for slot in range(p.alu_w):
        iq_alu_bank_args[f"issue_fire{slot}"] = iq_alu_issue_fires[slot]
        iq_alu_bank_args[f"issue_idx{slot}"] = iq_alu_issue_idxs[slot]
    for slot in range(p.bru_w):
        iq_bru_bank_args[f"issue_fire{slot}"] = iq_bru_issue_fires[slot]
        iq_bru_bank_args[f"issue_idx{slot}"] = iq_bru_issue_idxs[slot]
    for slot in range(p.lsu_w):
        iq_lsu_bank_args[f"issue_fire{slot}"] = iq_lsu_issue_fires[slot]
        iq_lsu_bank_args[f"issue_idx{slot}"] = iq_lsu_issue_idxs[slot]

    iq_cmd_bank_args["issue_fire0"] = iq_cmd_issue_fires[0]
    iq_cmd_bank_args["issue_idx0"] = iq_cmd_issue_idxs[0]

    iq_alu_bank = m.instance_auto(
        build_iq_bank_top,
        name="iq_alu_bank",
        params={
            "iq_depth": p.iq_depth,
            "iq_w": p.iq_w,
            "rob_w": p.rob_w,
            "ptag_w": p.ptag_w,
            "dispatch_w": p.dispatch_w,
            "issue_w": p.alu_w,
            "pregs": p.pregs,
        },
        **iq_alu_bank_args,
    )
    iq_bru_bank = m.instance_auto(
        build_iq_bank_top,
        name="iq_bru_bank",
        params={
            "iq_depth": p.iq_depth,
            "iq_w": p.iq_w,
            "rob_w": p.rob_w,
            "ptag_w": p.ptag_w,
            "dispatch_w": p.dispatch_w,
            "issue_w": p.bru_w,
            "pregs": p.pregs,
        },
        **iq_bru_bank_args,
    )
    iq_lsu_bank = m.instance_auto(
        build_iq_bank_top,
        name="iq_lsu_bank",
        params={
            "iq_depth": p.iq_depth,
            "iq_w": p.iq_w,
            "rob_w": p.rob_w,
            "ptag_w": p.ptag_w,
            "dispatch_w": p.dispatch_w,
            "issue_w": p.lsu_w,
            "pregs": p.pregs,
        },
        **iq_lsu_bank_args,
    )
    iq_cmd_bank = m.instance_auto(
        build_iq_bank_top,
        name="iq_cmd_bank",
        params={
            "iq_depth": p.iq_depth,
            "iq_w": p.iq_w,
            "rob_w": p.rob_w,
            "ptag_w": p.ptag_w,
            "dispatch_w": p.dispatch_w,
            "issue_w": 1,
            "pregs": p.pregs,
        },
        **iq_cmd_bank_args,
    )

    iq_alu_valid_mask = iq_alu_bank["valid_mask_o"]
    iq_bru_valid_mask = iq_bru_bank["valid_mask_o"]
    iq_lsu_valid_mask = iq_lsu_bank["valid_mask_o"]
    iq_cmd_valid_mask = iq_cmd_bank["valid_mask_o"]

    # --- committed store buffer (drains stores to D-memory) ---
    with m.scope("stbuf"):
        stbuf_head = m.out("head", clk=clk, rst=rst, width=p.sq_w, init=c(0, width=p.sq_w), en=consts.one1)
        stbuf_tail = m.out("tail", clk=clk, rst=rst, width=p.sq_w, init=c(0, width=p.sq_w), en=consts.one1)
        stbuf_count = m.out("count", clk=clk, rst=rst, width=p.sq_w + 1, init=c(0, width=p.sq_w + 1), en=consts.one1)
        stbuf_valid = []
        stbuf_addr = []
        stbuf_data = []
        stbuf_size = []
        for i in range(p.sq_entries):
            stbuf_valid.append(m.out(f"v{i}", clk=clk, rst=rst, width=1, init=consts.zero1, en=consts.one1))
            stbuf_addr.append(m.out(f"a{i}", clk=clk, rst=rst, width=64, init=consts.zero64, en=consts.one1))
            stbuf_data.append(m.out(f"d{i}", clk=clk, rst=rst, width=64, init=consts.zero64, en=consts.one1))
            stbuf_size.append(m.out(f"s{i}", clk=clk, rst=rst, width=4, init=consts.zero4, en=consts.one1))

    # --- commit selection (up to commit_w, stop on redirect/store/halt) ---
    commit_idxs = [rob_bank[f"commit_idx{slot}_o"] for slot in range(p.commit_w)]
    rob_pcs = [rob_bank[f"commit_pc{slot}_o"] for slot in range(p.commit_w)]
    rob_valids = [rob_bank[f"commit_valid{slot}_o"] for slot in range(p.commit_w)]
    rob_dones = [rob_bank[f"commit_done{slot}_o"] for slot in range(p.commit_w)]
    rob_ops = [rob_bank[f"commit_op{slot}_o"] for slot in range(p.commit_w)]
    rob_lens = [rob_bank[f"commit_len{slot}_o"] for slot in range(p.commit_w)]
    rob_dst_kinds = [rob_bank[f"commit_dst_kind{slot}_o"] for slot in range(p.commit_w)]
    rob_dst_aregs = [rob_bank[f"commit_dst_areg{slot}_o"] for slot in range(p.commit_w)]
    rob_pdsts = [rob_bank[f"commit_pdst{slot}_o"] for slot in range(p.commit_w)]
    rob_values = [rob_bank[f"commit_value{slot}_o"] for slot in range(p.commit_w)]
    rob_src0_regs = [rob_bank[f"commit_src0_reg{slot}_o"] for slot in range(p.commit_w)]
    rob_src1_regs = [rob_bank[f"commit_src1_reg{slot}_o"] for slot in range(p.commit_w)]
    rob_src0_values = [rob_bank[f"commit_src0_value{slot}_o"] for slot in range(p.commit_w)]
    rob_src1_values = [rob_bank[f"commit_src1_value{slot}_o"] for slot in range(p.commit_w)]
    rob_src0_valids = [rob_bank[f"commit_src0_valid{slot}_o"] for slot in range(p.commit_w)]
    rob_src1_valids = [rob_bank[f"commit_src1_valid{slot}_o"] for slot in range(p.commit_w)]
    rob_is_stores = [rob_bank[f"commit_is_store{slot}_o"] for slot in range(p.commit_w)]
    rob_st_addrs = [rob_bank[f"commit_store_addr{slot}_o"] for slot in range(p.commit_w)]
    rob_st_datas = [rob_bank[f"commit_store_data{slot}_o"] for slot in range(p.commit_w)]
    rob_st_sizes = [rob_bank[f"commit_store_size{slot}_o"] for slot in range(p.commit_w)]
    rob_is_loads = [rob_bank[f"commit_is_load{slot}_o"] for slot in range(p.commit_w)]
    rob_ld_addrs = [rob_bank[f"commit_load_addr{slot}_o"] for slot in range(p.commit_w)]
    rob_ld_datas = [rob_bank[f"commit_load_data{slot}_o"] for slot in range(p.commit_w)]
    rob_ld_sizes = [rob_bank[f"commit_load_size{slot}_o"] for slot in range(p.commit_w)]
    rob_is_boundaries = [rob_bank[f"commit_is_boundary{slot}_o"] for slot in range(p.commit_w)]
    rob_is_bstarts = [rob_bank[f"commit_is_bstart{slot}_o"] for slot in range(p.commit_w)]
    rob_is_bstops = [rob_bank[f"commit_is_bstop{slot}_o"] for slot in range(p.commit_w)]
    rob_boundary_kinds = [rob_bank[f"commit_boundary_kind{slot}_o"] for slot in range(p.commit_w)]
    rob_boundary_targets = [rob_bank[f"commit_boundary_target{slot}_o"] for slot in range(p.commit_w)]
    rob_pred_takes = [rob_bank[f"commit_pred_take{slot}_o"] for slot in range(p.commit_w)]
    rob_block_epochs = [rob_bank[f"commit_block_epoch{slot}_o"] for slot in range(p.commit_w)]
    rob_block_uids = [rob_bank[f"commit_block_uid{slot}_o"] for slot in range(p.commit_w)]
    rob_block_bids = [rob_bank[f"commit_block_bid{slot}_o"] for slot in range(p.commit_w)]
    rob_load_store_ids = [rob_bank[f"commit_load_store_id{slot}_o"] for slot in range(p.commit_w)]
    rob_resolved_d2s = [rob_bank[f"commit_resolved_d2{slot}_o"] for slot in range(p.commit_w)]
    rob_insn_raws = [rob_bank[f"commit_insn_raw{slot}_o"] for slot in range(p.commit_w)]
    rob_checkpoint_ids = [rob_bank[f"commit_checkpoint_id{slot}_o"] for slot in range(p.commit_w)]
    rob_macro_begins = [rob_bank[f"commit_macro_begin{slot}_o"] for slot in range(p.commit_w)]
    rob_macro_ends = [rob_bank[f"commit_macro_end{slot}_o"] for slot in range(p.commit_w)]
    rob_uop_uids = [rob_bank[f"commit_uop_uid{slot}_o"] for slot in range(p.commit_w)]
    rob_parent_uids = [rob_bank[f"commit_parent_uid{slot}_o"] for slot in range(p.commit_w)]

    head_op = rob_ops[0]
    head_len = rob_lens[0]
    head_dst_kind = rob_dst_kinds[0]
    head_dst_areg = rob_dst_aregs[0]
    head_pdst = rob_pdsts[0]
    head_value = rob_values[0]
    head_is_store = rob_is_stores[0]
    head_st_addr = rob_st_addrs[0]
    head_st_data = rob_st_datas[0]
    head_st_size = rob_st_sizes[0]
    head_is_load = rob_is_loads[0]
    head_ld_addr = rob_ld_addrs[0]
    head_ld_data = rob_ld_datas[0]
    head_ld_size = rob_ld_sizes[0]
    head_insn_raw = rob_insn_raws[0]
    head_checkpoint_id = rob_checkpoint_ids[0]
    head_macro_begin = rob_macro_begins[0]
    head_macro_end = rob_macro_ends[0]
    head_uop_uid = rob_uop_uids[0]

    # Commit-time branch/control decisions (BlockISA markers) for the head.
    commit_head = m.instance_auto(
        build_commit_head_stage,
        name="commit_head_stage",
        head_op=head_op,
        br_kind=state.br_kind.out(),
        commit_cond=state.commit_cond.out(),
    )
    head_is_macro = commit_head["head_is_macro"]
    head_is_start_marker = commit_head["head_is_start_marker"]
    head_is_boundary = commit_head["head_is_boundary"]
    head_br_take = commit_head["head_br_take"]
    head_skip = commit_head["head_skip"]

    # Template blocks (FENTRY/FEXIT/FRET.*) expand into template-uops through
    # CodeTemplateUnit, which blocks IFU while active/starting.
    # UID class encoding:
    # [2:0] 0..3 = decoded slot, 4 = template child, 5 = replay clone.
    template_uid_base = (template_uid_i.shl(amount=3)) | c(4, width=64)
    ctu = m.instance_auto(
        build_code_template_unit,
        name="code_template_unit",
        base_can_run=base_can_run,
        head_is_macro=head_is_macro,
        head_skip=head_skip,
        head_valid=rob_valids[0],
        head_done=rob_dones[0],
        macro_active_i=state.macro_active.out(),
        macro_wait_commit_i=state.macro_wait_commit.out(),
        macro_phase_i=state.macro_phase.out(),
        macro_op_i=state.macro_op.out(),
        macro_end_i=state.macro_end.out(),
        macro_stacksize_i=state.macro_stacksize.out(),
        macro_reg_i=state.macro_reg.out(),
        macro_i_i=state.macro_i.out(),
        macro_sp_base_i=state.macro_sp_base.out(),
        macro_uop_uid_i=template_uid_base,
        macro_uop_parent_uid_i=head_uop_uid,
    )
    macro_start = ctu["start_fire"]
    macro_block = ctu["block_ifu"]

    can_run = base_can_run & (~macro_block)
    # Macro/template expansion must only block frontend progress. Commit still
    # has to run so the macro head can retire and clear macro_wait_commit.
    commit_can_run = base_can_run

    # Return target for FRET.* (via RA, possibly restored by the macro engine).
    ret_ra_tag = ren_cmap_ra
    m.assign(prf_raddr[prf_rd_ret_ra], ret_ra_tag)
    ret_ra_val = prf_rdata[prf_rd_ret_ra]

    commit_allow = consts.one1
    commit_fires = []
    commit_pcs = []
    commit_next_pcs = []
    commit_is_bstarts = []
    commit_is_bstops = []
    commit_block_uids = []
    commit_block_bids = []
    commit_core_ids = []

    commit_count = c(0, width=3)

    redirect_valid = consts.zero1
    redirect_pc = state.pc.out()
    redirect_bid = state.flush_bid.out()
    redirect_checkpoint_id = c(0, width=6)
    redirect_from_corr = consts.zero1
    replay_redirect_fire = consts.zero1

    commit_store_fire = consts.zero1
    commit_store_addr = consts.zero64
    commit_store_data = consts.zero64
    commit_store_size = consts.zero4
    commit_store_seen = consts.zero1
    brob_retire_fire = consts.zero1
    brob_retire_bid = consts.zero64

    pc_live = state.pc.out()
    commit_cond_live = state.commit_cond.out()
    commit_tgt_live = state.commit_tgt.out()
    br_kind_live = state.br_kind.out()
    br_epoch_live = state.br_epoch.out()
    br_base_live = state.br_base_pc.out()
    br_off_live = state.br_off.out()
    br_pred_take_live = state.br_pred_take.out()
    active_block_uid_live = state.active_block_uid.out()
    active_block_bid_live = state.active_block_bid.out()
    lsid_issue_ptr_live = state.lsid_issue_ptr.out()
    lsid_complete_ptr_live = state.lsid_complete_ptr.out()
    block_head_live = state.block_head.out()
    br_corr_pending_live = state.br_corr_pending.out()
    br_corr_epoch_live = state.br_corr_epoch.out()
    br_corr_take_live = state.br_corr_take.out()
    br_corr_target_live = state.br_corr_target.out()
    br_corr_checkpoint_id_live = state.br_corr_checkpoint_id.out()

    stbuf_has_space = stbuf_count.out().ult(c(p.sq_entries, width=p.sq_w + 1))

    for slot in range(p.commit_w):
        allow_macro = consts.one1 if slot == 0 else consts.zero1
        commit_slot = m.instance_auto(
            build_commit_slot_step,
            name=f"commit_slot_step_{slot}",
            can_run_i=commit_can_run,
            allow_macro_i=allow_macro,
            commit_allow_i=commit_allow,
            commit_count_i=commit_count,
            redirect_valid_i=redirect_valid,
            redirect_pc_i=redirect_pc,
            redirect_bid_i=redirect_bid,
            redirect_checkpoint_id_i=redirect_checkpoint_id,
            redirect_from_corr_i=redirect_from_corr,
            replay_redirect_fire_i=replay_redirect_fire,
            commit_store_seen_i=commit_store_seen,
            stbuf_has_space_i=stbuf_has_space,
            brob_retire_fire_i=brob_retire_fire,
            brob_retire_bid_i=brob_retire_bid,
            pc_live_i=pc_live,
            commit_cond_i=commit_cond_live,
            commit_tgt_i=commit_tgt_live,
            br_kind_i=br_kind_live,
            br_epoch_i=br_epoch_live,
            br_base_i=br_base_live,
            br_off_i=br_off_live,
            br_pred_take_i=br_pred_take_live,
            active_block_uid_i=active_block_uid_live,
            active_block_bid_i=active_block_bid_live,
            block_head_i=block_head_live,
            br_corr_pending_i=br_corr_pending_live,
            br_corr_epoch_i=br_corr_epoch_live,
            br_corr_take_i=br_corr_take_live,
            br_corr_target_i=br_corr_target_live,
            br_corr_checkpoint_id_i=br_corr_checkpoint_id_live,
            replay_pending_i=state.replay_pending.out(),
            replay_store_rob_i=state.replay_store_rob.out(),
            replay_pc_i=state.replay_pc.out(),
            ret_ra_val_i=ret_ra_val,
            brob_active_allocated_i=brob_active_allocated_i,
            brob_active_ready_i=brob_active_ready_i,
            brob_active_exception_i=brob_active_exception_i,
            rob_valid_i=rob_valids[slot],
            rob_done_i=rob_dones[slot],
            rob_pc_i=rob_pcs[slot],
            rob_op_i=rob_ops[slot],
            rob_len_i=rob_lens[slot],
            rob_value_i=rob_values[slot],
            rob_is_store_i=rob_is_stores[slot],
            rob_store_addr_i=rob_st_addrs[slot],
            rob_store_data_i=rob_st_datas[slot],
            rob_store_size_i=rob_st_sizes[slot],
            rob_is_bstart_i=rob_is_bstarts[slot],
            rob_is_bstop_i=rob_is_bstops[slot],
            rob_boundary_kind_i=rob_boundary_kinds[slot],
            rob_boundary_target_i=rob_boundary_targets[slot],
            rob_pred_take_i=rob_pred_takes[slot],
            rob_block_uid_i=rob_block_uids[slot],
            rob_block_bid_i=rob_block_bids[slot],
            rob_checkpoint_id_i=rob_checkpoint_ids[slot],
            commit_idx_i=commit_idxs[slot],
        )
        commit_fires.append(commit_slot["commit_fire_o"])
        commit_pcs.append(commit_slot["pc_o"])
        commit_next_pcs.append(commit_slot["pc_next_o"])
        commit_is_bstarts.append(commit_slot["commit_is_bstart_o"])
        commit_is_bstops.append(commit_slot["commit_is_bstop_o"])
        commit_block_uids.append(commit_slot["commit_block_uid_o"])
        commit_block_bids.append(commit_slot["commit_block_bid_o"])
        commit_core_ids.append(c(0, width=2))

        commit_allow = commit_slot["commit_allow_o"]
        commit_count = commit_slot["commit_count_o"]
        redirect_valid = commit_slot["redirect_valid_o"]
        redirect_pc = commit_slot["redirect_pc_o"]
        redirect_bid = commit_slot["redirect_bid_o"]
        redirect_checkpoint_id = commit_slot["redirect_checkpoint_id_o"]
        redirect_from_corr = commit_slot["redirect_from_corr_o"]
        replay_redirect_fire = commit_slot["replay_redirect_fire_o"]
        commit_store_seen = commit_slot["commit_store_seen_o"]
        brob_retire_fire = commit_slot["brob_retire_fire_o"]
        brob_retire_bid = commit_slot["brob_retire_bid_o"]
        pc_live = commit_slot["pc_live_o"]
        commit_cond_live = commit_slot["commit_cond_o"]
        commit_tgt_live = commit_slot["commit_tgt_o"]
        br_kind_live = commit_slot["br_kind_o"]
        br_epoch_live = commit_slot["br_epoch_o"]
        br_base_live = commit_slot["br_base_o"]
        br_off_live = commit_slot["br_off_o"]
        br_pred_take_live = commit_slot["br_pred_take_o"]
        active_block_uid_live = commit_slot["active_block_uid_o"]
        active_block_bid_live = commit_slot["active_block_bid_o"]
        block_head_live = commit_slot["block_head_o"]
        br_corr_pending_live = commit_slot["br_corr_pending_o"]

    # Canonical retired-store selection from committed slots (oldest first).
    # This is the single source used for memory side effects and same-cycle
    # forwarding to keep side effects aligned with retire trace semantics.
    store_sel_fire = consts.zero1
    store_sel_addr = consts.zero64
    store_sel_data = consts.zero64
    store_sel_size = consts.zero4
    for slot in range(p.commit_w):
        slot_store = commit_fires[slot] & rob_is_stores[slot]
        take = slot_store & (~store_sel_fire)
        store_sel_fire = slot_store._select_internal(consts.one1, store_sel_fire)
        store_sel_addr = take._select_internal(rob_st_addrs[slot], store_sel_addr)
        store_sel_data = take._select_internal(rob_st_datas[slot], store_sel_data)
        store_sel_size = take._select_internal(rob_st_sizes[slot], store_sel_size)
    commit_store_fire = store_sel_fire
    commit_store_addr = store_sel_addr
    commit_store_data = store_sel_data
    commit_store_size = store_sel_size

    commit_fire = commit_fires[0]
    commit_redirect = redirect_valid

    # --- store tracking (for conservative load ordering) ---
    store_pending = rob_bank["store_pending_o"]

    # --- issue selection (up to issue_w ready IQ entries) ---

    alu_issue_valids = [iq_alu_bank[f"issue_pick_valid{i}_o"] for i in range(p.alu_w)]
    alu_issue_idxs = [iq_alu_bank[f"issue_pick_idx{i}_o"] for i in range(p.alu_w)]
    alu_uop_robs = [iq_alu_bank[f"issue_pick_rob{i}_o"] for i in range(p.alu_w)]
    alu_uop_ops = [iq_alu_bank[f"issue_pick_op{i}_o"] for i in range(p.alu_w)]
    alu_uop_pcs = [iq_alu_bank[f"issue_pick_pc{i}_o"] for i in range(p.alu_w)]
    alu_uop_imms = [iq_alu_bank[f"issue_pick_imm{i}_o"] for i in range(p.alu_w)]
    alu_uop_sls = [iq_alu_bank[f"issue_pick_srcl{i}_o"] for i in range(p.alu_w)]
    alu_uop_srs = [iq_alu_bank[f"issue_pick_srcr{i}_o"] for i in range(p.alu_w)]
    alu_uop_srcr_types = [iq_alu_bank[f"issue_pick_srcr_type{i}_o"] for i in range(p.alu_w)]
    alu_uop_shamts = [iq_alu_bank[f"issue_pick_shamt{i}_o"] for i in range(p.alu_w)]
    alu_uop_sps = [iq_alu_bank[f"issue_pick_srcp{i}_o"] for i in range(p.alu_w)]
    alu_uop_pdsts = [iq_alu_bank[f"issue_pick_pdst{i}_o"] for i in range(p.alu_w)]
    alu_uop_has_dsts = [iq_alu_bank[f"issue_pick_has_dst{i}_o"] for i in range(p.alu_w)]
    bru_issue_valids = [iq_bru_bank[f"issue_pick_valid{i}_o"] for i in range(p.bru_w)]
    bru_issue_idxs = [iq_bru_bank[f"issue_pick_idx{i}_o"] for i in range(p.bru_w)]
    bru_uop_robs = [iq_bru_bank[f"issue_pick_rob{i}_o"] for i in range(p.bru_w)]
    bru_uop_ops = [iq_bru_bank[f"issue_pick_op{i}_o"] for i in range(p.bru_w)]
    bru_uop_pcs = [iq_bru_bank[f"issue_pick_pc{i}_o"] for i in range(p.bru_w)]
    bru_uop_imms = [iq_bru_bank[f"issue_pick_imm{i}_o"] for i in range(p.bru_w)]
    bru_uop_sls = [iq_bru_bank[f"issue_pick_srcl{i}_o"] for i in range(p.bru_w)]
    bru_uop_srs = [iq_bru_bank[f"issue_pick_srcr{i}_o"] for i in range(p.bru_w)]
    bru_uop_srcr_types = [iq_bru_bank[f"issue_pick_srcr_type{i}_o"] for i in range(p.bru_w)]
    bru_uop_shamts = [iq_bru_bank[f"issue_pick_shamt{i}_o"] for i in range(p.bru_w)]
    bru_uop_sps = [iq_bru_bank[f"issue_pick_srcp{i}_o"] for i in range(p.bru_w)]
    bru_uop_pdsts = [iq_bru_bank[f"issue_pick_pdst{i}_o"] for i in range(p.bru_w)]
    bru_uop_has_dsts = [iq_bru_bank[f"issue_pick_has_dst{i}_o"] for i in range(p.bru_w)]
    lsu_issue_valids = [iq_lsu_bank[f"issue_pick_valid{i}_o"] for i in range(p.lsu_w)]
    lsu_issue_idxs = [iq_lsu_bank[f"issue_pick_idx{i}_o"] for i in range(p.lsu_w)]
    lsu_uop_robs = [iq_lsu_bank[f"issue_pick_rob{i}_o"] for i in range(p.lsu_w)]
    lsu_uop_ops = [iq_lsu_bank[f"issue_pick_op{i}_o"] for i in range(p.lsu_w)]
    lsu_uop_pcs = [iq_lsu_bank[f"issue_pick_pc{i}_o"] for i in range(p.lsu_w)]
    lsu_uop_imms = [iq_lsu_bank[f"issue_pick_imm{i}_o"] for i in range(p.lsu_w)]
    lsu_uop_sls = [iq_lsu_bank[f"issue_pick_srcl{i}_o"] for i in range(p.lsu_w)]
    lsu_uop_srs = [iq_lsu_bank[f"issue_pick_srcr{i}_o"] for i in range(p.lsu_w)]
    lsu_uop_srcr_types = [iq_lsu_bank[f"issue_pick_srcr_type{i}_o"] for i in range(p.lsu_w)]
    lsu_uop_shamts = [iq_lsu_bank[f"issue_pick_shamt{i}_o"] for i in range(p.lsu_w)]
    lsu_uop_sps = [iq_lsu_bank[f"issue_pick_srcp{i}_o"] for i in range(p.lsu_w)]
    lsu_uop_pdsts = [iq_lsu_bank[f"issue_pick_pdst{i}_o"] for i in range(p.lsu_w)]
    lsu_uop_has_dsts = [iq_lsu_bank[f"issue_pick_has_dst{i}_o"] for i in range(p.lsu_w)]

    # Slot ordering: LSU, BRU, ALU (stable debug lane0 = LSU).
    issue_valids = lsu_issue_valids + bru_issue_valids + alu_issue_valids
    issue_idxs = lsu_issue_idxs + bru_issue_idxs + alu_issue_idxs
    issue_fires = [(can_run & (~commit_redirect) & issue_valids[slot]) for slot in range(p.issue_w)]
    uop_robs = lsu_uop_robs + bru_uop_robs + alu_uop_robs
    uop_ops = lsu_uop_ops + bru_uop_ops + alu_uop_ops
    uop_pcs = lsu_uop_pcs + bru_uop_pcs + alu_uop_pcs
    uop_imms = lsu_uop_imms + bru_uop_imms + alu_uop_imms
    uop_sls = lsu_uop_sls + bru_uop_sls + alu_uop_sls
    uop_srs = lsu_uop_srs + bru_uop_srs + alu_uop_srs
    uop_srcr_types = lsu_uop_srcr_types + bru_uop_srcr_types + alu_uop_srcr_types
    uop_shamts = lsu_uop_shamts + bru_uop_shamts + alu_uop_shamts
    uop_sps = lsu_uop_sps + bru_uop_sps + alu_uop_sps
    uop_pdsts = lsu_uop_pdsts + bru_uop_pdsts + alu_uop_pdsts
    uop_has_dsts = lsu_uop_has_dsts + bru_uop_has_dsts + alu_uop_has_dsts

    # CMD IQ/pipe selection:
    # - command split uops are queued in iq_cmd
    # - command enqueue into BISQ is the completion event
    # - when CMD is present, it overlays the last issue slot
    cmd_issue_valid = iq_cmd_bank["issue_pick_valid0_o"]
    cmd_issue_idx = iq_cmd_bank["issue_pick_idx0_o"]
    cmd_uop_rob = iq_cmd_bank["issue_pick_rob0_o"]
    cmd_uop_op = iq_cmd_bank["issue_pick_op0_o"]
    cmd_uop_pc = iq_cmd_bank["issue_pick_pc0_o"]
    cmd_uop_imm = iq_cmd_bank["issue_pick_imm0_o"]
    cmd_uop_sl = iq_cmd_bank["issue_pick_srcl0_o"]
    cmd_uop_sr = iq_cmd_bank["issue_pick_srcr0_o"]
    cmd_uop_srcr_type = iq_cmd_bank["issue_pick_srcr_type0_o"]
    cmd_uop_shamt = iq_cmd_bank["issue_pick_shamt0_o"]
    cmd_uop_sp = iq_cmd_bank["issue_pick_srcp0_o"]
    cmd_uop_pdst = iq_cmd_bank["issue_pick_pdst0_o"]
    cmd_uop_has_dst = iq_cmd_bank["issue_pick_has_dst0_o"]
    cmd_slot = p.issue_w - 1
    cmd_issue_fire_raw = can_run & (~commit_redirect) & cmd_issue_valid
    cmd_issue_fire_eff = cmd_issue_fire_raw & bisq_enq_ready_i
    # Claim the shared issue slot only when CMD can actually enqueue into BISQ.
    cmd_slot_sel = cmd_issue_fire_eff

    # Lane0 retained for trace/debug outputs.
    issue_fire = issue_fires[0]

    uop_robs[cmd_slot] = cmd_slot_sel._select_internal(cmd_uop_rob, uop_robs[cmd_slot])
    uop_ops[cmd_slot] = cmd_slot_sel._select_internal(cmd_uop_op, uop_ops[cmd_slot])
    uop_pcs[cmd_slot] = cmd_slot_sel._select_internal(cmd_uop_pc, uop_pcs[cmd_slot])
    uop_imms[cmd_slot] = cmd_slot_sel._select_internal(cmd_uop_imm, uop_imms[cmd_slot])
    uop_sls[cmd_slot] = cmd_slot_sel._select_internal(cmd_uop_sl, uop_sls[cmd_slot])
    uop_srs[cmd_slot] = cmd_slot_sel._select_internal(cmd_uop_sr, uop_srs[cmd_slot])
    uop_srcr_types[cmd_slot] = cmd_slot_sel._select_internal(cmd_uop_srcr_type, uop_srcr_types[cmd_slot])
    uop_shamts[cmd_slot] = cmd_slot_sel._select_internal(cmd_uop_shamt, uop_shamts[cmd_slot])
    uop_sps[cmd_slot] = cmd_slot_sel._select_internal(cmd_uop_sp, uop_sps[cmd_slot])
    uop_pdsts[cmd_slot] = cmd_slot_sel._select_internal(cmd_uop_pdst, uop_pdsts[cmd_slot])
    uop_has_dsts[cmd_slot] = cmd_slot_sel._select_internal(cmd_uop_has_dst, uop_has_dsts[cmd_slot])
    uop_uids = []
    uop_parent_uids = []
    for slot in range(p.issue_w):
        m.assign(rob_meta_query_idxs[slot], uop_robs[slot])
        uop_uids.append(rob_issue_query_uop_uids[slot])
        uop_parent_uids.append(rob_issue_query_parent_uids[slot])

    # Lane0 named views (stable trace hooks).
    uop_rob = uop_robs[0]
    uop_op = uop_ops[0]
    uop_pc = uop_pcs[0]
    uop_imm = uop_imms[0]
    uop_sl = uop_sls[0]
    uop_sr = uop_srs[0]
    uop_sp = uop_sps[0]
    uop_pdst = uop_pdsts[0]
    uop_has_dst = uop_has_dsts[0]

    # PRF reads + execute for each issued uop.
    sl_vals = []
    sr_vals = []
    sp_vals = []
    exs = []
    for slot in range(p.issue_w):
        prf_base = prf_rd_op_base + (slot * 3)
        m.assign(prf_raddr[prf_base + 0], uop_sls[slot])
        m.assign(prf_raddr[prf_base + 1], uop_srs[slot])
        m.assign(prf_raddr[prf_base + 2], uop_sps[slot])
        sl_vals.append(prf_rdata[prf_base + 0])
        sr_vals.append(prf_rdata[prf_base + 1])
        sp_vals.append(prf_rdata[prf_base + 2])
        exec_inst = m.instance_auto(
            build_exec_uop,
            name=f"exec_uop_{slot}",
            op_i=uop_ops[slot],
            pc_i=uop_pcs[slot],
            imm_i=uop_imms[slot],
            srcl_val_i=sl_vals[slot],
            srcr_val_i=sr_vals[slot],
            srcr_type_i=uop_srcr_types[slot],
            shamt_i=uop_shamts[slot],
            srcp_val_i=sp_vals[slot],
        )
        exs.append(
            ExecOut(
                alu=exec_inst["alu_o"],
                is_load=exec_inst["is_load_o"],
                is_store=exec_inst["is_store_o"],
                size=exec_inst["size_o"],
                addr=exec_inst["addr_o"],
                wdata=exec_inst["wdata_o"],
            )
        )

    # Lane0 values for debug/trace.
    sl_val = sl_vals[0]
    sr_val = sr_vals[0]
    sp_val = sp_vals[0]

    issue_fires_eff = [issue_fires[i] for i in range(p.issue_w)]
    issue_fires_eff[cmd_slot] = cmd_slot_sel._select_internal(cmd_issue_fire_eff, issue_fires_eff[cmd_slot])
    cmd_payload_lane = cmd_uop_imm
    cmd_payload_lane = cmd_uop_op.__eq__(c(OP_BIOR, width=12))._select_internal(sl_vals[cmd_slot] | sr_vals[cmd_slot], cmd_payload_lane)
    cmd_payload_lane = cmd_uop_op.__eq__(c(OP_BLOAD, width=12))._select_internal(sl_vals[cmd_slot] + cmd_uop_imm, cmd_payload_lane)
    cmd_payload_lane = cmd_uop_op.__eq__(c(OP_BSTORE, width=12))._select_internal(sr_vals[cmd_slot], cmd_payload_lane)

    # Memory disambiguation/forwarding for the LSU lane (lane0).
    m.assign(rob_issue_fire_lane0_raw, issue_fires[0])
    m.assign(rob_ex0_is_load, exs[0].is_load)
    m.assign(rob_ex0_addr, exs[0].addr)
    m.assign(rob_ex0_rob, uop_robs[0])
    lsu_stage_args = {
        "issue_fire_lane0_raw": issue_fires[0],
        "ex0_is_load": exs[0].is_load,
        "ex0_is_store": exs[0].is_store,
        "ex0_addr": exs[0].addr,
        "ex0_lsid": rob_issue_query_load_store_ids[0],
        "lsid_issue_ptr": state.lsid_issue_ptr.out(),
        "commit_store_fire": commit_store_fire,
        "commit_store_addr": commit_store_addr,
        "commit_store_data": commit_store_data,
        "rob_older_store_pending_lane0": rob_lsu_older_store_pending_lane0,
        "rob_forward_hit_lane0": rob_lsu_forward_hit_lane0,
        "rob_forward_data_lane0": rob_lsu_forward_data_lane0,
    }
    for i in range(p.sq_entries):
        lsu_stage_args[f"stbuf_valid{i}"] = stbuf_valid[i].out()
        lsu_stage_args[f"stbuf_addr{i}"] = stbuf_addr[i].out()
        lsu_stage_args[f"stbuf_data{i}"] = stbuf_data[i].out()

    lsu_stage = m.instance_auto(
        build_lsu_stage,
        name="lsu_stage",
        params={"sq_entries": p.sq_entries, "sq_w": p.sq_w},
        **lsu_stage_args,
    )
    lsu_load_fire_raw = lsu_stage["lsu_load_fire_raw"]
    lsu_older_store_pending_lane0 = lsu_stage["lsu_older_store_pending_lane0"]
    lsu_forward_hit_lane0 = lsu_stage["lsu_forward_hit_lane0"]
    lsu_forward_data_lane0 = lsu_stage["lsu_forward_data_lane0"]
    lsu_lsid_block_lane0 = lsu_stage["lsu_lsid_block_lane0"]
    lsu_block_lane0 = lsu_stage["lsu_block_lane0"]
    lsu_lsid_issue_advance = lsu_stage["lsu_lsid_issue_advance"]
    issue_fires_eff[0] = lsu_stage["issue_fire_lane0_eff"]
    issue_fire = issue_fires_eff[0]
    lsid_issue_ptr_live = lsu_lsid_issue_advance._select_internal(lsid_issue_ptr_live + c(1, width=32), lsid_issue_ptr_live)
    lsid_complete_ptr_live = lsu_lsid_issue_advance._select_internal(lsid_complete_ptr_live + c(1, width=32), lsid_complete_ptr_live)
    # Redirect/flush drops in-flight younger memory ops: rebase LSID issue/complete
    # pointers to the current allocation head so stale IDs cannot deadlock LSU.
    lsid_issue_ptr_live = do_flush._select_internal(state.lsid_alloc_ctr.out(), lsid_issue_ptr_live)
    lsid_complete_ptr_live = do_flush._select_internal(state.lsid_alloc_ctr.out(), lsid_complete_ptr_live)

    issue_is_loads = []
    issue_is_stores = []
    load_mem_fires = []
    any_load_mem_fire = consts.zero1
    load_addr = consts.zero64
    for slot in range(p.issue_w):
        issue_is_loads.append(exs[slot].is_load)
        issue_is_stores.append(exs[slot].is_store)
        ld = issue_fires_eff[slot] & exs[slot].is_load
        ld_mem = ld
        if slot == 0:
            ld_mem = ld & (~lsu_forward_hit_lane0)
        load_mem_fires.append(ld_mem)
        any_load_mem_fire = any_load_mem_fire | ld_mem
        load_addr = ld_mem._select_internal(exs[slot].addr, load_addr)

    issued_is_load = issue_fires_eff[0] & issue_is_loads[0]
    issued_is_store = issue_fires_eff[0] & issue_is_stores[0]
    older_store_pending = lsu_older_store_pending_lane0

    # LSU violation replay state (updated after wb metadata is formed).
    replay_set = consts.zero1
    replay_set_store_rob = state.replay_store_rob.out()
    replay_set_pc = state.replay_pc.out()
    lsu_violation_detected = consts.zero1

    # --- template macro engine (FENTRY/FEXIT/FRET.*) ---
    macro_active = state.macro_active.out()
    macro_phase = state.macro_phase.out()
    macro_op = state.macro_op.out()
    macro_begin = state.macro_begin.out()
    macro_end = state.macro_end.out()
    macro_stacksize = state.macro_stacksize.out()
    # Optional fixed callframe addend via boundary value port.
    # Enforce the same alignment rule as old env parsing: non-8B-aligned
    # values are treated as zero.
    callframe_align_ok = (callframe_size_i & c(0x7, width=64)).__eq__(consts.zero64)
    macro_callframe_size = callframe_align_ok._select_internal(callframe_size_i, consts.zero64)
    macro_frame_adj = macro_stacksize + macro_callframe_size
    macro_reg = state.macro_reg.out()
    macro_i = state.macro_i.out()
    macro_sp_base = state.macro_sp_base.out()

    macro_is_fentry = ctu["macro_is_fentry"]
    macro_phase_init = ctu["phase_init"]
    macro_phase_mem = ctu["phase_mem"]
    macro_phase_sp = ctu["phase_sp"]
    macro_phase_setc = ctu["phase_setc"]
    macro_off_ok = ctu["off_ok"]
    macro_is_fexit = macro_op.__eq__(c(OP_FEXIT, width=12))
    macro_is_fret_ra = macro_op.__eq__(c(OP_FRET_RA, width=12))
    macro_is_fret_stk = macro_op.__eq__(c(OP_FRET_STK, width=12))

    # CodeTemplateUnit emits one template-uop per cycle while active.
    macro_uop_valid = ctu["uop_valid"]
    macro_uop_kind = ctu["uop_kind"]
    macro_uop_reg = ctu["uop_reg"]
    m.assign(ren_macro_uop_reg, macro_uop_reg)
    macro_uop_addr = ctu["uop_addr"]
    macro_uop_uid = ctu["uop_uid"]
    macro_uop_parent_uid = ctu["uop_parent_uid"]
    macro_uop_template_kind = ctu["uop_template_kind"]
    macro_uop_is_sp_sub = ctu["uop_is_sp_sub"]
    macro_uop_is_store = ctu["uop_is_store"]
    macro_uop_is_load = ctu["uop_is_load"]
    macro_uop_is_sp_add = ctu["uop_is_sp_add"]
    macro_uop_is_setc_tgt = ctu["uop_is_setc_tgt"]

    # D-memory read arbitration: macro restore-load > LSU load.
    macro_mem_read = macro_uop_is_load
    dmem_raddr = macro_mem_read._select_internal(macro_uop_addr, any_load_mem_fire._select_internal(load_addr, consts.zero64))

    # Macro/template uop operand reads.
    macro_reg_tag = ren_macro_reg_tag
    macro_sp_tag = ren_cmap_sp
    m.assign(prf_raddr[prf_rd_macro_reg], macro_reg_tag)
    m.assign(prf_raddr[prf_rd_macro_sp], macro_sp_tag)
    macro_reg_val = prf_rdata[prf_rd_macro_reg]
    macro_sp_val = prf_rdata[prf_rd_macro_sp]
    macro_reg_is_gpr = macro_uop_reg.ult(c(24, width=6))
    macro_reg_not_zero = ~macro_uop_reg.__eq__(c(0, width=6))
    macro_store_fire = macro_uop_is_store & macro_reg_is_gpr & macro_reg_not_zero
    macro_store_addr = macro_uop_addr
    macro_store_data = macro_reg_val
    macro_store_size = c(8, width=4)

    # MMIO (QEMU virt).
    #
    # - UART data: 0x1000_0000 (write low byte)
    # - EXIT:      0x1000_0004 (write exit code; stop simulation)
    mmio_uart = commit_store_fire & commit_store_addr.__eq__(c(0x1000_0000, width=64))
    mmio_exit = commit_store_fire & commit_store_addr.__eq__(c(0x1000_0004, width=64))
    mmio_any = mmio_uart | mmio_exit

    mmio_uart_data = mmio_uart._select_internal(commit_store_data._trunc(width=8), c(0, width=8))
    mmio_exit_code = mmio_exit._select_internal(commit_store_data._trunc(width=32), c(0, width=32))

    # Preserve store ordering:
    # - If the committed-store buffer already has older entries, enqueue all new
    #   committed stores (unless MMIO) so younger writes cannot bypass older ones.
    # - If macro uses the single write port this cycle, enqueue as well.
    stbuf_empty = stbuf_count.out().__eq__(c(0, width=p.sq_w + 1))
    commit_store_defer = commit_store_fire & (~mmio_any) & (macro_store_fire | (~stbuf_empty))
    stbuf_enq_fire = commit_store_defer
    stbuf_enq_idx = stbuf_tail.out()
    stbuf_enq_tail = stbuf_tail.out() + c(1, width=p.sq_w)

    stbuf_drain_fire = (~macro_store_fire) & (~commit_store_fire) & (~stbuf_count.out().__eq__(c(0, width=p.sq_w + 1)))
    stbuf_drain_addr = mux_by_uindex(m, idx=stbuf_head.out(), items=stbuf_addr, default=consts.zero64)
    stbuf_drain_data = mux_by_uindex(m, idx=stbuf_head.out(), items=stbuf_data, default=consts.zero64)
    stbuf_drain_size = mux_by_uindex(m, idx=stbuf_head.out(), items=stbuf_size, default=consts.zero4)
    stbuf_drain_head = stbuf_head.out() + c(1, width=p.sq_w)

    commit_store_write_through = commit_store_fire & (~mmio_any) & (~commit_store_defer)
    mem_wvalid = macro_store_fire | commit_store_write_through | stbuf_drain_fire
    mem_waddr = macro_store_fire._select_internal(
        macro_store_addr,
        commit_store_write_through._select_internal(commit_store_addr, stbuf_drain_addr),
    )
    dmem_wdata = macro_store_fire._select_internal(
        macro_store_data,
        commit_store_write_through._select_internal(commit_store_data, stbuf_drain_data),
    )
    mem_wsize = macro_store_fire._select_internal(
        macro_store_size,
        commit_store_write_through._select_internal(commit_store_size, stbuf_drain_size),
    )

    dmem_wsrc = c(0, width=2)
    dmem_wsrc = macro_store_fire._select_internal(c(1, width=2), dmem_wsrc)
    dmem_wsrc = commit_store_write_through._select_internal(c(2, width=2), dmem_wsrc)
    dmem_wsrc = stbuf_drain_fire._select_internal(c(3, width=2), dmem_wsrc)

    # Store write port (writes at clk edge). Stop-at-store ensures that at most
    # one store commits per cycle in this bring-up model; the macro engine
    # consumes the same single write port.
    wstrb = consts.zero8
    wstrb = mem_wsize.__eq__(c(1, width=4))._select_internal(c(0x01, width=8), wstrb)
    wstrb = mem_wsize.__eq__(c(2, width=4))._select_internal(c(0x03, width=8), wstrb)
    wstrb = mem_wsize.__eq__(c(4, width=4))._select_internal(c(0x0F, width=8), wstrb)
    wstrb = mem_wsize.__eq__(c(8, width=4))._select_internal(c(0xFF, width=8), wstrb)

    # Store buffer register updates.
    for i in range(p.sq_entries):
        idx = c(i, width=p.sq_w)
        do_enq = stbuf_enq_fire & stbuf_enq_idx.__eq__(idx)
        do_drain = stbuf_drain_fire & stbuf_head.out().__eq__(idx)
        v_next = stbuf_valid[i].out()
        v_next = do_drain._select_internal(consts.zero1, v_next)
        v_next = do_enq._select_internal(consts.one1, v_next)
        stbuf_valid[i].set(v_next)
        stbuf_addr[i].set(commit_store_addr, when=do_enq)
        stbuf_data[i].set(commit_store_data, when=do_enq)
        stbuf_size[i].set(commit_store_size, when=do_enq)

    stbuf_head_next = stbuf_head.out()
    stbuf_tail_next = stbuf_tail.out()
    stbuf_count_next = stbuf_count.out()
    stbuf_tail_next = stbuf_enq_fire._select_internal(stbuf_enq_tail, stbuf_tail_next)
    stbuf_count_next = stbuf_enq_fire._select_internal(stbuf_count_next + c(1, width=p.sq_w + 1), stbuf_count_next)
    stbuf_head_next = stbuf_drain_fire._select_internal(stbuf_drain_head, stbuf_head_next)
    stbuf_count_next = stbuf_drain_fire._select_internal(stbuf_count_next - c(1, width=p.sq_w + 1), stbuf_count_next)
    stbuf_head.set(stbuf_head_next)
    stbuf_tail.set(stbuf_tail_next)
    stbuf_count.set(stbuf_count_next)

    dmem_rdata = dmem_rdata_i
    macro_load_fwd_hit = consts.zero1
    macro_load_fwd_data = consts.zero64
    for i in range(p.sq_entries):
        st_match = stbuf_valid[i].out() & stbuf_addr[i].out().__eq__(macro_uop_addr)
        macro_load_fwd_hit = (macro_uop_is_load & st_match)._select_internal(consts.one1, macro_load_fwd_hit)
        macro_load_fwd_data = (macro_uop_is_load & st_match)._select_internal(stbuf_data[i].out(), macro_load_fwd_data)
    macro_load_data = macro_load_fwd_hit._select_internal(macro_load_fwd_data, dmem_rdata)
    # FRET.STK must consume the loaded stack RA value. Only FRET.RA uses the
    # saved-RA bypass path.
    macro_restore_ra = macro_uop_is_load & op_is(macro_op, OP_FRET_RA) & macro_uop_reg.__eq__(c(10, width=6))
    macro_load_data_eff = macro_restore_ra._select_internal(state.macro_saved_ra.out(), macro_load_data)
    # FRET.STK can finish immediately after restoring RA (e.g. [ra~ra]).
    # In that case there is no standalone SETC_TGT phase; consume the restored
    # RA value as return target on the RA-load step.
    macro_setc_from_fret_stk_ra_load = macro_uop_is_load & macro_is_fret_stk & macro_uop_reg.__eq__(c(10, width=6))
    macro_setc_tgt_fire = macro_uop_is_setc_tgt | macro_setc_from_fret_stk_ra_load
    macro_setc_tgt_data = ret_ra_val
    macro_setc_tgt_data = macro_setc_from_fret_stk_ra_load._select_internal(macro_load_data_eff, macro_setc_tgt_data)
    macro_setc_tgt_data = (macro_uop_is_setc_tgt & macro_is_fret_stk)._select_internal(state.macro_saved_ra.out(), macro_setc_tgt_data)

    macro_is_restore = macro_active & (~macro_is_fentry)

    # Macro PRF write port (one write per cycle).
    macro_reg_write = macro_uop_is_load & macro_reg_is_gpr & macro_reg_not_zero
    macro_sp_write_init = macro_uop_is_sp_sub
    macro_sp_write_restore = macro_uop_is_sp_add

    macro_prf_we = macro_reg_write | macro_sp_write_init | macro_sp_write_restore
    macro_prf_tag = macro_sp_tag
    macro_prf_data = consts.zero64
    macro_prf_tag = macro_reg_write._select_internal(macro_reg_tag, macro_prf_tag)
    macro_prf_data = macro_reg_write._select_internal(macro_load_data_eff, macro_prf_data)
    macro_prf_data = macro_sp_write_restore._select_internal(macro_sp_val + macro_frame_adj, macro_prf_data)
    macro_prf_data = macro_sp_write_init._select_internal(macro_sp_val - macro_frame_adj, macro_prf_data)

    # Load result (uses dmem_rdata in the same cycle raddr is set).
    load8 = dmem_rdata._trunc(width=8)
    load16 = dmem_rdata._trunc(width=16)
    load32 = dmem_rdata._trunc(width=32)
    load_lb = load8._sext(width=64)
    load_lbu = load8._zext(width=64)
    load_lh = load16._sext(width=64)
    load_lhu = load16._zext(width=64)
    load_lw = load32._sext(width=64)
    load_lwu = load32._zext(width=64)
    load_ld = dmem_rdata
    lsu_forward_active = (issue_fires_eff[0] & issue_is_loads[0]) & lsu_forward_hit_lane0
    issue_result_values = []
    wb_fires = []
    wb_robs = []
    wb_pdsts = []
    wb_values = []
    wb_fire_has_dsts = []
    load_fires = []
    store_fires = []
    wb_onehots = []
    for slot in range(p.issue_w):
        op = uop_ops[slot]
        load_val = load_lw
        load_val = op_is(op, OP_LB, OP_LBI, OP_HL_LB_PCR)._select_internal(load_lb, load_val)
        load_val = op_is(op, OP_LBU, OP_LBUI, OP_HL_LBU_PCR)._select_internal(load_lbu, load_val)
        load_val = op_is(op, OP_LH, OP_LHI, OP_HL_LH_PCR)._select_internal(load_lh, load_val)
        load_val = op_is(op, OP_LHU, OP_LHUI, OP_HL_LHU_PCR)._select_internal(load_lhu, load_val)
        load_val = op_is(op, OP_LWI, OP_C_LWI, OP_LW, OP_HL_LW_PCR)._select_internal(load_lw, load_val)
        load_val = op_is(op, OP_LWU, OP_LWUI, OP_HL_LWU_PCR)._select_internal(load_lwu, load_val)
        load_val = op_is(op, OP_LD, OP_LDI, OP_C_LDI, OP_HL_LD_PCR)._select_internal(load_ld, load_val)
        if slot == 0:
            fwd8 = lsu_forward_data_lane0._trunc(width=8)
            fwd16 = lsu_forward_data_lane0._trunc(width=16)
            fwd32 = lsu_forward_data_lane0._trunc(width=32)
            load_fwd = fwd32._sext(width=64)
            load_fwd = op_is(op, OP_LB, OP_LBI, OP_HL_LB_PCR)._select_internal(fwd8._sext(width=64), load_fwd)
            load_fwd = op_is(op, OP_LBU, OP_LBUI, OP_HL_LBU_PCR)._select_internal(fwd8._zext(width=64), load_fwd)
            load_fwd = op_is(op, OP_LH, OP_LHI, OP_HL_LH_PCR)._select_internal(fwd16._sext(width=64), load_fwd)
            load_fwd = op_is(op, OP_LHU, OP_LHUI, OP_HL_LHU_PCR)._select_internal(fwd16._zext(width=64), load_fwd)
            load_fwd = op_is(op, OP_LWI, OP_C_LWI, OP_LW, OP_HL_LW_PCR)._select_internal(fwd32._sext(width=64), load_fwd)
            load_fwd = op_is(op, OP_LWU, OP_LWUI, OP_HL_LWU_PCR)._select_internal(fwd32._zext(width=64), load_fwd)
            load_fwd = op_is(op, OP_LD, OP_LDI, OP_C_LDI, OP_HL_LD_PCR)._select_internal(lsu_forward_data_lane0, load_fwd)
            load_val = lsu_forward_active._select_internal(load_fwd, load_val)
        wb_value = issue_is_loads[slot]._select_internal(load_val, exs[slot].alu)
        if slot == cmd_slot:
            wb_value = cmd_slot_sel._select_internal(cmd_payload_lane, wb_value)
        issue_result_values.append(wb_value)
        wb_fire = issue_fires_eff[slot]
        wb_fires.append(wb_fire)
        wb_robs.append(uop_robs[slot])
        wb_pdsts.append(uop_pdsts[slot])
        wb_values.append(wb_value)
        wb_fire_has_dsts.append(wb_fire & uop_has_dsts[slot] & (~issue_is_stores[slot]))
        load_fires.append(wb_fire & issue_is_loads[slot])
        store_fires.append(wb_fire & issue_is_stores[slot])
        wb_onehots.append(onehot_from_tag(m, tag=uop_pdsts[slot], width=p.pregs, tag_width=p.ptag_w))

    replay_set = rob_lsu_violation_replay_set
    replay_set_store_rob = replay_set._select_internal(rob_lsu_violation_replay_store_rob, state.replay_store_rob.out())
    replay_set_pc = replay_set._select_internal(rob_lsu_violation_replay_pc, state.replay_pc.out())
    lsu_violation_detected = replay_set

    # --- dispatch frontend cluster ---
    dispatch_frontend = m.instance_auto(
        build_dispatch_frontend,
        name="dispatch_frontend",
        params={
            "dispatch_w": p.dispatch_w,
            "iq_depth": p.iq_depth,
            "iq_w": p.iq_w,
            "rob_depth": p.rob_depth,
            "rob_w": p.rob_w,
            "pregs": p.pregs,
            "ptag_w": p.ptag_w,
        },
        clk=clk,
        rst=rst,
        can_run=can_run,
        commit_redirect=commit_redirect,
        f4_valid=f4_valid,
        f4_pc=f4_pc,
        f4_window=f4_window,
        f4_checkpoint_id=f4_checkpoint,
        f4_pkt_uid=f4_pkt_uid,
        block_head_in=state.block_head.out(),
        block_epoch_in=state.br_epoch.out(),
        block_uid_in=state.assign_block_uid.out(),
        block_bid_in=state.assign_block_bid.out(),
        brob_alloc_ready_i=brob_alloc_ready_i,
        brob_alloc_bid_i=brob_alloc_bid_i,
        lsid_alloc_base=state.lsid_alloc_ctr.out(),
        rob_count=rob_count,
        ren_free_mask=ren_free_mask,
        iq_alu_valid_mask=iq_alu_valid_mask,
        iq_bru_valid_mask=iq_bru_valid_mask,
        iq_lsu_valid_mask=iq_lsu_valid_mask,
        iq_cmd_valid_mask=iq_cmd_valid_mask,
    )

    disp_valids = []
    disp_pcs = []
    disp_ops = []
    disp_lens = []
    disp_regdsts = []
    disp_srcls = []
    disp_srcrs = []
    disp_srcr_types = []
    disp_shamts = []
    disp_srcps = []
    disp_imms = []
    disp_insn_raws = []
    disp_is_start_marker = []
    disp_push_t = []
    disp_push_u = []
    disp_is_store = []
    disp_is_boundary = []
    disp_is_bstart = []
    disp_is_bstop = []
    disp_boundary_kind = []
    disp_boundary_target = []
    disp_pred_take = []
    disp_resolved_d2 = []
    disp_dst_is_gpr = []
    disp_need_pdst = []
    disp_dst_kind = []
    disp_checkpoint_ids = []
    disp_decode_uop_uids = []

    for slot in range(p.dispatch_w):
        disp_valids.append(dispatch_frontend[f"valid{slot}"])
        disp_pcs.append(dispatch_frontend[f"pc{slot}"])
        disp_ops.append(dispatch_frontend[f"op{slot}"])
        disp_lens.append(dispatch_frontend[f"len{slot}"])
        disp_regdsts.append(dispatch_frontend[f"regdst{slot}"])
        disp_srcls.append(dispatch_frontend[f"srcl{slot}"])
        disp_srcrs.append(dispatch_frontend[f"srcr{slot}"])
        disp_srcr_types.append(dispatch_frontend[f"srcr_type{slot}"])
        disp_shamts.append(dispatch_frontend[f"shamt{slot}"])
        disp_srcps.append(dispatch_frontend[f"srcp{slot}"])
        disp_imms.append(dispatch_frontend[f"imm{slot}"])
        disp_insn_raws.append(dispatch_frontend[f"insn_raw{slot}"])
        disp_is_start_marker.append(dispatch_frontend[f"is_start_marker{slot}"])
        disp_push_t.append(dispatch_frontend[f"push_t{slot}"])
        disp_push_u.append(dispatch_frontend[f"push_u{slot}"])
        disp_is_store.append(dispatch_frontend[f"is_store{slot}"])
        disp_is_boundary.append(dispatch_frontend[f"is_boundary{slot}"])
        disp_is_bstart.append(dispatch_frontend[f"is_bstart{slot}"])
        disp_is_bstop.append(dispatch_frontend[f"is_bstop{slot}"])
        disp_boundary_kind.append(dispatch_frontend[f"boundary_kind{slot}"])
        disp_boundary_target.append(dispatch_frontend[f"boundary_target{slot}"])
        disp_pred_take.append(dispatch_frontend[f"pred_take{slot}"])
        disp_resolved_d2.append(dispatch_frontend[f"resolved_d2{slot}"])
        disp_dst_is_gpr.append(dispatch_frontend[f"dst_is_gpr{slot}"])
        disp_need_pdst.append(dispatch_frontend[f"need_pdst{slot}"])
        disp_dst_kind.append(dispatch_frontend[f"dst_kind{slot}"])
        disp_checkpoint_ids.append(dispatch_frontend[f"checkpoint_id{slot}"])
        disp_decode_uop_uids.append(dispatch_frontend[f"uop_uid{slot}"])

    # BRU validation for SETC.cond: compare actual result vs predicted direction.
    # Keep BSTART-history state inside the recovery owner; parent only sees the
    # compact correction/fault result.
    bru_corr_set = consts.zero1
    bru_corr_take = consts.zero1
    bru_corr_target = consts.zero64
    bru_corr_checkpoint_id = c(0, width=6)
    bru_corr_epoch = c(0, width=16)
    bru_fault_set = consts.zero1
    bru_fault_rob = c(0, width=p.rob_w)
    bru_validate_fire = consts.zero1
    bru_mismatch_evt = consts.zero1
    bru_actual_take_dbg = consts.zero1
    bru_meta_query_idx = c(0, width=p.rob_w)
    bru_pred_take_dbg = state.br_pred_take.out()
    bru_boundary_pc_dbg = state.br_base_pc.out()
    if p.bru_w > 0:
        bru_slot = p.lsu_w
        bru_fire = issue_fires_eff[bru_slot]
        bru_op = uop_ops[bru_slot]
        bru_rob = uop_robs[bru_slot]
        bru_meta_query_idx = bru_rob
        bru_epoch = rob_bru_query_block_epoch
        bru_checkpoint = rob_bru_query_checkpoint_id
        bru_actual_take = issue_result_values[bru_slot]._trunc(width=1)
        bru_is_setc_cond = is_setc_any(bru_op, op_is) & (~is_setc_tgt(bru_op, op_is))
        bru_validate_fire = bru_fire & bru_is_setc_cond
        bru_actual_take_dbg = bru_actual_take
        bru_pred_take_dbg = state.br_pred_take.out()
        bru_boundary_pc_dbg = state.br_base_pc.out()
        bru_target = state.br_base_pc.out() + state.br_off.out()
        bru_target = state.br_kind.out().__eq__(c(BK_RET, width=3))._select_internal(state.commit_tgt.out(), bru_target)
        bru_recovery_owner = m.instance_auto(
            build_bru_recovery_owner,
            name="bru_recovery_owner",
            params={
                "dispatch_w": p.dispatch_w,
                "pcb_depth": p.rob_depth,
                "pcb_w": p.rob_w,
                "rob_w": p.rob_w,
            },
            clk=clk,
            rst=rst,
            f4_valid=f4_valid,
            bru_fire=bru_fire,
            bru_is_setc_cond=bru_is_setc_cond,
            bru_target=bru_target,
            bru_actual_take=bru_actual_take,
            bru_checkpoint=bru_checkpoint,
            bru_epoch=bru_epoch,
            bru_rob=bru_rob,
            state_br_kind=state.br_kind.out(),
            state_br_epoch=state.br_epoch.out(),
            state_br_pred_take=state.br_pred_take.out(),
            **{f"disp_valid{slot}": disp_valids[slot] for slot in range(p.dispatch_w)},
            **{f"disp_pc{slot}": disp_pcs[slot] for slot in range(p.dispatch_w)},
            **{f"disp_is_bstart{slot}": disp_is_bstart[slot] for slot in range(p.dispatch_w)},
        )
        bru_mismatch_evt = bru_recovery_owner["bru_mismatch_evt"]
        bru_corr_set = bru_recovery_owner["bru_corr_set"]
        bru_corr_take = bru_recovery_owner["bru_corr_take"]
        bru_corr_target = bru_recovery_owner["bru_corr_target"]
        bru_corr_checkpoint_id = bru_recovery_owner["bru_corr_checkpoint_id"]
        bru_corr_epoch = bru_recovery_owner["bru_corr_epoch"]
        bru_fault_set = bru_recovery_owner["bru_fault_set"]
        bru_fault_rob = bru_recovery_owner["bru_fault_rob"]
    m.assign(rob_meta_query_idxs[rob_bru_query_slot], bru_meta_query_idx)

    # Lane0 decode (stable trace hook).
    dec_op = dispatch_frontend["dec_op"]
    disp_count = dispatch_frontend["dispatch_count"]

    rob_space_ok = dispatch_frontend["rob_space_ok"]
    iq_alloc_ok = dispatch_frontend["iq_alloc_ok"]
    preg_alloc_ok = dispatch_frontend["preg_alloc_ok"]
    bid_alloc_ok = dispatch_frontend["bid_alloc_ok"]
    frontend_ready = dispatch_frontend["frontend_ready"]
    dispatch_fire = dispatch_frontend["dispatch_fire"]
    brob_alloc_fire = dispatch_frontend["brob_alloc_fire"]
    disp_alloc_mask = dispatch_frontend["disp_alloc_mask"]
    lsid_alloc_next = dispatch_frontend["lsid_alloc_next"]

    disp_to_alu = []
    disp_to_bru = []
    disp_to_lsu = []
    disp_to_d2 = []
    disp_to_cmd = []
    alu_alloc_valids = []
    alu_alloc_idxs = []
    bru_alloc_valids = []
    bru_alloc_idxs = []
    lsu_alloc_valids = []
    lsu_alloc_idxs = []
    cmd_alloc_valids = []
    cmd_alloc_idxs = []
    disp_pdsts = []
    disp_block_uids = []
    disp_block_bids = []
    disp_load_store_ids = []
    for slot in range(p.dispatch_w):
        disp_to_alu.append(dispatch_frontend[f"to_alu{slot}"])
        disp_to_bru.append(dispatch_frontend[f"to_bru{slot}"])
        disp_to_lsu.append(dispatch_frontend[f"to_lsu{slot}"])
        disp_to_d2.append(dispatch_frontend[f"to_d2{slot}"])
        disp_to_cmd.append(dispatch_frontend[f"to_cmd{slot}"])
        alu_alloc_valids.append(dispatch_frontend[f"alu_alloc_valid{slot}"])
        alu_alloc_idxs.append(dispatch_frontend[f"alu_alloc_idx{slot}"])
        bru_alloc_valids.append(dispatch_frontend[f"bru_alloc_valid{slot}"])
        bru_alloc_idxs.append(dispatch_frontend[f"bru_alloc_idx{slot}"])
        lsu_alloc_valids.append(dispatch_frontend[f"lsu_alloc_valid{slot}"])
        lsu_alloc_idxs.append(dispatch_frontend[f"lsu_alloc_idx{slot}"])
        cmd_alloc_valids.append(dispatch_frontend[f"cmd_alloc_valid{slot}"])
        cmd_alloc_idxs.append(dispatch_frontend[f"cmd_alloc_idx{slot}"])
        disp_pdsts.append(dispatch_frontend[f"disp_pdst{slot}"])
        disp_block_uids.append(dispatch_frontend[f"disp_block_uid{slot}"])
        disp_block_bids.append(dispatch_frontend[f"disp_block_bid{slot}"])
        disp_load_store_ids.append(dispatch_frontend[f"disp_load_store_id{slot}"])

    # Wire rename bank inputs (hierarchical SMAP/CMAP + ready/free).
    m.assign(ren_dispatch_fire, dispatch_fire)
    m.assign(ren_disp_alloc_mask, disp_alloc_mask)
    m.assign(ren_flush_checkpoint_id, state.flush_checkpoint_id.out())

    wb_set_mask = c(0, width=p.pregs)
    for slot in range(p.issue_w):
        wb_set_mask = wb_fire_has_dsts[slot]._select_internal(wb_set_mask | wb_onehots[slot], wb_set_mask)
    m.assign(ren_wb_set_mask, wb_set_mask)

    for slot in range(p.dispatch_w):
        m.assign(ren_disp_valids[slot], disp_valids[slot])
        m.assign(ren_disp_srcls[slot], disp_srcls[slot])
        m.assign(ren_disp_srcrs[slot], disp_srcrs[slot])
        m.assign(ren_disp_srcps[slot], disp_srcps[slot])
        m.assign(ren_disp_is_start_markers[slot], disp_is_start_marker[slot])
        m.assign(ren_disp_push_ts[slot], disp_push_t[slot])
        m.assign(ren_disp_push_us[slot], disp_push_u[slot])
        m.assign(ren_disp_dst_is_gprs[slot], disp_dst_is_gpr[slot])
        m.assign(ren_disp_regdsts[slot], disp_regdsts[slot])
        m.assign(ren_disp_pdsts[slot], disp_pdsts[slot])
        m.assign(ren_disp_checkpoint_ids[slot], disp_checkpoint_ids[slot])

    for slot in range(p.commit_w):
        m.assign(ren_commit_fires[slot], commit_fires[slot])
        m.assign(ren_commit_is_bstops[slot], commit_is_bstops[slot])
        m.assign(ren_rob_dst_kinds[slot], rob_dst_kinds[slot])
        m.assign(ren_rob_dst_aregs[slot], rob_dst_aregs[slot])
        m.assign(ren_rob_pdsts[slot], rob_pdsts[slot])

    # PRF writes (up to issue_w writebacks per cycle + macro write port).
    for slot in range(p.issue_w):
        m.assign(prf_wen[slot], wb_fire_has_dsts[slot])
        m.assign(prf_waddr[slot], wb_pdsts[slot])
        m.assign(prf_wdata[slot], wb_values[slot])
    m.assign(prf_wen[p.issue_w], macro_prf_we)
    m.assign(prf_waddr[p.issue_w], macro_prf_tag)
    m.assign(prf_wdata[p.issue_w], macro_prf_data)

    # Wire ROB bank update/control inputs.
    m.assign(rob_commit_count, commit_count)
    for slot in range(p.commit_w):
        m.assign(rob_commit_fires[slot], commit_fires[slot])

    m.assign(rob_disp_count, disp_count)
    m.assign(rob_dispatch_fire, dispatch_fire)
    for slot in range(p.dispatch_w):
        m.assign(rob_disp_valids[slot], disp_valids[slot])
        m.assign(rob_disp_pcs[slot], disp_pcs[slot])
        m.assign(rob_disp_ops[slot], disp_ops[slot])
        m.assign(rob_disp_lens[slot], disp_lens[slot])
        m.assign(rob_disp_insn_raws[slot], disp_insn_raws[slot])
        m.assign(rob_disp_checkpoint_ids[slot], disp_checkpoint_ids[slot])
        m.assign(rob_disp_dst_kinds[slot], disp_dst_kind[slot])
        m.assign(rob_disp_regdsts[slot], disp_regdsts[slot])
        m.assign(rob_disp_pdsts[slot], disp_pdsts[slot])
        m.assign(rob_disp_imms[slot], disp_imms[slot])
        m.assign(rob_disp_is_stores[slot], disp_is_store[slot])
        m.assign(rob_disp_is_boundaries[slot], disp_is_boundary[slot])
        m.assign(rob_disp_is_bstarts[slot], disp_is_bstart[slot])
        m.assign(rob_disp_is_bstops[slot], disp_is_bstop[slot])
        m.assign(rob_disp_boundary_kinds[slot], disp_boundary_kind[slot])
        m.assign(rob_disp_boundary_targets[slot], disp_boundary_target[slot])
        m.assign(rob_disp_pred_takes[slot], disp_pred_take[slot])
        m.assign(rob_disp_block_epochs[slot], dispatch_frontend[f"disp_block_epoch{slot}"])
        m.assign(rob_disp_block_uids[slot], disp_block_uids[slot])
        m.assign(rob_disp_block_bids[slot], disp_block_bids[slot])
        m.assign(rob_disp_load_store_ids[slot], disp_load_store_ids[slot])
        m.assign(rob_disp_resolved_d2s[slot], disp_resolved_d2[slot])
        m.assign(rob_disp_srcls[slot], disp_srcls[slot])
        m.assign(rob_disp_srcrs[slot], disp_srcrs[slot])
        m.assign(rob_disp_uop_uids[slot], disp_decode_uop_uids[slot])

    for slot in range(p.issue_w):
        m.assign(rob_wb_fires[slot], wb_fires[slot])
        m.assign(rob_wb_robs[slot], wb_robs[slot])
        m.assign(rob_wb_values[slot], wb_values[slot])
        m.assign(rob_store_fires[slot], store_fires[slot])
        m.assign(rob_load_fires[slot], load_fires[slot])
        m.assign(rob_ex_addrs[slot], exs[slot].addr)
        m.assign(rob_ex_wdatas[slot], exs[slot].wdata)
        m.assign(rob_ex_sizes[slot], exs[slot].size)
        m.assign(rob_ex_src0s[slot], sl_vals[slot])
        m.assign(rob_ex_src1s[slot], sr_vals[slot])

    # --- IQ updates (owned by LinxCoreIqBankTop) ---
    for slot in range(p.dispatch_w):
        m.assign(iq_disp_ops[slot], disp_ops[slot])
        m.assign(iq_disp_pcs[slot], disp_pcs[slot])
        m.assign(iq_disp_imms[slot], disp_imms[slot])
        m.assign(iq_disp_srcl_tags[slot], disp_srcl_tags[slot])
        m.assign(iq_disp_srcr_tags[slot], disp_srcr_tags[slot])
        m.assign(iq_disp_srcr_types[slot], disp_srcr_types[slot])
        m.assign(iq_disp_shamts[slot], disp_shamts[slot])
        m.assign(iq_disp_srcp_tags[slot], disp_srcp_tags[slot])
        m.assign(iq_disp_pdsts[slot], disp_pdsts[slot])
        m.assign(iq_disp_need_pdsts[slot], disp_need_pdst[slot])

        m.assign(iq_lsu_disp_tos[slot], disp_to_lsu[slot])
        m.assign(iq_bru_disp_tos[slot], disp_to_bru[slot])
        m.assign(iq_alu_disp_tos[slot], disp_to_alu[slot])
        m.assign(iq_cmd_disp_tos[slot], disp_to_cmd[slot])

        m.assign(iq_lsu_alloc_idxs[slot], lsu_alloc_idxs[slot])
        m.assign(iq_bru_alloc_idxs[slot], bru_alloc_idxs[slot])
        m.assign(iq_alu_alloc_idxs[slot], alu_alloc_idxs[slot])
        m.assign(iq_cmd_alloc_idxs[slot], cmd_alloc_idxs[slot])

    lsu_base = 0
    bru_base = p.lsu_w
    alu_base = p.lsu_w + p.bru_w
    alu_issue_fires_eff = [issue_fires_eff[alu_base + i] for i in range(p.alu_w)]
    cmd_slot_in_alu = cmd_slot - alu_base
    if (cmd_slot_in_alu >= 0) and (cmd_slot_in_alu < p.alu_w):
        alu_issue_fires_eff[cmd_slot_in_alu] = alu_issue_fires_eff[cmd_slot_in_alu] & (~cmd_slot_sel)
    for slot in range(p.lsu_w):
        m.assign(iq_lsu_issue_fires[slot], issue_fires_eff[lsu_base + slot])
        m.assign(iq_lsu_issue_idxs[slot], lsu_issue_idxs[slot])
    for slot in range(p.bru_w):
        m.assign(iq_bru_issue_fires[slot], issue_fires_eff[bru_base + slot])
        m.assign(iq_bru_issue_idxs[slot], bru_issue_idxs[slot])
    for slot in range(p.alu_w):
        m.assign(iq_alu_issue_fires[slot], alu_issue_fires_eff[slot])
        m.assign(iq_alu_issue_idxs[slot], alu_issue_idxs[slot])
    m.assign(iq_cmd_issue_fires[0], cmd_issue_fire_eff)
    m.assign(iq_cmd_issue_idxs[0], cmd_issue_idx)

    # Rename state updates are owned by LinxCoreRenameBankTop.

    # --- commit state updates (pc/br/control regs) ---
    state.pc.set(pc_live)
    # Architectural redirect authority is boundary-commit only.
    commit_redirect_stage = m.instance_auto(
        build_commit_redirect,
        name="commit_redirect_stage",
        module_name="LinxCoreCommitRedirectBackend",
        clk=clk,
        rst=rst,
        commit_fire=commit_redirect,
        commit_next_pc=redirect_pc,
        commit_bid=redirect_bid,
    )
    commit_redirect_any = commit_redirect_stage["redirect_valid_o"]
    redirect_pc_any = commit_redirect_stage["redirect_pc_o"]
    redirect_bid_any = commit_redirect_stage["redirect_bid_o"]
    redirect_checkpoint_id_any = redirect_checkpoint_id

    # Deferred BRU correction/fault tracking.
    br_corr_pending_n = br_corr_pending_live
    br_corr_epoch_n = br_corr_epoch_live
    br_corr_take_n = br_corr_take_live
    br_corr_target_n = br_corr_target_live
    br_corr_checkpoint_id_n = br_corr_checkpoint_id_live
    br_corr_pending_n = do_flush._select_internal(consts.zero1, br_corr_pending_n)
    br_corr_pending_n = bru_corr_set._select_internal(consts.one1, br_corr_pending_n)
    br_corr_epoch_n = bru_corr_set._select_internal(bru_corr_epoch, br_corr_epoch_n)
    br_corr_take_n = bru_corr_set._select_internal(bru_corr_take, br_corr_take_n)
    br_corr_target_n = bru_corr_set._select_internal(bru_corr_target, br_corr_target_n)
    br_corr_checkpoint_id_n = bru_corr_set._select_internal(bru_corr_checkpoint_id, br_corr_checkpoint_id_n)
    corr_epoch_stale = br_corr_pending_n & (~br_corr_epoch_n.__eq__(br_epoch_live))
    br_corr_pending_n = corr_epoch_stale._select_internal(consts.zero1, br_corr_pending_n)

    br_corr_fault_pending_n = state.br_corr_fault_pending.out()
    br_corr_fault_rob_n = state.br_corr_fault_rob.out()
    br_corr_fault_pending_n = do_flush._select_internal(consts.zero1, br_corr_fault_pending_n)
    br_corr_fault_pending_n = bru_fault_set._select_internal(consts.one1, br_corr_fault_pending_n)
    br_corr_fault_rob_n = bru_fault_set._select_internal(bru_fault_rob, br_corr_fault_rob_n)

    commit_ctrl_args = {
        "do_flush": do_flush,
        "f4_valid": f4_valid,
        "f4_pc": f4_pc,
        "commit_redirect": commit_redirect_any,
        "redirect_pc": redirect_pc_any,
        "redirect_checkpoint_id": redirect_checkpoint_id_any,
        "mmio_exit": mmio_exit,
        "state_fpc": state.fpc.out(),
        "state_flush_pc": state.flush_pc.out(),
        "state_flush_checkpoint_id": state.flush_checkpoint_id.out(),
        "state_flush_pending": state.flush_pending.out(),
        "state_replay_pending": state.replay_pending.out(),
        "state_replay_store_rob": state.replay_store_rob.out(),
        "state_replay_pc": state.replay_pc.out(),
        "replay_redirect_fire": replay_redirect_fire,
        "replay_set": replay_set,
        "replay_set_store_rob": replay_set_store_rob,
        "replay_set_pc": replay_set_pc,
    }
    for slot in range(p.commit_w):
        commit_ctrl_args[f"commit_fire{slot}"] = commit_fires[slot]
        commit_ctrl_args[f"rob_op{slot}"] = rob_ops[slot]

    commit_ctrl = m.instance_auto(
        build_commit_ctrl_stage,
        name="commit_ctrl_stage",
        params={"commit_w": p.commit_w, "rob_w": p.rob_w},
        **commit_ctrl_args,
    )
    state.fpc.set(commit_ctrl["fpc_next"])
    state.flush_pc.set(commit_ctrl["flush_pc_next"])
    state.flush_checkpoint_id.set(commit_ctrl["flush_checkpoint_id_next"])
    state.flush_pending.set(commit_ctrl["flush_pending_next"])
    flush_bid_n = state.flush_bid.out()
    flush_bid_n = commit_redirect_any._select_internal(redirect_bid_any, flush_bid_n)
    state.flush_bid.set(flush_bid_n)
    state.replay_pending.set(commit_ctrl["replay_pending_next"])
    state.replay_store_rob.set(commit_ctrl["replay_store_rob_next"])
    state.replay_pc.set(commit_ctrl["replay_pc_next"])
    trap_retire = consts.zero1
    for slot in range(p.commit_w):
        trap_retire = trap_retire | (commit_fires[slot] & commit_idxs[slot].__eq__(state.trap_rob.out()))
    trap_retire = trap_retire & state.trap_pending.out()
    trap_pending_n = state.trap_pending.out()
    trap_rob_n = state.trap_rob.out()
    trap_cause_n = state.trap_cause.out()
    trap_pending_n = do_flush._select_internal(consts.zero1, trap_pending_n)
    trap_pending_n = trap_retire._select_internal(consts.zero1, trap_pending_n)
    trap_pending_n = br_corr_fault_pending_n._select_internal(consts.one1, trap_pending_n)
    trap_rob_n = br_corr_fault_pending_n._select_internal(br_corr_fault_rob_n, trap_rob_n)
    trap_cause_n = br_corr_fault_pending_n._select_internal(c(TRAP_BRU_RECOVERY_NOT_BSTART, width=32), trap_cause_n)
    state.trap_pending.set(trap_pending_n)
    state.trap_rob.set(trap_rob_n)
    state.trap_cause.set(trap_cause_n)
    br_corr_fault_pending_n = trap_retire._select_internal(consts.zero1, br_corr_fault_pending_n)
    state.br_corr_fault_pending.set(br_corr_fault_pending_n)
    state.br_corr_fault_rob.set(br_corr_fault_rob_n)
    state.halted.set(consts.one1, when=(commit_ctrl["halt_set"] | trap_retire))

    commit_cond_live = macro_setc_tgt_fire._select_internal(consts.one1, commit_cond_live)
    commit_tgt_live = macro_setc_tgt_fire._select_internal(macro_setc_tgt_data, commit_tgt_live)
    state.cycles.set(state.cycles.out() + consts.one64)
    state.commit_cond.set(commit_cond_live)
    state.commit_tgt.set(commit_tgt_live)
    state.br_kind.set(br_kind_live)
    state.br_epoch.set(br_epoch_live)
    state.br_base_pc.set(br_base_live)
    state.br_off.set(br_off_live)
    state.br_pred_take.set(br_pred_take_live)
    state.active_block_uid.set(active_block_uid_live)
    state.active_block_bid.set(active_block_bid_live)
    # D1-time block identity: follow BROB allocator for new BSTARTs, and restore
    # assignment domain to the retire-stream identity on flush.
    assign_block_uid_n = state.assign_block_uid.out()
    assign_block_bid_n = state.assign_block_bid.out()
    assign_block_uid_n = do_flush._select_internal(active_block_uid_live, assign_block_uid_n)
    assign_block_bid_n = do_flush._select_internal(active_block_bid_live, assign_block_bid_n)
    assign_block_uid_n = brob_alloc_fire._select_internal(brob_alloc_bid_i, assign_block_uid_n)
    assign_block_bid_n = brob_alloc_fire._select_internal(brob_alloc_bid_i, assign_block_bid_n)
    state.assign_block_uid.set(assign_block_uid_n)
    state.assign_block_bid.set(assign_block_bid_n)
    state.lsid_alloc_ctr.set(lsid_alloc_next)
    state.lsid_issue_ptr.set(lsid_issue_ptr_live)
    state.lsid_complete_ptr.set(lsid_complete_ptr_live)
    state.block_head.set(do_flush._select_internal(consts.one1, block_head_live))
    state.br_corr_pending.set(br_corr_pending_n)
    state.br_corr_epoch.set(br_corr_epoch_n)
    state.br_corr_take.set(br_corr_take_n)
    state.br_corr_target.set(br_corr_target_n)
    state.br_corr_checkpoint_id.set(br_corr_checkpoint_id_n)

    # --- template macro engine state updates ---
    #
    # Implements the bring-up ABI semantics used by QEMU/LLVM:
    # - FENTRY: SP_SUB, then STORE loop.
    # - FEXIT: SP_ADD, then LOAD loop.
    # - FRET.STK: SP_ADD, LOAD ra, SETC.TGT ra, then remaining LOAD loop.
    # - FRET.RA: SETC.TGT ra, SP_ADD, then LOAD loop.
    ph_init = c(0, width=2)
    ph_mem = c(1, width=2)
    ph_sp = c(2, width=2)
    ph_setc = c(3, width=2)

    macro_active_n = macro_active
    macro_phase_n = macro_phase
    macro_op_n = macro_op
    macro_begin_n = state.macro_begin.out()
    macro_end_n = state.macro_end.out()
    macro_stack_n = macro_stacksize
    macro_reg_n = macro_reg
    macro_i_n = macro_i
    macro_sp_base_n = macro_sp_base

    macro_active_n = do_flush._select_internal(consts.zero1, macro_active_n)
    macro_phase_n = do_flush._select_internal(ph_init, macro_phase_n)

    macro_active_n = macro_start._select_internal(consts.one1, macro_active_n)
    macro_phase_n = macro_start._select_internal(ph_init, macro_phase_n)
    macro_op_n = macro_start._select_internal(head_op, macro_op_n)
    macro_begin_n = macro_start._select_internal(head_macro_begin, macro_begin_n)
    macro_end_n = macro_start._select_internal(head_macro_end, macro_end_n)
    macro_stack_n = macro_start._select_internal(head_value, macro_stack_n)
    macro_reg_n = macro_start._select_internal(head_macro_begin, macro_reg_n)
    macro_i_n = macro_start._select_internal(c(0, width=6), macro_i_n)

    macro_phase_is_init = macro_phase_init
    macro_phase_is_mem = macro_phase_mem
    macro_phase_is_sp = macro_phase_sp
    macro_phase_is_setc = macro_phase_setc

    # Init: latch base SP and setup iteration.
    init_fire = macro_active & macro_phase_is_init
    sp_new_init = macro_sp_val - macro_frame_adj
    sp_new_restore = macro_sp_val + macro_frame_adj
    macro_sp_base_n = (init_fire & macro_is_fentry)._select_internal(sp_new_init, macro_sp_base_n)
    macro_sp_base_n = (init_fire & (macro_is_fexit | macro_is_fret_stk))._select_internal(sp_new_restore, macro_sp_base_n)
    macro_reg_n = init_fire._select_internal(macro_begin, macro_reg_n)
    macro_i_n = init_fire._select_internal(c(0, width=6), macro_i_n)
    macro_phase_n = (init_fire & (macro_is_fentry | macro_is_fexit | macro_is_fret_stk))._select_internal(ph_mem, macro_phase_n)
    macro_phase_n = (init_fire & macro_is_fret_ra)._select_internal(ph_sp, macro_phase_n)

    # Mem loop: iterate regs and offsets; save uses store port, restore uses load port.
    step_fire = ctu["loop_fire"]
    step_done = ctu["loop_done"]
    reg_next = ctu["loop_reg_next"]
    i_next = ctu["loop_i_next"]
    macro_reg_n = (step_fire & (~step_done))._select_internal(reg_next, macro_reg_n)
    macro_i_n = (step_fire & (~step_done))._select_internal(i_next, macro_i_n)

    # FRET.STK requires a SETC.TGT immediately after restoring RA.
    step_ra_restore = step_fire & macro_is_fret_stk & macro_uop_is_load & macro_uop_reg.__eq__(c(10, width=6))
    macro_phase_n = (step_ra_restore & (~step_done))._select_internal(ph_setc, macro_phase_n)

    done_macro = step_done & (macro_is_fentry | macro_is_fexit | macro_is_fret_stk | macro_is_fret_ra)
    macro_active_n = done_macro._select_internal(consts.zero1, macro_active_n)
    macro_phase_n = done_macro._select_internal(ph_init, macro_phase_n)

    # FRET.RA has an explicit SP_ADD phase before restore loads.
    sp_fire = macro_active & macro_phase_is_sp & macro_is_fret_ra
    macro_sp_base_n = sp_fire._select_internal(sp_new_restore, macro_sp_base_n)
    macro_phase_n = sp_fire._select_internal(ph_mem, macro_phase_n)

    # FRET.STK emits SETC.TGT as a standalone template uop between RA load
    # and the remaining restore-load loop.
    setc_fire = macro_active & macro_phase_is_setc & macro_is_fret_stk
    macro_phase_n = setc_fire._select_internal(ph_mem, macro_phase_n)

    macro_wait_n = state.macro_wait_commit.out()
    macro_wait_n = do_flush._select_internal(consts.zero1, macro_wait_n)
    macro_wait_n = macro_start._select_internal(consts.one1, macro_wait_n)
    macro_committed = consts.zero1
    for slot in range(p.commit_w):
        op = rob_ops[slot]
        fire = commit_fires[slot]
        macro_committed = macro_committed | (fire & op_is(op, OP_FENTRY, OP_FEXIT, OP_FRET_RA, OP_FRET_STK))
    macro_wait_n = macro_committed._select_internal(consts.zero1, macro_wait_n)

    # Suppress one synthetic C.BSTART boundary-dup right after a macro
    # commit handoff (macro commit advances to a new PC).
    macro_handoff = consts.zero1
    for slot in range(p.commit_w):
        op = rob_ops[slot]
        fire = commit_fires[slot]
        is_macro_evt = op_is(op, OP_FENTRY, OP_FEXIT, OP_FRET_RA, OP_FRET_STK)
        macro_handoff = macro_handoff | (fire & is_macro_evt & (~commit_next_pcs[slot].__eq__(commit_pcs[slot])))
    any_commit_fire = consts.zero1
    for slot in range(p.commit_w):
        any_commit_fire = any_commit_fire | commit_fires[slot]
    post_macro_handoff_n = state.post_macro_handoff.out()
    post_macro_handoff_n = do_flush._select_internal(consts.zero1, post_macro_handoff_n)
    post_macro_handoff_n = macro_handoff._select_internal(consts.one1, post_macro_handoff_n)
    post_macro_handoff_n = (any_commit_fire & (~macro_handoff))._select_internal(consts.zero1, post_macro_handoff_n)

    state.macro_active.set(macro_active_n)
    state.macro_wait_commit.set(macro_wait_n)
    state.post_macro_handoff.set(post_macro_handoff_n)
    state.macro_phase.set(macro_phase_n)
    state.macro_op.set(macro_op_n)
    state.macro_begin.set(macro_begin_n)
    state.macro_end.set(macro_end_n)
    state.macro_stacksize.set(macro_stack_n)
    state.macro_reg.set(macro_reg_n)
    state.macro_i.set(macro_i_n)
    state.macro_sp_base.set(macro_sp_base_n)
    macro_saved_ra_n = state.macro_saved_ra.out()
    save_ra_fire = macro_store_fire & macro_uop_reg.__eq__(c(10, width=6))
    restore_ra_fire = macro_reg_write & macro_uop_reg.__eq__(c(10, width=6)) & macro_is_fret_stk
    macro_saved_ra_n = save_ra_fire._select_internal(macro_store_data, macro_saved_ra_n)
    macro_saved_ra_n = restore_ra_fire._select_internal(macro_load_data_eff, macro_saved_ra_n)
    state.macro_saved_ra.set(macro_saved_ra_n)

    # --- outputs ---
    m.output("halted", state.halted)
    m.output("cycles", state.cycles)
    m.output("pc", state.pc)
    m.output("fpc", state.fpc)
    m.output("rob_head_valid", rob_valids[0])
    m.output("rob_head_done", rob_dones[0])
    m.output("rob_head_pc", rob_pcs[0])
    m.output("rob_head_insn_raw", head_insn_raw)
    m.output("rob_head_len", head_len)
    m.output("rob_head_op", head_op)

    # Commit slot visibility (bring-up tracing): per-slot PC/op/value/fire.
    #
    # Template blocks (FENTRY/FEXIT/FRET.*) are traced as restartable
    # per-step retire events to match QEMU commit semantics exactly.
    # The internal macro ROB commit is hidden from the trace stream.
    macro_trace_fire = macro_uop_valid
    macro_adj_nonzero = ~macro_frame_adj.__eq__(consts.zero64)
    macro_trace_pc = state.pc.out()
    macro_trace_seq_pc = macro_trace_pc + head_len._zext(width=64)
    macro_enc = map_template_child_encoding(
        m,
        macro_op=macro_op,
        macro_insn_raw=head_insn_raw,
        uop_is_sp_sub=macro_uop_is_sp_sub,
        uop_is_sp_add=macro_uop_is_sp_add,
        uop_is_store=macro_uop_is_store,
        uop_is_load=macro_uop_is_load,
        uop_is_setc_tgt=macro_uop_is_setc_tgt,
    )
    macro_trace_op = macro_enc["op"]
    macro_trace_val = head_value
    macro_trace_rob = rob_head
    macro_trace_len = macro_enc["len"]
    macro_trace_insn = macro_enc["insn_raw"]

    macro_trace_wb_load = macro_reg_write
    macro_trace_wb_sp_sub = macro_uop_is_sp_sub & macro_adj_nonzero
    # QEMU commit trace does not currently report the SP adjust micro-op for
    # FRET.STK; hide it from the retire trace to keep lockstep stable.
    macro_trace_wb_sp_add = macro_uop_is_sp_add & macro_adj_nonzero & (~macro_is_fret_stk)
    macro_trace_wb_valid = macro_trace_fire & (macro_trace_wb_load | macro_trace_wb_sp_sub | macro_trace_wb_sp_add)
    macro_trace_wb_rd = c(0, width=6)
    macro_trace_wb_rd = macro_trace_wb_load._select_internal(macro_uop_reg, macro_trace_wb_rd)
    macro_trace_wb_rd = (macro_trace_wb_sp_sub | macro_trace_wb_sp_add)._select_internal(c(1, width=6), macro_trace_wb_rd)
    macro_trace_wb_data = consts.zero64
    macro_trace_wb_data = macro_trace_wb_load._select_internal(macro_load_data_eff, macro_trace_wb_data)
    macro_trace_wb_data = macro_trace_wb_sp_add._select_internal(macro_sp_val + macro_frame_adj, macro_trace_wb_data)
    macro_trace_wb_data = macro_trace_wb_sp_sub._select_internal(macro_sp_val - macro_frame_adj, macro_trace_wb_data)

    macro_trace_mem_store = macro_store_fire
    macro_trace_mem_load = macro_uop_is_load & macro_reg_is_gpr & macro_reg_not_zero
    macro_trace_mem_valid = macro_trace_fire & (macro_trace_mem_store | macro_trace_mem_load)
    macro_trace_mem_is_store = macro_trace_fire & macro_trace_mem_store
    macro_trace_mem_addr = macro_uop_addr
    macro_trace_mem_wdata = macro_trace_mem_store._select_internal(macro_store_data, consts.zero64)
    macro_trace_mem_rdata = macro_trace_mem_load._select_internal(macro_load_data_eff, consts.zero64)
    macro_trace_mem_size = macro_trace_mem_valid._select_internal(c(8, width=4), consts.zero4)
    macro_trace_src0_valid = macro_trace_fire & (macro_uop_is_sp_sub | macro_uop_is_sp_add | macro_store_fire | macro_uop_is_load)
    macro_trace_src0_reg = c(1, width=6)
    macro_trace_src0_reg = macro_store_fire._select_internal(macro_uop_reg, macro_trace_src0_reg)
    macro_trace_src0_data = macro_sp_val
    macro_trace_src0_data = macro_store_fire._select_internal(macro_store_data, macro_trace_src0_data)
    macro_trace_src1_valid = consts.zero1
    macro_trace_src1_reg = c(0, width=6)
    macro_trace_src1_data = consts.zero64
    macro_trace_dst_valid = macro_trace_wb_valid
    macro_trace_dst_reg = macro_trace_wb_rd
    macro_trace_dst_data = macro_trace_wb_data

    macro_trace_is_fentry = macro_op.__eq__(c(OP_FENTRY, width=12))
    macro_trace_is_fexit = macro_op.__eq__(c(OP_FEXIT, width=12))
    macro_trace_is_fret = op_is(macro_op, OP_FRET_RA, OP_FRET_STK)
    macro_trace_done_fentry = (macro_uop_is_sp_sub & macro_stacksize.__eq__(consts.zero64)) | (macro_uop_is_store & step_done)
    macro_trace_done_fexit = macro_uop_is_load & step_done & macro_trace_is_fexit
    macro_trace_done_fret = macro_uop_is_load & step_done & macro_trace_is_fret
    macro_trace_next_pc = macro_trace_pc
    macro_trace_next_pc = macro_trace_done_fentry._select_internal(macro_trace_seq_pc, macro_trace_next_pc)
    macro_trace_next_pc = macro_trace_done_fexit._select_internal(macro_trace_seq_pc, macro_trace_next_pc)
    macro_trace_next_pc = macro_trace_done_fret._select_internal(commit_tgt_live, macro_trace_next_pc)
    # QEMU commit-trace emits a side-effect-free template marker before the
    # first architecturally visible FRET template micro-op.
    macro_shadow_fire = macro_trace_fire & (
        (macro_is_fret_stk & macro_uop_is_sp_add) |
        (macro_is_fret_ra & macro_uop_is_setc_tgt)
    )
    # Keep dynamic uop IDs unique even when we emit both a shadow marker and
    # the architecturally visible template uop in the same cycle.
    macro_shadow_uid = macro_uop_uid | c(1 << 62, width=64)
    macro_shadow_uid_alt = macro_shadow_uid | c(1, width=64)

    # Keep retire trace strictly instruction-driven; qemu-specific boundary-only
    # metadata commits are filtered in the lockstep runner.
    shadow_boundary_fire = consts.zero1
    shadow_boundary_fire1 = consts.zero1

    max_commit_slots = 4
    for slot in range(max_commit_slots):
        fire = consts.zero1
        pc = consts.zero64
        rob_idx = c(0, width=p.rob_w)
        uop_uid = consts.zero64
        parent_uid = consts.zero64
        template_kind = c(0, width=3)
        op = c(0, width=12)
        val = consts.zero64
        ln = consts.zero3
        insn_raw = consts.zero64
        wb_valid = consts.zero1
        wb_rd = c(0, width=6)
        wb_data = consts.zero64
        src0_valid = consts.zero1
        src0_reg = c(0, width=6)
        src0_data = consts.zero64
        src1_valid = consts.zero1
        src1_reg = c(0, width=6)
        src1_data = consts.zero64
        dst_valid = consts.zero1
        dst_reg = c(0, width=6)
        dst_data = consts.zero64
        mem_valid = consts.zero1
        mem_is_store = consts.zero1
        mem_addr = consts.zero64
        mem_wdata = consts.zero64
        mem_rdata = consts.zero64
        mem_size = consts.zero4
        trap_valid = consts.zero1
        trap_cause = c(0, width=32)
        next_pc = consts.zero64
        checkpoint_id = c(0, width=6)
        if slot < p.commit_w:
            fire_raw = commit_fires[slot]
            pc = rob_pcs[slot]
            rob_idx = commit_idxs[slot]
            op = rob_ops[slot]
            val = rob_values[slot]
            ln = rob_lens[slot]
            insn_raw = rob_insn_raws[slot]
            uop_uid = rob_uop_uids[slot]
            parent_uid = rob_parent_uids[slot]
            is_macro_commit = op_is(op, OP_FENTRY, OP_FEXIT, OP_FRET_RA, OP_FRET_STK)
            fire = fire_raw & (~is_macro_commit)
            is_gpr_dst = rob_dst_kinds[slot].__eq__(c(1, width=2))
            wb_trace_suppress = op_is(
                op,
                OP_C_BSTART_STD,
                OP_C_BSTART_COND,
                OP_C_BSTART_DIRECT,
                OP_BSTART_STD_FALL,
                OP_BSTART_STD_DIRECT,
                OP_BSTART_STD_COND,
                OP_BSTART_STD_CALL,
                OP_C_BSTOP,
            )
            rd = rob_dst_aregs[slot]
            wb_valid = fire & is_gpr_dst & (~rd.__eq__(c(0, width=6))) & (~wb_trace_suppress)
            wb_rd = rd
            wb_data = rob_values[slot]
            src0_valid = fire & rob_src0_valids[slot]
            src0_reg = rob_src0_regs[slot]
            src0_data = rob_src0_values[slot]
            src1_valid = fire & rob_src1_valids[slot]
            src1_reg = rob_src1_regs[slot]
            src1_data = rob_src1_values[slot]
            dst_valid = wb_valid
            dst_reg = wb_rd
            dst_data = wb_data
            next_pc_slot = commit_next_pcs[slot]
            is_store = rob_is_stores[slot]
            is_load = rob_is_loads[slot]
            ld_trace_data = rob_ld_datas[slot]
            for i in range(p.sq_entries):
                st_hit = stbuf_valid[i].out() & stbuf_addr[i].out().__eq__(rob_ld_addrs[slot])
                ld_trace_data = st_hit._select_internal(stbuf_data[i].out(), ld_trace_data)
            mem_valid = fire & (is_store | is_load)
            mem_is_store = fire & is_store
            mem_addr = is_store._select_internal(rob_st_addrs[slot], rob_ld_addrs[slot])
            mem_wdata = is_store._select_internal(rob_st_datas[slot], consts.zero64)
            mem_rdata = is_load._select_internal(ld_trace_data, consts.zero64)
            mem_size = is_store._select_internal(rob_st_sizes[slot], rob_ld_sizes[slot])
            next_pc = next_pc_slot
            checkpoint_id = rob_checkpoint_ids[slot]
            trap_hit = state.trap_pending.out() & commit_idxs[slot].__eq__(state.trap_rob.out())
            trap_valid = fire & trap_hit
            trap_cause = trap_hit._select_internal(state.trap_cause.out(), trap_cause)

        # When `shadow_boundary_fire` is active, shift real retire records up
        # by one slot so slot0 can carry the synthetic boundary marker event.
        if slot > 0 and (slot - 1) < p.commit_w:
            prev = slot - 1
            fire_prev_raw = commit_fires[prev]
            pc_prev = commit_pcs[prev]
            rob_prev = commit_idxs[prev]
            op_prev = rob_ops[prev]
            val_prev = rob_values[prev]
            ln_prev = rob_lens[prev]
            insn_prev = rob_insn_raws[prev]
            uid_prev = rob_uop_uids[prev]
            parent_prev = rob_parent_uids[prev]
            is_macro_prev = op_is(op_prev, OP_FENTRY, OP_FEXIT, OP_FRET_RA, OP_FRET_STK)
            fire_prev = fire_prev_raw & (~is_macro_prev)
            is_gpr_prev = rob_dst_kinds[prev].__eq__(c(1, width=2))
            wb_suppress_prev = op_is(
                op_prev,
                OP_C_BSTART_STD,
                OP_C_BSTART_COND,
                OP_C_BSTART_DIRECT,
                OP_BSTART_STD_FALL,
                OP_BSTART_STD_DIRECT,
                OP_BSTART_STD_COND,
                OP_BSTART_STD_CALL,
                OP_C_BSTOP,
            )
            rd_prev = rob_dst_aregs[prev]
            wb_valid_prev = fire_prev & is_gpr_prev & (~rd_prev.__eq__(c(0, width=6))) & (~wb_suppress_prev)
            wb_rd_prev = rd_prev
            wb_data_prev = rob_values[prev]
            src0_valid_prev = fire_prev & rob_src0_valids[prev]
            src0_reg_prev = rob_src0_regs[prev]
            src0_data_prev = rob_src0_values[prev]
            src1_valid_prev = fire_prev & rob_src1_valids[prev]
            src1_reg_prev = rob_src1_regs[prev]
            src1_data_prev = rob_src1_values[prev]
            dst_valid_prev = wb_valid_prev
            dst_reg_prev = wb_rd_prev
            dst_data_prev = wb_data_prev
            next_pc_prev = commit_next_pcs[prev]
            is_store_prev = rob_is_stores[prev]
            is_load_prev = rob_is_loads[prev]
            ld_trace_prev = rob_ld_datas[prev]
            for i in range(p.sq_entries):
                st_hit_prev = stbuf_valid[i].out() & stbuf_addr[i].out().__eq__(rob_ld_addrs[prev])
                ld_trace_prev = st_hit_prev._select_internal(stbuf_data[i].out(), ld_trace_prev)
            mem_valid_prev = fire_prev & (is_store_prev | is_load_prev)
            mem_is_store_prev = fire_prev & is_store_prev
            mem_addr_prev = is_store_prev._select_internal(rob_st_addrs[prev], rob_ld_addrs[prev])
            mem_wdata_prev = is_store_prev._select_internal(rob_st_datas[prev], consts.zero64)
            mem_rdata_prev = is_load_prev._select_internal(ld_trace_prev, consts.zero64)
            mem_size_prev = is_store_prev._select_internal(rob_st_sizes[prev], rob_ld_sizes[prev])
            checkpoint_prev = rob_checkpoint_ids[prev]
            shift_active = shadow_boundary_fire
            if slot > 1:
                shift_active = shift_active | shadow_boundary_fire1

            fire = shift_active._select_internal(fire_prev, fire)
            pc = shift_active._select_internal(pc_prev, pc)
            rob_idx = shift_active._select_internal(rob_prev, rob_idx)
            op = shift_active._select_internal(op_prev, op)
            val = shift_active._select_internal(val_prev, val)
            ln = shift_active._select_internal(ln_prev, ln)
            insn_raw = shift_active._select_internal(insn_prev, insn_raw)
            uop_uid = shift_active._select_internal(uid_prev, uop_uid)
            parent_uid = shift_active._select_internal(parent_prev, parent_uid)
            wb_valid = shift_active._select_internal(wb_valid_prev, wb_valid)
            wb_rd = shift_active._select_internal(wb_rd_prev, wb_rd)
            wb_data = shift_active._select_internal(wb_data_prev, wb_data)
            src0_valid = shift_active._select_internal(src0_valid_prev, src0_valid)
            src0_reg = shift_active._select_internal(src0_reg_prev, src0_reg)
            src0_data = shift_active._select_internal(src0_data_prev, src0_data)
            src1_valid = shift_active._select_internal(src1_valid_prev, src1_valid)
            src1_reg = shift_active._select_internal(src1_reg_prev, src1_reg)
            src1_data = shift_active._select_internal(src1_data_prev, src1_data)
            dst_valid = shift_active._select_internal(dst_valid_prev, dst_valid)
            dst_reg = shift_active._select_internal(dst_reg_prev, dst_reg)
            dst_data = shift_active._select_internal(dst_data_prev, dst_data)
            mem_valid = shift_active._select_internal(mem_valid_prev, mem_valid)
            mem_is_store = shift_active._select_internal(mem_is_store_prev, mem_is_store)
            mem_addr = shift_active._select_internal(mem_addr_prev, mem_addr)
            mem_wdata = shift_active._select_internal(mem_wdata_prev, mem_wdata)
            mem_rdata = shift_active._select_internal(mem_rdata_prev, mem_rdata)
            mem_size = shift_active._select_internal(mem_size_prev, mem_size)
            next_pc = shift_active._select_internal(next_pc_prev, next_pc)
            checkpoint_id = shift_active._select_internal(checkpoint_prev, checkpoint_id)

        if slot == 0:
            fire = shadow_boundary_fire._select_internal(consts.one1, fire)
            pc = shadow_boundary_fire._select_internal(commit_pcs[0], pc)
            rob_idx = shadow_boundary_fire._select_internal(commit_idxs[0], rob_idx)
            op = shadow_boundary_fire._select_internal(rob_ops[0], op)
            val = shadow_boundary_fire._select_internal(rob_values[0], val)
            ln = shadow_boundary_fire._select_internal(rob_lens[0], ln)
            insn_raw = shadow_boundary_fire._select_internal(rob_insn_raws[0], insn_raw)
            uop_uid = shadow_boundary_fire._select_internal(rob_uop_uids[0], uop_uid)
            parent_uid = shadow_boundary_fire._select_internal(rob_parent_uids[0], parent_uid)
            wb_valid = shadow_boundary_fire._select_internal(consts.zero1, wb_valid)
            wb_rd = shadow_boundary_fire._select_internal(c(0, width=6), wb_rd)
            wb_data = shadow_boundary_fire._select_internal(consts.zero64, wb_data)
            src0_valid = shadow_boundary_fire._select_internal(consts.zero1, src0_valid)
            src0_reg = shadow_boundary_fire._select_internal(c(0, width=6), src0_reg)
            src0_data = shadow_boundary_fire._select_internal(consts.zero64, src0_data)
            src1_valid = shadow_boundary_fire._select_internal(consts.zero1, src1_valid)
            src1_reg = shadow_boundary_fire._select_internal(c(0, width=6), src1_reg)
            src1_data = shadow_boundary_fire._select_internal(consts.zero64, src1_data)
            dst_valid = shadow_boundary_fire._select_internal(consts.zero1, dst_valid)
            dst_reg = shadow_boundary_fire._select_internal(c(0, width=6), dst_reg)
            dst_data = shadow_boundary_fire._select_internal(consts.zero64, dst_data)
            mem_valid = shadow_boundary_fire._select_internal(consts.zero1, mem_valid)
            mem_is_store = shadow_boundary_fire._select_internal(consts.zero1, mem_is_store)
            mem_addr = shadow_boundary_fire._select_internal(consts.zero64, mem_addr)
            mem_wdata = shadow_boundary_fire._select_internal(consts.zero64, mem_wdata)
            mem_rdata = shadow_boundary_fire._select_internal(consts.zero64, mem_rdata)
            mem_size = shadow_boundary_fire._select_internal(consts.zero4, mem_size)
            next_pc = shadow_boundary_fire._select_internal(commit_pcs[0], next_pc)
            checkpoint_id = shadow_boundary_fire._select_internal(rob_checkpoint_ids[0], checkpoint_id)

            fire = macro_trace_fire._select_internal(consts.one1, fire)
            pc = macro_trace_fire._select_internal(macro_trace_pc, pc)
            rob_idx = macro_trace_fire._select_internal(macro_trace_rob, rob_idx)
            op = macro_trace_fire._select_internal(macro_trace_op, op)
            val = macro_trace_fire._select_internal(macro_trace_val, val)
            ln = macro_trace_fire._select_internal(macro_trace_len, ln)
            insn_raw = macro_trace_fire._select_internal(macro_trace_insn, insn_raw)
            uop_uid = macro_trace_fire._select_internal(macro_uop_uid, uop_uid)
            parent_uid = macro_trace_fire._select_internal(macro_uop_parent_uid, parent_uid)
            template_kind = macro_trace_fire._select_internal(macro_uop_template_kind, template_kind)
            wb_valid = macro_trace_fire._select_internal(macro_trace_wb_valid, wb_valid)
            wb_rd = macro_trace_fire._select_internal(macro_trace_wb_rd, wb_rd)
            wb_data = macro_trace_fire._select_internal(macro_trace_wb_data, wb_data)
            src0_valid = macro_trace_fire._select_internal(macro_trace_src0_valid, src0_valid)
            src0_reg = macro_trace_fire._select_internal(macro_trace_src0_reg, src0_reg)
            src0_data = macro_trace_fire._select_internal(macro_trace_src0_data, src0_data)
            src1_valid = macro_trace_fire._select_internal(macro_trace_src1_valid, src1_valid)
            src1_reg = macro_trace_fire._select_internal(macro_trace_src1_reg, src1_reg)
            src1_data = macro_trace_fire._select_internal(macro_trace_src1_data, src1_data)
            dst_valid = macro_trace_fire._select_internal(macro_trace_dst_valid, dst_valid)
            dst_reg = macro_trace_fire._select_internal(macro_trace_dst_reg, dst_reg)
            dst_data = macro_trace_fire._select_internal(macro_trace_dst_data, dst_data)
            mem_valid = macro_trace_fire._select_internal(macro_trace_mem_valid, mem_valid)
            mem_is_store = macro_trace_fire._select_internal(macro_trace_mem_is_store, mem_is_store)
            mem_addr = macro_trace_fire._select_internal(macro_trace_mem_addr, mem_addr)
            mem_wdata = macro_trace_fire._select_internal(macro_trace_mem_wdata, mem_wdata)
            mem_rdata = macro_trace_fire._select_internal(macro_trace_mem_rdata, mem_rdata)
            mem_size = macro_trace_fire._select_internal(macro_trace_mem_size, mem_size)
            next_pc = macro_trace_fire._select_internal(macro_trace_next_pc, next_pc)
            uop_uid = macro_shadow_fire._select_internal(macro_shadow_uid, uop_uid)
            wb_valid = macro_shadow_fire._select_internal(consts.zero1, wb_valid)
            wb_rd = macro_shadow_fire._select_internal(c(0, width=6), wb_rd)
            wb_data = macro_shadow_fire._select_internal(consts.zero64, wb_data)
            src0_valid = macro_shadow_fire._select_internal(consts.zero1, src0_valid)
            src0_reg = macro_shadow_fire._select_internal(c(0, width=6), src0_reg)
            src0_data = macro_shadow_fire._select_internal(consts.zero64, src0_data)
            src1_valid = macro_shadow_fire._select_internal(consts.zero1, src1_valid)
            src1_reg = macro_shadow_fire._select_internal(c(0, width=6), src1_reg)
            src1_data = macro_shadow_fire._select_internal(consts.zero64, src1_data)
            dst_valid = macro_shadow_fire._select_internal(consts.zero1, dst_valid)
            dst_reg = macro_shadow_fire._select_internal(c(0, width=6), dst_reg)
            dst_data = macro_shadow_fire._select_internal(consts.zero64, dst_data)
            mem_valid = macro_shadow_fire._select_internal(consts.zero1, mem_valid)
            mem_is_store = macro_shadow_fire._select_internal(consts.zero1, mem_is_store)
            mem_addr = macro_shadow_fire._select_internal(consts.zero64, mem_addr)
            mem_wdata = macro_shadow_fire._select_internal(consts.zero64, mem_wdata)
            mem_rdata = macro_shadow_fire._select_internal(consts.zero64, mem_rdata)
            mem_size = macro_shadow_fire._select_internal(consts.zero4, mem_size)
            next_pc = macro_shadow_fire._select_internal(macro_trace_pc, next_pc)
        else:
            if slot == 1 and p.commit_w > 1:
                fire = shadow_boundary_fire1._select_internal(consts.one1, fire)
                pc = shadow_boundary_fire1._select_internal(commit_pcs[1], pc)
                rob_idx = shadow_boundary_fire1._select_internal(commit_idxs[1], rob_idx)
                op = shadow_boundary_fire1._select_internal(rob_ops[1], op)
                val = shadow_boundary_fire1._select_internal(rob_values[1], val)
                ln = shadow_boundary_fire1._select_internal(rob_lens[1], ln)
                insn_raw = shadow_boundary_fire1._select_internal(rob_insn_raws[1], insn_raw)
                uop_uid = shadow_boundary_fire1._select_internal(rob_uop_uids[1], uop_uid)
                parent_uid = shadow_boundary_fire1._select_internal(rob_parent_uids[1], parent_uid)
                wb_valid = shadow_boundary_fire1._select_internal(consts.zero1, wb_valid)
                wb_rd = shadow_boundary_fire1._select_internal(c(0, width=6), wb_rd)
                wb_data = shadow_boundary_fire1._select_internal(consts.zero64, wb_data)
                src0_valid = shadow_boundary_fire1._select_internal(consts.zero1, src0_valid)
                src0_reg = shadow_boundary_fire1._select_internal(c(0, width=6), src0_reg)
                src0_data = shadow_boundary_fire1._select_internal(consts.zero64, src0_data)
                src1_valid = shadow_boundary_fire1._select_internal(consts.zero1, src1_valid)
                src1_reg = shadow_boundary_fire1._select_internal(c(0, width=6), src1_reg)
                src1_data = shadow_boundary_fire1._select_internal(consts.zero64, src1_data)
                dst_valid = shadow_boundary_fire1._select_internal(consts.zero1, dst_valid)
                dst_reg = shadow_boundary_fire1._select_internal(c(0, width=6), dst_reg)
                dst_data = shadow_boundary_fire1._select_internal(consts.zero64, dst_data)
                mem_valid = shadow_boundary_fire1._select_internal(consts.zero1, mem_valid)
                mem_is_store = shadow_boundary_fire1._select_internal(consts.zero1, mem_is_store)
                mem_addr = shadow_boundary_fire1._select_internal(consts.zero64, mem_addr)
                mem_wdata = shadow_boundary_fire1._select_internal(consts.zero64, mem_wdata)
                mem_rdata = shadow_boundary_fire1._select_internal(consts.zero64, mem_rdata)
                mem_size = shadow_boundary_fire1._select_internal(consts.zero4, mem_size)
                next_pc = shadow_boundary_fire1._select_internal(commit_pcs[1], next_pc)
                checkpoint_id = shadow_boundary_fire1._select_internal(rob_checkpoint_ids[1], checkpoint_id)
            if slot == 1:
                fire = macro_shadow_fire._select_internal(consts.one1, fire)
                pc = macro_shadow_fire._select_internal(macro_trace_pc, pc)
                rob_idx = macro_shadow_fire._select_internal(macro_trace_rob, rob_idx)
                op = macro_shadow_fire._select_internal(macro_trace_op, op)
                val = macro_shadow_fire._select_internal(macro_trace_val, val)
                ln = macro_shadow_fire._select_internal(macro_trace_len, ln)
                insn_raw = macro_shadow_fire._select_internal(macro_trace_insn, insn_raw)
                uop_uid = macro_shadow_fire._select_internal(macro_shadow_uid_alt, uop_uid)
                parent_uid = macro_shadow_fire._select_internal(macro_uop_parent_uid, parent_uid)
                template_kind = macro_shadow_fire._select_internal(macro_uop_template_kind, template_kind)
                wb_valid = macro_shadow_fire._select_internal(macro_trace_wb_valid, wb_valid)
                wb_rd = macro_shadow_fire._select_internal(macro_trace_wb_rd, wb_rd)
                wb_data = macro_shadow_fire._select_internal(macro_trace_wb_data, wb_data)
                src0_valid = macro_shadow_fire._select_internal(macro_trace_src0_valid, src0_valid)
                src0_reg = macro_shadow_fire._select_internal(macro_trace_src0_reg, src0_reg)
                src0_data = macro_shadow_fire._select_internal(macro_trace_src0_data, src0_data)
                src1_valid = macro_shadow_fire._select_internal(macro_trace_src1_valid, src1_valid)
                src1_reg = macro_shadow_fire._select_internal(macro_trace_src1_reg, src1_reg)
                src1_data = macro_shadow_fire._select_internal(macro_trace_src1_data, src1_data)
                dst_valid = macro_shadow_fire._select_internal(macro_trace_dst_valid, dst_valid)
                dst_reg = macro_shadow_fire._select_internal(macro_trace_dst_reg, dst_reg)
                dst_data = macro_shadow_fire._select_internal(macro_trace_dst_data, dst_data)
                mem_valid = macro_shadow_fire._select_internal(macro_trace_mem_valid, mem_valid)
                mem_is_store = macro_shadow_fire._select_internal(macro_trace_mem_is_store, mem_is_store)
                mem_addr = macro_shadow_fire._select_internal(macro_trace_mem_addr, mem_addr)
                mem_wdata = macro_shadow_fire._select_internal(macro_trace_mem_wdata, mem_wdata)
                mem_rdata = macro_shadow_fire._select_internal(macro_trace_mem_rdata, mem_rdata)
                mem_size = macro_shadow_fire._select_internal(macro_trace_mem_size, mem_size)
                next_pc = macro_shadow_fire._select_internal(macro_trace_next_pc, next_pc)
            macro_slot_keep = consts.zero1
            if slot == 0:
                macro_slot_keep = consts.one1
            if slot == 1:
                macro_slot_keep = macro_shadow_fire
            macro_kill = macro_trace_fire & (~macro_slot_keep)
            fire = macro_kill._select_internal(consts.zero1, fire)
            pc = macro_kill._select_internal(consts.zero64, pc)
            rob_idx = macro_kill._select_internal(c(0, width=p.rob_w), rob_idx)
            op = macro_kill._select_internal(c(0, width=12), op)
            val = macro_kill._select_internal(consts.zero64, val)
            ln = macro_kill._select_internal(consts.zero3, ln)
            insn_raw = macro_kill._select_internal(consts.zero64, insn_raw)
            uop_uid = macro_kill._select_internal(consts.zero64, uop_uid)
            parent_uid = macro_kill._select_internal(consts.zero64, parent_uid)
            template_kind = macro_kill._select_internal(c(0, width=3), template_kind)
            wb_valid = macro_kill._select_internal(consts.zero1, wb_valid)
            wb_rd = macro_kill._select_internal(c(0, width=6), wb_rd)
            wb_data = macro_kill._select_internal(consts.zero64, wb_data)
            src0_valid = macro_kill._select_internal(consts.zero1, src0_valid)
            src0_reg = macro_kill._select_internal(c(0, width=6), src0_reg)
            src0_data = macro_kill._select_internal(consts.zero64, src0_data)
            src1_valid = macro_kill._select_internal(consts.zero1, src1_valid)
            src1_reg = macro_kill._select_internal(c(0, width=6), src1_reg)
            src1_data = macro_kill._select_internal(consts.zero64, src1_data)
            dst_valid = macro_kill._select_internal(consts.zero1, dst_valid)
            dst_reg = macro_kill._select_internal(c(0, width=6), dst_reg)
            dst_data = macro_kill._select_internal(consts.zero64, dst_data)
            mem_valid = macro_kill._select_internal(consts.zero1, mem_valid)
            mem_is_store = macro_kill._select_internal(consts.zero1, mem_is_store)
            mem_addr = macro_kill._select_internal(consts.zero64, mem_addr)
            mem_wdata = macro_kill._select_internal(consts.zero64, mem_wdata)
            mem_rdata = macro_kill._select_internal(consts.zero64, mem_rdata)
            mem_size = macro_kill._select_internal(consts.zero4, mem_size)
            next_pc = macro_kill._select_internal(consts.zero64, next_pc)
            checkpoint_id = macro_kill._select_internal(c(0, width=6), checkpoint_id)
        m.output(f"commit_fire{slot}", fire)
        m.output(f"commit_pc{slot}", pc)
        m.output(f"commit_rob{slot}", rob_idx)
        m.output(f"commit_op{slot}", op)
        m.output(f"commit_uop_uid{slot}", uop_uid)
        m.output(f"commit_parent_uop_uid{slot}", parent_uid)
        m.output(f"commit_block_uid{slot}", commit_block_uids[slot])
        m.output(f"commit_block_bid{slot}", commit_block_bids[slot])
        m.output(f"commit_core_id{slot}", commit_core_ids[slot])
        m.output(f"commit_is_bstart{slot}", commit_is_bstarts[slot])
        m.output(f"commit_is_bstop{slot}", commit_is_bstops[slot])
        m.output(f"commit_load_store_id{slot}", rob_load_store_ids[slot])
        m.output(f"commit_template_kind{slot}", template_kind)
        m.output(f"commit_value{slot}", val)
        m.output(f"commit_len{slot}", ln)
        m.output(f"commit_insn_raw{slot}", insn_raw)
        m.output(f"commit_wb_valid{slot}", wb_valid)
        m.output(f"commit_wb_rd{slot}", wb_rd)
        m.output(f"commit_wb_data{slot}", wb_data)
        m.output(f"commit_src0_valid{slot}", src0_valid)
        m.output(f"commit_src0_reg{slot}", src0_reg)
        m.output(f"commit_src0_data{slot}", src0_data)
        m.output(f"commit_src1_valid{slot}", src1_valid)
        m.output(f"commit_src1_reg{slot}", src1_reg)
        m.output(f"commit_src1_data{slot}", src1_data)
        m.output(f"commit_dst_valid{slot}", dst_valid)
        m.output(f"commit_dst_reg{slot}", dst_reg)
        m.output(f"commit_dst_data{slot}", dst_data)
        m.output(f"commit_mem_valid{slot}", mem_valid)
        m.output(f"commit_mem_is_store{slot}", mem_is_store)
        m.output(f"commit_mem_addr{slot}", mem_addr)
        m.output(f"commit_mem_wdata{slot}", mem_wdata)
        m.output(f"commit_mem_rdata{slot}", mem_rdata)
        m.output(f"commit_mem_size{slot}", mem_size)
        m.output(f"commit_trap_valid{slot}", trap_valid)
        m.output(f"commit_trap_cause{slot}", trap_cause)
        m.output(f"commit_next_pc{slot}", next_pc)
        m.output(f"commit_checkpoint_id{slot}", checkpoint_id)
    m.output("rob_count", rob_count)

    m.output("issue_fire", issue_fire)
    m.output("dmem_raddr", dmem_raddr)
    m.output("dmem_wvalid", mem_wvalid)
    m.output("dmem_waddr", mem_waddr)
    m.output("dmem_wdata", dmem_wdata)
    m.output("dmem_wstrb", wstrb)
    m.output("dmem_wsrc", dmem_wsrc)
    m.output("frontend_ready", frontend_ready)
    m.output("redirect_valid", commit_redirect_any)
    m.output("redirect_pc", redirect_pc_any)
    m.output("redirect_bid", redirect_bid_any)
    m.output("ctu_block_ifu", macro_block)
    m.output("ctu_uop_valid", macro_uop_valid)
    m.output("bru_fault_set_dbg", bru_fault_set)

    # Deadlock diagnostics: locate the IQ entry currently backing ROB head.
    head_wait_hit = (
        iq_lsu_bank["head_wait_hit_o"]
        | iq_bru_bank["head_wait_hit_o"]
        | iq_alu_bank["head_wait_hit_o"]
        | iq_cmd_bank["head_wait_hit_o"]
    )
    head_wait_kind = c(0, width=2)
    head_wait_sl = c(0, width=p.ptag_w)
    head_wait_sr = c(0, width=p.ptag_w)
    head_wait_sp = c(0, width=p.ptag_w)
    head_wait_kind = iq_lsu_bank["head_wait_hit_o"]._select_internal(c(3, width=2), head_wait_kind)
    head_wait_kind = iq_bru_bank["head_wait_hit_o"]._select_internal(c(2, width=2), head_wait_kind)
    head_wait_kind = iq_alu_bank["head_wait_hit_o"]._select_internal(c(1, width=2), head_wait_kind)
    head_wait_kind = iq_cmd_bank["head_wait_hit_o"]._select_internal(c(0, width=2), head_wait_kind)
    head_wait_sl = iq_lsu_bank["head_wait_hit_o"]._select_internal(iq_lsu_bank["head_wait_sl_o"], head_wait_sl)
    head_wait_sr = iq_lsu_bank["head_wait_hit_o"]._select_internal(iq_lsu_bank["head_wait_sr_o"], head_wait_sr)
    head_wait_sp = iq_lsu_bank["head_wait_hit_o"]._select_internal(iq_lsu_bank["head_wait_sp_o"], head_wait_sp)
    head_wait_sl = iq_bru_bank["head_wait_hit_o"]._select_internal(iq_bru_bank["head_wait_sl_o"], head_wait_sl)
    head_wait_sr = iq_bru_bank["head_wait_hit_o"]._select_internal(iq_bru_bank["head_wait_sr_o"], head_wait_sr)
    head_wait_sp = iq_bru_bank["head_wait_hit_o"]._select_internal(iq_bru_bank["head_wait_sp_o"], head_wait_sp)
    head_wait_sl = iq_alu_bank["head_wait_hit_o"]._select_internal(iq_alu_bank["head_wait_sl_o"], head_wait_sl)
    head_wait_sr = iq_alu_bank["head_wait_hit_o"]._select_internal(iq_alu_bank["head_wait_sr_o"], head_wait_sr)
    head_wait_sp = iq_alu_bank["head_wait_hit_o"]._select_internal(iq_alu_bank["head_wait_sp_o"], head_wait_sp)
    head_wait_sl = iq_cmd_bank["head_wait_hit_o"]._select_internal(iq_cmd_bank["head_wait_sl_o"], head_wait_sl)
    head_wait_sr = iq_cmd_bank["head_wait_hit_o"]._select_internal(iq_cmd_bank["head_wait_sr_o"], head_wait_sr)
    head_wait_sp = iq_cmd_bank["head_wait_hit_o"]._select_internal(iq_cmd_bank["head_wait_sp_o"], head_wait_sp)
    # Debug taps for scheduler/LSU replay bring-up.
    wakeup_reason = compose_wakeup_reason(
        m,
        p=p,
        wb_fire_has_dsts=wb_fire_has_dsts,
        lsu_forward_active=lsu_forward_active,
        commit_redirect=commit_redirect_any,
        dispatch_fire=dispatch_fire,
    )
    replay_cause = compose_replay_cause(
        m,
        lsu_block_lane0=lsu_block_lane0,
        issued_is_load=issued_is_load,
        older_store_pending=older_store_pending,
        lsu_violation_detected=lsu_violation_detected,
        replay_redirect_fire=replay_redirect_fire,
    )
    m.output("wakeup_reason", wakeup_reason)
    m.output("replay_cause", replay_cause)
    m.output("dispatch_fire", dispatch_fire)

    frontend_occ_args = {}
    for stage in ("d1", "d2", "d3", "s1", "s2"):
        for slot in range(4):
            valid = consts.zero1
            pc = consts.zero64
            uid = consts.zero64
            stall = consts.zero1
            stall_cause = c(0, width=8)
            if slot < p.dispatch_w:
                valid = dispatch_frontend[f"stage_{stage}_valid{slot}"]
                pc = dispatch_frontend[f"stage_{stage}_pc{slot}"]
                uid = dispatch_frontend[f"stage_{stage}_uop_uid{slot}"]
                stall = dispatch_frontend[f"stage_{stage}_stall{slot}"]
                stall_cause = dispatch_frontend[f"stage_{stage}_stall_cause{slot}"]
            frontend_occ_args[f"{stage}_valid{slot}"] = valid
            frontend_occ_args[f"{stage}_uid{slot}"] = uid
            frontend_occ_args[f"{stage}_pc{slot}"] = pc
            frontend_occ_args[f"{stage}_stall{slot}"] = stall
            frontend_occ_args[f"{stage}_stall_cause{slot}"] = stall_cause
    m.instance_auto(
        build_backend_occ_frontend,
        name="backend_occ_frontend",
        module_name="LinxCoreBackendOccFrontend",
        **frontend_occ_args,
    )

    # Canonical IQ residency visibility: expose up to 4 resident uops
    # (LSU/BRU/ALU/CMD queues) for DFX pipeview IQ-stage tracking.
    iq_slot_views = [iq_lsu_bank, iq_bru_bank, iq_alu_bank, iq_cmd_bank]
    schedule_occ_args = {}
    for slot, iq_view in enumerate(iq_slot_views):
        iq_valid = iq_view["resident_valid_o"]
        iq_pc = iq_view["resident_pc_o"]
        iq_rob = iq_view["resident_rob_o"]
        m.assign(rob_meta_query_idxs[rob_iq_query_base + slot], iq_rob)
        iq_uid = rob_iq_query_uop_uids[slot]
        iq_parent_uid = rob_iq_query_parent_uids[slot]
        iq_block_uid = rob_iq_query_block_uids[slot]
        schedule_occ_args[f"iq_valid{slot}"] = iq_valid
        schedule_occ_args[f"iq_uid{slot}"] = iq_uid
        schedule_occ_args[f"iq_pc{slot}"] = iq_pc
        schedule_occ_args[f"iq_rob{slot}"] = iq_rob
        schedule_occ_args[f"iq_parent{slot}"] = iq_parent_uid
        schedule_occ_args[f"iq_block{slot}"] = iq_block_uid
    m.instance_auto(
        build_backend_occ_schedule,
        name="backend_occ_schedule",
        module_name="LinxCoreBackendOccSchedule",
        **schedule_occ_args,
    )

    # MMIO visibility for testbenches (UART + exit).
    m.output("mmio_uart_valid", mmio_uart)
    m.output("mmio_uart_data", mmio_uart_data)
    m.output("mmio_exit_valid", mmio_exit)
    m.output("mmio_exit_code", mmio_exit_code)

    # Block command export for Janus BCtrl/TMU/PE path.
    # Commands are produced by the CMD issue pipe and retire only when BISQ
    # enqueue succeeds (`cmd_issue_fire_eff`).
    cmd_op = cmd_uop_op
    cmd_kind = c(0, width=3)
    cmd_kind = cmd_op.__eq__(c(OP_BIOR, width=12))._select_internal(c(1, width=3), cmd_kind)
    cmd_kind = cmd_op.__eq__(c(OP_BLOAD, width=12))._select_internal(c(2, width=3), cmd_kind)
    cmd_kind = cmd_op.__eq__(c(OP_BSTORE, width=12))._select_internal(c(3, width=3), cmd_kind)
    cmd_payload = cmd_payload_lane
    cmd_tile = cmd_payload._trunc(width=6)
    cmd_src_rob = cmd_uop_rob
    cmd_bid = rob_issue_query_block_bids[cmd_slot]
    block_fabric = m.instance_auto(
        build_block_fabric,
        name="block_fabric",
        module_name="LinxCoreBlockFabricBackend",
        clk=clk,
        rst=rst,
        blk_bisq_enq_ready=bisq_enq_ready_i,
        blk_brob_active_allocated=brob_active_allocated_i,
        blk_brob_active_ready=brob_active_ready_i,
        blk_brob_active_exception=brob_active_exception_i,
        blk_brob_active_retired=brob_active_retired_i,
        blk_template_uid=template_uid_i,
        cmd_valid_i=cmd_issue_fire_eff,
        cmd_bid_i=cmd_bid,
    )
    # Block command tags must route by BID identity.
    cmd_tag = block_fabric["cmd_tag_o"]

    block_cmd_valid = cmd_issue_fire_eff
    block_cmd_kind = cmd_kind
    block_cmd_payload = cmd_payload
    block_cmd_tile = cmd_tile
    block_cmd_bid = block_fabric["cmd_bid_o"]
    block_cmd_tag = cmd_tag
    m.output("cmd_to_bisq_stage_cmd_valid", block_cmd_valid)
    m.output("cmd_to_bisq_stage_cmd_kind", block_cmd_kind)
    m.output("cmd_to_bisq_stage_cmd_bid", block_cmd_bid)
    m.output("cmd_to_bisq_stage_cmd_payload", block_cmd_payload)
    m.output("cmd_to_bisq_stage_cmd_tile", block_cmd_tile)
    m.output("cmd_to_bisq_stage_cmd_src_rob", cmd_src_rob)

    m.output("block_cmd_valid", block_cmd_valid)
    m.output("active_block_bid", state.active_block_bid.out())
    m.output("brob_alloc_fire", brob_alloc_fire)
    m.output("brob_retire_fire", brob_retire_fire)
    m.output("brob_retire_bid", brob_retire_bid)

    return None


@module(name="LinxCoreTraceExport")
def build_trace_export(m: Circuit, *, mem_bytes: int = (1 << 20)) -> None:
    _build_trace_export_core(m, mem_bytes=mem_bytes, params=None)
