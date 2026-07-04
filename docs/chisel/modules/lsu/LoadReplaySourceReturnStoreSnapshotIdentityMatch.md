# LoadReplaySourceReturnStoreSnapshotIdentityMatch

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplaySourceReturnStoreSnapshotIdentityMatch.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/LoadReplaySourceReturnStoreSnapshotIdentityMatchSpec.scala`
- Intended integrated user: `rtl/LinxCore/chisel/src/main/scala/linxcore/top/LinxCoreFrontendFetchRfAluTraceTop.scala`
  - R394 top integration is deferred because a dormant instance tripped the
    oversized top constructor `Method too large` limit.
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

The reduced top does not instantiate this module yet. Its R393 response-match
owner still ties `responseMatchesSelected=false`; this standalone R394 module
documents and tests the identity/stale-row boundary that will replace that
tie-off after the top is split or the response owner absorbs the logic without
growing the top constructor.

## Interface

### Inputs

| Signal | Description |
|---|---|
| `enable` | Replay-LIQ wrapper is active. |
| `flush` | Store/replay flush suppresses response identity matching. |
| `queryIssued` | Selected-row STQ snapshot query has issued. |
| `selectedValid` | Future selected replay-row identity is valid. |
| `selectedRepick` | Future selected row is still in the model-equivalent `LDQ_REPICK` state. |
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

The module is combinational. It does not store selected-row identity, allocate
an STQ response queue entry, or mutate LDQ/LIQ state.

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

The current reduced top does not instantiate this module. Future live
integration must source stable selected-row identity from replay-LIQ state and
raw response identity from an STQ response queue, while preserving the top
compile-budget constraint.

## Flush/Recovery

Flush clears `matchCandidate`, `selectedReady`, and
`responseMatchesSelected`. Response evidence visible during flush is reported
through `blockedByFlush`.

## Deferred Owners

- Selected replay-row identity storage from replay-LIQ launch residency.
- Raw STQ response queue carrying `MemReqBus.cID/eID`.
- Live `selectedRepick` stale-row state from the replay row FSM.
- Live SCB-order evidence, wait-store mutation, and data merge.

## Verification

Focused gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadReplaySourceReturnStoreSnapshotIdentityMatch
bash tools/chisel/run_chisel_tests.sh --only LoadReplaySourceReturnStoreSnapshotResponseMatch
bash tools/chisel/run_chisel_tests.sh --only LoadReplaySourceReturnStoreSnapshotQueryIssue
bash tools/chisel/run_chisel_tests.sh --only LoadReplaySourceReturnStoreSnapshotEvidence
bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop
FETCH_REDUCED_STORE_REPLAY_LIQ=1 BUILD_DIR=generated/r394x bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh
```

Reference tests cover disabled/flush suppression, no-query blocking,
missing-selected blocking, stale-row rejection, cluster/entry mismatch
diagnostics, matched selected-row acceptance, and Chisel elaboration.
