# DTU behavior

## Observe without controlling retirement {#DTU-001}
<!-- ndf: kind=req level=must layer=L1 status=stable since=0.1 depends-on=IFC-DTU-001,IFC-COMMIT-001 -->

DTU MUST accept commit and trace observations without owning ROB, commit,
trap, interrupt, or recovery state. A stalled external trace consumer MUST NOT
backpressure commit or any observed box. DTU MAY drop a newly observed trace
packet while an older exported packet is stalled, but the retained packet MUST
remain stable until accepted.

## Request debug control through OOO {#DTU-002}
<!-- ndf: kind=req level=must layer=L1 status=stable since=0.1 depends-on=IFC-DTU-001,CMT-002 -->

DTU MUST retain an accepted debug request until the OOO control owner accepts
it. DTU MUST NOT choose a halt boundary, synthesize a recovery plan, or mutate
architectural state. The response returned by OOO MUST preserve the request
transaction identity.

## Count observations only {#DTU-003}
<!-- ndf: kind=req level=must layer=L1 status=stable since=0.1 depends-on=DTU-001 -->

Performance counters MUST be monotonic observations. Counter values MUST NOT
qualify commit, recovery, interrupt, debug, or trace readiness.

## DTU mechanism {#MEC-DTU-002}
<!-- ndf: kind=arch level=must layer=L2 status=stable since=0.1 refines=DTU-001,DTU-002,DTU-003 -->

`linxcore.dtu.DTU` composes `DebugControl`, `TraceExport`, and
`PerformanceCounters`. `DebugControl` retains request transport only.
`TraceExport` owns one loss-tolerant output slot and keeps its observation
input ready. `PerformanceCounters` records accepted trace packets, dropped
trace packets, observed commit transactions and instructions, and accepted
debug requests.

## DTU verification {#VER-DTU-001}
<!-- ndf: kind=verif level=must layer=L3 status=stable since=0.1 verifies=DTU-001,DTU-002,DTU-003 -->

`DTUSpec` MUST cover stalled trace export, drop accounting, commit observation,
retained halt/resume request transport, and structural absence of recovery and
commit-control ownership inside DTU. `DTUActivationTraceSpec` MUST stall the
external trace consumer while proving live IEX terminal publication and commit
progress, accepted and dropped DTU observations, and stability of the retained
export packet.
