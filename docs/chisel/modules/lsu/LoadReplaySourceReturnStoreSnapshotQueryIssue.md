# LoadReplaySourceReturnStoreSnapshotQueryIssue

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplaySourceReturnStoreSnapshotQueryIssue.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/LoadReplaySourceReturnStoreSnapshotQueryIssueSpec.scala`
- Integrated user: `rtl/LinxCore/chisel/src/main/scala/linxcore/top/LinxCoreFrontendFetchRfAluTraceTop.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/lsu/load_unit/ldq.cpp`
    - `LDQInfo::pickL1`
    - `LDQInfo::loadRepick`
    - `LDQInfo::handleL1Lookup`
    - `LDQInfo::handleSTQReceive`
  - `model/LinxCoreModel/model/lsu/store_unit/store_unit.cpp`
    - `StoreUnit::handleLoadReq`
  - `model/LinxCoreModel/model/lsu/store_unit/stq.cpp`
    - `STQ::lookupForLoad`
- Related Chisel contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplaySourceReturnStoreSnapshotIdentityMatch.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplaySourceReturnStoreSnapshotResponseMatch.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplaySourceReturnStoreSnapshotEvidence.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplaySourceReturnStoreSnapshotReadyControl.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplaySourceReturnReadiness.scala`
- Contract IDs: `LC-CHISEL-LSU-REPLAY-STQ-SNAPSHOT-QUERY-ISSUE-001`

## Purpose

`LoadReplaySourceReturnStoreSnapshotQueryIssue` names the issue side of the
selected-row STQ snapshot request used by replay-LIQ source-return readiness.

The model path is:

1. `LDQInfo::pickL1` chooses a non-wait-store `LDQ_WAIT` row with local or L1
   data available.
2. `LDQInfo::loadRepick` marks that row `LDQ_REPICK`.
3. `LDQInfo::handleL1Lookup` sends the selected row to SCB and pushes the same
   request into `lookup_lu_su_q`.
4. `StoreUnit::handleLoadReq` pops `lookup_lu_su_q`, calls `stq.getData`, and
   returns the request through `lookup_su_lu_q`.

R392 adds only the request-issue boundary. The reduced top keeps
`requestEnable=false` and `sinkReady=false`, so `queryIssued` remains false
and the R391 evidence owner continues to report the missing live query instead
of changing source-return readiness.

## Interface

### Inputs

| Signal | Description |
|---|---|
| `enable` | Replay-LIQ wrapper is active. |
| `flush` | Store/replay flush suppresses query issue. |
| `requestEnable` | Future live-mode arm for issuing selected-row STQ snapshot requests. |
| `launchValid` | A selected replay row would need STQ snapshot evidence. |
| `sinkReady` | Future downstream STQ lookup/request sink can accept this request. |

### Outputs

| Signal | Description |
|---|---|
| `active` | `enable && !flush`. |
| `requestActive` | Live query issue has been enabled in an active cycle. |
| `queryCandidate` | Active selected replay row is visible to the query owner. |
| `queryValid` | Query request is live-enabled and has a selected row. |
| `queryIssued` | Query request is valid and the downstream sink is ready. |
| `blockedByDisabled` | Query request evidence appears while wrapper is disabled. |
| `blockedByFlush` | Query request evidence appears during flush. |
| `blockedByRequestDisabled` | A selected row needs a query, but live issue is disabled. |
| `blockedByNoLaunch` | Live issue is enabled without a selected row. |
| `blockedBySink` | A live valid query is waiting for downstream STQ lookup capacity. |

## State

The module is combinational. It does not store selected-row identity, allocate
queue entries, or observe STQ responses.

## Logic Design

The owner is intentionally a small request gate:

```text
active         = enable && !flush
requestActive  = active && requestEnable
queryCandidate = active && launchValid
queryValid     = requestActive && launchValid
queryIssued    = queryValid && sinkReady
```

`queryIssued` feeds `LoadReplaySourceReturnStoreSnapshotEvidence.queryIssued`
and the R393 `LoadReplaySourceReturnStoreSnapshotResponseMatch` owner through
the R395 composite path. The same path also instantiates the R394
identity-match consumer. In the current integration, `requestEnable`,
`sinkReady`, selected-row identity, and raw response inputs remain tied false
at the path boundary, so the evidence path still observes an unissued query
and no response evidence.

## Timing

The current integration is same-cycle and diagnostic only. A later live owner
must provide a real request payload and a valid/ready boundary for the STQ
lookup sink before enabling `requestEnable`.

## Flush/Recovery

Flush clears `active`, `requestActive`, `queryCandidate`, `queryValid`, and
`queryIssued`. Request evidence visible during flush is reported by
`blockedByFlush`.

## Deferred Owners

- Selected-row request payload and STQ lookup sink valid/ready connection.
- Raw STQ response queue and selected-row identity source into the R395 path.
- Wait-store row mutation and data merge.
- Live promotion of `LoadReplaySourceReturnStoreSnapshotReadyControl.requestEnable`.

## Verification

Focused gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadReplaySourceReturnStoreSnapshotQueryIssue
bash tools/chisel/run_chisel_tests.sh --only LoadReplaySourceReturnStoreSnapshotPath
bash tools/chisel/run_chisel_tests.sh --only LoadReplaySourceReturnStoreSnapshotResponseMatch
bash tools/chisel/run_chisel_tests.sh --only LoadReplaySourceReturnStoreSnapshotEvidence
bash tools/chisel/run_chisel_tests.sh --only LoadReplaySourceReturnStoreSnapshotReadyControl
bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop
FETCH_REDUCED_STORE_REPLAY_LIQ=1 BUILD_DIR=generated/r395x bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh
```

Reference tests cover disabled/flush suppression, disabled live request with a
selected row, enabled request with no selected row, sink backpressure, successful
issue, and Chisel elaboration.
