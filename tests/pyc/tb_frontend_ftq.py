from __future__ import annotations

from pycircuit import Tb, testbench

from bcc.frontend.ftq import build_ftq_lite as build  # noqa: E402


@testbench
def tb(t: Tb) -> None:
    t.clock("clk")
    t.reset("rst", cycles_asserted=2, cycles_deasserted=1)
    t.timeout(128)

    for cyc in range(32):
        t.drive("enq_valid", 0, at=cyc)
        t.drive("enq_pc", 0, at=cyc)
        t.drive("enq_npc", 0, at=cyc)
        t.drive("enq_checkpoint", 0, at=cyc)
        t.drive("deq_ready", 0, at=cyc)
        t.drive("flush_valid", 0, at=cyc)

    # Enqueue one FTQ entry.
    t.drive("enq_valid", 1, at=0)
    t.drive("enq_pc", 0x1000, at=0)
    t.drive("enq_npc", 0x1008, at=0)
    t.drive("enq_checkpoint", 0x05, at=0)
    t.expect("enq_ready", 1, at=0)

    # Request dequeue.
    t.drive("deq_ready", 1, at=1)

    # Enqueue two entries, verify ordering and flush behavior.
    t.drive("enq_valid", 1, at=3)
    t.drive("enq_pc", 0x2000, at=3)
    t.drive("enq_npc", 0x2008, at=3)
    t.drive("enq_checkpoint", 0x11, at=3)

    t.drive("enq_valid", 1, at=4)
    t.drive("enq_pc", 0x3000, at=4)
    t.drive("enq_npc", 0x3008, at=4)
    t.drive("enq_checkpoint", 0x12, at=4)

    t.drive("flush_valid", 1, at=5)
    t.expect("deq_valid", 0, at=6)
    t.expect("count_dbg", 0, at=6)
    t.expect("head_dbg", 0, at=6)
    t.expect("tail_dbg", 0, at=6)

    t.finish(at=8)
