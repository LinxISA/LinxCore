# LoadReplaySourceReturnStoreSnapshotRawResponseSource

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplaySourceReturnStoreSnapshotRawResponseSource.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/LoadReplaySourceReturnStoreSnapshotRawResponseSourceSpec.scala`
- Integrated user: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplaySourceReturnStoreSnapshotPath.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/ModelCommon/bus/MemReqBus.h`
    - `MemReqBus::bid`
    - `MemReqBus::gid`
    - `MemReqBus::rid`
    - `MemReqBus::lsID`
    - `MemReqBus::cID`
    - `MemReqBus::eID`
    - `MemReqBus::wait_store`
    - `MemReqBus::wait_tpc`
    - `MemReqBus::wait_bid`
    - `MemReqBus::wait_rid`
    - `MemReqBus::data_vld`
    - `MemReqBus::mtc_reqData`
  - `model/LinxCoreModel/model/mtccore/lsu/store_unit/store_unit.cpp`
    - `MtcStoreUnit::handleLoadReq`
    - `lookup_su_lu_q->push_back(loadReq)`
  - `model/LinxCoreModel/model/mtccore/lsu/store_unit/stq.cpp`
    - `MtcSTQ::getData`
    - `MtcSTQ::lookupForLoad`
  - `model/LinxCoreModel/model/mtccore/lsu/load_unit/ldq.cpp`
    - `MtcLDQInfo::handleSTQReceive`
    - `MtcLDQInfo::waitStore`
    - `MtcLDQInfo::handleMerge`
- Related Chisel contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplaySourceReturnStoreSnapshotResponsePayload.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplaySourceReturnStoreSnapshotResponseQueue.scala`
- Contract IDs: `LC-CHISEL-LSU-REPLAY-STQ-SNAPSHOT-RAW-RESPONSE-SOURCE-001`

## Purpose

`LoadReplaySourceReturnStoreSnapshotRawResponseSource` is the live gate and
payload shaper for raw store-unit responses entering the replay-LIQ local STQ
snapshot path.

The model stores the STQ lookup result in the same `MemReqBus` that came from
the load unit. `MtcStoreUnit::handleLoadReq` calls `MtcSTQ::getData` and then
pushes that bus into `lookup_su_lu_q`; `MtcLDQInfo::handleSTQReceive` later
uses `cID/eID`, checks `wait_store` before merge, and consumes `data_vld` plus
`mtc_reqData` only when no wait-store rewait is needed.

R421 inserts this owner between the path's raw response inputs and
`LoadReplaySourceReturnStoreSnapshotResponseQueue`. It adds an explicit
`liveEnable` arm so raw `lookup_su_lu_q`-shaped candidates cannot occupy the
response queue until the rest of the replay source-return policy is ready. The
current top ties `liveEnable=false`, so this packet is structural and does not
make raw STQ responses observable in generated top behavior.

R422 widens the raw candidate with original load request identity. The model
stores the STQ result in the same `MemReqBus` that came from the load unit, so
the raw response boundary must carry both the load identity
(`requestBid/requestGid/requestRid/requestLoadLsId`) and any wait-store
sideband.

## Interface

### Inputs

| Signal | Description |
|---|---|
| `enable` | Replay-LIQ source-return path is active. |
| `flush` | Suppresses raw response visibility. |
| `liveEnable` | Future live-mode arm for allowing raw STQ responses into the response queue. |
| `rawValid` | A raw store-unit response candidate is visible. |
| `clusterId` / `entryId` | Returned `MemReqBus.cID/eID` identity. |
| `requestBid` / `requestGid` / `requestRid` / `requestLoadLsId` | Original load request identity from the returned `MemReqBus`; this is the future queued-response flush identity. |
| `waitStore` | Raw response asks the target row to rewait on a not-ready store. |
| `dataValid` | Raw response has model-visible merge data. |
| `rawDataValid` | Raw response found resident-store bytes before wait-store suppression. |
| `dataSuppressedByWait` | Ready bytes were found but wait-store control wins. |
| `waitStoreIndex` | Local STQ index for the selected not-ready store. |
| `waitStoreBid` / `waitStoreRid` | Wait-store BID/RID identity. |
| `waitStoreLsId` | Wait-store LSID retained for later wakeup matching. |
| `waitStorePc` | Wait-store PC from model `wait_tpc`. |
| `dataMask` / `data` | 64-byte returned store-data mask and line-positioned data. |

### Outputs

| Signal | Description |
|---|---|
| `active` | `enable && !flush`. |
| `candidate` | Active raw response candidate is visible. |
| `responseValid` | Candidate is live-enabled and may enter the response queue. |
| `response` | Full `LoadReplaySourceReturnStoreSnapshotResponsePayloadBundle`, zero when not valid. |
| `blockedByDisabled` | Raw response appeared while disabled. |
| `blockedByFlush` | Raw response appeared during flush. |
| `blockedByLiveDisabled` | Active raw response appeared while live raw sourcing is still disabled. |
| `invalidDataWithWaitStore` | Candidate sets both wait-store and response-visible data. |
| `invalidDataValidWithoutRawData` | Candidate sets data-valid without raw-data evidence. |
| `invalidSuppressedDataWithoutWait` | Candidate reports wait suppression without wait-store. |
| `invalidSuppressedDataWithoutRawData` | Candidate reports wait suppression without raw-data evidence. |

## State

The module is combinational. It owns no FIFO state and performs no stale-row,
SCB-ordering, wait-store mutation, or returned-data merge policy.

## Logic Design

The source owner separates raw response visibility from live admission:

```text
active        = enable && !flush
candidate     = active && rawValid
responseValid = candidate && liveEnable
```

When `responseValid` is true, the module copies the full raw sideband set into
`LoadReplaySourceReturnStoreSnapshotResponsePayloadBundle`. When it is false,
the emitted payload is zero and the path can still admit a request-sink
generated response into the queue.

The raw sideband set keeps load request identity separate from wait-store
identity. `FlushBus::match(MemReqBus)` applies to the returned load request
fields; wait-store fields are consumed later by row mutation and wakeup logic.

The consistency diagnostics are candidates, not admission blockers. They
remain visible while `liveEnable=false` so future wiring can be checked before
raw responses are allowed to affect replay source-return readiness.

## Timing

The owner is same-cycle. Its `responseValid` output arbitrates the path's
single response-queue enqueue port against the local request-sink generated
response.

## Flush/Recovery

Flush clears `active`, `candidate`, and `responseValid`. A raw candidate
visible during flush is reported through `blockedByFlush`.

## Deferred Owners

- Live external `lookup_su_lu_q` producer wiring into this source owner.
- Precise queued-response pruning after a raw response has entered the FIFO.
- Full multi-cluster stale-row evidence and multi-token response ownership.
- Registered LIQ/LDQ wait-store mutation and returned-data merge.

## Verification

Focused gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadReplaySourceReturnStoreSnapshotRawResponseSource
bash tools/chisel/run_chisel_tests.sh --only LoadReplaySourceReturnStoreSnapshotPath
```

Reference tests cover live request-identity and payload preservation,
live-disabled candidate blocking, disabled/flush suppression, malformed-payload
diagnostics, composite enqueue priority, and Chisel elaboration.
