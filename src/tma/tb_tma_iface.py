from __future__ import annotations

import sys
from pathlib import Path

from pycircuit import Circuit, Tb, compile, module, testbench

_THIS_DIR = Path(__file__).resolve().parent
_SRC_DIR = _THIS_DIR.parent
if str(_SRC_DIR) not in sys.path:
    sys.path.insert(0, str(_SRC_DIR))

from tma.tma import build_janus_tma  # noqa: E402


ADDR_W = 64
DATA_W = 256
TXNID_W = 8
DBID_W = 8
BE_W = DATA_W // 8

ST_OK = 0x0
ST_PROTOCOL_ERR = 0x2
ST_ACCESS_ERR = 0x3
ST_TIMEOUT = 0x4
ST_UNSUPPORTED = 0x5

OPC_REQ_READ_ONCE = 0x01
OPC_REQ_WRITE_UNIQUE = 0x02
OPC_REQ_READ_NOSNP = 0x03
OPC_REQ_WRITE_NOSNP = 0x04
OPC_RSP_COMP = 0x01
OPC_RSP_COMP_DBID = 0x02
OPC_DAT_COMPDATA = 0x01
OPC_DAT_NCB_WRDATA = 0x02

PAYLOAD_TLOAD = 0x0
PAYLOAD_TSTORE = 0x1
PAYLOAD_UNSUPPORTED = 0x2


@module(name="JanusTmaIfaceTbTop")
def build(m: Circuit) -> None:
    clk = m.clock("clk")
    rst = m.reset("rst")

    cmd_valid_tma = m.input("cmd_valid_tma", width=1)
    cmd_tag_tma = m.input("cmd_tag_tma", width=8)
    cmd_payload_tma = m.input("cmd_payload_tma", width=64)

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
        module_name="JanusTmaIfaceTbDut",
        params={
            "enable_chi_if": 1,
            "ADDR_W": ADDR_W,
            "DATA_W": DATA_W,
            "TXNID_W": TXNID_W,
            "DBID_W": DBID_W,
            "stub_timeout_cycles": 4,
        },
        clk=clk,
        rst=rst,
        cmd_valid_tma=cmd_valid_tma,
        cmd_tag_tma=cmd_tag_tma,
        cmd_payload_tma=cmd_payload_tma,
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


@testbench
def tb(t: Tb) -> None:
    t.clock("clk")
    t.reset("rst", cycles_asserted=2, cycles_deasserted=1)
    t.timeout(900)

    def d(name: str, value: int, at: int) -> None:
        t.drive(name, int(value), at=at)

    def pulse(name: str, value: int, at: int) -> None:
        d(name, value, at)
        d(name, 0, at + 1)

    # Stable defaults.
    for sig in ("cmd_valid_tma", "cmd_tag_tma", "cmd_payload_tma"):
        d(sig, 0, 0)
    d("gm_req_ready", 1, 0)
    d("gm_rsp_valid", 0, 0)
    d("gm_rsp_opcode", 0, 0)
    d("gm_rsp_txnid", 0, 0)
    d("gm_rsp_dbid", 0, 0)
    d("gm_rsp_resp", 0, 0)
    d("gm_dat_rx_valid", 0, 0)
    d("gm_dat_rx_opcode", 0, 0)
    d("gm_dat_rx_txnid", 0, 0)
    d("gm_dat_rx_dbid", 0, 0)
    d("gm_dat_rx_data", 0, 0)
    d("gm_dat_rx_be", 0, 0)
    d("gm_dat_rx_resp", 0, 0)
    d("gm_dat_tx_ready", 1, 0)

    d("tr_req_ready", 1, 0)
    d("tr_rsp_valid", 0, 0)
    d("tr_rsp_opcode", 0, 0)
    d("tr_rsp_txnid", 0, 0)
    d("tr_rsp_dbid", 0, 0)
    d("tr_rsp_resp", 0, 0)
    d("tr_dat_rx_valid", 0, 0)
    d("tr_dat_rx_opcode", 0, 0)
    d("tr_dat_rx_txnid", 0, 0)
    d("tr_dat_rx_dbid", 0, 0)
    d("tr_dat_rx_data", 0, 0)
    d("tr_dat_rx_be", 0, 0)
    d("tr_dat_rx_resp", 0, 0)
    d("tr_dat_tx_ready", 1, 0)

    def pulse_gm_dat_rx(at: int, *, txnid: int, opcode: int = OPC_DAT_COMPDATA, resp: int = 0) -> None:
        d("gm_dat_rx_opcode", opcode, at)
        d("gm_dat_rx_txnid", txnid, at)
        d("gm_dat_rx_resp", resp, at)
        pulse("gm_dat_rx_valid", 1, at)

    def pulse_tr_dat_rx(at: int, *, txnid: int, opcode: int = OPC_DAT_COMPDATA, resp: int = 0) -> None:
        d("tr_dat_rx_opcode", opcode, at)
        d("tr_dat_rx_txnid", txnid, at)
        d("tr_dat_rx_resp", resp, at)
        pulse("tr_dat_rx_valid", 1, at)

    def pulse_gm_rsp(at: int, *, txnid: int, opcode: int, resp: int = 0, dbid: int = 0) -> None:
        d("gm_rsp_opcode", opcode, at)
        d("gm_rsp_txnid", txnid, at)
        d("gm_rsp_resp", resp, at)
        d("gm_rsp_dbid", dbid, at)
        pulse("gm_rsp_valid", 1, at)

    def pulse_tr_rsp(at: int, *, txnid: int, opcode: int = OPC_RSP_COMP, resp: int = 0) -> None:
        d("tr_rsp_opcode", opcode, at)
        d("tr_rsp_txnid", txnid, at)
        d("tr_rsp_resp", resp, at)
        pulse("tr_rsp_valid", 1, at)

    def issue_cmd(at: int, *, tag: int, payload: int) -> int:
        t.expect("cmd_ready_tma", 1, at=at, phase="pre", msg=f"cmd not ready at {at}")
        d("cmd_tag_tma", tag, at)
        d("cmd_payload_tma", payload, at)
        pulse("cmd_valid_tma", 1, at)
        return at + 1

    next_txn = 0

    def alloc_txn() -> int:
        nonlocal next_txn
        cur = next_txn & ((1 << TXNID_W) - 1)
        next_txn = (next_txn + 1) & ((1 << TXNID_W) - 1)
        return cur

    cyc = 4

    # 1) tload_happy_path_single_cmd
    txn = alloc_txn()
    cyc = issue_cmd(cyc, tag=0x11, payload=PAYLOAD_TLOAD)
    t.expect("gm_req_valid", 1, at=cyc, phase="pre", msg="case1 gm req valid")
    t.expect("gm_req_opcode", OPC_REQ_READ_ONCE, at=cyc, phase="pre", msg="case1 gm req opcode")
    t.expect("gm_req_txnid", txn, at=cyc, phase="pre", msg="case1 gm req txn")
    cyc += 1
    t.expect("gm_dat_rx_ready", 1, at=cyc, phase="pre", msg="case1 gm dat ready")
    pulse_gm_dat_rx(cyc, txnid=txn)
    cyc += 1
    t.expect("tr_req_valid", 1, at=cyc, phase="pre", msg="case1 tr req valid")
    t.expect("tr_req_opcode", OPC_REQ_WRITE_NOSNP, at=cyc, phase="pre", msg="case1 tr req opcode")
    cyc += 1
    t.expect("tr_dat_tx_valid", 1, at=cyc, phase="pre", msg="case1 tr dat valid")
    t.expect("tr_dat_tx_opcode", OPC_DAT_NCB_WRDATA, at=cyc, phase="pre", msg="case1 tr dat opcode")
    cyc += 1
    t.expect("tr_rsp_ready", 1, at=cyc, phase="pre", msg="case1 tr rsp ready")
    pulse_tr_rsp(cyc, txnid=txn)
    t.expect("rsp_valid_tma", 1, at=cyc, msg="case1 rsp valid")
    t.expect("rsp_tag_tma", 0x11, at=cyc, msg="case1 rsp tag")
    t.expect("rsp_status_tma", ST_OK, at=cyc, msg="case1 rsp status")
    cyc += 1
    t.expect("rsp_valid_tma", 0, at=cyc, msg="case1 rsp pulse")

    # 2) tstore_happy_path_single_cmd
    txn = alloc_txn()
    cyc = issue_cmd(cyc + 1, tag=0x12, payload=PAYLOAD_TSTORE)
    t.expect("tr_req_valid", 1, at=cyc, phase="pre", msg="case2 tr req valid")
    t.expect("tr_req_opcode", OPC_REQ_READ_NOSNP, at=cyc, phase="pre", msg="case2 tr req opcode")
    t.expect("tr_req_txnid", txn, at=cyc, phase="pre", msg="case2 tr req txn")
    cyc += 1
    pulse_tr_dat_rx(cyc, txnid=txn)
    cyc += 1
    t.expect("gm_req_valid", 1, at=cyc, phase="pre", msg="case2 gm req valid")
    t.expect("gm_req_opcode", OPC_REQ_WRITE_UNIQUE, at=cyc, phase="pre", msg="case2 gm req opcode")
    cyc += 1
    pulse_gm_rsp(cyc, txnid=txn, opcode=OPC_RSP_COMP_DBID, dbid=0x2A)
    cyc += 1
    t.expect("gm_dat_tx_valid", 1, at=cyc, phase="pre", msg="case2 gm dat valid")
    t.expect("gm_dat_tx_opcode", OPC_DAT_NCB_WRDATA, at=cyc, phase="pre", msg="case2 gm dat opcode")
    t.expect("gm_dat_tx_dbid", 0x2A, at=cyc, phase="pre", msg="case2 gm dat dbid")
    cyc += 1
    pulse_gm_rsp(cyc, txnid=txn, opcode=OPC_RSP_COMP)
    t.expect("rsp_valid_tma", 1, at=cyc, msg="case2 rsp valid")
    t.expect("rsp_tag_tma", 0x12, at=cyc, msg="case2 rsp tag")
    t.expect("rsp_status_tma", ST_OK, at=cyc, msg="case2 rsp status")

    # 3) cmd_backpressure_hold_and_fire_once
    txn = alloc_txn()
    cyc = issue_cmd(cyc + 2, tag=0x13, payload=PAYLOAD_TLOAD)
    d("gm_req_ready", 0, cyc)
    t.expect("gm_req_valid", 1, at=cyc, phase="pre", msg="case3 hold1 valid")
    t.expect("cmd_ready_tma", 0, at=cyc, phase="pre", msg="case3 hold1 cmd_ready")
    t.expect("gm_req_valid", 1, at=cyc + 1, phase="pre", msg="case3 hold2 valid")
    d("gm_req_ready", 1, cyc + 2)
    t.expect("gm_req_valid", 1, at=cyc + 2, phase="pre", msg="case3 release valid")
    cyc += 3
    pulse_gm_dat_rx(cyc, txnid=txn)
    cyc += 1
    t.expect("tr_req_valid", 1, at=cyc, phase="pre", msg="case3 tr req")
    cyc += 1
    t.expect("tr_dat_tx_valid", 1, at=cyc, phase="pre", msg="case3 tr dat")
    cyc += 1
    pulse_tr_rsp(cyc, txnid=txn)
    t.expect("rsp_valid_tma", 1, at=cyc, msg="case3 rsp")
    t.expect("rsp_status_tma", ST_OK, at=cyc, msg="case3 status")
    t.expect("rsp_valid_tma", 0, at=cyc + 1, msg="case3 single completion pulse")

    # 4) write_flow_requires_dbid_before_dat
    txn = alloc_txn()
    cyc = issue_cmd(cyc + 2, tag=0x14, payload=PAYLOAD_TSTORE)
    t.expect("tr_req_valid", 1, at=cyc, phase="pre", msg="case4 tr req")
    cyc += 1
    pulse_tr_dat_rx(cyc, txnid=txn)
    cyc += 1
    t.expect("gm_req_valid", 1, at=cyc, phase="pre", msg="case4 gm req")
    cyc += 1
    t.expect("gm_dat_tx_valid", 0, at=cyc, phase="pre", msg="case4 no dat before dbid")
    pulse_gm_rsp(cyc + 1, txnid=txn, opcode=OPC_RSP_COMP_DBID, dbid=0x15)
    cyc += 2
    t.expect("gm_dat_tx_valid", 1, at=cyc, phase="pre", msg="case4 dat after dbid")
    t.expect("gm_dat_tx_dbid", 0x15, at=cyc, phase="pre", msg="case4 dbid carried")
    cyc += 1
    pulse_gm_rsp(cyc, txnid=txn, opcode=OPC_RSP_COMP)
    t.expect("rsp_status_tma", ST_OK, at=cyc, msg="case4 status")

    # 5) read_flow_requires_dat_before_complete
    txn = alloc_txn()
    cyc = issue_cmd(cyc + 2, tag=0x15, payload=PAYLOAD_TLOAD)
    t.expect("gm_req_valid", 1, at=cyc, phase="pre", msg="case5 gm req")
    cyc += 1
    t.expect("tr_req_valid", 0, at=cyc, phase="pre", msg="case5 no tr req before gm dat")
    t.expect("rsp_valid_tma", 0, at=cyc, phase="pre", msg="case5 no rsp before gm dat")
    pulse_gm_dat_rx(cyc + 1, txnid=txn)
    cyc += 2
    t.expect("tr_req_valid", 1, at=cyc, phase="pre", msg="case5 tr req after gm dat")
    cyc += 1
    t.expect("tr_dat_tx_valid", 1, at=cyc, phase="pre", msg="case5 tr dat")
    cyc += 1
    pulse_tr_rsp(cyc, txnid=txn)
    t.expect("rsp_status_tma", ST_OK, at=cyc, msg="case5 status")

    # 6) access_err_from_resp_slverr
    txn = alloc_txn()
    cyc = issue_cmd(cyc + 2, tag=0x16, payload=PAYLOAD_TLOAD)
    t.expect("gm_req_valid", 1, at=cyc, phase="pre", msg="case6 gm req")
    cyc += 1
    pulse_gm_dat_rx(cyc, txnid=txn, resp=2)
    t.expect("rsp_valid_tma", 1, at=cyc, msg="case6 rsp")
    t.expect("rsp_status_tma", ST_ACCESS_ERR, at=cyc, msg="case6 access err")

    # 7) protocol_err_on_txnid_mismatch
    txn = alloc_txn()
    cyc = issue_cmd(cyc + 2, tag=0x17, payload=PAYLOAD_TSTORE)
    t.expect("tr_req_valid", 1, at=cyc, phase="pre", msg="case7 tr req")
    cyc += 1
    pulse_tr_dat_rx(cyc, txnid=(txn + 1) & 0xFF)
    t.expect("rsp_valid_tma", 1, at=cyc, msg="case7 rsp")
    t.expect("rsp_status_tma", ST_PROTOCOL_ERR, at=cyc, msg="case7 protocol err")

    # 8) timeout_err_when_no_rsp_within_window
    txn = alloc_txn()
    cyc = issue_cmd(cyc + 2, tag=0x18, payload=PAYLOAD_TLOAD)
    d("gm_req_ready", 0, cyc)
    t.expect("gm_req_valid", 1, at=cyc, phase="pre", msg="case8 req stall c0")
    t.expect("gm_req_valid", 1, at=cyc + 1, phase="pre", msg="case8 req stall c1")
    t.expect("gm_req_valid", 1, at=cyc + 2, phase="pre", msg="case8 req stall c2")
    t.expect("gm_req_valid", 1, at=cyc + 3, phase="pre", msg="case8 req stall c3")
    t.expect("rsp_valid_tma", 1, at=cyc + 4, msg="case8 timeout rsp")
    t.expect("rsp_status_tma", ST_TIMEOUT, at=cyc + 4, msg="case8 timeout status")
    d("gm_req_ready", 1, cyc + 5)
    cyc += 6

    # 9) unsupported_op_returns_status
    _ = alloc_txn()
    cyc = issue_cmd(cyc, tag=0x19, payload=PAYLOAD_UNSUPPORTED)
    t.expect("rsp_valid_tma", 1, at=cyc, phase="pre", msg="case9 unsupported rsp")
    t.expect("rsp_tag_tma", 0x19, at=cyc, phase="pre", msg="case9 unsupported tag")
    t.expect("rsp_status_tma", ST_UNSUPPORTED, at=cyc, phase="pre", msg="case9 unsupported status")
    cyc += 1

    # 10) rsp_tag_echo_and_single_completion
    txn = alloc_txn()
    cyc = issue_cmd(cyc, tag=0xAA, payload=PAYLOAD_TLOAD)
    t.expect("gm_req_txnid", txn, at=cyc, phase="pre", msg="case10 txn")
    cyc += 1
    pulse_gm_dat_rx(cyc, txnid=txn)
    cyc += 1
    t.expect("tr_req_valid", 1, at=cyc, phase="pre", msg="case10 tr req")
    cyc += 1
    t.expect("tr_dat_tx_valid", 1, at=cyc, phase="pre", msg="case10 tr dat")
    cyc += 1
    pulse_tr_rsp(cyc, txnid=txn)
    t.expect("rsp_valid_tma", 1, at=cyc, msg="case10 rsp valid")
    t.expect("rsp_tag_tma", 0xAA, at=cyc, msg="case10 rsp tag")
    t.expect("rsp_valid_tma", 0, at=cyc + 1, msg="case10 single pulse")
    cyc += 2

    # 11) multi_cmd_sequential_completion_order
    txn1 = alloc_txn()
    cyc = issue_cmd(cyc, tag=0xB1, payload=PAYLOAD_TLOAD)
    t.expect("cmd_ready_tma", 0, at=cyc, phase="pre", msg="case11 busy backpressure")
    d("cmd_tag_tma", 0xB2, at=cyc)
    d("cmd_payload_tma", PAYLOAD_TSTORE, at=cyc)
    pulse("cmd_valid_tma", 1, cyc)
    t.expect("gm_req_txnid", txn1, at=cyc, phase="pre", msg="case11 txn1")
    cyc += 1
    pulse_gm_dat_rx(cyc, txnid=txn1)
    cyc += 1
    t.expect("tr_req_valid", 1, at=cyc, phase="pre", msg="case11 tr req1")
    cyc += 1
    t.expect("tr_dat_tx_valid", 1, at=cyc, phase="pre", msg="case11 tr dat1")
    cyc += 1
    pulse_tr_rsp(cyc, txnid=txn1)
    t.expect("rsp_valid_tma", 1, at=cyc, msg="case11 rsp1")
    t.expect("rsp_tag_tma", 0xB1, at=cyc, msg="case11 rsp1 tag")
    cyc += 2

    txn2 = alloc_txn()
    cyc = issue_cmd(cyc, tag=0xB2, payload=PAYLOAD_TSTORE)
    t.expect("tr_req_txnid", txn2, at=cyc, phase="pre", msg="case11 txn2")
    cyc += 1
    pulse_tr_dat_rx(cyc, txnid=txn2)
    cyc += 1
    t.expect("gm_req_valid", 1, at=cyc, phase="pre", msg="case11 gm req2")
    cyc += 1
    pulse_gm_rsp(cyc, txnid=txn2, opcode=OPC_RSP_COMP_DBID, dbid=0x3C)
    cyc += 1
    t.expect("gm_dat_tx_valid", 1, at=cyc, phase="pre", msg="case11 gm dat2")
    cyc += 1
    pulse_gm_rsp(cyc, txnid=txn2, opcode=OPC_RSP_COMP)
    t.expect("rsp_tag_tma", 0xB2, at=cyc, msg="case11 rsp2 tag")

    # 12) txn_id_allocate_release_no_overlap
    txn_a = alloc_txn()
    txn_b = alloc_txn()
    if txn_a == txn_b:
        raise RuntimeError("txn allocator expectation failed: repeated txn id")

    cyc = issue_cmd(cyc + 2, tag=0xC1, payload=PAYLOAD_TLOAD)
    t.expect("gm_req_txnid", txn_a, at=cyc, phase="pre", msg="case12 txn_a")
    cyc += 1
    pulse_gm_dat_rx(cyc, txnid=txn_a)
    cyc += 1
    t.expect("tr_req_valid", 1, at=cyc, phase="pre", msg="case12 tr req a")
    cyc += 1
    t.expect("tr_dat_tx_valid", 1, at=cyc, phase="pre", msg="case12 tr dat a")
    cyc += 1
    pulse_tr_rsp(cyc, txnid=txn_a)
    t.expect("rsp_tag_tma", 0xC1, at=cyc, msg="case12 rsp a")

    cyc = issue_cmd(cyc + 2, tag=0xC2, payload=PAYLOAD_TLOAD)
    t.expect("gm_req_txnid", txn_b, at=cyc, phase="pre", msg="case12 txn_b")
    cyc += 1
    pulse_gm_dat_rx(cyc, txnid=txn_b)
    cyc += 1
    t.expect("tr_req_valid", 1, at=cyc, phase="pre", msg="case12 tr req b")
    cyc += 1
    t.expect("tr_dat_tx_valid", 1, at=cyc, phase="pre", msg="case12 tr dat b")
    cyc += 1
    pulse_tr_rsp(cyc, txnid=txn_b)
    t.expect("rsp_tag_tma", 0xC2, at=cyc, msg="case12 rsp b")
    t.expect("rsp_status_tma", ST_OK, at=cyc, msg="case12 status b")

    t.finish(at=cyc + 4)


if __name__ == "__main__":
    print(compile(build, name="tb_tma_iface_top").emit_mlir())
