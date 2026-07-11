from __future__ import annotations

from pycircuit import Tb, testbench

from bcc.backend.modules.mapq import build_scalar_mapq as build


@testbench
def tb(t: Tb) -> None:
    t.clock("clk")
    t.reset("rst", cycles_asserted=2, cycles_deasserted=1)
    t.timeout(64)

    for cycle in range(12):
        t.drive("rename_valid", 0, at=cycle)
        t.drive("rename_arch", 0, at=cycle)
        t.drive("rename_old_phys", 0, at=cycle)
        t.drive("rename_new_phys", 0, at=cycle)
        t.drive("rename_rid", 0, at=cycle)
        t.drive("rename_order", 0, at=cycle)
        t.drive("commit_valid", 0, at=cycle)
        t.drive("commit_rid", 0, at=cycle)
        t.drive("commit_order", 0, at=cycle)
        t.drive("flush_valid", 0, at=cycle)
        t.drive("flush_order", 0, at=cycle)
        t.drive("flush_inclusive", 0, at=cycle)

    t.drive("rename_valid", 1, at=0)
    t.drive("rename_arch", 3, at=0)
    t.drive("rename_old_phys", 3, at=0)
    t.drive("rename_new_phys", 24, at=0)
    t.drive("rename_rid", 5, at=0)
    t.drive("rename_order", 10, at=0)
    t.expect("rename_fire", 1, at=0)

    t.drive("rename_valid", 1, at=1)
    t.drive("rename_arch", 3, at=1)
    t.drive("rename_old_phys", 24, at=1)
    t.drive("rename_new_phys", 25, at=1)
    t.drive("rename_rid", 6, at=1)
    t.drive("rename_order", 11, at=1)

    t.drive("commit_valid", 1, at=2)
    t.drive("commit_rid", 5, at=2)
    t.drive("commit_order", 10, at=2)
    t.expect("commit_match", 1, at=2)
    t.expect("commit_arch", 3, at=2)
    t.expect("commit_new_phys", 24, at=2)
    t.expect("release_phys_mask", 1 << 3, at=2)

    t.drive("flush_valid", 1, at=4)
    t.drive("flush_order", 10, at=4)
    t.expect("flush_prune_mask", 0x2, at=4)
    t.expect("release_phys_mask", 1 << 25, at=4)

    t.expect("valid_mask", 0, at=4)
    t.finish(at=6)
