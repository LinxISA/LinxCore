from __future__ import annotations

from pycircuit import Circuit, module

from common.config import top_config
from common.uid_allocator import build_uid_allocator
from bcc.backend.backend import build_backend
from mem.mem2r1w import build_mem2r1w
from bcc.bctrl.bctrl import build_janus_bcc_bctrl
from bcc.bctrl.bisq import build_janus_bcc_bctrl_bisq
from bcc.bctrl.brob import build_janus_bcc_bctrl_brob
from bcc.iex.iex import build_janus_bcc_iex
from bcc.lsu.dcache_stub import build_janus_bcc_lsu_dcache_stub
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
from .ib import build_ib
@module(name="LinxCoreTopExport", value_params={"callframe_size_i": "i64"})
def build_top_export(
    m: Circuit,
    *,
    # Default bring-up memory: enough to avoid destructive aliasing for typical
    # workloads with large .bss (CoreMark/Dhrystone reserve ~16MiB).
    mem_bytes: int = (1 << 26),
    ic_sets: int = 32,
    ic_ways: int = 4,
    ic_line_bytes: int = 64,
    ifetch_bundle_bits: int = 128,
    ifetch_bundle_bytes: int | None = None,
    ib_depth: int = 8,
    ic_miss_outstanding: int = 1,
    ic_enable: int = 1,
    callframe_size_i=0,
) -> None:
    cfg = top_config(
        m,
        mem_bytes=mem_bytes,
        ic_sets=ic_sets,
        ic_ways=ic_ways,
        ic_line_bytes=ic_line_bytes,
        ifetch_bundle_bits=ifetch_bundle_bits,
        ifetch_bundle_bytes=ifetch_bundle_bytes,
        ib_depth=ib_depth,
        ic_miss_outstanding=ic_miss_outstanding,
        ic_enable=ic_enable,
    )
    mem_bytes = int(cfg["mem_bytes"])
    ic_sets = int(cfg["ic_sets"])
    ic_ways = int(cfg["ic_ways"])
    ic_line_bytes = int(cfg["ic_line_bytes"])
    ifetch_bundle_bits = int(cfg["ifetch_bundle_bits"])
    ifetch_bundle_bytes = int(cfg["ifetch_bundle_bytes"])
    ib_depth = int(cfg["ib_depth"])
    ic_miss_outstanding = int(cfg["ic_miss_outstanding"])
    ic_enable = int(cfg["ic_enable"])

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

    # Optional IFU bypass for lockstep bring-up:
    # testbench can directly provide F4-stage packets from QEMU commit stream.
    tb_ifu_stub_enable_top = m.input("tb_ifu_stub_enable", width=1)
    tb_ifu_stub_valid_top = m.input("tb_ifu_stub_valid", width=1)
    tb_ifu_stub_pc_top = m.input("tb_ifu_stub_pc", width=64)
    tb_ifu_stub_window_top = m.input("tb_ifu_stub_window", width=64)
    tb_ifu_stub_checkpoint_top = m.input("tb_ifu_stub_checkpoint", width=6)
    tb_ifu_stub_pkt_uid_top = m.input("tb_ifu_stub_pkt_uid", width=64)

    c = m.const

    dmem_rdata_top = m.new_wire(width=64)

    flush_valid_fls = m.new_wire(width=1)
    flush_pc_fls = m.new_wire(width=64)
    uid_alloc_fetch_count_top = m.new_wire(width=3)
    uid_alloc_template_count_top = m.new_wire(width=2)
    uid_alloc_replay_count_top = m.new_wire(width=2)

    uid_alloc_top = m.instance_auto(
        build_uid_allocator,
        name="uid_allocator",
        module_name="LinxCoreUidAllocatorTop",
        clk=clk_top,
        rst=rst_top,
        fetch_alloc_count_i=uid_alloc_fetch_count_top,
        template_alloc_count_i=uid_alloc_template_count_top,
        replay_alloc_count_i=uid_alloc_replay_count_top,
    )

    # Hard-cut frontend: instruction supply is QEMU/host-fed into an on-chip IB.
    # IFU/ICache are removed from the functional design path.
    m.output("ic_l2_req_valid", c(0, width=1))
    m.output("ic_l2_req_addr", c(0, width=64))
    m.output("icache_miss_active_dbg", c(0, width=1))
    m.output("icache_miss_wait_dbg", c(0, width=1))
    m.output("icache_miss_phase_dbg", c(0, width=2))
    m.output("icache_miss_need0_dbg", c(0, width=1))
    m.output("icache_miss_need1_dbg", c(0, width=1))
    m.output("icache_f1_hit_dbg", c(0, width=1))
    m.output("icache_f1_miss_dbg", c(0, width=1))
    m.output("icache_f1_stall_dbg", c(0, width=1))
    m.output("icache_f1_valid_dbg", c(0, width=1))

    backend_ready_top = m.new_wire(width=1)
    # Handshake seen by the host/QEMU stub. Keep this as a direct IB signal to
    # avoid intermediate aliasing during aggressive lowering/folding.
    tb_ifu_stub_ready_top = m.new_wire(width=1)
    ib_flush_top = m.new_wire(width=1)
    bisq_enq_ready_wire = m.new_wire(width=1)
    brob_active_allocated_wire = m.new_wire(width=1)
    brob_active_ready_wire = m.new_wire(width=1)
    brob_active_exception_wire = m.new_wire(width=1)
    brob_active_retired_wire = m.new_wire(width=1)
    brob_alloc_ready_wire = m.new_wire(width=1)
    brob_alloc_bid_wire = m.new_wire(width=64)

    ib_top = m.instance_auto(
        build_ib,
        name="linxcore_ib",
        module_name="LinxCoreIbTop",
        params={"depth": 32},
        clk=clk_top,
        rst=rst_top,
        # Handshake contract: valid must be driven independently from ready.
        # The IB provides decoupling from backend stalls, so host/QEMU should
        # be allowed to enqueue whenever IB has space.
        push_valid=tb_ifu_stub_enable_top & tb_ifu_stub_valid_top,
        push_pc=tb_ifu_stub_pc_top,
        push_window=tb_ifu_stub_window_top,
        push_checkpoint=tb_ifu_stub_checkpoint_top,
        push_pkt_uid=tb_ifu_stub_pkt_uid_top,
        pop_ready=backend_ready_top,
        # Keep IB coherent with backend redirect semantics.
        flush=ib_flush_top,
    )

    f4_valid_in_top = ib_top["pop_valid"]
    f4_pc_in_top = ib_top["pop_pc"]
    f4_window_in_top = ib_top["pop_window"]
    f4_checkpoint_in_top = ib_top["pop_checkpoint"]
    f4_pkt_uid_in_top = ib_top["pop_pkt_uid"]

    backend_top = m.instance_auto(
        build_backend,
        name="janus_backend",
        module_name="JanusBccBackendTop",
        params={"mem_bytes": mem_bytes},
        clk=clk_top,
        rst=rst_top,
        boot_pc=boot_pc_top,
        boot_sp=boot_sp_top,
        boot_ra=boot_ra_top,
        f4_valid_i=f4_valid_in_top,
        f4_pc_i=f4_pc_in_top,
        f4_window_i=f4_window_in_top,
        f4_checkpoint_i=f4_checkpoint_in_top,
        f4_pkt_uid_i=f4_pkt_uid_in_top,
        dmem_rdata_i=dmem_rdata_top,
        bisq_enq_ready_i=bisq_enq_ready_wire,
        brob_active_allocated_i=brob_active_allocated_wire,
        brob_active_ready_i=brob_active_ready_wire,
        brob_active_exception_i=brob_active_exception_wire,
        brob_active_retired_i=brob_active_retired_wire,
        brob_alloc_ready_i=brob_alloc_ready_wire,
        brob_alloc_bid_i=brob_alloc_bid_wire,
        template_uid_i=uid_alloc_top["template_uid_base_o"],
        callframe_size_i=callframe_size_i,
    )

    m.assign(uid_alloc_template_count_top, backend_top["ctu_uop_valid"])
    m.assign(uid_alloc_replay_count_top, (~backend_top["replay_cause"].__eq__(c(0, width=8))))

    m.assign(backend_ready_top, backend_top["frontend_ready"])
    m.assign(ib_flush_top, backend_top["redirect_valid"])
    ib_push_ready_top = ib_top["push_ready"]
    # Acknowledge host/QEMU packets exactly when the IB can accept a push.
    m.assign(tb_ifu_stub_ready_top, ib_push_ready_top)
    m.output("tb_ifu_stub_ready", tb_ifu_stub_ready_top)
    m.output("ib_count_dbg_top", ib_top["count_dbg"])
    m.assign(flush_valid_fls, backend_top["redirect_valid"])
    m.assign(flush_pc_fls, backend_top["redirect_pc"])
    m.assign(uid_alloc_fetch_count_top, ib_top["pop_fire"] | flush_valid_fls)

    mem_top = m.instance_auto(
        build_mem2r1w,
        name="mem2r1w",
        module_name="JanusMem2R1WTop",
        params={"mem_bytes": mem_bytes},
        clk=clk_top,
        rst=rst_top,
        if_raddr=c(0, width=64),
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
    m.assign(dmem_rdata_top, mem_top["d_rdata"])

    # OOO stage-map probe pipeline.
    dec1_d1 = m.instance_auto(
        build_janus_bcc_ooo_dec1,
        name="janus_dec1",
        module_name="JanusBccOooDec1Top",
        f4_to_d1_stage_valid_f4=f4_valid_in_top,
        f4_to_d1_stage_pc_f4=f4_pc_in_top,
        f4_to_d1_stage_window_f4=f4_window_in_top,
        f4_to_d1_stage_checkpoint_id_f4=f4_checkpoint_in_top,
        f4_to_d1_stage_pkt_uid_f4=f4_pkt_uid_in_top,
    )
    dec2_d2 = m.instance_auto(
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
    ren_d3 = m.instance_auto(
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
    s1_s1 = m.instance_auto(
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
    s2_s2 = m.instance_auto(
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

    iex_top = m.instance_auto(
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

    rob_top = m.instance_auto(
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

    pcbuf_top = m.instance_auto(
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
        # Hard-cut: IFU/PC-buffer frontend metadata is removed; keep stage-map
        # probe modules buildable by tying this legacy IFU-only metadata off.
        f3_to_pcb_stage_bstart_valid_f3=c(0, width=1),
        f3_to_pcb_stage_bstart_pc_f3=c(0, width=64),
        f3_to_pcb_stage_bstart_kind_f3=c(0, width=3),
        f3_to_pcb_stage_bstart_target_f3=c(0, width=64),
        f3_to_pcb_stage_pred_take_f3=c(0, width=1),
        lookup_pc_pcb=s2_s2["s2_to_iex_stage_pc_s2"],
    )
    m.assign(pcb_lookup_hit_pcb, pcbuf_top["pcb_to_bru_stage_lookup_hit_pcb"])
    m.assign(pcb_lookup_target_pcb, pcbuf_top["pcb_to_bru_stage_lookup_target_pcb"])
    m.assign(pcb_lookup_pred_take_pcb, pcbuf_top["pcb_to_bru_stage_lookup_pred_take_pcb"])

    flush_top = m.instance_auto(
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

    renu_top = m.instance_auto(
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

    liq_top = m.instance_auto(
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
    stq_top = m.instance_auto(
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
    lhq_top = m.instance_auto(
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
    mdb_top = m.instance_auto(
        build_janus_bcc_lsu_mdb,
        name="janus_mdb",
        module_name="JanusBccLsuMdbTop",
        clk=clk_top,
        rst=rst_top,
        lhq_conflict_lhq=lhq_top["lhq_conflict_lhq"],
    )
    l1d_top = m.instance_auto(
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

    # SCB is currently a stage-map/DFX model (functional stores go through
    # backend_top["dmem_w*"]). Wire it up with the v4.0 SCB interface so the
    # module compiles and provides stable observation points.
    dcache_req_ready_top = m.new_wire(width=1)
    dcache_resp_valid_top = m.new_wire(width=1)
    dcache_resp_entry_id_top = m.new_wire(width=4)
    dcache_resp_ok_top = m.new_wire(width=1)
    dcache_resp_err_code_top = m.new_wire(width=4)

    scb_enq_valid_top = stq_top["stq_head_store_valid_stq"]
    scb_enq_line_top = stq_top["stq_head_store_addr_stq"] & (~c(63, width=64))
    # Stage-map packing: assume 8B store at byte offset 0 (no dynamic shifts in v4).
    scb_enq_mask_top = c(0xFF, width=64)
    scb_enq_data_top = stq_top["stq_head_store_data_stq"]._zext(width=512)
    scb_sid_ctr_top = m.out("scb_sid_ctr_top", clk=clk_top, rst=rst_top, width=6, init=c(1, width=6), en=c(1, width=1))
    scb_top = m.instance_auto(
        build_janus_bcc_lsu_scb,
        name="janus_scb",
        module_name="JanusBccLsuScbTop",
        clk=clk_top,
        rst=rst_top,
        enq_valid=scb_enq_valid_top,
        enq_line=scb_enq_line_top,
        enq_mask=scb_enq_mask_top,
        enq_data=scb_enq_data_top,
        enq_sid=scb_sid_ctr_top.out(),
        dcache_req_ready=dcache_req_ready_top,
        dcache_resp_valid=dcache_resp_valid_top,
        dcache_resp_entry_id=dcache_resp_entry_id_top,
        dcache_resp_ok=dcache_resp_ok_top,
        dcache_resp_err_code=dcache_resp_err_code_top,
    )
    scb_enq_fire_top = scb_enq_valid_top & scb_top["enq_ready"]
    scb_sid_ctr_top.set((scb_sid_ctr_top.out() + c(1, width=6))._trunc(width=6), when=scb_enq_fire_top)

    dcache_stub_top = m.instance_auto(
        build_janus_bcc_lsu_dcache_stub,
        name="janus_dcache_stub",
        module_name="JanusBccLsuDCacheStubTop",
        clk=clk_top,
        rst=rst_top,
        dcache_req_valid=scb_top["dcache_req_valid"],
        dcache_req_entry_id=scb_top["dcache_req_entry_id"],
        dcache_req_line=scb_top["dcache_req_line"],
        dcache_req_mask=scb_top["dcache_req_mask"],
        dcache_req_data=scb_top["dcache_req_data"],
        dcache_resp_ready=scb_top["dcache_resp_ready"],
    )
    m.assign(dcache_req_ready_top, dcache_stub_top["dcache_req_ready"])
    m.assign(dcache_resp_valid_top, dcache_stub_top["dcache_resp_valid"])
    m.assign(dcache_resp_entry_id_top, dcache_stub_top["dcache_resp_entry_id"])
    m.assign(dcache_resp_ok_top, dcache_stub_top["dcache_resp_ok"])
    m.assign(dcache_resp_err_code_top, dcache_stub_top["dcache_resp_err_code"])

    scb_issue_fire_top = scb_top["dcache_req_valid"] & dcache_stub_top["dcache_req_ready"]

    deq_ready_bisq_wire = m.new_wire(width=1)
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

    bisq_top = m.instance_auto(
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
        flush_valid_bisq=backend_top["redirect_valid"],
        flush_fire_bisq=backend_top["redirect_valid"],
        flush_bid_bisq=backend_top["redirect_bid"],
    )
    m.assign(bisq_enq_ready_wire, bisq_top["bisq_enq_ready_bisq"])

    tmu_noc_top = m.instance_auto(
        build_janus_tmu_noc_node,
        name="janus_tmu_noc",
        module_name="JanusTmuNocNodeTop",
        clk=clk_top,
        rst=rst_top,
        in_valid_noc=bisq_top["bisq_head_valid_bisq"],
        in_data_noc=bisq_top["bisq_head_payload_bisq"],
        out_ready_noc=c(1, width=1),
    )
    tmu_rf_top = m.instance_auto(
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

    bctrl_top = m.instance_auto(
        build_janus_bcc_bctrl,
        name="janus_bctrl",
        module_name="JanusBccBctrlTop",
        bisq_head_valid_bisq=bisq_top["bisq_head_valid_bisq"],
        bisq_head_kind_bisq=bisq_top["bisq_head_kind_bisq"],
        bisq_head_bid_bisq=bisq_top["bisq_head_bid_bisq"],
        bisq_head_payload_bisq=bisq_top["bisq_head_payload_bisq"],
        bisq_head_tile_bisq=bisq_top["bisq_head_tile_bisq"],
        bisq_head_rob_bisq=bisq_top["bisq_head_rob_bisq"],
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

    tma_top = m.instance_auto(
        build_janus_tma,
        name="janus_tma",
        module_name="JanusTmaTop",
        clk=clk_top,
        rst=rst_top,
        cmd_valid_tma=bctrl_top["cmd_tma_valid_bctrl"],
        cmd_tag_tma=bctrl_top["bctrl_to_pe_stage_cmd_tag_bctrl"],
        cmd_payload_tma=bctrl_top["bctrl_to_pe_stage_cmd_payload_bctrl"],
        cmd_bid_tma=bctrl_top["bctrl_to_tma_stage_cmd_bid_bctrl"],
        flush_fire_tma=flush_valid_fls,
        flush_bid_tma=backend_top["redirect_bid"],
    )
    cube_top = m.instance_auto(
        build_janus_cube,
        name="janus_cube",
        module_name="JanusCubeTop",
        clk=clk_top,
        rst=rst_top,
        cmd_valid_cube=bctrl_top["cmd_cube_valid_bctrl"],
        cmd_tag_cube=bctrl_top["bctrl_to_pe_stage_cmd_tag_bctrl"],
        cmd_payload_cube=bctrl_top["bctrl_to_pe_stage_cmd_payload_bctrl"],
        cmd_bid_cube=bctrl_top["bctrl_to_cube_stage_cmd_bid_bctrl"],
        flush_fire_cube=flush_valid_fls,
        flush_bid_cube=backend_top["redirect_bid"],
    )
    tau_top = m.instance_auto(
        build_janus_tau,
        name="janus_tau",
        module_name="JanusTauTop",
        clk=clk_top,
        rst=rst_top,
        cmd_valid_tau=bctrl_top["cmd_tau_valid_bctrl"],
        cmd_tag_tau=bctrl_top["bctrl_to_pe_stage_cmd_tag_bctrl"],
        cmd_payload_tau=bctrl_top["bctrl_to_pe_stage_cmd_payload_bctrl"],
        cmd_bid_tau=bctrl_top["bctrl_to_tau_stage_cmd_bid_bctrl"],
        flush_fire_tau=flush_valid_fls,
        flush_bid_tau=backend_top["redirect_bid"],
    )
    vec_top = m.instance_auto(
        build_linxcore_vec,
        name="linxcore_vec",
        module_name="LinxCoreVecTop",
        clk=clk_top,
        rst=rst_top,
        cmd_valid_vec=bctrl_top["cmd_vec_valid_bctrl"],
        cmd_tag_vec=bctrl_top["bctrl_to_pe_stage_cmd_tag_bctrl"],
        cmd_payload_vec=bctrl_top["bctrl_to_pe_stage_cmd_payload_bctrl"],
        cmd_bid_vec=bctrl_top["bctrl_to_vec_stage_cmd_bid_bctrl"],
        flush_fire_vec=flush_valid_fls,
        flush_bid_vec=backend_top["redirect_bid"],
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

    brob_top = m.instance_auto(
        build_janus_bcc_bctrl_brob,
        name="janus_brob",
        module_name="JanusBccBrobTop",
        clk=clk_top,
        rst=rst_top,
        alloc_fire_brob=backend_top["brob_alloc_fire"],
        issue_fire_brob=bctrl_top["issue_fire_brob"],
        issue_tag_brob=bctrl_top["issue_tag_brob"],
        issue_bid_brob=bctrl_top["issue_bid_brob"],
        issue_src_rob_brob=bctrl_top["issue_src_rob_brob"],
        retire_fire_brob=backend_top["brob_retire_fire"],
        retire_bid_brob=backend_top["brob_retire_bid"],
        query_bid_brob=backend_top["active_block_bid"],
        flush_valid_brob=flush_valid_fls,
        flush_fire_brob=flush_valid_fls,
        flush_bid_brob=backend_top["redirect_bid"],
        rsp_valid_brob=bctrl_top["rsp_valid_brob"],
        rsp_tag_brob=bctrl_top["rsp_tag_brob"],
        rsp_status_brob=bctrl_top["rsp_status_brob"],
        rsp_data0_brob=bctrl_top["rsp_data0_brob"],
        rsp_data1_brob=bctrl_top["rsp_data1_brob"],
        rsp_trap_valid_brob=bctrl_top["rsp_trap_valid_brob"],
        rsp_trap_cause_brob=bctrl_top["rsp_trap_cause_brob"],
    )
    m.output("brob_to_rob_stage_rsp_valid_top", brob_top["brob_to_rob_stage_rsp_valid_brob"])
    m.output("brob_to_rob_stage_rsp_src_rob_top", brob_top["brob_to_rob_stage_rsp_src_rob_brob"])
    m.output("brob_to_rob_stage_rsp_bid_top", brob_top["brob_to_rob_stage_rsp_bid_brob"])
    m.output("brob_query_state_top", brob_top["brob_query_state_brob"])
    m.output("brob_query_allocated_top", brob_top["brob_query_allocated_brob"])
    m.output("brob_query_ready_top", brob_top["brob_query_ready_brob"])
    m.output("brob_query_exception_top", brob_top["brob_query_exception_brob"])
    m.output("brob_query_retired_top", brob_top["brob_query_retired_brob"])
    m.output("brob_count_dbg_top", brob_top["brob_count_brob"])
    m.output("brob_alloc_ready_dbg_top", brob_top["brob_alloc_ready_brob"])
    m.output("brob_alloc_bid_dbg_top", brob_top["brob_alloc_bid_brob"])
    m.output("brob_retire_fire_top", backend_top["brob_retire_fire"])
    m.output("brob_retire_bid_top", backend_top["brob_retire_bid"])
    m.assign(brob_active_allocated_wire, brob_top["brob_query_allocated_brob"])
    m.assign(brob_active_ready_wire, brob_top["brob_query_ready_brob"])
    m.assign(brob_active_exception_wire, brob_top["brob_query_exception_brob"])
    m.assign(brob_active_retired_wire, brob_top["brob_query_retired_brob"])
    m.assign(brob_alloc_ready_wire, brob_top["brob_alloc_ready_brob"])
    m.assign(brob_alloc_bid_wire, brob_top["brob_alloc_bid_brob"])
    m.output("bctrl_issue_bid_top", bctrl_top["issue_bid_brob"])
    m.output("bctrl_issue_fire_top", bctrl_top["issue_fire_brob"])
    m.output("bctrl_issue_src_rob_top", bctrl_top["issue_src_rob_brob"])

    # Export canonical backend-compatible ports.
    export_ports = [
        "cycles",
        "halted",
        "pc",
        "mmio_uart_valid",
        "mmio_uart_data",
        "mmio_exit_valid",
        "mmio_exit_code",
        "dmem_wvalid",
        "dmem_waddr",
        "dmem_wdata",
        "dmem_wstrb",
        "rob_head_valid",
        "rob_head_done",
        "rob_head_pc",
        "rob_head_insn_raw",
        "rob_head_len",
        "rob_head_op",
        "ctu_block_ifu",
        "replay_cause",
        "bru_fault_set_dbg",
    ]
    for name_top in export_ports:
        m.output(name_top, backend_top[name_top])

    m.output("ftq_head", c(0, width=4))
    m.output("ftq_tail", c(0, width=4))
    # Legacy FTQ/IFU debug ports: keep for bench compatibility, but tie off in
    # the IB-fed frontend.
    m.output("ftq_count", c(0, width=5))
    m.output("checkpoint_id", f4_checkpoint_in_top)

build_top_export.__pycircuit_name__ = "linxcore_top_export"
