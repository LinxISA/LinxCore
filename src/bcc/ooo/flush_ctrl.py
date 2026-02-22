from __future__ import annotations

from pycircuit import Circuit, module


@module(name="JanusBccOooFlushCtrl")
def build_janus_bcc_ooo_flush_ctrl(m: Circuit) -> None:
    clk_fls = m.clock("clk")
    rst_fls = m.reset("rst")

    rob_to_flush_ctrl_stage_redirect_valid_rob = m.input("rob_to_flush_ctrl_stage_redirect_valid_rob", width=1)
    rob_to_flush_ctrl_stage_redirect_pc_rob = m.input("rob_to_flush_ctrl_stage_redirect_pc_rob", width=64)
    rob_to_flush_ctrl_stage_checkpoint_id_rob = m.input("rob_to_flush_ctrl_stage_checkpoint_id_rob", width=6)

    c = m.const

    pending_valid_fls = m.out("pending_valid_fls", clk=clk_fls, rst=rst_fls, width=1, init=c(0, width=1), en=c(1, width=1))
    pending_pc_fls = m.out("pending_pc_fls", clk=clk_fls, rst=rst_fls, width=64, init=c(0, width=64), en=c(1, width=1))
    pending_checkpoint_fls = m.out("pending_checkpoint_fls", clk=clk_fls, rst=rst_fls, width=6, init=c(0, width=6), en=c(1, width=1))

    pending_valid_next_fls = pending_valid_fls.out()
    pending_valid_next_fls = pending_valid_fls.out()._select_internal(c(0, width=1), pending_valid_next_fls)
    pending_valid_next_fls = rob_to_flush_ctrl_stage_redirect_valid_rob._select_internal(c(1, width=1), pending_valid_next_fls)

    pending_valid_fls.set(pending_valid_next_fls)
    pending_pc_fls.set(rob_to_flush_ctrl_stage_redirect_pc_rob, when=rob_to_flush_ctrl_stage_redirect_valid_rob)
    pending_checkpoint_fls.set(rob_to_flush_ctrl_stage_checkpoint_id_rob, when=rob_to_flush_ctrl_stage_redirect_valid_rob)

    m.output("flush_valid_fls", pending_valid_fls.out())
    m.output("flush_pc_fls", pending_pc_fls.out())
    m.output("flush_checkpoint_id_fls", pending_checkpoint_fls.out())
