# Commit interface

## OOO-owned architectural commit {#IFC-COMMIT-001}
<!-- ndf: kind=req level=must layer=L1 status=stable since=0.1 depends-on=ARC-TOP-020,D-PREFIX-001,D-IDENTITY-001 -->

OOO shall publish architectural commit as a continuous oldest-first prefix.
Each committed entry shall carry its complete ROB and instruction identities,
architectural result, memory side effect summary, and precise trap state.
Commit-side release to rename, ROB, and BROB owners shall use side-effect-free
readiness followed by one common fire. The visible commit transaction, rename
release, ROB release, and BROB release are one retained boundary; no physical
row or previous physical register may be freed from a readiness check or from
an unacknowledged `Valid` pulse. A zero-lane precise trap or interrupt
boundary carries no ordinary release lanes and may fire once without requiring
ROB, rename, or BROB release readiness.

## Commit transaction Bundle {#MEC-COMMIT-001}
<!-- ndf: kind=arch level=must layer=L2 status=stable since=0.1 refines=IFC-COMMIT-001 -->

`CommitTxn` uses `count + Vec(retireWidth, CommitEntry)`. TOP and DTU observe
the same OOO-produced transaction; observation does not create a second commit
owner or an independent retirement decision.

`RenameCommitReleaseTxn` is the companion release payload for complete
destination history. It preserves every destination slot even when the public
`CommitEntry.destination` field carries only the primary projection for
architectural observation.

`CommitControlTxn` carries the public commit prefix plus companion rename,
ROB, and BROB release payloads. The owner-level ready signals are not
acknowledgements; they are exact prepare decisions used to decide whether the
single `CommitControl.out.fire` may authorize every release side effect. ROB
commit preview observation is side-effect-free; ROB retirement state advances
only on the matching common commit-apply pulse.
