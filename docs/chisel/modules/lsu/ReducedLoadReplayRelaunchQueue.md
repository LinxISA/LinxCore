# ReducedLoadReplayRelaunchQueue

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/ReducedLoadReplayRelaunchQueue.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/ReducedLoadReplayRelaunchQueueSpec.scala`
- Integrated user: `rtl/LinxCore/chisel/src/main/scala/linxcore/top/LinxCoreFrontendFetchRfAluTraceTop.scala`
- LinxCoreModel evidence:
  - `tools/LinxCoreModel/model/lsu/load_unit/ldq.cpp`
    - `LDQInfo::waitStore`
    - `LDQInfo::handleSUWakeup`
  - `tools/LinxCoreModel/model/lsu/store_unit/stq.cpp`
    - `STQ::lookupForLoad`
- Related Chisel contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/ReducedLoadWaitReplaySlot.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/ReducedLoadReplayCompletionDrain.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/ResidentStoreReplayWakeup.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayWakeup.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadInflightQueue.scala`
- Contract IDs: `LC-CHISEL-LSU-STQ-FWD-005`

## Purpose

`ReducedLoadReplayRelaunchQueue` is the first owner after
`ReducedLoadWaitReplaySlot` that consumes a wait-store-clear relaunch
candidate. The wait slot emits only a one-cycle pulse when
`LoadReplayWakeup` clears the remembered wait-store key; this queue records
that pulse into a finite FIFO so a later LIQ or issue owner can observe a
stable pending replay request.

This module does not launch a load, allocate or mutate `LoadInflightQueue`
rows, update a ready table, or wake dependent consumers. It owns only
candidate admission, FIFO ordering, flush clearing, dequeue handshake, and
overflow diagnostics. In the reduced top after R273,
`ReducedLoadReplayCompletionDrain` drives `outReady` only when the held load
later completes with matching identity. R275 makes the candidate payload match
the future LIQ allocation/relaunch boundary more directly by carrying the
forwarding snapshot `(youngestStoreId, youngestStoreLsId)` as explicit
sidecars in addition to BID/GID/RID/reduced-LSID load identity.

## Interface

### Inputs

| Signal | Description |
|---|---|
| `flush` | Clears the queue and suppresses enqueue/dequeue for the cycle. |
| `enqueueValid` | Candidate-valid pulse from the reduced wait-store slot. |
| `enqueue` | `ReducedLoadReplayCandidate` payload: remembered load PC, address, size, BID, GID, RID, reduced LSID, and forwarding snapshot `(youngestStoreId, youngestStoreLsId)`. |
| `outReady` | Consumer readiness for the queue head. In the reduced top this comes from `ReducedLoadReplayCompletionDrain`; future LIQ/issue work may replace that diagnostic consumer. |

### Outputs

| Signal | Description |
|---|---|
| `enqueueReady` | FIFO can accept the candidate this cycle. A simultaneous dequeue opens space. |
| `enqueueAccepted` | Candidate was stored in the queue. |
| `enqueueDropped` | A one-cycle candidate was valid while the queue was full and no dequeue opened space. |
| `outValid` | Queue head is valid. |
| `out` | Queue-head relaunch candidate payload. |
| `outFire` | Queue head was consumed by `outReady`. |
| `pending` | At least one relaunch candidate is resident. |
| `full` | FIFO is full. |
| `empty` | FIFO is empty. |
| `count` | Resident candidate count. |

## State

The module owns a small circular FIFO of `ReducedLoadReplayCandidate` entries,
with registered head pointer, tail pointer, and occupancy count. Stored
payloads force `valid=true` on admission so the output valid handshake remains
the authority for queue residency. Empty payloads explicitly disable every
ROBID-shaped sidecar, including GID/RID and the forwarding snapshot.

## Logic Design

The model load replay sequence is:

1. `STQ::lookupForLoad` identifies an older not-ready store and records the
   blocking store identity in the load request.
2. `LDQInfo::waitStore` marks the load row waiting and returns it to the model
   wait state.
3. `LDQInfo::handleSUWakeup` clears wait-store state when a ready store wakeup
   matches `(BID, LSID, PC)`.
4. The row becomes eligible for the normal wait/relaunch path again.

`ReducedLoadWaitReplaySlot` covers steps 1 through 3 for a single reduced-top
diagnostic row. `ReducedLoadReplayRelaunchQueue` covers the next boundary:
the cleared load identity is no longer a transient pulse and can be consumed
by a later owner in FIFO order.

Admission accepts `enqueueValid && enqueue.valid` when the queue is not full,
or when the current head is also consumed in the same cycle. If the queue is
full and no dequeue occurs, the module reports `enqueueDropped` because the
upstream candidate is a one-cycle pulse and cannot be backpressured by the
current wait-slot owner.

## Timing

`outValid` and `out` describe the pre-cycle queue head. If `outReady` and
`enqueueValid` are both asserted while full, the old head is consumed and the
new candidate is written into the freed slot. Occupancy remains full.

The integrated reduced top currently drives `outReady` from
`ReducedLoadReplayCompletionDrain`, so a queued candidate is consumed only
when the held load completes in W2 with matching PC, address, size, BID,
GID/RID, and LSID. Later LIQ/issue work should replace that diagnostic drain
with a real relaunch admission boundary.

## Flush/Recovery

`flush` clears entries, pointers, and count, and suppresses admission,
dequeue, and drop reporting for that cycle. The reduced top drives it from the
same reduced-store flush used by the wait slot.

## Deferred Owners

- A real consumer that allocates or selects a `LoadInflightQueue` row.
- Relaunch arbitration with newly issued loads.
- Ready-table or dependent-consumer wakeup.
- Multiple load wait slots feeding a shared queue.
- Precise LSU recovery pruning by load BID/LSID.

## Verification

Focused gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only ReducedLoadReplayRelaunchQueue
bash tools/chisel/run_chisel_tests.sh --only ReducedLoadReplayCompletionDrain
bash tools/chisel/run_chisel_tests.sh --only ReducedLoadWaitReplaySlot
bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop
```

Reference tests cover FIFO ordering, same-cycle dequeue/enqueue into a full
queue, full-drop diagnostics, flush clearing and admission suppression, and
Chisel elaboration of queue diagnostics.
