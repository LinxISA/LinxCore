from __future__ import annotations

from pycircuit import Circuit, module


@module(name="JanusBccIfuCtrl")
def build_janus_bcc_ifu_ctrl(m: Circuit) -> None:
    clk_top = m.clock("clk")
    rst_top = m.reset("rst")

    f2_to_f3_stage_valid_f2 = m.input("f2_to_f3_stage_valid_f2", width=1)
    flush_valid_fls = m.input("flush_valid_fls", width=1)

    c = m.const

    with m.scope("fctrl"):
        checkpoint_head_f3 = m.out(
            "checkpoint_head_f3",
            clk=clk_top,
            rst=rst_top,
            width=6,
            init=c(0, width=6),
            en=c(1, width=1),
        )

    checkpoint_next_f3 = checkpoint_head_f3.out()
    checkpoint_next_f3 = f2_to_f3_stage_valid_f2._select_internal(checkpoint_head_f3.out() + c(1, width=6), checkpoint_next_f3)
    checkpoint_next_f3 = flush_valid_fls._select_internal(c(0, width=6), checkpoint_next_f3)
    checkpoint_head_f3.set(checkpoint_next_f3)

    m.output("ctrl_to_f3_stage_checkpoint_id_f3", checkpoint_head_f3.out())
