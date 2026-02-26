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

    def enq(cyc: int, sid: int, mask: int, byte: int) -> None:
        # Put byte in lane0 for visibility.
        data = (byte & 0xFF)
        t.drive("enq_valid", 1, at=cyc)
        t.drive("enq_line", LINE0, at=cyc)
        t.drive("enq_mask", mask, at=cyc)
        t.drive("enq_data", data, at=cyc)
        t.drive("enq_sid", sid, at=cyc)

    # Enqueue SID 1
    enq(0, sid=1, mask=0x1, byte=0x11)
    # Enqueue SID 2 (consecutive) => should merge into same SCB entry
    enq(1, sid=2, mask=0x2, byte=0x22)

    # Expect at least one CHI request sometime after enqueue.
    t.expect("chi_req_valid", 1, at=2)

    # Capture txnid of first issued request and respond out-of-order:
    # We can't dynamically capture in this TB API, so just assume txnid=0
    # for the first request (since free mask starts all-ones and allocator picks lowest).
    t.drive("chi_resp_valid", 1, at=4)
    t.drive("chi_resp_txnid", 0, at=4)

    # After resp, entry should become done and pop from head.
    t.expect("has_unissued", 0, at=6)

    t.finish(at=8)
