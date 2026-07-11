# BrobStoreRangeState

## Sources

- Chisel: `chisel/src/main/scala/linxcore/bctrl/BrobStoreRangeState.scala`
- Generated probe: `chisel/src/main/scala/linxcore/bctrl/BrobStoreRangeStateProbe.scala`
- Unit tests: `chisel/src/test/scala/linxcore/bctrl/BrobStoreRangeStateSpec.scala`
- LinxCoreModel: `model/bctrl/BROB.cpp`, `model/bctrl/spe/Decoder.cpp`,
  `model/bctrl/spe/DCTop.cpp`

## Role

`BrobStoreRangeState` assigns one contiguous store-ID range to every live
block. It owns independent range cursors and next-store-ID counters per STID
and consumes the live head/count window from `BrobOrderState`. It does not
infer age from unsigned BID magnitude.

This owner is separate from scalar decode identity assignment. A scalar store
receives its per-STID `sid` when decode is accepted. BROB later records the
number of stores in each block and assigns the block's aggregate
`startStoreId..startStoreId+storeCount` range. The two identities have the same
program-order source but different lifetimes and consumers.

## Parameters

| Parameter | Meaning |
|---|---|
| `entries` | Power-of-two BROB capacity per STID. |
| `bidWidth` | Full implementation BID width. |
| `stidWidth` / `stidCount` | Selector width and number of independent lanes. |
| `storeIdWidth` | Width of the per-STID block range serial ID. |
| `storeCountWidth` | Width of one block's store count. |

All widths are explicit. No ARM queue depth, barrier class, exclusive-monitor
state, exception level, or condition-code behavior is imported.

## Allocation And Counting

An allocation is accepted only when the selected STID is implemented and the
exact full-BID slot is free. `DispatchROBAllocator` includes this readiness in
new-block admission, so order-state, metadata, and range rows share one
allocation event.

Each accepted scalar store increments the exact resident block's accumulated
count. `blockScalarDone` publishes that accumulated count as certain. An
authoritative template or tile producer may instead publish an explicit
parameterized count through `countCertainUseValue/countCertainValue`; a decode
hint alone is not sufficient. A second count-certain event is ignored rather
than changing an already frozen range. `BrobStoreCountPublisher` retains and
arbitrates those sources before this input.

An agreeing duplicate is terminal and idempotent. A conflicting explicit
value reports `countCertainConflict`, leaves the frozen row unchanged, and is
not treated as accepted. The exact BROB head cannot retire until
`headCountKnown` is true, preventing delayed publication from leaving a hole
behind the range cursor.

## Consecutive Assignment

For each STID, the owner starts at `rangeCursorBid` and scans only the bounded
live window supplied by `orderHeadBid/orderLiveCount`.

1. The exact resident cursor row receives a stable `startStoreId`, even while
   its count is unknown.
2. A row advances the cursor only after its count is certain.
3. Consecutive known successors may advance in the same cycle.
4. A hole, stale full BID, nonresident row, or unknown count stops the scan;
   no younger row bypasses it.
5. `nextStoreId` advances modulo the configured width by each frozen count.

The assigned start remains row metadata. It is not a store-commit permit and
does not make the block non-flushable. `BrobNonFlushFrontier` and the retained
store-commit owner remain the only current STQ-to-SCB authorization path.

## Retirement And Recovery

Retirement removes only an exact allocated `(STID, full BID)` row and shares
the public block-retire event from `DispatchROBAllocator`.

Accepted suffix recovery clears the same killed range rows as BROB order and
metadata state. If the first killed row is at or older than the range cursor,
the owner rewinds the cursor to that full BID. An already assigned first-killed
row supplies its saved `startStoreId`; an exact unresolved cursor retains the
current next ID. A missing required start is reported as an integration error.
Recovery is scoped to one STID; reset/restart remains the all-lane operation.

## Interfaces And Diagnostics

- `rangeCursorBid`, `nextStoreId`: per-STID frontier state.
- `advanceCount`: number of consecutive count-certain rows consumed this
  cycle.
- `blockedValid/blockedBid`: first row preventing further assignment.
- `query*`: exact full-BID row residency, count, and assigned range start.
- `allocAccepted`, `storeObservedAccepted`, `countCertainAccepted`,
  `retireAccepted`: lifecycle coherence evidence.
- `countCertainDuplicateMatch`, `countCertainConflict`: idempotent repeat and
  inconsistent-authority diagnostics.
- `headResident`, `headCountKnown`: exact head lifecycle and retirement gate.
- `recoveryRewound`, `recoveryMissingStart`: recovery result and invariant
  failure evidence.

## Verification

```bash
bash tools/chisel/run_chisel_tests.sh --only BrobStoreRangeState
bash tools/chisel/run_chisel_tests.sh --only DispatchROBAllocator
bash tools/chisel/run_chisel_tests.sh --only DecodeRenameROBPath
bash tools/chisel/run_chisel_brob_store_range_state_probe.sh
```

The generated probe proves independent STIDs, stable blocking on an unknown
count, scalar and explicit-count assignment, recovery/reallocation, exact-row
queries, and store-ID/BID rollover through `[14,15,0]`.

## Scope

The implementation retains ISA-neutral contiguous-range bookkeeping from the
model and adapts it to Linx STID, full-BID, block completion, and accepted
recovery semantics. Cache ordering, fences, atomics, exclusives, and barrier
opcode semantics are outside this module and remain governed by the Linx ISA
and LSU contracts.
