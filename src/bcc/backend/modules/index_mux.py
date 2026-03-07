from __future__ import annotations

from pycircuit import Circuit, function

from ..helpers import mux_by_uindex


@function
def _fit_width(m: Circuit, wire, *, width: int):
    if wire.width == width:
        return wire
    if wire.width < width:
        return wire._zext(width=width)
    return wire._trunc(width=width)


@function
def banked_mux_by_uindex(
    m: Circuit,
    *,
    idx,
    items,
    default,
    bank_depth: int = 16,
):
    depth = len(items)
    if depth <= 0:
        raise ValueError("items must be non-empty")
    if bank_depth <= 1:
        raise ValueError("bank_depth must be > 1")
    if depth <= bank_depth:
        return mux_by_uindex(m, idx=idx, items=items, default=default)

    c = m.const
    leaf_depth = min(int(bank_depth), depth)
    leaf_idx_w = max(1, (leaf_depth - 1).bit_length())
    local_idx = _fit_width(m, idx, width=leaf_idx_w)

    bank_count = (depth + leaf_depth - 1) // leaf_depth
    bank_idx_w = max(1, (bank_count - 1).bit_length())
    if idx.width > leaf_idx_w:
        bank_idx = _fit_width(m, idx[leaf_idx_w:idx.width], width=bank_idx_w)
    else:
        bank_idx = c(0, width=bank_idx_w)

    bank_outs = []
    for bank in range(bank_count):
        bank_items = []
        for lane in range(leaf_depth):
            item_idx = bank * leaf_depth + lane
            bank_items.append(items[item_idx] if item_idx < depth else default)
        bank_outs.append(mux_by_uindex(m, idx=local_idx, items=bank_items, default=default))

    return mux_by_uindex(m, idx=bank_idx, items=bank_outs, default=default)
