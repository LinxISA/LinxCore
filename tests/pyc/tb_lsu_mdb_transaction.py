from __future__ import annotations

from pycircuit import Tb, testbench

from bcc.lsu.mdb_transaction import (
    build_linx_bcc_lsu_mdb_transaction_control as build,
)


@testbench
def tb(t: Tb) -> None:
    # CROSS_RTL_SCENARIO: atomic-conflict-admission
    # CROSS_RTL_SCENARIO: conflict-sink-backpressure
    # CROSS_RTL_SCENARIO: unrequired-sink-bypass
    t.clock("clk")
    t.reset("rst", cycles_asserted=2, cycles_deasserted=1)
    t.timeout(32)

    names = (
        "enable",
        "candidate_valid",
        "record_required",
        "wait_plan_required",
        "recovery_required",
        "record_ready",
        "wait_plan_ready",
        "recovery_ready",
    )
    for cyc in range(8):
        for name in names:
            t.drive(name, 0, at=cyc)

    # A missing recovery credit must suppress every derived valid.
    for name in ("enable", "candidate_valid", "record_required", "recovery_required", "record_ready", "wait_plan_ready"):
        t.drive(name, 1, at=0)
    t.expect("candidate_ready", 0, at=0, phase="pre")
    t.expect("accepted", 0, at=0, phase="pre")
    t.expect("record_valid", 0, at=0, phase="pre")
    t.expect("recovery_valid", 0, at=0, phase="pre")

    # Both required sinks publish from the same accepted candidate.
    for name in ("enable", "candidate_valid", "record_required", "recovery_required", "record_ready", "wait_plan_ready", "recovery_ready"):
        t.drive(name, 1, at=1)
    t.expect("candidate_ready", 1, at=1, phase="pre")
    t.expect("accepted", 1, at=1, phase="pre")
    t.expect("record_valid", 1, at=1, phase="pre")
    t.expect("recovery_valid", 1, at=1, phase="pre")
    t.expect("wait_plan_valid", 0, at=1, phase="pre")

    # Sinks that are not required cannot block a conflict-free candidate.
    t.drive("enable", 1, at=2)
    t.drive("candidate_valid", 1, at=2)
    t.expect("candidate_ready", 1, at=2, phase="pre")
    t.expect("accepted", 1, at=2, phase="pre")
    t.expect("record_valid", 0, at=2, phase="pre")
    t.expect("wait_plan_valid", 0, at=2, phase="pre")
    t.expect("recovery_valid", 0, at=2, phase="pre")

    # A required wait-plan sink participates in the same ready chain.
    t.drive("enable", 1, at=3)
    t.drive("candidate_valid", 1, at=3)
    t.drive("wait_plan_required", 1, at=3)
    t.expect("candidate_ready", 0, at=3, phase="pre")
    t.expect("accepted", 0, at=3, phase="pre")
    t.expect("wait_plan_valid", 0, at=3, phase="pre")

    t.drive("enable", 1, at=4)
    t.drive("candidate_valid", 1, at=4)
    t.drive("wait_plan_required", 1, at=4)
    t.drive("wait_plan_ready", 1, at=4)
    t.expect("accepted", 1, at=4, phase="pre")
    t.expect("wait_plan_valid", 1, at=4, phase="pre")

    t.finish(at=6)
