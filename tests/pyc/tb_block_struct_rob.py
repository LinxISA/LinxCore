from __future__ import annotations

from pycircuit import Tb, testbench

from bcc.block_struct.rob_rtl import build_janus_bcc_block_struct_rob as build  # noqa: E402


@testbench
def tb(t: Tb) -> None:
    t.clock("clk")
    t.reset("rst", cycles_asserted=2, cycles_deasserted=1)
    t.timeout(32)

    BID = 0x11

    def drive_defaults(cyc: int) -> None:
        t.drive("alloc_valid", 0, at=cyc)
        t.drive("alloc_bid", 0, at=cyc)
        t.drive("alloc_eob", 0, at=cyc)

        t.drive("wb_valid", 0, at=cyc)
        t.drive("wb_rid", 0, at=cyc)
        t.drive("wb_trap_valid", 0, at=cyc)
        t.drive("wb_trap_cause", 0, at=cyc)

        t.drive("retire_ready", 0, at=cyc)

    for cyc in range(8):
        drive_defaults(cyc)

    # Cycle 0: allocate entry at tail=0, mark it as EOB.
    t.drive("alloc_valid", 1, at=0)
    t.drive("alloc_bid", BID, at=0)
    t.drive("alloc_eob", 1, at=0)

    # Cycle 1: writeback marks done.
    t.drive("wb_valid", 1, at=1)
    t.drive("wb_rid", 0, at=1)

    # Cycle 2: retire fires, should pulse scalar_done.
    t.drive("retire_ready", 1, at=2)
    t.expect("retire_fire", 1, at=2)
    t.expect("scalar_done_valid", 1, at=2)
    t.expect("scalar_done_bid", BID, at=2)

    t.finish(at=3)
