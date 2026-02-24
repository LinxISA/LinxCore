from __future__ import annotations

from functools import partial

from pycircuit import Circuit, function, module


@function
def _state_reg(m: Circuit, c, clk, rst, name: str, width: int, init: int):
    return m.out(name, clk=clk, rst=rst, width=width, init=c(init, width=width), en=c(1, width=1))


@function
def _word_from_regs(m: Circuit, c, data_w: int, regs: list, idx, idx_w: int):
    _ = m
    out_w = c(0, width=data_w)
    for i, r in enumerate(regs):
        out_w = (idx == c(i, width=idx_w))._select_internal(r.out(), out_w)
    return out_w


@function
def _byte_from_word(m: Circuit, c, word, byte_idx):
    _ = m
    out_b = c(0, width=8)
    for bi in range(32):
        out_b = (byte_idx == c(bi, width=5))._select_internal(word[bi * 8 : (bi + 1) * 8], out_b)
    return out_b


@function
def _byte_from_regs(m: Circuit, c, data_w: int, regs: list, off, word_idx_w: int):
    word_idx = off.lshr(amount=5)._trunc(width=word_idx_w)
    byte_idx = off._trunc(width=5)
    src_word = _word_from_regs(m, c, data_w, regs, word_idx, idx_w=word_idx_w)
    return _byte_from_word(m, c, src_word, byte_idx)


@function
def _beat_from_regs(m: Circuit, c, data_w: int, regs: list, start_off, word_idx_w: int):
    out_d = c(0, width=data_w)
    for bj in range(32):
        boff = start_off._zext(width=16) + c(bj, width=16)
        bval = _byte_from_regs(m, c, data_w, regs, boff, word_idx_w=word_idx_w)
        out_d = out_d | bval._zext(width=data_w).shl(amount=8 * bj)
    return out_d


@module(name="JanusTma")
def build_janus_tma(
    m: Circuit,
    *,
    enable_chi_if: int = 0,
    enable_ut_cfg_if: int = 0,
    ADDR_W: int = 64,
    DATA_W: int = 256,
    TXNID_W: int = 8,
    DBID_W: int = 8,
    BPQ_DEPTH: int = 1,
    RFB_DEPTH: int = 16,
    WCB_DEPTH: int = 16,
    BDB_DEPTH: int = 64,
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
        return

    if DATA_W != 256:
        raise ValueError("Current JanusTma NORM implementation expects DATA_W=256")
    if ADDR_W != 64:
        raise ValueError("Current JanusTma NORM implementation expects ADDR_W=64")
    if DATA_W % 8 != 0:
        raise ValueError("DATA_W must be byte-aligned")

    BE_W = DATA_W // 8

    timeout_init = max(1, int(stub_timeout_cycles))
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

    OP_TLOAD = 0
    OP_TSTORE = 1

    LAYOUT_NORM = 0
    PAD_NULL = 0

    # Command states.
    S_CMD_IDLE = 0
    S_CMD_VALIDATE = 1
    S_CMD_RESP = 2

    S_TL_PREP_ROW = 3
    S_TL_GM_REQ = 4
    S_TL_GM_WAIT_DAT = 5
    S_TL_BUILD_ROW = 6
    S_TL_APPEND_WCB = 7
    S_TL_TR_REQ = 8
    S_TL_TR_SEND_DAT = 9
    S_TL_TR_WAIT_COMP = 10

    S_TS_TR_REQ = 11
    S_TS_TR_WAIT_DAT = 12
    S_TS_BUILD_ROW = 13
    S_TS_GM_REQ = 14
    S_TS_GM_WAIT_DBID = 15
    S_TS_GM_SEND_DAT = 16
    S_TS_GM_WAIT_COMP = 17

    state_w = 6

    # CHI ports.
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

    if enable_ut_cfg_if == 1:
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
    else:
        ut_cfg_layout = c(LAYOUT_NORM, width=3)
        ut_cfg_elem_type = c(0, width=3)
        ut_cfg_pad_mode = c(PAD_NULL, width=2)
        ut_cfg_gm_base_addr = cmd_payload_tma
        ut_cfg_tr_base_addr = cmd_payload_tma + c(0x1000, width=64)
        ut_cfg_gm_inner_elems = c(32, width=16)
        ut_cfg_gm_outer_elems = c(1, width=16)
        ut_cfg_tr_inner_elems = c(32, width=16)
        ut_cfg_tr_outer_elems = c(1, width=16)
        ut_cfg_gm_inner_stride_B = c(256, width=16)

    # Helper aliases for compact call-sites.
    state_reg = partial(_state_reg, m, c, clk_tma, rst_tma)
    word_from_regs = partial(_word_from_regs, m, c, DATA_W)
    byte_from_regs = partial(_byte_from_regs, m, c, DATA_W)
    beat_from_regs = partial(_beat_from_regs, m, c, DATA_W)

    # State regs.
    state_q_tma = state_reg("state_q_tma", state_w, S_CMD_IDLE)
    tag_q_tma = state_reg("tag_q_tma", 8, 0)
    payload_q_tma = state_reg("payload_q_tma", 64, 0)
    op_q_tma = state_reg("op_q_tma", 2, 0)
    txnid_q_tma = state_reg("txnid_q_tma", TXNID_W, 0)
    next_txnid_q_tma = state_reg("next_txnid_q_tma", TXNID_W, 0)
    dbid_q_tma = state_reg("dbid_q_tma", DBID_W, 0)

    status_q_tma = state_reg("status_q_tma", 4, ST_OK)
    err_info_q_tma = state_reg("err_info_q_tma", 32, 0)
    beats_q_tma = state_reg("beats_q_tma", 32, 0)
    elapsed_q_tma = state_reg("elapsed_q_tma", 32, 0)
    timeout_q_tma = state_reg("timeout_q_tma", timeout_w, 0)

    cfg_layout_q_tma = state_reg("cfg_layout_q_tma", 3, 0)
    cfg_elem_q_tma = state_reg("cfg_elem_q_tma", 3, 0)
    cfg_pad_q_tma = state_reg("cfg_pad_q_tma", 2, 0)
    gm_base_q_tma = state_reg("gm_base_q_tma", 64, 0)
    tr_base_q_tma = state_reg("tr_base_q_tma", 64, 0)
    gm_inner_q_tma = state_reg("gm_inner_q_tma", 16, 0)
    gm_outer_q_tma = state_reg("gm_outer_q_tma", 16, 0)
    tr_inner_q_tma = state_reg("tr_inner_q_tma", 16, 0)
    tr_outer_q_tma = state_reg("tr_outer_q_tma", 16, 0)
    gm_stride_q_tma = state_reg("gm_stride_q_tma", 16, 0)

    row_bytes_q_tma = state_reg("row_bytes_q_tma", 16, 0)
    row_beats_q_tma = state_reg("row_beats_q_tma", 4, 0)
    rows_per_chunk_q_tma = state_reg("rows_per_chunk_q_tma", 4, 0)

    row_idx_q_tma = state_reg("row_idx_q_tma", 16, 0)
    chunk_idx_q_tma = state_reg("chunk_idx_q_tma", 16, 0)
    chunk_row_idx_q_tma = state_reg("chunk_row_idx_q_tma", 4, 0)

    req_addr_q_tma = state_reg("req_addr_q_tma", 64, 0)
    req_len_q_tma = state_reg("req_len_q_tma", 8, 0)
    req_second_addr_q_tma = state_reg("req_second_addr_q_tma", 64, 0)
    req_second_pending_q_tma = state_reg("req_second_pending_q_tma", 1, 0)
    req_phase_q_tma = state_reg("req_phase_q_tma", 1, 0)
    first_req_addr_q_tma = state_reg("first_req_addr_q_tma", 64, 0)

    recv_idx_q_tma = state_reg("recv_idx_q_tma", 4, 0)
    exp_beats_q_tma = state_reg("exp_beats_q_tma", 4, 0)
    gm_store_base_q_tma = state_reg("gm_store_base_q_tma", 5, 0)
    send_idx_q_tma = state_reg("send_idx_q_tma", 4, 0)
    wcb_fill_q_tma = state_reg("wcb_fill_q_tma", 4, 0)

    gm_buf_regs = [state_reg(f"gm_buf{i}_tma", DATA_W, 0) for i in range(16)]
    row_buf_regs = [state_reg(f"row_buf{i}_tma", DATA_W, 0) for i in range(8)]
    wcb_buf_regs = [state_reg(f"wcb_buf{i}_tma", DATA_W, 0) for i in range(8)]
    tr_chunk_regs = [state_reg(f"tr_chunk{i}_tma", DATA_W, 0) for i in range(8)]

    # State decodes.
    state_is_idle = state_q_tma.out() == (c(S_CMD_IDLE, width=state_w))
    state_is_validate = state_q_tma.out() == (c(S_CMD_VALIDATE, width=state_w))
    state_is_resp = state_q_tma.out() == (c(S_CMD_RESP, width=state_w))

    state_is_tl_prep_row = state_q_tma.out() == (c(S_TL_PREP_ROW, width=state_w))
    state_is_tl_gm_req = state_q_tma.out() == (c(S_TL_GM_REQ, width=state_w))
    state_is_tl_gm_wait_dat = state_q_tma.out() == (c(S_TL_GM_WAIT_DAT, width=state_w))
    state_is_tl_build_row = state_q_tma.out() == (c(S_TL_BUILD_ROW, width=state_w))
    state_is_tl_append_wcb = state_q_tma.out() == (c(S_TL_APPEND_WCB, width=state_w))
    state_is_tl_tr_req = state_q_tma.out() == (c(S_TL_TR_REQ, width=state_w))
    state_is_tl_tr_send_dat = state_q_tma.out() == (c(S_TL_TR_SEND_DAT, width=state_w))
    state_is_tl_tr_wait_comp = state_q_tma.out() == (c(S_TL_TR_WAIT_COMP, width=state_w))

    state_is_ts_tr_req = state_q_tma.out() == (c(S_TS_TR_REQ, width=state_w))
    state_is_ts_tr_wait_dat = state_q_tma.out() == (c(S_TS_TR_WAIT_DAT, width=state_w))
    state_is_ts_build_row = state_q_tma.out() == (c(S_TS_BUILD_ROW, width=state_w))
    state_is_ts_gm_req = state_q_tma.out() == (c(S_TS_GM_REQ, width=state_w))
    state_is_ts_gm_wait_dbid = state_q_tma.out() == (c(S_TS_GM_WAIT_DBID, width=state_w))
    state_is_ts_gm_send_dat = state_q_tma.out() == (c(S_TS_GM_SEND_DAT, width=state_w))
    state_is_ts_gm_wait_comp = state_q_tma.out() == (c(S_TS_GM_WAIT_COMP, width=state_w))

    # Common wires.
    cmd_ready_tma = state_is_idle
    accept_tma = cmd_valid_tma & cmd_ready_tma

    payload_op_tma = cmd_payload_tma[0:2]
    payload_decode_err_tma = cmd_payload_tma[63]
    payload_is_tload_tma = payload_op_tma == (c(OP_TLOAD, width=2))
    payload_is_tstore_tma = payload_op_tma == (c(OP_TSTORE, width=2))
    payload_supported_tma = payload_is_tload_tma | payload_is_tstore_tma

    # Element width / row shape decode.
    elem_is_i8_tma = cfg_elem_q_tma.out() == (c(0, width=3))
    elem_is_i16_tma = (cfg_elem_q_tma.out() == c(1, width=3)) | (cfg_elem_q_tma.out() == c(3, width=3))
    elem_is_i32_tma = (cfg_elem_q_tma.out() == c(2, width=3)) | (cfg_elem_q_tma.out() == c(4, width=3))
    elem_valid_tma = elem_is_i8_tma | elem_is_i16_tma | elem_is_i32_tma

    elem_bytes_tma = c(1, width=16)
    elem_bytes_tma = elem_is_i16_tma._select_internal(c(2, width=16), elem_bytes_tma)
    elem_bytes_tma = elem_is_i32_tma._select_internal(c(4, width=16), elem_bytes_tma)

    row_bytes_raw_tma = (gm_inner_q_tma.out()._zext(width=18) * elem_bytes_tma._zext(width=18))._trunc(width=16)

    row_bytes_is32_tma = row_bytes_raw_tma == (c(32, width=16))
    row_bytes_is64_tma = row_bytes_raw_tma == (c(64, width=16))
    row_bytes_is128_tma = row_bytes_raw_tma == (c(128, width=16))
    row_bytes_is256_tma = row_bytes_raw_tma == (c(256, width=16))
    row_bytes_valid_tma = row_bytes_is32_tma | row_bytes_is64_tma | row_bytes_is128_tma | row_bytes_is256_tma

    row_beats_calc_tma = c(1, width=4)
    row_beats_calc_tma = row_bytes_is64_tma._select_internal(c(2, width=4), row_beats_calc_tma)
    row_beats_calc_tma = row_bytes_is128_tma._select_internal(c(4, width=4), row_beats_calc_tma)
    row_beats_calc_tma = row_bytes_is256_tma._select_internal(c(8, width=4), row_beats_calc_tma)

    rows_per_chunk_calc_tma = c(8, width=4)
    rows_per_chunk_calc_tma = row_bytes_is64_tma._select_internal(c(4, width=4), rows_per_chunk_calc_tma)
    rows_per_chunk_calc_tma = row_bytes_is128_tma._select_internal(c(2, width=4), rows_per_chunk_calc_tma)
    rows_per_chunk_calc_tma = row_bytes_is256_tma._select_internal(c(1, width=4), rows_per_chunk_calc_tma)

    gm_base_b0_tma = gm_base_q_tma.out()[0:1]
    gm_base_b1_tma = gm_base_q_tma.out()[1:2]
    gm_elem_align_bad_tma = (elem_is_i16_tma & (gm_base_b0_tma == c(1, width=1))) | (
        elem_is_i32_tma & ((gm_base_b0_tma == c(1, width=1)) | (gm_base_b1_tma == c(1, width=1)))
    )

    layout_norm_tma = cfg_layout_q_tma.out() == (c(LAYOUT_NORM, width=3))
    pad_null_tma = cfg_pad_q_tma.out() == (c(PAD_NULL, width=2))

    inner_match_tma = gm_inner_q_tma.out() == (tr_inner_q_tma.out())
    outer_match_tma = gm_outer_q_tma.out() == (tr_outer_q_tma.out())
    outer_nonzero_tma = ~(gm_outer_q_tma.out() == c(0, width=16))

    outer_mod8_bad_tma = row_bytes_is32_tma & (~(gm_outer_q_tma.out()[0:3] == c(0, width=3)))
    outer_mod4_bad_tma = row_bytes_is64_tma & (~(gm_outer_q_tma.out()[0:2] == c(0, width=2)))
    outer_mod2_bad_tma = row_bytes_is128_tma & (~(gm_outer_q_tma.out()[0:1] == c(0, width=1)))
    outer_div_bad_tma = outer_mod8_bad_tma | outer_mod4_bad_tma | outer_mod2_bad_tma

    stride_ok_tma = gm_stride_q_tma.out().uge(c(128, width=16))

    unsupported_cfg_tma = (~layout_norm_tma) | (~pad_null_tma)
    decode_bad_cfg_tma = (
        (~elem_valid_tma)
        | (~row_bytes_valid_tma)
        | (~inner_match_tma)
        | (~outer_match_tma)
        | (~outer_nonzero_tma)
        | outer_div_bad_tma
        | gm_elem_align_bad_tma
        | (~stride_ok_tma)
    )

    # Row/chunk address helpers.
    row_off_tma = (row_idx_q_tma.out()._zext(width=64) * gm_stride_q_tma.out()._zext(width=64))._trunc(width=64)
    row_addr_tma = gm_base_q_tma.out() + row_off_tma
    row_end_tma = row_addr_tma + row_bytes_q_tma.out()._zext(width=64)

    row_off128_tma = row_addr_tma[0:7]._zext(width=16)
    row_off256_tma = row_addr_tma[0:8]._zext(width=16)

    fit128_tma = ~(row_off128_tma + row_bytes_q_tma.out()._zext(width=16)).ugt(c(128, width=16))
    fit256_tma = ~(row_off256_tma + row_bytes_q_tma.out()._zext(width=16)).ugt(c(256, width=16))

    align128_mask = ((1 << 64) - 1) ^ ((1 << 7) - 1)
    align256_mask = ((1 << 64) - 1) ^ ((1 << 8) - 1)
    row_base128_tma = row_addr_tma & c(align128_mask, width=64)
    row_base256_tma = row_addr_tma & c(align256_mask, width=64)

    req0_addr_plan_tma = fit128_tma._select_internal(row_base128_tma, row_base256_tma)
    req0_len_plan_tma = fit128_tma._select_internal(c(3, width=8), c(7, width=8))
    req1_addr_plan_tma = row_base256_tma + c(256, width=64)
    req2_needed_plan_tma = (~fit128_tma) & (~fit256_tma)

    tl_rows_after_append_tma = row_idx_q_tma.out() + c(1, width=16)
    tl_fill_after_append_tma = wcb_fill_q_tma.out() + row_beats_q_tma.out()
    tl_flush_after_append_tma = tl_fill_after_append_tma == (c(8, width=4))

    ts_rows_after_done_tma = row_idx_q_tma.out() + c(1, width=16)
    ts_chunk_row_after_done_tma = chunk_row_idx_q_tma.out() + c(1, width=4)
    ts_chunk_wrap_tma = ts_chunk_row_after_done_tma == (rows_per_chunk_q_tma.out())

    tr_chunk_addr_tma = tr_base_q_tma.out() + chunk_idx_q_tma.out()._zext(width=64).shl(amount=8)

    # Interface drive.
    gm_req_valid = state_is_tl_gm_req | state_is_ts_gm_req
    gm_req_opcode = state_is_tl_gm_req._select_internal(c(OPC_REQ_READ_ONCE, width=7), c(OPC_REQ_WRITE_UNIQUE, width=7))
    gm_req_fire = gm_req_valid & gm_req_ready

    tr_req_valid = state_is_tl_tr_req | state_is_ts_tr_req
    tr_req_opcode = state_is_tl_tr_req._select_internal(c(OPC_REQ_WRITE_NOSNP, width=7), c(OPC_REQ_READ_NOSNP, width=7))
    tr_req_fire = tr_req_valid & tr_req_ready

    gm_rsp_ready = state_is_ts_gm_wait_dbid | state_is_ts_gm_wait_comp
    tr_rsp_ready = state_is_tl_tr_wait_comp
    gm_dat_rx_ready = state_is_tl_gm_wait_dat
    tr_dat_rx_ready = state_is_ts_tr_wait_dat

    gm_rsp_fire = gm_rsp_valid & gm_rsp_ready
    tr_rsp_fire = tr_rsp_valid & tr_rsp_ready
    gm_dat_rx_fire = gm_dat_rx_valid & gm_dat_rx_ready
    tr_dat_rx_fire = tr_dat_rx_valid & tr_dat_rx_ready

    tr_dat_tx_valid = state_is_tl_tr_send_dat
    gm_dat_tx_valid = state_is_ts_gm_send_dat
    tr_dat_tx_fire = tr_dat_tx_valid & tr_dat_tx_ready
    gm_dat_tx_fire = gm_dat_tx_valid & gm_dat_tx_ready

    tr_dat_tx_word_tma = word_from_regs(wcb_buf_regs, send_idx_q_tma.out()._trunc(width=3), idx_w=3)

    gm_beat_addr_tma = req_addr_q_tma.out() + send_idx_q_tma.out()._zext(width=64).shl(amount=5)
    gm_dat_tx_data_tma = c(0, width=DATA_W)
    gm_dat_tx_be_tma = c(0, width=BE_W)
    for bj in range(32):
        abs_addr_bj_tma = gm_beat_addr_tma + c(bj, width=64)
        in_row_bj_tma = abs_addr_bj_tma.uge(row_addr_tma) & abs_addr_bj_tma.ult(row_end_tma)
        src_off_bj_tma = (abs_addr_bj_tma - row_addr_tma)._trunc(width=16)
        src_byte_bj_tma = byte_from_regs(row_buf_regs, src_off_bj_tma, word_idx_w=3)
        out_byte_bj_tma = in_row_bj_tma._select_internal(src_byte_bj_tma, c(0, width=8))
        gm_dat_tx_data_tma = gm_dat_tx_data_tma | out_byte_bj_tma._zext(width=DATA_W).shl(amount=8 * bj)
        gm_dat_tx_be_tma = in_row_bj_tma._select_internal(gm_dat_tx_be_tma | c(1 << bj, width=BE_W), gm_dat_tx_be_tma)

    # Core state transition/update skeleton.
    state_next_tma = state_q_tma.out()
    tag_next_tma = tag_q_tma.out()
    payload_next_tma = payload_q_tma.out()
    op_next_tma = op_q_tma.out()
    txnid_next_tma = txnid_q_tma.out()
    next_txnid_next_tma = next_txnid_q_tma.out()
    dbid_next_tma = dbid_q_tma.out()

    status_next_tma = status_q_tma.out()
    err_info_next_tma = err_info_q_tma.out()
    beats_next_tma = beats_q_tma.out()
    elapsed_next_tma = elapsed_q_tma.out()
    timeout_next_tma = timeout_q_tma.out()

    cfg_layout_next_tma = cfg_layout_q_tma.out()
    cfg_elem_next_tma = cfg_elem_q_tma.out()
    cfg_pad_next_tma = cfg_pad_q_tma.out()
    gm_base_next_tma = gm_base_q_tma.out()
    tr_base_next_tma = tr_base_q_tma.out()
    gm_inner_next_tma = gm_inner_q_tma.out()
    gm_outer_next_tma = gm_outer_q_tma.out()
    tr_inner_next_tma = tr_inner_q_tma.out()
    tr_outer_next_tma = tr_outer_q_tma.out()
    gm_stride_next_tma = gm_stride_q_tma.out()

    row_bytes_next_tma = row_bytes_q_tma.out()
    row_beats_next_tma = row_beats_q_tma.out()
    rows_per_chunk_next_tma = rows_per_chunk_q_tma.out()

    row_idx_next_tma = row_idx_q_tma.out()
    chunk_idx_next_tma = chunk_idx_q_tma.out()
    chunk_row_idx_next_tma = chunk_row_idx_q_tma.out()

    req_addr_next_tma = req_addr_q_tma.out()
    req_len_next_tma = req_len_q_tma.out()
    req_second_addr_next_tma = req_second_addr_q_tma.out()
    req_second_pending_next_tma = req_second_pending_q_tma.out()
    req_phase_next_tma = req_phase_q_tma.out()
    first_req_addr_next_tma = first_req_addr_q_tma.out()

    recv_idx_next_tma = recv_idx_q_tma.out()
    exp_beats_next_tma = exp_beats_q_tma.out()
    gm_store_base_next_tma = gm_store_base_q_tma.out()
    send_idx_next_tma = send_idx_q_tma.out()
    wcb_fill_next_tma = wcb_fill_q_tma.out()

    progress_tma = (
        accept_tma
        | gm_req_fire
        | tr_req_fire
        | gm_rsp_fire
        | tr_rsp_fire
        | gm_dat_rx_fire
        | tr_dat_rx_fire
        | gm_dat_tx_fire
        | tr_dat_tx_fire
    )

    active_state_tma = (~state_is_idle) & (~state_is_resp)
    elapsed_next_tma = active_state_tma._select_internal(elapsed_q_tma.out() + c(1, width=32), elapsed_next_tma)

    timeout_is_zero_tma = timeout_q_tma.out() == (c(0, width=timeout_w))
    timeout_reload_tma = active_state_tma & progress_tma
    timeout_dec_tma = active_state_tma & (~progress_tma) & (timeout_q_tma.out().ugt(c(0, width=timeout_w)))
    timeout_hit_tma = active_state_tma & (~progress_tma) & timeout_is_zero_tma
    timeout_next_tma = timeout_reload_tma._select_internal(timeout_init_c, timeout_next_tma)
    timeout_next_tma = timeout_dec_tma._select_internal(timeout_q_tma.out() - c(1, width=timeout_w), timeout_next_tma)

    beat_inc_tma = gm_dat_rx_fire | tr_dat_rx_fire | gm_dat_tx_fire | tr_dat_tx_fire
    beats_next_tma = beat_inc_tma._select_internal(beats_q_tma.out() + c(1, width=32), beats_next_tma)

    # Accept new command.
    tag_next_tma = accept_tma._select_internal(cmd_tag_tma, tag_next_tma)
    payload_next_tma = accept_tma._select_internal(cmd_payload_tma, payload_next_tma)
    txnid_next_tma = accept_tma._select_internal(next_txnid_q_tma.out(), txnid_next_tma)
    next_txnid_next_tma = accept_tma._select_internal(next_txnid_q_tma.out() + c(1, width=TXNID_W), next_txnid_next_tma)

    status_next_tma = accept_tma._select_internal(c(ST_OK, width=4), status_next_tma)
    err_info_next_tma = accept_tma._select_internal(c(0, width=32), err_info_next_tma)
    beats_next_tma = accept_tma._select_internal(c(0, width=32), beats_next_tma)
    elapsed_next_tma = accept_tma._select_internal(c(0, width=32), elapsed_next_tma)
    timeout_next_tma = accept_tma._select_internal(timeout_init_c, timeout_next_tma)

    cfg_layout_next_tma = accept_tma._select_internal(ut_cfg_layout, cfg_layout_next_tma)
    cfg_elem_next_tma = accept_tma._select_internal(ut_cfg_elem_type, cfg_elem_next_tma)
    cfg_pad_next_tma = accept_tma._select_internal(ut_cfg_pad_mode, cfg_pad_next_tma)
    gm_base_next_tma = accept_tma._select_internal(ut_cfg_gm_base_addr, gm_base_next_tma)
    tr_base_next_tma = accept_tma._select_internal(ut_cfg_tr_base_addr, tr_base_next_tma)
    gm_inner_next_tma = accept_tma._select_internal(ut_cfg_gm_inner_elems, gm_inner_next_tma)
    gm_outer_next_tma = accept_tma._select_internal(ut_cfg_gm_outer_elems, gm_outer_next_tma)
    tr_inner_next_tma = accept_tma._select_internal(ut_cfg_tr_inner_elems, tr_inner_next_tma)
    tr_outer_next_tma = accept_tma._select_internal(ut_cfg_tr_outer_elems, tr_outer_next_tma)
    gm_stride_next_tma = accept_tma._select_internal(ut_cfg_gm_inner_stride_B, gm_stride_next_tma)

    row_idx_next_tma = accept_tma._select_internal(c(0, width=16), row_idx_next_tma)
    chunk_idx_next_tma = accept_tma._select_internal(c(0, width=16), chunk_idx_next_tma)
    chunk_row_idx_next_tma = accept_tma._select_internal(c(0, width=4), chunk_row_idx_next_tma)
    req_phase_next_tma = accept_tma._select_internal(c(0, width=1), req_phase_next_tma)
    req_second_pending_next_tma = accept_tma._select_internal(c(0, width=1), req_second_pending_next_tma)
    recv_idx_next_tma = accept_tma._select_internal(c(0, width=4), recv_idx_next_tma)
    exp_beats_next_tma = accept_tma._select_internal(c(0, width=4), exp_beats_next_tma)
    gm_store_base_next_tma = accept_tma._select_internal(c(0, width=5), gm_store_base_next_tma)
    send_idx_next_tma = accept_tma._select_internal(c(0, width=4), send_idx_next_tma)
    wcb_fill_next_tma = accept_tma._select_internal(c(0, width=4), wcb_fill_next_tma)

    state_next_tma = accept_tma._select_internal(c(S_CMD_VALIDATE, width=state_w), state_next_tma)

    # Validation state.
    val_payload_decode_err_tma = state_is_validate & payload_q_tma.out()[63]
    val_op_is_tload_tma = payload_q_tma.out()[0:2] == (c(OP_TLOAD, width=2))
    val_op_is_tstore_tma = payload_q_tma.out()[0:2] == (c(OP_TSTORE, width=2))
    val_op_supported_tma = val_op_is_tload_tma | val_op_is_tstore_tma

    val_decode_bad_tma = state_is_validate & (val_payload_decode_err_tma | decode_bad_cfg_tma)
    val_unsupported_tma = state_is_validate & (~val_payload_decode_err_tma) & (~val_decode_bad_tma) & (
        (~val_op_supported_tma) | unsupported_cfg_tma
    )

    val_start_tload_tma = state_is_validate & (~val_decode_bad_tma) & (~val_unsupported_tma) & val_op_is_tload_tma
    val_start_tstore_tma = state_is_validate & (~val_decode_bad_tma) & (~val_unsupported_tma) & val_op_is_tstore_tma

    state_next_tma = val_decode_bad_tma._select_internal(c(S_CMD_RESP, width=state_w), state_next_tma)
    status_next_tma = val_decode_bad_tma._select_internal(c(ST_DECODE_ERR, width=4), status_next_tma)
    err_info_next_tma = val_decode_bad_tma._select_internal(c(0x0001, width=32), err_info_next_tma)

    state_next_tma = val_unsupported_tma._select_internal(c(S_CMD_RESP, width=state_w), state_next_tma)
    status_next_tma = val_unsupported_tma._select_internal(c(ST_UNSUPPORTED, width=4), status_next_tma)
    err_info_next_tma = val_unsupported_tma._select_internal(c(0x0002, width=32), err_info_next_tma)

    op_next_tma = val_start_tload_tma._select_internal(c(OP_TLOAD, width=2), op_next_tma)
    op_next_tma = val_start_tstore_tma._select_internal(c(OP_TSTORE, width=2), op_next_tma)

    row_bytes_next_tma = (val_start_tload_tma | val_start_tstore_tma)._select_internal(row_bytes_raw_tma, row_bytes_next_tma)
    row_beats_next_tma = (val_start_tload_tma | val_start_tstore_tma)._select_internal(row_beats_calc_tma, row_beats_next_tma)
    rows_per_chunk_next_tma = (val_start_tload_tma | val_start_tstore_tma)._select_internal(rows_per_chunk_calc_tma, rows_per_chunk_next_tma)

    state_next_tma = val_start_tload_tma._select_internal(c(S_TL_PREP_ROW, width=state_w), state_next_tma)
    state_next_tma = val_start_tstore_tma._select_internal(c(S_TS_TR_REQ, width=state_w), state_next_tma)

    # TLOAD flow.
    tl_prep_fire_tma = state_is_tl_prep_row
    req_addr_next_tma = tl_prep_fire_tma._select_internal(req0_addr_plan_tma, req_addr_next_tma)
    req_len_next_tma = tl_prep_fire_tma._select_internal(req0_len_plan_tma, req_len_next_tma)
    req_second_addr_next_tma = tl_prep_fire_tma._select_internal(req1_addr_plan_tma, req_second_addr_next_tma)
    req_second_pending_next_tma = tl_prep_fire_tma._select_internal(req2_needed_plan_tma, req_second_pending_next_tma)
    req_phase_next_tma = tl_prep_fire_tma._select_internal(c(0, width=1), req_phase_next_tma)
    first_req_addr_next_tma = tl_prep_fire_tma._select_internal(req0_addr_plan_tma, first_req_addr_next_tma)
    recv_idx_next_tma = tl_prep_fire_tma._select_internal(c(0, width=4), recv_idx_next_tma)
    exp_beats_next_tma = tl_prep_fire_tma._select_internal(req0_len_plan_tma[0:4] + c(1, width=4), exp_beats_next_tma)
    gm_store_base_next_tma = tl_prep_fire_tma._select_internal(c(0, width=5), gm_store_base_next_tma)
    state_next_tma = tl_prep_fire_tma._select_internal(c(S_TL_GM_REQ, width=state_w), state_next_tma)

    tl_gm_req_fire_tma = state_is_tl_gm_req & gm_req_fire
    state_next_tma = tl_gm_req_fire_tma._select_internal(c(S_TL_GM_WAIT_DAT, width=state_w), state_next_tma)

    tl_gm_dat_last_tma = state_is_tl_gm_wait_dat & gm_dat_rx_fire & (recv_idx_q_tma.out() == (exp_beats_q_tma.out() - c(1, width=4)))
    tl_need_second_tma = tl_gm_dat_last_tma & req_second_pending_q_tma.out() & (req_phase_q_tma.out() == c(0, width=1))
    tl_read_done_tma = tl_gm_dat_last_tma & (~tl_need_second_tma)

    recv_idx_next_tma = (state_is_tl_gm_wait_dat & gm_dat_rx_fire)._select_internal(recv_idx_q_tma.out() + c(1, width=4), recv_idx_next_tma)

    req_addr_next_tma = tl_need_second_tma._select_internal(req_second_addr_q_tma.out(), req_addr_next_tma)
    req_len_next_tma = tl_need_second_tma._select_internal(c(7, width=8), req_len_next_tma)
    req_phase_next_tma = tl_need_second_tma._select_internal(c(1, width=1), req_phase_next_tma)
    recv_idx_next_tma = tl_need_second_tma._select_internal(c(0, width=4), recv_idx_next_tma)
    exp_beats_next_tma = tl_need_second_tma._select_internal(c(8, width=4), exp_beats_next_tma)
    gm_store_base_next_tma = tl_need_second_tma._select_internal(c(8, width=5), gm_store_base_next_tma)
    state_next_tma = tl_need_second_tma._select_internal(c(S_TL_GM_REQ, width=state_w), state_next_tma)
    state_next_tma = tl_read_done_tma._select_internal(c(S_TL_BUILD_ROW, width=state_w), state_next_tma)

    # Build row buffer candidates from GM read windows / TR read chunk.
    row_src_base_tma = (row_addr_tma - first_req_addr_q_tma.out())._trunc(width=16)
    chunk_row_base_tma = (chunk_row_idx_q_tma.out()._zext(width=5) * row_beats_q_tma.out()._zext(width=5))._trunc(width=4)
    for bi in range(8):
        row_word_valid_i_tma = row_beats_q_tma.out().ugt(c(bi, width=4))

        row_start_off_i_tma = row_src_base_tma + c(32 * bi, width=16)
        row_word_from_gm_i_tma = beat_from_regs(gm_buf_regs, row_start_off_i_tma, word_idx_w=4)
        row_word_from_gm_i_tma = row_word_valid_i_tma._select_internal(row_word_from_gm_i_tma, c(0, width=DATA_W))

        src_idx_i_tma = (chunk_row_base_tma + c(bi, width=4))._trunc(width=3)
        row_word_from_tr_i_tma = word_from_regs(tr_chunk_regs, src_idx_i_tma, idx_w=3)
        row_word_from_tr_i_tma = row_word_valid_i_tma._select_internal(row_word_from_tr_i_tma, c(0, width=DATA_W))

        row_word_next_i_tma = row_buf_regs[bi].out()
        row_word_next_i_tma = state_is_tl_build_row._select_internal(row_word_from_gm_i_tma, row_word_next_i_tma)
        row_word_next_i_tma = state_is_ts_build_row._select_internal(row_word_from_tr_i_tma, row_word_next_i_tma)
        row_buf_regs[bi].set(row_word_next_i_tma)

    state_next_tma = state_is_tl_build_row._select_internal(c(S_TL_APPEND_WCB, width=state_w), state_next_tma)

    # Append row to WCB and decide if a 256B TR write is ready.
    for dj in range(8):
        wcb_word_next_j_tma = wcb_buf_regs[dj].out()
        for si in range(8):
            src_valid_i_tma = row_beats_q_tma.out().ugt(c(si, width=4))
            dst_hit_i_tma = (wcb_fill_q_tma.out() + c(si, width=4)) == (c(dj, width=4))
            take_i_tma = src_valid_i_tma & dst_hit_i_tma & state_is_tl_append_wcb
            wcb_word_next_j_tma = take_i_tma._select_internal(row_buf_regs[si].out(), wcb_word_next_j_tma)
        wcb_buf_regs[dj].set(wcb_word_next_j_tma)

    row_idx_next_tma = state_is_tl_append_wcb._select_internal(tl_rows_after_append_tma, row_idx_next_tma)
    wcb_fill_next_tma = state_is_tl_append_wcb._select_internal(tl_fill_after_append_tma, wcb_fill_next_tma)
    state_next_tma = (state_is_tl_append_wcb & (~tl_flush_after_append_tma))._select_internal(c(S_TL_PREP_ROW, width=state_w), state_next_tma)
    state_next_tma = (state_is_tl_append_wcb & tl_flush_after_append_tma)._select_internal(c(S_TL_TR_REQ, width=state_w), state_next_tma)

    tl_tr_req_fire_tma = state_is_tl_tr_req & tr_req_fire
    send_idx_next_tma = tl_tr_req_fire_tma._select_internal(c(0, width=4), send_idx_next_tma)
    state_next_tma = tl_tr_req_fire_tma._select_internal(c(S_TL_TR_SEND_DAT, width=state_w), state_next_tma)

    tl_tr_dat_last_tma = state_is_tl_tr_send_dat & tr_dat_tx_fire & (send_idx_q_tma.out() == c(7, width=4))
    send_idx_next_tma = (state_is_tl_tr_send_dat & tr_dat_tx_fire)._select_internal(send_idx_q_tma.out() + c(1, width=4), send_idx_next_tma)
    state_next_tma = tl_tr_dat_last_tma._select_internal(c(S_TL_TR_WAIT_COMP, width=state_w), state_next_tma)

    tl_tr_comp_good_tma = (
        state_is_tl_tr_wait_comp
        & tr_rsp_fire
        & (tr_rsp_opcode == c(OPC_RSP_COMP, width=6))
        & (tr_rsp_txnid == txnid_q_tma.out())
        & (tr_rsp_resp == c(0, width=2))
    )
    tl_tr_comp_bad_proto_tma = state_is_tl_tr_wait_comp & tr_rsp_fire & (
        (~(tr_rsp_opcode == c(OPC_RSP_COMP, width=6))) | (~(tr_rsp_txnid == txnid_q_tma.out()))
    )
    tl_tr_comp_bad_resp_tma = (
        state_is_tl_tr_wait_comp
        & tr_rsp_fire
        & (~tl_tr_comp_bad_proto_tma)
        & (~(tr_rsp_resp == c(0, width=2)))
    )

    chunk_idx_next_tma = tl_tr_comp_good_tma._select_internal(chunk_idx_q_tma.out() + c(1, width=16), chunk_idx_next_tma)
    wcb_fill_next_tma = tl_tr_comp_good_tma._select_internal(c(0, width=4), wcb_fill_next_tma)
    tl_done_tma = tl_tr_comp_good_tma & (row_idx_q_tma.out() == gm_outer_q_tma.out())
    tl_more_tma = tl_tr_comp_good_tma & (~tl_done_tma)
    state_next_tma = tl_done_tma._select_internal(c(S_CMD_RESP, width=state_w), state_next_tma)
    status_next_tma = tl_done_tma._select_internal(c(ST_OK, width=4), status_next_tma)
    err_info_next_tma = tl_done_tma._select_internal(c(0, width=32), err_info_next_tma)
    state_next_tma = tl_more_tma._select_internal(c(S_TL_PREP_ROW, width=state_w), state_next_tma)

    # TSTORE flow.
    ts_tr_req_fire_tma = state_is_ts_tr_req & tr_req_fire
    recv_idx_next_tma = ts_tr_req_fire_tma._select_internal(c(0, width=4), recv_idx_next_tma)
    state_next_tma = ts_tr_req_fire_tma._select_internal(c(S_TS_TR_WAIT_DAT, width=state_w), state_next_tma)

    ts_tr_dat_last_tma = state_is_ts_tr_wait_dat & tr_dat_rx_fire & (recv_idx_q_tma.out() == c(7, width=4))
    recv_idx_next_tma = (state_is_ts_tr_wait_dat & tr_dat_rx_fire)._select_internal(recv_idx_q_tma.out() + c(1, width=4), recv_idx_next_tma)
    state_next_tma = ts_tr_dat_last_tma._select_internal(c(S_TS_BUILD_ROW, width=state_w), state_next_tma)

    req_addr_next_tma = state_is_ts_build_row._select_internal(req0_addr_plan_tma, req_addr_next_tma)
    req_len_next_tma = state_is_ts_build_row._select_internal(req0_len_plan_tma, req_len_next_tma)
    req_second_addr_next_tma = state_is_ts_build_row._select_internal(req1_addr_plan_tma, req_second_addr_next_tma)
    req_second_pending_next_tma = state_is_ts_build_row._select_internal(req2_needed_plan_tma, req_second_pending_next_tma)
    req_phase_next_tma = state_is_ts_build_row._select_internal(c(0, width=1), req_phase_next_tma)
    first_req_addr_next_tma = state_is_ts_build_row._select_internal(req0_addr_plan_tma, first_req_addr_next_tma)
    state_next_tma = state_is_ts_build_row._select_internal(c(S_TS_GM_REQ, width=state_w), state_next_tma)

    ts_gm_req_fire_tma = state_is_ts_gm_req & gm_req_fire
    state_next_tma = ts_gm_req_fire_tma._select_internal(c(S_TS_GM_WAIT_DBID, width=state_w), state_next_tma)

    ts_gm_dbid_good_tma = (
        state_is_ts_gm_wait_dbid
        & gm_rsp_fire
        & (gm_rsp_opcode == c(OPC_RSP_COMP_DBID, width=6))
        & (gm_rsp_txnid == txnid_q_tma.out())
        & (gm_rsp_resp == c(0, width=2))
    )
    ts_gm_dbid_bad_proto_tma = state_is_ts_gm_wait_dbid & gm_rsp_fire & (
        (~(gm_rsp_opcode == c(OPC_RSP_COMP_DBID, width=6))) | (~(gm_rsp_txnid == txnid_q_tma.out()))
    )
    ts_gm_dbid_bad_resp_tma = (
        state_is_ts_gm_wait_dbid
        & gm_rsp_fire
        & (~ts_gm_dbid_bad_proto_tma)
        & (~(gm_rsp_resp == c(0, width=2)))
    )

    dbid_next_tma = ts_gm_dbid_good_tma._select_internal(gm_rsp_dbid, dbid_next_tma)
    send_idx_next_tma = ts_gm_dbid_good_tma._select_internal(c(0, width=4), send_idx_next_tma)
    state_next_tma = ts_gm_dbid_good_tma._select_internal(c(S_TS_GM_SEND_DAT, width=state_w), state_next_tma)

    ts_gm_dat_last_tma = state_is_ts_gm_send_dat & gm_dat_tx_fire & (send_idx_q_tma.out() == req_len_q_tma.out()[0:4])
    send_idx_next_tma = (state_is_ts_gm_send_dat & gm_dat_tx_fire)._select_internal(send_idx_q_tma.out() + c(1, width=4), send_idx_next_tma)
    state_next_tma = ts_gm_dat_last_tma._select_internal(c(S_TS_GM_WAIT_COMP, width=state_w), state_next_tma)

    ts_gm_comp_good_tma = (
        state_is_ts_gm_wait_comp
        & gm_rsp_fire
        & (gm_rsp_opcode == c(OPC_RSP_COMP, width=6))
        & (gm_rsp_txnid == txnid_q_tma.out())
        & (gm_rsp_resp == c(0, width=2))
    )
    ts_gm_comp_bad_proto_tma = state_is_ts_gm_wait_comp & gm_rsp_fire & (
        (~(gm_rsp_opcode == c(OPC_RSP_COMP, width=6))) | (~(gm_rsp_txnid == txnid_q_tma.out()))
    )
    ts_gm_comp_bad_resp_tma = (
        state_is_ts_gm_wait_comp
        & gm_rsp_fire
        & (~ts_gm_comp_bad_proto_tma)
        & (~(gm_rsp_resp == c(0, width=2)))
    )

    ts_need_second_wr_tma = ts_gm_comp_good_tma & req_second_pending_q_tma.out() & (req_phase_q_tma.out() == c(0, width=1))
    ts_row_done_tma = ts_gm_comp_good_tma & (~ts_need_second_wr_tma)

    req_addr_next_tma = ts_need_second_wr_tma._select_internal(req_second_addr_q_tma.out(), req_addr_next_tma)
    req_len_next_tma = ts_need_second_wr_tma._select_internal(c(7, width=8), req_len_next_tma)
    req_phase_next_tma = ts_need_second_wr_tma._select_internal(c(1, width=1), req_phase_next_tma)
    state_next_tma = ts_need_second_wr_tma._select_internal(c(S_TS_GM_REQ, width=state_w), state_next_tma)

    row_idx_next_tma = ts_row_done_tma._select_internal(ts_rows_after_done_tma, row_idx_next_tma)
    chunk_row_idx_next_tma = ts_row_done_tma._select_internal(ts_chunk_row_after_done_tma, chunk_row_idx_next_tma)

    ts_done_tma = ts_row_done_tma & (ts_rows_after_done_tma == gm_outer_q_tma.out())
    ts_chunk_reload_tma = ts_row_done_tma & (~ts_done_tma) & ts_chunk_wrap_tma
    ts_chunk_stay_tma = ts_row_done_tma & (~ts_done_tma) & (~ts_chunk_wrap_tma)

    chunk_idx_next_tma = ts_chunk_reload_tma._select_internal(chunk_idx_q_tma.out() + c(1, width=16), chunk_idx_next_tma)
    chunk_row_idx_next_tma = ts_chunk_reload_tma._select_internal(c(0, width=4), chunk_row_idx_next_tma)

    state_next_tma = ts_chunk_reload_tma._select_internal(c(S_TS_TR_REQ, width=state_w), state_next_tma)
    state_next_tma = ts_chunk_stay_tma._select_internal(c(S_TS_BUILD_ROW, width=state_w), state_next_tma)

    state_next_tma = ts_done_tma._select_internal(c(S_CMD_RESP, width=state_w), state_next_tma)
    status_next_tma = ts_done_tma._select_internal(c(ST_OK, width=4), status_next_tma)
    err_info_next_tma = ts_done_tma._select_internal(c(0, width=32), err_info_next_tma)

    # Store incoming beats to buffers (hold old value when no write).
    gm_store_idx_tma = (gm_store_base_q_tma.out() + recv_idx_q_tma.out()._zext(width=5))._trunc(width=4)
    for gi in range(16):
        gm_hit_i_tma = state_is_tl_gm_wait_dat & gm_dat_rx_fire & (gm_store_idx_tma == c(gi, width=4))
        gm_word_next_i_tma = gm_hit_i_tma._select_internal(gm_dat_rx_data, gm_buf_regs[gi].out())
        gm_buf_regs[gi].set(gm_word_next_i_tma)

    for ti in range(8):
        tr_hit_i_tma = state_is_ts_tr_wait_dat & tr_dat_rx_fire & (recv_idx_q_tma.out() == c(ti, width=4))
        tr_word_next_i_tma = tr_hit_i_tma._select_internal(tr_dat_rx_data, tr_chunk_regs[ti].out())
        tr_chunk_regs[ti].set(tr_word_next_i_tma)

    # Protocol/access checks for Dat/Rsp paths.
    tl_gm_dat_bad_proto_tma = state_is_tl_gm_wait_dat & gm_dat_rx_fire & (
        (~(gm_dat_rx_opcode == c(OPC_DAT_COMPDATA, width=6))) | (~(gm_dat_rx_txnid == txnid_q_tma.out()))
    )
    tl_gm_dat_bad_resp_tma = (
        state_is_tl_gm_wait_dat
        & gm_dat_rx_fire
        & (~tl_gm_dat_bad_proto_tma)
        & (~(gm_dat_rx_resp == c(0, width=2)))
    )

    ts_tr_dat_bad_proto_tma = state_is_ts_tr_wait_dat & tr_dat_rx_fire & (
        (~(tr_dat_rx_opcode == c(OPC_DAT_COMPDATA, width=6))) | (~(tr_dat_rx_txnid == txnid_q_tma.out()))
    )
    ts_tr_dat_bad_resp_tma = (
        state_is_ts_tr_wait_dat
        & tr_dat_rx_fire
        & (~ts_tr_dat_bad_proto_tma)
        & (~(tr_dat_rx_resp == c(0, width=2)))
    )

    any_proto_err_tma = (
        tl_gm_dat_bad_proto_tma
        | tl_tr_comp_bad_proto_tma
        | ts_tr_dat_bad_proto_tma
        | ts_gm_dbid_bad_proto_tma
        | ts_gm_comp_bad_proto_tma
    )
    any_access_err_tma = (
        tl_gm_dat_bad_resp_tma
        | tl_tr_comp_bad_resp_tma
        | ts_tr_dat_bad_resp_tma
        | ts_gm_dbid_bad_resp_tma
        | ts_gm_comp_bad_resp_tma
    )

    state_next_tma = any_proto_err_tma._select_internal(c(S_CMD_RESP, width=state_w), state_next_tma)
    status_next_tma = any_proto_err_tma._select_internal(c(ST_PROTOCOL_ERR, width=4), status_next_tma)
    err_info_next_tma = any_proto_err_tma._select_internal(c(0x0A01, width=32), err_info_next_tma)

    state_next_tma = any_access_err_tma._select_internal(c(S_CMD_RESP, width=state_w), state_next_tma)
    status_next_tma = any_access_err_tma._select_internal(c(ST_ACCESS_ERR, width=4), status_next_tma)
    err_info_next_tma = any_access_err_tma._select_internal(c(0x0A02, width=32), err_info_next_tma)

    state_next_tma = timeout_hit_tma._select_internal(c(S_CMD_RESP, width=state_w), state_next_tma)
    status_next_tma = timeout_hit_tma._select_internal(c(ST_TIMEOUT, width=4), status_next_tma)
    err_info_next_tma = timeout_hit_tma._select_internal(c(0x0F00, width=32), err_info_next_tma)

    # Leave response state after one cycle.
    state_next_tma = state_is_resp._select_internal(c(S_CMD_IDLE, width=state_w), state_next_tma)

    # Commit register updates.
    state_q_tma.set(state_next_tma)
    tag_q_tma.set(tag_next_tma)
    payload_q_tma.set(payload_next_tma)
    op_q_tma.set(op_next_tma)
    txnid_q_tma.set(txnid_next_tma)
    next_txnid_q_tma.set(next_txnid_next_tma)
    dbid_q_tma.set(dbid_next_tma)

    status_q_tma.set(status_next_tma)
    err_info_q_tma.set(err_info_next_tma)
    beats_q_tma.set(beats_next_tma)
    elapsed_q_tma.set(elapsed_next_tma)
    timeout_q_tma.set(timeout_next_tma)

    cfg_layout_q_tma.set(cfg_layout_next_tma)
    cfg_elem_q_tma.set(cfg_elem_next_tma)
    cfg_pad_q_tma.set(cfg_pad_next_tma)
    gm_base_q_tma.set(gm_base_next_tma)
    tr_base_q_tma.set(tr_base_next_tma)
    gm_inner_q_tma.set(gm_inner_next_tma)
    gm_outer_q_tma.set(gm_outer_next_tma)
    tr_inner_q_tma.set(tr_inner_next_tma)
    tr_outer_q_tma.set(tr_outer_next_tma)
    gm_stride_q_tma.set(gm_stride_next_tma)

    row_bytes_q_tma.set(row_bytes_next_tma)
    row_beats_q_tma.set(row_beats_next_tma)
    rows_per_chunk_q_tma.set(rows_per_chunk_next_tma)

    row_idx_q_tma.set(row_idx_next_tma)
    chunk_idx_q_tma.set(chunk_idx_next_tma)
    chunk_row_idx_q_tma.set(chunk_row_idx_next_tma)

    req_addr_q_tma.set(req_addr_next_tma)
    req_len_q_tma.set(req_len_next_tma)
    req_second_addr_q_tma.set(req_second_addr_next_tma)
    req_second_pending_q_tma.set(req_second_pending_next_tma)
    req_phase_q_tma.set(req_phase_next_tma)
    first_req_addr_q_tma.set(first_req_addr_next_tma)

    recv_idx_q_tma.set(recv_idx_next_tma)
    exp_beats_q_tma.set(exp_beats_next_tma)
    gm_store_base_q_tma.set(gm_store_base_next_tma)
    send_idx_q_tma.set(send_idx_next_tma)
    wcb_fill_q_tma.set(wcb_fill_next_tma)

    # Outputs.
    m.output("cmd_ready_tma", cmd_ready_tma)
    m.output("rsp_valid_tma", state_is_resp)
    m.output("rsp_tag_tma", tag_q_tma.out())
    m.output("rsp_status_tma", status_q_tma.out())
    m.output("rsp_data0_tma", beats_q_tma.out()._zext(width=64))
    m.output("rsp_data1_tma", elapsed_q_tma.out()._zext(width=64))

    m.output("gm_req_valid", gm_req_valid)
    m.output("gm_req_opcode", gm_req_opcode)
    m.output("gm_req_txnid", txnid_q_tma.out())
    m.output("gm_req_addr", req_addr_q_tma.out())
    m.output("gm_req_size", c(5, width=3))
    m.output("gm_req_len", req_len_q_tma.out())

    m.output("gm_rsp_ready", gm_rsp_ready)
    m.output("gm_dat_rx_ready", gm_dat_rx_ready)

    m.output("gm_dat_tx_valid", gm_dat_tx_valid)
    m.output("gm_dat_tx_opcode", c(OPC_DAT_NCB_WRDATA, width=6))
    m.output("gm_dat_tx_txnid", txnid_q_tma.out())
    m.output("gm_dat_tx_dbid", dbid_q_tma.out())
    m.output("gm_dat_tx_data", gm_dat_tx_data_tma)
    m.output("gm_dat_tx_be", gm_dat_tx_be_tma)
    m.output("gm_dat_tx_resp", c(0, width=2))

    m.output("tr_req_valid", tr_req_valid)
    m.output("tr_req_opcode", tr_req_opcode)
    m.output("tr_req_txnid", txnid_q_tma.out())
    m.output("tr_req_addr", tr_chunk_addr_tma)
    m.output("tr_req_size", c(5, width=3))
    m.output("tr_req_len", c(7, width=8))

    m.output("tr_rsp_ready", tr_rsp_ready)
    m.output("tr_dat_rx_ready", tr_dat_rx_ready)

    m.output("tr_dat_tx_valid", tr_dat_tx_valid)
    m.output("tr_dat_tx_opcode", c(OPC_DAT_NCB_WRDATA, width=6))
    m.output("tr_dat_tx_txnid", txnid_q_tma.out())
    m.output("tr_dat_tx_dbid", c(0, width=DBID_W))
    m.output("tr_dat_tx_data", tr_dat_tx_word_tma)
    m.output("tr_dat_tx_be", c((1 << BE_W) - 1, width=BE_W))
    m.output("tr_dat_tx_resp", c(0, width=2))

    # Debug/observability for UT deadlock triage.
    bpq_occ_tma = (~state_is_idle)._zext(width=16)
    rfb_busy_tma = (state_is_tl_gm_req | state_is_tl_gm_wait_dat | state_is_ts_tr_req | state_is_ts_tr_wait_dat)._zext(width=16)
    wcb_busy_tma = (
        wcb_fill_q_tma.out().ugt(c(0, width=4))
        | state_is_tl_tr_req
        | state_is_tl_tr_send_dat
        | state_is_tl_tr_wait_comp
        | state_is_ts_gm_req
        | state_is_ts_gm_wait_dbid
        | state_is_ts_gm_send_dat
        | state_is_ts_gm_wait_comp
    )._zext(width=16)
    bdb_busy_tma = (state_is_tl_build_row | state_is_ts_build_row)._zext(width=16)

    m.output("dbg_state_tma", state_q_tma.out())
    m.output("dbg_bpq_occ_tma", bpq_occ_tma)
    m.output("dbg_rfb_occ_tma", rfb_busy_tma)
    m.output("dbg_wcb_occ_tma", wcb_busy_tma)
    m.output("dbg_bdb_occ_tma", bdb_busy_tma)
    m.output("dbg_row_idx_tma", row_idx_q_tma.out())
    m.output("dbg_chunk_idx_tma", chunk_idx_q_tma.out())
    m.output("dbg_chunk_row_idx_tma", chunk_row_idx_q_tma.out())
    m.output("dbg_timeout_tma", timeout_q_tma.out())

    _ = BPQ_DEPTH
    _ = RFB_DEPTH
    _ = WCB_DEPTH
    _ = BDB_DEPTH
    _ = gm_dat_rx_dbid
    _ = gm_dat_rx_be
    _ = tr_rsp_dbid
    _ = tr_dat_rx_dbid
    _ = tr_dat_rx_be
    _ = ST_INTERNAL_ERR
