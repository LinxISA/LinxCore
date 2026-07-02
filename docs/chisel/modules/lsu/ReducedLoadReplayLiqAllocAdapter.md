# ReducedLoadReplayLiqAllocAdapter

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/ReducedLoadReplayLiqAllocAdapter.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/ReducedLoadReplayLiqAllocAdapterSpec.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/lsu/store_unit/stq.cpp`
    - `STQ::lookupForLoad`
  - `model/LinxCoreModel/model/lsu/load_unit/ldq.cpp`
    - `LDQInfo::insert`
    - `LDQInfo::updateWaitInfo`
    - `LDQInfo::handleSUWakeup`
- Related Chisel contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/ReducedLoadWaitReplaySlot.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/ReducedLoadReplayRelaunchQueue.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/ReducedLoadReplayLiqAllocPath.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadInflightQueue.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayWakeup.scala`
- Contract IDs: `LC-CHISEL-LSU-LIQ-002`

## Purpose

`ReducedLoadReplayLiqAllocAdapter` is the first typed handoff from the reduced
wait-store replay queue to the real `LoadInflightQueue` allocation shape. It
converts a stable `ReducedLoadReplayCandidate` into `LoadInflightAlloc` without
launching the load, mutating LIQ rows directly, waking consumers, or consuming
the queue head until the downstream allocation boundary is ready.

The adapter preserves two identity domains:

- load identity: `BID/GID/RID/loadLsId`, plus PC, address, and size;
- store-order snapshot: `(youngestStoreId, youngestStoreLsId)`, captured when
  the load originally observed the blocking store relation.

Keeping those domains separate mirrors the model split between the load row's
own `MemReqBus` identity and the store-forwarding eligibility snapshot used by
`STQ::lookupForLoad` and later store-unit replay wakeups.

## Interface

### Inputs

| Signal | Description |
|---|---|
| `flush` | Suppresses allocation and queue consumption for the cycle. |
| `candidateValid` | Queue-head valid from `ReducedLoadReplayRelaunchQueue`. |
| `candidate` | `ReducedLoadReplayCandidate` payload containing load identity and forwarding snapshot. |
| `allocReady` | Downstream `LoadInflightQueue.allocReady` or equivalent allocation credit. |

### Outputs

| Signal | Description |
|---|---|
| `allocValid` | A usable replay candidate is being presented as `LoadInflightAlloc`. |
| `alloc` | Mapped LIQ allocation payload. `isTile`, `specWakeup`, and `stackValid` are false for this reduced scalar replay path. |
| `consumeReady` | Queue-head consume pulse. Asserted only when `allocValid && allocReady`. |
| `blockedByAlloc` | Candidate is usable, but downstream allocation is backpressured. |
| `candidateUsable` | `candidateValid && candidate.valid && !flush`; exposed for diagnostics. |

## State

The adapter is combinational and owns no storage. The replay queue remains the
resident-state owner for pending candidates. `LoadInflightQueue` remains the
registered LIQ row owner after `consumeReady` fires.

## Logic Design

The C++ model sequence is:

1. `STQ::lookupForLoad` finds overlapping older stores by `(BID, LSID)` order
   and records a wait-store producer when selected bytes are not data-ready.
2. `LDQInfo::updateWaitInfo` / `waitStore` records that producer on the load
   row and leaves the load in a wait state.
3. `LDQInfo::handleSUWakeup` clears matching wait-store state and may merge
   store data into miss rows when the waking store is no newer than the load's
   store-order snapshot.
4. The load returns to the ordinary wait/relaunch path.

The reduced replay path already owns steps 1 through 3 diagnostically:
`ReducedStoreResidentForward` selects the producer, `ReducedLoadWaitReplaySlot`
captures and clears it through `LoadReplayWakeup`, and
`ReducedLoadReplayRelaunchQueue` makes the clear event persistent. This adapter
adds the next boundary: the queue head can become a normal LIQ allocation
request without reconstructing identity from top-level wires.

Mapping is direct:

- `candidate.bid/gid/rid/loadLsId` -> `alloc.bid/gid/rid/loadLsId`
- `candidate.pc/addr/size` -> `alloc.pc/addr/size`
- `candidate.youngestStoreId/youngestStoreLsId` ->
  `alloc.youngestStoreId/youngestStoreLsId`
- reduced scalar flags are false: `isTile=false`, `specWakeup=false`,
  `stackValid=false`

## Timing

`allocValid` stays high while the queue head is usable and `allocReady` is low.
`consumeReady` is the only queue pop condition and is asserted only on the
accepted allocation cycle. This follows the model queue rule: inspect the head
while stalled, consume it only when ownership transfers.

## Flush/Recovery

`flush` masks the combinational allocation request and deasserts
`consumeReady`. The queue and LIQ owners still own their own registered flush
state. This module does not prune by BID/LSID; precise LSU recovery remains a
future LIQ/LDQ owner.

## Deferred Owners

- Wiring `ReducedLoadReplayLiqAllocPath` into `LinxCoreFrontendFetchRfAluTraceTop`.
- Full LIQ allocation/relaunch arbitration with newly issued loads.
- Load-store conflict recovery and LHQ/ResolveQ movement.
- Ready-table and dependent-consumer wakeup after relaunch.
- Precise flush pruning by load BID/RID/LSID.

## Verification

Focused gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only ReducedLoadReplayLiqAllocAdapter
bash tools/chisel/run_chisel_tests.sh --only LoadInflightQueue
bash tools/chisel/run_chisel_tests.sh --only ReducedLoadWaitReplaySlot
```

Reference tests cover identity/snapshot mapping, allocation backpressure,
flush/absent-candidate suppression, and Chisel elaboration of the allocation
handshake plus `loadLsId` and forwarding snapshot fields.
