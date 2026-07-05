# ReducedLoadReplayLiqAllocPath

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/ReducedLoadReplayLiqAllocPath.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/ReducedLoadReplayLiqAllocPathSpec.scala`,
  `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/ReducedStoreWaitReplayToLiqPathSpec.scala`,
  `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/ReducedStoreWaitReplayChiselPathSpec.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/lsu/load_unit/ldq.cpp`
    - `LDQInfo::insert`
    - `LDQInfo::updateWaitInfo`
    - `LDQInfo::handleSUWakeup`
  - `model/LinxCoreModel/model/lsu/store_unit/stq.cpp`
    - `STQ::lookupForLoad`
- Related Chisel contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/ReducedLoadReplayLiqAllocAdapter.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadInflightQueue.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadInflightLaunchSelect.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/ReducedStoreWaitReplayChiselPathProbe.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadInflightRowMutationRequestBridge.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnConsumerReady.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadResolveQueue.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/ReducedLoadReplayRelaunchQueue.scala`
- Contract IDs: `LC-CHISEL-LSU-LIQ-003`

## Purpose

`ReducedLoadReplayLiqAllocPath` is the composition boundary between queued
reduced replay candidates and registered LIQ row state. It instantiates
`ReducedLoadReplayLiqAllocAdapter`, `LoadInflightQueue`, and the R280
`LoadInflightLaunchSelect` selector. It still uses the LIQ allocation handshake
as the only authority for consuming the replay queue head, and R311 carries the
candidate's compact replay destination sideband through allocation, residency,
selected-row diagnostics, and the LRET-payload diagnostic boundary. R375
carries the candidate's RF-derived source-trace sideband through the same
allocation, residency, and selected-row diagnostic path while keeping the W2
commit-row source provider disabled.

This module is intentionally narrower than full replay. It does not drive the
LIQ launch port unless `launchEnable` is explicitly asserted by the parent, and
the reduced top keeps that input disabled. It also does not arbitrate new loads
against replay loads or wake dependent consumers. Its job is to prove that the
candidate payload can become normal LIQ residency, expose when resident rows
satisfy the model-derived scalar replay-pick predicate, and provide the
path-local launch/E4/LHQ diagnostic boundary needed before the top enables live
replay. R285 gives the path-local `lhqRecord` output a top-level
`LoadResolveQueue` storage consumer, and R286 has the top feed back a delayed
`clearResolved` request once that consumer accepts the record. Retire and live
MDB publication remain outside this module.

R417 replaces the R416 tie-off with a source-shaped row-mutation bridge. The
path accepts the R410
`LoadReplaySourceReturnStoreSnapshotRowMutationRequest` payload shape, uses
`LoadInflightRowMutationRequestBridge` to translate the source wait-store ID
width into the LIQ-native `storeEntries` width, and drives the child
`LoadInflightQueue` native row-mutation port. R445 lets the current reduced top
drive the source path's guarded `rowMutationLiveEnable` true, so the bridge and
native writer can now accept source-shaped local-STQ response mutations after
the source head-proof and LIQ write-control guards pass. R428 forwards the
bridge, native write-control, and aggregate row-mutation blocker diagnostics
through the reduced top IO so
future live-mutation packets can be compared at the wrapper boundary before
changing the enable policy. R429 also forwards the child LIQ write-control
blocker reasons, making the model-required valid/Repick/SCB-return and
same-row writer-conflict prerequisites visible without changing the disabled
live arm.

R418 splits the E2 source-return inputs passed into `LoadInflightQueue`.
`e2ScbReturned` now carries only the SCB source bit, while `e2StqReturned`
carries the local STQ/store-source bit from `LoadReplaySourceReturnReadiness`.
The downstream `LoadForwardPipeline` still requires load-data, SCB, and
STQ/store source bits before asserting the combined `sourcesReturned` row
state. R453 exposes the child LIQ refill wakeup input at this composition
boundary so a generated-RTL fixture can prove allocated replay rows become
launchable through the existing row-owned refill path; the live reduced top
ties this input inactive. R464 exposes the child LIQ replay-wakeup input and
completion masks at the same boundary; the live reduced top also ties this
input inactive, while the generated-RTL wait/replay probe uses it to supply
SCB source-return evidence through the native `LoadReplayWakeup` owner before
an MDB row mutation writes. R449 adds an end-to-end reference-owner test for the
pre-launch half of
the path. It proves that a relaunch candidate generated by the wait-store owner
can be queued and allocated into LIQ residency with the original load identity
and forwarding snapshot intact. It is not a live top replay proof; the current
live architectural store/load probe still produces zero wait/replay counters.

R487 forwards the child LIQ `scbReturn*` proof port. This gives the top-level
source-return accepted-token owner a narrow way to record model
`handleSCBReceive` ordering evidence on a selected `Repick` row without
driving the broader replay-wakeup byte-merge path.

R489 keeps the same pass-through clear interface but records the widened child
LIQ contract: `clearResolvedValid` may now clear either an explicit
`Resolved` row or a complete source-returned `Repick` row selected by the
parent replay-return lifecycle path.

## Interface

### Inputs

| Signal | Description |
|---|---|
| `flush` | Flushes the internal LIQ and suppresses candidate consumption. |
| `candidateValid` | Queue-head valid from `ReducedLoadReplayRelaunchQueue`. |
| `candidate` | Queued reduced replay candidate with load identity, destination, source traces, return signedness, and forwarding snapshot. |
| `launchEnable` | Parent-owned arm bit. When low, selector diagnostics remain visible but `LoadInflightQueue.launchValid` is not driven. |
| `pickValid` / `pickIndex` | Parent-owned R486 pick-only request. It marks the selected LIQ row `Repick` without running `LoadForwardPipeline`. |
| `scbReturnValid` / `scbReturnIndex` | Parent-owned R487 SCB-return proof request. It marks a selected `Repick` row `scbReturned` without merging data. |
| `e2Stores` | Abstract STQ forwarding rows for the `LoadForwardPipeline` launch path. R283 top wiring feeds a `ResidentStoreForwardStoreSnapshot` vector while keeping launch disabled. |
| `e2BaseData` / `e2BaseValidMask` | Baseline line data and valid bytes for relaunches that do not already have row-owned data. R295 top wiring shapes the selected row's scalar sparse-memory response through `LoadReplayBaseDataAlign` while keeping launch disabled; R296 gates those inputs with `LoadLookupArbiter.replayGranted` so execute-returned sparse-memory bytes cannot feed the replay row. R297 routes the same grant-qualified data-return predicate through `LoadReplayLaunchReadiness`. |
| `e2LoadDataReturned` / `e2ScbReturned` / `e2StqReturned` / `e2ReturnReady` | Source-return and return-slot readiness sidebands consumed by `LoadForwardPipeline`. R418 drives `e2ScbReturned` from `LoadReplaySourceReturnReadiness.scbSourceReturned` and `e2StqReturned` from `storeSourceReturned`. R305 drives `e2ReturnReady` from `LoadReplayReturnReadiness` fed by the consumer-ready, budget, permit, and select split; the reduced top arms the budget but keeps the downstream LRET and mem-wakeup sinks low. |
| `replayWakeValid` / `replayWake` | Pass-through store-unit/SCB replay wakeup to the child `LoadInflightQueue`. The live reduced top ties this inactive; the generated-RTL wait/replay probe drives it to mark a live target row source-returned before an MDB wait mutation. |
| `clearResolvedValid` | Pass-through clear request for future tests or consumers that resolve rows. Since R489, the child LIQ also accepts a complete source-returned `Repick` row on this path. |
| `clearResolvedIndex` | LIQ slot for `clearResolvedValid`. |
| `rowMutationRequestValid` / `rowMutationRequestTargetMask` / `rowMutationRequestTargetIndex` | Source-shaped R410 row-mutation request validity and LIQ target. Current top drives these from `LoadReplaySourceReturnStoreSnapshotPath`, whose live arm remains false. |
| `rowMutationSetWaitStatus` / `rowMutationKeepRepickStatus` / `rowMutationClearReturnState` | Future status and split-return-state writes for wait-store rewait or continued repick. |
| `rowMutationLineWrite` / `rowMutationWaitStoreWrite` | Future row-data and wait-store-state write enables. |
| `rowMutationNextWaitStore*` | Source-shaped wait-store payload. The bridge converts `rowMutationNextWaitStoreInfo` from source ID width to native `storeEntries` width. |
| `rowMutationNextLineData` / `rowMutationNextValidMask` / `rowMutationNextDataComplete` | Future row data image from the source snapshot row-state plan. |
| `rowMutationNextScbReturned` / `rowMutationNextStqReturned` / `rowMutationNextStoreSourceReturned` | Future split source-return state from the source snapshot row-state plan. |
| `refillValid` / `refill` | Pass-through read-refill wakeup to the child `LoadInflightQueue`. The live reduced top ties this inactive; the generated-RTL wait/replay probe drives it to make an allocated replay row launchable through row-owned data. |

### Outputs

| Signal | Description |
|---|---|
| `candidateConsumeReady` | Connects to replay-queue `outReady`; high only when LIQ allocation accepts. |
| `candidateUsable` | The queue head is valid and not masked by flush. |
| `candidateBlockedByAlloc` | Queue head is usable but the LIQ allocation pointer is not free. |
| `allocValid` | Adapter-presented allocation request. |
| `allocReady` | LIQ allocation pointer is free and flush is inactive. |
| `allocAccepted` | Candidate allocated into LIQ row state this cycle. |
| `allocIndex` | LIQ allocation slot. |
| `allocLoadId` | Slot-plus-wrap `LID` assigned by `LoadInflightQueue`. |
| `rows` | Current LIQ row image. |
| `occupiedMask` | Resident LIQ rows. |
| `waitMask` | Rows in `Wait`. |
| `waitStoreMask` | Rows waiting on a store. This path allocates replay-ready rows, so newly allocated rows do not set it. |
| `launchWaitMask` | `LoadInflightLaunchSelect` view of valid `Wait` rows. |
| `launchWaitStoreBlockedMask` | `Wait` rows still blocked by wait-store state. |
| `launchTileBlockedMask` | `Wait` rows suppressed because this selector is scalar-only. |
| `launchUnblockedWaitMask` | Scalar `Wait` rows with no wait-store block. |
| `launchRequestCompleteMask` | Unblocked rows whose row-owned valid bytes cover the requested load bytes. |
| `launchDataHitMask` | Unblocked rows with `l1Hit`, `storeBypass`, or complete requested row-owned bytes. |
| `launchCandidateMask` | Enabled `launchDataHitMask` candidates. |
| `launchMask` | One-hot oldest selected scalar candidate. Diagnostic only in this path. |
| `launchValid` / `launchIndex` | Selector request before parent `launchEnable` qualification. |
| `launchCandidateCount` | Number of selector candidates. |
| `launchSelected*` | R294/R305/R307/R311/R375 selected launch-row identity from `LoadInflightLaunchSelect`: LIQ load ID, BID/GID/RID, load LSID, PC, address, size, replay-return signedness, replay destination, source trace, 64-byte request mask, `specWakeup`, and `stackValid`. These signals remain diagnostic while `launchEnable` is low. |
| `launchDriveValid` | Actual valid presented to `LoadInflightQueue.launchValid`; low unless the parent-owned `launchEnable && launchValid` predicate is true. Since R305, the reduced top drives return readiness from `LoadReplayReturnReadiness` fed by `LoadReplayReturnConsumerReady` plus the budget/permit/select split; the LRET and mem-wakeup sinks remain low, so launch stays disabled. |
| `launchReady` | Selected row is launch-ready in `LoadInflightQueue`. |
| `launchAccepted` | Selected row entered `Repick` through `LoadInflightQueue`. |
| `pickReady` / `pickAccepted` | Child LIQ response for the R486 pick-only path. |
| `scbReturnReady` / `scbReturnAccepted` | Child LIQ response for the R487 SCB-return proof path. |
| `repickMask` / `missMask` / `resolvedMask` | Pass-through LIQ state masks after any enabled relaunches. |
| `e4UpdateValid` / `e4UpdateIndex` / `e4MissKind` / `e4WakeupValid` | Pass-through E4 outcome diagnostics from the launched-row pipeline. |
| `lhqRecordValid` / `lhqRecord` | Path-local resolved-load hit record, including load PC after R284. R285 top wiring can append this row to `LoadResolveQueue`; R286 top wiring clears the resolved LIQ row after that append is accepted. This module itself does not own ResolveQ backpressure, retire, or MDB publication. |
| `residentCount` | Resident LIQ row count. |
| `empty` / `full` | LIQ occupancy diagnostics. |
| `missPending` | Pass-through LIQ miss-pending diagnostic. |
| `clearResolvedAccepted` | Pass-through `LoadInflightQueue` clear response. |
| `rowMutationBridgeValid` / `rowMutationSourceStoreIndexFits` | R417 bridge admission diagnostics before the native LIQ row-mutation port. |
| `rowMutationInvalid*` | Bridge/payload invalid diagnostics for out-of-range source store index, conflicting status writes, wait-store payload without wait status, and inconsistent split-return state. |
| `rowMutationWriteEnable` / `rowMutationApplyValid` / `rowMutationTargetEvidenceValid` / `rowMutationWriteConflict` | Native `LoadInflightQueue` row-mutation storage diagnostics from the R416 row owner. |
| `rowMutationBlockedByBridge` / `rowMutationBlockedByControl` | Request blocked before native shape conversion or by the LIQ row-mutation write-control policy. |
| `rowMutationControlBlockedBy*` | R429 detailed native write-control blocker reasons from `LoadInflightQueue`: invalid target row, not-Repick state, missing SCB return, E4 update conflict, clear-resolved conflict, replay-wakeup conflict, refill conflict, launch conflict, and allocation conflict. |
| `replayWakeWaitStoreClearMask` / `replayWakeMergeMask` / `replayWakeCompletedMask` | Child LIQ replay-wakeup diagnostics passed through for generated-RTL evidence. |
| `refillAccepted` / `refillWakeMask` | Child LIQ refill-wakeup diagnostics. |

## State

The composition owns no state beyond its child `LoadInflightQueue`. The replay
queue remains the pending-candidate owner before `candidateConsumeReady`, and
`LoadInflightQueue` owns row residency after `allocAccepted`.

## Logic Design

The C++ model creates load rows in `LDQInfo::insert`, records wait-store
state through `LDQInfo::updateWaitInfo` / `waitStore`, and later clears
matching store waits in `LDQInfo::handleSUWakeup`. Once wait-store state is
cleared, the row returns to the normal wait/relaunch path. In Chisel, the
reduced wait slot and replay queue produce the same cleared load as a
`ReducedLoadReplayCandidate`.

`ReducedLoadReplayLiqAllocPath` maps that candidate into real LIQ residency:

1. `ReducedLoadReplayLiqAllocAdapter` converts the candidate into
   `LoadInflightAlloc`.
2. `LoadInflightQueue.allocReady` backpressures the adapter when the allocation
   pointer names a resident row.
3. `candidateConsumeReady` fires only with `allocAccepted`, transferring
   ownership from the replay queue into LIQ row state.
4. The allocated row stays in `Wait` with row-owned `loadLsId` plus the
   forwarding snapshot `(youngestStoreId, youngestStoreLsId)`.
5. `LoadInflightLaunchSelect` scans the resulting LIQ row image and exposes the
   model `pickL1` predicates: `Wait`, no wait-store block, non-tile,
   data-hit/request-complete, and oldest `(BID, loadLsId)` selection.
   R294 carries the selector's selected-row PC, address, size, load ID,
   BID/GID/RID, load LSID, and request-byte mask through this path so the
   parent can wire base-data lookup and launch arbitration later without
   re-scanning the row array.
   R305 extends that selected-row surface with `specWakeup` and `stackValid`
   so `LoadReplayReturnConsumerReady` can require the model mem-wakeup sink
   only for rows that did not already get speculative wakeup and are not stack
   loads.
   R307 extends the same surface with `returnSignExtend`, preserving the
   opcode-derived sign/zero-extension choice needed by
   `LoadReplayReturnDataExtract`. R311 extends the same surface with the
   compact replay destination sideband so later return-payload and wakeup
   owners do not need to reconstruct the renamed destination from top-level
   context. R375 extends the same surface with RF-derived
   `sourceTraceValid/source0/source1` so future replay W2 commit-row source
   fill can consume row-owned provenance rather than ROB allocation metadata.
   R295 has the top consume that selected row in `LoadReplayBaseDataAlign`,
   producing dormant `e2BaseData/e2BaseValidMask` inputs for the same selected
   row. R296 drives those dormant inputs only when `LoadLookupArbiter` grants
   the selected replay row on the shared sparse-memory lookup port. R297 adds
   `LoadReplayLaunchReadiness` as the parent launch arm predicate, R299 feeds
   its source-return input from `LoadReplaySourceReturnReadiness`, and R300
   feeds its return-ready input from `LoadReplayReturnReadiness`. R305 inserts
   `LoadReplayReturnConsumerReady` behind the budget arm, separating the
   always-required IEX LRET sink from the conditional mem-wakeup sink. Launch
   stays disabled until those sinks are real.
6. R282 adds an explicit parent-owned `launchEnable` gate. When it is high,
   the selector's `launchValid/launchIndex` drive `LoadInflightQueue`, which
   relaunches the row through `LoadForwardPipeline` using `e2Stores`,
   `e2BaseData`, `e2BaseValidMask`, and source-return sidebands. When it is
   low, all selector diagnostics remain visible but no LIQ state is mutated by
   launch.
7. R486 adds a parent-owned pick-only gate in parallel with launch. The reduced
   top drives this from the source-return snapshot query issue so the selected
   row enters `Repick` before STQ response matching, but final launch/E4 still
   waits for source-return and replay-return readiness.
8. R487 forwards a parent-owned SCB-return proof gate in parallel with launch
   and pick. The top drives it from the source-return accepted token after that
   token is resident, matching the model order where SCB receive records
   `scbRnt` before STQ receive applies the store snapshot.
9. R417 adds the source-shaped row-mutation bridge in parallel with launch
   selection. The bridge accepts the R410 request shape, blocks malformed or
   out-of-range wait-store identities, and feeds the child LIQ native mutation
   port. The native writer still enforces the R416 target-row and same-cycle
   conflict policy before mutating registered row state.
10. R418 passes split SCB and STQ/store source-return bits to the child LIQ so
   E4 row state can preserve `scbReturned` and `stqReturned` separately while
   keeping the combined `sourcesReturned` wakeup behavior unchanged.
11. R453 passes read-refill wakeups through to the child LIQ. Refilled rows use
   the normal `LoadRefillWakeup` update path: matching working rows return to
   `Wait` with `l1Hit` and full row-owned valid bytes, allowing
   `LoadInflightLaunchSelect` to select them without changing allocation or
   row-mutation ownership.
12. R454 drives the existing E2 source-return and return-ready sidebands from
    the generated-RTL fixture, then observes the child LIQ's existing E4
    outcome, LHQ hit-record, and `Resolved` status outputs. The E4/LHQ
    observation is one cycle after launch, and the registered `Resolved` mask
    follows on the next clock.
13. R455 composes the fixture LHQ record with `LoadResolveQueue` and feeds the
    child LIQ `clearResolved` input one cycle after the queue accepts the
    record, matching the R286 top-level delayed-clear contract.
14. R464 passes replay wakeups through to the child LIQ. A parent-provided SCB
    wake uses the existing `LoadReplayWakeup` logic to merge bytes and mark
    matching non-`Repick` working rows `scbReturned/sourcesReturned`. If that
    row is then launched, `LoadInflightQueue` preserves the source-return
    evidence into `Repick`, satisfying the row-mutation write-control
    prerequisite for MDB wait re-mutation.

The replay and refill wakeup ports remain inactive in this owner unless a
parent wires them; the live reduced top ties both inactive through helper
wiring. The R283
reduced top keeps `launchEnable` low but feeds `e2Stores` from a shared
`ResidentStoreForwardStoreSnapshot`, so the path has model-shaped resident STQ
store candidates available before live replay launch is armed. Base-data and
return-readiness boundaries are wired but disabled where required; live
arbitration wiring remains deferred. R285 consumes the
path-local LHQ hit-record output in the top-level `LoadResolveQueue`, but this
path still relies on the parent to decide when a resolved row should clear LIQ
state or become visible to MDB conflict detection. R286 records that parent
clear timing: the top delays `clearResolvedValid` until the cycle after
ResolveQ accepts the LHQ record, when the source LIQ row is resident in
`Resolved` state.
R417 connects the child LIQ native row-mutation port to the source-shaped
bridge in this composition. R445 promotes the upstream source live arm, so this
path may now write the child LIQ row when the source proof and native
write-control checks both pass.
R429 only forwards the existing child LIQ write-control blocker reasons; it
does not change the bridge request, write-enable predicate, or registered row
mutation behavior.

## Timing

The path is a single-cycle ready/valid allocation boundary around the replay
queue head. If LIQ is full, `candidateBlockedByAlloc` is high and
`candidateConsumeReady` remains low, so the queue keeps the head resident. A
same-cycle flush suppresses allocation and consumption.

## Flush/Recovery

`flush` is passed directly to `LoadInflightQueue` and the adapter. It clears
resident LIQ state and returns the allocation pointer to slot zero. Precise
LSU recovery pruning by BID/RID/LSID is still a deferred LIQ/LDQ recovery
owner.

## Deferred Owners

- Live default `LinxCoreFrontendFetchRfAluTraceTop` replay replacement. R279
  wires this composition only through the opt-in reduced-store replay-LIQ
  wrapper and diagnostics.
- Top-level launch selection between new loads and replay loads. R281 exposes
  `LoadInflightLaunchSelect` through this path and the opt-in top diagnostics,
  and R282 adds the path-local launch drive gate, but the reduced top still
  leaves that gate disabled.
- External SCB replay response ownership and real LRET/mem-wakeup sink
  readiness for enabled relaunch.
- Live enable of `LoadReplaySourceReturnStoreSnapshotRowMutationRequest` and
  raw source-response evidence that can produce active row-mutation requests.
- Default/live LIQ ResolveQ insertion and load-store conflict publication.
- Ready-table, bypass, and dependent-consumer wakeup.

## Verification

Focused gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only ReducedLoadReplayLiqAllocPath
bash tools/chisel/run_chisel_tests.sh --only ReducedStoreWaitReplayToLiqPath
bash tools/chisel/run_chisel_tests.sh --only ReducedStoreWaitReplayChiselPath
bash tools/chisel/run_chisel_tests.sh --only ReducedLoadReplayLiqAllocAdapter
bash tools/chisel/run_chisel_tests.sh --only LoadInflightQueue
bash tools/chisel/run_chisel_tests.sh --only LoadInflightLaunchSelect
bash tools/chisel/run_chisel_reduced_store_wait_replay_chisel_path.sh
bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop
```

Reference tests cover allocation-pointer order, preservation of load LSID,
destination, and forwarding snapshot sidecars, full-LIQ backpressure without
queue consumption, flush clearing, and Chisel elaboration with the adapter, LIQ
row owner, and launch selector child modules. R282 elaboration also locks the path-local
`launchEnable`, E2 forwarding inputs, launch accepted/ready outputs, E4
diagnostics, LHQ hit-record output, and miss-pending diagnostic.
R429 elaboration also locks the detailed row-mutation write-control blocker
output names so downstream top-level diagnostics can distinguish model
prerequisite failures from same-row writer conflicts.
R449 locks the cross-owner wait-store-to-LIQ allocation contract while live
launch, source-return mutation, and row lifecycle side effects remain disabled.
R451 adds a test-only Chisel composition probe that connects the real STQ row
owner through the resident-forward, wait-slot, replay-wakeup, relaunch-queue,
and LIQ allocation modules. This probe is fixture evidence for the
address-ready/data-late timing window R450 could not reach through the live
`LDI`/`SDI`/`LDI` top stimulus; it is not itself QEMU/live promotion evidence.
R452 makes the probe executable through Verilator. The generated-RTL harness
asserts that the relaunch queue fires into this LIQ path and that the first
resident LIQ row preserves the `youngestStoreLsId` sidecar from the blocking
store.
R453 extends that generated-RTL harness to drive a refill wakeup after LIQ
allocation, assert that the refilled row becomes a launch candidate, and then
assert `launchEnable` long enough for `launchAccepted` to move the row into
`Repick`. The passing report records `liq_refill=true`,
`liq_launch_valid=true`, `liq_launch_accepted=true`, and `launch_load_lsid=3`.
R454 extends the same harness to drive source-return and return-port readiness
on the launch cycle. The passing report records `liq_e4_update=true`,
`liq_lhq_record=true`, `liq_resolved=true`, and
`e4_cycles_after_launch=1`, proving the existing path-local E4/LHQ diagnostics
and registered resolved-row transition under generated RTL. The proof remains
fixture-driven and does not promote the live reduced top.
R455 extends the same harness through the next owner boundary:
`LoadResolveQueue` accepts the LHQ record, the delayed `clearResolved` request
clears the LIQ row, and the ResolveQ record remains resident. The passing
report records `resolve_queue_push=true`, `liq_clear_resolved=true`, and
`resolve_queue_count=1`. This remains generated-RTL fixture evidence because
the replay launch, refill, source-return sidebands, and return readiness are
harness-driven.
R464 locks the replay-wakeup pass-through and positive MDB row-write evidence.
The generated-RTL report records `liq_replay_wake_completed_mask=2`,
`liq_scb_returned_mask_before_mdb_write=2`,
`mdb_lookup_wait_plan_write=true`, `mdb_lookup_wait_plan_apply=true`, and
`liq_wait_store_mask_after_mdb_write=2`, proving the row write uses native LIQ
source-return evidence rather than bypassing write-control.
R487 locks the direct SCB-return proof pass-through at this composition
boundary. Focused elaboration checks cover `scbReturnValid`, `scbReturnReady`,
and `scbReturnAccepted` so the top can drive model ordering evidence without
changing replay-wakeup ownership.
R489 keeps the same `clearResolved*` pass-through while relying on
`LoadInflightQueue` to classify complete source-returned `Repick` rows as
clearable terminal rows.
