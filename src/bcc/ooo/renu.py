from __future__ import annotations

from pycircuit import Circuit, module


@module(name="JanusBccOooRenu")
def build_janus_bcc_ooo_renu(m: Circuit, *, aregs: int = 32) -> None:
    clk_top = m.clock("clk")
    rst_top = m.reset("rst")

    commit_fire_renu = m.input("commit_fire_renu", width=1)
    commit_areg_renu = m.input("commit_areg_renu", width=6)
    commit_pdst_renu = m.input("commit_pdst_renu", width=6)

    c = m.const
    cmap_renu = []
    for i in range(aregs):
        cmap_renu.append(m.out(f"cmap{i}_renu", clk=clk_top, rst=rst_top, width=6, init=c(0, width=6), en=c(1, width=1)))

    for i in range(aregs):
        hit_renu = commit_areg_renu == c(i, width=6)
        cmap_renu[i].set(commit_pdst_renu, when=commit_fire_renu & hit_renu)
        m.output(f"cmap{i}_out_renu", cmap_renu[i].out())
