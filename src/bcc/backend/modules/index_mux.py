from __future__ import annotations

from pycircuit import Circuit, Reg, Wire, function

from ..helpers import mux_by_uindex


@function
def banked_mux_by_uindex(
    m: Circuit,
    *,
    idx: Wire,
    items: list[Wire | Reg],
    default: Wire,
    bank_depth: int = 16,
) -> Wire:
    _ = bank_depth
    return mux_by_uindex(m, idx=idx, items=items, default=default)
