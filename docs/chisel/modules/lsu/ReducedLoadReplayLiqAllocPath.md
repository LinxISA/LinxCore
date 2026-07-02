# ReducedLoadReplayLiqAllocPath

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/ReducedLoadReplayLiqAllocPath.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/ReducedLoadReplayLiqAllocPathSpec.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/lsu/load_unit/ldq.cpp`
    - `LDQInfo::insert`
    - `LDQInfo::updateWaitInfo`
    - `LDQInfo::handleSUWakeup`
  - `model/LinxCoreModel/model/lsu/store_unit/stq.cpp`
    - `STQ::lookupForLoad`
- Related Chisel contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/ReducedLoadReplayLiqAllocAdapter.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadInflightQueue.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/ReducedLoadReplayRelaunchQueue.scala`
- Contract IDs: `LC-CHISEL-LSU-LIQ-003`

## Purpose

`ReducedLoadReplayLiqAllocPath` is the allocation-only composition boundary
between queued reduced replay candidates and registered LIQ row state. It
instantiates `ReducedLoadReplayLiqAllocAdapter` and `LoadInflightQueue`, then
uses the LIQ allocation handshake as the only authority for consuming the
replay queue head.

This module is intentionally narrower than full replay. It does not launch
loads, arbitrate new loads against replay loads, publish LHQ rows into a real
ResolveQ, wake dependent consumers, or connect to the live reduced top. Its
job is to prove that the candidate payload can become normal LIQ residency
without top-level glue reconstructing identity.

## Interface

### Inputs

| Signal | Description |
|---|---|
| `flush` | Flushes the internal LIQ and suppresses candidate consumption. |
| `candidateValid` | Queue-head valid from `ReducedLoadReplayRelaunchQueue`. |
| `candidate` | Queued reduced replay candidate with load identity and forwarding snapshot. |
| `clearResolvedValid` | Pass-through clear request for future tests or consumers that resolve rows. |
| `clearResolvedIndex` | LIQ slot for `clearResolvedValid`. |

### Outputs

| Signal | Description |
|---|---|
| `candidateConsumeReady` | Connects to replay-queue `outReady`; high only when LIQ allocation accepts. |
| `candidateUsable` | The queue head is valid and not masked by flush. |
| `candidateBlockedByAlloc` | Queue head is usable but the LIQ allocation pointer is not free. |
| `allocValid` | Adapter-presented allocation request. |
| `allocReady` | LIQ allocation pointer is free and flush is inactive. |
| `allocAccepted` | Candidate allocated into LIQ row state this cycle. |
| `allocIndex` | LIQ allocation slot. |
| `allocLoadId` | Slot-plus-wrap `LID` assigned by `LoadInflightQueue`. |
| `rows` | Current LIQ row image. |
| `occupiedMask` | Resident LIQ rows. |
| `waitMask` | Rows in `Wait`. |
| `waitStoreMask` | Rows waiting on a store. This path allocates replay-ready rows, so newly allocated rows do not set it. |
| `residentCount` | Resident LIQ row count. |
| `empty` / `full` | LIQ occupancy diagnostics. |
| `clearResolvedAccepted` | Pass-through `LoadInflightQueue` clear response. |

## State

The composition owns no state beyond its child `LoadInflightQueue`. The replay
queue remains the pending-candidate owner before `candidateConsumeReady`, and
`LoadInflightQueue` owns row residency after `allocAccepted`.

## Logic Design

The C++ model creates load rows in `LDQInfo::insert`, records wait-store
state through `LDQInfo::updateWaitInfo` / `waitStore`, and later clears
matching store waits in `LDQInfo::handleSUWakeup`. Once wait-store state is
cleared, the row returns to the normal wait/relaunch path. In Chisel, the
reduced wait slot and replay queue produce the same cleared load as a
`ReducedLoadReplayCandidate`.

`ReducedLoadReplayLiqAllocPath` maps that candidate into real LIQ residency:

1. `ReducedLoadReplayLiqAllocAdapter` converts the candidate into
   `LoadInflightAlloc`.
2. `LoadInflightQueue.allocReady` backpressures the adapter when the allocation
   pointer names a resident row.
3. `candidateConsumeReady` fires only with `allocAccepted`, transferring
   ownership from the replay queue into LIQ row state.
4. The allocated row stays in `Wait` with row-owned `loadLsId` plus the
   forwarding snapshot `(youngestStoreId, youngestStoreLsId)`.

All launch, E2/E3/E4 forwarding, replay wakeup, refill wakeup, and return
ports are tied inactive in this owner. Those behaviors remain in
`LoadInflightQueue` and future top-level replay arbitration packets.

## Timing

The path is a single-cycle ready/valid allocation boundary around the replay
queue head. If LIQ is full, `candidateBlockedByAlloc` is high and
`candidateConsumeReady` remains low, so the queue keeps the head resident. A
same-cycle flush suppresses allocation and consumption.

## Flush/Recovery

`flush` is passed directly to `LoadInflightQueue` and the adapter. It clears
resident LIQ state and returns the allocation pointer to slot zero. Precise
LSU recovery pruning by BID/RID/LSID is still a deferred LIQ/LDQ recovery
owner.

## Deferred Owners

- Live default `LinxCoreFrontendFetchRfAluTraceTop` replay replacement. R279
  wires this composition only through the opt-in reduced-store replay-LIQ
  wrapper and diagnostics.
- Top-level launch selection between new loads and replay loads. R280 adds
  `LoadInflightLaunchSelect` as a standalone selector for row-owned data-hit
  replay rows, but this path does not drive `LoadInflightQueue.launchValid`.
- Reusing row-owned data from replay/refill wakeups during relaunch.
- LHQ/ResolveQ queue movement and load-store conflict publication.
- Ready-table, bypass, and dependent-consumer wakeup.

## Verification

Focused gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only ReducedLoadReplayLiqAllocPath
bash tools/chisel/run_chisel_tests.sh --only ReducedLoadReplayLiqAllocAdapter
bash tools/chisel/run_chisel_tests.sh --only LoadInflightQueue
bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop
```

Reference tests cover allocation-pointer order, preservation of load LSID and
forwarding snapshot sidecars, full-LIQ backpressure without queue consumption,
flush clearing, and Chisel elaboration with both child modules.
