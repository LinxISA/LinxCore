from __future__ import annotations

from pycircuit import Tb, testbench

from bcc.frontend.frontend import build_frontend as build  # noqa: E402


@testbench
def tb(t: Tb) -> None:
    t.clock("clk")
    t.reset("rst", cycles_asserted=2, cycles_deasserted=1)
    t.timeout(128)

    for cyc in range(32):
        t.drive("boot_pc", 0x1000, at=cyc)
        t.drive("imem_rdata", 0, at=cyc)
        t.drive("backend_ready", 0, at=cyc)
        t.drive("redirect_valid", 0, at=cyc)
        t.drive("redirect_pc", 0, at=cyc)
        t.drive("flush_valid", 0, at=cyc)
        t.drive("flush_pc", 0, at=cyc)

    # Consume one packet.
    t.drive("backend_ready", 1, at=2)

    # Redirect path updates fetch address and PC state.
    t.drive("redirect_valid", 1, at=3)
    t.drive("redirect_pc", 0x2000, at=3)

    # Flush has priority over redirect target selection and clears queue state.
    t.drive("flush_valid", 1, at=5)
    t.drive("flush_pc", 0x3000, at=5)

    t.finish(at=8)
