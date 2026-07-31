# Commit interface

## OOO-owned architectural commit {#IFC-COMMIT-001}
<!-- ndf: kind=req level=must layer=L1 status=stable since=0.1 depends-on=ARC-TOP-020,D-PREFIX-001,D-IDENTITY-001 -->

OOO shall publish architectural commit as a continuous oldest-first prefix.
Each committed entry shall carry its complete ROB and instruction identities,
architectural result, memory side effect summary, and precise trap state.
Commit-side release to rename, ROB, and BROB owners shall be acknowledged as
one retained transaction; no physical row or previous physical register may be
freed from an unacknowledged `Valid` pulse.

## Commit transaction Bundle {#MEC-COMMIT-001}
<!-- ndf: kind=arch level=must layer=L2 status=stable since=0.1 refines=IFC-COMMIT-001 -->

`CommitTxn` uses `count + Vec(retireWidth, CommitEntry)`. TOP and DTU observe
the same OOO-produced transaction; observation does not create a second commit
owner or an independent retirement decision.

`RenameCommitReleaseTxn` is the companion release payload for complete
destination history. It preserves every destination slot even when the public
`CommitEntry.destination` field carries only the primary projection for
architectural observation.
