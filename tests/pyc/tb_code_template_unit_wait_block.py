from __future__ import annotations

from pycircuit import Tb, testbench

from bcc.backend.code_template_unit import build_code_template_unit as build  # noqa: E402


@testbench
def tb(t: Tb) -> None:
    t.timeout(16)

    for cyc in range(8):
        t.drive("base_can_run", 0, at=cyc)
        t.drive("head_is_macro", 0, at=cyc)
        t.drive("head_skip", 0, at=cyc)
        t.drive("head_valid", 0, at=cyc)
        t.drive("head_done", 0, at=cyc)
        t.drive("macro_active_i", 0, at=cyc)
        t.drive("macro_wait_commit_i", 0, at=cyc)
        t.drive("macro_phase_i", 0, at=cyc)
        t.drive("macro_op_i", 0, at=cyc)
        t.drive("macro_end_i", 0, at=cyc)
        t.drive("macro_stacksize_i", 0, at=cyc)
        t.drive("macro_reg_i", 0, at=cyc)
        t.drive("macro_i_i", 0, at=cyc)
        t.drive("macro_sp_base_i", 0, at=cyc)
        t.drive("macro_uop_uid_i", 0, at=cyc)
        t.drive("macro_uop_parent_uid_i", 0, at=cyc)

    # Handoff window: template uops are done, but the parent macro commit has
    # not retired yet. IFU must stay blocked and no new start may fire.
    t.drive("base_can_run", 1, at=0)
    t.drive("head_is_macro", 1, at=0)
    t.drive("head_valid", 1, at=0)
    t.drive("head_done", 1, at=0)
    t.drive("macro_wait_commit_i", 1, at=0)
    t.expect("start_fire", 0, at=0)
    t.expect("block_ifu", 1, at=0)

    # Active template execution also blocks IFU.
    t.drive("macro_wait_commit_i", 0, at=1)
    t.drive("macro_active_i", 1, at=1)
    t.expect("start_fire", 0, at=1)
    t.expect("block_ifu", 1, at=1)

    # Once both the active body and the handoff wait clear, IFU may run again.
    t.drive("macro_active_i", 0, at=2)
    t.drive("head_is_macro", 0, at=2)
    t.drive("head_valid", 0, at=2)
    t.drive("head_done", 0, at=2)
    t.expect("start_fire", 0, at=2)
    t.expect("block_ifu", 0, at=2)

    t.finish(at=3)
