from __future__ import annotations

from pycircuit import Circuit, module


@module(name="JanusBccIfuF1")
def build_janus_bcc_ifu_f1(m: Circuit) -> None:
    f0_to_f1_stage_pc_f0 = m.input("f0_to_f1_stage_pc_f0", width=64)
    f0_to_f1_stage_valid_f0 = m.input("f0_to_f1_stage_valid_f0", width=1)

    # F1 performs tag/predict side lookups in a full design. In this
    # milestone it is a timing stage that keeps interface boundaries explicit.
    m.output("f1_to_icache_stage_pc_f1", f0_to_f1_stage_pc_f0)
    m.output("f1_to_icache_stage_valid_f1", f0_to_f1_stage_valid_f0)
