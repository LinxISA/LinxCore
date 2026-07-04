# LoadReplaySourceReturnStoreSnapshotRequestQueue

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplaySourceReturnStoreSnapshotRequestQueue.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/LoadReplaySourceReturnStoreSnapshotRequestQueueSpec.scala`
- Integrated user: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplaySourceReturnStoreSnapshotPath.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/mtccore/lsu/load_unit/ldq.cpp`
    - `MtcLDQInfo::handleL1Lookup`
    - `MtcLDQInfo::flush`
  - `model/LinxCoreModel/model/mtccore/lsu/store_unit/store_unit.cpp`
    - `MtcStoreUnit::handleLoadReq`
    - `lookup_lu_su_q`
  - `model/LinxCoreModel/model/mtccore/lsu/store_unit/stq.cpp`
    - `MtcSTQ::lookupForLoad`
- Related Chisel contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplaySourceReturnStoreSnapshotRequestPayload.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplaySourceReturnStoreSnapshotRequestControl.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplaySourceReturnStoreSnapshotRequestSink.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplaySourceReturnStoreSnapshotPath.scala`
- Contract IDs: `LC-CHISEL-LSU-REPLAY-STQ-SNAPSHOT-REQUEST-QUEUE-001`

## Purpose

`LoadReplaySourceReturnStoreSnapshotRequestQueue` is the local request storage
boundary corresponding to the model `lookup_lu_su_q` handoff from LDQ to store
unit.

The model pushes a selected repick load request from
`MtcLDQInfo::handleL1Lookup` into `lookup_lu_su_q`. `MtcStoreUnit::handleLoadReq`
later drains all visible requests, calls `MtcSTQ::lookupForLoad`, and pushes the
mutated request into `lookup_su_lu_q`.

R403 adds only the request queue. It does not perform STQ data lookup, create a
raw store-unit sink, source raw responses, or mutate LIQ row state. The current
top still ties live request enable false.

R404 consumes this queue through `LoadReplaySourceReturnStoreSnapshotRequestSink`.
The queue still owns only FIFO residency: the sink decides whether the visible
head can be consumed based on raw store-unit sink readiness and response-queue
capacity.

## Interface

### Inputs

| Signal | Description |
|---|---|
| `enable` | Composite source-return path is active. |
| `flush` | Clears resident queued requests and suppresses admission. |
| `enqueueValid` | R402 request payload is valid after query issue. |
| `enqueueRequest` | Typed selected-row request payload. |
| `dequeueReady` | R404 request sink can consume the head. |

### Outputs

| Signal | Description |
|---|---|
| `enqueueReady` | Queue has capacity, including same-cycle head drain from a full queue. |
| `enqueueAccepted` | Input payload entered the queue or bypassed to a consuming sink. |
| `enqueueDropped` | Payload was visible while disabled, flushed, or full. |
| `headValid` / `head` | Visible FIFO head or empty-queue bypass payload. |
| `headConsumed` | Visible head was accepted by `dequeueReady`. |
| `pending` / `full` / `empty` / `count` | Queue occupancy diagnostics. |
| `blockedByDisabled` / `blockedByFlush` / `blockedByFull` | Admission blockers. |

## State

The queue owns a finite FIFO of R402 request payloads, plus head/tail pointers
and occupancy count. It clears all state on `flush`.

## Logic Design

The queue preserves FIFO order and supports two important timing cases:

- Empty-queue bypass: a new request can be visible at the head and consumed in
  the same cycle without becoming resident.
- Full-queue replacement: if the resident head is consumed, a new enqueue may
  use the freed slot in the same cycle.

Head visibility depends only on resident occupancy or empty bypass. It does not
depend on downstream `dequeueReady`; drain readiness only changes pointers and
count. This follows the response-queue discipline that avoids valid/ready
combinational cycles.

## Flush/Recovery

`flush` clears all resident requests and blocks same-cycle enqueue. More precise
queued-request suffix pruning remains deferred until the full row-state and
multi-token request path exists.

## Deferred Owners

- Resident STQ data lookup behind the request sink.
- Raw `lookup_su_lu_q` response source.
- Precise queued-request pruning beyond all-clear flush.
- Multi-token accepted-query queueing.
- Live promotion of `requestEnable`.

## Verification

Focused gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadReplaySourceReturnStoreSnapshotRequestQueue
bash tools/chisel/run_chisel_tests.sh --only LoadReplaySourceReturnStoreSnapshotRequestSink
bash tools/chisel/run_chisel_tests.sh --only LoadReplaySourceReturnStoreSnapshotPath
bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop
FETCH_REDUCED_STORE_REPLAY_LIQ=1 BUILD_DIR=generated/r404x bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh
```

Reference tests cover FIFO order, empty-queue same-cycle bypass, full-queue
enqueue after resident drain, full/disabled/flush blockers, and Chisel
elaboration.
