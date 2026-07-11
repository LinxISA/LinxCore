# BrobOrderState

## Sources

- Chisel: `chisel/src/main/scala/linxcore/bctrl/BrobOrderState.scala`
- Generated probe: `chisel/src/main/scala/linxcore/bctrl/BrobOrderStateProbe.scala`
- LinxCoreModel: `model/bctrl/BROB.h`, `model/bctrl/BROB.cpp`

## Role

`BrobOrderState` is the parameterized per-STID owner for the live BROB order
window. Each lane stores three independent values:

- `allocCursor`: exclusive next-allocation full BID;
- `commitCursor`: oldest live full BID;
- `liveCount`: number of resident identities in `[commitCursor, allocCursor)`.

The values share one module because they define one bounded ring window, but
they are not one pointer. Allocation advances only the tail. Retirement
advances only the head. Recovery truncates the tail and count while preserving
the head. This matches `BlockROB::allocBlock`, `BlockROB::BlockCOMPLETED`,
`BlockROB::recoverBlock`, and `BlockROB::setFlushed`.

## Parameters

| Parameter | Meaning |
|---|---|
| `entries` | Power-of-two resident block capacity per STID. |
| `bidWidth` | Internal pointer width; must include explicit wrap history above the canonical BID slot. |
| `stidWidth` | Width of an STID selector. |
| `stidCount` | Number of independently owned BROB order windows. |

`liveCount` has width `ceil(log2(entries + 1))`. The resident window never
contains more than `entries` identities. Canonical external BID is only the
low `log2(entries)` slot; internal cursors retain the wrap history.

## Allocation

An allocation applies only when all of these conditions hold:

1. `allocStid` selects an implemented lane;
2. `allocBid` equals that lane's current `allocCursor` exactly;
3. `liveCount < entries`.

`DispatchROBAllocator` generates `allocBid` from this owner and combines the
owner's full indication with `BrobMetaTracker.allocReady`. Public allocation,
ROB allocation, BROB metadata allocation, and order-window advance therefore
share one admitted event. A stale, skipped, or duplicate full BID cannot move
the tail.

## Completion And Retirement

`BrobMetaTracker` resolves the exact metadata row named by each owner-provided
`commitCursor`. It checks the full BID and STID, not only the physical slot.
Only a resident, completed head is eligible.

Eligible STID heads enter a round-robin arbiter. Its winner crosses a
one-entry flow-through retire slot before the downstream rename-commit queue.
The slot provides an irrevocable full `(STID, BID)` identity under
backpressure: a newly completed head in another STID cannot change the held
winner. Metadata free, commit-head advance, live-count decrement, rename
commit enqueue, and public `blockRetireFire` occur on the same ready/valid fire.

Completion remains persistent metadata. Consecutive scalar-done events can
mark different blocks complete while retirement is blocked; no one-cycle
completion pulse is used as the retirement authority. A younger completed
block waits until every older live block in its STID retires.

The current integrated block-retire interface has one shared retire lane.
LinxCoreModel's configurable multi-block `bctrl_bandwidth` remains a promotion
gap; widening requires a multi-enqueue downstream commit boundary, not
duplicating head selection outside this owner.

## Recovery

`BrobLiveBidResolver` first resolves the request's canonical BID slot against
the selected STID's exact `[commitCursor, liveCount)` window. Exactly one match
is required. A zero-match request is rejected; more than one match violates the
bounded-window invariant and asserts. The resolved internal pointer, not any
migration-era upper transport bits, is the recovery pivot.

The accepted cleanup decision is translated to `recoveryFirstKilledBid`:

- miss-predict: `firstKilled = pivot`;
- retained-target nuke/inner/fast flush: `firstKilled = pivot + 1`.

The resolver supplies the pivot distance. Inclusive recovery retains that
distance; retained-pivot recovery retains `distance + 1`. Empty lanes,
out-of-range STIDs, and canonical slots outside the live window are rejected.

On application, `allocCursor := firstKilled` and `liveCount := retained`.
`commitCursor` does not move. `BrobMetaTracker` receives the flush only from
this applied decision and uses the same commit head/live count to classify the
modular killed suffix. This includes live windows spanning full-BID rollover;
raw unsigned BID magnitude is never the metadata age test. Order-window
truncation and table pruning therefore cannot diverge. An applied recovery also
cancels a held retire identity; a surviving completed head is selected again
from post-recovery state.

The integrated backend reconstructs its cleanup view with
`recoveryResolvedPivotBid` before feeding rename and queue owners. A missing
canonical match suppresses that complete downstream view rather than allowing
BROB rejection and rename mutation to diverge.

## Simultaneous Events

Priority and arithmetic are explicit:

1. Applied recovery dominates allocation and retirement in its selected lane;
   accepted recovery blocks global allocation and retire publication.
2. Without recovery, allocation and retirement may fire together.
3. Same-lane simultaneous allocation and retirement advance both cursors and
   leave `liveCount` unchanged.
4. Different STID lanes mutate independently.

## Diagnostics

- `headMismatch[stid]` reports disagreement between the order window and exact
  metadata residency.
- `recoveryWindowValid`, `recoveryRetainedCount`, and
  `recoveryOldAllocBid` expose the recovery calculation.
- `recoveryCanonicalMatch` and `recoveryResolvedPivotBid` expose canonical
  resolution; `recoveryLegacyPointerMismatch` reports disagreement between
  the wider transported value and the resolved internal pointer without
  changing recovery authority.
- `retireMetadataAccepted` at `DispatchROBAllocator` proves that a public
  retire fire removed the exact metadata head.

## Verification

- `bash tools/chisel/run_chisel_tests.sh --only BrobOrderState`
- `bash tools/chisel/run_chisel_tests.sh --only BROB`
- `bash tools/chisel/run_chisel_tests.sh --only DispatchROBAllocator`
- `bash tools/chisel/run_chisel_tests.sh --only DecodeRenameROBPath`
- `bash tools/chisel/run_chisel_brob_order_state_probe.sh`

The generated probe covers independent STID windows, out-of-order completion,
irrevocable backpressure, fair cross-STID retirement, exact metadata removal,
simultaneous allocation/retirement, inclusive and retained-pivot recovery,
invalid-pivot rejection, full-BID allocation identity rejection, and metadata
suffix pruning across internal-pointer rollover, canonical slot resolution,
and diagnostic-only legacy upper-bit mismatch.

## Scope

This owner implements ISA-neutral reorder-window machinery using Linx STID,
BID, block completion, and recovery semantics. It does not import ARM
exception levels, condition flags, exclusive monitors, barriers, or any other
ARM architectural behavior. Non-flush and store-barrier frontiers, replay
state, and multi-block retire width remain later Chisel packets. Inactive
LinxCoreModel `dispatchPtr`, `renameStartPtr`, and `renamePtr` declarations are
not treated as behavior authority.
