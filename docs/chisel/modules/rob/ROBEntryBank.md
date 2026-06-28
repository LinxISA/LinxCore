# ROBEntryBank

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/rob/ROBEntryBank.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/rob/ROBEntryBankSpec.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/pe/PECommon/PROBStatus.h`
  - `model/LinxCoreModel/model/pe/PECommon/PROBCommon.h`
  - `model/LinxCoreModel/model/bctrl/spe/SPEROB.cpp`
- Related Chisel contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/backend/DispatchROBAllocator.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/recovery/RecoveryCleanupControl.scala`
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
| output | `allocRobValue` | `UInt(log2(entries).W)` | diagnostic | ROB slot that will be assigned on an accepted allocation |
| input | `completeValid` | `Bool` | none | Requests completion marking for `completeRobValue` |
| input | `completeRobValue` | `UInt(log2(entries).W)` | with `completeValid` | Slot to mark completed when its status allows completion |
| output | `completeAccepted` | `Bool` | diagnostic | High when completion updates an allocated/renamed/issued/completed row |
| output | `completeIgnored` | `Bool` | diagnostic | High when completion targets a free, retired, fault, or need-flush row |
| input | `deallocReady` | `Bool` | yes | Allows the deallocation walk to release retired rows this cycle |
| output | `commit` | `CommitTracePort` | fixed-width valid rows | Contiguous completed head rows, slot-labeled and zeroed when invalid |
| output | `commitValidMask` | `UInt(commitWidth.W)` | diagnostic | Valid row mask for the commit window |
| output | `commitCount` | `UInt` | diagnostic | Number of rows marked retired this cycle |
| output | `deallocValidMask` | `UInt(commitWidth.W)` | diagnostic | Deallocated retired-row mask for the dealloc walk |
| output | `deallocCount` | `UInt` | diagnostic | Number of rows freed this cycle |
| output | `commit*Error` | `Bool` | monitor | `CommitTraceMonitor` contract flags for the emitted commit window |
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
| output | `commitHead*` | mixed | diagnostic | Commit-pointer row status and slot |
| output | `deallocHead*` | mixed | diagnostic | Dealloc-pointer row status and slot |
| output | `occupiedMask` | `UInt(entries.W)` | diagnostic | Non-free slot mask |
| output | `completedMask` | `UInt(entries.W)` | diagnostic | Completed slot mask |
| output | `retiredMask` | `UInt(entries.W)` | diagnostic | Retired slot mask |

## State

- `rows`: one `CommitTraceRow` per slot.
- `rowBid`: native backend/BROB BID sidecar per slot, sourced from `allocBid`.
- `rowRid`: native ROB RID sidecar per slot, allocated from `allocValue` and
  `allocWrap`.
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

Flush has priority over allocation, completion, commit, and deallocation. The
bank feeds `ROBFlushPrune` with occupied rows, each row's status, and a
native `ROBID` view from `rowBid` and `rowRid`. `CommitTraceRow.identity`
remains the trace and duplicate-detection sideband; it is not the source of the
flush comparison metadata.

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
`deallocReady` is high. It frees only contiguous valid `Retired` rows, clears
their payloads, marks them `Free`, advances `deallocValue`, and decrements
`size`. A row committed in the current cycle is not deallocated until a later
cycle because the deallocation walk observes the pre-cycle status array.

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
- LSU/STQ/SCB cleanup and LSID rebasing,
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

Focused tests cover commit/dealloc phase separation, incomplete-head blocking,
duplicate identity rejection until deallocation, deallocation backpressure,
ignored invalid completion targets, RID-based flush pruning through the entry
bank reference model, allocation reuse of the first pruned slot, retired-row
flush accounting, flush comparison through native row IDs rather than trace
identity sidebands, and Chisel elaboration with monitor plus flush diagnostic
outputs.
