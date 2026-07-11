from __future__ import annotations

from pycircuit import Tb, testbench

from bcc.lsu.scb import build_janus_bcc_lsu_scb as build  # noqa: E402


@testbench
def tb(t: Tb) -> None:
    t.clock("clk")
    t.reset("rst", cycles_asserted=2, cycles_deasserted=1)
    t.timeout(128)

    # Defaults
    for cyc in range(32):
        t.drive("enq_valid", 0, at=cyc)
        t.drive("enq_line", 0, at=cyc)
        t.drive("enq_mask", 0, at=cyc)
        t.drive("enq_data", 0, at=cyc)
        t.drive("enq_sid", 0, at=cyc)

        t.drive("chi_req_ready", 1, at=cyc)
        t.drive("chi_resp_valid", 0, at=cyc)
        t.drive("chi_resp_txnid", 0, at=cyc)

    LINE0 = 0x1000
    LINE1 = 0x1040

    def enq(cyc: int, line: int, sid: int, mask: int, byte: int) -> None:
        lane = (mask & -mask).bit_length() - 1
        data = (byte & 0xFF) << (lane * 8)
        t.drive("enq_valid", 1, at=cyc)
        t.drive("enq_line", line, at=cyc)
        t.drive("enq_mask", mask, at=cyc)
        t.drive("enq_data", data, at=cyc)
        t.drive("enq_sid", sid, at=cyc)

    # Hold the request channel so LINE0 remains the presented head row.
    for cyc in range(5):
        t.drive("chi_req_ready", 0, at=cyc)

    enq(0, LINE0, sid=1, mask=0x1, byte=0x11)
    enq(1, LINE1, sid=2, mask=0x2, byte=0x22)
    enq(2, LINE1, sid=3, mask=0x4, byte=0x33)

    t.expect("merge_tail_presented", 0, at=2, phase="pre")
    t.expect("merge_same_line", 1, at=2, phase="pre")
    t.expect("merge_consecutive_sid", 1, at=2, phase="pre")
    t.expect("merge_allowed", 1, at=2, phase="pre")

    # The two consecutive LINE1 stores coalesce behind LINE0. The visible
    # LINE0 request remains stable throughout the stall.
    t.expect("scb_count", 2, at=2)
    t.expect("chi_req_valid", 1, at=2)
    t.expect("chi_req_addr", LINE0, at=2)
    t.expect("chi_req_strb", 0x1, at=2)
    t.expect("chi_req_data", 0x11, at=2)

    # Issue LINE0. The merged LINE1 row then becomes visible with both bytes.
    t.drive("chi_req_ready", 1, at=5)
    t.expect("chi_req_addr", LINE1, at=5)
    t.expect("chi_req_strb", 0x6, at=5)
    t.expect("chi_req_data", 0x00332200, at=5)

    # The allocator uses transaction IDs in ascending free-mask order.
    t.drive("chi_resp_valid", 1, at=7)
    t.drive("chi_resp_txnid", 0, at=7)
    t.drive("chi_resp_valid", 1, at=8)
    t.drive("chi_resp_txnid", 1, at=8)

    t.expect("has_unissued", 0, at=9)
    t.finish(at=11)
