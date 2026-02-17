from __future__ import annotations

from pycircuit import Circuit

from top.top import build_linxcore_top


def build(
    m: Circuit,
    *,
    mem_bytes: int = (1 << 20),
    ic_sets: int = 32,
    ic_ways: int = 4,
    ic_line_bytes: int = 64,
    ifetch_bundle_bytes: int = 128,
    ib_depth: int = 8,
    ic_miss_outstanding: int = 1,
    ic_enable: int = 1,
) -> None:
    build_linxcore_top(
        m,
        mem_bytes=mem_bytes,
        ic_sets=ic_sets,
        ic_ways=ic_ways,
        ic_line_bytes=ic_line_bytes,
        ifetch_bundle_bytes=ifetch_bundle_bytes,
        ib_depth=ib_depth,
        ic_miss_outstanding=ic_miss_outstanding,
        ic_enable=ic_enable,
    )


build.__pycircuit_name__ = "linxcore_top"
