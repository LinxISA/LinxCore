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
R67 adds the scalar block-clean scheduler: once a block-last source has entered
the relation-cmap owner, the path blocks further ROB deallocation windows until
the block-last source's generated mark/release commands have been accepted,
then pulses `CleanCMAP` through the embedded relation cmap.
R68 adds the next model boundary after that clean: a backpressurable
`localBlockCommit*` event that represents `SPEROB::ReportLocalRegBlockCommit`
and can later be consumed by the SGPR local-register owner. The current module
does not mutate SGPR state.
R69 connects that ready input to the reduced scalar/T/U rename composition, so
this module keeps the event pending until the local-register owner accepts it.
R70 carries the selected STID from the accepted block-last source through that
post-clean event. The reduced consumer still owns only one local STID, but the
event is now explicit enough for later multi-STID and PE fanout.
R74 carries the retired row's PE/STID through the source FIFO, relation-cmap
entry, and serialized retire command so local mark/release commands can route
to the deallocated row's bank independently of the rename-head active bank.

## Interface

Inputs:

- `sources`: `Vec(sourceWidth, TULinkRetireSource)` from the ROB deallocation
  window. Valid elements carry native `bid/gid/rid`, `peId/stid`, row-owned
  `tSeq/uSeq`, `isLast`, and T/U destination ownership.
- `clear`: local hard reset for the source queue and relation-cmap state. The
  reduced backend currently ties this low and uses `flush` for recovery
  pruning.
- `flush`: backend recovery flush intent. It prunes queued sources and the
  embedded relation cmap with the same model `FlushRelativeReg` suffix rule.
- `cleanBlockValid/cleanBlockBid`: block-commit cleanup equivalent to
  `CleanCMAP(bid)`.
- `cleanGroupValid/cleanGroupBid/cleanGroupGid`: group cleanup equivalent to
  `CleanGroupCMAP(bid, gid)`.
- `commandReady`: actual downstream command acceptance. The integrated path
  drives this from `ScalarTURenameBridge.tuRetireAccepted`, not from predicted
  readiness.
- `localBlockCommitReady`: downstream acceptance for the scalar local-register
  block-commit event that follows the auto `CleanCMAP` pulse. In the reduced
  backend this now comes from `ScalarTURenameBridge.tuLocalBlockCommitReady`.

Outputs:

- `sourceWindowReady`: true when the queue has room for a full ROB dealloc
  window. The reduced top gates ROB deallocation with this signal.
- `sourceValidMask`, `sourceEnqueueCount`, `sourceQueueCount`,
  `sourceQueueFull`, `sourceQueueEmpty`, `sourceDequeued`: serializer
  observability.
- `command`, `commandFire`: serialized mark-retired or deallocation command
  for `TULinkRename`. The command includes `peId/stid` sidecars copied from
  the retired row or resident relation entry selected by `TULinkRelationCmap`.
- `unsupportedDst`, `preRelease*`, `pressureRelease*`, `pending*`,
  `tCount/uCount`: forwarded relation-cmap diagnostics.
- `cleanupActive`, `sourcePruneCount`, `relationPruneTCount`,
  `relationPruneUCount`: cleanup observability for queued sources and
  embedded relation entries.
- `autoCleanBlockPending`, `autoCleanBlockValid`, `autoCleanBlockBid`:
  one-shot scheduler state for the model `CleanCMAP(bid)` event caused by a
  scalar block-last deallocation source.
- `localBlockCommitPending`, `localBlockCommitValid`,
  `localBlockCommitBid`, `localBlockCommitStid`, `localBlockCommitFire`:
  one-shot event boundary for the model
  `ReportLocalRegBlockCommit(bid, stid)` call. The reduced backend feeds this
  event to the live T/U local-register owner through
  `ScalarTURenameBridge`.

## State

- Source FIFO: stores accepted `TULinkRetireSource` rows in ROB slot order,
  including row-owned PE/STID bank identity.
- Head, tail, and count registers for the source FIFO.
- Embedded `TULinkRelationCmap` state for T and U relation entries and
  pending mark/release commands.
- Auto block-clean latch containing the accepted block-last source BID and
  STID while relation-cmap mark/release commands drain.
- Local block-commit latch containing the BID/STID pair whose scalar
  `CleanCMAP` pulse has completed and whose SGPR-local block-commit report is
  waiting for downstream acceptance.

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
The FIFO and embedded relation-cmap preserve `peId/stid` through this
width-to-one conversion. No command may reconstruct bank identity from the
currently selected rename row.

`commandReady` is tied to actual rename acceptance in the integrated path.
This keeps flush and commit priority inside `TULinkRename`: if rename rejects
a retire command because flush or commit maintenance wins, relation-cmap keeps
the command pending and the source stream does not advance past that policy
point.

When the relation-cmap owner accepts a source whose `isLast` sidecar is set,
the path latches that BID and STID as a pending scalar block clean and
immediately deasserts `sourceWindowReady`. The pending state does not block
existing relation-cmap commands; it only blocks new ROB deallocation-source
admission. After `pendingMark`, `pendingPostReleaseT`, and
`pendingPostReleaseU` are all clear, the path emits one auto `cleanBlock`
pulse for the latched BID. This matches `SPEROB::dealloc()` calling
`ReleaseRelative()` for the block-last row before `CommitBlock()` calls
`CleanCMAP(bid)`.

When that auto `cleanBlock` pulse fires, the path schedules a separate local
block-commit event for the same BID/STID pair. The event becomes valid on the
next cycle, so the visible order is relation-cmap drain, scalar `CleanCMAP`,
then `ReportLocalRegBlockCommit`. While the event is pending, the source
window remains closed to later ROB deallocation sources. The pending event does
not block relation-cmap command acceptance; it only blocks new source
admission until `localBlockCommitReady` accepts the event or
recovery/external block cleanup prunes it. A downstream reduced bank that does
not own the event STID keeps ready low, leaving the event pending for a future
fanout owner rather than silently consuming it.

Cleanup uses the same predicates on the source FIFO and on the embedded
relation cmap. Exact block and group clean operations can remove matching rows
from anywhere in the queue while preserving order. Recovery flush removes only
the newest suffix whose BID matches the model `FlushRelativeReg` predicate:
inclusive when `baseOnBid` is set, strict otherwise. While cleanup is active,
the module blocks source-window admission, suppresses relation-cmap input, and
holds command progress so no flushed queued source can re-enter the relation
stream.

`clear` remains a synchronous hard reset, separate from model recovery flush
pruning. The current reduced composition drives backend recovery through
`flush`, not through `clear`.

## Model Alignment

The preserved C++ order is:

- `SPEROB::dealloc()` walks retired rows in commit/dealloc slot order.
- For each row, `ReleaseRelative()` first drains older relation entries when
  block-last or `(bid,gid)` changes.
- `ReleaseFunc()` records a T or U relation, marks the local register retired,
  then optionally releases the oldest relation due to block-last or pressure.
- `LocalRegMgr::ReportRetired(seq, isDealloc)` is the acceptance point for the
  mark or release command.
- `CommitBlock()` runs `CleanCMAP(bid)` only after that block-last
  `ReleaseRelative()` work.
- `CommitBlock()` then runs `ReportLocalRegBlockCommit(bid, stid)`, which
  calls `SPERename::ReportSGPRBlockCommit()` and eventually
  `LocalRegMgr::ReportBlockCommit()` on the scalar local-register manager for
  the selected STID.

`TULinkRetireCommandPath` owns only the width-to-one serialization and the
acceptance coupling to `TULinkRename`. The detailed relation policy remains in
`TULinkRelationCmap`, queued source cleanup mirrors that policy before rows
enter relation-cmap, the scalar block-clean scheduler drives the post-drain
`CleanCMAP` pulse, the local block-commit event preserves the next model
ordering point, and the local mapQ mutation remains in `TULinkRename`.
R74 also preserves the model's retire-bank arguments:
`ReleaseRelative()` derives the retired row's PE/STID, `ReleaseFunc()` records
`RelateInfo.peid`, and later `ReportRetired(..., isDealloc=true)` must use
that stored relation entry identity.

## Deferred Owners

- External live block/group commit event wiring for non-scalar
  `cleanBlock*` and `cleanGroup*`; scalar block-last auto clean is now local.
- Ready-table mutation and physical tag wakeup/release side effects for
  relation cleanup entries.
- Less conservative source FIFO credit that can accept partial windows with an
  explicit ROB credit contract.
- Predicate, vector, SIMT, tile, per-PE, and per-STID relation maps.
- Full-width T/U rename maintenance ports.
- Dynamic scalar PE owner carry into ROB allocation; the reduced path still
  allocates PE0 even though the retire sidecar is now structurally present.

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
no-destination block-last row preservation, source FIFO cleanup before
relation-cmap consumption, auto block-clean timing after relation commands
drain, local block-commit STID carry and backpressure after auto clean, and
retired-row PE/STID preservation from source rows through relation commands,
and elaboration through the embedded `TULinkRelationCmap`.
