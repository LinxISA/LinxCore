from __future__ import annotations

from pycircuit import Circuit, module


@module(name="JanusBccLsuMdb")
def build_janus_bcc_lsu_mdb(m: Circuit) -> None:
    clk_top = m.clock("clk")
    rst_top = m.reset("rst")

    lhq_conflict_lhq = m.input("lhq_conflict_lhq", width=1)

    c = m.const

    with m.scope("mdb"):
        conflict_seen_mdb = m.out(
            "conflict_seen_mdb",
            clk=clk_top,
            rst=rst_top,
            width=1,
            init=c(0, width=1),
            en=c(1, width=1),
        )

    conflict_seen_mdb.set(lhq_conflict_lhq | conflict_seen_mdb.out())
    m.output("lsu_violation_detected_mdb", lhq_conflict_lhq)
    m.output("lsu_conflict_seen_mdb", conflict_seen_mdb.out())
