from __future__ import annotations

from pycircuit import Circuit, module


@module(name="LinxCoreUidAllocator")
def build_uid_allocator(m: Circuit) -> None:
    clk_top = m.clock("clk")
    rst_top = m.reset("rst")

    # Deterministic allocation order per cycle:
    # 1) IFU fetch packets (up to 4)
    # 2) Template children
    # 3) Replay instances
    fetch_alloc_count_i = m.input("fetch_alloc_count_i", width=3)
    template_alloc_count_i = m.input("template_alloc_count_i", width=2)
    replay_alloc_count_i = m.input("replay_alloc_count_i", width=2)

    c = m.const

    next_uid_top = m.out(
        "next_uid_top",
        clk=clk_top,
        rst=rst_top,
        width=64,
        # fetch UID=1 is used by IFU reset state, so allocator starts from 2.
        init=c(2, width=64),
        en=c(1, width=1),
    )

    fetch_base_top = next_uid_top.out()
    template_base_top = fetch_base_top + fetch_alloc_count_i.zext(width=64)
    replay_base_top = template_base_top + template_alloc_count_i.zext(width=64)

    total_alloc_top = (
        fetch_alloc_count_i.zext(width=64)
        + template_alloc_count_i.zext(width=64)
        + replay_alloc_count_i.zext(width=64)
    )
    next_uid_n_top = next_uid_top.out() + total_alloc_top
    next_uid_top.set(next_uid_n_top)

    m.output("fetch_uid_base_o", fetch_base_top)
    m.output("template_uid_base_o", template_base_top)
    m.output("replay_uid_base_o", replay_base_top)
    m.output("next_uid_dbg_o", next_uid_top.out())
