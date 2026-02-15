from __future__ import annotations

from pycircuit import Circuit, module


@module(name="JanusBccOooFlushCtrl")
def build_janus_bcc_ooo_flush_ctrl(m: Circuit) -> None:
    rob_to_flush_ctrl_stage_redirect_valid_rob = m.input("rob_to_flush_ctrl_stage_redirect_valid_rob", width=1)
    rob_to_flush_ctrl_stage_redirect_pc_rob = m.input("rob_to_flush_ctrl_stage_redirect_pc_rob", width=64)
    rob_to_flush_ctrl_stage_checkpoint_id_rob = m.input("rob_to_flush_ctrl_stage_checkpoint_id_rob", width=6)

    m.output("flush_valid_fls", rob_to_flush_ctrl_stage_redirect_valid_rob)
    m.output("flush_pc_fls", rob_to_flush_ctrl_stage_redirect_pc_rob)
    m.output("flush_checkpoint_id_fls", rob_to_flush_ctrl_stage_checkpoint_id_rob)
