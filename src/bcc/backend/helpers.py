from __future__ import annotations

from pycircuit import Circuit, Reg, Wire, function


@function
def mux_by_uindex(m: Circuit, *, idx: Wire, items: list[Wire | Reg], default: Wire) -> Wire:
    """Return items[idx] (idx is an integer code), using a linear mux chain."""
    c = m.const
    out: Wire = default
    for i, it in enumerate(items):
        w = it if isinstance(it, Wire) else it.out()
        out = idx.__eq__(c(i, width=idx.width))._select_internal(w, out)
    return out


@function
def mask_bit(m: Circuit, *, mask: Wire, idx: Wire, width: int) -> Wire:
    """Return mask[idx] as i1, using a linear mux chain."""
    c = m.const
    out = c(0, width=1)
    for i in range(int(width)):
        out = idx.__eq__(c(i, width=idx.width))._select_internal(mask[i], out)
    return out


@function
def onehot_from_tag(m: Circuit, *, tag: Wire, width: int, tag_width: int) -> Wire:
    c = m.const
    out = c(0, width=width)
    for i in range(int(width)):
        out = tag.__eq__(c(i, width=tag_width))._select_internal(c(1 << i, width=width), out)
    return out


@function
def alloc_from_free_mask(m: Circuit, *, free_mask: Wire, width: int, tag_width: int) -> tuple[Wire, Wire, Wire]:
    """Priority-encode the lowest free bit in `free_mask`."""
    c = m.const
    valid = c(0, width=1)
    tag = c(0, width=tag_width)
    onehot = c(0, width=width)
    for i in range(int(width)):
        bit = free_mask[i]
        take = bit & (~valid)
        valid = take._select_internal(c(1, width=1), valid)
        tag = take._select_internal(c(i, width=tag_width), tag)
        onehot = take._select_internal(c(1 << i, width=width), onehot)
    return valid, tag, onehot
