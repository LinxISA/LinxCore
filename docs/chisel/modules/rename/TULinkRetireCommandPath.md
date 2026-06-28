# TULinkRetireCommandPath

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/rename/TULinkRetireCommandPath.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/rename/TULinkRetireCommandPathSpec.scala`
- Shared bundles: `rtl/LinxCore/chisel/src/main/scala/linxcore/common/TULinkBundles.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/bctrl/spe/SPEROB.cpp`
  - `model/LinxCoreModel/model/bctrl/spe/SPERename.cpp`
  - `model/LinxCoreModel/model/bctrl/spe/LocalRegMgr.cpp`
- Related Chisel:
  - `chisel/src/main/scala/linxcore/rename/TULinkRelationCmap.scala`
  - `chisel/src/main/scala/linxcore/rename/TULinkRename.scala`
  - `chisel/src/main/scala/linxcore/rename/ScalarTURenameBridge.scala`
  - `chisel/src/main/scala/linxcore/rob/ROBEntryBank.scala`
  - `chisel/src/main/scala/linxcore/backend/DecodeRenameROBPath.scala`

## Purpose

`TULinkRetireCommandPath` is the live bridge from width-wide ROB
deallocation-row T/U retire sources to the single T/U rename retire-command
maintenance port. It accepts the `ROBEntryBank.deallocTURetireSource` vector
as an atomic window, serializes valid rows in slot order, feeds
`TULinkRelationCmap`, and advances the relation stream only after
`TULinkRename` reports that the command was accepted.

The module exists because the C++ model calls `SPEROB::ReleaseRelative()` once
per deallocated ROB row, while the current Chisel T/U rename owner exposes one
retire command per cycle. The serializer preserves row-owned metadata until
the relation-cmap policy can consume every valid row.

## Interface

Inputs:

- `sources`: `Vec(sourceWidth, TULinkRetireSource)` from the ROB deallocation
  window. Valid elements carry native `bid/gid/rid`, `stid`, row-owned
  `tSeq/uSeq`, `isLast`, and T/U destination ownership.
- `clear`: clears the source queue and relation-cmap state. In the reduced
  path this is driven by the live T/U cleanup flush publisher.
- `commandReady`: actual downstream command acceptance. The integrated path
  drives this from `ScalarTURenameBridge.tuRetireAccepted`, not from predicted
  readiness.

Outputs:

- `sourceWindowReady`: true when the queue has room for a full ROB dealloc
  window. The reduced top gates ROB deallocation with this signal.
- `sourceValidMask`, `sourceEnqueueCount`, `sourceQueueCount`,
  `sourceQueueFull`, `sourceQueueEmpty`, `sourceDequeued`: serializer
  observability.
- `command`, `commandFire`: serialized mark-retired or deallocation command
  for `TULinkRename`.
- `unsupportedDst`, `preRelease*`, `pressureRelease*`, `pending*`,
  `tCount/uCount`: forwarded relation-cmap diagnostics.

## State

- Source FIFO: stores accepted `TULinkRetireSource` rows in ROB slot order.
- Head, tail, and count registers for the source FIFO.
- Embedded `TULinkRelationCmap` state for T and U relation entries and
  pending mark/release commands.

The source FIFO depth must be a power of two and at least `sourceWidth`. The
reduced top uses a conservative queue-credit rule: it accepts a dealloc window
only when enough free entries exist for the entire window, even if fewer source
elements are valid in that cycle.

## Logic Design

`sourceWindowReady` is computed only from FIFO credit and `clear`. When the
ROB deallocates, every valid source element in the window is written into the
FIFO at `tail + PopCount(prior valid slots)`. Invalid source slots are
ignored, but valid no-destination rows are preserved because a block-last row
can still force relation-cmap drain.

The FIFO presents one head row to `TULinkRelationCmap`. The head row is
removed only when relation-cmap reports `inAccepted`, so rows cannot be lost
while older relation releases or pending mark commands block acceptance.

`commandReady` is tied to actual rename acceptance in the integrated path.
This keeps flush and commit priority inside `TULinkRename`: if rename rejects
a retire command because flush or commit maintenance wins, relation-cmap keeps
the command pending and the source stream does not advance past that policy
point.

`clear` synchronously drops queued sources and relation-cmap state. The
current reduced path also blocks ROB deallocation while T/U cleanup is active,
so a recovery cleanup cannot enqueue new retire-source rows behind a pending
flush.

## Model Alignment

The preserved C++ order is:

- `SPEROB::dealloc()` walks retired rows in commit/dealloc slot order.
- For each row, `ReleaseRelative()` first drains older relation entries when
  block-last or `(bid,gid)` changes.
- `ReleaseFunc()` records a T or U relation, marks the local register retired,
  then optionally releases the oldest relation due to block-last or pressure.
- `LocalRegMgr::ReportRetired(seq, isDealloc)` is the acceptance point for the
  mark or release command.

`TULinkRetireCommandPath` owns only the width-to-one serialization and the
acceptance coupling to `TULinkRename`. The detailed relation policy remains in
`TULinkRelationCmap`, and the local mapQ mutation remains in `TULinkRename`.

## Deferred Owners

- Relation flush pruning equivalent to model `FlushRelativeReg`,
  `CleanCMAP`, and `CleanGroupCMAP`.
- Less conservative source FIFO credit that can accept partial windows with an
  explicit ROB credit contract.
- Predicate, vector, SIMT, tile, per-PE, and per-STID relation maps.
- Full-width T/U rename maintenance ports.

## Verification

Focused gate:

```bash
bash tools/chisel/run_chisel_tests.sh --only TULinkRetireCommandPath
```

Affected gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only TULinkRelationCmap
bash tools/chisel/run_chisel_tests.sh --only ScalarTURenameBridge
bash tools/chisel/run_chisel_tests.sh --only DecodeRenameROBPath
bash tools/chisel/run_chisel_tests.sh --only ROBEntryBank
bash tools/chisel/run_chisel_tests.sh --only DispatchROBAllocator
```

The tests cover slot-order serialization, conservative full-window credit,
no-destination block-last row preservation, and elaboration through the
embedded `TULinkRelationCmap`.
