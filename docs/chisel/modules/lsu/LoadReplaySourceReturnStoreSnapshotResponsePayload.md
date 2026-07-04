# LoadReplaySourceReturnStoreSnapshotResponsePayload

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplaySourceReturnStoreSnapshotResponsePayload.scala`
- Tests:
  - `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/LoadReplaySourceReturnStoreSnapshotRequestSinkSpec.scala`
  - `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/LoadReplaySourceReturnStoreSnapshotResponseQueueSpec.scala`
- Integrated users:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplaySourceReturnStoreSnapshotPath.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplaySourceReturnStoreSnapshotRequestSink.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplaySourceReturnStoreSnapshotResponseQueue.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplaySourceReturnStoreSnapshotResponseApply.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/ModelCommon/bus/MemReqBus.h`
    - `MemReqBus::cID`
    - `MemReqBus::eID`
    - `MemReqBus::wait_store`
    - `MemReqBus::wait_bid`
    - `MemReqBus::wait_rid`
    - `MemReqBus::wait_tpc`
    - `MemReqBus::mtc_reqData`
  - `model/LinxCoreModel/model/mtccore/lsu/store_unit/stq.cpp`
    - `MtcSTQ::lookupForLoad`
  - `model/LinxCoreModel/model/mtccore/lsu/load_unit/ldq.cpp`
    - `MtcLDQInfo::handleSTQReceive`
    - `MtcLDQInfo::waitStore`
    - `MtcLDQInfo::handleMerge`
- Contract IDs: `LC-CHISEL-LSU-REPLAY-STQ-SNAPSHOT-RESPONSE-PAYLOAD-001`

## Purpose

`LoadReplaySourceReturnStoreSnapshotResponsePayloadBundle` is the typed Chisel
shape for the store-unit response returned to replay-LIQ local STQ snapshot
matching.

R406 keeps the existing response-valid, wait-store, and data-valid booleans,
then adds the model sidebands that a later LIQ row-mutation owner needs:
wait-store identity, raw data evidence, response-visible data evidence, a
64-byte byte-valid mask, and 64-byte store data.

R420 wires the same full payload shape to the composite path's raw external
response boundary. The current reduced top still ties those raw inputs inactive,
but the path no longer fabricates placeholder raw-data and wait-store fields
when a future `lookup_su_lu_q` source presents a response.

## Interface

| Field | Description |
|---|---|
| `valid` | Payload record is meaningful. |
| `clusterId` / `entryId` | Returned `MemReqBus.cID/eID` identity. |
| `waitStore` | Store lookup found an older not-ready store for at least one requested byte. |
| `dataValid` | Store data may be merged by `handleSTQReceive`; this is suppressed when `waitStore` is true. |
| `rawDataValid` | Store lookup found at least one ready resident-store byte, even if wait-store control wins. |
| `dataSuppressedByWait` | Diagnostic for raw data that is ignored because `waitStore` takes priority. |
| `waitStoreIndex` | Local STQ row index for the selected not-ready store. |
| `waitStoreBid` / `waitStoreRid` | Model wait-store BID/RID identity. |
| `waitStoreLsId` | Store LSID, retained for existing wait-store wakeup matching. |
| `waitStorePc` | Model wait-store `tpc`/store PC. |
| `dataMask` | Valid store-data bytes in `data`. |
| `data` | 64-byte line-positioned store data. |

## Logic Design

The bundle has no logic. `LoadReplaySourceReturnStoreSnapshotRequestSink`
constructs it from the accepted request identity plus R405 lookup sidebands,
and `LoadReplaySourceReturnStoreSnapshotPath` constructs the same shape from
raw external response inputs. `LoadReplaySourceReturnStoreSnapshotResponseQueue`
preserves the full record in FIFO order while existing response matching still
consumes only `cID/eID`, `waitStore`, and `dataValid`.

The payload intentionally carries both `rawDataValid` and `dataValid`. The C++
STQ lookup can discover ready bytes and a later not-ready store in the same
response, but `MtcLDQInfo::handleSTQReceive` handles `wait_store` first and
returns before data merge.

R407 adds `LoadReplaySourceReturnStoreSnapshotResponseApply` as the first
consumer of the full payload. It converts ordered queue-head payloads into
wait-store and data-merge intent without mutating LIQ rows yet.

## Deferred Owners

- Registered LIQ/LDQ wait-store row mutation using the R407 apply intent.
- Registered LIQ/LDQ data merge using the R407 apply intent.
- Live raw external `lookup_su_lu_q` source wiring.
- Precise queued response pruning.

## Verification

Focused gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadReplaySourceReturnStoreSnapshotRequestSink
bash tools/chisel/run_chisel_tests.sh --only LoadReplaySourceReturnStoreSnapshotResponseQueue
bash tools/chisel/run_chisel_tests.sh --only LoadReplaySourceReturnStoreSnapshotPath
bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop
```

Reference tests cover payload production from wait-store/data lookup
sidebands, FIFO preservation of wait identity and data mask/data, empty-queue
bypass, full-queue pop/push, disabled/flush behavior, and Chisel elaboration.
