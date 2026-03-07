from __future__ import annotations

from pycircuit import Circuit, Reg, Wire, function

from common.util import shl_var


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
    """Return mask[idx] as i1.

    Uses dynamic logical shift + bit0 extract instead of an O(width) mux chain.
    """
    w = int(width)
    if w <= 0:
        raise ValueError("mask_bit width must be > 0")
    if mask.width != w:
        raise ValueError(f"mask_bit width mismatch: mask={mask.width} width={w}")
    return mask.lshr(amount=idx)[0]


@function
def onehot_from_tag(m: Circuit, *, tag: Wire, width: int, tag_width: int) -> Wire:
    w = int(width)
    if w <= 0:
        raise ValueError("onehot_from_tag width must be > 0")

    # Preserve old semantics: out=0 when tag is out of range.
    c = m.const
    cmp_w = max(tag.width, max(1, int(w).bit_length()))
    tag_ext = tag if tag.width == cmp_w else tag._zext(width=cmp_w)
    tag_in_range = tag_ext.ult(c(w, width=cmp_w))

    # Variable shift using the low log2(width) bits, masked by tag_in_range.
    shamt_w = max(1, (w - 1).bit_length())
    shamt = tag
    if tag.width < shamt_w:
        shamt = tag._zext(width=shamt_w)
    elif tag.width > shamt_w:
        shamt = tag._trunc(width=shamt_w)

    shifted = shl_var(m, c(1, width=w), shamt)
    return tag_in_range._select_internal(shifted, c(0, width=w))


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
