from __future__ import annotations

from pycircuit import Circuit, module


@module(name="JanusBccLsuLhq")
def build_janus_bcc_lsu_lhq(m: Circuit) -> None:
    liq_fire_liq = m.input("liq_fire_liq", width=1)
    liq_rob_liq = m.input("liq_rob_liq", width=6)
    liq_addr_liq = m.input("liq_addr_liq", width=64)

    older_store_valid_lhq = m.input("older_store_valid_lhq", width=1)
    older_store_addr_lhq = m.input("older_store_addr_lhq", width=64)

    conflict_lhq = liq_fire_liq & older_store_valid_lhq & (liq_addr_liq == older_store_addr_lhq)

    m.output("lhq_fire_lhq", liq_fire_liq)
    m.output("lhq_rob_lhq", liq_rob_liq)
    m.output("lhq_addr_lhq", liq_addr_liq)
    m.output("lhq_conflict_lhq", conflict_lhq)
