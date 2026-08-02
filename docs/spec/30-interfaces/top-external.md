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
