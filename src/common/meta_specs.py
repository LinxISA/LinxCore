from __future__ import annotations

from pycircuit import Circuit, const, spec

from .module_specs import MODULE_SPECS
from .interfaces import INTERFACE_SPEC


@const
def stage_bundle_spec(m: Circuit, prefix: str, *, suffix: str = ""):
    _ = m
    p = str(prefix)
    sfx = str(suffix)
    if p not in INTERFACE_SPEC:
        raise ValueError(f"unknown interface prefix: {p!r}")
    b = spec.bundle(p)
    for f in INTERFACE_SPEC[p]:
        b.field(f"{f.name}{sfx}", width=int(f.width))
    return b.build()


@const
def stage_bundle_specs(m: Circuit, *, suffix: str = ""):
    _ = m
    out: dict[str, object] = {}
    for p in sorted(INTERFACE_SPEC.keys()):
        out[p] = stage_bundle_spec(m, p, suffix=suffix)
    return out


@const
def module_bundle_spec(m: Circuit, name: str):
    key = str(name).strip()
    if key not in MODULE_SPECS:
        raise ValueError(f"unknown module spec: {key!r}")
    return MODULE_SPECS[key](m)


@const
def module_bundle_specs(m: Circuit):
    out: dict[str, object] = {}
    for name in sorted(MODULE_SPECS.keys()):
        out[name] = MODULE_SPECS[name](m)
    return out
