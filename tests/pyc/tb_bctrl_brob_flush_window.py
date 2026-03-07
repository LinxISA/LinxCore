from __future__ import annotations

from pycircuit import Tb, testbench

from bcc.bctrl.brob import build_janus_bcc_bctrl_brob as build  # noqa: E402


@testbench
def tb(t: Tb) -> None:
    t.clock("clk")
    t.reset("rst", cycles_asserted=2, cycles_deasserted=1)
    t.timeout(48)

    def drive_defaults(cyc: int) -> None:
        t.drive("alloc_fire_brob", 0, at=cyc)
        t.drive("issue_fire_brob", 0, at=cyc)
        t.drive("issue_tag_brob", 0, at=cyc)
        t.drive("issue_bid_brob", 0, at=cyc)
        t.drive("issue_src_rob_brob", 0, at=cyc)
        t.drive("retire_fire_brob", 0, at=cyc)
        t.drive("retire_bid_brob", 0, at=cyc)
        t.drive("query_bid_brob", 0, at=cyc)
        t.drive("flush_valid_brob", 0, at=cyc)
        t.drive("flush_fire_brob", 0, at=cyc)
        t.drive("flush_bid_brob", 0, at=cyc)
        t.drive("rsp_valid_brob", 0, at=cyc)
        t.drive("rsp_tag_brob", 0, at=cyc)
        t.drive("rsp_status_brob", 0, at=cyc)
        t.drive("rsp_data0_brob", 0, at=cyc)
        t.drive("rsp_data1_brob", 0, at=cyc)
        t.drive("rsp_trap_valid_brob", 0, at=cyc)
        t.drive("rsp_trap_cause_brob", 0, at=cyc)

    for cyc in range(10):
        drive_defaults(cyc)

    # Build a live window where head_bid > 1:
    #   c0 alloc bid1
    #   c1 alloc bid2
    #   c2 retire bid1 => head becomes 2
    #   c3 alloc bid3 => live bids {2,3}, tail=4, count=2
    t.drive("alloc_fire_brob", 1, at=0)
    t.drive("alloc_fire_brob", 1, at=1)
    t.drive("retire_fire_brob", 1, at=2)
    t.drive("retire_bid_brob", 1, at=2)
    t.drive("alloc_fire_brob", 1, at=3)

    # Flush with an older-than-head bid. This must clamp to the live window and
    # never underflow BROB count.
    t.drive("flush_valid_brob", 1, at=4)
    t.drive("flush_fire_brob", 1, at=4)
    t.drive("flush_bid_brob", 0, at=4)

    t.expect("brob_count_brob", 0, at=4)
    t.expect("brob_alloc_ready_brob", 1, at=4)

    # After clamp-to-zero flush, allocation must still work.
    t.drive("alloc_fire_brob", 1, at=5)
    t.expect("brob_count_brob", 1, at=5)

    t.finish(at=6)
