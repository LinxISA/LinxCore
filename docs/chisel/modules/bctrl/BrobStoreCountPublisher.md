# BrobStoreCountPublisher

## Sources

- Chisel: `chisel/src/main/scala/linxcore/bctrl/BrobStoreCountPublisher.scala`
- Generated probe:
  `chisel/src/main/scala/linxcore/bctrl/BrobStoreCountPublisherProbe.scala`
- Unit tests:
  `chisel/src/test/scala/linxcore/bctrl/BrobStoreCountPublisherSpec.scala`
- LinxCoreModel:
  - `model/bctrl/spe/DCTop.cpp`
  - `model/bctrl/spe/Decoder.cpp`
  - `model/bctrl/spe/GenCoder.cpp`
  - `model/bctrl/BROB.cpp`

## Role

`BrobStoreCountPublisher` is the retained handoff between block count
producers and `BrobStoreRangeState`. It keeps scalar closure and explicit
CTU/tile counts as independent one-entry sources, arbitrates them without
losing a non-backpressurable scalar event, and removes pending events with the
same accepted killed-suffix recovery as BROB state.

The module does not calculate template semantics. A future CTU or tile engine
owns the count for its operation and presents the exact `(STID, full BID,
storeCount)` payload. This boundary proves transport and lifecycle, not the
producer's opcode-specific arithmetic.

## Model Alignment

The active model uses three distinct carriers:

- DCTop owns per-STID `startSID` and `storeInstCount` bookkeeping.
- Decoder and GenCoder assign per-instruction SID as block start plus local
  offset and accumulate generated scalar/template stores.
- BROB `deliveryStoreID` assigns contiguous starts and stops at an uncertain
  count.

`BlockROB::setStoreCount` exists but has no active caller in the current model,
and DCTop `calcLSCnt` is also orphaned. R654 therefore retains the useful
publication mechanism while defining a real hardware producer boundary; it
does not cite dead model declarations as proof of a live source.

## Inputs And Residency

Scalar input carries `valid`, STID, and full BID. The count value comes from
the exact range row's accumulated scalar-store count.

Explicit input carries `valid/ready`, STID, full BID, and a parameterized count
value. Input is ready only when:

1. the selected STID exists;
2. the full BID lies inside that STID's authoritative head/live-count window;
3. the explicit holding entry is empty or reaches terminal handling that
   cycle.

An accepted explicit event is retained until the range sink accepts it,
recognizes an agreeing duplicate, or accepted recovery kills it. A sink row
that is temporarily absent keeps the event pending rather than dropping it.

## Arbitration

- One source present: publish that source.
- Same-block scalar and explicit sources: explicit value wins; scalar count
  closure is redundant for the range row.
- Different-block collision: scalar closure publishes first because the
  source event cannot be backpressured; explicit remains pending.
- Sink rejection holds the selected source. A new event may replace a source
  only on the same cycle that the previous resident event terminates.

Scalar and explicit publication affect count certainty only. BROB scalar done,
engine done, exceptions, strong non-flush qualification, and STQ-to-SCB
admission remain separate state machines.

## Recovery

Pending and same-cycle incoming identities are classified with bounded modular
distance from the selected STID's BROB head. An accepted recovery cancels only
events at or after `recoveryFirstKilledBid` inside the current live window.
Older survivors remain pending. BID magnitude is never an age test.

## Duplicate Contract

`BrobStoreRangeState` reports three terminal classes:

- first publication: accepted and freezes the row count;
- agreeing duplicate: terminal and idempotent;
- conflicting explicit duplicate: nonterminal integration error; the frozen
  row is unchanged and the publisher holds the event for observability or
  recovery cancellation.

Block retirement requires both normal BROB completion and count-known state at
the exact head. This prevents a completed row from retiring while its delayed
count publication still owns the contiguous range frontier.

## Verification

```bash
bash tools/chisel/run_chisel_tests.sh --only BrobStoreCountPublisher
bash tools/chisel/run_chisel_tests.sh --only BrobStoreRangeState
bash tools/chisel/run_chisel_tests.sh --only DispatchROBAllocator
bash tools/chisel/run_chisel_tests.sh --only DecodeRenameROBPath
bash tools/chisel/run_chisel_brob_store_count_publisher_probe.sh
```

The generated probe covers live-window rejection, scalar accumulation,
agreeing and conflicting duplicates, recovery cancellation, same- and
different-block collisions, source serialization, and retention until a
missing range row is coherently allocated.

## ISA Boundary

This is ISA-neutral queue and recovery machinery adapted to Linx STID/full-BID
block semantics. It imports no ARM barriers, exclusives, exception levels,
condition flags, acquire/release opcodes, or fixed reference-core sizing.
