# LoadReplaySourceReturnStoreSnapshotSelectedIdentity

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplaySourceReturnStoreSnapshotSelectedIdentity.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/LoadReplaySourceReturnStoreSnapshotSelectedIdentitySpec.scala`
- Integrated user: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplaySourceReturnStoreSnapshotPath.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/lsu/load_unit/ldq.cpp`
    - `LDQInfo::pickL1`
    - `LDQInfo::loadRepick`
    - `LDQInfo::handleL1Lookup`
    - `LDQInfo::handleSTQReceive`
- Related Chisel contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadInflightLaunchSelect.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadInflightQueue.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/ReducedLoadReplayLiqAllocPath.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplaySourceReturnStoreSnapshotAcceptedToken.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplaySourceReturnStoreSnapshotIdentityMatch.scala`
- Contract IDs: `LC-CHISEL-LSU-REPLAY-STQ-SNAPSHOT-SELECTED-ID-001`

## Purpose

`LoadReplaySourceReturnStoreSnapshotSelectedIdentity` projects the reduced
replay-LIQ selected launch slot into the selected-row identity consumed by the
local STQ snapshot response matcher.

In LinxCoreModel, `LDQInfo::pickL1` selects an `LDQ_WAIT` row, then
`LDQInfo::loadRepick` marks that row `LDQ_REPICK` and records it in the L1
lookup entries. Later `LDQInfo::handleSTQReceive` indexes the response by
`MemReqBus.cID/eID` and accepts it only if the row is still `LDQ_REPICK`.

The current reduced Chisel path has one reduced cluster. This module therefore
maps the selected LIQ launch index to `(clusterId=0, entryId=launchIndex)` and
uses `ReducedLoadReplayLiqAllocPath.repickMask` to decide whether the selected
slot is already resident in the post-launch `Repick` state. It is a projection
owner only; real multi-cluster LDQ identity storage remains deferred.
R397 consumes this projection through
`LoadReplaySourceReturnStoreSnapshotAcceptedToken`, so response matching now
uses an accepted-query token instead of the raw current launch selector.

## Interface

### Inputs

| Signal | Description |
|---|---|
| `enable` | Projection owner is active. |
| `flush` | Suppresses selected identity during replay/store flush. |
| `launchValid` | Reduced LIQ selector has a candidate launch row. |
| `launchIndex` | Selected reduced LIQ slot. |
| `repickMask` | Current LIQ rows already marked `LoadInflightStatus.Repick`. |

### Outputs

| Signal | Description |
|---|---|
| `active` | `enable && !flush`. |
| `selectedValid` | Active selected launch row exists. |
| `selectedRepick` | Selected row is valid and its LIQ slot is marked `Repick`. |
| `selectedClusterId` | Reduced single-cluster ID, currently zero. |
| `selectedEntryId` | Reduced entry ID, equal to `launchIndex`. |
| `selectedIndexOneHot` | One-hot selected LIQ slot when a selected row is valid. |
| `repickMaskHit` | Selected one-hot intersects `repickMask`. |
| `blockedByDisabled` | Launch candidate was present while disabled. |
| `blockedByFlush` | Launch candidate was present during flush. |
| `blockedByNoLaunch` | Active owner has no selected launch candidate. |
| `blockedByNotRepick` | Selected launch row is not yet marked `Repick`. |

## State

The module is combinational. It does not store selected-row identity, mutate LIQ
state, issue STQ requests, consume STQ responses, or merge returned data.

## Logic Design

The module computes:

```text
selectedValid  = enable && !flush && launchValid
selectedRepick = selectedValid && repickMask[launchIndex]
selectedID     = (clusterId = 0, entryId = launchIndex)
```

This exactly matches the current reduced Chisel topology: `LoadInflightLaunchSelect`
chooses a `Wait` row, and `LoadInflightQueue` changes that row to `Repick` only
when the parent launch handshake is accepted. Therefore a pre-accept selected
row may be valid but not response-ready. The stale-row guard in
`LoadReplaySourceReturnStoreSnapshotIdentityMatch` remains the authority for
rejecting responses before the row reaches or after it leaves `Repick`.

## Deferred Owners

- Multi-cluster LDQ selected-row `cID/eID` storage.
- Multi-entry or multi-cluster selected-row token storage beyond the R397
  reduced accepted-query token.
- Raw STQ response queueing and `MemReqBus.cID/eID` decode.
- Live promotion of store-snapshot request issue and response acceptance.

## Verification

Focused gate:

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadReplaySourceReturnStoreSnapshotSelectedIdentity
```

Reference tests cover disabled and flush blockers, single-cluster entry
projection, valid-but-not-repick selected rows, no-launch diagnostics, and
Chisel elaboration.
