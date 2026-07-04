# LoadReplaySourceReturnStoreSnapshotIdentityMatch

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplaySourceReturnStoreSnapshotIdentityMatch.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/LoadReplaySourceReturnStoreSnapshotIdentityMatchSpec.scala`
- Integrated user: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplaySourceReturnStoreSnapshotPath.scala`
  - `LinxCoreFrontendFetchRfAluTraceTop` instantiates the R395 path rather than
    this owner directly, avoiding another child in the oversized top
    constructor.
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/lsu/load_unit/ldq.cpp`
    - `LDQInfo::handleSTQReceive`
    - `LDQInfo::waitStore`
    - `LDQInfo::handleMerge`
  - `model/LinxCoreModel/model/ModelCommon/bus/MemReqBus.h`
    - `MemReqBus::cID`
    - `MemReqBus::eID`
- Related Chisel contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplaySourceReturnStoreSnapshotQueryIssue.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplaySourceReturnStoreSnapshotSelectedIdentity.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplaySourceReturnStoreSnapshotAcceptedToken.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplaySourceReturnStoreSnapshotResponseMatch.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplaySourceReturnStoreSnapshotEvidence.scala`
- Contract IDs: `LC-CHISEL-LSU-REPLAY-STQ-SNAPSHOT-IDENTITY-MATCH-001`

## Purpose

`LoadReplaySourceReturnStoreSnapshotIdentityMatch` names the selected-row
identity check on the replay-LIQ STQ snapshot response path.

The model response path indexes the LDQ row from `MemReqBus.cID/eID` in
`LDQInfo::handleSTQReceive`, returns early if that row is no longer
`LDQ_REPICK`, and only then lets the response participate in SCB-order,
wait-store, and data-merge handling. R394 captures that identity/stale-row
boundary before the R393 response-order owner.

R395 integrates this module inside
`LoadReplaySourceReturnStoreSnapshotPath`. The reduced top still ties raw
selected-row identity and response inputs false at the path boundary, so
`responseMatchesSelected` remains false in the current live-disabled mode. The
identity/stale-row boundary is nevertheless now part of the composed path
instead of a separate direct top child.

R396 adds `LoadReplaySourceReturnStoreSnapshotSelectedIdentity` upstream inside
that path. In the current reduced single-cluster topology, the selected LIQ
launch index projects to `(clusterId=0, entryId=launchIndex)` and
`repickMask[launchIndex]` supplies the stale-row guard. Full LDQ selected-row
storage can still replace that projection through the raw path-boundary inputs.

R397 inserts `LoadReplaySourceReturnStoreSnapshotAcceptedToken` between the
selected-identity projection and this matcher. This matcher now sees the
identity of an accepted local STQ snapshot query, including an empty-slot
same-cycle bypass, rather than the floating selected launch row.

## Interface

### Inputs

| Signal | Description |
|---|---|
| `enable` | Replay-LIQ wrapper is active. |
| `flush` | Store/replay flush suppresses response identity matching. |
| `queryIssued` | Accepted-query token is visible to the response path. |
| `selectedValid` | Accepted-query token identity is valid. |
| `selectedRepick` | Accepted token still represents a model-equivalent `LDQ_REPICK` row. |
| `responseValid` | Future raw STQ response is visible. |
| `selectedClusterId` | Future selected replay-row cluster ID. |
| `selectedEntryId` | Future selected replay-row entry ID. |
| `responseClusterId` | Future STQ response cluster ID from `MemReqBus.cID`. |
| `responseEntryId` | Future STQ response entry ID from `MemReqBus.eID`. |

### Outputs

| Signal | Description |
|---|---|
| `active` | `enable && !flush`. |
| `matchCandidate` | Active raw response is visible. |
| `selectedReady` | Selected row is valid and still repick in an active cycle. |
| `clusterMatches` | Selected and response cluster IDs match. |
| `entryMatches` | Selected and response entry IDs match. |
| `identityMatches` | Both cluster and entry IDs match. |
| `responseMatchesSelected` | Query-issued, live selected-row, non-stale identity match. |
| `blockedByDisabled` | Raw response appears while wrapper is disabled. |
| `blockedByFlush` | Raw response appears during flush. |
| `blockedByNoQuery` | Response appears before query issue. |
| `blockedByNoSelected` | Response appears after query issue without selected-row identity. |
| `blockedByStaleRow` | Selected row is no longer in repick state. |
| `blockedByClusterMismatch` | Response targets a different cluster. |
| `blockedByEntryMismatch` | Response targets a different entry in the same cluster. |
| `invalidResponseWithoutQuery` | Raw response appears while active before query issue. |

## State

The module is combinational. R397 moves selected-row storage into
`LoadReplaySourceReturnStoreSnapshotAcceptedToken`; this matcher still does
not allocate an STQ response queue entry or mutate LDQ/LIQ state.

## Logic Design

The owner mirrors the model acceptance order:

```text
matchCandidate          = enable && !flush && responseValid
responseHasQuery        = matchCandidate && queryIssued
responseHasSelected     = responseHasQuery && selectedValid
responseHasLiveSelected = responseHasSelected && selectedRepick
identityMatches         = selectedClusterId == responseClusterId &&
                          selectedEntryId == responseEntryId
responseMatchesSelected = responseHasLiveSelected && identityMatches
```

`responseMatchesSelected` feeds
`LoadReplaySourceReturnStoreSnapshotResponseMatch.responseMatchesSelected`.
SCB-before-STQ ordering remains in that downstream owner.

## Timing

The current reduced top instantiates this module through the R395/R396
composite path. Future live integration must source stable selected-row
identity from the accepted-token owner and raw response identity from an STQ
response queue, while preserving the top compile-budget constraint.

## Flush/Recovery

Flush clears `matchCandidate`, `selectedReady`, and
`responseMatchesSelected`. Response evidence visible during flush is reported
through `blockedByFlush`.

## Deferred Owners

- Full selected replay-row identity storage from replay-LIQ launch residency
  beyond the R397 one-token reduced boundary.
- Raw STQ response queue carrying `MemReqBus.cID/eID`.
- Live `selectedRepick` stale-row state from the replay row FSM.
- Live SCB-order evidence, wait-store mutation, and data merge.

## Verification

Focused gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadReplaySourceReturnStoreSnapshotIdentityMatch
bash tools/chisel/run_chisel_tests.sh --only LoadReplaySourceReturnStoreSnapshotAcceptedToken
bash tools/chisel/run_chisel_tests.sh --only LoadReplaySourceReturnStoreSnapshotPath
bash tools/chisel/run_chisel_tests.sh --only LoadReplaySourceReturnStoreSnapshotResponseMatch
bash tools/chisel/run_chisel_tests.sh --only LoadReplaySourceReturnStoreSnapshotQueryIssue
bash tools/chisel/run_chisel_tests.sh --only LoadReplaySourceReturnStoreSnapshotEvidence
bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop
FETCH_REDUCED_STORE_REPLAY_LIQ=1 BUILD_DIR=generated/r397x bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh
```

Reference tests cover disabled/flush suppression, no-query blocking,
missing-selected blocking, stale-row rejection, cluster/entry mismatch
diagnostics, matched selected-row acceptance, and Chisel elaboration.
