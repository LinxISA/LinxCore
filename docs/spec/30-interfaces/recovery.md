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
`killedGroupCount` counts affected ROB groups, not killed members, and the
surviving tail names the exact ordered live member immediately before the
suffix when one exists.
Owner-local structures that coalesce multiple ROB members into one entry must
derive their exact local action from this compact suffix. In particular, a BROB
block whose first member survives while its recorded last member lies in the
suffix remains the same BID/generation and is shortened to the surviving tail
on matching Apply.
Box IOs reuse these types; no direct IEX- or LSU-to-IFU recovery-control path
exists.

Recovery control requests the ROB-authored plan through an explicit
Decoupled request/response. Targets receive the retained ROB plan in Prepare
phase until their individual prepare fires, return one matching prepared
transaction, and mutate only on the later common Apply broadcast. A matching
Abort broadcast terminates the retained transaction without owner mutation.
RecoveryControl also exposes a dedicated `robAbort: Valid[RecoveryPlan]`
terminal for the ROB request side. If abort arrives after `robPrepare.fire`
but before `robPrepared.fire`, RecoveryControl waits for the exact
ROB-authored response and then emits only this ROB abort terminal; it does not
broadcast a stale or default target plan. A ROB response is exact only by the
request fields: `RecoveryPlanContract.sameRobRequest` requires Prepare phase,
transaction ID, cause, full trigger identity, redirect PC, and new epoch to
match the retained seed request while deliberately ignoring the ROB-authored
survivor-tail and killed-suffix fields. Unsolicited responses before
`robPrepare.fire` are drainable stale Decoupled transactions and are not
accepted as the current plan, even if the held beat matches a later seed
request. Stale, unrelated, wrong-phase, or otherwise mismatched ROB responses
inside `RequestRob`, `WaitRob`, or `WaitRobAbort` are also Decoupled
transactions: RecoveryControl drains them when the response window is legal,
but they are not semantically accepted as the current plan. A same-cycle
mismatched response does not prevent the request from firing once. Canonical
ROB and BROB owner IOs
consume explicit matching `RecoveryPlan` Abort inputs and clear only the
retained transaction named by the shared transaction-equality helper. Stale,
duplicate, mismatched, or wrong-phase Apply and Abort packets are
non-mutating. Simultaneous matching Apply and Abort fail closed by taking the
non-mutating Abort path; owner mutation is gated off before any assertion or
diagnostic.
Abort during target preparation is a single terminal decision: if it coincides
with the final target acknowledgement barrier, Abort wins and Apply is not
registered. Once Apply is already visible to targets, the plan is committed and
RecoveryControl must not schedule a later Abort for that same plan.
An exception event wins global arbitration only when it carries a precise trap
payload; the `Exception` enum value alone does not outrank an older ordinary
event.

`RecoveryCandidateLookup` and `RecoveryCandidateStatus` are public ROB-facing
candidate arbitration payloads. RecoveryControl publishes retained producer
events as lookup candidates. ROB returns matching status with exact
eligible/rejected state, a globally comparable allocation-age token, and a
head-trap bit proving synchronous trap priority. RecoveryControl selects only
among matching eligible ROB statuses and deliberately discards matching
rejected candidates without blocking another eligible source.
All producer candidates admitted into one arbitration boundary remain live
until every active producer has an exact matching eligible or rejected status;
an interrupt candidate cannot bypass an unresolved producer. The allocation-age
token uses `RecoveryAge.tokenWidth(CoreParams)` and is compared with the
wrap-safe `RecoveryAge.older` relation. The token space must be more than twice
the maximum live ROB-member window so no live producer pair can be exactly
half-range ambiguous.

`RecoveryPlanContract` defines equality while ignoring only `phase`, exact
membership in the compact suffix, and legal empty/non-empty suffix shape.
Recovery targets MUST use this helper rather than private global-age
comparisons or partial transaction matching.
