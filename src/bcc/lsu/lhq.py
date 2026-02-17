from __future__ import annotations

from pycircuit import Circuit, module


@module(name="JanusBccLsuLhq")
def build_janus_bcc_lsu_lhq(m: Circuit) -> None:
    clk_lhq = m.clock("clk")
    rst_lhq = m.reset("rst")

    liq_fire_liq = m.input("liq_fire_liq", width=1)
    liq_rob_liq = m.input("liq_rob_liq", width=6)
    liq_addr_liq = m.input("liq_addr_liq", width=64)

    older_store_valid_lhq = m.input("older_store_valid_lhq", width=1)
    older_store_addr_lhq = m.input("older_store_addr_lhq", width=64)

    c = m.const

    conflict_seen_lhq = m.out("conflict_seen_lhq", clk=clk_lhq, rst=rst_lhq, width=1, init=c(0, width=1), en=c(1, width=1))
    conflict_addr_lhq = m.out("conflict_addr_lhq", clk=clk_lhq, rst=rst_lhq, width=64, init=c(0, width=64), en=c(1, width=1))

    conflict_lhq = liq_fire_liq & older_store_valid_lhq & (liq_addr_liq == older_store_addr_lhq)

    conflict_seen_next_lhq = conflict_seen_lhq.out() | conflict_lhq
    conflict_seen_lhq.set(conflict_seen_next_lhq)
    conflict_addr_lhq.set(liq_addr_liq, when=conflict_lhq)

    m.output("lhq_fire_lhq", liq_fire_liq)
    m.output("lhq_rob_lhq", liq_rob_liq)
    m.output("lhq_addr_lhq", liq_addr_liq)
    m.output("lhq_conflict_lhq", conflict_lhq)
    m.output("lhq_conflict_seen_lhq", conflict_seen_lhq.out())
    m.output("lhq_conflict_addr_lhq", conflict_addr_lhq.out())
