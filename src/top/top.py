from __future__ import annotations

from pycircuit import Circuit, module

from common.uid_allocator import build_uid_allocator
from bcc.backend.backend import build_backend
from mem.mem2r1w import build_mem2r1w
from bcc.bctrl.bctrl import build_janus_bcc_bctrl
from bcc.bctrl.bisq import build_janus_bcc_bctrl_bisq
from bcc.bctrl.brenu import build_janus_bcc_bctrl_brenu
from bcc.bctrl.brob import build_janus_bcc_bctrl_brob
from bcc.iex.iex import build_janus_bcc_iex
from bcc.ifu.ctrl import build_janus_bcc_ifu_ctrl
from bcc.ifu.f0 import build_janus_bcc_ifu_f0
from bcc.ifu.f1 import build_janus_bcc_ifu_f1
from bcc.ifu.f2 import build_janus_bcc_ifu_f2
from bcc.ifu.f3 import build_janus_bcc_ifu_f3
from bcc.ifu.f4 import build_janus_bcc_ifu_f4
from bcc.ifu.icache import build_janus_bcc_ifu_icache
from bcc.lsu.l1d import build_janus_bcc_lsu_l1d
from bcc.lsu.lhq import build_janus_bcc_lsu_lhq
from bcc.lsu.liq import build_janus_bcc_lsu_liq
from bcc.lsu.mdb import build_janus_bcc_lsu_mdb
from bcc.lsu.scb import build_janus_bcc_lsu_scb
from bcc.lsu.stq import build_janus_bcc_lsu_stq
from bcc.ooo.dec1 import build_janus_bcc_ooo_dec1
from bcc.ooo.dec2 import build_janus_bcc_ooo_dec2
from bcc.ooo.flush_ctrl import build_janus_bcc_ooo_flush_ctrl
from bcc.ooo.pc_buffer import build_janus_bcc_ooo_pc_buffer
from bcc.ooo.ren import build_janus_bcc_ooo_ren
from bcc.ooo.renu import build_janus_bcc_ooo_renu
from bcc.ooo.rob import build_janus_bcc_ooo_rob
from bcc.ooo.s1 import build_janus_bcc_ooo_s1
from bcc.ooo.s2 import build_janus_bcc_ooo_s2
from cube.cube import build_janus_cube
from tau.tau import build_janus_tau
from tma.tma import build_janus_tma
from tmu.noc.node import build_janus_tmu_noc_node
from tmu.sram.tilereg import build_janus_tmu_tilereg
from vec.vec import build_linxcore_vec


@module(name="LinxCoreTop")
def build_linxcore_top(
    m: Circuit,
    *,
    mem_bytes: int = (1 << 20),
    ic_sets: int = 32,
    ic_ways: int = 4,
    ic_line_bytes: int = 64,
    ifetch_bundle_bytes: int = 128,
    ib_depth: int = 8,
    ic_miss_outstanding: int = 1,
    ic_enable: int = 1,
) -> None:
    clk_top = m.clock("clk")
    rst_top = m.reset("rst")

    boot_pc_top = m.input("boot_pc", width=64)
    boot_sp_top = m.input("boot_sp", width=64)
    boot_ra_top = m.input("boot_ra", width=64)

    host_wvalid_top = m.input("host_wvalid", width=1)
    host_waddr_top = m.input("host_waddr", width=64)
    host_wdata_top = m.input("host_wdata", width=64)
    host_wstrb_top = m.input("host_wstrb", width=8)

    ic_l2_req_ready_top = m.input("ic_l2_req_ready", width=1)
    ic_l2_rsp_valid_top = m.input("ic_l2_rsp_valid", width=1)
    ic_l2_rsp_addr_top = m.input("ic_l2_rsp_addr", width=64)
    ic_l2_rsp_data_top = m.input("ic_l2_rsp_data", width=512)
    ic_l2_rsp_error_top = m.input("ic_l2_rsp_error", width=1)

    c = m.const

    dmem_rdata_top = m.new_wire(width=64)
    imem_rdata_top = m.new_wire(width=64)

    flush_valid_fls = m.new_wire(width=1)
    flush_pc_fls = m.new_wire(width=64)
    f2_to_f0_advance_wire_f2 = m.new_wire(width=1)
    f2_to_f0_next_pc_wire_f2 = m.new_wire(width=64)
    f3_to_f2_ready_wire_f3 = m.new_wire(width=1)
    uid_alloc_fetch_count_top = m.new_wire(width=3)
    uid_alloc_template_count_top = m.new_wire(width=2)
    uid_alloc_replay_count_top = m.new_wire(width=2)

    uid_alloc_top = m.instance(
        build_uid_allocator,
        name="uid_allocator",
        module_name="LinxCoreUidAllocatorTop",
        clk=clk_top,
        rst=rst_top,
        fetch_alloc_count_i=uid_alloc_fetch_count_top,
        template_alloc_count_i=uid_alloc_template_count_top,
        replay_alloc_count_i=uid_alloc_replay_count_top,
    )

    # IFU stage chain.
    ifu_f0 = m.instance(
        build_janus_bcc_ifu_f0,
        name="janus_ifu_f0",
        module_name="JanusBccIfuF0Top",
        clk=clk_top,
        rst=rst_top,
        boot_pc_top=boot_pc_top,
        flush_valid_fls=flush_valid_fls,
        flush_pc_fls=flush_pc_fls,
        f2_to_f0_advance_stage_valid_f2=f2_to_f0_advance_wire_f2,
        f2_to_f0_next_pc_stage_pc_f2=f2_to_f0_next_pc_wire_f2,
        uid_alloc_fire_top=uid_alloc_fetch_count_top.ugt(c(0, width=3)),
        uid_alloc_uid_top=uid_alloc_top["fetch_uid_base_o"],
    )

    ifu_f1 = m.instance(
        build_janus_bcc_ifu_f1,
        name="janus_ifu_f1",
        module_name="JanusBccIfuF1Top",
        clk=clk_top,
        rst=rst_top,
        f0_to_f1_stage_pc_f0=ifu_f0["f0_to_f1_stage_pc_f0"],
        f0_to_f1_stage_valid_f0=ifu_f0["f0_to_f1_stage_valid_f0"],
        f0_to_f1_stage_pkt_uid_f0=ifu_f0["f0_to_f1_stage_pkt_uid_f0"],
    )

    ifu_icache = m.instance(
        build_janus_bcc_ifu_icache,
        name="janus_ifu_icache",
        module_name="JanusBccIfuICacheTop",
        params={
            "ic_sets": ic_sets,
            "ic_ways": ic_ways,
            "ic_line_bytes": ic_line_bytes,
            "ifetch_bundle_bytes": ifetch_bundle_bytes,
            "ic_miss_outstanding": ic_miss_outstanding,
            "ic_enable": ic_enable,
        },
        clk=clk_top,
        rst=rst_top,
        f1_to_icache_stage_pc_f1=ifu_f1["f1_to_icache_stage_pc_f1"],
        f1_to_icache_stage_valid_f1=ifu_f1["f1_to_icache_stage_valid_f1"],
        f1_to_icache_stage_pkt_uid_f1=ifu_f1["f1_to_icache_stage_pkt_uid_f1"],
        imem_rdata_top=imem_rdata_top,
        ic_l2_req_ready_top=ic_l2_req_ready_top,
        ic_l2_rsp_valid_top=ic_l2_rsp_valid_top,
        ic_l2_rsp_addr_top=ic_l2_rsp_addr_top,
        ic_l2_rsp_data_top=ic_l2_rsp_data_top,
        ic_l2_rsp_error_top=ic_l2_rsp_error_top,
    )

    m.output("ic_l2_req_valid", ifu_icache["ic_l2_req_valid_top"])
    m.output("ic_l2_req_addr", ifu_icache["ic_l2_req_addr_top"])

    ifu_f2 = m.instance(
        build_janus_bcc_ifu_f2,
        name="janus_ifu_f2",
        module_name="JanusBccIfuF2Top",
        f1_to_f2_stage_pc_f1=ifu_icache["f1_to_f2_stage_pc_f1"],
        f1_to_f2_stage_bundle128_f1=ifu_icache["f1_to_f2_stage_bundle128_f1"],
        f1_to_f2_stage_bundle_base_pc_f1=ifu_icache["f1_to_f2_stage_bundle_base_pc_f1"],
        f1_to_f2_stage_slot_base_offset_f1=ifu_icache["f1_to_f2_stage_slot_base_offset_f1"],
        f1_to_f2_stage_hit_f1=ifu_icache["f1_to_f2_stage_hit_f1"],
        f1_to_f2_stage_miss_f1=ifu_icache["f1_to_f2_stage_miss_f1"],
        f1_to_f2_stage_stall_f1=ifu_icache["f1_to_f2_stage_stall_f1"],
        f1_to_f2_stage_valid_f1=ifu_icache["f1_to_f2_stage_valid_f1"],
        f1_to_f2_stage_pkt_uid_f1=ifu_icache["f1_to_f2_stage_pkt_uid_f1"],
        f3_to_f2_stage_ready_f3=f3_to_f2_ready_wire_f3,
    )

    # Close F2->F0 feedback explicitly.
    m.assign(f2_to_f0_advance_wire_f2, ifu_f2["f2_to_f0_advance_stage_valid_f2"])
    m.assign(f2_to_f0_next_pc_wire_f2, ifu_f2["f2_to_f0_next_pc_stage_pc_f2"])

    ifu_ctrl = m.instance(
        build_janus_bcc_ifu_ctrl,
        name="janus_ifu_ctrl",
        module_name="JanusBccIfuCtrlTop",
        clk=clk_top,
        rst=rst_top,
        f2_to_f3_stage_valid_f2=ifu_f2["f2_to_f3_stage_valid_f2"],
        flush_valid_fls=flush_valid_fls,
    )

    backend_ready_top = m.new_wire(width=1)
    bisq_enq_ready_wire = m.new_wire(width=1)
    brob_active_allocated_wire = m.new_wire(width=1)
    brob_active_ready_wire = m.new_wire(width=1)
    brob_active_exception_wire = m.new_wire(width=1)
    brob_active_retired_wire = m.new_wire(width=1)

    ifu_f3 = m.instance(
        build_janus_bcc_ifu_f3,
        name="janus_ifu_f3",
        module_name="JanusBccIfuF3Top",
        params={"ibuf_depth": ib_depth},
        clk=clk_top,
        rst=rst_top,
        f2_to_f3_stage_pc_f2=ifu_f2["f2_to_f3_stage_pc_f2"],
        f2_to_f3_stage_window_f2=ifu_f2["f2_to_f3_stage_window_f2"],
        f2_to_f3_stage_bundle128_f2=ifu_f2["f2_to_f3_stage_bundle128_f2"],
        f2_to_f3_stage_bundle_base_pc_f2=ifu_f2["f2_to_f3_stage_bundle_base_pc_f2"],
        f2_to_f3_stage_slot_base_offset_f2=ifu_f2["f2_to_f3_stage_slot_base_offset_f2"],
        f2_to_f3_stage_pkt_uid_f2=ifu_f2["f2_to_f3_stage_pkt_uid_f2"],
        f2_to_f3_stage_valid_f2=ifu_f2["f2_to_f3_stage_valid_f2"],
        f2_to_f3_stage_miss_f2=ifu_f2["f2_to_f3_stage_miss_f2"],
        f2_to_f3_stage_stall_f2=ifu_f2["f2_to_f3_stage_stall_f2"],
        ctrl_to_f3_stage_checkpoint_id_f3=ifu_ctrl,
        backend_ready_top=backend_ready_top,
        flush_valid_fls=flush_valid_fls,
    )
    m.assign(f3_to_f2_ready_wire_f3, ifu_f3["f3_ibuf_ready_f3"])

    ifu_f4 = m.instance(
        build_janus_bcc_ifu_f4,
        name="janus_ifu_f4",
        module_name="JanusBccIfuF4Top",
        clk=clk_top,
        rst=rst_top,
        f3_to_f4_stage_pc_f3=ifu_f3["ib_to_f4_stage_pc_ib"],
        f3_to_f4_stage_window_f3=ifu_f3["ib_to_f4_stage_window_ib"],
        f3_to_f4_stage_pkt_uid_f3=ifu_f3["ib_to_f4_stage_pkt_uid_ib"],
        f3_to_f4_stage_valid_f3=ifu_f3["ib_to_f4_stage_valid_ib"],
        f3_to_f4_stage_checkpoint_id_f3=ifu_f3["ib_to_f4_stage_checkpoint_id_ib"],
        flush_valid_fls=flush_valid_fls,
    )

    backend_top = m.instance(
        build_backend,
        name="janus_backend",
        module_name="JanusBccBackendCompat",
        params={"mem_bytes": mem_bytes},
        clk=clk_top,
        rst=rst_top,
        boot_pc=boot_pc_top,
        boot_sp=boot_sp_top,
        boot_ra=boot_ra_top,
        f4_valid_i=ifu_f4["f4_to_d1_stage_valid_f4"],
        f4_pc_i=ifu_f4["f4_to_d1_stage_pc_f4"],
        f4_window_i=ifu_f4["f4_to_d1_stage_window_f4"],
        f4_checkpoint_i=ifu_f4["f4_to_d1_stage_checkpoint_id_f4"],
        f4_pkt_uid_i=ifu_f4["f4_to_d1_stage_pkt_uid_f4"],
        dmem_rdata_i=dmem_rdata_top,
        bisq_enq_ready_i=bisq_enq_ready_wire,
        brob_active_allocated_i=brob_active_allocated_wire,
        brob_active_ready_i=brob_active_ready_wire,
        brob_active_exception_i=brob_active_exception_wire,
        brob_active_retired_i=brob_active_retired_wire,
        template_uid_i=uid_alloc_top["template_uid_base_o"],
    )

    m.assign(uid_alloc_template_count_top, backend_top["ctu_uop_valid"])
    m.assign(uid_alloc_replay_count_top, (~backend_top["replay_cause"].eq(c(0, width=8))))

    m.assign(backend_ready_top, backend_top["frontend_ready"])
    m.assign(flush_valid_fls, backend_top["redirect_valid"])
    m.assign(flush_pc_fls, backend_top["redirect_pc"])
    m.assign(uid_alloc_fetch_count_top, f2_to_f0_advance_wire_f2 | flush_valid_fls)

    mem_top = m.instance(
        build_mem2r1w,
        name="mem2r1w",
        module_name="JanusMem2R1WTop",
        params={"mem_bytes": mem_bytes},
        clk=clk_top,
        rst=rst_top,
        if_raddr=ifu_icache["imem_raddr_top"],
        d_raddr=backend_top["dmem_raddr"],
        d_wvalid=backend_top["dmem_wvalid"],
        d_waddr=backend_top["dmem_waddr"],
        d_wdata=backend_top["dmem_wdata"],
        d_wstrb=backend_top["dmem_wstrb"],
        host_wvalid=host_wvalid_top,
        host_waddr=host_waddr_top,
        host_wdata=host_wdata_top,
        host_wstrb=host_wstrb_top,
    )
    m.assign(imem_rdata_top, mem_top["if_rdata"])
    m.assign(dmem_rdata_top, mem_top["d_rdata"])

    # OOO stage-map probe pipeline.
    dec1_d1 = m.instance(
        build_janus_bcc_ooo_dec1,
        name="janus_dec1",
        module_name="JanusBccOooDec1Top",
        f4_to_d1_stage_valid_f4=ifu_f4["f4_to_d1_stage_valid_f4"],
        f4_to_d1_stage_pc_f4=ifu_f4["f4_to_d1_stage_pc_f4"],
        f4_to_d1_stage_window_f4=ifu_f4["f4_to_d1_stage_window_f4"],
        f4_to_d1_stage_checkpoint_id_f4=ifu_f4["f4_to_d1_stage_checkpoint_id_f4"],
        f4_to_d1_stage_pkt_uid_f4=ifu_f4["f4_to_d1_stage_pkt_uid_f4"],
    )
    dec2_d2 = m.instance(
        build_janus_bcc_ooo_dec2,
        name="janus_dec2",
        module_name="JanusBccOooDec2Top",
        d1_to_d2_stage_valid_d1=dec1_d1["d1_to_d2_stage_valid_d1"],
        d1_to_d2_stage_pc_d1=dec1_d1["d1_to_d2_stage_pc_d1"],
        d1_to_d2_stage_op_d1=dec1_d1["d1_to_d2_stage_op_d1"],
        d1_to_d2_stage_len_d1=dec1_d1["d1_to_d2_stage_len_d1"],
        d1_to_d2_stage_regdst_d1=dec1_d1["d1_to_d2_stage_regdst_d1"],
        d1_to_d2_stage_srcl_d1=dec1_d1["d1_to_d2_stage_srcl_d1"],
        d1_to_d2_stage_srcr_d1=dec1_d1["d1_to_d2_stage_srcr_d1"],
        d1_to_d2_stage_srcp_d1=dec1_d1["d1_to_d2_stage_srcp_d1"],
        d1_to_d2_stage_imm_d1=dec1_d1["d1_to_d2_stage_imm_d1"],
        d1_to_d2_stage_insn_raw_d1=dec1_d1["d1_to_d2_stage_insn_raw_d1"],
        d1_to_d2_stage_checkpoint_id_d1=dec1_d1["d1_to_d2_stage_checkpoint_id_d1"],
        d1_to_d2_stage_uop_uid_d1=dec1_d1["d1_to_d2_stage_uop_uid_d1"],
    )
    ren_d3 = m.instance(
        build_janus_bcc_ooo_ren,
        name="janus_ren",
        module_name="JanusBccOooRenTop",
        d2_to_d3_stage_valid_d2=dec2_d2["d2_to_d3_stage_valid_d2"],
        d2_to_d3_stage_pc_d2=dec2_d2["d2_to_d3_stage_pc_d2"],
        d2_to_d3_stage_op_d2=dec2_d2["d2_to_d3_stage_op_d2"],
        d2_to_d3_stage_regdst_d2=dec2_d2["d2_to_d3_stage_regdst_d2"],
        d2_to_d3_stage_srcl_d2=dec2_d2["d2_to_d3_stage_srcl_d2"],
        d2_to_d3_stage_srcr_d2=dec2_d2["d2_to_d3_stage_srcr_d2"],
        d2_to_d3_stage_srcp_d2=dec2_d2["d2_to_d3_stage_srcp_d2"],
        d2_to_d3_stage_imm_d2=dec2_d2["d2_to_d3_stage_imm_d2"],
        d2_to_d3_stage_checkpoint_id_d2=dec2_d2["d2_to_d3_stage_checkpoint_id_d2"],
        d2_to_d3_stage_uop_uid_d2=dec2_d2["d2_to_d3_stage_uop_uid_d2"],
    )
    s1_s1 = m.instance(
        build_janus_bcc_ooo_s1,
        name="janus_s1",
        module_name="JanusBccOooS1Top",
        d3_to_s1_stage_valid_d3=ren_d3["d3_to_s1_stage_valid_d3"],
        d3_to_s1_stage_pc_d3=ren_d3["d3_to_s1_stage_pc_d3"],
        d3_to_s1_stage_op_d3=ren_d3["d3_to_s1_stage_op_d3"],
        d3_to_s1_stage_pdst_d3=ren_d3["d3_to_s1_stage_pdst_d3"],
        d3_to_s1_stage_imm_d3=ren_d3["d3_to_s1_stage_imm_d3"],
        d3_to_s1_stage_uop_uid_d3=ren_d3["d3_to_s1_stage_uop_uid_d3"],
    )
    s2_s2 = m.instance(
        build_janus_bcc_ooo_s2,
        name="janus_s2",
        module_name="JanusBccOooS2Top",
        clk=clk_top,
        rst=rst_top,
        s1_to_s2_stage_valid_s1=s1_s1["s1_to_s2_stage_valid_s1"],
        s1_to_s2_stage_pc_s1=s1_s1["s1_to_s2_stage_pc_s1"],
        s1_to_s2_stage_op_s1=s1_s1["s1_to_s2_stage_op_s1"],
        s1_to_s2_stage_pdst_s1=s1_s1["s1_to_s2_stage_pdst_s1"],
        s1_to_s2_stage_imm_s1=s1_s1["s1_to_s2_stage_imm_s1"],
        s1_to_s2_stage_iq_class_s1=s1_s1["s1_to_s2_stage_iq_class_s1"],
        s1_to_s2_stage_uop_uid_s1=s1_s1["s1_to_s2_stage_uop_uid_s1"],
    )

    pcb_lookup_hit_pcb = m.new_wire(width=1)
    pcb_lookup_target_pcb = m.new_wire(width=64)
    pcb_lookup_pred_take_pcb = m.new_wire(width=1)

    iex_top = m.instance(
        build_janus_bcc_iex,
        name="janus_iex",
        module_name="JanusBccIexTop",
        clk=clk_top,
        rst=rst_top,
        s2_to_iex_stage_valid_s2=s2_s2["s2_to_iex_stage_valid_s2"],
        s2_to_iex_stage_op_s2=s2_s2["s2_to_iex_stage_op_s2"],
        s2_to_iex_stage_pc_s2=s2_s2["s2_to_iex_stage_pc_s2"],
        s2_to_iex_stage_pdst_s2=s2_s2["s2_to_iex_stage_pdst_s2"],
        s2_to_iex_stage_imm_s2=s2_s2["s2_to_iex_stage_imm_s2"],
        s2_to_iex_stage_iq_class_s2=s2_s2["s2_to_iex_stage_iq_class_s2"],
        pcb_to_bru_stage_lookup_hit_pcb=pcb_lookup_hit_pcb,
        pcb_to_bru_stage_lookup_target_pcb=pcb_lookup_target_pcb,
        pcb_to_bru_stage_lookup_pred_take_pcb=pcb_lookup_pred_take_pcb,
    )

    rob_top = m.instance(
        build_janus_bcc_ooo_rob,
        name="janus_rob",
        module_name="JanusBccOooRobTop",
        clk=clk_top,
        rst=rst_top,
        alloc_valid_rob=ren_d3["d3_to_s1_stage_valid_d3"],
        alloc_pc_rob=ren_d3["d3_to_s1_stage_pc_d3"],
        wb_valid_rob=iex_top["iex_to_rob_stage_wb_valid_e1"],
        wb_idx_rob=iex_top["iex_to_rob_stage_wb_rob_e1"],
        wb_value_rob=iex_top["iex_to_rob_stage_wb_value_e1"],
        commit_ready_rob=c(1, width=1),
    )

    pcbuf_top = m.instance(
        build_janus_bcc_ooo_pc_buffer,
        name="janus_pcbuf",
        module_name="JanusBccOooPcBufferTop",
        params={"depth": 64, "idx_w": 6},
        clk=clk_top,
        rst=rst_top,
        wr_valid_pcb=ren_d3["d3_to_s1_stage_valid_d3"],
        wr_idx_pcb=rob_top["commit_idx_rob"],
        wr_pc_pcb=ren_d3["d3_to_s1_stage_pc_d3"],
        rd_idx_pcb=rob_top["commit_idx_rob"],
        f3_to_pcb_stage_bstart_valid_f3=ifu_f3["f3_to_pcb_stage_bstart_valid_f3"],
        f3_to_pcb_stage_bstart_pc_f3=ifu_f3["f3_to_pcb_stage_bstart_pc_f3"],
        f3_to_pcb_stage_bstart_kind_f3=ifu_f3["f3_to_pcb_stage_bstart_kind_f3"],
        f3_to_pcb_stage_bstart_target_f3=ifu_f3["f3_to_pcb_stage_bstart_target_f3"],
        f3_to_pcb_stage_pred_take_f3=ifu_f3["f3_to_pcb_stage_pred_take_f3"],
        lookup_pc_pcb=s2_s2["s2_to_iex_stage_pc_s2"],
    )
    m.assign(pcb_lookup_hit_pcb, pcbuf_top["pcb_to_bru_stage_lookup_hit_pcb"])
    m.assign(pcb_lookup_target_pcb, pcbuf_top["pcb_to_bru_stage_lookup_target_pcb"])
    m.assign(pcb_lookup_pred_take_pcb, pcbuf_top["pcb_to_bru_stage_lookup_pred_take_pcb"])

    flush_top = m.instance(
        build_janus_bcc_ooo_flush_ctrl,
        name="janus_flush",
        module_name="JanusBccOooFlushTop",
        clk=clk_top,
        rst=rst_top,
        rob_to_flush_ctrl_stage_redirect_valid_rob=rob_top["rob_to_flush_ctrl_stage_redirect_valid_rob"]
        | iex_top["iex_to_flush_stage_redirect_valid_e1"],
        rob_to_flush_ctrl_stage_redirect_pc_rob=rob_top["rob_to_flush_ctrl_stage_redirect_valid_rob"]._select_internal(
            rob_top["rob_to_flush_ctrl_stage_redirect_pc_rob"],
            iex_top["iex_to_flush_stage_redirect_pc_e1"],
        ),
        rob_to_flush_ctrl_stage_checkpoint_id_rob=rob_top["rob_to_flush_ctrl_stage_checkpoint_id_rob"],
    )

    # Stage-map flush probe is instantiated for module split/visibility, but
    # canonical fetch redirect must come only from the backend commit path.

    renu_top = m.instance(
        build_janus_bcc_ooo_renu,
        name="janus_renu",
        module_name="JanusBccOooRenuTop",
        params={"aregs": 32},
        clk=clk_top,
        rst=rst_top,
        commit_fire_renu=rob_top["commit_fire_rob"],
        commit_areg_renu=rob_top["commit_idx_rob"],
        commit_pdst_renu=rob_top["commit_idx_rob"],
    )

    liq_top = m.instance(
        build_janus_bcc_lsu_liq,
        name="janus_liq",
        module_name="JanusBccLsuLiqTop",
        params={"depth": 32, "idx_w": 5},
        clk=clk_top,
        rst=rst_top,
        load_valid_liq=iex_top["iex_to_rob_stage_load_valid_e1"],
        load_rob_liq=iex_top["iex_to_rob_stage_load_rob_e1"],
        load_addr_liq=iex_top["iex_to_rob_stage_load_addr_e1"],
    )
    stq_top = m.instance(
        build_janus_bcc_lsu_stq,
        name="janus_stq",
        module_name="JanusBccLsuStqTop",
        params={"depth": 32, "idx_w": 5},
        clk=clk_top,
        rst=rst_top,
        store_valid_stq=iex_top["iex_to_rob_stage_store_valid_e1"],
        store_rob_stq=iex_top["iex_to_rob_stage_store_rob_e1"],
        store_addr_stq=iex_top["iex_to_rob_stage_load_addr_e1"],
        store_data_stq=iex_top["iex_to_rob_stage_store_data_e1"],
    )
    lhq_top = m.instance(
        build_janus_bcc_lsu_lhq,
        name="janus_lhq",
        module_name="JanusBccLsuLhqTop",
        clk=clk_top,
        rst=rst_top,
        liq_fire_liq=liq_top["liq_fire_liq"],
        liq_rob_liq=liq_top["liq_rob_liq"],
        liq_addr_liq=liq_top["liq_addr_liq"],
        older_store_valid_lhq=stq_top["stq_head_store_valid_stq"],
        older_store_addr_lhq=stq_top["stq_head_store_addr_stq"],
    )
    mdb_top = m.instance(
        build_janus_bcc_lsu_mdb,
        name="janus_mdb",
        module_name="JanusBccLsuMdbTop",
        clk=clk_top,
        rst=rst_top,
        lhq_conflict_lhq=lhq_top["lhq_conflict_lhq"],
    )
    l1d_top = m.instance(
        build_janus_bcc_lsu_l1d,
        name="janus_l1d",
        module_name="JanusBccLsuL1DTop",
        clk=clk_top,
        rst=rst_top,
        load_req_valid_l1d=iex_top["iex_to_rob_stage_load_valid_e1"],
        load_req_rob_l1d=iex_top["iex_to_rob_stage_load_rob_e1"],
        load_req_addr_l1d=iex_top["iex_to_rob_stage_load_addr_e1"],
        dmem_rdata_top=dmem_rdata_top,
    )
    scb_top = m.instance(
        build_janus_bcc_lsu_scb,
        name="janus_scb",
        module_name="JanusBccLsuScbTop",
        clk=clk_top,
        rst=rst_top,
        stq_head_store_valid_stq=stq_top["stq_head_store_valid_stq"],
        stq_head_store_addr_stq=stq_top["stq_head_store_addr_stq"],
        stq_head_store_data_stq=stq_top["stq_head_store_data_stq"],
    )

    deq_ready_bisq_wire = m.new_wire(width=1)
    issue_fire_brenu_wire = m.new_wire(width=1)
    rsp_tma_valid_wire = m.new_wire(width=1)
    rsp_tma_tag_wire = m.new_wire(width=8)
    rsp_tma_status_wire = m.new_wire(width=4)
    rsp_tma_data0_wire = m.new_wire(width=64)
    rsp_tma_data1_wire = m.new_wire(width=64)
    rsp_cube_valid_wire = m.new_wire(width=1)
    rsp_cube_tag_wire = m.new_wire(width=8)
    rsp_cube_status_wire = m.new_wire(width=4)
    rsp_cube_data0_wire = m.new_wire(width=64)
    rsp_cube_data1_wire = m.new_wire(width=64)
    rsp_tau_valid_wire = m.new_wire(width=1)
    rsp_tau_tag_wire = m.new_wire(width=8)
    rsp_tau_status_wire = m.new_wire(width=4)
    rsp_tau_data0_wire = m.new_wire(width=64)
    rsp_tau_data1_wire = m.new_wire(width=64)
    rsp_vec_valid_wire = m.new_wire(width=1)
    rsp_vec_tag_wire = m.new_wire(width=8)
    rsp_vec_status_wire = m.new_wire(width=4)
    rsp_vec_data0_wire = m.new_wire(width=64)
    rsp_vec_data1_wire = m.new_wire(width=64)
    cmd_ready_tma_wire = m.new_wire(width=1)
    cmd_ready_cube_wire = m.new_wire(width=1)
    cmd_ready_vec_wire = m.new_wire(width=1)
    cmd_ready_tau_wire = m.new_wire(width=1)

    bisq_top = m.instance(
        build_janus_bcc_bctrl_bisq,
        name="janus_bisq",
        module_name="JanusBccBisqTop",
        params={"depth": 16, "idx_w": 4},
        clk=clk_top,
        rst=rst_top,
        enq_valid_bisq=backend_top["cmd_to_bisq_stage_cmd_valid"],
        enq_kind_bisq=backend_top["cmd_to_bisq_stage_cmd_kind"],
        enq_bid_bisq=backend_top["cmd_to_bisq_stage_cmd_bid"],
        enq_payload_bisq=backend_top["cmd_to_bisq_stage_cmd_payload"],
        enq_tile_bisq=backend_top["cmd_to_bisq_stage_cmd_tile"],
        enq_rob_bisq=backend_top["cmd_to_bisq_stage_cmd_src_rob"],
        deq_ready_bisq=deq_ready_bisq_wire,
    )
    m.assign(bisq_enq_ready_wire, bisq_top["bisq_enq_ready_bisq"])
    m.output("cmd_to_bisq_stage_cmd_valid_top", backend_top["cmd_to_bisq_stage_cmd_valid"])
    m.output("cmd_to_bisq_stage_cmd_kind_top", backend_top["cmd_to_bisq_stage_cmd_kind"])
    m.output("cmd_to_bisq_stage_cmd_bid_top", backend_top["cmd_to_bisq_stage_cmd_bid"])
    m.output("cmd_to_bisq_stage_cmd_payload_top", backend_top["cmd_to_bisq_stage_cmd_payload"])
    m.output("cmd_to_bisq_stage_cmd_tile_top", backend_top["cmd_to_bisq_stage_cmd_tile"])
    m.output("cmd_to_bisq_stage_cmd_src_rob_top", backend_top["cmd_to_bisq_stage_cmd_src_rob"])
    brenu_top = m.instance(
        build_janus_bcc_bctrl_brenu,
        name="janus_brenu",
        module_name="JanusBccBrenuTop",
        clk=clk_top,
        rst=rst_top,
        issue_fire_brenu=issue_fire_brenu_wire,
    )

    tmu_noc_top = m.instance(
        build_janus_tmu_noc_node,
        name="janus_tmu_noc",
        module_name="JanusTmuNocNodeTop",
        clk=clk_top,
        rst=rst_top,
        in_valid_noc=bisq_top["bisq_head_valid_bisq"],
        in_data_noc=bisq_top["bisq_head_payload_bisq"],
        out_ready_noc=c(1, width=1),
    )
    tmu_rf_top = m.instance(
        build_janus_tmu_tilereg,
        name="janus_tmu_tilereg",
        module_name="JanusTmuTileRegTop",
        params={"regs": 32},
        clk=clk_top,
        rst=rst_top,
        wr_valid_tmu=tmu_noc_top["out_valid_noc"],
        wr_idx_tmu=bisq_top["bisq_head_tile_bisq"],
        wr_data_tmu=tmu_noc_top["out_data_noc"],
        rd_idx_tmu=bisq_top["bisq_head_tile_bisq"],
    )
    m.output("tmu_to_pe_stage_cmd_valid_top", tmu_noc_top["out_valid_noc"])
    m.output("tmu_to_pe_stage_cmd_ready_top", c(1, width=1))
    m.output("tmu_to_pe_stage_tile_data_top", tmu_rf_top["rd_data_tmu"])
    m.output("pe_to_tmu_stage_wr_valid_top", c(0, width=1))
    m.output("pe_to_tmu_stage_wr_tile_top", c(0, width=6))
    m.output("pe_to_tmu_stage_wr_data_top", c(0, width=64))

    bctrl_top = m.instance(
        build_janus_bcc_bctrl,
        name="janus_bctrl",
        module_name="JanusBccBctrlTop",
        bisq_head_valid_bisq=bisq_top["bisq_head_valid_bisq"],
        bisq_head_kind_bisq=bisq_top["bisq_head_kind_bisq"],
        bisq_head_bid_bisq=bisq_top["bisq_head_bid_bisq"],
        bisq_head_payload_bisq=bisq_top["bisq_head_payload_bisq"],
        bisq_head_tile_bisq=bisq_top["bisq_head_tile_bisq"],
        bisq_head_rob_bisq=bisq_top["bisq_head_rob_bisq"],
        brenu_tag_brenu=brenu_top["brenu_tag_brenu"],
        brenu_bid_brenu=brenu_top["brenu_bid_brenu"],
        brenu_epoch_brenu=brenu_top["brenu_epoch_brenu"],
        brenu_issue_ready_brenu=brenu_top["brenu_issue_ready_brenu"],
        cmd_ready_tma_tma=cmd_ready_tma_wire,
        cmd_ready_cube_cube=cmd_ready_cube_wire,
        cmd_ready_vec_vec=cmd_ready_vec_wire,
        cmd_ready_tau_tau=cmd_ready_tau_wire,
        rsp_tma_valid_tma=rsp_tma_valid_wire,
        rsp_tma_tag_tma=rsp_tma_tag_wire,
        rsp_tma_status_tma=rsp_tma_status_wire,
        rsp_tma_data0_tma=rsp_tma_data0_wire,
        rsp_tma_data1_tma=rsp_tma_data1_wire,
        rsp_cube_valid_cube=rsp_cube_valid_wire,
        rsp_cube_tag_cube=rsp_cube_tag_wire,
        rsp_cube_status_cube=rsp_cube_status_wire,
        rsp_cube_data0_cube=rsp_cube_data0_wire,
        rsp_cube_data1_cube=rsp_cube_data1_wire,
        rsp_tau_valid_tau=rsp_tau_valid_wire,
        rsp_tau_tag_tau=rsp_tau_tag_wire,
        rsp_tau_status_tau=rsp_tau_status_wire,
        rsp_tau_data0_tau=rsp_tau_data0_wire,
        rsp_tau_data1_tau=rsp_tau_data1_wire,
        rsp_vec_valid_vec=rsp_vec_valid_wire,
        rsp_vec_tag_vec=rsp_vec_tag_wire,
        rsp_vec_status_vec=rsp_vec_status_wire,
        rsp_vec_data0_vec=rsp_vec_data0_wire,
        rsp_vec_data1_vec=rsp_vec_data1_wire,
    )

    m.assign(deq_ready_bisq_wire, bctrl_top["deq_ready_bisq"])
    m.assign(issue_fire_brenu_wire, bctrl_top["issue_fire_brenu"])
    m.output("brenu_bid_top", brenu_top["brenu_bid_brenu"])
    m.output("brenu_slot_id_top", brenu_top["brenu_slot_id_brenu"])

    tma_top = m.instance(
        build_janus_tma,
        name="janus_tma",
        module_name="JanusTmaTop",
        clk=clk_top,
        rst=rst_top,
        cmd_valid_tma=bctrl_top["cmd_tma_valid_bctrl"],
        cmd_tag_tma=bctrl_top["bctrl_to_pe_stage_cmd_tag_bctrl"],
        cmd_payload_tma=bctrl_top["bctrl_to_pe_stage_cmd_payload_bctrl"],
    )
    cube_top = m.instance(
        build_janus_cube,
        name="janus_cube",
        module_name="JanusCubeTop",
        clk=clk_top,
        rst=rst_top,
        cmd_valid_cube=bctrl_top["cmd_cube_valid_bctrl"],
        cmd_tag_cube=bctrl_top["bctrl_to_pe_stage_cmd_tag_bctrl"],
        cmd_payload_cube=bctrl_top["bctrl_to_pe_stage_cmd_payload_bctrl"],
    )
    tau_top = m.instance(
        build_janus_tau,
        name="janus_tau",
        module_name="JanusTauTop",
        clk=clk_top,
        rst=rst_top,
        cmd_valid_tau=bctrl_top["cmd_tau_valid_bctrl"],
        cmd_tag_tau=bctrl_top["bctrl_to_pe_stage_cmd_tag_bctrl"],
        cmd_payload_tau=bctrl_top["bctrl_to_pe_stage_cmd_payload_bctrl"],
    )
    vec_top = m.instance(
        build_linxcore_vec,
        name="linxcore_vec",
        module_name="LinxCoreVecTop",
        clk=clk_top,
        rst=rst_top,
        cmd_valid_vec=bctrl_top["cmd_vec_valid_bctrl"],
        cmd_tag_vec=bctrl_top["bctrl_to_pe_stage_cmd_tag_bctrl"],
        cmd_payload_vec=bctrl_top["bctrl_to_pe_stage_cmd_payload_bctrl"],
    )

    m.assign(rsp_tma_valid_wire, tma_top["rsp_valid_tma"])
    m.assign(cmd_ready_tma_wire, tma_top["cmd_ready_tma"])
    m.assign(rsp_tma_tag_wire, tma_top["rsp_tag_tma"])
    m.assign(rsp_tma_status_wire, tma_top["rsp_status_tma"])
    m.assign(rsp_tma_data0_wire, tma_top["rsp_data0_tma"])
    m.assign(rsp_tma_data1_wire, tma_top["rsp_data1_tma"])

    m.assign(rsp_cube_valid_wire, cube_top["rsp_valid_cube"])
    m.assign(cmd_ready_cube_wire, cube_top["cmd_ready_cube"])
    m.assign(rsp_cube_tag_wire, cube_top["rsp_tag_cube"])
    m.assign(rsp_cube_status_wire, cube_top["rsp_status_cube"])
    m.assign(rsp_cube_data0_wire, cube_top["rsp_data0_cube"])
    m.assign(rsp_cube_data1_wire, cube_top["rsp_data1_cube"])

    m.assign(rsp_tau_valid_wire, tau_top["rsp_valid_tau"])
    m.assign(cmd_ready_tau_wire, tau_top["cmd_ready_tau"])
    m.assign(rsp_tau_tag_wire, tau_top["rsp_tag_tau"])
    m.assign(rsp_tau_status_wire, tau_top["rsp_status_tau"])
    m.assign(rsp_tau_data0_wire, tau_top["rsp_data0_tau"])
    m.assign(rsp_tau_data1_wire, tau_top["rsp_data1_tau"])

    m.assign(rsp_vec_valid_wire, vec_top["rsp_valid_vec"])
    m.assign(cmd_ready_vec_wire, vec_top["cmd_ready_vec"])
    m.assign(rsp_vec_tag_wire, vec_top["rsp_tag_vec"])
    m.assign(rsp_vec_status_wire, vec_top["rsp_status_vec"])
    m.assign(rsp_vec_data0_wire, vec_top["rsp_data0_vec"])
    m.assign(rsp_vec_data1_wire, vec_top["rsp_data1_vec"])

    brob_top = m.instance(
        build_janus_bcc_bctrl_brob,
        name="janus_brob",
        module_name="JanusBccBrobTop",
        clk=clk_top,
        rst=rst_top,
        issue_fire_brob=bctrl_top["issue_fire_brob"],
        issue_tag_brob=bctrl_top["issue_tag_brob"],
        issue_bid_brob=bctrl_top["issue_bid_brob"],
        issue_src_rob_brob=bctrl_top["issue_src_rob_brob"],
        retire_fire_brob=backend_top["brob_retire_fire"],
        retire_bid_brob=backend_top["brob_retire_bid"],
        query_bid_brob=backend_top["active_block_bid"],
        rsp_valid_brob=bctrl_top["rsp_valid_brob"],
        rsp_tag_brob=bctrl_top["rsp_tag_brob"],
        rsp_status_brob=bctrl_top["rsp_status_brob"],
        rsp_data0_brob=bctrl_top["rsp_data0_brob"],
        rsp_data1_brob=bctrl_top["rsp_data1_brob"],
        rsp_trap_valid_brob=bctrl_top["rsp_trap_valid_brob"],
        rsp_trap_cause_brob=bctrl_top["rsp_trap_cause_brob"],
    )
    m.output("brob_to_rob_stage_rsp_valid_top", brob_top["brob_to_rob_stage_rsp_valid_brob"])
    m.output("brob_to_rob_stage_rsp_tag_top", brob_top["brob_to_rob_stage_rsp_tag_brob"])
    m.output("brob_to_rob_stage_rsp_status_top", brob_top["brob_to_rob_stage_rsp_status_brob"])
    m.output("brob_to_rob_stage_rsp_data0_top", brob_top["brob_to_rob_stage_rsp_data0_brob"])
    m.output("brob_to_rob_stage_rsp_data1_top", brob_top["brob_to_rob_stage_rsp_data1_brob"])
    m.output("brob_to_rob_stage_rsp_src_rob_top", brob_top["brob_to_rob_stage_rsp_src_rob_brob"])
    m.output("brob_to_rob_stage_rsp_bid_top", brob_top["brob_to_rob_stage_rsp_bid_brob"])
    m.output("brob_query_state_top", brob_top["brob_query_state_brob"])
    m.output("brob_query_allocated_top", brob_top["brob_query_allocated_brob"])
    m.output("brob_query_ready_top", brob_top["brob_query_ready_brob"])
    m.output("brob_query_exception_top", brob_top["brob_query_exception_brob"])
    m.output("brob_query_retired_top", brob_top["brob_query_retired_brob"])
    m.assign(brob_active_allocated_wire, brob_top["brob_query_allocated_brob"])
    m.assign(brob_active_ready_wire, brob_top["brob_query_ready_brob"])
    m.assign(brob_active_exception_wire, brob_top["brob_query_exception_brob"])
    m.assign(brob_active_retired_wire, brob_top["brob_query_retired_brob"])
    m.output("bctrl_to_pe_stage_cmd_bid_top", bctrl_top["bctrl_to_pe_stage_cmd_bid_bctrl"])
    m.output("bctrl_issue_bid_top", bctrl_top["issue_bid_brob"])
    m.output("bctrl_issue_fire_top", bctrl_top["issue_fire_brob"])
    m.output("bctrl_issue_src_rob_top", bctrl_top["issue_src_rob_brob"])

    # Canonical LinxTrace event bus (debug-only, no architectural effect).
    # Event kinds: normal=0, flush=1, trap=2, replay=3.
    trace_kind_normal_top = c(0, width=2)
    trace_kind_flush_top = c(1, width=2)
    trace_kind_trap_top = c(2, width=2)
    trace_kind_replay_top = c(3, width=2)

    trace_sid_f0_top = c(0, width=6)
    trace_sid_f1_top = c(1, width=6)
    trace_sid_f2_top = c(2, width=6)
    trace_sid_f3_top = c(3, width=6)
    trace_sid_f4_top = c(4, width=6)
    trace_sid_d1_top = c(5, width=6)
    trace_sid_d2_top = c(6, width=6)
    trace_sid_d3_top = c(7, width=6)
    trace_sid_iq_top = c(8, width=6)
    trace_sid_s1_top = c(9, width=6)
    trace_sid_s2_top = c(10, width=6)
    trace_sid_p1_top = c(11, width=6)
    trace_sid_i1_top = c(12, width=6)
    trace_sid_i2_top = c(13, width=6)
    trace_sid_e1_top = c(14, width=6)
    trace_sid_e2_top = c(15, width=6)
    trace_sid_e3_top = c(16, width=6)
    trace_sid_e4_top = c(17, width=6)
    trace_sid_w1_top = c(18, width=6)
    trace_sid_w2_top = c(19, width=6)
    trace_sid_liq_top = c(20, width=6)
    trace_sid_lhq_top = c(21, width=6)
    trace_sid_stq_top = c(22, width=6)
    trace_sid_scb_top = c(23, width=6)
    trace_sid_mdb_top = c(24, width=6)
    trace_sid_l1d_top = c(25, width=6)
    trace_sid_bisq_top = c(26, width=6)
    trace_sid_bctrl_top = c(27, width=6)
    trace_sid_tmu_top = c(28, width=6)
    trace_sid_tma_top = c(29, width=6)
    trace_sid_cube_top = c(30, width=6)
    trace_sid_vec_top = c(31, width=6)
    trace_sid_tau_top = c(32, width=6)
    trace_sid_brob_top = c(33, width=6)
    trace_sid_rob_top = c(34, width=6)
    trace_sid_cmt_top = c(35, width=6)
    trace_sid_fls_top = c(36, width=6)

    # Sequence assignment: stamp each dispatched ROB slot and reuse the stamp
    # for later stage events (issue/commit/lsu/block responses).
    trace_seq_ctr_top = m.out(
        "trace_seq_ctr_top",
        clk=clk_top,
        rst=rst_top,
        width=32,
        init=c(1, width=32),
        en=c(1, width=1),
    )
    trace_rob_seq_top = []
    trace_rob_uid_top = []
    trace_front_uid_ctr_top = m.out(
        "trace_front_uid_ctr_top",
        clk=clk_top,
        rst=rst_top,
        width=64,
        init=c(1, width=64),
        en=c(1, width=1),
    )
    for i in range(64):
        trace_rob_seq_top.append(
            m.out(
                f"trace_rob_seq{i}_top",
                clk=clk_top,
                rst=rst_top,
                width=32,
                init=c(0, width=32),
                en=c(1, width=1),
            )
        )
        trace_rob_uid_top.append(
            m.out(
                f"trace_rob_uid{i}_top",
                clk=clk_top,
                rst=rst_top,
                width=64,
                init=c(0, width=64),
                en=c(1, width=1),
            )
        )

    def trace_lookup_seq_top(rob_idx_top):
        seq_top = c(0, width=32)
        for i in range(64):
            seq_top = rob_idx_top.eq(c(i, width=6))._select_internal(trace_rob_seq_top[i].out(), seq_top)
        return seq_top

    def trace_lookup_uid_top(rob_idx_top):
        uid_top = c(0, width=64)
        for i in range(64):
            uid_top = rob_idx_top.eq(c(i, width=6))._select_internal(trace_rob_uid_top[i].out(), uid_top)
        return uid_top

    trace_dispatch_fire_top = [backend_top[f"dispatch_fire{slot}"] for slot in range(4)]
    trace_dispatch_rob_top = [backend_top[f"dispatch_rob{slot}"] for slot in range(4)]
    trace_dispatch_pc_top = [backend_top[f"dispatch_pc{slot}"] for slot in range(4)]
    trace_dispatch_edge_top = []
    trace_issue_edge_top = []
    trace_commit_edge_top = []
    trace_commit_wb_edge_top = []
    for slot in range(4):
        prev_dispatch_top = m.out(
            f"trace_prev_dispatch_fire{slot}_top",
            clk=clk_top,
            rst=rst_top,
            width=1,
            init=c(0, width=1),
            en=c(1, width=1),
        )
        prev_issue_top = m.out(
            f"trace_prev_issue_fire{slot}_top",
            clk=clk_top,
            rst=rst_top,
            width=1,
            init=c(0, width=1),
            en=c(1, width=1),
        )
        prev_commit_top = m.out(
            f"trace_prev_commit_fire{slot}_top",
            clk=clk_top,
            rst=rst_top,
            width=1,
            init=c(0, width=1),
            en=c(1, width=1),
        )
        prev_commit_wb_top = m.out(
            f"trace_prev_commit_wb_fire{slot}_top",
            clk=clk_top,
            rst=rst_top,
            width=1,
            init=c(0, width=1),
            en=c(1, width=1),
        )
        dispatch_cur_top = backend_top[f"dispatch_fire{slot}"]
        issue_cur_top = backend_top[f"issue_fire{slot}"]
        commit_cur_top = backend_top[f"commit_fire{slot}"]
        commit_wb_cur_top = backend_top[f"commit_wb_valid{slot}"]
        trace_dispatch_edge_top.append(dispatch_cur_top & (~prev_dispatch_top.out()))
        trace_issue_edge_top.append(issue_cur_top & (~prev_issue_top.out()))
        trace_commit_edge_top.append(commit_cur_top & (~prev_commit_top.out()))
        trace_commit_wb_edge_top.append(commit_wb_cur_top & (~prev_commit_wb_top.out()))
        prev_dispatch_top.set(dispatch_cur_top)
        prev_issue_top.set(issue_cur_top)
        prev_commit_top.set(commit_cur_top)
        prev_commit_wb_top.set(commit_wb_cur_top)

    trace_dispatch_seq_top = []
    trace_seq_next_top = trace_seq_ctr_top.out()
    for slot in range(4):
        trace_dispatch_seq_top.append(trace_seq_next_top)
        trace_seq_next_top = trace_dispatch_fire_top[slot]._select_internal(trace_seq_next_top + c(1, width=32), trace_seq_next_top)
    trace_seq_ctr_top.set(trace_seq_next_top)

    for i in range(64):
        seq_i_top = trace_rob_seq_top[i].out()
        uid_i_top = trace_rob_uid_top[i].out()
        for slot in range(4):
            hit_i_top = trace_dispatch_fire_top[slot] & trace_dispatch_rob_top[slot].eq(c(i, width=6))
            seq_i_top = hit_i_top._select_internal(trace_dispatch_seq_top[slot], seq_i_top)
            uid_i_top = hit_i_top._select_internal(backend_top[f"dispatch_uop_uid{slot}"], uid_i_top)
        trace_rob_seq_top[i].set(seq_i_top)
        trace_rob_uid_top[i].set(uid_i_top)

    # Issue delay taps for explicit I1/I2 stage markers.
    trace_issue_fire_d1_top = []
    trace_issue_rob_d1_top = []
    trace_issue_pc_d1_top = []
    trace_issue_fire_d2_top = []
    trace_issue_rob_d2_top = []
    trace_issue_pc_d2_top = []
    for slot in range(4):
        fire_d1_top = m.out(
            f"trace_issue_fire_d1{slot}_top",
            clk=clk_top,
            rst=rst_top,
            width=1,
            init=c(0, width=1),
            en=c(1, width=1),
        )
        rob_d1_top = m.out(
            f"trace_issue_rob_d1{slot}_top",
            clk=clk_top,
            rst=rst_top,
            width=6,
            init=c(0, width=6),
            en=c(1, width=1),
        )
        pc_d1_top = m.out(
            f"trace_issue_pc_d1{slot}_top",
            clk=clk_top,
            rst=rst_top,
            width=64,
            init=c(0, width=64),
            en=c(1, width=1),
        )
        fire_d2_top = m.out(
            f"trace_issue_fire_d2{slot}_top",
            clk=clk_top,
            rst=rst_top,
            width=1,
            init=c(0, width=1),
            en=c(1, width=1),
        )
        rob_d2_top = m.out(
            f"trace_issue_rob_d2{slot}_top",
            clk=clk_top,
            rst=rst_top,
            width=6,
            init=c(0, width=6),
            en=c(1, width=1),
        )
        pc_d2_top = m.out(
            f"trace_issue_pc_d2{slot}_top",
            clk=clk_top,
            rst=rst_top,
            width=64,
            init=c(0, width=64),
            en=c(1, width=1),
        )

        fire_d1_top.set(backend_top[f"issue_fire{slot}"])
        rob_d1_top.set(backend_top[f"issue_rob{slot}"])
        pc_d1_top.set(backend_top[f"issue_pc{slot}"])
        fire_d2_top.set(fire_d1_top.out())
        rob_d2_top.set(rob_d1_top.out())
        pc_d2_top.set(pc_d1_top.out())

        trace_issue_fire_d1_top.append(fire_d1_top.out())
        trace_issue_rob_d1_top.append(rob_d1_top.out())
        trace_issue_pc_d1_top.append(pc_d1_top.out())
        trace_issue_fire_d2_top.append(fire_d2_top.out())
        trace_issue_rob_d2_top.append(rob_d2_top.out())
        trace_issue_pc_d2_top.append(pc_d2_top.out())

    # Deterministic event selection: stage-id order then lane order.
    trace_evt_valid_top = c(0, width=1)
    trace_evt_stage_id_top = c(0, width=6)
    trace_evt_lane_top = c(0, width=3)
    trace_evt_rob_top = c(0, width=6)
    trace_evt_seq_top = c(0, width=32)
    trace_evt_pc_top = c(0, width=64)
    trace_evt_kind_top = c(0, width=2)

    def trace_choose_event_top(*, valid_top, stage_id_top, lane_top, rob_top, seq_top, pc_top, kind_top):
        nonlocal trace_evt_valid_top, trace_evt_stage_id_top, trace_evt_lane_top, trace_evt_rob_top
        nonlocal trace_evt_seq_top, trace_evt_pc_top, trace_evt_kind_top
        choose_top = valid_top & (~trace_evt_valid_top)
        trace_evt_valid_top = choose_top._select_internal(c(1, width=1), trace_evt_valid_top)
        trace_evt_stage_id_top = choose_top._select_internal(stage_id_top, trace_evt_stage_id_top)
        trace_evt_lane_top = choose_top._select_internal(lane_top, trace_evt_lane_top)
        trace_evt_rob_top = choose_top._select_internal(rob_top, trace_evt_rob_top)
        trace_evt_seq_top = choose_top._select_internal(seq_top, trace_evt_seq_top)
        trace_evt_pc_top = choose_top._select_internal(pc_top, trace_evt_pc_top)
        trace_evt_kind_top = choose_top._select_internal(kind_top, trace_evt_kind_top)

    # Edge-detect broad valid signals to avoid a single always-high source
    # monopolizing the one-event-per-cycle debug channel.
    trace_prev_f0_top = m.out("trace_prev_f0_top", clk=clk_top, rst=rst_top, width=1, init=c(0, width=1), en=c(1, width=1))
    trace_prev_f1_top = m.out("trace_prev_f1_top", clk=clk_top, rst=rst_top, width=1, init=c(0, width=1), en=c(1, width=1))
    trace_prev_f2_top = m.out("trace_prev_f2_top", clk=clk_top, rst=rst_top, width=1, init=c(0, width=1), en=c(1, width=1))
    trace_prev_f3_top = m.out("trace_prev_f3_top", clk=clk_top, rst=rst_top, width=1, init=c(0, width=1), en=c(1, width=1))
    trace_prev_f4_top = m.out("trace_prev_f4_top", clk=clk_top, rst=rst_top, width=1, init=c(0, width=1), en=c(1, width=1))
    trace_prev_d1_top = m.out("trace_prev_d1_top", clk=clk_top, rst=rst_top, width=1, init=c(0, width=1), en=c(1, width=1))
    trace_prev_d2_top = m.out("trace_prev_d2_top", clk=clk_top, rst=rst_top, width=1, init=c(0, width=1), en=c(1, width=1))
    trace_prev_s1_top = m.out("trace_prev_s1_top", clk=clk_top, rst=rst_top, width=1, init=c(0, width=1), en=c(1, width=1))
    trace_prev_s2_top = m.out("trace_prev_s2_top", clk=clk_top, rst=rst_top, width=1, init=c(0, width=1), en=c(1, width=1))

    trace_f0_cur_top = f2_to_f0_advance_wire_f2
    trace_f1_cur_top = ifu_f1["f1_to_icache_stage_valid_f1"]
    trace_f2_cur_top = ifu_f2["f2_to_f3_stage_valid_f2"]
    trace_f3_cur_top = ifu_f3["f3_pop_fire_f3"]
    trace_f4_cur_top = ifu_f4["f4_to_d1_stage_valid_f4"]
    trace_d1_cur_top = dec1_d1["d1_to_d2_stage_valid_d1"]
    trace_d2_cur_top = dec2_d2["d2_to_d3_stage_valid_d2"]
    trace_s1_cur_top = s1_s1["s1_to_s2_stage_valid_s1"]
    trace_s2_cur_top = s2_s2["s2_to_iex_stage_valid_s2"]

    trace_f0_fire_top = trace_f0_cur_top & (~trace_prev_f0_top.out())
    trace_f1_fire_top = trace_f1_cur_top & (~trace_prev_f1_top.out())
    trace_f2_fire_top = trace_f2_cur_top & (~trace_prev_f2_top.out())
    trace_f3_fire_top = trace_f3_cur_top & (~trace_prev_f3_top.out())
    trace_f4_fire_top = trace_f4_cur_top & (~trace_prev_f4_top.out())
    trace_d1_fire_top = trace_d1_cur_top & (~trace_prev_d1_top.out())
    trace_d2_fire_top = trace_d2_cur_top & (~trace_prev_d2_top.out())
    trace_s1_fire_top = trace_s1_cur_top & (~trace_prev_s1_top.out())
    trace_s2_fire_top = trace_s2_cur_top & (~trace_prev_s2_top.out())

    trace_front_uid_top = c(1 << 63, width=64) | trace_front_uid_ctr_top.out()
    trace_front_uid_next_top = trace_front_uid_ctr_top.out()
    trace_front_fire_any_top = (
        trace_f0_fire_top
        | trace_f1_fire_top
        | trace_f2_fire_top
        | trace_f3_fire_top
        | trace_f4_fire_top
        | trace_d1_fire_top
        | trace_d2_fire_top
        | trace_s1_fire_top
        | trace_s2_fire_top
    )
    trace_front_uid_next_top = trace_front_fire_any_top._select_internal(trace_front_uid_next_top + c(1, width=64), trace_front_uid_next_top)
    trace_front_uid_ctr_top.set(trace_front_uid_next_top)

    trace_prev_f0_top.set(trace_f0_cur_top)
    trace_prev_f1_top.set(trace_f1_cur_top)
    trace_prev_f2_top.set(trace_f2_cur_top)
    trace_prev_f3_top.set(trace_f3_cur_top)
    trace_prev_f4_top.set(trace_f4_cur_top)
    trace_prev_d1_top.set(trace_d1_cur_top)
    trace_prev_d2_top.set(trace_d2_cur_top)
    trace_prev_s1_top.set(trace_s1_cur_top)
    trace_prev_s2_top.set(trace_s2_cur_top)

    trace_front_seq_top = trace_seq_ctr_top.out()
    trace_choose_event_top(
        valid_top=trace_f0_fire_top,
        stage_id_top=trace_sid_f0_top,
        lane_top=c(0, width=3),
        rob_top=c(0, width=6),
        seq_top=trace_front_seq_top,
        pc_top=ifu_f0["f0_to_f1_stage_pc_f0"],
        kind_top=trace_kind_normal_top,
    )
    trace_choose_event_top(
        valid_top=trace_f1_fire_top,
        stage_id_top=trace_sid_f1_top,
        lane_top=c(0, width=3),
        rob_top=c(0, width=6),
        seq_top=trace_front_seq_top,
        pc_top=ifu_f1["f1_to_icache_stage_pc_f1"],
        kind_top=trace_kind_normal_top,
    )
    trace_choose_event_top(
        valid_top=trace_f2_fire_top,
        stage_id_top=trace_sid_f2_top,
        lane_top=c(0, width=3),
        rob_top=c(0, width=6),
        seq_top=trace_front_seq_top,
        pc_top=ifu_f2["f2_to_f3_stage_pc_f2"],
        kind_top=trace_kind_normal_top,
    )
    trace_choose_event_top(
        valid_top=trace_f3_fire_top,
        stage_id_top=trace_sid_f3_top,
        lane_top=c(0, width=3),
        rob_top=c(0, width=6),
        seq_top=trace_front_seq_top,
        pc_top=ifu_f3["f3_to_f4_stage_pc_f3"],
        kind_top=trace_kind_normal_top,
    )
    trace_choose_event_top(
        valid_top=trace_f4_fire_top,
        stage_id_top=trace_sid_f4_top,
        lane_top=c(0, width=3),
        rob_top=c(0, width=6),
        seq_top=trace_front_seq_top,
        pc_top=ifu_f4["f4_to_d1_stage_pc_f4"],
        kind_top=trace_kind_normal_top,
    )
    trace_choose_event_top(
        valid_top=trace_d1_fire_top,
        stage_id_top=trace_sid_d1_top,
        lane_top=c(0, width=3),
        rob_top=c(0, width=6),
        seq_top=trace_front_seq_top,
        pc_top=dec1_d1["d1_to_d2_stage_pc_d1"],
        kind_top=trace_kind_normal_top,
    )
    trace_choose_event_top(
        valid_top=trace_d2_fire_top,
        stage_id_top=trace_sid_d2_top,
        lane_top=c(0, width=3),
        rob_top=c(0, width=6),
        seq_top=trace_front_seq_top,
        pc_top=dec2_d2["d2_to_d3_stage_pc_d2"],
        kind_top=trace_kind_normal_top,
    )
    for slot in range(4):
        trace_choose_event_top(
            valid_top=trace_dispatch_edge_top[slot],
            stage_id_top=trace_sid_d3_top,
            lane_top=c(slot, width=3),
            rob_top=trace_dispatch_rob_top[slot],
            seq_top=trace_dispatch_seq_top[slot],
            pc_top=trace_dispatch_pc_top[slot],
            kind_top=trace_kind_normal_top,
        )
    trace_choose_event_top(
        valid_top=trace_s1_fire_top,
        stage_id_top=trace_sid_s1_top,
        lane_top=c(0, width=3),
        rob_top=c(0, width=6),
        seq_top=trace_front_seq_top,
        pc_top=s1_s1["s1_to_s2_stage_pc_s1"],
        kind_top=trace_kind_normal_top,
    )
    trace_choose_event_top(
        valid_top=trace_s2_fire_top,
        stage_id_top=trace_sid_s2_top,
        lane_top=c(0, width=3),
        rob_top=c(0, width=6),
        seq_top=trace_front_seq_top,
        pc_top=s2_s2["s2_to_iex_stage_pc_s2"],
        kind_top=trace_kind_normal_top,
    )
    for slot in range(4):
        issue_rob_top = backend_top[f"issue_rob{slot}"]
        issue_pc_top = backend_top[f"issue_pc{slot}"]
        trace_choose_event_top(
            valid_top=trace_issue_edge_top[slot],
            stage_id_top=trace_sid_p1_top,
            lane_top=c(slot, width=3),
            rob_top=issue_rob_top,
            seq_top=trace_lookup_seq_top(issue_rob_top),
            pc_top=issue_pc_top,
            kind_top=trace_kind_normal_top,
        )
        i1_rob_top = trace_issue_rob_d1_top[slot]
        i1_pc_top = trace_issue_pc_d1_top[slot]
        trace_choose_event_top(
            valid_top=trace_issue_fire_d1_top[slot],
            stage_id_top=trace_sid_i1_top,
            lane_top=c(slot, width=3),
            rob_top=i1_rob_top,
            seq_top=trace_lookup_seq_top(i1_rob_top),
            pc_top=i1_pc_top,
            kind_top=trace_kind_normal_top,
        )
        i2_rob_top = trace_issue_rob_d2_top[slot]
        i2_pc_top = trace_issue_pc_d2_top[slot]
        trace_choose_event_top(
            valid_top=trace_issue_fire_d2_top[slot],
            stage_id_top=trace_sid_i2_top,
            lane_top=c(slot, width=3),
            rob_top=i2_rob_top,
            seq_top=trace_lookup_seq_top(i2_rob_top),
            pc_top=i2_pc_top,
            kind_top=trace_kind_normal_top,
        )

    e1_valid_top = (
        iex_top["iex_to_rob_stage_wb_valid_e1"]
        | iex_top["iex_to_rob_stage_load_valid_e1"]
        | iex_top["iex_to_rob_stage_store_valid_e1"]
        | iex_top["iex_to_flush_stage_redirect_valid_e1"]
    )
    e1_rob_top = iex_top["iex_to_rob_stage_wb_rob_e1"]
    e1_rob_top = iex_top["iex_to_rob_stage_load_valid_e1"]._select_internal(iex_top["iex_to_rob_stage_load_rob_e1"], e1_rob_top)
    e1_rob_top = iex_top["iex_to_rob_stage_store_valid_e1"]._select_internal(iex_top["iex_to_rob_stage_store_rob_e1"], e1_rob_top)
    e1_pc_top = backend_top["issue_pc0"]
    trace_choose_event_top(
        valid_top=e1_valid_top,
        stage_id_top=trace_sid_e1_top,
        lane_top=c(0, width=3),
        rob_top=e1_rob_top,
        seq_top=trace_lookup_seq_top(e1_rob_top),
        pc_top=e1_pc_top,
        kind_top=trace_kind_normal_top,
    )
    trace_choose_event_top(
        valid_top=l1d_top["lsu_to_rob_stage_load_valid_l1d"],
        stage_id_top=trace_sid_e2_top,
        lane_top=c(0, width=3),
        rob_top=l1d_top["lsu_to_rob_stage_load_rob_l1d"],
        seq_top=trace_lookup_seq_top(l1d_top["lsu_to_rob_stage_load_rob_l1d"]),
        pc_top=pcbuf_top["rd_pc_pcb"],
        kind_top=trace_kind_normal_top,
    )
    trace_choose_event_top(
        valid_top=lhq_top["lhq_fire_lhq"],
        stage_id_top=trace_sid_e3_top,
        lane_top=c(0, width=3),
        rob_top=lhq_top["lhq_rob_lhq"],
        seq_top=trace_lookup_seq_top(lhq_top["lhq_rob_lhq"]),
        pc_top=pcbuf_top["rd_pc_pcb"],
        kind_top=trace_kind_normal_top,
    )
    trace_choose_event_top(
        valid_top=mdb_top["lsu_violation_detected_mdb"],
        stage_id_top=trace_sid_e4_top,
        lane_top=c(0, width=3),
        rob_top=c(0, width=6),
        seq_top=trace_front_seq_top,
        pc_top=pcbuf_top["rd_pc_pcb"],
        kind_top=trace_kind_replay_top,
    )
    for slot in range(4):
        commit_rob_top = backend_top[f"commit_rob{slot}"]
        commit_pc_top = backend_top[f"commit_pc{slot}"]
        trace_choose_event_top(
            valid_top=trace_commit_wb_edge_top[slot],
            stage_id_top=trace_sid_w1_top,
            lane_top=c(slot, width=3),
            rob_top=commit_rob_top,
            seq_top=trace_lookup_seq_top(commit_rob_top),
            pc_top=commit_pc_top,
            kind_top=trace_kind_normal_top,
        )
        trace_choose_event_top(
            valid_top=trace_commit_edge_top[slot],
            stage_id_top=trace_sid_w2_top,
            lane_top=c(slot, width=3),
            rob_top=commit_rob_top,
            seq_top=trace_lookup_seq_top(commit_rob_top),
            pc_top=commit_pc_top,
            kind_top=trace_kind_normal_top,
        )
    trace_choose_event_top(
        valid_top=liq_top["liq_enq_fire_liq"],
        stage_id_top=trace_sid_liq_top,
        lane_top=c(0, width=3),
        rob_top=iex_top["iex_to_rob_stage_load_rob_e1"],
        seq_top=trace_lookup_seq_top(iex_top["iex_to_rob_stage_load_rob_e1"]),
        pc_top=pcbuf_top["rd_pc_pcb"],
        kind_top=trace_kind_normal_top,
    )
    trace_choose_event_top(
        valid_top=lhq_top["lhq_fire_lhq"],
        stage_id_top=trace_sid_lhq_top,
        lane_top=c(0, width=3),
        rob_top=lhq_top["lhq_rob_lhq"],
        seq_top=trace_lookup_seq_top(lhq_top["lhq_rob_lhq"]),
        pc_top=pcbuf_top["rd_pc_pcb"],
        kind_top=trace_kind_normal_top,
    )
    trace_choose_event_top(
        valid_top=stq_top["stq_enq_fire_stq"],
        stage_id_top=trace_sid_stq_top,
        lane_top=c(0, width=3),
        rob_top=iex_top["iex_to_rob_stage_store_rob_e1"],
        seq_top=trace_lookup_seq_top(iex_top["iex_to_rob_stage_store_rob_e1"]),
        pc_top=pcbuf_top["rd_pc_pcb"],
        kind_top=trace_kind_normal_top,
    )
    trace_choose_event_top(
        valid_top=scb_top["dmem_wvalid_scb"],
        stage_id_top=trace_sid_scb_top,
        lane_top=c(0, width=3),
        rob_top=stq_top["stq_head_store_rob_stq"],
        seq_top=trace_lookup_seq_top(stq_top["stq_head_store_rob_stq"]),
        pc_top=pcbuf_top["rd_pc_pcb"],
        kind_top=trace_kind_normal_top,
    )
    trace_choose_event_top(
        valid_top=mdb_top["lsu_conflict_seen_mdb"],
        stage_id_top=trace_sid_mdb_top,
        lane_top=c(0, width=3),
        rob_top=c(0, width=6),
        seq_top=trace_front_seq_top,
        pc_top=pcbuf_top["rd_pc_pcb"],
        kind_top=trace_kind_replay_top,
    )
    trace_choose_event_top(
        valid_top=l1d_top["lsu_to_rob_stage_load_valid_l1d"],
        stage_id_top=trace_sid_l1d_top,
        lane_top=c(0, width=3),
        rob_top=l1d_top["lsu_to_rob_stage_load_rob_l1d"],
        seq_top=trace_lookup_seq_top(l1d_top["lsu_to_rob_stage_load_rob_l1d"]),
        pc_top=pcbuf_top["rd_pc_pcb"],
        kind_top=trace_kind_normal_top,
    )
    trace_choose_event_top(
        valid_top=bisq_top["bisq_deq_fire_bisq"],
        stage_id_top=trace_sid_bisq_top,
        lane_top=c(0, width=3),
        rob_top=bisq_top["bisq_head_rob_bisq"],
        seq_top=trace_lookup_seq_top(bisq_top["bisq_head_rob_bisq"]),
        pc_top=pcbuf_top["rd_pc_pcb"],
        kind_top=trace_kind_normal_top,
    )
    trace_choose_event_top(
        valid_top=bctrl_top["issue_fire_brob"],
        stage_id_top=trace_sid_bctrl_top,
        lane_top=c(0, width=3),
        rob_top=bctrl_top["issue_src_rob_brob"],
        seq_top=trace_lookup_seq_top(bctrl_top["issue_src_rob_brob"]),
        pc_top=pcbuf_top["rd_pc_pcb"],
        kind_top=trace_kind_normal_top,
    )
    trace_choose_event_top(
        valid_top=tmu_noc_top["out_valid_noc"],
        stage_id_top=trace_sid_tmu_top,
        lane_top=c(0, width=3),
        rob_top=bisq_top["bisq_head_rob_bisq"],
        seq_top=trace_lookup_seq_top(bisq_top["bisq_head_rob_bisq"]),
        pc_top=pcbuf_top["rd_pc_pcb"],
        kind_top=trace_kind_normal_top,
    )
    trace_choose_event_top(
        valid_top=bctrl_top["cmd_tma_valid_bctrl"],
        stage_id_top=trace_sid_tma_top,
        lane_top=c(0, width=3),
        rob_top=bctrl_top["bctrl_to_pe_stage_cmd_src_rob_bctrl"],
        seq_top=trace_lookup_seq_top(bctrl_top["bctrl_to_pe_stage_cmd_src_rob_bctrl"]),
        pc_top=pcbuf_top["rd_pc_pcb"],
        kind_top=trace_kind_normal_top,
    )
    trace_choose_event_top(
        valid_top=bctrl_top["cmd_cube_valid_bctrl"],
        stage_id_top=trace_sid_cube_top,
        lane_top=c(0, width=3),
        rob_top=bctrl_top["bctrl_to_pe_stage_cmd_src_rob_bctrl"],
        seq_top=trace_lookup_seq_top(bctrl_top["bctrl_to_pe_stage_cmd_src_rob_bctrl"]),
        pc_top=pcbuf_top["rd_pc_pcb"],
        kind_top=trace_kind_normal_top,
    )
    trace_choose_event_top(
        valid_top=bctrl_top["cmd_vec_valid_bctrl"],
        stage_id_top=trace_sid_vec_top,
        lane_top=c(0, width=3),
        rob_top=bctrl_top["bctrl_to_pe_stage_cmd_src_rob_bctrl"],
        seq_top=trace_lookup_seq_top(bctrl_top["bctrl_to_pe_stage_cmd_src_rob_bctrl"]),
        pc_top=pcbuf_top["rd_pc_pcb"],
        kind_top=trace_kind_normal_top,
    )
    trace_choose_event_top(
        valid_top=bctrl_top["cmd_tau_valid_bctrl"],
        stage_id_top=trace_sid_tau_top,
        lane_top=c(0, width=3),
        rob_top=bctrl_top["bctrl_to_pe_stage_cmd_src_rob_bctrl"],
        seq_top=trace_lookup_seq_top(bctrl_top["bctrl_to_pe_stage_cmd_src_rob_bctrl"]),
        pc_top=pcbuf_top["rd_pc_pcb"],
        kind_top=trace_kind_normal_top,
    )
    trace_choose_event_top(
        valid_top=brob_top["brob_rsp_fire_brob"],
        stage_id_top=trace_sid_brob_top,
        lane_top=c(0, width=3),
        rob_top=brob_top["brob_to_rob_stage_rsp_src_rob_brob"],
        seq_top=trace_lookup_seq_top(brob_top["brob_to_rob_stage_rsp_src_rob_brob"]),
        pc_top=pcbuf_top["rd_pc_pcb"],
        kind_top=trace_kind_normal_top,
    )
    trace_choose_event_top(
        valid_top=rob_top["commit_fire_rob"],
        stage_id_top=trace_sid_rob_top,
        lane_top=c(0, width=3),
        rob_top=rob_top["commit_idx_rob"],
        seq_top=trace_lookup_seq_top(rob_top["commit_idx_rob"]),
        pc_top=rob_top["commit_pc_rob"],
        kind_top=trace_kind_normal_top,
    )
    for slot in range(4):
        commit_rob_top = backend_top[f"commit_rob{slot}"]
        commit_pc_top = backend_top[f"commit_pc{slot}"]
        trace_choose_event_top(
            valid_top=trace_commit_edge_top[slot],
            stage_id_top=trace_sid_cmt_top,
            lane_top=c(slot, width=3),
            rob_top=commit_rob_top,
            seq_top=trace_lookup_seq_top(commit_rob_top),
            pc_top=commit_pc_top,
            kind_top=trace_kind_normal_top,
        )
        trace_choose_event_top(
            valid_top=backend_top[f"commit_trap_valid{slot}"],
            stage_id_top=trace_sid_cmt_top,
            lane_top=c(slot, width=3),
            rob_top=commit_rob_top,
            seq_top=trace_lookup_seq_top(commit_rob_top),
            pc_top=commit_pc_top,
            kind_top=trace_kind_trap_top,
        )
    trace_choose_event_top(
        valid_top=flush_valid_fls,
        stage_id_top=trace_sid_fls_top,
        lane_top=c(0, width=3),
        rob_top=c(0, width=6),
        seq_top=trace_front_seq_top,
        pc_top=flush_pc_fls,
        kind_top=trace_kind_flush_top,
    )
    trace_choose_event_top(
        valid_top=~backend_top["replay_cause"].eq(c(0, width=8)),
        stage_id_top=trace_sid_fls_top,
        lane_top=c(0, width=3),
        rob_top=c(0, width=6),
        seq_top=trace_front_seq_top,
        pc_top=flush_pc_fls,
        kind_top=trace_kind_replay_top,
    )

    # One-time stage legend emission (fills stage list in LinxTrace even when a
    # given workload does not naturally hit every block-fabric stage).
    trace_boot_stage_top = m.out(
        "trace_boot_stage_top",
        clk=clk_top,
        rst=rst_top,
        width=6,
        init=c(0, width=6),
        en=c(1, width=1),
    )
    trace_boot_active_top = m.out(
        "trace_boot_active_top",
        clk=clk_top,
        rst=rst_top,
        width=1,
        init=c(1, width=1),
        en=c(1, width=1),
    )
    trace_boot_fire_top = trace_boot_active_top.out() & (~trace_evt_valid_top)
    trace_boot_kind_top = trace_boot_stage_top.out().eq(trace_sid_fls_top)._select_internal(trace_kind_flush_top, trace_kind_normal_top)
    trace_evt_valid_top = trace_boot_fire_top._select_internal(c(1, width=1), trace_evt_valid_top)
    trace_evt_stage_id_top = trace_boot_fire_top._select_internal(trace_boot_stage_top.out(), trace_evt_stage_id_top)
    trace_evt_lane_top = trace_boot_fire_top._select_internal(c(0, width=3), trace_evt_lane_top)
    trace_evt_rob_top = trace_boot_fire_top._select_internal(c(0, width=6), trace_evt_rob_top)
    trace_evt_seq_top = trace_boot_fire_top._select_internal(c(0, width=32), trace_evt_seq_top)
    trace_evt_pc_top = trace_boot_fire_top._select_internal(boot_pc_top, trace_evt_pc_top)
    trace_evt_kind_top = trace_boot_fire_top._select_internal(trace_boot_kind_top, trace_evt_kind_top)

    trace_boot_end_top = trace_boot_fire_top & trace_boot_stage_top.out().eq(trace_sid_fls_top)
    trace_boot_stage_next_top = trace_boot_stage_top.out()
    trace_boot_stage_next_top = trace_boot_fire_top._select_internal(trace_boot_stage_top.out() + c(1, width=6), trace_boot_stage_next_top)
    trace_boot_stage_next_top = trace_boot_end_top._select_internal(trace_boot_stage_top.out(), trace_boot_stage_next_top)
    trace_boot_active_next_top = trace_boot_active_top.out()
    trace_boot_active_next_top = trace_boot_end_top._select_internal(c(0, width=1), trace_boot_active_next_top)
    trace_boot_stage_top.set(trace_boot_stage_next_top)
    trace_boot_active_top.set(trace_boot_active_next_top)

    m.output("trace_evt_valid_top", trace_evt_valid_top)
    m.output("trace_evt_stage_id_top", trace_evt_stage_id_top)
    m.output("trace_evt_lane_top", trace_evt_lane_top)
    m.output("trace_evt_rob_top", trace_evt_rob_top)
    m.output("trace_evt_seq_top", trace_evt_seq_top)
    m.output("trace_evt_pc_top", trace_evt_pc_top)
    m.output("trace_evt_kind_top", trace_evt_kind_top)

    # DFX pipeview occupancy probes: per-stage/lane cycle-residency stream.
    dfx_kind_normal_top = c(0, width=3)
    dfx_kind_flush_top = c(1, width=3)
    dfx_kind_trap_top = c(2, width=3)
    dfx_kind_replay_top = c(3, width=3)
    dfx_kind_template_top = c(4, width=3)

    def emit_occ_debug(
        stage_top: str,
        lane_top: int,
        valid_top,
        uid_top,
        pc_top,
        rob_top,
        kind_top,
        parent_uid_top,
        block_uid_top=None,
        core_id_top=None,
        stall_top=None,
        stall_cause_top=None,
    ):
        m.debug_occ(
            stage_top,
            lane_top,
            {
                "valid": valid_top,
                "uop_uid": uid_top,
                "pc": pc_top,
                "rob": rob_top,
                "kind": kind_top,
                "parent_uid": parent_uid_top,
                "block_uid": c(0, width=64) if block_uid_top is None else block_uid_top,
                "core_id": c(0, width=2) if core_id_top is None else core_id_top,
                "stall": c(0, width=1) if stall_top is None else stall_top,
                "stall_cause": c(0, width=8) if stall_cause_top is None else stall_cause_top,
            },
        )

    # Frontend stage-residency UID namespace.
    # Keep fetch packet identity in high bits, and encode stage in low bits so
    # F0/F1/F2/F3/IB/F4 occupancy does not collapse into one sample per cycle.
    def frontend_uid(pkt_uid_top, stage_tag_top: int):
        return (pkt_uid_top.shl(amount=6)) | c(stage_tag_top & 0x3F, width=64)

    f0_uid_top = frontend_uid(ifu_f0["f0_to_f1_stage_pkt_uid_f0"], 1)
    f1_uid_top = frontend_uid(ifu_f1["f1_to_icache_stage_pkt_uid_f1"], 2)
    f2_uid_top = frontend_uid(ifu_f2["f2_to_f3_stage_pkt_uid_f2"], 3)
    f3_uid_top = frontend_uid(ifu_f3["f3_to_f4_stage_pkt_uid_f3"], 4)
    ib_uid_top = frontend_uid(ifu_f3["ib_head_uid_f3"], 5)
    f4_uid_top = frontend_uid(ifu_f4["f4_to_d1_stage_pkt_uid_f4"], 6)
    emit_occ_debug(
        "f0",
        0,
        ifu_f0["f0_to_f1_stage_valid_f0"],
        f0_uid_top,
        ifu_f0["f0_to_f1_stage_pc_f0"],
        c(0, width=6),
        dfx_kind_normal_top,
        c(0, width=64),
    )
    emit_occ_debug(
        "f1",
        0,
        ifu_f1["f1_to_icache_stage_valid_f1"],
        f1_uid_top,
        ifu_f1["f1_to_icache_stage_pc_f1"],
        c(0, width=6),
        dfx_kind_normal_top,
        c(0, width=64),
    )
    emit_occ_debug(
        "f2",
        0,
        ifu_f2["f2_to_f3_stage_valid_f2"],
        f2_uid_top,
        ifu_f2["f2_to_f3_stage_pc_f2"],
        c(0, width=6),
        dfx_kind_normal_top,
        c(0, width=64),
    )
    emit_occ_debug(
        "f3",
        0,
        ifu_f3["f3_to_f4_stage_valid_f3"],
        f3_uid_top,
        ifu_f3["f3_to_f4_stage_pc_f3"],
        c(0, width=6),
        dfx_kind_normal_top,
        c(0, width=64),
    )
    emit_occ_debug(
        "ib",
        0,
        ifu_f3["ib_valid_f3"],
        ib_uid_top,
        ifu_f3["ib_head_pc_f3"],
        c(0, width=6),
        dfx_kind_normal_top,
        c(0, width=64),
        stall_top=ifu_f3["ib_valid_f3"] & (~backend_ready_top),
        stall_cause_top=(ifu_f3["ib_valid_f3"] & (~backend_ready_top))._select_internal(c(1, width=8), c(0, width=8)),
    )
    emit_occ_debug(
        "f4",
        0,
        ifu_f4["f4_to_d1_stage_valid_f4"],
        f4_uid_top,
        ifu_f4["f4_to_d1_stage_pc_f4"],
        c(0, width=6),
        dfx_kind_normal_top,
        c(0, width=64),
    )
    emit_occ_debug(
        "d1",
        0,
        dec1_d1["d1_to_d2_stage_valid_d1"],
        dec1_d1["d1_to_d2_stage_uop_uid_d1"],
        dec1_d1["d1_to_d2_stage_pc_d1"],
        c(0, width=6),
        dfx_kind_normal_top,
        c(0, width=64),
    )
    emit_occ_debug(
        "d2",
        0,
        dec2_d2["d2_to_d3_stage_valid_d2"],
        dec2_d2["d2_to_d3_stage_uop_uid_d2"],
        dec2_d2["d2_to_d3_stage_pc_d2"],
        c(0, width=6),
        dfx_kind_normal_top,
        c(0, width=64),
    )
    emit_occ_debug(
        "s1",
        0,
        s1_s1["s1_to_s2_stage_valid_s1"],
        s1_s1["s1_to_s2_stage_uop_uid_s1"],
        s1_s1["s1_to_s2_stage_pc_s1"],
        c(0, width=6),
        dfx_kind_normal_top,
        c(0, width=64),
    )
    emit_occ_debug(
        "s2",
        0,
        s2_s2["s2_to_iex_stage_valid_s2"],
        s2_s2["s2_to_iex_stage_uop_uid_s2"],
        s2_s2["s2_to_iex_stage_pc_s2"],
        c(0, width=6),
        dfx_kind_normal_top,
        c(0, width=64),
    )

    for slot in range(4):
        d_uid = backend_top[f"dispatch_uop_uid{slot}"]
        d_puid = backend_top[f"dispatch_parent_uop_uid{slot}"]
        emit_occ_debug(
            "d3",
            slot,
            trace_dispatch_edge_top[slot],
            d_uid,
            trace_dispatch_pc_top[slot],
            trace_dispatch_rob_top[slot],
            dfx_kind_normal_top,
            d_puid,
            backend_top[f"dispatch_block_uid{slot}"],
            c(0, width=2),
        )
        emit_occ_debug(
            "iq",
            slot,
            backend_top[f"iq_valid{slot}"],
            backend_top[f"iq_uop_uid{slot}"],
            backend_top[f"iq_pc{slot}"],
            backend_top[f"iq_rob{slot}"],
            dfx_kind_normal_top,
            backend_top[f"iq_parent_uop_uid{slot}"],
            backend_top[f"iq_block_uid{slot}"],
            c(0, width=2),
        )
        i_uid = backend_top[f"issue_uop_uid{slot}"]
        i_puid = backend_top[f"issue_parent_uop_uid{slot}"]
        emit_occ_debug(
            "p1",
            slot,
            trace_issue_edge_top[slot],
            i_uid,
            backend_top[f"issue_pc{slot}"],
            backend_top[f"issue_rob{slot}"],
            dfx_kind_normal_top,
            i_puid,
            backend_top[f"issue_block_uid{slot}"],
            c(0, width=2),
        )
        emit_occ_debug(
            "i1",
            slot,
            trace_issue_fire_d1_top[slot],
            i_uid,
            trace_issue_pc_d1_top[slot],
            trace_issue_rob_d1_top[slot],
            dfx_kind_normal_top,
            i_puid,
            backend_top[f"issue_block_uid{slot}"],
            c(0, width=2),
        )
        emit_occ_debug(
            "i2",
            slot,
            trace_issue_fire_d2_top[slot],
            i_uid,
            trace_issue_pc_d2_top[slot],
            trace_issue_rob_d2_top[slot],
            dfx_kind_normal_top,
            i_puid,
            backend_top[f"issue_block_uid{slot}"],
            c(0, width=2),
        )
        c_uid = backend_top[f"commit_uop_uid{slot}"]
        c_puid = backend_top[f"commit_parent_uop_uid{slot}"]
        c_is_template = ~backend_top[f"commit_template_kind{slot}"].eq(c(0, width=3))
        c_kind = c_is_template._select_internal(dfx_kind_template_top, dfx_kind_normal_top)
        c_kind = backend_top[f"commit_trap_valid{slot}"]._select_internal(dfx_kind_trap_top, c_kind)
        emit_occ_debug(
            "rob",
            slot,
            trace_commit_edge_top[slot],
            c_uid,
            backend_top[f"commit_pc{slot}"],
            backend_top[f"commit_rob{slot}"],
            c_kind,
            c_puid,
            backend_top[f"commit_block_uid{slot}"],
            backend_top[f"commit_core_id{slot}"],
        )
        emit_occ_debug(
            "w1",
            slot,
            trace_commit_wb_edge_top[slot],
            c_uid,
            backend_top[f"commit_pc{slot}"],
            backend_top[f"commit_rob{slot}"],
            c_kind,
            c_puid,
            backend_top[f"commit_block_uid{slot}"],
            backend_top[f"commit_core_id{slot}"],
        )
        emit_occ_debug(
            "w2",
            slot,
            trace_commit_edge_top[slot],
            c_uid,
            backend_top[f"commit_pc{slot}"],
            backend_top[f"commit_rob{slot}"],
            c_kind,
            c_puid,
            backend_top[f"commit_block_uid{slot}"],
            backend_top[f"commit_core_id{slot}"],
        )
        emit_occ_debug(
            "cmt",
            slot,
            trace_commit_edge_top[slot],
            c_uid,
            backend_top[f"commit_pc{slot}"],
            backend_top[f"commit_rob{slot}"],
            c_kind,
            c_puid,
            backend_top[f"commit_block_uid{slot}"],
            backend_top[f"commit_core_id{slot}"],
        )

    e1_uid_top = trace_lookup_uid_top(e1_rob_top)
    emit_occ_debug("e1", 0, e1_valid_top, e1_uid_top, e1_pc_top, e1_rob_top, dfx_kind_normal_top, c(0, width=64))
    e2_rob_top = l1d_top["lsu_to_rob_stage_load_rob_l1d"]
    emit_occ_debug("e2", 0, l1d_top["lsu_to_rob_stage_load_valid_l1d"], trace_lookup_uid_top(e2_rob_top), pcbuf_top["rd_pc_pcb"], e2_rob_top, dfx_kind_normal_top, c(0, width=64))
    e3_rob_top = lhq_top["lhq_rob_lhq"]
    emit_occ_debug("e3", 0, lhq_top["lhq_fire_lhq"], trace_lookup_uid_top(e3_rob_top), pcbuf_top["rd_pc_pcb"], e3_rob_top, dfx_kind_normal_top, c(0, width=64))
    emit_occ_debug("e4", 0, mdb_top["lsu_violation_detected_mdb"], c(0, width=64), pcbuf_top["rd_pc_pcb"], c(0, width=6), dfx_kind_replay_top, c(0, width=64))

    liq_rob_top = iex_top["iex_to_rob_stage_load_rob_e1"]
    emit_occ_debug("liq", 0, liq_top["liq_enq_fire_liq"], trace_lookup_uid_top(liq_rob_top), pcbuf_top["rd_pc_pcb"], liq_rob_top, dfx_kind_normal_top, c(0, width=64))
    emit_occ_debug("lhq", 0, lhq_top["lhq_fire_lhq"], trace_lookup_uid_top(e3_rob_top), pcbuf_top["rd_pc_pcb"], e3_rob_top, dfx_kind_normal_top, c(0, width=64))
    stq_rob_top = iex_top["iex_to_rob_stage_store_rob_e1"]
    emit_occ_debug("stq", 0, stq_top["stq_enq_fire_stq"], trace_lookup_uid_top(stq_rob_top), pcbuf_top["rd_pc_pcb"], stq_rob_top, dfx_kind_normal_top, c(0, width=64))
    scb_rob_top = stq_top["stq_head_store_rob_stq"]
    emit_occ_debug("scb", 0, scb_top["dmem_wvalid_scb"], trace_lookup_uid_top(scb_rob_top), pcbuf_top["rd_pc_pcb"], scb_rob_top, dfx_kind_normal_top, c(0, width=64))
    emit_occ_debug("mdb", 0, mdb_top["lsu_conflict_seen_mdb"] | mdb_top["lsu_violation_detected_mdb"], c(0, width=64), pcbuf_top["rd_pc_pcb"], c(0, width=6), dfx_kind_replay_top, c(0, width=64))
    emit_occ_debug("l1d", 0, l1d_top["lsu_to_rob_stage_load_valid_l1d"], trace_lookup_uid_top(e2_rob_top), pcbuf_top["rd_pc_pcb"], e2_rob_top, dfx_kind_normal_top, c(0, width=64))

    bisq_rob_top = bisq_top["bisq_head_rob_bisq"]
    emit_occ_debug("bisq", 0, bisq_top["bisq_deq_fire_bisq"], trace_lookup_uid_top(bisq_rob_top), pcbuf_top["rd_pc_pcb"], bisq_rob_top, dfx_kind_normal_top, c(0, width=64))
    bctrl_rob_top = bctrl_top["issue_src_rob_brob"]
    emit_occ_debug("bctrl", 0, bctrl_top["issue_fire_brob"], trace_lookup_uid_top(bctrl_rob_top), pcbuf_top["rd_pc_pcb"], bctrl_rob_top, dfx_kind_normal_top, c(0, width=64))
    emit_occ_debug("tmu", 0, tmu_noc_top["out_valid_noc"], trace_lookup_uid_top(bisq_rob_top), pcbuf_top["rd_pc_pcb"], bisq_rob_top, dfx_kind_normal_top, c(0, width=64))
    emit_occ_debug("tma", 0, bctrl_top["cmd_tma_valid_bctrl"], trace_lookup_uid_top(bctrl_top["bctrl_to_pe_stage_cmd_src_rob_bctrl"]), pcbuf_top["rd_pc_pcb"], bctrl_top["bctrl_to_pe_stage_cmd_src_rob_bctrl"], dfx_kind_normal_top, c(0, width=64))
    emit_occ_debug("cube", 0, bctrl_top["cmd_cube_valid_bctrl"], trace_lookup_uid_top(bctrl_top["bctrl_to_pe_stage_cmd_src_rob_bctrl"]), pcbuf_top["rd_pc_pcb"], bctrl_top["bctrl_to_pe_stage_cmd_src_rob_bctrl"], dfx_kind_normal_top, c(0, width=64))
    emit_occ_debug("vec", 0, bctrl_top["cmd_vec_valid_bctrl"], trace_lookup_uid_top(bctrl_top["bctrl_to_pe_stage_cmd_src_rob_bctrl"]), pcbuf_top["rd_pc_pcb"], bctrl_top["bctrl_to_pe_stage_cmd_src_rob_bctrl"], dfx_kind_normal_top, c(0, width=64))
    emit_occ_debug("tau", 0, bctrl_top["cmd_tau_valid_bctrl"], trace_lookup_uid_top(bctrl_top["bctrl_to_pe_stage_cmd_src_rob_bctrl"]), pcbuf_top["rd_pc_pcb"], bctrl_top["bctrl_to_pe_stage_cmd_src_rob_bctrl"], dfx_kind_normal_top, c(0, width=64))
    brob_rob_top = brob_top["brob_to_rob_stage_rsp_src_rob_brob"]
    emit_occ_debug("brob", 0, brob_top["brob_rsp_fire_brob"], trace_lookup_uid_top(brob_rob_top), pcbuf_top["rd_pc_pcb"], brob_rob_top, dfx_kind_normal_top, c(0, width=64))
    emit_occ_debug("fls", 0, flush_valid_fls | (~backend_top["replay_cause"].eq(c(0, width=8))), c(0, width=64), flush_pc_fls, c(0, width=6), dfx_kind_flush_top, c(0, width=64))

    # Branch-control specific probes:
    # - BRU validates setc.cond and records correction intent.
    # - ROB applies correction at boundary-commit.
    # - FLS reports final redirect cause.
    bru_kind_top = backend_top["bru_mismatch_dbg"]._select_internal(dfx_kind_replay_top, dfx_kind_normal_top)
    emit_occ_debug(
        "BRU",
        0,
        backend_top["bru_validate_fire_dbg"],
        backend_top["issue_uop_uid1"],
        backend_top["issue_pc1"],
        backend_top["issue_rob1"],
        bru_kind_top,
        backend_top["issue_parent_uop_uid1"],
    )
    rob_branch_kind_top = backend_top["redirect_from_corr_dbg"]._select_internal(dfx_kind_replay_top, dfx_kind_normal_top)
    emit_occ_debug(
        "ROB",
        4,
        backend_top["redirect_valid"],
        backend_top["commit_uop_uid0"],
        backend_top["commit_pc0"],
        backend_top["commit_rob0"],
        rob_branch_kind_top,
        backend_top["commit_parent_uop_uid0"],
    )
    fls_kind_top = backend_top["bru_fault_set_dbg"]._select_internal(
        dfx_kind_trap_top,
        backend_top["redirect_from_corr_dbg"]._select_internal(dfx_kind_replay_top, dfx_kind_flush_top),
    )
    emit_occ_debug(
        "FLS",
        1,
        backend_top["redirect_valid"] | backend_top["bru_fault_set_dbg"],
        c(0, width=64),
        backend_top["redirect_pc"],
        c(0, width=6),
        fls_kind_top,
        c(0, width=64),
    )

    # Template micro-uop events are exposed as explicit E1 template-kind probes.
    emit_occ_debug(
        "e1",
        4,
        backend_top["ctu_uop_valid"],
        backend_top["ctu_uop_uid"],
        backend_top["pc"],
        c(0, width=6),
        dfx_kind_template_top,
        backend_top["ctu_uop_parent_uid"],
    )

    # Export canonical backend-compatible ports.
    export_ports = [
        "cycles",
        "halted",
        "pc",
        "fpc",
        "a0",
        "a1",
        "ra",
        "sp",
        "mmio_uart_valid",
        "mmio_uart_data",
        "mmio_exit_valid",
        "mmio_exit_code",
        "dispatch_fire",
        "dec_op",
        "issue_fire",
        "issue_op",
        "issue_pc",
        "issue_rob",
        "issue_sl",
        "issue_sr",
        "issue_sp",
        "issue_pdst",
        "issue_sl_val",
        "issue_sr_val",
        "issue_sp_val",
        "issue_is_load",
        "issue_is_store",
        "store_pending",
        "store_pending_older",
        "mem_raddr",
        "dmem_raddr",
        "dmem_wvalid",
        "dmem_waddr",
        "dmem_wdata",
        "dmem_wstrb",
        "dmem_wsrc",
        "stbuf_enq_fire",
        "stbuf_drain_fire",
        "macro_store_fire_dbg",
        "commit_store_wt_fire_dbg",
        "ooo_4wide",
        "block_cmd_valid",
        "block_cmd_kind",
        "block_cmd_payload",
        "block_cmd_tile",
        "block_cmd_tag",
        "rob_count",
        "rob_head_valid",
        "rob_head_done",
        "rob_head_pc",
        "rob_head_insn_raw",
        "rob_head_len",
        "rob_head_op",
        "ctu_block_ifu",
        "ctu_uop_valid",
        "ctu_uop_kind",
        "ctu_uop_reg",
        "ctu_uop_addr",
        "ctu_uop_uid",
        "ctu_uop_parent_uid",
        "ctu_uop_template_kind",
        "head_wait_hit",
        "head_wait_kind",
        "head_wait_sl",
        "head_wait_sr",
        "head_wait_sp",
        "head_wait_sl_rdy",
        "head_wait_sr_rdy",
        "head_wait_sp_rdy",
        "redirect_checkpoint_id",
        "wakeup_reason",
        "replay_cause",
        "bru_validate_fire_dbg",
        "bru_mismatch_dbg",
        "bru_actual_take_dbg",
        "bru_pred_take_dbg",
        "bru_boundary_pc_dbg",
        "bru_corr_set_dbg",
        "bru_corr_pending_dbg",
        "bru_corr_target_dbg",
        "bru_corr_epoch_dbg",
        "br_epoch_dbg",
        "br_kind_dbg",
        "redirect_from_corr_dbg",
        "redirect_from_boundary_dbg",
        "bru_fault_set_dbg",
    ]
    for name_top in export_ports:
        m.output(name_top, backend_top[name_top])

    for slot in range(4):
        m.output(f"dispatch_fire{slot}", backend_top[f"dispatch_fire{slot}"])
        m.output(f"dispatch_pc{slot}", backend_top[f"dispatch_pc{slot}"])
        m.output(f"dispatch_rob{slot}", backend_top[f"dispatch_rob{slot}"])
        m.output(f"dispatch_op{slot}", backend_top[f"dispatch_op{slot}"])
        m.output(f"dispatch_uop_uid{slot}", backend_top[f"dispatch_uop_uid{slot}"])
        m.output(f"dispatch_parent_uop_uid{slot}", backend_top[f"dispatch_parent_uop_uid{slot}"])
        m.output(f"dispatch_block_uid{slot}", backend_top[f"dispatch_block_uid{slot}"])
        m.output(f"dispatch_block_bid{slot}", backend_top[f"dispatch_block_bid{slot}"])
        m.output(f"dispatch_load_store_id{slot}", backend_top[f"dispatch_load_store_id{slot}"])
        m.output(f"issue_fire{slot}", backend_top[f"issue_fire{slot}"])
        m.output(f"issue_pc{slot}", backend_top[f"issue_pc{slot}"])
        m.output(f"issue_rob{slot}", backend_top[f"issue_rob{slot}"])
        m.output(f"issue_op{slot}", backend_top[f"issue_op{slot}"])
        m.output(f"issue_uop_uid{slot}", backend_top[f"issue_uop_uid{slot}"])
        m.output(f"issue_parent_uop_uid{slot}", backend_top[f"issue_parent_uop_uid{slot}"])
        m.output(f"issue_block_uid{slot}", backend_top[f"issue_block_uid{slot}"])
        m.output(f"issue_block_bid{slot}", backend_top[f"issue_block_bid{slot}"])
        m.output(f"issue_load_store_id{slot}", backend_top[f"issue_load_store_id{slot}"])
        m.output(f"commit_fire{slot}", backend_top[f"commit_fire{slot}"])
        m.output(f"commit_pc{slot}", backend_top[f"commit_pc{slot}"])
        m.output(f"commit_rob{slot}", backend_top[f"commit_rob{slot}"])
        m.output(f"commit_op{slot}", backend_top[f"commit_op{slot}"])
        m.output(f"commit_uop_uid{slot}", backend_top[f"commit_uop_uid{slot}"])
        m.output(f"commit_parent_uop_uid{slot}", backend_top[f"commit_parent_uop_uid{slot}"])
        m.output(f"commit_block_uid{slot}", backend_top[f"commit_block_uid{slot}"])
        m.output(f"commit_block_bid{slot}", backend_top[f"commit_block_bid{slot}"])
        m.output(f"commit_core_id{slot}", backend_top[f"commit_core_id{slot}"])
        m.output(f"commit_is_bstart{slot}", backend_top[f"commit_is_bstart{slot}"])
        m.output(f"commit_is_bstop{slot}", backend_top[f"commit_is_bstop{slot}"])
        m.output(f"commit_load_store_id{slot}", backend_top[f"commit_load_store_id{slot}"])
        m.output(f"commit_template_kind{slot}", backend_top[f"commit_template_kind{slot}"])
        m.output(f"commit_value{slot}", backend_top[f"commit_value{slot}"])
        m.output(f"commit_len{slot}", backend_top[f"commit_len{slot}"])
        m.output(f"commit_insn_raw{slot}", backend_top[f"commit_insn_raw{slot}"])
        m.output(f"commit_wb_valid{slot}", backend_top[f"commit_wb_valid{slot}"])
        m.output(f"commit_wb_rd{slot}", backend_top[f"commit_wb_rd{slot}"])
        m.output(f"commit_wb_data{slot}", backend_top[f"commit_wb_data{slot}"])
        m.output(f"commit_src0_valid{slot}", backend_top[f"commit_src0_valid{slot}"])
        m.output(f"commit_src0_reg{slot}", backend_top[f"commit_src0_reg{slot}"])
        m.output(f"commit_src0_data{slot}", backend_top[f"commit_src0_data{slot}"])
        m.output(f"commit_src1_valid{slot}", backend_top[f"commit_src1_valid{slot}"])
        m.output(f"commit_src1_reg{slot}", backend_top[f"commit_src1_reg{slot}"])
        m.output(f"commit_src1_data{slot}", backend_top[f"commit_src1_data{slot}"])
        m.output(f"commit_dst_valid{slot}", backend_top[f"commit_dst_valid{slot}"])
        m.output(f"commit_dst_reg{slot}", backend_top[f"commit_dst_reg{slot}"])
        m.output(f"commit_dst_data{slot}", backend_top[f"commit_dst_data{slot}"])
        m.output(f"commit_mem_valid{slot}", backend_top[f"commit_mem_valid{slot}"])
        m.output(f"commit_mem_is_store{slot}", backend_top[f"commit_mem_is_store{slot}"])
        m.output(f"commit_mem_addr{slot}", backend_top[f"commit_mem_addr{slot}"])
        m.output(f"commit_mem_wdata{slot}", backend_top[f"commit_mem_wdata{slot}"])
        m.output(f"commit_mem_rdata{slot}", backend_top[f"commit_mem_rdata{slot}"])
        m.output(f"commit_mem_size{slot}", backend_top[f"commit_mem_size{slot}"])
        m.output(f"commit_trap_valid{slot}", backend_top[f"commit_trap_valid{slot}"])
        m.output(f"commit_trap_cause{slot}", backend_top[f"commit_trap_cause{slot}"])
        m.output(f"commit_next_pc{slot}", backend_top[f"commit_next_pc{slot}"])
        m.output(f"commit_checkpoint_id{slot}", backend_top[f"commit_checkpoint_id{slot}"])
        m.output(f"commit_seq{slot}", trace_lookup_seq_top(backend_top[f"commit_rob{slot}"]))

    blk_evt_valid_top = c(0, width=1)
    blk_evt_kind_top = c(0, width=3)  # 1=open, 2=close, 3=redirect, 4=fault
    blk_evt_block_uid_top = c(0, width=64)
    blk_evt_core_id_top = c(0, width=2)
    blk_evt_block_bid_top = c(0, width=64)
    blk_evt_pc_top = c(0, width=64)
    blk_evt_seq_top = c(0, width=32)
    for slot in range(4):
        commit_fire_slot = backend_top[f"commit_fire{slot}"]
        is_open_slot = commit_fire_slot & backend_top[f"commit_is_bstart{slot}"]
        is_close_slot = commit_fire_slot & backend_top[f"commit_is_bstop{slot}"]
        slot_evt = is_open_slot | is_close_slot
        take_evt = slot_evt & (~blk_evt_valid_top)
        slot_kind = is_open_slot._select_internal(c(1, width=3), c(2, width=3))
        blk_evt_valid_top = take_evt._select_internal(c(1, width=1), blk_evt_valid_top)
        blk_evt_kind_top = take_evt._select_internal(slot_kind, blk_evt_kind_top)
        blk_evt_block_uid_top = take_evt._select_internal(backend_top[f"commit_block_uid{slot}"], blk_evt_block_uid_top)
        blk_evt_block_bid_top = take_evt._select_internal(backend_top[f"commit_block_bid{slot}"], blk_evt_block_bid_top)
        blk_evt_core_id_top = take_evt._select_internal(backend_top[f"commit_core_id{slot}"], blk_evt_core_id_top)
        blk_evt_pc_top = take_evt._select_internal(backend_top[f"commit_pc{slot}"], blk_evt_pc_top)
        blk_evt_seq_top = take_evt._select_internal(trace_lookup_seq_top(backend_top[f"commit_rob{slot}"]), blk_evt_seq_top)
    redir_evt_top = backend_top["redirect_valid"] & (~blk_evt_valid_top)
    blk_evt_valid_top = redir_evt_top._select_internal(c(1, width=1), blk_evt_valid_top)
    blk_evt_kind_top = redir_evt_top._select_internal(c(3, width=3), blk_evt_kind_top)
    blk_evt_pc_top = redir_evt_top._select_internal(backend_top["redirect_pc"], blk_evt_pc_top)
    fault_evt_top = backend_top["bru_fault_set_dbg"] & (~blk_evt_valid_top)
    blk_evt_valid_top = fault_evt_top._select_internal(c(1, width=1), blk_evt_valid_top)
    blk_evt_kind_top = fault_evt_top._select_internal(c(4, width=3), blk_evt_kind_top)

    m.output("dbg__blk_evt_valid_top", blk_evt_valid_top)
    m.output("dbg__blk_evt_kind_top", blk_evt_kind_top)
    m.output("dbg__blk_evt_block_uid_top", blk_evt_block_uid_top)
    m.output("dbg__blk_evt_block_bid_top", blk_evt_block_bid_top)
    m.output("dbg__blk_evt_core_id_top", blk_evt_core_id_top)
    m.output("dbg__blk_evt_pc_top", blk_evt_pc_top)
    m.output("dbg__blk_evt_seq_top", blk_evt_seq_top)

    m.output("ftq_head", c(0, width=4))
    m.output("ftq_tail", c(0, width=4))
    m.output("ftq_count", ifu_f3["f3_ibuf_count_f3"] | c(0, width=5))
    m.output("checkpoint_id", ifu_ctrl)

    # Additional Janus stage-map debug taps.
    m.output("janus_probe_commit_pc_rob", rob_top["commit_pc_rob"])
    m.output("janus_probe_rd_pc_pcb", pcbuf_top["rd_pc_pcb"])
    m.output("janus_probe_lsu_conflict_mdb", mdb_top["lsu_conflict_seen_mdb"])
    m.output("janus_probe_brob_pending", brob_top["brob_pending_brob"])


build_linxcore_top.__pycircuit_name__ = "linxcore_top"
