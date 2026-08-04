# DTU interface

## Debug and trace remain observational {#IFC-DTU-001}
<!-- ndf: kind=req level=must layer=L1 status=stable since=0.1 depends-on=ARC-TOP-022,D-IDENTITY-001 -->

DTU shall consume typed trace and commit observations and may issue typed debug
requests. DTU shall not own commit, trap, interrupt, cache, predictor, rename,
or recovery state.

## Trace and debug Bundles {#MEC-DTU-001}
<!-- ndf: kind=arch level=must layer=L2 status=stable since=0.1 refines=IFC-DTU-001 -->

`TraceEvent` retains source, event kind, instruction identity, ROB identity,
PC, opcode, and payload. `DTUIO` receives parameterized trace and commit
observations plus an external debug request, exports trace and performance
counters, and transports the debug request and matching response through a
typed owner-facing control channel. TOP routes that control channel to OOO.
