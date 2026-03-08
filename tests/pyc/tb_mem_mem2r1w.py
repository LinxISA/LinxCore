from __future__ import annotations

from pycircuit import Tb, testbench

from mem.mem2r1w import build_mem2r1w as build  # noqa: E402


@testbench
def tb(t: Tb) -> None:
    t.clock("clk")
    t.reset("rst", cycles_asserted=2, cycles_deasserted=1)
    t.timeout(96)

    stack_base = 0x0000000007FE0000

    for cyc in range(24):
        t.drive("if_raddr", 0, at=cyc)
        t.drive("d_raddr", 0, at=cyc)

        t.drive("d_wvalid", 0, at=cyc)
        t.drive("d_waddr", 0, at=cyc)
        t.drive("d_wdata", 0, at=cyc)
        t.drive("d_wstrb", 0, at=cyc)

        t.drive("host_wvalid", 0, at=cyc)
        t.drive("host_waddr", 0, at=cyc)
        t.drive("host_wdata", 0, at=cyc)
        t.drive("host_wstrb", 0, at=cyc)

    # Cycle 0: d-port write in low region.
    t.drive("d_wvalid", 1, at=0)
    t.drive("d_waddr", 0x40, at=0)
    t.drive("d_wdata", 0x1122334455667788, at=0)
    t.drive("d_wstrb", 0xFF, at=0)
    t.expect("wvalid_eff", 1, at=0)
    t.expect("waddr_eff", 0x40, at=0)
    t.expect("wdata_eff", 0x1122334455667788, at=0)
    t.expect("wstrb_eff", 0xFF, at=0)

    # Cycle 1: host write wins arbitration when both write channels are valid.
    t.drive("d_wvalid", 1, at=1)
    t.drive("d_waddr", 0x80, at=1)
    t.drive("d_wdata", 0xDEADBEEFCAFEBABE, at=1)
    t.drive("d_wstrb", 0x0F, at=1)

    t.drive("host_wvalid", 1, at=1)
    t.drive("host_waddr", stack_base + 0x20, at=1)
    t.drive("host_wdata", 0xAABBCCDDEEFF0011, at=1)
    t.drive("host_wstrb", 0xF0, at=1)
    t.expect("wvalid_eff", 1, at=1)
    t.expect("waddr_eff", 0x0000000000080020, at=1)
    t.expect("wdata_eff", 0xAABBCCDDEEFF0011, at=1)
    t.expect("wstrb_eff", 0xF0, at=1)

    # Cycle 2: d-port stack-window write uses mapped upper-half address window.
    t.drive("d_wvalid", 1, at=2)
    t.drive("d_waddr", stack_base + 0x10, at=2)
    t.drive("d_wdata", 0x0102030405060708, at=2)
    t.drive("d_wstrb", 0xFF, at=2)
    t.expect("wvalid_eff", 1, at=2)
    t.expect("waddr_eff", 0x0000000000080010, at=2)

    # Cycle 3+: hold read addresses; this TB focuses on arbitration + address mapping
    # via explicit `*_eff` outputs (byte_mem read timing is backend-dependent).
    t.drive("if_raddr", 0x40, at=3)
    t.drive("d_raddr", 0x40, at=3)
    t.expect("wvalid_eff", 0, at=3)

    # Cycle 4: stack virtual addresses are driven; no write should be active.
    t.drive("if_raddr", stack_base + 0x20, at=4)
    t.drive("d_raddr", stack_base + 0x20, at=4)
    t.expect("wvalid_eff", 0, at=4)

    t.finish(at=6)
