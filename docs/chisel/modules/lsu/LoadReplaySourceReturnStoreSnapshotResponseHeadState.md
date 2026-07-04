# LoadReplaySourceReturnStoreSnapshotResponseHeadState

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplaySourceReturnStoreSnapshotResponseHeadState.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/LoadReplaySourceReturnStoreSnapshotResponseHeadStateSpec.scala`
- Integrated user: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplaySourceReturnStoreSnapshotPath.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/mtccore/lsu/load_unit/ldq.cpp`
    - `MtcLDQInfo::handleSTQReceive`
    - `clusters[bus.cID].entryArr[bus.eID]`
    - `entry.fsm != MTC_LDQ_REPICK`
    - `ASSERT(entry.scbRnt)`
- Contract IDs: `LC-CHISEL-LSU-REPLAY-STQ-SNAPSHOT-RESPONSE-HEAD-STATE-001`

## Purpose

`LoadReplaySourceReturnStoreSnapshotResponseHeadState` proves whether the raw
local STQ response FIFO head targets a replay row that is no longer repick.

The model does not drop stale STQ responses because they merely fail to match
the currently selected row. It indexes the response target row with
`bus.cID/eID` and returns only when that row's fsm is no longer
`MTC_LDQ_REPICK`.

R400 adds the reduced single-cluster version of that proof inside
`LoadReplaySourceReturnStoreSnapshotPath`. In the current reduced topology,
`clusterId=0` and `entryId` maps to the LIQ slot. Therefore a visible response
head is stale only when:

```text
clusterId == 0
entryId < liqEntries
repickMask(entryId) == 0
```

R419 adds reduced row-valid and row-SCB-returned masks beside the repick mask.
These masks do not change stale-drain semantics: the model still discards a
response when the addressed row is not repick. They do let the composite prove
that a still-repick head is eligible to satisfy the model's SCB-before-STQ
assertion before response matching treats the head as ordered.

The current top still ties raw response inputs false, so this owner does not
make replay launch live. It feeds the R399 response drain and the response
matcher's SCB-order proof so a future raw head can be dropped or consumed only
from explicit row-state evidence.

## Interface

### Inputs

| Signal | Description |
|---|---|
| `enable` | Replay-LIQ wrapper is active. |
| `flush` | Suppresses stale proof. |
| `reducedEnable` | Enables the reduced single-cluster row-state proof from `repickMask`. |
| `headValid` | Raw response queue exposes a head. |
| `responseClusterId` | Queue-head response target cluster ID. |
| `responseEntryId` | Queue-head response target entry ID. |
| `repickMask` | Current reduced LIQ row-state mask where `1` means the row is still repick. |
| `rowProofEnable` | Enables reduced row-valid and SCB-returned mask checks. |
| `rowValidMask` | Current reduced LIQ row-valid mask for the response target. |
| `rowScbReturnedMask` | Current reduced LIQ mask where `1` means the target row has already seen SCB return. |
| `externalHeadStale` | Future full row-state stale proof or explicit override from outside the reduced projection. |

### Outputs

| Signal | Description |
|---|---|
| `active` | `enable && !flush`. |
| `headStale` | Shared stale proof consumed by `LoadReplaySourceReturnStoreSnapshotResponseDrain`. |
| `externalHeadStaleUsed` | External stale proof was accepted for the visible head. |
| `reducedHeadTargetsRow` | The head targets a reduced single-cluster LIQ row. |
| `reducedHeadRepick` | The targeted reduced row is still repick. |
| `reducedHeadRowValid` | The target row is valid when reduced row proof is enabled, or the target is classifiable when it is disabled. |
| `reducedHeadScbReturned` | The target row is still repick and has SCB-return evidence when reduced row proof is enabled. |
| `reducedHeadApplyEligible` | The target row is still repick, row-valid, and SCB-returned, so an ordered response may apply to it. |
| `reducedHeadStale` | The targeted reduced row is no longer repick. |
| `reducedHeadOneHot` | One-hot row selector for the targeted reduced LIQ entry. |
| `blockedByNoHead` | Active proof has no visible response head. |
| `blockedByReducedDisabled` | A head is visible but neither reduced nor external stale proof is enabled. |
| `blockedByUnsupportedCluster` | Reduced proof cannot classify a nonzero cluster ID. |
| `blockedByEntryOutOfRange` | Reduced proof cannot classify an entry ID outside `liqEntries`. |
| `blockedByInvalidRow` | Reduced row proof is enabled and the target row is not valid. |
| `blockedByScbNotReturned` | The target row is still repick but has not yet seen SCB return. |
| `blockedByStillRepick` | The head targets a row that is still repick, so it is not stale. |
| `blockedByDisabled` | Head evidence appears while disabled. |
| `blockedByFlush` | Head evidence appears during flush. |

## State

The module is combinational. It reads reduced row-state masks but does not own
the LIQ/LDQ row fsm, raw response FIFO, accepted-query token, wait-store
state, or returned-data merge.

## Logic Design

The reduced proof intentionally classifies only rows it can prove:

```text
reducedCandidate   = active && reducedEnable && headValid
targetsReducedRow  = reducedCandidate && clusterId == 0 && entryId < liqEntries
reducedHeadRepick  = targetsReducedRow && repickMask(entryId)
reducedHeadRowValid =
  targetsReducedRow && (!rowProofEnable || rowValidMask(entryId))
reducedHeadScbReturned =
  reducedHeadRepick && (!rowProofEnable || rowScbReturnedMask(entryId))
reducedHeadApplyEligible =
  reducedHeadRepick && reducedHeadRowValid && reducedHeadScbReturned
reducedHeadStale   = targetsReducedRow && !repickMask(entryId)
headStale          = externalHeadStaleUsed || reducedHeadStale
```

Unsupported cluster IDs and out-of-range entry IDs do not become stale by
default. They hold the FIFO head until a later full row-state owner can prove
the target state or classify the payload as illegal.

## Timing

The owner consumes queue head identity and the current reduced `repickMask` in
the same cycle. It feeds only the R399 drain's `headStale` input; queue head
visibility remains independent of dequeue ready.

## Flush/Recovery

Flush suppresses stale proof. The response queue owns resident-entry clearing
on path flush, and precise response-specific flush pruning remains deferred.

## Deferred Owners

- Full multi-cluster LDQ/LIQ row-state fsm source for `externalHeadStale`.
- Illegal/out-of-range response handling once raw response sourcing is live.
- Precise queued-response flush pruning by response identity.
- Wait-store row mutation and returned-data merge after ordered consumption.

## Verification

Focused gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadReplaySourceReturnStoreSnapshotResponseHeadState
bash tools/chisel/run_chisel_tests.sh --only LoadReplaySourceReturnStoreSnapshotPath
```

Reference tests cover still-repick heads, reduced stale heads, SCB-not-returned
row proof, invalid-row proof, unsupported cluster and out-of-range entry
holding, external stale proof, disabled/flush suppression, no-head behavior,
reduced-disabled behavior, and Chisel elaboration.
