# LinxCore contract scope

## One maintained core chain {#ARC-TOP-001}
<!-- ndf: kind=req level=must layer=L1 status=stable since=0.1 -->

The maintained Chisel core MUST have one architectural path:
`TOP -> IFU -> CTU -> OOO -> IEX -> LSU`, with DTU observing the path through
explicit trace and debug interfaces. TOP is wiring and external adaptation,
not a second owner of pipeline state.

## Linx semantics are authoritative {#ARC-TOP-002}
<!-- ndf: kind=req level=must layer=L1 status=stable since=0.1 -->

The Linx ISA manual, approved LinxCore decisions, and stable clauses in this
tree MUST define architectural behavior. External sources may contribute
mechanisms and documentation structure, but cannot introduce another ISA's
register, exception, memory, or instruction semantics.

## Replacement evidence precedes deletion {#ARC-TOP-003}
<!-- ndf: kind=req level=must layer=L1 status=stable since=0.1 -->

An old state-owning implementation MUST remain available until its replacement
passes the required unit, adjacent integration, generated-RTL, and natural
workload gates. After equivalent evidence exists, the old owner is removed
rather than retained as a second selectable chain.
