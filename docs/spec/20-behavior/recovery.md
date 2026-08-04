# Recovery behavior

## Select one precise recovery action {#REC-001}
<!-- ndf: kind=req level=must layer=L1 status=stable since=0.1 depends-on=IFC-RECOVERY-001,D-IDENTITY-001 -->

OOO MUST select the architectural recovery event. A synchronous head fault
MUST take priority over debug and interrupt requests. An interrupt request is
eligible only when its STID matches the precise ROB boundary; arbitration MUST
select the highest-priority eligible request.

## Apply after every required acknowledgement {#REC-002}
<!-- ndf: kind=req level=must layer=L1 status=stable since=0.1 depends-on=REC-001,IFC-RECOVERY-001 -->

The same typed `RecoveryPlan` MUST fan out in Prepare phase. Apply MUST remain
withheld until every required target has accepted Prepare and returned an
exact matching Prepared acknowledgement. A missing or stale acknowledgement
MUST NOT partially apply recovery.

## Keep cleanup local to the target STID {#REC-003}
<!-- ndf: kind=req level=must layer=L1 status=stable since=0.1 depends-on=REC-002 -->

IFU, CTU, OOO, IEX, and LSU MUST own only their local cleanup. Prepare MUST
fence the target STID without preventing unrelated-STID admission or forward
progress where the local storage and protocol permit it. Apply MUST prune only
the plan's exact killed set; Abort MUST release the fence without mutation.

## Recovery mechanism {#MEC-REC-001}
<!-- ndf: kind=arch level=must layer=L2 status=stable since=0.1 refines=REC-001,REC-002,REC-003 -->

`linxcore.ooo.RecoveryControl` retains candidate selection and the common
Prepare/Prepared/Apply barrier. TOP routes the typed target ports without
recomputing age or kill membership. Each public box compares the complete
transaction identity before acknowledging or applying its local action.

## Recovery verification {#VER-REC-001}
<!-- ndf: kind=verif level=must layer=L3 status=stable since=0.1 verifies=REC-001,REC-002,REC-003 -->

`CommitControlDebugSpec` MUST cover interrupt STID qualification and
synchronous-fault priority. `RecoveryControlBarrierSpec` MUST cover prepare
fanout, missing-acknowledgement stall, and one common apply.
`RecoveryIntegrationSpec` MUST cover the same contracts together with
unrelated-STID CTU progress.
