from __future__ import annotations

from pycircuit import Circuit, module


@module(name="JanusBccBctrlBrenu")
def build_janus_bcc_bctrl_brenu(m: Circuit) -> None:
    clk_top = m.clock("clk")
    rst_top = m.reset("rst")

    issue_fire_brenu = m.input("issue_fire_brenu", width=1)

    c = m.const
    with m.scope("brenu"):
        tag_ctr_brenu = m.out("tag_ctr_brenu", clk=clk_top, rst=rst_top, width=8, init=c(0, width=8), en=c(1, width=1))
        epoch_ctr_brenu = m.out("epoch_ctr_brenu", clk=clk_top, rst=rst_top, width=8, init=c(0, width=8), en=c(1, width=1))

    tag_next_brenu = issue_fire_brenu._select_internal(tag_ctr_brenu.out() + c(1, width=8), tag_ctr_brenu.out())
    epoch_next_brenu = issue_fire_brenu._select_internal(epoch_ctr_brenu.out() + c(1, width=8), epoch_ctr_brenu.out())
    tag_ctr_brenu.set(tag_next_brenu)
    epoch_ctr_brenu.set(epoch_next_brenu)

    m.output("brenu_tag_brenu", tag_ctr_brenu.out())
    m.output("brenu_epoch_brenu", epoch_ctr_brenu.out())
