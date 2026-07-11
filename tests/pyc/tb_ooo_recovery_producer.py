from __future__ import annotations

from pycircuit import Tb, testbench

from bcc.ooo.recovery_producer import build_linx_bcc_ooo_recovery_producer as build


@testbench
def tb(t: Tb) -> None:
    t.clock("clk")
    t.reset("rst", cycles_asserted=2, cycles_deasserted=1)
    t.timeout(64)

    BID0 = 0x8000_0000_0000_1234
    TPC0 = 0xFFFF_0000_0000_4568
    BID1 = 0x1111_2222_3333_4444
    TPC1 = 0x5555_6666_7777_8888
    BAD_BID = 0xDEAD_BEEF_DEAD_BEEF
    BAD_TPC = 0xCAFE_BABE_CAFE_BABE

    for cyc in range(16):
        t.drive("recovery_valid", 0, at=cyc)
        t.drive("recovery_bid", 0, at=cyc)
        t.drive("recovery_restart_tpc", 0, at=cyc)
        t.drive("recovery_stid_valid", 0, at=cyc)
        t.drive("recovery_stid", 0, at=cyc)
        t.drive("recovery_owner_valid", 0, at=cyc)
        t.drive("recovery_owner", 0, at=cyc)
        t.drive("packet_ready", 0, at=cyc)

    # Missing STID/owner identity must not create an output packet.
    t.drive("recovery_valid", 1, at=0)
    t.drive("recovery_bid", BAD_BID, at=0)
    t.drive("recovery_restart_tpc", BAD_TPC, at=0)
    t.drive("recovery_stid_valid", 0, at=0)
    t.drive("recovery_stid", 0x12, at=0)
    t.drive("recovery_owner_valid", 1, at=0)
    t.drive("recovery_owner", 0x34, at=0)
    t.expect("recovery_fire", 0, at=0)
    t.expect("recovery_ready", 0, at=0)
    t.expect("packet_valid", 0, at=0)

    # A complete identity captures the full-width BID/TPC and owner scope.
    t.drive("recovery_valid", 1, at=1)
    t.drive("recovery_bid", BID0, at=1)
    t.drive("recovery_restart_tpc", TPC0, at=1)
    t.drive("recovery_stid_valid", 1, at=1)
    t.drive("recovery_stid", 0x5A, at=1)
    t.drive("recovery_owner_valid", 1, at=1)
    t.drive("recovery_owner", 0xC3, at=1)
    t.expect("recovery_fire", 1, at=1, phase="pre")

    # Backpressure keeps the packet bit-stable even while new input toggles.
    t.drive("recovery_valid", 1, at=2)
    t.drive("recovery_bid", BID1, at=2)
    t.drive("recovery_restart_tpc", TPC1, at=2)
    t.drive("recovery_stid_valid", 1, at=2)
    t.drive("recovery_stid", 0xA5, at=2)
    t.drive("recovery_owner_valid", 1, at=2)
    t.drive("recovery_owner", 0x3C, at=2)
    t.expect("recovery_ready", 0, at=2, phase="pre")
    t.expect("recovery_fire", 0, at=2, phase="pre")
    t.expect("packet_valid", 1, at=2)
    t.expect("packet_bid", BID0, at=2)
    t.expect("packet_restart_tpc", TPC0, at=2)
    t.expect("packet_stid", 0x5A, at=2)
    t.expect("packet_owner", 0xC3, at=2)

    t.expect("packet_bid", BID0, at=3)
    t.expect("packet_restart_tpc", TPC0, at=3)
    t.expect("packet_stid", 0x5A, at=3)
    t.expect("packet_owner", 0xC3, at=3)

    # Accept the retained packet while replacing it with the next complete one.
    t.drive("packet_ready", 1, at=4)
    t.drive("recovery_valid", 1, at=4)
    t.drive("recovery_bid", BID1, at=4)
    t.drive("recovery_restart_tpc", TPC1, at=4)
    t.drive("recovery_stid_valid", 1, at=4)
    t.drive("recovery_stid", 0xA5, at=4)
    t.drive("recovery_owner_valid", 1, at=4)
    t.drive("recovery_owner", 0x3C, at=4)
    t.expect("packet_fire", 1, at=4, phase="pre")
    t.expect("recovery_fire", 1, at=4, phase="pre")
    t.expect("packet_bid", BID0, at=4, phase="pre")
    t.expect("packet_restart_tpc", TPC0, at=4, phase="pre")

    t.expect("packet_valid", 1, at=5)
    t.expect("packet_bid", BID1, at=5)
    t.expect("packet_restart_tpc", TPC1, at=5)
    t.expect("packet_stid", 0xA5, at=5)
    t.expect("packet_owner", 0x3C, at=5)

    t.drive("packet_ready", 1, at=6)
    t.expect("packet_fire", 1, at=6, phase="pre")
    t.expect("packet_valid", 0, at=6)

    t.finish(at=8)
