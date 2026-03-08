from __future__ import annotations

from pycircuit import Tb, testbench

from bcc.frontend.bpu import build_bpu_lite as build  # noqa: E402


@testbench
def tb(t: Tb) -> None:
    t.clock("clk")
    t.reset("rst", cycles_asserted=2, cycles_deasserted=1)
    t.timeout(96)

    pc = 0x1234
    target = 0x4000

    for cyc in range(20):
        t.drive("req_valid", 0, at=cyc)
        t.drive("req_pc", pc, at=cyc)
        t.drive("update_valid", 0, at=cyc)
        t.drive("update_pc", pc, at=cyc)
        t.drive("update_taken", 0, at=cyc)
        t.drive("update_target", target, at=cyc)

    # No history hit yet: predict not-taken to pc+8.
    t.drive("req_valid", 1, at=0)
    t.expect("pred_valid", 1, at=0)
    t.expect("pred_taken", 0, at=0)
    t.expect("pred_target", pc + 8, at=0)
    t.expect("checkpoint_id", (pc >> 2) & 0x3F, at=0)

    # Train as taken.
    t.drive("update_valid", 1, at=1)
    t.drive("update_taken", 1, at=1)
    t.drive("update_target", target, at=1)

    # Next request hits tag + strong-enough counter => predict taken.
    t.drive("req_valid", 1, at=2)
    t.expect("pred_valid", 1, at=2)
    t.expect("pred_taken", 1, at=2)
    t.expect("pred_target", target, at=2)

    # Train as not-taken once; 2-bit counter drops below taken threshold.
    t.drive("update_valid", 1, at=3)
    t.drive("update_taken", 0, at=3)

    t.drive("req_valid", 1, at=4)
    t.expect("pred_taken", 0, at=4)
    t.expect("pred_target", pc + 8, at=4)

    t.finish(at=6)
