from __future__ import annotations

from pycircuit import Circuit, module


@module(name="JanusBccBctrlBrob")
def build_janus_bcc_bctrl_brob(m: Circuit) -> None:
    clk_top = m.clock("clk")
    rst_top = m.reset("rst")

    issue_fire_brob = m.input("issue_fire_brob", width=1)
    issue_tag_brob = m.input("issue_tag_brob", width=8)
    rsp_valid_brob = m.input("rsp_valid_brob", width=1)
    rsp_tag_brob = m.input("rsp_tag_brob", width=8)

    c = m.const

    pending_brob = m.out("pending_brob", clk=clk_top, rst=rst_top, width=16, init=c(0, width=16), en=c(1, width=1))

    issue_bit_brob = c(0, width=16)
    rsp_bit_brob = c(0, width=16)
    for i in range(16):
        issue_bit_brob = (issue_tag_brob[0:4] == c(i, width=4))._select_internal(c(1 << i, width=16), issue_bit_brob)
        rsp_bit_brob = (rsp_tag_brob[0:4] == c(i, width=4))._select_internal(c(1 << i, width=16), rsp_bit_brob)

    pending_next_brob = pending_brob.out()
    pending_next_brob = issue_fire_brob._select_internal(pending_next_brob | issue_bit_brob, pending_next_brob)
    pending_next_brob = rsp_valid_brob._select_internal(pending_next_brob & (~rsp_bit_brob), pending_next_brob)
    pending_brob.set(pending_next_brob)

    m.output("brob_pending_brob", pending_brob.out())
