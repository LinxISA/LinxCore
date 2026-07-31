# OOO to IEX interface

## Typed dispatch and completion {#IFC-OOO-IEX-001}
<!-- ndf: kind=req level=must layer=L1 status=stable since=0.1 depends-on=ARC-TOP-032,D-IDENTITY-001 -->

OOO shall dispatch renamed uops through class-specific channels, with CMD kept
independent from system and multicycle work. IEX shall return completions using
the complete ROB member identity carried by the dispatched uop.

## Dispatch and completion Bundles {#MEC-OOO-IEX-001}
<!-- ndf: kind=arch level=must layer=L2 status=stable since=0.1 refines=IFC-OOO-IEX-001 -->

`OOOIEXIO` groups ALU, BRU, AGU, STD, system, and CMD `DispatchTxn` channels
without flattening fixed lane names. `CompletionTxn` echoes `RobIdentity`;
backpressure never authorizes identity or payload mutation.
