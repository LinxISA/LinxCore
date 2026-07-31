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
`RecoveryPlan` carries one transaction ID, exact cutoff identity, redirect,
and prepare/apply phase. Box IOs reuse these types; no direct IEX- or
LSU-to-IFU recovery-control path exists.
