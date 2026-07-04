# LoadReplaySourceReturnStoreSnapshotResponseMatch

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplaySourceReturnStoreSnapshotResponseMatch.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/LoadReplaySourceReturnStoreSnapshotResponseMatchSpec.scala`
- Integrated user: `rtl/LinxCore/chisel/src/main/scala/linxcore/top/LinxCoreFrontendFetchRfAluTraceTop.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/lsu/load_unit/ldq.cpp`
    - `LDQInfo::handleSTQReceive`
    - `LDQInfo::waitStore`
    - `LDQInfo::handleMerge`
    - `LDQInfo::pickL1`
  - `model/LinxCoreModel/model/lsu/store_unit/store_unit.cpp`
    - `StoreUnit::handleLoadReq`
  - `model/LinxCoreModel/model/lsu/store_unit/stq.cpp`
    - `STQ::lookupForLoad`
- Related Chisel contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplaySourceReturnStoreSnapshotIdentityMatch.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplaySourceReturnStoreSnapshotQueryIssue.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplaySourceReturnStoreSnapshotEvidence.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplaySourceReturnStoreSnapshotReadyControl.scala`
- Contract IDs: `LC-CHISEL-LSU-REPLAY-STQ-SNAPSHOT-RESPONSE-MATCH-001`

## Purpose

`LoadReplaySourceReturnStoreSnapshotResponseMatch` names the response side of
the selected-row STQ snapshot request used by replay-LIQ source-return
readiness.

The model path is:

1. `LDQInfo::handleL1Lookup` pushes the selected `LDQ_REPICK` row into
   `lookup_lu_su_q`.
2. `StoreUnit::handleLoadReq` calls `stq.getData` and returns the same
   `MemReqBus` through `lookup_su_lu_q`.
3. `LDQInfo::handleSTQReceive` indexes the row by `cID/eID`, ignores rows that
   are no longer `LDQ_REPICK`, asserts that `scbRnt` has already returned, and
   then marks `stqRnt`.
4. A `wait_store` response calls `LDQInfo::waitStore`; otherwise `data_vld`
   controls whether `LDQInfo::handleMerge` merges returned store bytes.

R393 adds only the response-match and ordering boundary. R394 names the
identity source for `responseMatchesSelected`, and R395 composes both owners
inside `LoadReplaySourceReturnStoreSnapshotPath`. The reduced top still ties
raw STQ response inputs, selected-row identity, and SCB-return evidence false
at the path boundary, so `responseValid`, `waitStore`, and `dataValid` remain
false at
`LoadReplaySourceReturnStoreSnapshotEvidence`.

## Interface

### Inputs

| Signal | Description |
|---|---|
| `enable` | Replay-LIQ wrapper is active. |
| `flush` | Store/replay flush suppresses response matching. |
| `queryIssued` | The selected-row STQ snapshot query has issued. |
| `responseValidIn` | Future raw STQ response is visible. |
| `responseMatchesSelected` | Future identity check says the response targets the selected replay row. |
| `scbReturned` | Future SCB source-return evidence has already arrived. |
| `waitStoreIn` | Raw response asks the row to wait on a store. |
| `dataValidIn` | Raw response carries mergeable store data. |

### Outputs

| Signal | Description |
|---|---|
| `active` | `enable && !flush`. |
| `responseCandidate` | Active raw STQ response is visible. |
| `responseMatched` | Candidate response has an issued query and matches the selected row. |
| `responseOrdered` | Matched response also satisfies SCB-before-STQ ordering. |
| `responseValid` | Ordered response forwarded to the evidence classifier. |
| `waitStore` | Ordered wait-store evidence forwarded to the evidence classifier. |
| `dataValid` | Ordered data-valid evidence forwarded to the evidence classifier. |
| `blockedByDisabled` | Raw response evidence appears while wrapper is disabled. |
| `blockedByFlush` | Raw response evidence appears during flush. |
| `blockedByNoQuery` | Response appears before the selected-row query has issued. |
| `blockedByNoMatch` | Response appears for a non-selected or stale row. |
| `blockedByScbOrder` | Response matches the selected row before SCB return evidence. |
| `invalidResponseWithoutQuery` | Raw response is visible without an issued query. |
| `invalidDataWithWaitStore` | Ordered response carries both wait-store and data evidence. |

## State

The module is combinational. It does not store selected-row identity or mutate
LIQ/LDQ state. R395 feeds `responseMatchesSelected` from the R394 identity
matcher inside the composite path; future live integration must still supply
selected-row identity state and a raw STQ response queue owner before this top
can consume it.

## Logic Design

The owner mirrors the model response acceptance order without changing state:

```text
active            = enable && !flush
responseCandidate = active && responseValidIn
responseHasQuery  = responseCandidate && queryIssued
responseMatched   = responseHasQuery && responseMatchesSelected
responseOrdered   = responseMatched && scbReturned
responseValid     = responseOrdered
```

Only ordered responses feed the R391 evidence owner. Wait-store and data-valid
sidebands are forwarded only for ordered responses, while diagnostics expose
no-query, stale/unmatched, and SCB-order blockers.

## Timing

The current reduced top uses this module as a dormant same-cycle diagnostic
owner. Live response queueing, selected-row identity storage, and replay-row
mutation remain future owners.

## Flush/Recovery

Flush clears candidate, match, ordered, and forwarded response evidence.
Response evidence visible during flush is reported through `blockedByFlush`.

## Deferred Owners

- Raw STQ response queue and valid/ready boundary.
- Selected replay-row identity storage and live `cID/eID` response source into
  R394 identity matching.
- Live SCB-return evidence into the response-ordering input.
- Stateful wait-store rewait and returned-data merge into replay row state.
- Live promotion of `LoadReplaySourceReturnStoreSnapshotReadyControl.requestEnable`.

## Verification

Focused gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadReplaySourceReturnStoreSnapshotResponseMatch
bash tools/chisel/run_chisel_tests.sh --only LoadReplaySourceReturnStoreSnapshotPath
bash tools/chisel/run_chisel_tests.sh --only LoadReplaySourceReturnStoreSnapshotQueryIssue
bash tools/chisel/run_chisel_tests.sh --only LoadReplaySourceReturnStoreSnapshotEvidence
bash tools/chisel/run_chisel_tests.sh --only LoadReplaySourceReturnStoreSnapshotReadyControl
bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop
FETCH_REDUCED_STORE_REPLAY_LIQ=1 BUILD_DIR=generated/r395x bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh
```

Reference tests cover disabled/flush suppression, no-query blocking, unmatched
response suppression, SCB-order blocking, ordered wait-store/data evidence, a
malformed wait-store-with-data payload, and Chisel elaboration.
