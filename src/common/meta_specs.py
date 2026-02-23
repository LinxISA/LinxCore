from __future__ import annotations

from pycircuit import Circuit, meta, template

from .interfaces import INTERFACE_SPEC


@template
def stage_bundle_spec(m: Circuit, prefix: str, *, suffix: str = ""):
    _ = m
    p = str(prefix)
    sfx = str(suffix)
    if p not in INTERFACE_SPEC:
        raise ValueError(f"unknown interface prefix: {p!r}")
    b = meta.bundle(p)
    for f in INTERFACE_SPEC[p]:
        b.field(f"{f.name}{sfx}", width=int(f.width))
    return b.build()


@template
def stage_bundle_specs(m: Circuit, *, suffix: str = ""):
    _ = m
    out: dict[str, object] = {}
    for p in sorted(INTERFACE_SPEC.keys()):
        out[p] = stage_bundle_spec(m, p, suffix=suffix)
    return out
