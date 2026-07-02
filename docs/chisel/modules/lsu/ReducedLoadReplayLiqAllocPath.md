# ReducedLoadReplayLiqAllocPath

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/ReducedLoadReplayLiqAllocPath.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/ReducedLoadReplayLiqAllocPathSpec.scala`
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
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadResolveQueue.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/ReducedLoadReplayRelaunchQueue.scala`
- Contract IDs: `LC-CHISEL-LSU-LIQ-003`

## Purpose

`ReducedLoadReplayLiqAllocPath` is the composition boundary between queued
reduced replay candidates and registered LIQ row state. It instantiates
`ReducedLoadReplayLiqAllocAdapter`, `LoadInflightQueue`, and the R280
`LoadInflightLaunchSelect` selector. It still uses the LIQ allocation handshake
as the only authority for consuming the replay queue head.

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

## Interface

### Inputs

| Signal | Description |
|---|---|
| `flush` | Flushes the internal LIQ and suppresses candidate consumption. |
| `candidateValid` | Queue-head valid from `ReducedLoadReplayRelaunchQueue`. |
| `candidate` | Queued reduced replay candidate with load identity and forwarding snapshot. |
| `launchEnable` | Parent-owned arm bit. When low, selector diagnostics remain visible but `LoadInflightQueue.launchValid` is not driven. |
| `e2Stores` | Abstract STQ forwarding rows for the `LoadForwardPipeline` launch path. R283 top wiring feeds a `ResidentStoreForwardStoreSnapshot` vector while keeping launch disabled. |
| `e2BaseData` / `e2BaseValidMask` | Baseline line data and valid bytes for relaunches that do not already have row-owned data. |
| `e2LoadDataReturned` / `e2ScbReturned` / `e2ReturnReady` | Source-return and return-slot readiness sidebands consumed by `LoadForwardPipeline`. |
| `clearResolvedValid` | Pass-through clear request for future tests or consumers that resolve rows. |
| `clearResolvedIndex` | LIQ slot for `clearResolvedValid`. |

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
| `launchSelected*` | R294 selected launch-row identity from `LoadInflightLaunchSelect`: LIQ load ID, BID/GID/RID, load LSID, PC, address, size, and 64-byte request mask. These signals remain diagnostic while `launchEnable` is low. |
| `launchDriveValid` | Actual valid presented to `LoadInflightQueue.launchValid`; low unless `launchEnable && launchValid`. |
| `launchReady` | Selected row is launch-ready in `LoadInflightQueue`. |
| `launchAccepted` | Selected row entered `Repick` through `LoadInflightQueue`. |
| `repickMask` / `missMask` / `resolvedMask` | Pass-through LIQ state masks after any enabled relaunches. |
| `e4UpdateValid` / `e4UpdateIndex` / `e4MissKind` / `e4WakeupValid` | Pass-through E4 outcome diagnostics from the launched-row pipeline. |
| `lhqRecordValid` / `lhqRecord` | Path-local resolved-load hit record, including load PC after R284. R285 top wiring can append this row to `LoadResolveQueue`; R286 top wiring clears the resolved LIQ row after that append is accepted. This module itself does not own ResolveQ backpressure, retire, or MDB publication. |
| `residentCount` | Resident LIQ row count. |
| `empty` / `full` | LIQ occupancy diagnostics. |
| `missPending` | Pass-through LIQ miss-pending diagnostic. |
| `clearResolvedAccepted` | Pass-through `LoadInflightQueue` clear response. |

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
6. R282 adds an explicit parent-owned `launchEnable` gate. When it is high,
   the selector's `launchValid/launchIndex` drive `LoadInflightQueue`, which
   relaunches the row through `LoadForwardPipeline` using `e2Stores`,
   `e2BaseData`, `e2BaseValidMask`, and source-return sidebands. When it is
   low, all selector diagnostics remain visible but no LIQ state is mutated by
   launch.

Replay wakeup and refill wakeup ports remain inactive in this owner. The R283
reduced top keeps `launchEnable` low but feeds `e2Stores` from a shared
`ResidentStoreForwardStoreSnapshot`, so the path has model-shaped resident STQ
store candidates available before live replay launch is armed. Base-data,
return-readiness, and live arbitration wiring remain deferred. R285 consumes the
path-local LHQ hit-record output in the top-level `LoadResolveQueue`, but this
path still relies on the parent to decide when a resolved row should clear LIQ
state or become visible to MDB conflict detection. R286 records that parent
clear timing: the top delays `clearResolvedValid` until the cycle after
ResolveQ accepts the LHQ record, when the source LIQ row is resident in
`Resolved` state.

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
- Row/base-data ownership, return-readiness wiring, and source arbitration for
  enabled relaunch.
- Default/live LIQ ResolveQ insertion and load-store conflict publication.
- Ready-table, bypass, and dependent-consumer wakeup.

## Verification

Focused gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only ReducedLoadReplayLiqAllocPath
bash tools/chisel/run_chisel_tests.sh --only ReducedLoadReplayLiqAllocAdapter
bash tools/chisel/run_chisel_tests.sh --only LoadInflightQueue
bash tools/chisel/run_chisel_tests.sh --only LoadInflightLaunchSelect
bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop
```

Reference tests cover allocation-pointer order, preservation of load LSID and
forwarding snapshot sidecars, full-LIQ backpressure without queue consumption,
flush clearing, and Chisel elaboration with the adapter, LIQ row owner, and
launch selector child modules. R282 elaboration also locks the path-local
`launchEnable`, E2 forwarding inputs, launch accepted/ready outputs, E4
diagnostics, LHQ hit-record output, and miss-pending diagnostic.
