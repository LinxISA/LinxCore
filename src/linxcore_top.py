from __future__ import annotations

from pycircuit import Circuit

from top.top import build_linxcore_top


def build(m: Circuit, *, mem_bytes: int = (1 << 20)) -> None:
    build_linxcore_top(m, mem_bytes=mem_bytes)


build.__pycircuit_name__ = "linxcore_top"
