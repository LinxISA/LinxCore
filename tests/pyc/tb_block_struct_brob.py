from __future__ import annotations

from pycircuit import Tb, testbench

# pycircuit.cli build expects a symbol named `build` decorated with @module.
from bcc.block_struct.brob_rtl import build_janus_bcc_block_struct_brob as build  # noqa: E402


@testbench
def tb(t: Tb) -> None:
    t.clock("clk")
    t.reset("rst", cycles_asserted=2, cycles_deasserted=1)
    t.timeout(32)

    BID = 0x10

    def drive_defaults(cyc: int) -> None:
        # alloc
        t.drive("alloc_valid", 0, at=cyc)
        t.drive("alloc_bid", 0, at=cyc)
        t.drive("alloc_blocktype", 0, at=cyc)
        t.drive("alloc_needs_engine", 0, at=cyc)

        # scalar done
        t.drive("scalar_done_valid", 0, at=cyc)
        t.drive("scalar_done_bid", 0, at=cyc)
        t.drive("scalar_done_trap_valid", 0, at=cyc)
        t.drive("scalar_done_trap_cause", 0, at=cyc)

        # engine done
        t.drive("engine_done_valid", 0, at=cyc)
        t.drive("engine_done_bid", 0, at=cyc)
        t.drive("engine_done_trap_valid", 0, at=cyc)
        t.drive("engine_done_trap_cause", 0, at=cyc)

        # retire
        t.drive("retire_valid", 0, at=cyc)
        t.drive("retire_bid", 0, at=cyc)

        # query
        t.drive("query_valid", 1, at=cyc)
        t.drive("query_bid", BID, at=cyc)

    for cyc in range(8):
        drive_defaults(cyc)

    # Cycle 0: alloc a block that needs engine.
    t.drive("alloc_valid", 1, at=0)
    t.drive("alloc_bid", BID, at=0)
    t.drive("alloc_blocktype", 2, at=0)  # arbitrary
    t.drive("alloc_needs_engine", 1, at=0)
    t.expect("query_alloc", 1, at=0)
    t.expect("query_complete", 0, at=0)

    # Cycle 1: scalar retire hits EOB => scalar_done
    t.drive("scalar_done_valid", 1, at=1)
    t.drive("scalar_done_bid", BID, at=1)
    t.expect("query_scalar_done", 1, at=1)
    t.expect("query_complete", 0, at=1)

    # Cycle 2: engine done arrives => complete
    t.drive("engine_done_valid", 1, at=2)
    t.drive("engine_done_bid", BID, at=2)
    t.expect("query_engine_done", 1, at=2)
    t.expect("query_complete", 1, at=2)

    # Cycle 3: retire/free entry
    t.drive("retire_valid", 1, at=3)
    t.drive("retire_bid", BID, at=3)
    t.expect("query_alloc", 0, at=3)

    t.finish(at=4)
