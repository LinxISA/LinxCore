# BlockScalarDoneSequencer

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/bctrl/BlockScalarDoneSequencer.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/bctrl/BlockScalarDoneSequencerSpec.scala`
- Integration:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/backend/DecodeRenameROBPath.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/backend/DispatchROBAllocator.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/bctrl/BROB.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/bctrl/spe/SPEROB.cpp`
  - `model/LinxCoreModel/model/bctrl/spe/DCTop.cpp`

## Purpose

`BlockScalarDoneSequencer` owns the reduced scalar-done to BROB-retire timing
boundary. A block completion source can pulse scalar done for a full BID in the
current cycle, and the sequencer produces the matching block-retire pulse one
cycle later. This lets `BrobMetaTracker` observe the `Completed` state before
the retire/free request clears the entry.

This module does not decide that a block is complete. The current reduced
sources are marker-consume lifecycle events, scalar redirect cleanup, and
ROB block-last deallocation from `DecodeRenameROBPath`. Future full marker-row
retirement should feed this same boundary instead of adding another local
pending-retire register.

## Interface

| Direction | Signal | Type | Description |
|---|---|---|---|
| Input | `flushValid` | `Bool` | Clears pending retire state for backend or block cleanup. |
| Input | `inValid` | `Bool` | Current-cycle scalar-done event. |
| Input | `inBid` | `UInt(bidWidth.W)` | Full BID associated with `inValid`. |
| Output | `scalarDoneValid` | `Bool` | Same-cycle pass-through scalar-done pulse. |
| Output | `scalarDoneBid` | `UInt(bidWidth.W)` | Same-cycle pass-through scalar-done BID. |
| Input/Output | `inStid` / `scalarDoneStid` | `UInt(stidWidth.W)` | Same-cycle STID paired with the full BID. |
| Output | `retireStid` | `UInt(stidWidth.W)` | Retained STID paired with `retireBid`. |
| Output | `retireValid` | `Bool` | Registered retire pulse for the previous scalar-done BID. |
| Output | `retireBid` | `UInt(bidWidth.W)` | Full BID associated with `retireValid`. |
| Output | `retirePending` | `Bool` | Registered pending state, used by marker pre-retire allocation conflict logic. |

## Logic Design

- `scalarDoneValid`, `scalarDoneBid`, and `scalarDoneStid` are direct pass-through outputs. They
  feed `DispatchROBAllocator.blockScalarDone*`, which forwards them into
  `BrobMetaTracker.scalarDone*`. The pending retire register retains both BID
  and STID, so equal BID values in different lanes cannot alias.
- `retireValid` and `retireBid` are driven from a one-entry register that
  captures `inBid` when `inValid` is asserted.
- If `inValid` arrives while an old retire is pending, the old retire remains
  visible for the current cycle and the new BID becomes the pending retire for
  the next cycle.
- `flushValid` clears the pending state for following cycles. Current-cycle
  outputs still reflect the registered state, matching the pre-R167
  `DecodeRenameROBPath` timing.

## Model Alignment

`SPEROB::dealloc()` stops at block-last rows and calls `CommitLast()` /
`CommitBlock()` at the model block boundary. The reduced Chisel path splits
that into an observable scalar-done update followed by a one-cycle-later BROB
retire/free pulse because `BrobMetaTracker` is a synchronous metadata owner.

Reduced marker rows are still consumed outside the ROB when
`skipBlockMarkers=true`; this module only owns the event sequencing after the
reduced lifecycle source has selected a full BID.

## Verification

- `bash tools/chisel/run_chisel_tests.sh --only BlockScalarDoneSequencer`
- `bash tools/chisel/run_chisel_tests.sh --only DecodeRenameROBPath`
- `bash tools/chisel/run_chisel_tests.sh --only BROB`
