from __future__ import annotations

from pycircuit import Circuit, module


@module(name="JanusBccIfuF0")
def build_janus_bcc_ifu_f0(m: Circuit) -> None:
    clk_top = m.clock("clk")
    rst_top = m.reset("rst")

    boot_pc_top = m.input("boot_pc_top", width=64)
    flush_valid_fls = m.input("flush_valid_fls", width=1)
    flush_pc_fls = m.input("flush_pc_fls", width=64)

    f2_to_f0_advance_stage_valid_f2 = m.input("f2_to_f0_advance_stage_valid_f2", width=1)
    f2_to_f0_next_pc_stage_pc_f2 = m.input("f2_to_f0_next_pc_stage_pc_f2", width=64)
    uid_alloc_fire_top = m.input("uid_alloc_fire_top", width=1)
    uid_alloc_uid_top = m.input("uid_alloc_uid_top", width=64)

    c = m.const

    with m.scope("f0_pc"):
        fetch_pc_f0 = m.out(
            "fetch_pc_f0",
            clk=clk_top,
            rst=rst_top,
            width=64,
            init=boot_pc_top,
            en=c(1, width=1),
        )
        fetch_pkt_uid_f0 = m.out(
            "fetch_pkt_uid_f0",
            clk=clk_top,
            rst=rst_top,
            width=64,
            init=c(1, width=64),
            en=c(1, width=1),
        )

    pc_next_f0 = fetch_pc_f0.out()
    pc_next_f0 = f2_to_f0_advance_stage_valid_f2._select_internal(f2_to_f0_next_pc_stage_pc_f2, pc_next_f0)
    pc_next_f0 = flush_valid_fls._select_internal(flush_pc_fls, pc_next_f0)
    fetch_pc_f0.set(pc_next_f0)
    pkt_uid_next_f0 = uid_alloc_fire_top._select_internal(uid_alloc_uid_top, fetch_pkt_uid_f0.out())
    fetch_pkt_uid_f0.set(pkt_uid_next_f0)

    m.output("f0_to_f1_stage_pc_f0", fetch_pc_f0.out())
    m.output("f0_to_f1_stage_valid_f0", c(1, width=1))
    m.output("f0_to_f1_stage_redirect_f0", flush_valid_fls)
    m.output("f0_to_f1_stage_pkt_uid_f0", fetch_pkt_uid_f0.out())
