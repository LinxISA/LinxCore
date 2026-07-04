# LoadReplaySourceReturnStoreSnapshotEvidence

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplaySourceReturnStoreSnapshotEvidence.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/LoadReplaySourceReturnStoreSnapshotEvidenceSpec.scala`
- Integrated user: `rtl/LinxCore/chisel/src/main/scala/linxcore/top/LinxCoreFrontendFetchRfAluTraceTop.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/lsu/load_unit/ldq_cluster.cpp`
    - `LUEntryInfo::Reset`
    - `LUEntryInfo::rewait`
  - `model/LinxCoreModel/model/lsu/load_unit/ldq.cpp`
    - `LDQInfo::loadRepick`
    - `LDQInfo::handleL1Lookup`
    - `LDQInfo::handleSTQReceive`
    - `LDQInfo::waitStore`
    - `LDQInfo::handleMerge`
    - `LDQInfo::pickL1`
- Related Chisel contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplaySourceReturnStoreSnapshotQueryIssue.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplaySourceReturnStoreSnapshotIdentityMatch.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplaySourceReturnStoreSnapshotResponseMatch.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplaySourceReturnStoreSnapshotReadyControl.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplaySourceReturnReadiness.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/ResidentStoreForwardStoreSnapshot.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadStoreForwarding.scala`
- Contract IDs: `LC-CHISEL-LSU-REPLAY-STQ-SNAPSHOT-EVIDENCE-001`

## Purpose

`LoadReplaySourceReturnStoreSnapshotEvidence` names the selected-row evidence
boundary for the local STQ source-return side of replay-LIQ relaunch.

The model resets `stqRnt` when a row is reset or rewaited. A repicked row sends
the lookup to STQ from `LDQInfo::handleL1Lookup`, and
`LDQInfo::handleSTQReceive` later sets `stqRnt` for the matching `LDQ_REPICK`
entry. A response with `wait_store` forces `LDQInfo::waitStore`; a response
without data still completes the STQ return flag; a response with data merges
bytes through `LDQInfo::handleMerge`.

R391 keeps the reduced top dormant. The top feeds this owner with the selected
replay-row launch-valid signal. R392 drives `queryIssued` from
`LoadReplaySourceReturnStoreSnapshotQueryIssue`, but keeps that owner's
`requestEnable` and `sinkReady` false, so `queryIssued` remains false. R393
drives `responseValid`, `waitStore`, and `dataValid` from
`LoadReplaySourceReturnStoreSnapshotResponseMatch`, while raw response,
selected-row match, and SCB-order evidence remain false. R394 names the future
selected-row identity matcher as a standalone unit; this top does not
instantiate it until the constructor method-size limit is relieved. Its
`snapshotRequired` and `snapshotValid` outputs feed
`LoadReplaySourceReturnStoreSnapshotReadyControl`; that control still has
`requestEnable=false`, so the integrated readiness path continues to forward
the legacy combinational resident-store snapshot readiness.

## Interface

### Inputs

| Signal | Description |
|---|---|
| `enable` | Replay-LIQ wrapper is active. |
| `flush` | Store/replay flush suppresses evidence acceptance. |
| `launchValid` | A selected replay row would be eligible for source-return qualification. |
| `queryIssued` | Future STQ snapshot request for the selected replay row has been issued. |
| `responseValid` | Future STQ response for the selected replay row has returned. |
| `waitStore` | The returned STQ response requests wait-store replay. |
| `dataValid` | The returned STQ response carries mergeable store data. |

### Outputs

| Signal | Description |
|---|---|
| `active` | `enable && !flush`. |
| `requestValid` | Active selected replay row. This is the live-mode local-STQ source requirement. |
| `queryActive` | Required selected row has issued an STQ query. |
| `responseAccepted` | Required selected row has an issued query and selected-row response. |
| `snapshotRequired` | Evidence requirement sent to `LoadReplaySourceReturnStoreSnapshotReadyControl`. |
| `snapshotValid` | Selected-row STQ evidence completed without wait-store replay. |
| `waitStoreReplay` | Selected-row response requests wait-store rewait. |
| `mergeDataPresent` | Completed response carries STQ data to merge. |
| `noDataReturn` | Completed response carries no STQ data; the model still treats `stqRnt` as true. |
| `blockedByDisabled` | Evidence appears while the wrapper is disabled. |
| `blockedByFlush` | Evidence appears during flush. |
| `blockedByNoLaunch` | Query/response evidence appears with no selected replay row. |
| `blockedByNoQuery` | Selected replay row has not issued its STQ query. |
| `blockedByNoResponse` | Issued query has not received its selected-row response. |
| `blockedByWaitStore` | Response asks the row to rewait on a store. |
| `invalidResponseWithoutQuery` | Response is visible without a query. |
| `invalidWaitStoreWithoutResponse` | Wait-store is visible without a response. |
| `invalidDataWithWaitStore` | Data is visible on a wait-store response. |

## State

The module is combinational. It does not store selected-row identity or merge
data. R392 names the request-issue side of `queryIssued`, R393 names the
response-order side, and R394 names a standalone future identity-match source
for the still-tied selected-row match input.

## Logic Design

The owner separates four model concepts that are easy to conflate:

1. A repicked row requires an STQ source-return response.
2. The STQ query may not have been issued yet.
3. The STQ response may return with no data and still set `stqRnt`.
4. A wait-store response cancels completion and rewaits the row.

The Chisel predicate is:

```text
active           = enable && !flush
requestValid     = active && launchValid
queryActive      = requestValid && queryIssued
responseAccepted = queryActive && responseValid
snapshotRequired = requestValid
snapshotValid    = responseAccepted && !waitStore
```

`mergeDataPresent` and `noDataReturn` classify completed non-wait responses.
They are diagnostics only; byte merge remains in the store-forwarding and
return-data path.

## Timing

The current reduced top uses this module as a same-cycle diagnostic owner. It
does not change the launch or replay-row lifecycle timing because
`LoadReplaySourceReturnStoreSnapshotReadyControl.requestEnable` remains false.

## Flush/Recovery

Flush clears `active`, `requestValid`, `queryActive`, `responseAccepted`, and
snapshot completion. Evidence visible during flush is reported through
`blockedByFlush`.

## Deferred Owners

- Raw STQ response queue and live selected-row identity into R394 matching.
- Stateful wait-store replay row mutation.
- Store-data merge into replay row line data.
- Live promotion of `LoadReplaySourceReturnStoreSnapshotReadyControl.requestEnable`.

## Verification

Focused gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadReplaySourceReturnStoreSnapshotEvidence
bash tools/chisel/run_chisel_tests.sh --only LoadReplaySourceReturnStoreSnapshotResponseMatch
bash tools/chisel/run_chisel_tests.sh --only LoadReplaySourceReturnStoreSnapshotQueryIssue
bash tools/chisel/run_chisel_tests.sh --only LoadReplaySourceReturnStoreSnapshotReadyControl
bash tools/chisel/run_chisel_tests.sh --only LoadReplaySourceReturnReadiness
bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop
FETCH_REDUCED_STORE_REPLAY_LIQ=1 BUILD_DIR=generated/r393x bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh
```

Reference tests cover idle/no-row behavior, selected-row query requirement,
query wait, no-data response completion, data response merge diagnostics,
wait-store replay blocking, malformed response diagnostics, and Chisel
elaboration.
