# Commit, trap, interrupt, and debug behavior

## Keep commit selection in OOO {#CMT-001}
<!-- ndf: kind=req level=must layer=L1 status=stable since=0.1 depends-on=IFC-COMMIT-001,ARC-TOP-020 -->

OOO MUST select one ordered commit prefix and coordinate ROB, rename, BROB,
and PC-buffer release on the same accepted transaction. TOP and DTU MUST only
route or observe the selected transaction. Trace readiness MUST NOT qualify
commit acceptance.

## Halt at a precise commit boundary {#CMT-002}
<!-- ndf: kind=req level=must layer=L1 status=stable since=0.1 depends-on=CMT-001,DTU-002 -->

OOO MUST retain an accepted halt request until the requested STID reaches a
precise commit boundary. A synchronous fault at that boundary MUST take
priority. When the debug action is accepted, OOO MUST report a typed Debug
trap, stop later commits, and return the matching debug response. Resume MUST
clear the OOO-owned halted state; DTU MUST own no copy of that state.

## Commit-control mechanism {#MEC-CMT-001}
<!-- ndf: kind=arch level=must layer=L2 status=stable since=0.1 refines=CMT-001,CMT-002 -->

`linxcore.ooo.CommitControl` owns commit/trap/interrupt/debug selection and the
halted bit. Its interrupt arbitration first qualifies requests against the ROB
boundary STID. `linxcore.dtu.DebugControl` only transports the typed request
and response.

## Commit-control verification {#VER-CMT-001}
<!-- ndf: kind=verif level=must layer=L3 status=stable since=0.1 verifies=CMT-001,CMT-002 -->

`CommitControlDebugSpec` MUST prove normal commit, synchronous-fault priority,
precise interrupt selection, halt at a commit boundary, and resume in one
bounded control harness. `RecoveryIntegrationSpec` MUST prove the same
contracts across the integration fixtures. `DTUSpec` MUST prove that DTU has
no commit or recovery authority.
