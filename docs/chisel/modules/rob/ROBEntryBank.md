# ROBEntryBank

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/rob/ROBEntryBank.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/rob/ROBEntryBankSpec.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/pe/PECommon/PROBStatus.h`
  - `model/LinxCoreModel/model/pe/PECommon/PROBCommon.h`
  - `model/LinxCoreModel/model/bctrl/spe/SPEROB.cpp`
  - `model/LinxCoreModel/model/bctrl/spe/SPERename.cpp`
  - `model/LinxCoreModel/model/ModelCommon/LSUUtils.cpp`
- Related Chisel contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/common/TULinkBundles.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/backend/DispatchROBAllocator.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/rename/TULinkRelationCmap.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/recovery/RecoveryCleanupControl.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/rename/TULinkFlushSourceSelector.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/rob/ROBEntryStatus.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/rob/ROBFlushPrune.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/commit/CommitTrace.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/commit/CommitTraceMonitor.scala`
- Contract IDs: `LC-IF-CHISEL-ROB-BANK-001`

## Purpose

`ROBEntryBank` is the first status-backed integrated ROB/CMT skeleton. It
extends the reduced commit harness into the LinxCoreModel phase shape without
claiming full ROB replacement: allocation creates `Allocated` rows, completion
marks rows `Completed`, commit walks only contiguous completed head rows and
marks them `Retired`, and a separate deallocation walk later frees retired
rows. The bank now consumes `ROBFlushPrune` as its first integrated recovery
helper: a matching flush has priority over allocation/completion/commit/dealloc
for the cycle, clears the selected rows, updates resident and outstanding
counts, and rebases the allocation/commit pointers covered by the model prune
walk.

R56 adds the first live ROB source image for T/U local-register cleanup. Each
allocated row can now store `stid`, row-owned `tSeq/uSeq`, and the row's T/U
destination class. A non-base flush request can read back the exact matching
row as a `TULinkFlushSequenceSource` for
`TULinkFlushSourceSelector.robSource`.

R63 adds the ROB deallocation-row source image for SPEROB relation-cmap
retire/release. Each allocated row also stores native `gid` and `isLast`, and
the deallocation walk exposes a `TULinkRetireSource` vector for every
deallocated slot in the fixed retire window. This is observation and handoff
infrastructure only; relation-cmap serialization is owned by
`TULinkRelationCmap`.

R66 aligns the deallocation walk with `SPEROB::dealloc()` by stopping the
current deallocation window after the first block-last row. The bank exposes
that block-last row's native `(bid,gid)` as the future block-clean scheduling
point and now also exposes the row's full 64-bit `blockBid` so BROB lifecycle
owners do not have to reconstruct block identity from ring `ROBID` sidecars.
It does not fire `CleanCMAP`.
R74 adds the row's scalar PE owner to the deallocation T/U retire source. The
bank stores `allocPeId` next to `allocStid`, then publishes both in
`deallocTURetireSource` so downstream retire commands can route by retired-row
bank identity.
R76 adds the post-rename row update needed by enqueue-time ROB reservation.
Allocation may now reserve the row before `DecodeRenameQueue` enqueue with
only decode-time identity and zero T/U sidecars. When rename later accepts the
queued instruction, `renameUpdate*` patches the stored `CommitTraceRow`,
`tSeq/uSeq`, and T/U destination ownership and moves the row from `Allocated`
to `Renamed`.

`ReducedCommitROB` remains the reduced trace harness. Do not retrofit full
deallocation or recovery semantics into that module.

## Interface

| Direction | Signal | Type | Valid/ready | Description |
|---|---|---|---|---|
| input | `flush` | `FlushBus` | `flush.req.valid` | Annotated flush request consumed by `ROBFlushPrune` |
| input | `allocValid` | `Bool` | `allocReady` | Requests allocation of `allocRow` into the current allocation pointer |
| output | `allocReady` | `Bool` | yes | High when the bank is not full and the row identity is not already resident |
| output | `allocDuplicateIdentity` | `Bool` | diagnostic | High when live non-free rows already contain the same `(bid,gid,rid)` |
| input | `allocRow` | `CommitTraceRow` | with `allocValid` | Commit trace payload stored with the ROB row |
| input | `allocBid` | `ROBID(entries)` | with `allocValid` | Native backend/BROB BID sidecar stored for recovery comparison |
| input | `allocGid` | `ROBID(entries)` | with `allocValid` | Native group ID sidecar used by relation-cmap release grouping |
| input | `allocPeId` | `UInt(peIdWidth.W)` | with `allocValid` | Scalar PE owner sidecar stored for T/U retire-command bank routing |
| input | `allocStid` | `UInt(stidWidth.W)` | with `allocValid` | Thread/STID sidecar stored for exact T/U cleanup source matching |
| input | `allocTSeq` / `allocUSeq` | `ROBID(mapQDepth)` | with `allocValid` | Row-owned local T/U sequence snapshots from rename |
| input | `allocTUDstValid` / `allocTUDstKind` | mixed | with `allocValid` | Whether the row owns a T or U destination that needs previous-sequence adjustment |
| input | `allocIsLast` | `Bool` | with `allocValid` | Whether the row is the model block-last instruction for relation-cmap release |
| output | `allocRobValue` | `UInt(log2(entries).W)` | diagnostic | ROB slot that will be assigned on an accepted allocation |
| input | `renameUpdateValid` | `Bool` | `renameUpdateReady` | Requests a post-rename update for an already reserved row |
| output | `renameUpdateReady` | `Bool` | yes | High when `renameUpdateRid` names a resident allocated/renamed row and no flush owns the cycle |
| output | `renameUpdateAccepted` | `Bool` | diagnostic | `renameUpdateValid && renameUpdateReady` |
| output | `renameUpdateIgnored` | `Bool` | diagnostic | Update request could not apply because flush or row/status/RID mismatch blocked it |
| input | `renameUpdateRid` | `ROBID(entries)` | with `renameUpdateValid` | Native RID of the reserved row to patch after rename |
| input | `renameUpdateRow` | `CommitTraceRow` | with `renameUpdateValid` | Post-rename commit trace row payload for the reserved row |
| input | `renameUpdateTSeq` / `renameUpdateUSeq` | `ROBID(mapQDepth)` | with `renameUpdateValid` | Post-rename row-owned local T/U sequence snapshots |
| input | `renameUpdateTUDstValid` / `renameUpdateTUDstKind` | mixed | with `renameUpdateValid` | Post-rename T/U destination ownership sidecar |
| input | `completeValid` | `Bool` | none | Requests completion marking for `completeRobValue` |
| input | `completeRobValue` | `UInt(log2(entries).W)` | with `completeValid` | Slot to mark completed when its status allows completion |
| input | `completeRowValid` | `Bool` | with accepted completion | Requests the completion path to replace the stored `CommitTraceRow` payload before marking the row completed |
| input | `completeRow` | `CommitTraceRow` | with `completeRowValid` | Execute/LSU-produced commit payload; the bank overwrites `valid` and native ROB ID fields from the completed slot |
| output | `completeAccepted` | `Bool` | diagnostic | High when completion updates an allocated/renamed/issued/completed row |
| output | `completeIgnored` | `Bool` | diagnostic | High when completion targets a free, retired, fault, or need-flush row |
| input | `deallocReady` | `Bool` | yes | Allows the deallocation walk to release retired rows this cycle |
| output | `commit` | `CommitTracePort` | fixed-width valid rows | Contiguous completed head rows, slot-labeled and zeroed when invalid |
| output | `commitValidMask` | `UInt(commitWidth.W)` | diagnostic | Valid row mask for the commit window |
| output | `commitCount` | `UInt` | diagnostic | Number of rows marked retired this cycle |
| output | `deallocValidMask` | `UInt(commitWidth.W)` | diagnostic | Deallocated retired-row mask for the dealloc walk |
| output | `deallocCount` | `UInt` | diagnostic | Number of rows freed this cycle |
| output | `commit*Error` | `Bool` | monitor | `CommitTraceMonitor` contract flags for the emitted commit window |
| output | `deallocTURetireSource` | `Vec(commitWidth, TULinkRetireSource)` | diagnostic/source | Row-owned T/U retire sources for each deallocated ROB slot |
| output | `deallocBlockLastValid`, `deallocBlockLastBid`, `deallocBlockLastGid`, `deallocBlockLastBlockBid` | mixed | diagnostic/source | First block-last row freed by the deallocation walk; future `CleanCMAP` scheduling point plus full 64-bit block identity |
| output | `size` | `UInt` | diagnostic | Resident non-free row count; retired rows remain resident |
| output | `outstandingCount` | `UInt` | diagnostic | Model `osdSize`-like count; decremented at commit, not dealloc |
| output | `flushApplied` | `Bool` | diagnostic | A matching valid row was found and the bank applied the prune mask |
| output | `flushDirectMatchMask` | `UInt(entries.W)` | diagnostic | Rows directly covered by the flush BID or BID/RID predicate |
| output | `flushPruneMask` | `UInt(entries.W)` | diagnostic | Rows cleared by the model-style prune region |
| output | `flushPruneBeforeCommitMask` | `UInt(entries.W)` | diagnostic | Pruned rows encountered before the pre-cycle commit pointer |
| output | `flushOutstandingPruneMask` | `UInt(entries.W)` | diagnostic | Pruned rows that decremented outstanding work |
| output | `flushResidentDecrement` | `UInt` | diagnostic | Resident row decrement contributed by this flush |
| output | `flushOutstandingDecrement` | `UInt` | diagnostic | Outstanding-work decrement contributed by this flush |
| output | `flushAllocRebased` | `Bool` | diagnostic | Allocation pointer was rebased to the first pruned row |
| output | `flushAllocRebaseValue` | `UInt(log2(entries).W)` | diagnostic | New allocation pointer value after a matching flush |
| output | `flushCommitRebased` | `Bool` | diagnostic | Commit pointer was rebased because a pruned row was before commit head |
| output | `flushCommitRebaseValue` | `UInt(log2(entries).W)` | diagnostic | First pruned-before-commit slot |
| output | `flushClearedAll` | `Bool` | diagnostic | Flush removed every resident row, so commit/dealloc also rebase |
| output | `robTULinkSource` | `TULinkFlushSequenceSource` | diagnostic/source | Exact ROB row source for non-base T/U cleanup |
| output | `robTULinkSourceMatched` | `Bool` | diagnostic | A live row exactly matched `(flush.bid, flush.rid, flush.stid)` |
| output | `robTULinkSourceMultipleMatch` | `Bool` | diagnostic | More than one live row matched the exact T/U source predicate |
| output | `commitHead*` | mixed | diagnostic | Commit-pointer row status and slot |
| output | `deallocHead*` | mixed | diagnostic | Dealloc-pointer row status and slot |
| output | `occupiedMask` | `UInt(entries.W)` | diagnostic | Non-free slot mask |
| output | `completedMask` | `UInt(entries.W)` | diagnostic | Completed slot mask |
| output | `retiredMask` | `UInt(entries.W)` | diagnostic | Retired slot mask |

## State

- `rows`: one `CommitTraceRow` per slot.
- `rowBid`: native backend/BROB BID sidecar per slot, sourced from `allocBid`.
- `rowGid`: native group sidecar per slot, sourced from `allocGid`.
- `rowRid`: native ROB RID sidecar per slot, allocated from `allocValue` and
  `allocWrap`.
- `rowPeId`: scalar PE owner sidecar per slot, sourced from allocation.
- `rowStid`: STID sidecar per slot, sourced from allocation.
- `rowTSeq` / `rowUSeq`: row-owned local T/U mapQ sequence snapshots.
- `rowTUDstValid` / `rowTUDstKind`: whether the row owns a T/U destination
  that requires previous-sequence cleanup adjustment.
- `rowIsLast`: model block-last sidecar used by relation-cmap release.
- `status`: one `ROBEntryStatus.Type` per slot, reset to `Free`.
- `allocValue` / `allocWrap`: circular allocation pointer.
- `commitValue` / `commitWrap`: circular commit pointer.
- `deallocValue` / `deallocWrap`: circular deallocation pointer.
- `size`: number of resident non-free entries.
- `outstandingCount`: model `osdSize`-like count for entries that still need
  retirement.
- `ROBFlushPrune`: combinational child helper that observes pre-cycle row
  metadata and produces flush masks/counts.

## Logic Design

Allocation rejects a row if the bank is full or any non-free slot has the same
`CommitTraceRow.identity` `(bid,gid,rid)`. An accepted allocation writes
`allocRow`, forces `row.valid`, fills the ROB sideband from the allocation
pointer, stores native flush metadata in `rowBid`/`rowRid`, marks the slot
`Allocated`, advances the allocation pointer, increments `size`, and increments
`outstandingCount`. `rowBid` comes from the backend/BROB owner through
`allocBid`; `rowRid` is allocated locally from the bank allocation pointer,
matching `SPEROB::allocROB` assigning `inst->rid` from `allocPtr`.
The allocation also stores the T/U source sidecars supplied by the dispatch
allocator. In the C++ model, `SPERename::Rename` writes `inst->tSeq` and
`inst->uSeq` before destination rename; `SPEROB::getRetireID` later exposes
those row-owned values, and `SPEROB::CheckDstDataOut`/LSU recovery builders use
them as the selected cleanup source.
R63 stores native `gid` and `isLast` beside those sidecars because
`SPEROB::ReleaseRelative` decides relation-cmap drain/release from
`(bid,gid)` group changes and block-last rows, not from commit-trace identity.
R74 additionally stores `rowPeId` because `SPEROB::ReleaseRelative` and
`RelateInfo` preserve the PE owner used later by
`SPERename::RepLocalRetired`; the reduced path currently supplies PE0, but the
row image must already carry the sidecar.

Post-rename update is the value-row equivalent of the C++ model's shared
`SimInst` pointer. `DCTop::Work` allocates the PE ROB row before writing
`dec_ren_q`, while `SPERename::Rename` later mutates the same instruction with
`tSeq/uSeq` and renamed operand state. In Chisel, `renameUpdateValid` patches a
reserved row only when `renameUpdateRid` matches the stored native `rowRid` and
the pre-cycle status is `Allocated` or `Renamed`. The update rewrites the
stored commit row, forces its ROB sideband to the reserved native RID, updates
T/U sidecars, and changes `Allocated -> Renamed`. It does not rewrite native
`rowBid/rowGid/rowRid/rowPeId/rowStid/rowIsLast`, because those belong to the
allocation-time row owner.

Flush has priority over allocation, completion, commit, and deallocation. The
bank feeds `ROBFlushPrune` with occupied rows, each row's status, and a
native `ROBID` view from `rowBid` and `rowRid`. `CommitTraceRow.identity`
remains the trace and duplicate-detection sideband; it is not the source of the
flush comparison metadata.

The ROB T/U source output is a separate combinational exact-match lookup. It
is valid only when the incoming flush request is non-base and a live row matches
all of `(flush.req.bid, flush.req.rid, flush.req.stid)`. Base-on-BID cleanup
emits an invalid source because `TULinkRename` prunes by BID without local
sequence sidebands. The selected payload is the row's stored
`tSeq/uSeq/dstKind`; it is not reconstructed from trace identity, row index,
or default zeros. Multiple exact matches are reported as a diagnostic and
remain a duplicate-identity violation for later live composition.

When `ROBFlushPrune` finds a match, the bank clears every row in
`flushPruneMask`, marks those slots `Free`, subtracts
`flushResidentDecrement` from `size`, and subtracts
`flushOutstandingDecrement` from `outstandingCount`. The allocation pointer is
rebased to the first pruned row, matching `SPEROB::HandleNextEntryVldAndLessEq`.
If the flush removed all resident rows, `commitPtr` and `deallocPtr` are also
rebased to the new allocation pointer. Otherwise, the commit pointer is rebased
to the first pruned-before-commit row when the selector reports
`commitRebaseNeeded`; if the flush leaves no outstanding work, commit also
rebases to the allocation pointer.

Completion is an idempotent status update for rows in `Allocated`, `Renamed`,
`Issued`, or `Completed`. It does not alter pointers or counts. Completion
requests targeting `Free`, `Retired`, `Fault`, or `NeedFlush` are surfaced as
ignored diagnostics. Completion requests presented during an applied flush are
also reported ignored because flush owns the cycle.

Commit scans from `commitValue` for at most `commitWidth` slots. Each slot can
fire only if all older slots in the same window fire and the current row is
valid with status `Completed`. Fired rows are emitted through
`CommitTracePort`, relabeled with their commit slot, marked `Retired`, and
subtracted from `outstandingCount`. Commit stops at the first non-completed
head.

Deallocation scans from `deallocValue` for at most `commitWidth` slots when
`deallocReady` is high. It frees only contiguous valid `Retired` rows and
stops after the first row whose stored `rowIsLast` sidecar is true, matching
`SPEROB::dealloc()` breaking after `CommitLast(uop)`. Freed rows have their
payloads and native/TU sidecars cleared, are marked `Free`, advance
`deallocValue`, and decrement `size`. A row committed in the current cycle is
not deallocated until a later cycle because the deallocation walk observes the
pre-cycle status array.

For every slot that the deallocation walk will free in the current cycle,
`deallocTURetireSource(slot)` exposes the pre-clear row image:
`valid`, native `bid/gid/rid`, `peId/stid`, `isLast`, `tSeq/uSeq`, and T/U
destination ownership. The output is a vector rather than a collapsed command
so no row is lost when `commitWidth` deallocates more than one retired row.
`TULinkRelationCmap` is the downstream policy owner that serializes this
vector into mark/dealloc commands for `TULinkRename`.

When a deallocated row is block-last, `deallocBlockLastValid` and its
`bid/gid` outputs identify the first such row in the deallocation window.
`deallocBlockLastBlockBid` preserves the full hardware block BID already stored
in the row's `CommitTraceRow.blockBid`, which is the sideband needed by BROB
scalar-completion and retire owners. This is intentionally source plumbing for
later relation-clean scheduling; firing `CleanCMAP` directly from ROB commit
would be too early because the serialized relation-cmap `ReleaseRelative` work
for the block-last row must complete first.

The commit output feeds `CommitTraceMonitor`; monitor errors mean the fixed
commit window is not safe for QEMU comparison.

## Timing

All walks observe pre-cycle state. Completion becomes visible to commit on the
next cycle unless the row was already `Completed`. Commit changes
`Completed -> Retired`; deallocation changes `Retired -> Free` on a later
visible cycle. Allocation backpressure is based on pre-cycle `size`, so a full
bank does not accept an allocation in the same cycle that deallocation frees
space. A matching flush suppresses allocation, completion, commit, and
deallocation for the cycle so row clearing and count updates are not
double-counted.

## Flush/Recovery

`ROBEntryBank` now applies the row-clearing and count-accounting portion of
`SPEROB::flush` through `ROBFlushPrune`. It also implements the allocation and
commit pointer rebasing that follows the selected prune point. The following
model behaviors remain future integrated ROB/CMT work:

- deeper dispatch/BROB integration beyond the current `DispatchROBAllocator`
  allocation bridge,
- checkpoint/rename restore,
- local ready-table and physical destination cleanup,
- LSU/STQ/SCB cleanup, LSID rebasing, and LSU-originated T/U source sidecars,
- branchVld/inner-jump recovery state,
- precise trap ownership,
- frontend restart token publication.

`RecoveryCleanupControl` is now the registered intent boundary for those
future consumers. `ROBEntryBank` should continue to consume only the ROB prune
portion until the downstream cleanup owners exist.

## Trace/Observability

The emitted commit window uses the existing `CommitTraceRow` schema and
`CommitTraceMonitor` checks. Invalid fixed-width commit slots are zeroed before
they reach the monitor or trace adapter. `occupiedMask`, `completedMask`, and
`retiredMask` expose row lifecycle state for early Verilator harnesses without
turning status into an architectural trace format.

## Verification

- `bash tools/chisel/run_chisel_tests.sh --only ROBEntryBank`
- `bash tools/chisel/run_chisel_tests.sh --only RecoveryCleanupControl`
- `bash tools/chisel/run_chisel_tests.sh --only DispatchROBAllocator`
- `bash tools/chisel/run_chisel_tests.sh --only ROBFlushPrune`
- `bash tools/chisel/run_chisel_tests.sh --only ROBEntryStatus`
- `bash tools/chisel/run_chisel_tests.sh --only FlushControl`
- `bash tools/chisel/run_chisel_tests.sh --only ReducedCommitROB`
- `bash tools/chisel/run_chisel_rob_bookkeeping.sh --reduced-rob`
- `python3 tools/chisel/trace_schema_adapter.py --self-test`
- `bash tools/chisel/run_chisel_qemu_crosscheck.sh --dry-run`
- `bash tools/chisel/run_chisel_reduced_rob_xcheck.sh`
- `bash tools/chisel/build_chisel.sh`

Focused tests cover commit/dealloc phase separation, block-last deallocation
window stopping, full block-BID exposure on the block-last deallocation event,
incomplete-head blocking, duplicate identity rejection until deallocation,
post-rename sidecar patching of a reserved row, deallocation
backpressure, ignored invalid completion targets,
RID-based flush pruning through the entry bank reference model, allocation
reuse of the first pruned slot, retired-row
flush accounting, flush comparison through native row IDs rather than trace
identity sidebands, exact non-base ROB T/U source publication and source clear
after flush, deallocation-row T/U retire source publication with native
`bid/gid/rid` plus `peId/stid`, and Chisel elaboration with monitor plus
flush/TU diagnostic outputs.
