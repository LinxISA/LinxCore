from __future__ import annotations

"""LSU top (bring-up skeleton).

Milestone goal:
- Implement the committed-store drain path (policy B) via SCB→D$ stub.

This module will later absorb STQ/LIQ/LHQ/MDB integration, forwarding, and
nuke/miss plumbing. For now it exposes just enough ports for top-level wiring.
"""

from pycircuit import Circuit, module

from bcc.lsu.lsu_store_drain import build_janus_bcc_lsu_store_drain


@module(name="JanusBccLsu")
def build_janus_bcc_lsu(m: Circuit) -> None:
    clk = m.clock("clk")
    rst = m.reset("rst")

    # --- committed store input (non-flush policy B)
    commit_store_fire = m.input("commit_store_fire", width=1)
    commit_store_addr = m.input("commit_store_addr", width=64)
    commit_store_data = m.input("commit_store_data", width=64)
    commit_store_size = m.input("commit_store_size", width=4)

    store_drain = m.instance_auto(
        build_janus_bcc_lsu_store_drain,
        name="store_drain",
        module_name="JanusBccLsuStoreDrain",
        clk=clk,
        rst=rst,
        commit_store_fire=commit_store_fire,
        commit_store_addr=commit_store_addr,
        commit_store_data=commit_store_data,
        commit_store_size=commit_store_size,
    )

    # Debug exports (keep narrow; expand as needed)
    m.output("scb_enq_fire", store_drain["scb_enq_fire"])
    m.output("scb_req_valid", store_drain["scb_req_valid"])
    m.output("scb_count", store_drain["scb_count"])
    m.output("scb_inflight", store_drain["scb_inflight"])
