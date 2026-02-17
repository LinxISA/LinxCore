from __future__ import annotations

from pycircuit import Circuit, module


@module(name="JanusBccBctrlBrenu")
def build_janus_bcc_bctrl_brenu(m: Circuit) -> None:
    clk_brenu = m.clock("clk")
    rst_brenu = m.reset("rst")

    issue_fire_brenu = m.input("issue_fire_brenu", width=1)

    c = m.const
    slot_w_brenu = 4
    with m.scope("brenu"):
        tag_ctr_brenu = m.out("tag_ctr_brenu", clk=clk_brenu, rst=rst_brenu, width=8, init=c(0, width=8), en=c(1, width=1))
        epoch_ctr_brenu = m.out("epoch_ctr_brenu", clk=clk_brenu, rst=rst_brenu, width=8, init=c(0, width=8), en=c(1, width=1))
        # High BID bits: debug-only uniqueness domain.
        issued_ctr_brenu = m.out("issued_ctr_brenu", clk=clk_brenu, rst=rst_brenu, width=60, init=c(0, width=60), en=c(1, width=1))

    tag_next_brenu = issue_fire_brenu._select_internal(tag_ctr_brenu.out() + c(1, width=8), tag_ctr_brenu.out())
    epoch_roll_brenu = issue_fire_brenu & tag_ctr_brenu.out().eq(c(0xFF, width=8))
    epoch_next_brenu = epoch_roll_brenu._select_internal(epoch_ctr_brenu.out() + c(1, width=8), epoch_ctr_brenu.out())

    issued_next_brenu = issued_ctr_brenu.out()
    issued_next_brenu = issue_fire_brenu._select_internal(issued_ctr_brenu.out() + c(1, width=60), issued_next_brenu)

    slot_id_brenu = tag_ctr_brenu.out()[0:slot_w_brenu]
    # BID contract:
    #   low bits  = BROB entry id (slot)
    #   high bits = debug-only unique sequence.
    bid_brenu = (issued_ctr_brenu.out().zext(width=64).shl(amount=slot_w_brenu)) | slot_id_brenu.zext(width=64)

    tag_ctr_brenu.set(tag_next_brenu)
    epoch_ctr_brenu.set(epoch_next_brenu)
    issued_ctr_brenu.set(issued_next_brenu)

    m.output("brenu_tag_brenu", tag_ctr_brenu.out())
    m.output("brenu_slot_id_brenu", slot_id_brenu)
    m.output("brenu_bid_brenu", bid_brenu)
    m.output("brenu_epoch_brenu", epoch_ctr_brenu.out())
    m.output("brenu_issue_ready_brenu", c(1, width=1))
    m.output("brenu_issued_ctr_brenu", issued_ctr_brenu.out())
