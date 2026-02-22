from __future__ import annotations

from dataclasses import dataclass

from pycircuit import Circuit, Wire, function


@dataclass(frozen=True)
class Consts:
    one1: Wire
    zero1: Wire
    zero3: Wire
    zero4: Wire
    zero6: Wire
    zero8: Wire
    zero32: Wire
    zero64: Wire
    one64: Wire


@function
def make_consts(m: Circuit) -> Consts:
    c = m.const
    return Consts(
        one1=c(1, width=1),
        zero1=c(0, width=1),
        zero3=c(0, width=3),
        zero4=c(0, width=4),
        zero6=c(0, width=6),
        zero8=c(0, width=8),
        zero32=c(0, width=32),
        zero64=c(0, width=64),
        one64=c(1, width=64),
    )

@function
def masked_eq(m: Circuit, x: Wire, *, mask: int, match: int) -> Wire:
    _ = m
    return (x & int(mask)).__eq__(int(match))


@function
def shl_var(m: Circuit, value: Wire, shamt: Wire) -> Wire:
    """Variable shift-left by `shamt` (uses low 6 bits)."""
    _ = m
    s = shamt._trunc(width=6)
    out = value
    out = s[0]._select_internal(out.shl(amount=1), out)
    out = s[1]._select_internal(out.shl(amount=2), out)
    out = s[2]._select_internal(out.shl(amount=4), out)
    out = s[3]._select_internal(out.shl(amount=8), out)
    out = s[4]._select_internal(out.shl(amount=16), out)
    out = s[5]._select_internal(out.shl(amount=32), out)
    return out


@function
def lshr_var(m: Circuit, value: Wire, shamt: Wire) -> Wire:
    """Variable logical shift-right by `shamt` (uses low 6 bits)."""
    _ = m
    s = shamt._trunc(width=6)
    out = value
    out = s[0]._select_internal(out.lshr(amount=1), out)
    out = s[1]._select_internal(out.lshr(amount=2), out)
    out = s[2]._select_internal(out.lshr(amount=4), out)
    out = s[3]._select_internal(out.lshr(amount=8), out)
    out = s[4]._select_internal(out.lshr(amount=16), out)
    out = s[5]._select_internal(out.lshr(amount=32), out)
    return out


@function
def ashr_var(m: Circuit, value: Wire, shamt: Wire) -> Wire:
    """Variable arithmetic shift-right by `shamt` (uses low 6 bits)."""
    _ = m
    s = shamt._trunc(width=6)
    out = value.as_signed()
    out = s[0]._select_internal(out.ashr(amount=1), out)
    out = s[1]._select_internal(out.ashr(amount=2), out)
    out = s[2]._select_internal(out.ashr(amount=4), out)
    out = s[3]._select_internal(out.ashr(amount=8), out)
    out = s[4]._select_internal(out.ashr(amount=16), out)
    out = s[5]._select_internal(out.ashr(amount=32), out)
    return out
