from __future__ import annotations

from pycircuit import Tb, testbench

from bcc.frontend.ibuffer import build_ibuffer as build  # noqa: E402


@testbench
def tb(t: Tb) -> None:
    t.clock("clk")
    t.reset("rst", cycles_asserted=2, cycles_deasserted=1)
    t.timeout(128)

    for cyc in range(32):
        t.drive("push_valid", 0, at=cyc)
        t.drive("push_pc", 0, at=cyc)
        t.drive("push_window", 0, at=cyc)
        t.drive("push_pkt_uid", 0, at=cyc)
        t.drive("pop_ready", 0, at=cyc)
        t.drive("flush_valid", 0, at=cyc)

    # Push one entry.
    t.drive("push_valid", 1, at=0)
    t.drive("push_pc", 0x10, at=0)
    t.drive("push_window", 0xAAAABBBBCCCCDDDD, at=0)
    t.drive("push_pkt_uid", 0x11, at=0)
    t.expect("push_ready", 1, at=0)

    # Pop request.
    t.drive("pop_ready", 1, at=1)

    # Fill two entries then flush.
    t.drive("push_valid", 1, at=3)
    t.drive("push_pc", 0x20, at=3)
    t.drive("push_window", 0x1111222233334444, at=3)
    t.drive("push_pkt_uid", 0x22, at=3)

    t.drive("push_valid", 1, at=4)
    t.drive("push_pc", 0x30, at=4)
    t.drive("push_window", 0x5555666677778888, at=4)
    t.drive("push_pkt_uid", 0x33, at=4)

    t.drive("flush_valid", 1, at=5)
    t.expect("count_dbg", 0, at=6)
    t.expect("head_dbg", 0, at=6)
    t.expect("tail_dbg", 0, at=6)

    t.finish(at=8)
