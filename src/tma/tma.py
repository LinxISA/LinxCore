from __future__ import annotations

from pycircuit import Circuit, module


@module(name="JanusTma")
def build_janus_tma(
    m: Circuit,
    *,
    enable_chi_if: int = 0,
    ADDR_W: int = 64,
    DATA_W: int = 256,
    TXNID_W: int = 8,
    DBID_W: int = 8,
    stub_timeout_cycles: int = 64,
) -> None:
    clk_tma = m.clock("clk")
    rst_tma = m.reset("rst")

    cmd_valid_tma = m.input("cmd_valid_tma", width=1)
    cmd_tag_tma = m.input("cmd_tag_tma", width=8)
    cmd_payload_tma = m.input("cmd_payload_tma", width=64)

    c = m.const

    # Keep legacy top-level compatibility by default.
    if enable_chi_if == 0:
        busy_tma = m.out("busy_tma", clk=clk_tma, rst=rst_tma, width=1, init=c(0, width=1), en=c(1, width=1))
        wait_tma = m.out("wait_tma", clk=clk_tma, rst=rst_tma, width=3, init=c(0, width=3), en=c(1, width=1))
        tag_q_tma = m.out("tag_q_tma", clk=clk_tma, rst=rst_tma, width=8, init=c(0, width=8), en=c(1, width=1))
        payload_q_tma = m.out("payload_q_tma", clk=clk_tma, rst=rst_tma, width=64, init=c(0, width=64), en=c(1, width=1))

        cmd_ready_tma = ~busy_tma.out()
        accept_tma = cmd_valid_tma & cmd_ready_tma

        busy_next_tma = busy_tma.out()
        wait_next_tma = wait_tma.out()
        busy_next_tma = accept_tma._select_internal(c(1, width=1), busy_next_tma)
        wait_next_tma = accept_tma._select_internal(c(2, width=3), wait_next_tma)

        count_down_tma = busy_tma.out() & (wait_tma.out() > c(0, width=3))
        wait_next_tma = count_down_tma._select_internal(wait_tma.out() - c(1, width=3), wait_next_tma)

        rsp_fire_tma = busy_tma.out() & (wait_tma.out() == c(0, width=3))
        busy_next_tma = rsp_fire_tma._select_internal(c(0, width=1), busy_next_tma)

        busy_tma.set(busy_next_tma)
        wait_tma.set(wait_next_tma)
        tag_q_tma.set(cmd_tag_tma, when=accept_tma)
        payload_q_tma.set(cmd_payload_tma, when=accept_tma)

        m.output("cmd_ready_tma", cmd_ready_tma)
        m.output("rsp_valid_tma", rsp_fire_tma)
        m.output("rsp_tag_tma", tag_q_tma.out())
        m.output("rsp_status_tma", c(0, width=4))
        m.output("rsp_data0_tma", payload_q_tma.out() + c(1, width=64))
        m.output("rsp_data1_tma", payload_q_tma.out())
    else:
        if DATA_W % 8 != 0:
            raise ValueError("DATA_W must be byte-aligned")
    
        BE_W = DATA_W // 8
        timeout_init = max(0, int(stub_timeout_cycles))
        timeout_w = max(1, timeout_init.bit_length() + 1)
        timeout_init_c = c(timeout_init, width=timeout_w)
    
        # Unified status code contract.
        ST_OK = 0x0
        ST_DECODE_ERR = 0x1
        ST_PROTOCOL_ERR = 0x2
        ST_ACCESS_ERR = 0x3
        ST_TIMEOUT = 0x4
        ST_UNSUPPORTED = 0x5
        ST_INTERNAL_ERR = 0xF
    
        # Placeholder CHI opcodes for interface-level verification.
        OPC_REQ_READ_ONCE = 0x01
        OPC_REQ_WRITE_UNIQUE = 0x02
        OPC_REQ_READ_NOSNP = 0x03
        OPC_REQ_WRITE_NOSNP = 0x04
    
        OPC_RSP_COMP = 0x01
        OPC_RSP_COMP_DBID = 0x02
        OPC_DAT_COMPDATA = 0x01
        OPC_DAT_NCB_WRDATA = 0x02
    
        S_IDLE = 0
        S_TLOAD_REQ_GM = 1
        S_TLOAD_WAIT_GM_DAT = 2
        S_TLOAD_REQ_TR = 3
        S_TLOAD_SEND_TR_DAT = 4
        S_TLOAD_WAIT_TR_COMP = 5
        S_TSTORE_REQ_TR = 6
        S_TSTORE_WAIT_TR_DAT = 7
        S_TSTORE_REQ_GM = 8
        S_TSTORE_WAIT_GM_DBID = 9
        S_TSTORE_SEND_GM_DAT = 10
        S_TSTORE_WAIT_GM_COMP = 11
        S_DONE = 12
        state_w = 4
    
        OP_TLOAD = 0
        OP_TSTORE = 1
    
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
    
        state_q_tma = m.out("state_q_tma", clk=clk_tma, rst=rst_tma, width=state_w, init=c(S_IDLE, width=state_w), en=c(1, width=1))
        tag_q_tma = m.out("tag_q_tma", clk=clk_tma, rst=rst_tma, width=8, init=c(0, width=8), en=c(1, width=1))
        payload_q_tma = m.out("payload_q_tma", clk=clk_tma, rst=rst_tma, width=64, init=c(0, width=64), en=c(1, width=1))
        op_q_tma = m.out("op_q_tma", clk=clk_tma, rst=rst_tma, width=2, init=c(0, width=2), en=c(1, width=1))
        txnid_q_tma = m.out("txnid_q_tma", clk=clk_tma, rst=rst_tma, width=TXNID_W, init=c(0, width=TXNID_W), en=c(1, width=1))
        dbid_q_tma = m.out("dbid_q_tma", clk=clk_tma, rst=rst_tma, width=DBID_W, init=c(0, width=DBID_W), en=c(1, width=1))
        status_q_tma = m.out("status_q_tma", clk=clk_tma, rst=rst_tma, width=4, init=c(ST_OK, width=4), en=c(1, width=1))
        beats_q_tma = m.out("beats_q_tma", clk=clk_tma, rst=rst_tma, width=32, init=c(0, width=32), en=c(1, width=1))
        elapsed_q_tma = m.out("elapsed_q_tma", clk=clk_tma, rst=rst_tma, width=32, init=c(0, width=32), en=c(1, width=1))
        err_info_q_tma = m.out("err_info_q_tma", clk=clk_tma, rst=rst_tma, width=32, init=c(0, width=32), en=c(1, width=1))
        timeout_q_tma = m.out("timeout_q_tma", clk=clk_tma, rst=rst_tma, width=timeout_w, init=c(0, width=timeout_w), en=c(1, width=1))
        next_txnid_q_tma = m.out("next_txnid_q_tma", clk=clk_tma, rst=rst_tma, width=TXNID_W, init=c(0, width=TXNID_W), en=c(1, width=1))
    
        state_is_idle = state_q_tma.out() == c(S_IDLE, width=state_w)
        state_is_done = state_q_tma.out() == c(S_DONE, width=state_w)
        state_is_tload_req_gm = state_q_tma.out() == c(S_TLOAD_REQ_GM, width=state_w)
        state_is_tload_wait_gm_dat = state_q_tma.out() == c(S_TLOAD_WAIT_GM_DAT, width=state_w)
        state_is_tload_req_tr = state_q_tma.out() == c(S_TLOAD_REQ_TR, width=state_w)
        state_is_tload_send_tr_dat = state_q_tma.out() == c(S_TLOAD_SEND_TR_DAT, width=state_w)
        state_is_tload_wait_tr_comp = state_q_tma.out() == c(S_TLOAD_WAIT_TR_COMP, width=state_w)
        state_is_tstore_req_tr = state_q_tma.out() == c(S_TSTORE_REQ_TR, width=state_w)
        state_is_tstore_wait_tr_dat = state_q_tma.out() == c(S_TSTORE_WAIT_TR_DAT, width=state_w)
        state_is_tstore_req_gm = state_q_tma.out() == c(S_TSTORE_REQ_GM, width=state_w)
        state_is_tstore_wait_gm_dbid = state_q_tma.out() == c(S_TSTORE_WAIT_GM_DBID, width=state_w)
        state_is_tstore_send_gm_dat = state_q_tma.out() == c(S_TSTORE_SEND_GM_DAT, width=state_w)
        state_is_tstore_wait_gm_comp = state_q_tma.out() == c(S_TSTORE_WAIT_GM_COMP, width=state_w)
    
        cmd_ready_tma = state_is_idle
        accept_tma = cmd_valid_tma & cmd_ready_tma
    
        payload_op_tma = cmd_payload_tma[0:2]
        payload_decode_err_tma = cmd_payload_tma[63]
        payload_is_tload_tma = payload_op_tma == c(OP_TLOAD, width=2)
        payload_is_tstore_tma = payload_op_tma == c(OP_TSTORE, width=2)
        payload_supported_tma = payload_is_tload_tma | payload_is_tstore_tma
    
        gm_req_valid = state_is_tload_req_gm | state_is_tstore_req_gm
        gm_req_opcode = c(0, width=7)
        gm_req_opcode = state_is_tload_req_gm._select_internal(c(OPC_REQ_READ_ONCE, width=7), gm_req_opcode)
        gm_req_opcode = state_is_tstore_req_gm._select_internal(c(OPC_REQ_WRITE_UNIQUE, width=7), gm_req_opcode)
    
        tr_req_valid = state_is_tload_req_tr | state_is_tstore_req_tr
        tr_req_opcode = c(0, width=7)
        tr_req_opcode = state_is_tload_req_tr._select_internal(c(OPC_REQ_WRITE_NOSNP, width=7), tr_req_opcode)
        tr_req_opcode = state_is_tstore_req_tr._select_internal(c(OPC_REQ_READ_NOSNP, width=7), tr_req_opcode)
    
        gm_rsp_ready = state_is_tstore_wait_gm_dbid | state_is_tstore_wait_gm_comp
        tr_rsp_ready = state_is_tload_wait_tr_comp
        gm_dat_rx_ready = state_is_tload_wait_gm_dat
        tr_dat_rx_ready = state_is_tstore_wait_tr_dat
    
        gm_dat_tx_valid = state_is_tstore_send_gm_dat
        tr_dat_tx_valid = state_is_tload_send_tr_dat
    
        gm_dat_tx_opcode = c(0, width=6)
        gm_dat_tx_opcode = state_is_tstore_send_gm_dat._select_internal(c(OPC_DAT_NCB_WRDATA, width=6), gm_dat_tx_opcode)
    
        tr_dat_tx_opcode = c(0, width=6)
        tr_dat_tx_opcode = state_is_tload_send_tr_dat._select_internal(c(OPC_DAT_NCB_WRDATA, width=6), tr_dat_tx_opcode)
    
        gm_req_fire = gm_req_valid & gm_req_ready
        tr_req_fire = tr_req_valid & tr_req_ready
        gm_rsp_fire = gm_rsp_valid & gm_rsp_ready
        tr_rsp_fire = tr_rsp_valid & tr_rsp_ready
        gm_dat_rx_fire = gm_dat_rx_valid & gm_dat_rx_ready
        tr_dat_rx_fire = tr_dat_rx_valid & tr_dat_rx_ready
        gm_dat_tx_fire = gm_dat_tx_valid & gm_dat_tx_ready
        tr_dat_tx_fire = tr_dat_tx_valid & tr_dat_tx_ready
    
        state_next_tma = state_q_tma.out()
        tag_next_tma = tag_q_tma.out()
        payload_next_tma = payload_q_tma.out()
        op_next_tma = op_q_tma.out()
        txnid_next_tma = txnid_q_tma.out()
        dbid_next_tma = dbid_q_tma.out()
        status_next_tma = status_q_tma.out()
        beats_next_tma = beats_q_tma.out()
        elapsed_next_tma = elapsed_q_tma.out()
        err_info_next_tma = err_info_q_tma.out()
        timeout_next_tma = timeout_q_tma.out()
        next_txnid_next_tma = next_txnid_q_tma.out()
    
        active_state_tma = (~state_is_idle) & (~state_is_done)
        timeout_is_zero_tma = timeout_q_tma.out() == c(0, width=timeout_w)
        progress_tma = (
            gm_req_fire
            | tr_req_fire
            | gm_rsp_fire
            | tr_rsp_fire
            | gm_dat_rx_fire
            | tr_dat_rx_fire
            | gm_dat_tx_fire
            | tr_dat_tx_fire
        )
        timeout_hit_tma = active_state_tma & timeout_is_zero_tma & (~progress_tma)
        timeout_reload_tma = active_state_tma & progress_tma
        timeout_dec_tma = active_state_tma & (~progress_tma) & (timeout_q_tma.out() > c(0, width=timeout_w))
        timeout_next_tma = timeout_reload_tma._select_internal(timeout_init_c, timeout_next_tma)
        timeout_next_tma = timeout_dec_tma._select_internal(timeout_q_tma.out() - c(1, width=timeout_w), timeout_next_tma)
        elapsed_next_tma = active_state_tma._select_internal(elapsed_q_tma.out() + c(1, width=32), elapsed_next_tma)
    
        tag_next_tma = accept_tma._select_internal(cmd_tag_tma, tag_next_tma)
        payload_next_tma = accept_tma._select_internal(cmd_payload_tma, payload_next_tma)
        txnid_next_tma = accept_tma._select_internal(next_txnid_q_tma.out(), txnid_next_tma)
        dbid_next_tma = accept_tma._select_internal(c(0, width=DBID_W), dbid_next_tma)
        status_next_tma = accept_tma._select_internal(c(ST_OK, width=4), status_next_tma)
        beats_next_tma = accept_tma._select_internal(c(0, width=32), beats_next_tma)
        elapsed_next_tma = accept_tma._select_internal(c(0, width=32), elapsed_next_tma)
        err_info_next_tma = accept_tma._select_internal(c(0, width=32), err_info_next_tma)
        timeout_next_tma = accept_tma._select_internal(timeout_init_c, timeout_next_tma)
        next_txnid_next_tma = accept_tma._select_internal(next_txnid_q_tma.out() + c(1, width=TXNID_W), next_txnid_next_tma)
    
        start_decode_err_tma = accept_tma & payload_decode_err_tma
        start_tload_tma = accept_tma & (~payload_decode_err_tma) & payload_is_tload_tma
        start_tstore_tma = accept_tma & (~payload_decode_err_tma) & payload_is_tstore_tma
        start_unsupported_tma = accept_tma & (~payload_decode_err_tma) & (~payload_supported_tma)
    
        state_next_tma = start_decode_err_tma._select_internal(c(S_DONE, width=state_w), state_next_tma)
        status_next_tma = start_decode_err_tma._select_internal(c(ST_DECODE_ERR, width=4), status_next_tma)
        err_info_next_tma = start_decode_err_tma._select_internal(c(0x0001, width=32), err_info_next_tma)
    
        state_next_tma = start_tload_tma._select_internal(c(S_TLOAD_REQ_GM, width=state_w), state_next_tma)
        op_next_tma = start_tload_tma._select_internal(c(OP_TLOAD, width=2), op_next_tma)
    
        state_next_tma = start_tstore_tma._select_internal(c(S_TSTORE_REQ_TR, width=state_w), state_next_tma)
        op_next_tma = start_tstore_tma._select_internal(c(OP_TSTORE, width=2), op_next_tma)
    
        state_next_tma = start_unsupported_tma._select_internal(c(S_DONE, width=state_w), state_next_tma)
        status_next_tma = start_unsupported_tma._select_internal(c(ST_UNSUPPORTED, width=4), status_next_tma)
        err_info_next_tma = start_unsupported_tma._select_internal(c(0x0002, width=32), err_info_next_tma)
    
        state_next_tma = (state_is_tload_req_gm & gm_req_fire)._select_internal(c(S_TLOAD_WAIT_GM_DAT, width=state_w), state_next_tma)
        state_next_tma = (state_is_tload_req_tr & tr_req_fire)._select_internal(c(S_TLOAD_SEND_TR_DAT, width=state_w), state_next_tma)
        state_next_tma = (state_is_tload_send_tr_dat & tr_dat_tx_fire)._select_internal(c(S_TLOAD_WAIT_TR_COMP, width=state_w), state_next_tma)
        state_next_tma = (state_is_tstore_req_tr & tr_req_fire)._select_internal(c(S_TSTORE_WAIT_TR_DAT, width=state_w), state_next_tma)
        state_next_tma = (state_is_tstore_req_gm & gm_req_fire)._select_internal(c(S_TSTORE_WAIT_GM_DBID, width=state_w), state_next_tma)
        state_next_tma = (state_is_tstore_send_gm_dat & gm_dat_tx_fire)._select_internal(c(S_TSTORE_WAIT_GM_COMP, width=state_w), state_next_tma)
    
        tr_dat_tx_beat_tma = state_is_tload_send_tr_dat & tr_dat_tx_fire
        gm_dat_tx_beat_tma = state_is_tstore_send_gm_dat & gm_dat_tx_fire
        gm_dat_rx_beat_tma = state_is_tload_wait_gm_dat & gm_dat_rx_fire
        tr_dat_rx_beat_tma = state_is_tstore_wait_tr_dat & tr_dat_rx_fire
        beat_inc_tma = gm_dat_rx_beat_tma | tr_dat_tx_beat_tma | tr_dat_rx_beat_tma | gm_dat_tx_beat_tma
        beats_next_tma = beat_inc_tma._select_internal(beats_q_tma.out() + c(1, width=32), beats_next_tma)
    
        gm_dat_rx_bad_txn_tma = ~(gm_dat_rx_txnid == txnid_q_tma.out())
        gm_dat_rx_bad_op_tma = ~(gm_dat_rx_opcode == c(OPC_DAT_COMPDATA, width=6))
        gm_dat_rx_bad_resp_tma = ~(gm_dat_rx_resp == c(0, width=2))
        gm_dat_rx_protocol_tma = state_is_tload_wait_gm_dat & gm_dat_rx_fire & (gm_dat_rx_bad_txn_tma | gm_dat_rx_bad_op_tma)
        gm_dat_rx_access_tma = state_is_tload_wait_gm_dat & gm_dat_rx_fire & (~(gm_dat_rx_bad_txn_tma | gm_dat_rx_bad_op_tma)) & gm_dat_rx_bad_resp_tma
        gm_dat_rx_good_tma = state_is_tload_wait_gm_dat & gm_dat_rx_fire & (~(gm_dat_rx_bad_txn_tma | gm_dat_rx_bad_op_tma)) & (~gm_dat_rx_bad_resp_tma)
    
        state_next_tma = gm_dat_rx_good_tma._select_internal(c(S_TLOAD_REQ_TR, width=state_w), state_next_tma)
        state_next_tma = gm_dat_rx_protocol_tma._select_internal(c(S_DONE, width=state_w), state_next_tma)
        status_next_tma = gm_dat_rx_protocol_tma._select_internal(c(ST_PROTOCOL_ERR, width=4), status_next_tma)
        err_info_next_tma = gm_dat_rx_protocol_tma._select_internal(c(0x0101, width=32), err_info_next_tma)
        state_next_tma = gm_dat_rx_access_tma._select_internal(c(S_DONE, width=state_w), state_next_tma)
        status_next_tma = gm_dat_rx_access_tma._select_internal(c(ST_ACCESS_ERR, width=4), status_next_tma)
        err_info_next_tma = gm_dat_rx_access_tma._select_internal(c(0x0102, width=32), err_info_next_tma)
    
        tr_rsp_bad_txn_tma = ~(tr_rsp_txnid == txnid_q_tma.out())
        tr_rsp_bad_op_tma = ~(tr_rsp_opcode == c(OPC_RSP_COMP, width=6))
        tr_rsp_bad_resp_tma = ~(tr_rsp_resp == c(0, width=2))
        tr_rsp_protocol_tma = state_is_tload_wait_tr_comp & tr_rsp_fire & (tr_rsp_bad_txn_tma | tr_rsp_bad_op_tma)
        tr_rsp_access_tma = state_is_tload_wait_tr_comp & tr_rsp_fire & (~(tr_rsp_bad_txn_tma | tr_rsp_bad_op_tma)) & tr_rsp_bad_resp_tma
        tr_rsp_good_tma = state_is_tload_wait_tr_comp & tr_rsp_fire & (~(tr_rsp_bad_txn_tma | tr_rsp_bad_op_tma)) & (~tr_rsp_bad_resp_tma)
    
        state_next_tma = tr_rsp_good_tma._select_internal(c(S_DONE, width=state_w), state_next_tma)
        status_next_tma = tr_rsp_good_tma._select_internal(c(ST_OK, width=4), status_next_tma)
        err_info_next_tma = tr_rsp_good_tma._select_internal(c(0x0000, width=32), err_info_next_tma)
        state_next_tma = tr_rsp_protocol_tma._select_internal(c(S_DONE, width=state_w), state_next_tma)
        status_next_tma = tr_rsp_protocol_tma._select_internal(c(ST_PROTOCOL_ERR, width=4), status_next_tma)
        err_info_next_tma = tr_rsp_protocol_tma._select_internal(c(0x0103, width=32), err_info_next_tma)
        state_next_tma = tr_rsp_access_tma._select_internal(c(S_DONE, width=state_w), state_next_tma)
        status_next_tma = tr_rsp_access_tma._select_internal(c(ST_ACCESS_ERR, width=4), status_next_tma)
        err_info_next_tma = tr_rsp_access_tma._select_internal(c(0x0104, width=32), err_info_next_tma)
    
        tr_dat_rx_bad_txn_tma = ~(tr_dat_rx_txnid == txnid_q_tma.out())
        tr_dat_rx_bad_op_tma = ~(tr_dat_rx_opcode == c(OPC_DAT_COMPDATA, width=6))
        tr_dat_rx_bad_resp_tma = ~(tr_dat_rx_resp == c(0, width=2))
        tr_dat_rx_protocol_tma = state_is_tstore_wait_tr_dat & tr_dat_rx_fire & (tr_dat_rx_bad_txn_tma | tr_dat_rx_bad_op_tma)
        tr_dat_rx_access_tma = state_is_tstore_wait_tr_dat & tr_dat_rx_fire & (~(tr_dat_rx_bad_txn_tma | tr_dat_rx_bad_op_tma)) & tr_dat_rx_bad_resp_tma
        tr_dat_rx_good_tma = state_is_tstore_wait_tr_dat & tr_dat_rx_fire & (~(tr_dat_rx_bad_txn_tma | tr_dat_rx_bad_op_tma)) & (~tr_dat_rx_bad_resp_tma)
    
        state_next_tma = tr_dat_rx_good_tma._select_internal(c(S_TSTORE_REQ_GM, width=state_w), state_next_tma)
        state_next_tma = tr_dat_rx_protocol_tma._select_internal(c(S_DONE, width=state_w), state_next_tma)
        status_next_tma = tr_dat_rx_protocol_tma._select_internal(c(ST_PROTOCOL_ERR, width=4), status_next_tma)
        err_info_next_tma = tr_dat_rx_protocol_tma._select_internal(c(0x0201, width=32), err_info_next_tma)
        state_next_tma = tr_dat_rx_access_tma._select_internal(c(S_DONE, width=state_w), state_next_tma)
        status_next_tma = tr_dat_rx_access_tma._select_internal(c(ST_ACCESS_ERR, width=4), status_next_tma)
        err_info_next_tma = tr_dat_rx_access_tma._select_internal(c(0x0202, width=32), err_info_next_tma)
    
        gm_rsp_dbid_bad_txn_tma = ~(gm_rsp_txnid == txnid_q_tma.out())
        gm_rsp_dbid_bad_op_tma = ~(gm_rsp_opcode == c(OPC_RSP_COMP_DBID, width=6))
        gm_rsp_dbid_bad_resp_tma = ~(gm_rsp_resp == c(0, width=2))
        gm_rsp_dbid_protocol_tma = state_is_tstore_wait_gm_dbid & gm_rsp_fire & (gm_rsp_dbid_bad_txn_tma | gm_rsp_dbid_bad_op_tma)
        gm_rsp_dbid_access_tma = state_is_tstore_wait_gm_dbid & gm_rsp_fire & (~(gm_rsp_dbid_bad_txn_tma | gm_rsp_dbid_bad_op_tma)) & gm_rsp_dbid_bad_resp_tma
        gm_rsp_dbid_good_tma = state_is_tstore_wait_gm_dbid & gm_rsp_fire & (~(gm_rsp_dbid_bad_txn_tma | gm_rsp_dbid_bad_op_tma)) & (~gm_rsp_dbid_bad_resp_tma)
    
        state_next_tma = gm_rsp_dbid_good_tma._select_internal(c(S_TSTORE_SEND_GM_DAT, width=state_w), state_next_tma)
        dbid_next_tma = gm_rsp_dbid_good_tma._select_internal(gm_rsp_dbid, dbid_next_tma)
        state_next_tma = gm_rsp_dbid_protocol_tma._select_internal(c(S_DONE, width=state_w), state_next_tma)
        status_next_tma = gm_rsp_dbid_protocol_tma._select_internal(c(ST_PROTOCOL_ERR, width=4), status_next_tma)
        err_info_next_tma = gm_rsp_dbid_protocol_tma._select_internal(c(0x0203, width=32), err_info_next_tma)
        state_next_tma = gm_rsp_dbid_access_tma._select_internal(c(S_DONE, width=state_w), state_next_tma)
        status_next_tma = gm_rsp_dbid_access_tma._select_internal(c(ST_ACCESS_ERR, width=4), status_next_tma)
        err_info_next_tma = gm_rsp_dbid_access_tma._select_internal(c(0x0204, width=32), err_info_next_tma)
    
        gm_rsp_comp_bad_txn_tma = ~(gm_rsp_txnid == txnid_q_tma.out())
        gm_rsp_comp_bad_op_tma = ~(gm_rsp_opcode == c(OPC_RSP_COMP, width=6))
        gm_rsp_comp_bad_resp_tma = ~(gm_rsp_resp == c(0, width=2))
        gm_rsp_comp_protocol_tma = state_is_tstore_wait_gm_comp & gm_rsp_fire & (gm_rsp_comp_bad_txn_tma | gm_rsp_comp_bad_op_tma)
        gm_rsp_comp_access_tma = state_is_tstore_wait_gm_comp & gm_rsp_fire & (~(gm_rsp_comp_bad_txn_tma | gm_rsp_comp_bad_op_tma)) & gm_rsp_comp_bad_resp_tma
        gm_rsp_comp_good_tma = state_is_tstore_wait_gm_comp & gm_rsp_fire & (~(gm_rsp_comp_bad_txn_tma | gm_rsp_comp_bad_op_tma)) & (~gm_rsp_comp_bad_resp_tma)
    
        state_next_tma = gm_rsp_comp_good_tma._select_internal(c(S_DONE, width=state_w), state_next_tma)
        status_next_tma = gm_rsp_comp_good_tma._select_internal(c(ST_OK, width=4), status_next_tma)
        err_info_next_tma = gm_rsp_comp_good_tma._select_internal(c(0x0000, width=32), err_info_next_tma)
        state_next_tma = gm_rsp_comp_protocol_tma._select_internal(c(S_DONE, width=state_w), state_next_tma)
        status_next_tma = gm_rsp_comp_protocol_tma._select_internal(c(ST_PROTOCOL_ERR, width=4), status_next_tma)
        err_info_next_tma = gm_rsp_comp_protocol_tma._select_internal(c(0x0205, width=32), err_info_next_tma)
        state_next_tma = gm_rsp_comp_access_tma._select_internal(c(S_DONE, width=state_w), state_next_tma)
        status_next_tma = gm_rsp_comp_access_tma._select_internal(c(ST_ACCESS_ERR, width=4), status_next_tma)
        err_info_next_tma = gm_rsp_comp_access_tma._select_internal(c(0x0206, width=32), err_info_next_tma)
    
        status_next_tma = timeout_hit_tma._select_internal(c(ST_TIMEOUT, width=4), status_next_tma)
        err_info_next_tma = timeout_hit_tma._select_internal(c(0x0F00, width=32), err_info_next_tma)
        state_next_tma = timeout_hit_tma._select_internal(c(S_DONE, width=state_w), state_next_tma)
    
        state_next_tma = state_is_done._select_internal(c(S_IDLE, width=state_w), state_next_tma)
    
        state_q_tma.set(state_next_tma)
        tag_q_tma.set(tag_next_tma)
        payload_q_tma.set(payload_next_tma)
        op_q_tma.set(op_next_tma)
        txnid_q_tma.set(txnid_next_tma)
        dbid_q_tma.set(dbid_next_tma)
        status_q_tma.set(status_next_tma)
        beats_q_tma.set(beats_next_tma)
        elapsed_q_tma.set(elapsed_next_tma)
        err_info_q_tma.set(err_info_next_tma)
        timeout_q_tma.set(timeout_next_tma)
        next_txnid_q_tma.set(next_txnid_next_tma)
    
        gm_req_addr = payload_q_tma.out()
        tr_req_addr = payload_q_tma.out() + c(0x1000, width=64)
        all_be_tma = c((1 << BE_W) - 1, width=BE_W)
    
        m.output("cmd_ready_tma", cmd_ready_tma)
        m.output("rsp_valid_tma", state_is_done)
        m.output("rsp_tag_tma", tag_q_tma.out())
        m.output("rsp_status_tma", status_q_tma.out())
        m.output("rsp_data0_tma", payload_q_tma.out())
        m.output("rsp_data1_tma", payload_q_tma.out() + c(1, width=64))
    
        m.output("gm_req_valid", gm_req_valid)
        m.output("gm_req_opcode", gm_req_opcode)
        m.output("gm_req_txnid", txnid_q_tma.out())
        m.output("gm_req_addr", gm_req_addr)
        m.output("gm_req_size", c(5, width=3))
        m.output("gm_req_len", c(0, width=8))
        m.output("gm_rsp_ready", gm_rsp_ready)
        m.output("gm_dat_rx_ready", gm_dat_rx_ready)
        m.output("gm_dat_tx_valid", gm_dat_tx_valid)
        m.output("gm_dat_tx_opcode", gm_dat_tx_opcode)
        m.output("gm_dat_tx_txnid", txnid_q_tma.out())
        m.output("gm_dat_tx_dbid", dbid_q_tma.out())
        m.output("gm_dat_tx_data", c(0, width=DATA_W))
        m.output("gm_dat_tx_be", all_be_tma)
        m.output("gm_dat_tx_resp", c(0, width=2))
    
        m.output("tr_req_valid", tr_req_valid)
        m.output("tr_req_opcode", tr_req_opcode)
        m.output("tr_req_txnid", txnid_q_tma.out())
        m.output("tr_req_addr", tr_req_addr)
        m.output("tr_req_size", c(5, width=3))
        m.output("tr_req_len", c(0, width=8))
        m.output("tr_rsp_ready", tr_rsp_ready)
        m.output("tr_dat_rx_ready", tr_dat_rx_ready)
        m.output("tr_dat_tx_valid", tr_dat_tx_valid)
        m.output("tr_dat_tx_opcode", tr_dat_tx_opcode)
        m.output("tr_dat_tx_txnid", txnid_q_tma.out())
        m.output("tr_dat_tx_dbid", c(0, width=DBID_W))
        m.output("tr_dat_tx_data", c(0, width=DATA_W))
        m.output("tr_dat_tx_be", all_be_tma)
        m.output("tr_dat_tx_resp", c(0, width=2))
    
        _ = gm_dat_rx_dbid
        _ = gm_dat_rx_data
        _ = gm_dat_rx_be
        _ = tr_rsp_dbid
        _ = tr_dat_rx_dbid
        _ = tr_dat_rx_data
        _ = tr_dat_rx_be
        _ = ST_INTERNAL_ERR
