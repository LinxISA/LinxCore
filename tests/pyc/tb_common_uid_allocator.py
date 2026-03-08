from __future__ import annotations

from pycircuit import Tb, testbench

from common.uid_allocator import build_uid_allocator as build  # noqa: E402


@testbench
def tb(t: Tb) -> None:
    t.clock("clk")
    t.reset("rst", cycles_asserted=2, cycles_deasserted=1)
    t.timeout(64)

    for cyc in range(16):
        t.drive("fetch_alloc_count_i", 0, at=cyc)
        t.drive("template_alloc_count_i", 0, at=cyc)
        t.drive("replay_alloc_count_i", 0, at=cyc)

    # Cycle 0: post-cycle state after allocating (2 + 1 + 1) from base UID=2.
    t.drive("fetch_alloc_count_i", 2, at=0)
    t.drive("template_alloc_count_i", 1, at=0)
    t.drive("replay_alloc_count_i", 1, at=0)
    t.expect("fetch_uid_base_o", 6, at=0)
    t.expect("template_uid_base_o", 8, at=0)
    t.expect("replay_uid_base_o", 9, at=0)
    t.expect("next_uid_dbg_o", 6, at=0)

    # Cycle 1: allocate (1 + 0 + 1), then observe post-cycle state.
    t.drive("fetch_alloc_count_i", 1, at=1)
    t.drive("template_alloc_count_i", 0, at=1)
    t.drive("replay_alloc_count_i", 1, at=1)
    t.expect("fetch_uid_base_o", 8, at=1)
    t.expect("template_uid_base_o", 9, at=1)
    t.expect("replay_uid_base_o", 9, at=1)
    t.expect("next_uid_dbg_o", 8, at=1)

    # Cycle 2: no allocations, cursor remains stable.
    t.expect("fetch_uid_base_o", 8, at=2)
    t.expect("template_uid_base_o", 8, at=2)
    t.expect("replay_uid_base_o", 8, at=2)
    t.expect("next_uid_dbg_o", 8, at=2)

    t.finish(at=4)
