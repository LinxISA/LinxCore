from __future__ import annotations

from pycircuit import Circuit, const, spec

meta = spec


@const
def backend_block_if_spec(m: Circuit):
    _ = m
    return (
        meta.bundle("backend_block_if")
        .field("bisq_enq_ready", width=1)
        .field("brob_active_allocated", width=1)
        .field("brob_active_ready", width=1)
        .field("brob_active_exception", width=1)
        .field("brob_active_retired", width=1)
        .field("template_uid", width=64)
        .build()
    )
