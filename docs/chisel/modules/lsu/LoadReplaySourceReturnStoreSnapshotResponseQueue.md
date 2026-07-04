# LoadReplaySourceReturnStoreSnapshotResponseQueue

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplaySourceReturnStoreSnapshotResponseQueue.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/LoadReplaySourceReturnStoreSnapshotResponseQueueSpec.scala`
- Integrated user: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplaySourceReturnStoreSnapshotPath.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/lsu/store_unit/store_unit.cpp`
    - `StoreUnit::handleLoadReq`
    - `lookup_su_lu_q->push_back(loadReq)`
  - `model/LinxCoreModel/model/mtccore/lsu/load_unit/ldq.cpp`
    - `MtcLDQInfo::Work`
    - `lookup_su_lu_q->front()`
    - `lookup_su_lu_q->pop_front()`
    - `MtcLDQInfo::handleSTQReceive`
    - `MtcLDQInfo::flush`
  - `model/LinxCoreModel/model/ModelCommon/bus/MemReqBus.h`
    - `MemReqBus::cID`
    - `MemReqBus::eID`
    - `MemReqBus::wait_store`
    - `MemReqBus::wait_bid`
    - `MemReqBus::wait_rid`
    - `MemReqBus::wait_tpc`
    - `MemReqBus::mtc_reqData`
- Related Chisel contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplaySourceReturnStoreSnapshotResponsePayload.scala`
- Contract IDs: `LC-CHISEL-LSU-REPLAY-STQ-SNAPSHOT-RESPONSE-QUEUE-001`

## Purpose

`LoadReplaySourceReturnStoreSnapshotResponseQueue` is the raw store-unit
response FIFO boundary in front of local STQ snapshot response matching.

The model returns local STQ lookup results through `lookup_su_lu_q`.
`StoreUnit::handleLoadReq` pushes a `MemReqBus` into that deque after
`stq.getData`, and `MtcLDQInfo::Work` later drains the deque head in FIFO
order before calling `handleSTQReceive`. Flush removes matching `MemReqBus`
records from this queue.

R398 inserts this owner inside
`LoadReplaySourceReturnStoreSnapshotPath`. The current top still ties raw
response inputs false, so this packet does not make replay launch live. It
does give the composite a real valid/ready boundary for future raw STQ
responses without adding another direct child to the oversized top.

R399 moves the pop decision into
`LoadReplaySourceReturnStoreSnapshotResponseDrain`. The queue remains only the
raw FIFO/bypass owner; it does not decide whether a head is ordered or stale.

R406 widens the queue entry to
`LoadReplaySourceReturnStoreSnapshotResponsePayloadBundle`. Existing response
matching still consumes `headClusterId`, `headEntryId`, `headWaitStore`, and
`headDataValid`, while the FIFO now also preserves wait-store identity and
store-data mask/data for the later LIQ row-mutation owner.

## Interface

### Inputs

| Signal | Description |
|---|---|
| `enable` | Replay-LIQ wrapper is active. |
| `flush` | Clears resident raw STQ responses and suppresses admission. |
| `enqueueValid` | A raw STQ response `MemReqBus` is visible. |
| `enqueue` | Full response payload carrying `cID/eID`, wait-store identity, raw data evidence, and store data mask/data. |
| `dequeueReady` | Downstream drain owner consumed or explicitly dropped the current head. |

### Outputs

| Signal | Description |
|---|---|
| `enqueueReady` | Queue can accept the raw response this cycle. |
| `enqueueAccepted` | Raw response was accepted into the FIFO or empty-slot bypass. |
| `enqueueDropped` | One-cycle raw response arrived without space or while disabled. |
| `headValid` | Resident or empty-slot bypass response is visible to identity matching. |
| `head` | Full R406 response payload at the FIFO head. |
| `headClusterId` | Head response `cID`. |
| `headEntryId` | Head response `eID`. |
| `headWaitStore` | Head wait-store sideband. |
| `headDataValid` | Head data-valid sideband. |
| `headRawDataValid` / `headDataSuppressedByWait` | Raw-data diagnostics preserved across FIFO storage. |
| `headWaitStore*` | Head wait-store index, BID, RID, LSID, and PC. |
| `headDataMask` / `headData` | Head store-data valid mask and 64-byte line data. |
| `headConsumed` | Visible head was consumed this cycle. |
| `pending` | A resident response remains after this cycle. |
| `full` | Resident queue occupancy is at depth. |
| `empty` | Resident queue occupancy is zero. |
| `count` | Resident queue occupancy. |
| `blockedByDisabled` | Raw response arrived while disabled. |
| `blockedByFlush` | Raw response arrived during flush. |
| `blockedByFull` | Raw response arrived while no resident slot was available. |

## State

The module owns a circular FIFO of response records:

```text
clusterId
entryId
waitStore
dataValid
rawDataValid
dataSuppressedByWait
waitStoreIndex
waitStoreBid
waitStoreRid
waitStoreLsId
waitStorePc
dataMask
data
```

It does not own selected-row token identity, SCB-before-STQ ordering,
stale-row proof, wait-store mutation, or returned-data merge.

## Logic Design

The queue preserves model FIFO order and supports same-cycle pop/push:

```text
popResident     = active && residentHeadValid && dequeueReady
enqueueReady    = active && (!full || popResident)
enqueueAccepted = enqueueValid && enqueueReady
```

If the resident FIFO is empty and a response is accepted, the response also
bypasses to the current head. If downstream consumes that bypassed response in
the same cycle, the record is not written into resident state. This preserves
the previous same-cycle R397 path behavior while introducing storage for later
multi-cycle raw response return.

`headValid` is intentionally independent of `dequeueReady`; only storage and
occupancy depend on downstream readiness. This avoids a combinational loop
when the downstream response matcher drives `dequeueReady`.
As of R399, `dequeueReady` comes from `LoadReplaySourceReturnStoreSnapshotResponseDrain`,
which may pop an ordered head or a future explicitly stale head.

## Timing

The path consumes `headValid/head*` in the same cycle for identity and
response matching. Resident dequeue happens after the downstream ordered
response is accepted. Full queues can still accept a new response when the
resident head is consumed in the same cycle.

## Flush/Recovery

Flush clears all resident entries, hides the head, and suppresses admission.
The model has a more selective `FlushBus` match over queued `MemReqBus`
records; this reduced packet uses the existing path-level flush until full row
identity and precise queued-response pruning are promoted.

## Deferred Owners

- Live raw STQ response source into the path boundary.
- Full multi-cluster stale-row proof beyond the R400 reduced `repickMask`
  owner.
- Multi-token or multi-row response ownership beyond the current one-token
  accepted-query path.
- Wait-store row mutation and returned-data merge.

## Verification

Focused gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadReplaySourceReturnStoreSnapshotResponseQueue
bash tools/chisel/run_chisel_tests.sh --only LoadReplaySourceReturnStoreSnapshotPath
```

Reference tests cover FIFO order, empty-queue bypass plus same-cycle consume,
full-queue simultaneous pop/push, full-queue drop diagnostics, flush clearing,
disabled-response diagnostics, preservation of wait-store identity and data
mask/data sidebands, and Chisel elaboration.
