# TOP external interface

## Backpressured external CMD issue {#IFC-TOP-EXT-001}
<!-- ndf: kind=req level=must layer=L1 status=stable since=0.1 depends-on=ARC-TOP-020,D-IDENTITY-001,IFC-OOO-IEX-001,IFC-COMMIT-001 -->

TOP shall expose CMD as an independently backpressured output. Each command
shall carry the exact instruction and ROB member identities, opcode, and source
operands retained by IEX. An absent or blocked command sink shall retain the
transaction without mutation and shall not authorize a resolve or external
side effect.

## External CMD Bundle {#MEC-TOP-EXT-001}
<!-- ndf: kind=arch level=must layer=L2 status=stable since=0.1 refines=IFC-TOP-EXT-001 -->

`TOPIO` projects one `Decoupled[CmdIssueTxn]` endpoint. Its `valid` and payload
remain stable until `fire`. The same fire participates in the matching
`RobNoflushTxn` consumption, external CMD acceptance, and no-value
`RobResolveTxn`; TOP contains no queue, retry owner, always-ready sink, or
independent completion path for this endpoint.

## Complete external TOP endpoint {#IFC-TOP-EXT-002}
<!-- ndf: kind=req level=must layer=L1 status=stable since=0.1 depends-on=IFC-TOP-EXT-001,IFC-DTU-001,IFC-MEMORY-001,PRM-LSU-SIZING-001 -->

`TOPIO` is the sole platform boundary for instruction memory, one data-memory
lane per LSU request lane, GPR bootstrap, synchronous interrupt, debug, system
and CMD issue, LSU authorization/maintenance, commit, trap, trace, counters,
and LSU health. The interrupt request is sampled in the core clock domain; a
platform with asynchronous interrupt pins MUST synchronize and qualify them
before presenting `InterruptRequest`. No asynchronous reset or raw clock pin
is part of `TOPIO`.

External memory requests use the typed `MemorySize` log2-byte encoding:
`Bytes1=0` through `Bytes64=6`. The 64-byte instruction/cache-line request is
therefore representable without truncation. Semantic 1/2/4/8-byte sizes inside
IEX and LSU remain separate from this external transport encoding.
