# OOO to IEX interface

## Typed dispatch and resolve {#IFC-OOO-IEX-001}
<!-- ndf: kind=req level=must layer=L1 status=stable since=0.1 depends-on=ARC-TOP-032,D-IDENTITY-001 -->

OOO shall dispatch renamed uops through class-specific channels, with CMD kept
independent from system and multicycle work. IEX shall return resolves using
the complete ROB member identity carried by the dispatched uop. A system or CMD
side effect shall remain resident until its exact ROB noflush authorization and
consumer readiness participate in the same resolve rendezvous.

Every dispatch, resolve, trace, and commit packet is a continuous `[0, count)`
prefix. A lane outside the prefix is invalid and cannot carry an independent
side effect. Terminal queues retain each accepted prefix until all coupled
consumers fire.

## Dispatch and resolve Bundles {#MEC-OOO-IEX-001}
<!-- ndf: kind=arch level=must layer=L2 status=stable since=0.1 refines=IFC-OOO-IEX-001 -->

`OOOIEXIO` groups ALU, BRU, AGU, STD, system, and CMD `DispatchTxn` channels
without flattening fixed lane names. `RobResolveTxn` echoes `RobIdentity`;
backpressure never authorizes identity or payload mutation. `RobNoflushTxn`
authorizes one exact head member and is not a retained queue owner.
`RobNoflushReadyTxn` is the exact IEX-to-OOO NFRDY proof for the same member;
its `valid` means operand and legality checks are complete without a local
trap and the authoritative side-effect owners report every older effect
drained. OOO accepts a matching proof only while the same unresolved member is
the oldest unresolved row behind a fully completed, trap-free per-STID ROB
prefix. This permits a younger System/CMD row in the same atomic BROB block to
complete without requiring an impossible partial block retirement. A stale or
mismatched proof is drained without
authorization, while a matching proof and authorization remain one retained
ready/valid rendezvous. Recovery fencing holds `ready` low for an exact,
unaccepted proof; Abort therefore resumes the same proof instead of requiring
the producer to regenerate it.
`SystemIssueTxn` is forwarded losslessly through public `OOOIO` to its TOP
side-effect owner. The TOP-projected `CmdIssueTxn` remains a separate public
IEX transaction. Neither endpoint may be tied ready or silently drained.

`DispatchTxn.trap` carries the precise decode-trap intent owned by OOO from
the canonical D3 lane into IEX. Its validity and cause are immutable parts of
the dispatch payload and therefore remain stable under backpressure. A
`StoreDispatchTxn` copies the same intent into both its STA and STD members;
the atomic store beat cannot expose one member with a missing or different
trap intent. IEX may transport and resolve this intent but does not create a
second decode-trap owner.

`DispatchTxn.pcBufferIndexOffset` carries the exact `pcBufferIndex`、
`pcOffset` and `allocationEpoch` prepared by the OOO PC buffer on the common
D3 publication edge. IEX retains those fields in the IQ. A uop that needs its
PC presents `PcBufferReadAddress` during I1; OOO returns the exact PC base
through the readyless `pcBufferReadPcBase` vector, and IEX adds the retained PC
offset in I2. A stale PC buffer index/allocation epoch produces no valid PC
base and must repick without execution. The complete PC is not copied through
the IQ and IEX owns no PC buffer rows.

`DispatchTxn.memoryOrder` is stable logical order metadata. `requestCount`
states the number of memory requests represented by the uop; `firstLsid`,
`firstLid`, and `firstSid` are respectively the first full memory-order, load,
and store serials assigned to that uop. `YOST` identifies the youngest older
store by full LSID and SID, while `YOLD` identifies the youngest older load by
full LSID and LID. Invalid `YOST` or `YOLD` fields are canonical zeroes. These
fields are program-order identities only: they never contain a dispatch
transaction, IEX memory transaction, load-attempt generation, pipe route, or
physical LIQ/STQ row.
