# TULinkRecoveryCleanupPath

## Source Mapping

- Chisel: `chisel/src/main/scala/linxcore/rename/TULinkRecoveryCleanupPath.scala`
- Tests: `chisel/src/test/scala/linxcore/rename/TULinkRecoveryCleanupPathSpec.scala`
- Related Chisel:
  - `chisel/src/main/scala/linxcore/common/TULinkBundles.scala`
  - `chisel/src/main/scala/linxcore/rob/ROBEntryBank.scala`
  - `chisel/src/main/scala/linxcore/rename/TULinkFlushSourceSelector.scala`
  - `chisel/src/main/scala/linxcore/rename/TULinkFlushSequencePublisher.scala`
  - `chisel/src/main/scala/linxcore/rename/TULinkRename.scala`
  - `chisel/src/main/scala/linxcore/recovery/RecoveryCleanupControl.scala`
  - `chisel/src/main/scala/linxcore/backend/DecodeRenameROBPath.scala`
- LinxCoreModel:
  - `model/LinxCoreModel/model/bctrl/spe/SPEROB.cpp`
  - `model/LinxCoreModel/model/bctrl/spe/SPERename.cpp`
  - `model/LinxCoreModel/model/bctrl/LocalRegMgr.cpp`
  - `model/LinxCoreModel/model/ModelCommon/LSUUtils.cpp`
  - `model/LinxCoreModel/model/lsu/store_unit/store_unit.cpp`
  - `model/LinxCoreModel/model/mtccore/lsu/load_unit/ldq.cpp`

## Purpose

`TULinkRecoveryCleanupPath` is the composition owner that connects ROB/LSU
T/U cleanup source selection, T/U flush sequence publishing, and the T/U
local-register rename owner. It consumes a registered `RecoveryCleanupIntent`
plus explicit ROB and LSU row candidates, selects the row snapshot, and drives
the local `TULinkRename` flush command fields.

```text
RecoveryCleanupIntent + ROB row candidate + LSU row candidate
  -> TULinkFlushSourceSelector
  -> TULinkFlushSequencePublisher
  -> TULinkRename.flush*
```

## Interface

Inputs:

- `in`, `renameValid`: decoded-uop input for the local T/U rename owner.
- `retireValid`, `retireKind`, `retireSeq`, `retireDealloc`: local retire or
  direct deallocation command.
- `commitValid`, `commitBid`: block-commit release command.
- `cleanup`: registered recovery intent from `RecoveryCleanupControl`.
- `robSource`: ROB row candidate with `(bid, rid, stid, tSeq, uSeq, dstValid,
  dstKind)`.
- `lsuSource`: LSU row candidate with `(bid, rid, stid, tSeq, uSeq, dstValid,
  dstKind)`.

Outputs:

- All `TULinkRename` observability surfaces: `ready`, `accepted`, source and
  destination resolution, T/U allocation and deallocation sequences, mapQ
  masks, retired/released/flushed masks, pressure counts, and retire/commit
  diagnostics.
- Publisher command observability: `publisherFlushValid`,
  `publisherFlushBaseOnBid`, `publisherFlushBid`, `publisherFlushRid`,
  `publisherFlushTSeq`, and `publisherFlushUSeq`.
- Recovery diagnostics: `cleanupActive`, `cleanupBlockedBySource`,
  `flushSourceRequired`, `flushSourceMatched`, `flushMissingSource`,
  `flushSourceMismatch`, `flushTPrevApplied`, and `flushUPrevApplied`.
- Selector observability: `selectedFlushSource`, `robSourceMatched`,
  `lsuSourceMatched`, `robSourceMismatched`, `lsuSourceMismatched`,
  `multipleSourcesMatched`, `sourceConflict`, `selectorSourceMissing`,
  `selectedFromRob`, and `selectedFromLsu`.

## Logic Design

The wrapper instantiates exactly three owners:

- `TULinkFlushSourceSelector`
- `TULinkFlushSequencePublisher`
- `TULinkRename`

The selector consumes `cleanup`, `robSource`, and `lsuSource`. The publisher
then consumes `cleanup` plus the selector's emitted source. Its emitted command
drives the corresponding T/U rename flush inputs:

```text
rename.flushValid     := publisher.flushValid
rename.flushBaseOnBid := publisher.flushBaseOnBid
rename.flushBid       := publisher.flushBid
rename.flushRid       := publisher.flushRid
rename.flushTSeq      := publisher.flushTSeq
rename.flushUSeq      := publisher.flushUSeq
```

The cleanup-active predicate is:

```text
cleanup.valid && cleanup.backendFlushValid
```

For a non-base cleanup, a missing or mismatched selected-row snapshot suppresses
`publisher.flushValid`. The composition treats that as a recovery barrier:

```text
cleanupBlockedBySource = cleanupActive && !publisher.flushValid
```

When `cleanupBlockedBySource` is true, the wrapper blocks new T/U rename,
retire, and commit inputs for that cycle. This avoids silently falling through
to unrelated local-register maintenance after a malformed recovery command.
Selector conflicts are intentionally reported at the selector boundary. The
publisher sees the suppressed invalid source and reports `flushMissingSource`,
so callers should inspect both the selector and publisher diagnostics.

Base-on-BID cleanup does not require a selected-row source. The publisher emits
a valid command with zero local sequences when no source is present, and
`TULinkRename` prunes by BID instead of local sequence.

## Model Alignment

The model has two pieces that must stay composed:

- `SPERename::Flush` forwards backend cleanup to scalar local-register owners.
- `LocalRegMgr::flush` consumes `FlushReq.tSeq/uSeq` for non-base local
  pruning.

The selected-row sequence source comes from ROB/LSU recovery builders:

- `SPEROB::getRetireID` exposes row-owned `tSeq/uSeq`.
- `SPEROB::CheckDstDataOut` copies row sequences for scalar inner flush.
- Store-unit and MTC LDQ flush builders call `GetPrevRegSeq` when the flushed
  row owns a T or U destination.

`TULinkRecoveryCleanupPath` preserves that split. It owns composition and
barrier behavior, while the row owners remain responsible for producing the
candidate source snapshots. The reduced backend now instantiates the path with
`DispatchROBAllocator.robTULinkSource` on `robSource` and
`StoreDispatchSTQPath.lsuTULinkSource` from the live STQ bank on `lsuSource`.
This proves the ROB/LSU source-selection diagnostics against a real STQ row
owner while the actual T/U rename-state mutation remains deferred.

## Timing

The wrapper is combinational around the selector, publisher, and rename child
modules. It does not register the cleanup intent or row candidates. The
upstream recovery owner must provide stable candidate snapshots in the same
cycle as the registered cleanup intent.

## Flush/Recovery

Flush priority remains inside `TULinkRename`: flush wins over commit, retire,
and rename. The wrapper only adds a guard for invalid non-base source evidence.

Recovery diagnostics are intentionally surfaced as outputs. A later live path
should monitor selector conflict/missing diagnostics together with
`flushMissingSource` and `flushSourceMismatch` as recovery-contract failures
rather than ignoring them.

## Deferred Owners

- Use the reduced backend cleanup publisher outputs to mutate a live merged
  scalar/T/U rename state instead of only publishing diagnostics.
- Replace disabled store-dispatch T/U sidecars with live rename snapshots.
- Scalar decode/rename composition that merges GPR and T/U accepted outputs.
- Relation-cmap release policy around T/U retire/dealloc.
- T/U ready-table initialization and wakeup state.
- Multi-PE and multi-thread T/U bank replication beyond the current STID0
  composition.

## Verification

Focused gate:

```bash
bash tools/chisel/run_chisel_tests.sh --only TULinkRecoveryCleanupPath
```

Affected gates:

```bash
sbt --client --error 'Test / compile'
bash tools/chisel/run_chisel_tests.sh --only TULinkFlushSequencePublisher
bash tools/chisel/run_chisel_tests.sh --only TULinkFlushSourceSelector
bash tools/chisel/run_chisel_tests.sh --only TULinkRename
bash tools/chisel/run_chisel_tests.sh --only RecoveryCleanupControl
bash tools/chisel/run_chisel_tests.sh --only FlushControl
bash tools/chisel/run_chisel_rob_bookkeeping.sh --reduced-rob
```

The current tests cover ROB-source cleanup, LSU-source fallback, T- and
U-destination previous-sequence adjustment through the composition reference,
base-on-BID source-free cleanup, missing/mismatched source blocking, duplicate
source conflict blocking, inactive cleanup behavior, IO widths, and elaboration
with selector, publisher, and rename child owners.
