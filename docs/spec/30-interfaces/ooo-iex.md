# OOO to IEX interface

## Typed dispatch and resolve {#IFC-OOO-IEX-001}
<!-- ndf: kind=req level=must layer=L1 status=stable since=0.1 depends-on=ARC-TOP-032,D-IDENTITY-001 -->

OOO shall dispatch renamed uops through class-specific channels, with CMD kept
independent from system and multicycle work. IEX shall return resolves using
the complete ROB member identity carried by the dispatched uop. A system or CMD
side effect shall remain resident until its exact ROB noflush authorization and
consumer readiness participate in the same resolve rendezvous.

## Dispatch and resolve Bundles {#MEC-OOO-IEX-001}
<!-- ndf: kind=arch level=must layer=L2 status=stable since=0.1 refines=IFC-OOO-IEX-001 -->

`OOOIEXIO` groups ALU, BRU, AGU, STD, system, and CMD `DispatchTxn` channels
without flattening fixed lane names. `RobResolveTxn` echoes `RobIdentity`;
backpressure never authorizes identity or payload mutation. `RobNoflushTxn`
authorizes one exact head member and is not a retained queue owner.
`SystemIssueTxn` and the TOP-projected `CmdIssueTxn` remain separate
backpressured transactions.
