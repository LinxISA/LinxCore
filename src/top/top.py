from __future__ import annotations

from pycircuit import Circuit, module

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
def build_linxcore_top(m: Circuit, *, mem_bytes: int = (1 << 20)) -> None:
    clk_top = m.clock("clk")
    rst_top = m.reset("rst")

    boot_pc_top = m.input("boot_pc", width=64)
    boot_sp_top = m.input("boot_sp", width=64)
    boot_ra_top = m.input("boot_ra", width=64)

    host_wvalid_top = m.input("host_wvalid", width=1)
    host_waddr_top = m.input("host_waddr", width=64)
    host_wdata_top = m.input("host_wdata", width=64)
    host_wstrb_top = m.input("host_wstrb", width=8)

    c = m.const

    dmem_rdata_top = m.new_wire(width=64)
    imem_rdata_top = m.new_wire(width=64)

    flush_valid_fls = m.new_wire(width=1)
    flush_pc_fls = m.new_wire(width=64)
    f2_to_f0_advance_wire_f2 = m.new_wire(width=1)
    f2_to_f0_next_pc_wire_f2 = m.new_wire(width=64)
    f3_to_f2_ready_wire_f3 = m.new_wire(width=1)

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
    )

    ifu_f1 = m.instance(
        build_janus_bcc_ifu_f1,
        name="janus_ifu_f1",
        module_name="JanusBccIfuF1Top",
        f0_to_f1_stage_pc_f0=ifu_f0["f0_to_f1_stage_pc_f0"],
        f0_to_f1_stage_valid_f0=ifu_f0["f0_to_f1_stage_valid_f0"],
    )

    ifu_icache = m.instance(
        build_janus_bcc_ifu_icache,
        name="janus_ifu_icache",
        module_name="JanusBccIfuICacheTop",
        f1_to_icache_stage_pc_f1=ifu_f1["f1_to_icache_stage_pc_f1"],
        f1_to_icache_stage_valid_f1=ifu_f1["f1_to_icache_stage_valid_f1"],
        imem_rdata_top=imem_rdata_top,
    )

    ifu_f2 = m.instance(
        build_janus_bcc_ifu_f2,
        name="janus_ifu_f2",
        module_name="JanusBccIfuF2Top",
        f1_to_f2_stage_pc_f1=ifu_icache["f1_to_f2_stage_pc_f1"],
        f1_to_f2_stage_window_f1=ifu_icache["f1_to_f2_stage_window_f1"],
        f1_to_f2_stage_valid_f1=ifu_icache["f1_to_f2_stage_valid_f1"],
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

    ifu_f3 = m.instance(
        build_janus_bcc_ifu_f3,
        name="janus_ifu_f3",
        module_name="JanusBccIfuF3Top",
        params={"ibuf_depth": 8},
        clk=clk_top,
        rst=rst_top,
        f2_to_f3_stage_pc_f2=ifu_f2["f2_to_f3_stage_pc_f2"],
        f2_to_f3_stage_window_f2=ifu_f2["f2_to_f3_stage_window_f2"],
        f2_to_f3_stage_valid_f2=ifu_f2["f2_to_f3_stage_valid_f2"],
        ctrl_to_f3_stage_checkpoint_id_f3=ifu_ctrl,
        backend_ready_top=backend_ready_top,
        flush_valid_fls=flush_valid_fls,
    )
    m.assign(f3_to_f2_ready_wire_f3, ifu_f3["f3_ibuf_ready_f3"])

    ifu_f4 = m.instance(
        build_janus_bcc_ifu_f4,
        name="janus_ifu_f4",
        module_name="JanusBccIfuF4Top",
        f3_to_f4_stage_pc_f3=ifu_f3["f3_to_f4_stage_pc_f3"],
        f3_to_f4_stage_window_f3=ifu_f3["f3_to_f4_stage_window_f3"],
        f3_to_f4_stage_valid_f3=ifu_f3["f3_to_f4_stage_valid_f3"],
        f3_to_f4_stage_checkpoint_id_f3=ifu_f3["f3_to_f4_stage_checkpoint_id_f3"],
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
        dmem_rdata_i=dmem_rdata_top,
    )

    m.assign(backend_ready_top, backend_top["frontend_ready"])
    m.assign(flush_valid_fls, backend_top["redirect_valid"])
    m.assign(flush_pc_fls, backend_top["redirect_pc"])

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
    )

    pcb_lookup_hit_pcb = m.new_wire(width=1)
    pcb_lookup_target_pcb = m.new_wire(width=64)
    pcb_lookup_pred_take_pcb = m.new_wire(width=1)

    iex_top = m.instance(
        build_janus_bcc_iex,
        name="janus_iex",
        module_name="JanusBccIexTop",
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
        load_req_valid_l1d=iex_top["iex_to_rob_stage_load_valid_e1"],
        load_req_rob_l1d=iex_top["iex_to_rob_stage_load_rob_e1"],
        load_req_addr_l1d=iex_top["iex_to_rob_stage_load_addr_e1"],
        dmem_rdata_top=dmem_rdata_top,
    )
    scb_top = m.instance(
        build_janus_bcc_lsu_scb,
        name="janus_scb",
        module_name="JanusBccLsuScbTop",
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

    bisq_top = m.instance(
        build_janus_bcc_bctrl_bisq,
        name="janus_bisq",
        module_name="JanusBccBisqTop",
        params={"depth": 16, "idx_w": 4},
        clk=clk_top,
        rst=rst_top,
        enq_valid_bisq=backend_top["block_cmd_valid"],
        enq_kind_bisq=backend_top["block_cmd_kind"] | c(0, width=3),
        enq_payload_bisq=backend_top["block_cmd_payload"],
        enq_tile_bisq=backend_top["block_cmd_tile"],
        enq_rob_bisq=backend_top["commit_rob0"],
        deq_ready_bisq=deq_ready_bisq_wire,
    )
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
        in_valid_noc=bisq_top["bisq_head_valid_bisq"],
        in_data_noc=bisq_top["bisq_head_payload_bisq"],
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

    bctrl_top = m.instance(
        build_janus_bcc_bctrl,
        name="janus_bctrl",
        module_name="JanusBccBctrlTop",
        bisq_head_valid_bisq=bisq_top["bisq_head_valid_bisq"],
        bisq_head_kind_bisq=bisq_top["bisq_head_kind_bisq"],
        bisq_head_payload_bisq=tmu_rf_top,
        bisq_head_tile_bisq=bisq_top["bisq_head_tile_bisq"],
        bisq_head_rob_bisq=bisq_top["bisq_head_rob_bisq"],
        brenu_tag_brenu=brenu_top["brenu_tag_brenu"],
        brenu_epoch_brenu=brenu_top["brenu_epoch_brenu"],
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

    tma_top = m.instance(
        build_janus_tma,
        name="janus_tma",
        module_name="JanusTmaTop",
        cmd_valid_tma=bctrl_top["cmd_tma_valid_bctrl"],
        cmd_tag_tma=bctrl_top["bctrl_to_pe_stage_cmd_tag_bctrl"],
        cmd_payload_tma=bctrl_top["bctrl_to_pe_stage_cmd_payload_bctrl"],
    )
    cube_top = m.instance(
        build_janus_cube,
        name="janus_cube",
        module_name="JanusCubeTop",
        cmd_valid_cube=bctrl_top["cmd_cube_valid_bctrl"],
        cmd_tag_cube=bctrl_top["bctrl_to_pe_stage_cmd_tag_bctrl"],
        cmd_payload_cube=bctrl_top["bctrl_to_pe_stage_cmd_payload_bctrl"],
    )
    tau_top = m.instance(
        build_janus_tau,
        name="janus_tau",
        module_name="JanusTauTop",
        cmd_valid_tau=bctrl_top["cmd_tau_valid_bctrl"],
        cmd_tag_tau=bctrl_top["bctrl_to_pe_stage_cmd_tag_bctrl"],
        cmd_payload_tau=bctrl_top["bctrl_to_pe_stage_cmd_payload_bctrl"],
    )
    vec_top = m.instance(
        build_linxcore_vec,
        name="linxcore_vec",
        module_name="LinxCoreVecTop",
        cmd_valid_vec=bctrl_top["cmd_vec_valid_bctrl"],
        cmd_tag_vec=bctrl_top["bctrl_to_pe_stage_cmd_tag_bctrl"],
        cmd_payload_vec=bctrl_top["bctrl_to_pe_stage_cmd_payload_bctrl"],
    )

    m.assign(rsp_tma_valid_wire, tma_top["rsp_valid_tma"])
    m.assign(rsp_tma_tag_wire, tma_top["rsp_tag_tma"])
    m.assign(rsp_tma_status_wire, tma_top["rsp_status_tma"])
    m.assign(rsp_tma_data0_wire, tma_top["rsp_data0_tma"])
    m.assign(rsp_tma_data1_wire, tma_top["rsp_data1_tma"])

    m.assign(rsp_cube_valid_wire, cube_top["rsp_valid_cube"])
    m.assign(rsp_cube_tag_wire, cube_top["rsp_tag_cube"])
    m.assign(rsp_cube_status_wire, cube_top["rsp_status_cube"])
    m.assign(rsp_cube_data0_wire, cube_top["rsp_data0_cube"])
    m.assign(rsp_cube_data1_wire, cube_top["rsp_data1_cube"])

    m.assign(rsp_tau_valid_wire, tau_top["rsp_valid_tau"])
    m.assign(rsp_tau_tag_wire, tau_top["rsp_tag_tau"])
    m.assign(rsp_tau_status_wire, tau_top["rsp_status_tau"])
    m.assign(rsp_tau_data0_wire, tau_top["rsp_data0_tau"])
    m.assign(rsp_tau_data1_wire, tau_top["rsp_data1_tau"])

    m.assign(rsp_vec_valid_wire, vec_top["rsp_valid_vec"])
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
        rsp_valid_brob=bctrl_top["rsp_valid_brob"],
        rsp_tag_brob=bctrl_top["rsp_tag_brob"],
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
    ]
    for name_top in export_ports:
        m.output(name_top, backend_top[name_top])

    for slot in range(4):
        m.output(f"dispatch_fire{slot}", backend_top[f"dispatch_fire{slot}"])
        m.output(f"dispatch_pc{slot}", backend_top[f"dispatch_pc{slot}"])
        m.output(f"dispatch_rob{slot}", backend_top[f"dispatch_rob{slot}"])
        m.output(f"dispatch_op{slot}", backend_top[f"dispatch_op{slot}"])
        m.output(f"issue_fire{slot}", backend_top[f"issue_fire{slot}"])
        m.output(f"issue_pc{slot}", backend_top[f"issue_pc{slot}"])
        m.output(f"issue_rob{slot}", backend_top[f"issue_rob{slot}"])
        m.output(f"issue_op{slot}", backend_top[f"issue_op{slot}"])
        m.output(f"commit_fire{slot}", backend_top[f"commit_fire{slot}"])
        m.output(f"commit_pc{slot}", backend_top[f"commit_pc{slot}"])
        m.output(f"commit_rob{slot}", backend_top[f"commit_rob{slot}"])
        m.output(f"commit_op{slot}", backend_top[f"commit_op{slot}"])
        m.output(f"commit_value{slot}", backend_top[f"commit_value{slot}"])
        m.output(f"commit_len{slot}", backend_top[f"commit_len{slot}"])
        m.output(f"commit_insn_raw{slot}", backend_top[f"commit_insn_raw{slot}"])
        m.output(f"commit_wb_valid{slot}", backend_top[f"commit_wb_valid{slot}"])
        m.output(f"commit_wb_rd{slot}", backend_top[f"commit_wb_rd{slot}"])
        m.output(f"commit_wb_data{slot}", backend_top[f"commit_wb_data{slot}"])
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

    m.output("ftq_head", c(0, width=4))
    m.output("ftq_tail", c(0, width=4))
    m.output("ftq_count", ifu_f3["f3_ibuf_count_f3"] | c(0, width=5))
    m.output("checkpoint_id", ifu_ctrl)

    # Additional Janus stage-map debug taps.
    m.output("janus_probe_commit_pc_rob", rob_top["commit_pc_rob"])
    m.output("janus_probe_rd_pc_pcb", pcbuf_top["rd_pc_pcb"])
    m.output("janus_probe_lsu_conflict_mdb", mdb_top["lsu_conflict_seen_mdb"])
    m.output("janus_probe_brob_pending", brob_top)


build_linxcore_top.__pycircuit_name__ = "linxcore_top"
