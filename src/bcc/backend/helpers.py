from __future__ import annotations

from pycircuit import Circuit, Reg, Wire, function, u


@function
def mux_by_uindex(m: Circuit, *, idx: Wire, items: list[Wire | Reg], default: Wire) -> Wire:
    """Return items[idx] (idx is an integer code), using a balanced mux tree.

    This is depth-critical for LinxCore: a linear chain multiplies through
    nested use sites and trips pycc's logic-depth gate.
    """

    n = int(len(items))
    if n <= 0:
        return default

    # Normalize to wires.
    vals: list[Wire] = []
    for it in items:
        vals.append(it if isinstance(it, Wire) else it.out())

    # Pad to a power-of-two so we can build a bitwise mux tree.
    p2 = 1 << (n - 1).bit_length()
    for _ in range(p2 - n):
        vals.append(default)

    # Tree select using low bits first (preserves standard little-endian index).
    stage: list[Wire] = vals
    stages = int(p2.bit_length() - 1)
    for bit in range(stages):
        nxt: list[Wire] = []
        for j in range(0, len(stage), 2):
            a = stage[j]
            b = stage[j + 1]
            # IMPORTANT: this helper is used from both JIT-compiled and
            # tracing-style designs. Avoid Python `if` / ternary on i1 Wires
            # (would call Wire.__bool__); use mux primitive instead.
            nxt.append(idx[bit]._select_internal(b, a))
        stage = nxt

    # NOTE: most LinxCore index domains are power-of-two and fully cover idx's
    # representable range, so we intentionally omit an out-of-range guard here.
    return stage[0]


@function
def mask_bit(m: Circuit, *, mask: Wire, idx: Wire, width: int) -> Wire:
    """Return mask[idx] as i1.

    Keep this helper tracing-style friendly: avoid Python boolean control flow
    on i1 Wires (would call Wire.__bool__). Prefer a compact representation
    (dynamic shift + compare) to avoid generating a giant mux network in the
    frontend IR and emitted C++.
    """
    n = int(width)
    if n <= 0:
        return u(1, 0)
    mask_n = mask if mask.width == n else mask[0:n]
    zero_n = mask_n & 0
    # onehot = 1 << idx (out-of-range shift yields 0 => default 0).
    onehot = (zero_n | u(n, 1)).shl(amount=idx)
    return (mask_n & onehot) != zero_n


@function
def onehot_from_tag(m: Circuit, *, tag: Wire, width: int, tag_width: int) -> Wire:
    """Return a onehot vector with bit `tag` set.

    Keep this compact to avoid exploding IR and emitted C++ in hot stages
    (rename/commit/engine). Out-of-range shifts produce 0, matching the
    previous compare-and-select implementation.
    """
    n = int(width)
    if n <= 0:
        return u(0, 0)
    # NOTE: tag_width is kept for call-site stability; shift amount uses `tag`.
    # Anchor the literal to a Wire so `.shl(...)` is available.
    base = (tag & 0) | u(n, 1)
    out = base.shl(amount=tag)
    return out if out.width == n else out[0:n]


@function
def alloc_from_free_mask(m: Circuit, *, free_mask: Wire, width: int, tag_width: int) -> tuple[Wire, Wire, Wire]:
    """Priority-encode the lowest free bit in `free_mask`."""
    valid = u(1, 0)
    tag = u(tag_width, 0)
    onehot = u(width, 0)
    for i in range(int(width)):
        bit = free_mask[i]
        take = bit & (~valid)
        valid = take._select_internal(u(1, 1), valid)
        tag = take._select_internal(u(tag_width, i), tag)
        onehot = take._select_internal(u(width, 1 << i), onehot)
    return valid, tag, onehot
