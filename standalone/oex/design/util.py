from __future__ import annotations

from pycircuit import Circuit, Reg, Vec, Wire, function


@function
def mux_by_uindex(m: Circuit, *, idx: Wire, items: list[Wire | Reg], default: Wire) -> Wire:
    """Linear mux-chain helper local to standalone OEX."""
    c = m.const
    out: Wire = default
    for i, it in enumerate(items):
        w = it if isinstance(it, Wire) else it.out()
        out = idx.__eq__(c(i, width=idx.width))._select_internal(w, out)
    return out


@function
def pack_bits_lsb(m: Circuit, *, bits_lsb: list[Wire]) -> Wire:
    """Pack LSB-first bits into a bus Wire (bit0 is LSB)."""
    _ = m
    return Vec(tuple(reversed(bits_lsb))).pack()


@function
def priority_pick_lsb(m: Circuit, *, bits_lsb: list[Wire], idx_w: int) -> tuple[Wire, Wire]:
    """Pick the lowest-index set bit: returns (found:i1, idx:iW)."""
    c = m.const
    iw = max(1, int(idx_w))
    found = c(0, width=1)
    idx = c(0, width=iw)
    for i, b in enumerate(bits_lsb):
        take = b & (~found)
        idx = take._select_internal(c(i, width=iw), idx)
        found = found | b
    return found, idx
