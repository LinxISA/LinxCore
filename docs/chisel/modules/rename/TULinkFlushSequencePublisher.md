# TULinkFlushSequencePublisher

## Source Mapping

- Chisel: `chisel/src/main/scala/linxcore/rename/TULinkFlushSequencePublisher.scala`
- Tests: `chisel/src/test/scala/linxcore/rename/TULinkFlushSequencePublisherSpec.scala`
- LinxCoreModel:
  - `model/LinxCoreModel/model/ModelCommon/bus/FlushBus.h`
  - `model/LinxCoreModel/model/ModelCommon/LSUUtils.cpp`
  - `model/LinxCoreModel/model/lsu/store_unit/store_unit.cpp`
  - `model/LinxCoreModel/model/mtccore/lsu/load_unit/ldq.cpp`
  - `model/LinxCoreModel/model/bctrl/spe/SPEROB.cpp`
  - `model/LinxCoreModel/model/bctrl/spe/SPERename.cpp`
  - `model/LinxCoreModel/model/bctrl/LocalRegMgr.cpp`
- Related Chisel:
  - `chisel/src/main/scala/linxcore/recovery/RecoveryCleanupControl.scala`
  - `chisel/src/main/scala/linxcore/rename/TULinkFlushSourceSelector.scala`
  - `chisel/src/main/scala/linxcore/rename/TULinkRename.scala`
  - `chisel/src/main/scala/linxcore/rename/TULinkRecoveryCleanupPath.scala`

## Purpose

`TULinkFlushSequencePublisher` is the standalone owner for publishing the
local T/U mapQ sequence sidebands needed by `TULinkRename` flush cleanup. It
bridges a registered `RecoveryCleanupIntent` to the local-register cleanup
inputs:

```text
flushValid, flushBaseOnBid, flushBid, flushRid, flushTSeq, flushUSeq
```

The module does not mutate T/U mapQ state and does not compose the full
decode/rename path. It records the model rule that a non-base recovery needs
the selected flushed row's T/U local sequence snapshot, and that the sequence
must be rewound by one only when that same flushed row owns the matching T or U
destination.

## Interface

Inputs:

- `cleanup`: registered recovery intent from `RecoveryCleanupControl`.
- `source`: selected ROB/LSU row snapshot for the recovery owner:
  - `valid`
  - `bid`, `rid`, `stid`
  - `tSeq`, `uSeq`
  - `dstValid`, `dstKind`

Outputs:

- `flushValid`: command is safe to drive into `TULinkRename`.
- `flushBaseOnBid`: copied from `cleanup.flush.baseOnBid`.
- `flushBid`, `flushRid`: copied from `cleanup.flush.req`.
- `flushTSeq`, `flushUSeq`: published local mapQ sequence sidebands.
- `sourceRequired`: non-base cleanup needs a selected row snapshot.
- `sourceMatched`: selected row matches `(bid, rid, stid)` in the cleanup
  request.
- `missingSource`: non-base cleanup has no selected row snapshot.
- `sourceMismatch`: non-base cleanup has a row snapshot that does not match
  the selected request.
- `tPrevApplied`, `uPrevApplied`: the model `GetPrevRegSeq` adjustment was
  applied for T or U.

## Logic Design

The command is active when:

```text
cleanup.valid && cleanup.backendFlushValid
```

This follows the model backend fanout. `FlushControl::flushBackend` calls
`SPE::Flush` for global flushes, global replays, and PE/thread-scoped replays.
`SPE::Flush` then calls `d2Stage.Flush`, and `SPERename::Flush` calls
`LocalRegMgr::flush` for each scalar T/U local-register owner. The scalar
stack rename lane uses `renameFlushValid`/`renameReplayValid`; T/U local
register cleanup follows the backend PE fanout.

For base-on-BID cleanup, `TULinkRename` ignores the local sequence sidebands,
so the publisher does not require a selected row snapshot. It still passes the
BID/RID fields through and emits zero local sequences if no source is present.

For non-base cleanup, the source row must match the selected request:

```text
source.bid  == cleanup.flush.req.bid
source.rid  == cleanup.flush.req.rid
source.stid == cleanup.flush.req.stid
```

If the source is missing or mismatched, `flushValid` is suppressed and the
diagnostic bit identifies the issue. This avoids pruning `TULinkRename` with a
default sequence that did not come from the actual flushed row.

The published sequence rule is:

```text
flushTSeq = source.tSeq - 1 when source.dstKind == T, else source.tSeq
flushUSeq = source.uSeq - 1 when source.dstKind == U, else source.uSeq
```

The subtraction uses wrap-aware `ROBID.sub`, matching scalar
`LocalRegMgr::GetPrevROBID` through `GetPrevRegSeq`.

## Model Alignment

`FlushReq` carries `tSeq`, `uSeq`, and `predSeq` sidebands. The scalar store
unit and MTC LDQ build LSU-originated flush requests from the old retiring row:

- start with `oldRetire.tSeq` and `oldRetire.uSeq`,
- inspect the owning instruction from the ROB,
- call `GetPrevRegSeq` only when the instruction destination includes
  `OPD_TLINK` or `OPD_ULINK`.

`SPEROB::getRetireID` exposes row-owned `tSeq/uSeq`. `SPEROB::CheckDstDataOut`
copies the row sequences for scalar inner flush construction. `SPERename` set
those row sequences before destination rename, so a flushed row that owns a
T/U destination needs the previous sequence to keep that destination alive
while pruning younger rows.

## Timing

The module is combinational. It consumes the registered cleanup intent and the
selected row snapshot in the same cycle and emits a command for the T/U cleanup
owner. A later composition packet may register the source snapshot at the
ROB/LSU recovery boundary; this module does not require or create that state.

## Flush/Recovery

`TULinkRecoveryCleanupPath` drives `TULinkRename.flushValid` from
`flushValid`. The remaining command fields map directly:

```text
TULinkRename.flushBaseOnBid := flushBaseOnBid
TULinkRename.flushBid       := flushBid
TULinkRename.flushRid       := flushRid
TULinkRename.flushTSeq      := flushTSeq
TULinkRename.flushUSeq      := flushUSeq
```

`sourceMismatch` and `missingSource` are recovery-contract diagnostics. They
should be monitored by the future live composition rather than silently
dropped.

## Deferred Owners

- Live ROB/LSU row sidecars feeding `TULinkFlushSourceSelector`.
- Relation-cmap release owner around local retire/dealloc.
- Ready-table mutation for T/U local physical tags.
- Multi-PE and multi-thread bank replication beyond the current STID0 packet.

## Verification

Focused gate:

```bash
bash tools/chisel/run_chisel_tests.sh --only TULinkFlushSequencePublisher
```

Affected gates:

```bash
sbt --client --error 'Test / compile'
bash tools/chisel/run_chisel_tests.sh --only TULinkRename
bash tools/chisel/run_chisel_tests.sh --only RecoveryCleanupControl
bash tools/chisel/run_chisel_tests.sh --only FlushControl
bash tools/chisel/run_chisel_rob_bookkeeping.sh --reduced-rob
```

The current tests cover non-base matching, T-only previous-sequence
adjustment, U-only previous-sequence adjustment, base-on-BID behavior without
a source row, missing/mismatched source suppression, IO widths, standalone
elaboration, and destination enum stability.
