from __future__ import annotations

from pycircuit import Circuit, module


@module(name="JanusBccIfuICache")
def build_janus_bcc_ifu_icache(m: Circuit) -> None:
    f1_to_icache_stage_pc_f1 = m.input("f1_to_icache_stage_pc_f1", width=64)
    f1_to_icache_stage_valid_f1 = m.input("f1_to_icache_stage_valid_f1", width=1)
    imem_rdata_top = m.input("imem_rdata_top", width=64)

    m.output("imem_raddr_top", f1_to_icache_stage_pc_f1)
    m.output("f1_to_f2_stage_pc_f1", f1_to_icache_stage_pc_f1)
    m.output("f1_to_f2_stage_window_f1", imem_rdata_top)
    m.output("f1_to_f2_stage_valid_f1", f1_to_icache_stage_valid_f1)
    m.output("icache_to_f2_stage_pc_f1", f1_to_icache_stage_pc_f1)
    m.output("icache_to_f2_stage_window_f1", imem_rdata_top)
    m.output("icache_to_f2_stage_valid_f1", f1_to_icache_stage_valid_f1)
