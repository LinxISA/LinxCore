# IEX to ROB resolve decision

## Resolve one ROB member per transaction {#D-IEX-ROB-RESOLVE-001}
<!-- ndf: kind=decision level=must layer=L1 status=stable since=0.1 affects=IFC-OOO-IEX-001,MEC-OOO-IEX-001,IFC-COMMIT-001,MEC-COMMIT-001 -->

**Context.** One decoded instruction may create multiple uops and multiple
P/T/U destinations while still occupying one ROB member. ROB needs a precise
resolved/trap state transition, but it must not become a second physical
register-file or wakeup-data owner.

**Decision.** `RobResolveTxn` names exactly one complete ROB member identity.
An ordinary terminal rendezvous resolves that member once. Destination ordinal
zero may be projected as `destinationIndex/value` for architectural
observation, while every valid P/T/U destination writes and wakes atomically
on the same terminal fire. A no-destination uop resolves with canonical-zero
destination fields. A precise trap resolves once with exact trap metadata and
without register-file write or wakeup. An early-resolved member is marked at
ROB admission and emits no later terminal resolve. A recovery-killed member
emits no resolve.

**Consequence.** ROB stores resolved and trap state, not a multi-destination
value array. Backpressure retains the full resolve, register-file write,
wakeup, trace, and recovery rendezvous; none of those effects may occur from a
readiness check or a partial fire.

**Supersession.** A later resolve-shape decision must replace the ROB consumer,
terminal producer, register-file writes, and wakeups in one cutover. Parallel
completion and resolve protocols are forbidden.
