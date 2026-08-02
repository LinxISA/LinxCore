# System, multicycle, and CMD issue decision

## Keep system/multicycle and CMD residency independent {#D-IEX-SYS-CMD-001}
<!-- ndf: kind=decision level=must layer=L1 status=stable since=0.1 affects=IFC-OOO-IEX-001,MEC-OOO-IEX-001,IFC-COMMIT-001,MEC-COMMIT-001,IFC-RECOVERY-001,IFC-TOP-EXT-001,MEC-TOP-EXT-001,VER-IFC-TOP-EXT-001 -->

**Context.** System operations, multicycle execution, and externally consumed
CMD operations have different completion and backpressure conditions. Treating
any of them as an immediate value or routing them to an always-ready sink would
lose precise side-effect ordering.

**Decision.** System and multicycle work share one resident queue; CMD has an
independent resident queue. Unsupported operations remain resident and fail
closed. OOO may issue one exact `RobNoflushTxn` only for the head member after
all input and legality checks complete without a pending trap, all older
effects are drained, and no recovery Prepare targets its STID. This transaction
authorizes one named side effect; it is not the scalar noflush window boundary
and is not a second resolve owner.

IEX presents those completed checks as one exact `RobNoflushReadyTxn`. Its
valid beat is the NFRDY proof and must match the per-STID resident ROB head in
transaction, instruction, and ROB identity. OOO drains stale proofs without
granting authorization, preserves a matching proof under backpressure, and
suppresses a fired member until that exact head leaves residency. Arbitration
or progress on another STID cannot clear or duplicate that suppression.

A no-destination system side effect uses `SystemIssueTxn`. The matching
noflush authorization, system issue, side-effect application, and no-value
`RobResolveTxn` form one atomic rendezvous. A CMD operation uses `CmdIssueTxn`
on the TOP external projection; the matching authorization, external issue,
and no-value resolve likewise fire atomically. A missing external sink applies
backpressure and cannot drop or auto-resolve CMD. Destination-producing system
operations fail closed until a response-bearing contract is specified.

Recovery Prepare takes priority over new authorization. Apply removes killed
resident rows and unused authorization; Abort preserves them. A side effect
whose atomic rendezvous has fired is exactly once and cannot be undone by a
younger recovery.

**Consequence.** System/multicycle and CMD capacity, wakeup, backpressure, and
verification remain independent even though both use ROB-authored precise
side-effect authorization.

**Supersession.** Any response-bearing system or CMD design must replace the
corresponding request, authorization, resolve, and recovery behavior together.
An always-ready compatibility sink is never a valid transition mechanism.
