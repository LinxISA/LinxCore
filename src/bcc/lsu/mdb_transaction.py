from __future__ import annotations

from pycircuit import Circuit, module


@module(name="LinxBccLsuMdbTransactionControl")
def build_linx_bcc_lsu_mdb_transaction_control(m: Circuit) -> None:
    m.clock("clk")
    m.reset("rst")

    enable = m.input("enable", width=1)
    candidate_valid = m.input("candidate_valid", width=1)
    record_required = m.input("record_required", width=1)
    wait_plan_required = m.input("wait_plan_required", width=1)
    recovery_required = m.input("recovery_required", width=1)
    record_ready = m.input("record_ready", width=1)
    wait_plan_ready = m.input("wait_plan_ready", width=1)
    recovery_ready = m.input("recovery_ready", width=1)

    required_sinks_ready = (
        ((~record_required) | record_ready)
        & ((~wait_plan_required) | wait_plan_ready)
        & ((~recovery_required) | recovery_ready)
    )
    candidate_ready = enable & required_sinks_ready
    accepted = candidate_valid & candidate_ready
    record_valid = accepted & record_required
    wait_plan_valid = accepted & wait_plan_required
    recovery_valid = accepted & recovery_required

    m.output("candidate_ready", candidate_ready)
    m.output("accepted", accepted)
    m.output("record_valid", record_valid)
    m.output("wait_plan_valid", wait_plan_valid)
    m.output("recovery_valid", recovery_valid)
