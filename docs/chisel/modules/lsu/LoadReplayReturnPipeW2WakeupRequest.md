# LoadReplayReturnPipeW2WakeupRequest

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2WakeupRequest.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/LoadReplayReturnPipeW2WakeupRequestSpec.scala`
- Integrated user: `rtl/LinxCore/chisel/src/main/scala/linxcore/top/LinxCoreFrontendFetchRfAluTraceTop.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/iex/pipe/lda_pipe.cpp`
    - `WakeupScalarLocalLinks`
    - `LDAPipe::runW2`
  - `model/LinxCoreModel/model/iex/pipe/agu_pipe.cpp`
    - `WakeupScalarLocalLinks`
    - `AGUPipe::runW2`
  - `model/LinxCoreModel/model/iex/iex.cpp`
    - `IEX::setMemWakeup`
  - `model/LinxCoreModel/model/iex/iex_iq.cpp`
    - `IssueQueue::WakeupIQTag`
- Related Chisel contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2SideEffectRequest.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2WakeupSinkReady.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2CompletionCandidate.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2SideEffectPayloadPlan.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2Slot.scala`
- Contract IDs: `LC-CHISEL-LSU-REPLAY-PIPE-W2-WAKEUP-REQUEST-001`

## Purpose

`LoadReplayReturnPipeW2WakeupRequest` names the post-completion W2
ready-table and issue-wakeup payload before live replay wakeups are allowed to
mutate readiness state. In LinxCoreModel, `LDAPipe::runW2` and
`AGUPipe::runW2` publish W2 resolve state, optionally produce RF writeback,
and call `WakeupScalarLocalLinks` for T/U scalar-local destinations. Returned
load memory wakeup also flows through `IEX::setMemWakeup`, which finds the ROB
instruction destination vector and fans each eligible destination through
`IssueQueue::WakeupIQTag`; that call then updates issue queues and the ready
table.

R342 keeps mutation dormant and preserves the destination split already exposed
by `LoadReplayReturnPipeW2CompletionCandidate`: GPR wakeups are identified as
the current reduced scalar subset, while T/U local-link wakeups are preserved
as non-GPR payloads for future routing instead of being rejected.

## Interface

| Direction | Signal | Description |
|---|---|---|
| input | `wakeupRequest` | Post-completion wakeup request from `LoadReplayReturnPipeW2SideEffectRequest.wakeupRequest`. |
| input | `slotOccupied` | W2 slot has a resident replayed-load entry. |
| input | `slotTargetIsAgu` / `slotTargetIsLda` | One-hot W2 target pipe class copied from the resident slot. |
| input | `slotPipeIndex` | W2 return-pipe index. |
| input | `slotBid` / `slotGid` / `slotRid` / `slotLoadLsId` | Resident ROB/load identity. BID/GID/RID must be valid for a legal request. |
| input | `slotPc` | Resident PC diagnostic for the future wakeup owner. |
| input | `slotDst` | Resident renamed destination sideband. |
| input | `slotWakeupRequired` | Resident sideband proving the row should publish wakeup. |
| output | `candidateValid` | Wakeup request and W2 slot are both present. |
| output | `targetValid` | Candidate has exactly one W2 target class. |
| output | `identityValid` | Candidate target has valid BID/GID/RID identity. |
| output | `wakeupRequired` | Candidate identity is valid and the resident row requires wakeup. |
| output | `destinationValid` | Required wakeup has a present destination. |
| output | `wakeupValid` | Candidate, target, identity, wakeup-required, and destination checks all pass. |
| output | `reducedGprWakeupValid` | Valid wakeup targets the reduced scalar GPR subset. |
| output | `nonGprWakeup` | Valid wakeup targets a non-GPR destination such as T/U local links. |
| output | `targetIsAgu` / `targetIsLda` / `targetPipeIndex` | Validated target pipe diagnostics. |
| output | `wakeupBid` / `wakeupGid` / `wakeupRid` / `wakeupLoadLsId` | Copied identity fields only when `wakeupValid` is true. |
| output | `wakeupPc` | Copied resident PC only when `wakeupValid` is true. |
| output | `wakeupKind` / `wakeupArchTag` / `wakeupRelTag` / `wakeupPhysTag` / `wakeupOldPhysTag` | Copied destination fields only when `wakeupValid` is true. |
| output | `blockedByNoRequest` | A W2 slot is resident but no post-completion wakeup request fired. |
| output | `blockedByNoSlot` | Wakeup request fired without a resident W2 slot. |
| output | `blockedByInvalidTarget` | Candidate has no target or both LDA and AGU targets. |
| output | `blockedByInvalidBid` / `blockedByInvalidGid` / `blockedByInvalidRid` | Candidate has an invalid required ROB identity field. |
| output | `blockedByInvalidIdentity` | Aggregate invalid BID/GID/RID diagnostic. |
| output | `blockedByWakeupNotRequired` | Candidate identity is legal but the resident row says wakeup is not required. |
| output | `blockedByNoDestination` | Required wakeup has no destination payload. |

## Logic Design

```text
candidateValid = wakeupRequest && slotOccupied
targetValid = slotTargetIsAgu ^ slotTargetIsLda
identityValid = slotBid.valid && slotGid.valid && slotRid.valid
hasDestination = slotDst.valid && slotDst.kind != None
wakeupRequired = candidateValid && targetValid && identityValid && slotWakeupRequired
wakeupValid = wakeupRequired && hasDestination
reducedGprWakeupValid = wakeupValid && slotDst.kind == Gpr
nonGprWakeup = wakeupValid && slotDst.kind != Gpr
```

When `wakeupValid` is false, copied identity and destination fields are driven
to disabled or zero values. `slotLoadLsId` is copied as replay diagnostic
context; the model wakeup owner ultimately fans out by ROB instruction
destination and operand type.

## Integration

`LinxCoreFrontendFetchRfAluTraceTop` wires this module behind R339:

- `wakeupRequest` comes from `LoadReplayReturnPipeW2SideEffectRequest`;
- all slot identity, target, destination, and wakeup-required inputs come from
  the R333 W2 slot;
- top-level diagnostics are exposed under
  `reducedLoadReplayLiqLretPipeW2WakeupRequest*`.

Because R336-R338 keep live W2 sinks disabled, R334 completion remains low,
R339 `wakeupRequest` remains low, and R342 request outputs remain dormant in
the current reduced fixture. No ready-table state, issue-queue entry, RF
state, ROB row, or W2 slot lifecycle state is mutated by this module.
R343 consumes `wakeupValid` as the wakeup payload bit in
`LoadReplayReturnPipeW2SideEffectPayloadPlan`; the plan remains observational
until future live W2 sinks are enabled.
R349 consumes `wakeupValid` plus the copied destination payload behind the R346
`wakeupFire` pulse in `LoadReplayReturnPipeW2WakeupFirePayload`; that boundary
is also observational until the ready-table and issue-wakeup owners are live.

## Deferred Owners

- Real ready-table mutation and issue-queue wakeup fanout.
- GPR versus scalar-local-link wakeup routing to the final issue fabrics.
- Load spec-wakeup LPV/load-init metadata.
- PE/core/thread routing fields from the full model wakeup path.
- Ordering and atomicity between resolve, RF writeback, wakeup, and W2 slot
  clear.

## Verification

Focused gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2WakeupRequest
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2SideEffectRequest
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2CompletionCandidate
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2WakeupSinkReady
bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop
FETCH_REDUCED_STORE_REPLAY_LIQ=1 BUILD_DIR=generated/r342-replay-pipe-w2-wakeup-request-xcheck bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh
```

Reference tests cover legal GPR payload copying, scalar local-link payload
preservation, dormant resident-slot behavior, request-without-slot diagnostics,
invalid target diagnostics, invalid identity diagnostics, wakeup-not-required
and no-destination blockers, and Chisel elaboration.
