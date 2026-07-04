# LoadReplaySourceReturnStoreSnapshotRequestSink

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplaySourceReturnStoreSnapshotRequestSink.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/LoadReplaySourceReturnStoreSnapshotRequestSinkSpec.scala`
- Integrated user: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplaySourceReturnStoreSnapshotPath.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/mtccore/lsu/load_unit/ldq.cpp`
    - `MtcLDQInfo::handleL1Lookup`
    - `MtcLDQInfo::handleSTQReceive`
  - `model/LinxCoreModel/model/mtccore/lsu/store_unit/store_unit.cpp`
    - `MtcStoreUnit::handleLoadReq`
  - `model/LinxCoreModel/model/mtccore/lsu/store_unit/stq.cpp`
    - `MtcSTQ::lookupForLoad`
- Related Chisel contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplaySourceReturnStoreSnapshotRequestPayload.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplaySourceReturnStoreSnapshotRequestQueue.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplaySourceReturnStoreSnapshotResponsePayload.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplaySourceReturnStoreSnapshotLookup.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplaySourceReturnStoreSnapshotResponseQueue.scala`
- Contract IDs: `LC-CHISEL-LSU-REPLAY-STQ-SNAPSHOT-REQUEST-SINK-001`

## Purpose

`LoadReplaySourceReturnStoreSnapshotRequestSink` is the typed handoff between
the queued selected-row request and the ordered STQ snapshot response shape.

The model store unit drains `lookup_lu_su_q`, calls `MtcSTQ::lookupForLoad`,
mutates the same `MemReqBus`, and pushes it into `lookup_su_lu_q`. R404 adds
the corresponding Chisel request-consume and response-produce boundary inside
the composite source-return path. It does not yet perform resident STQ byte
lookup, data merge, wait-store row mutation, or full LIQ/LDQ state mutation.

R406 widens the produced response from the original `cID/eID`, `waitStore`,
and `dataValid` booleans into `LoadReplaySourceReturnStoreSnapshotResponsePayloadBundle`.
The sink now carries wait-store BID/RID/LSID/PC and 64-byte data mask/data
sidebands from the R405 lookup into the response queue while preserving the
old scalar outputs for existing response matching.

## Interface

### Inputs

| Signal | Description |
|---|---|
| `enable` | Composite source-return path is active. |
| `flush` | Suppresses request consumption and response generation. |
| `requestValid` / `request` | Visible R403 request-queue head. |
| `rawSinkReady` | Future store-unit lookup sink readiness. Current top ties this false. |
| `responseReady` | Existing response queue has storage space for the generated `lookup_su_lu_q` response. In the R404 composite this is the queue `!full` predicate, not enqueue-ready-with-pop. |
| `lookupWaitStore` | STQ lookup result asks the load to wait on a store. R405 drives this from `LoadReplaySourceReturnStoreSnapshotLookup.waitStoreValid`. |
| `lookupWaitStoreInfo` | Selected not-ready store index, BID, LSID, and PC from `LoadStoreForwarding`. |
| `lookupWaitStoreRid` | Selected not-ready store ROB RID recovered from the current `STQEntryBankRow`. This maps to model `MemReqBus.wait_rid`. |
| `lookupRawDataValid` | Raw ready-byte evidence from the lookup, even when wait-store suppresses merge. |
| `lookupDataValid` | Response-visible store-data evidence. R405 drives this from `Lookup.responseDataValid`, not raw `data_vld`, so wait-store rewait wins before data merge. |
| `lookupDataSuppressedByWait` | Raw data existed but wait-store control won. |
| `lookupDataMask` / `lookupData` | Store-only byte mask and 64-byte line data returned by the lookup. |

### Outputs

| Signal | Description |
|---|---|
| `requestReady` | Request queue may consume its visible head. |
| `requestAccepted` | Request head was consumed by the sink. |
| `response` | Full R406 response payload record for `LoadReplaySourceReturnStoreSnapshotResponseQueue`. |
| `responseValid` | A response record should enqueue into the response queue. |
| `responseClusterId` / `responseEntryId` | Returned row identity copied from the consumed request. |
| `responseWaitStore` / `responseDataValid` | Returned STQ lookup sidebands. |
| `responseRawDataValid` / `responseDataSuppressedByWait` | Raw-data diagnostics preserved for future row mutation/debug. |
| `responseWaitStore*` | Wait-store index, BID, RID, LSID, and PC copied into the response payload. |
| `responseDataMask` / `responseData` | Store-data valid mask and line-positioned data copied into the response payload. |
| `blockedBy*` | Disabled, flush, no-request, raw-sink, and response-queue blockers. |
| `invalidDataWithWaitStore` | Diagnostic for a future lookup result that asserts both wait-store and data-valid. |

## State

The module is combinational. Queue residency remains owned by
`LoadReplaySourceReturnStoreSnapshotRequestQueue`, and response ordering remains
owned by `LoadReplaySourceReturnStoreSnapshotResponseQueue`.

## Logic Design

The sink accepts a request only when all of these predicates hold:

- the path is active,
- the request queue presents a valid request head,
- the raw store-unit lookup sink is ready,
- and the response queue can accept the generated response.

On acceptance, the response identity is copied from the request `clusterId` and
`entryId`, matching the model's mutation of the same `MemReqBus` between
`lookup_lu_su_q` and `lookup_su_lu_q`.

R406 also copies the model-equivalent return sidebands:

- `lookupWaitStoreInfo.storeId` -> `waitStoreBid`
- `lookupWaitStoreRid` -> `waitStoreRid`
- `lookupWaitStoreInfo.storeLsId` -> `waitStoreLsId`
- `lookupWaitStoreInfo.pc` -> `waitStorePc`
- `lookupDataMask` / `lookupData` -> store-only returned data payload

The composite deliberately drives `responseReady` from response FIFO space
rather than from the FIFO's `enqueueReady`. `enqueueReady` may include
same-cycle resident-head drain, and that drain depends on response matching.
Using it here would feed request acceptance into response enqueue, response
head bypass, response drain, and back into request acceptance.

R405 drives `lookupWaitStore` and `lookupDataValid` from the resident-STQ
lookup owner. A lookup can still produce no-wait/no-data, matching the model
case where no older resident store overlaps the replay request. Raw ready-byte
evidence remains visible at the lookup owner as `rawDataValid`; the sink only
receives response-visible data evidence after wait-store suppression.

R406 keeps raw data evidence in the response payload even when wait-store
suppresses merge. Future row mutation must use `response.dataValid`, not
`response.rawDataValid`, when deciding whether to merge returned data.

## Flush/Recovery

`flush` blocks request acceptance and response generation. Precise queued
request pruning remains owned by future row-state work; this sink only consumes
the visible request head.

## Deferred Owners

- Wait-store row mutation from the R406 response payload.
- Store-data merge into replay row line data from the R406 response payload.
- Precise queued-request flush pruning beyond all-clear flush.
- Live promotion of `requestEnable` and raw `sinkReady`.

## Verification

Focused gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadReplaySourceReturnStoreSnapshotRequestSink
bash tools/chisel/run_chisel_tests.sh --only LoadReplaySourceReturnStoreSnapshotLookup
bash tools/chisel/run_chisel_tests.sh --only LoadReplaySourceReturnStoreSnapshotPath
bash tools/chisel/run_chisel_tests.sh --only LoadReplaySourceReturnStoreSnapshotRequestQueue
bash tools/chisel/run_chisel_tests.sh --only LoadReplaySourceReturnStoreSnapshotResponseQueue
bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop
FETCH_REDUCED_STORE_REPLAY_LIQ=1 BUILD_DIR=generated/r406x bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh
```

Reference tests cover request acceptance only with both sink and response
readiness, independent raw-sink and response backpressure, wait-store/data-valid
payload diagnostics, disabled/flush blockers, and Chisel elaboration.
