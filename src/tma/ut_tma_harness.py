from __future__ import annotations

from pycircuit import Circuit, module

from tma.tma import build_janus_tma  # noqa: E402


ADDR_W = 64
DATA_W = 256
TXNID_W = 8
DBID_W = 8
BE_W = DATA_W // 8


@module(name="ut_tma_harness")
def build(m: Circuit) -> None:
    clk = m.clock("clk")
    rst = m.reset("rst")

    cmd_valid_tma = m.input("cmd_valid_tma", width=1)
    cmd_tag_tma = m.input("cmd_tag_tma", width=8)
    cmd_payload_tma = m.input("cmd_payload_tma", width=64)

    # UT-only sideband config for real NORM data path.
    ut_cfg_layout = m.input("ut_cfg_layout", width=3)
    ut_cfg_elem_type = m.input("ut_cfg_elem_type", width=3)
    ut_cfg_pad_mode = m.input("ut_cfg_pad_mode", width=2)
    ut_cfg_gm_base_addr = m.input("ut_cfg_gm_base_addr", width=64)
    ut_cfg_tr_base_addr = m.input("ut_cfg_tr_base_addr", width=64)
    ut_cfg_gm_inner_elems = m.input("ut_cfg_gm_inner_elems", width=16)
    ut_cfg_gm_outer_elems = m.input("ut_cfg_gm_outer_elems", width=16)
    ut_cfg_tr_inner_elems = m.input("ut_cfg_tr_inner_elems", width=16)
    ut_cfg_tr_outer_elems = m.input("ut_cfg_tr_outer_elems", width=16)
    ut_cfg_gm_inner_stride_B = m.input("ut_cfg_gm_inner_stride_B", width=16)

    gm_req_ready = m.input("gm_req_ready", width=1)
    gm_rsp_valid = m.input("gm_rsp_valid", width=1)
    gm_rsp_opcode = m.input("gm_rsp_opcode", width=6)
    gm_rsp_txnid = m.input("gm_rsp_txnid", width=TXNID_W)
    gm_rsp_dbid = m.input("gm_rsp_dbid", width=DBID_W)
    gm_rsp_resp = m.input("gm_rsp_resp", width=2)
    gm_dat_rx_valid = m.input("gm_dat_rx_valid", width=1)
    gm_dat_rx_opcode = m.input("gm_dat_rx_opcode", width=6)
    gm_dat_rx_txnid = m.input("gm_dat_rx_txnid", width=TXNID_W)
    gm_dat_rx_dbid = m.input("gm_dat_rx_dbid", width=DBID_W)
    gm_dat_rx_data = m.input("gm_dat_rx_data", width=DATA_W)
    gm_dat_rx_be = m.input("gm_dat_rx_be", width=BE_W)
    gm_dat_rx_resp = m.input("gm_dat_rx_resp", width=2)
    gm_dat_tx_ready = m.input("gm_dat_tx_ready", width=1)

    tr_req_ready = m.input("tr_req_ready", width=1)
    tr_rsp_valid = m.input("tr_rsp_valid", width=1)
    tr_rsp_opcode = m.input("tr_rsp_opcode", width=6)
    tr_rsp_txnid = m.input("tr_rsp_txnid", width=TXNID_W)
    tr_rsp_dbid = m.input("tr_rsp_dbid", width=DBID_W)
    tr_rsp_resp = m.input("tr_rsp_resp", width=2)
    tr_dat_rx_valid = m.input("tr_dat_rx_valid", width=1)
    tr_dat_rx_opcode = m.input("tr_dat_rx_opcode", width=6)
    tr_dat_rx_txnid = m.input("tr_dat_rx_txnid", width=TXNID_W)
    tr_dat_rx_dbid = m.input("tr_dat_rx_dbid", width=DBID_W)
    tr_dat_rx_data = m.input("tr_dat_rx_data", width=DATA_W)
    tr_dat_rx_be = m.input("tr_dat_rx_be", width=BE_W)
    tr_dat_rx_resp = m.input("tr_dat_rx_resp", width=2)
    tr_dat_tx_ready = m.input("tr_dat_tx_ready", width=1)

    dut = m.instance(
        build_janus_tma,
        name="dut_tma",
        module_name="JanusTmaUtHarnessDut",
        params={
            "enable_chi_if": 1,
            "enable_ut_cfg_if": 1,
            "ADDR_W": ADDR_W,
            "DATA_W": DATA_W,
            "TXNID_W": TXNID_W,
            "DBID_W": DBID_W,
            "BPQ_DEPTH": 1,
            "RFB_DEPTH": 16,
            "WCB_DEPTH": 16,
            "BDB_DEPTH": 64,
            "stub_timeout_cycles": 2048,
        },
        clk=clk,
        rst=rst,
        cmd_valid_tma=cmd_valid_tma,
        cmd_tag_tma=cmd_tag_tma,
        cmd_payload_tma=cmd_payload_tma,
        ut_cfg_layout=ut_cfg_layout,
        ut_cfg_elem_type=ut_cfg_elem_type,
        ut_cfg_pad_mode=ut_cfg_pad_mode,
        ut_cfg_gm_base_addr=ut_cfg_gm_base_addr,
        ut_cfg_tr_base_addr=ut_cfg_tr_base_addr,
        ut_cfg_gm_inner_elems=ut_cfg_gm_inner_elems,
        ut_cfg_gm_outer_elems=ut_cfg_gm_outer_elems,
        ut_cfg_tr_inner_elems=ut_cfg_tr_inner_elems,
        ut_cfg_tr_outer_elems=ut_cfg_tr_outer_elems,
        ut_cfg_gm_inner_stride_B=ut_cfg_gm_inner_stride_B,
        gm_req_ready=gm_req_ready,
        gm_rsp_valid=gm_rsp_valid,
        gm_rsp_opcode=gm_rsp_opcode,
        gm_rsp_txnid=gm_rsp_txnid,
        gm_rsp_dbid=gm_rsp_dbid,
        gm_rsp_resp=gm_rsp_resp,
        gm_dat_rx_valid=gm_dat_rx_valid,
        gm_dat_rx_opcode=gm_dat_rx_opcode,
        gm_dat_rx_txnid=gm_dat_rx_txnid,
        gm_dat_rx_dbid=gm_dat_rx_dbid,
        gm_dat_rx_data=gm_dat_rx_data,
        gm_dat_rx_be=gm_dat_rx_be,
        gm_dat_rx_resp=gm_dat_rx_resp,
        gm_dat_tx_ready=gm_dat_tx_ready,
        tr_req_ready=tr_req_ready,
        tr_rsp_valid=tr_rsp_valid,
        tr_rsp_opcode=tr_rsp_opcode,
        tr_rsp_txnid=tr_rsp_txnid,
        tr_rsp_dbid=tr_rsp_dbid,
        tr_rsp_resp=tr_rsp_resp,
        tr_dat_rx_valid=tr_dat_rx_valid,
        tr_dat_rx_opcode=tr_dat_rx_opcode,
        tr_dat_rx_txnid=tr_dat_rx_txnid,
        tr_dat_rx_dbid=tr_dat_rx_dbid,
        tr_dat_rx_data=tr_dat_rx_data,
        tr_dat_rx_be=tr_dat_rx_be,
        tr_dat_rx_resp=tr_dat_rx_resp,
        tr_dat_tx_ready=tr_dat_tx_ready,
    )

    m.output("cmd_ready_tma", dut["cmd_ready_tma"])
    m.output("rsp_valid_tma", dut["rsp_valid_tma"])
    m.output("rsp_tag_tma", dut["rsp_tag_tma"])
    m.output("rsp_status_tma", dut["rsp_status_tma"])
    m.output("rsp_data0_tma", dut["rsp_data0_tma"])
    m.output("rsp_data1_tma", dut["rsp_data1_tma"])

    m.output("gm_req_valid", dut["gm_req_valid"])
    m.output("gm_req_opcode", dut["gm_req_opcode"])
    m.output("gm_req_txnid", dut["gm_req_txnid"])
    m.output("gm_req_addr", dut["gm_req_addr"])
    m.output("gm_req_size", dut["gm_req_size"])
    m.output("gm_req_len", dut["gm_req_len"])
    m.output("gm_rsp_ready", dut["gm_rsp_ready"])
    m.output("gm_dat_rx_ready", dut["gm_dat_rx_ready"])
    m.output("gm_dat_tx_valid", dut["gm_dat_tx_valid"])
    m.output("gm_dat_tx_opcode", dut["gm_dat_tx_opcode"])
    m.output("gm_dat_tx_txnid", dut["gm_dat_tx_txnid"])
    m.output("gm_dat_tx_dbid", dut["gm_dat_tx_dbid"])
    m.output("gm_dat_tx_data", dut["gm_dat_tx_data"])
    m.output("gm_dat_tx_be", dut["gm_dat_tx_be"])
    m.output("gm_dat_tx_resp", dut["gm_dat_tx_resp"])

    m.output("tr_req_valid", dut["tr_req_valid"])
    m.output("tr_req_opcode", dut["tr_req_opcode"])
    m.output("tr_req_txnid", dut["tr_req_txnid"])
    m.output("tr_req_addr", dut["tr_req_addr"])
    m.output("tr_req_size", dut["tr_req_size"])
    m.output("tr_req_len", dut["tr_req_len"])
    m.output("tr_rsp_ready", dut["tr_rsp_ready"])
    m.output("tr_dat_rx_ready", dut["tr_dat_rx_ready"])
    m.output("tr_dat_tx_valid", dut["tr_dat_tx_valid"])
    m.output("tr_dat_tx_opcode", dut["tr_dat_tx_opcode"])
    m.output("tr_dat_tx_txnid", dut["tr_dat_tx_txnid"])
    m.output("tr_dat_tx_dbid", dut["tr_dat_tx_dbid"])
    m.output("tr_dat_tx_data", dut["tr_dat_tx_data"])
    m.output("tr_dat_tx_be", dut["tr_dat_tx_be"])
    m.output("tr_dat_tx_resp", dut["tr_dat_tx_resp"])

    # Debug taps for deadlock triage.
    m.output("dbg_state_tma", dut["dbg_state_tma"])
    m.output("dbg_bpq_occ_tma", dut["dbg_bpq_occ_tma"])
    m.output("dbg_rfb_occ_tma", dut["dbg_rfb_occ_tma"])
    m.output("dbg_wcb_occ_tma", dut["dbg_wcb_occ_tma"])
    m.output("dbg_bdb_occ_tma", dut["dbg_bdb_occ_tma"])
    m.output("dbg_row_idx_tma", dut["dbg_row_idx_tma"])
    m.output("dbg_chunk_idx_tma", dut["dbg_chunk_idx_tma"])
    m.output("dbg_chunk_row_idx_tma", dut["dbg_chunk_row_idx_tma"])
    m.output("dbg_timeout_tma", dut["dbg_timeout_tma"])
