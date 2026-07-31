# Recovery interface

## One precise recovery authority {#IFC-RECOVERY-001}
<!-- ndf: kind=req level=must layer=L1 status=stable since=0.1 depends-on=ARC-TOP-033,D-RECOVERY-001,D-IDENTITY-001 -->

IEX, LSU, and other detecting owners shall report typed recovery events to OOO.
OOO shall be the only producer of a global recovery plan. Every affected owner
shall prepare without mutation and shall mutate only on the matching apply
transaction routed by TOP.

## Recovery event and plan Bundles {#MEC-RECOVERY-001}
<!-- ndf: kind=arch level=must layer=L2 status=stable since=0.1 refines=IFC-RECOVERY-001 -->

`RecoveryEvent` names the exact trigger and proposed redirect.
`RecoveryPlan` carries one transaction ID, phase, cause, exact trigger,
surviving tail, redirect, new epoch, and one compact ROB-authored killed
suffix descriptor: `firstKilledValid`, `firstKilled`, `lastKilled`,
`killedGroupCount`, and `killedMemberCount`. The descriptor identifies the
unique contiguous live ROB suffix without emitting a capacity-sized vector.
Box IOs reuse these types; no direct IEX- or LSU-to-IFU recovery-control path
exists.

Recovery control requests the ROB-authored plan through an explicit
Decoupled request/response. Targets receive the retained ROB plan in Prepare
phase until their individual prepare fires, return one matching prepared
transaction, and mutate only on the later common Apply broadcast. A matching
Abort broadcast terminates the retained transaction without owner mutation.

`RecoveryPlanContract` defines equality while ignoring only `phase`, exact
membership in the compact suffix, and legal empty/non-empty suffix shape.
Recovery targets MUST use this helper rather than private global-age
comparisons or partial transaction matching.
