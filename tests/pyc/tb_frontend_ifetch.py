from __future__ import annotations

from pycircuit import Tb, testbench

from bcc.frontend.ifetch import build_ifetch as build  # noqa: E402


@testbench
def tb(t: Tb) -> None:
    t.clock("clk")
    t.reset("rst", cycles_asserted=2, cycles_deasserted=1)
    t.timeout(96)

    for cyc in range(24):
        t.drive("boot_pc", 0x1000, at=cyc)
        t.drive("imem_rdata", 0, at=cyc)
        t.drive("stall_i", 0, at=cyc)
        t.drive("redirect_valid", 0, at=cyc)
        t.drive("redirect_pc", 0, at=cyc)
        t.drive("pred_valid", 0, at=cyc)
        t.drive("pred_taken", 0, at=cyc)
        t.drive("pred_target", 0, at=cyc)

    # Baseline fetch presence.
    t.expect("fetch_valid", 1, at=0)

    # Stall holds PC and suppresses fetch_valid.
    t.drive("stall_i", 1, at=2)
    t.expect("fetch_valid", 0, at=2)

    # Redirect takes priority and updates fetch address immediately.
    t.drive("redirect_valid", 1, at=3)
    t.drive("redirect_pc", 0x2000, at=3)
    t.expect("fetch_valid", 0, at=3)

    # Prediction path for next PC.
    t.drive("pred_valid", 1, at=5)
    t.drive("pred_taken", 1, at=5)
    t.drive("pred_target", 0x3000, at=5)
    t.expect("fetch_next_pc", 0x3000, at=5)

    t.finish(at=8)
