# ROB Commit Model Notes

## Source Mapping

- LinxCoreModel: `model/LinxCoreModel/model/bctrl/spe/SPEROB.cpp`
- LinxCoreModel: `model/LinxCoreModel/model/bctrl/spe/SPEROB.h`
- LinxCoreModel: `model/LinxCoreModel/model/pe/PECommon/PROBStatus.h`
- LinxCoreModel: `model/LinxCoreModel/model/pe/PECommon/PROBCommon.h`
- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/rob/ROBEntryStatus.scala`
- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/rob/ROBEntryBank.scala`
- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/rob/ROBFlushPrune.scala`
- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/backend/DispatchROBAllocator.scala`
- LinxCoreModel: `model/LinxCoreModel/model/veclite/VectorLiteROB.cpp`
- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/rob/ReducedCommitROB.scala`

## Model Contract

`SPEROB::commit` walks from `next.commitPtr` for at most
`speTop->configs.retiredWidth` entries. It retires only while the current head
entry is valid and `INST_COMPLETED`; the loop breaks at the first invalid or
incomplete head. For each retired row, the model marks the entry
`INST_RETIRED`, records trace, updates commit-visible statistics and side
effects, then advances `commitPtr` with `IncROBID`.

`SPEROB::dealloc` is a separate walk from `next.deallocPtr`. It releases only
`INST_RETIRED` rows, applies cleanup and side effects, then marks the slot
`INST_FREE` and decrements `size`. Chisel integrated ROB work must not collapse
`INST_COMPLETED` and `INST_RETIRED` into one state.

`SPEROB::flush` and `CheckNextEntryStatus` treat allocated, renamed, issued,
completed, and need-flush rows as outstanding work. Free and already-retired
rows are not part of that outstanding-work decrement. `ROBEntryStatus.osdActive`
and `flushClears` preserve this accounting shape.

`getRetireID` exposes the commit-head identity as `(bid, gid, rid)` plus PE and
TPC context. This aligns with the Chisel `CommitTraceRow.identity` sideband, but
full architectural commit effects remain owned by the later integrated ROB/CMT
stage.

`VectorLiteROB::CheckCommit` is less precise for scalar ROB behavior but
confirms the same reduced property for block-side work: completed entries can
move to retired state, while backpressure stops the walk.

## Hardware Direction

The Chisel `ReducedCommitROB` is a Phase 0B harness, not the final ROB. It owns
only enough state to prove:

- allocation into a circular ROB,
- duplicate `(bid,gid,rid)` rejection,
- completion marking by ROB slot,
- head-ordered retirement up to `commitWidth`,
- commit trace row emission through `CommitTracePort`.

It intentionally does not yet model deallocation, rename cleanup, store drain,
precise exception side effects, branch checkpoint recovery, or full flush
pointer rebasing.

The Chisel `ROBEntryStatus` package is the first Phase 5 preparation slice. It
locks the `PROBStatus` numeric order and helper predicates that later ROB banks,
CMT control, and recovery prune modules must share.

The Chisel `ROBEntryBank` is the first status-backed bank skeleton. It preserves
the model's separate `allocPtr`, `commitPtr`, and `deallocPtr` shape, accepts
completion by ROB slot, emits monitored commit rows only for contiguous
completed heads, marks those rows `Retired`, and frees only retired rows on the
later deallocation walk. Retired rows stay resident and continue to reject
duplicate `(bid,gid,rid)` allocations until deallocation clears them. The bank
now applies `ROBFlushPrune` as a priority recovery phase: an applied flush
suppresses allocation/completion/commit/dealloc for the cycle, clears pruned
rows, decrements resident and outstanding counts with the selector's accounting,
rebases `allocPtr` to the first pruned row, and rebases `commitPtr` when the
model walk prunes before the old commit head or leaves no outstanding work. The
bank now stores native row BID/RID sidecars for that flush comparison:
`allocBid` is supplied by the backend/BROB owner, while RID is allocated locally
from the bank allocation pointer. `CommitTraceRow.identity` remains the commit
trace and duplicate-detection sideband and must not be reused as the recovery
comparison key.

The Chisel `ROBFlushPrune` helper captures the selection part of
`SPEROB::flush`: scan from the deallocation pointer, match either
`flush.bid <= row.bid` or `(flush.bid, flush.rid) <= (row.bid, row.rid)`,
and once the first valid row matches, prune that row plus all later valid rows
in scan order. Its outstanding decrement follows `CheckNextEntryStatus`, so
retired/fault/free rows do not decrement outstanding work even when resident
rows are removed.

The Chisel `DispatchROBAllocator` is the first integration owner that provides
the bank's `allocBid` from a block owner rather than a unit-test fixture. It
generates the next full hardware BID, allocates `BrobMetaTracker` and
`ROBEntryBank` atomically, writes the full BID into the row `blockBid` sideband,
and converts the full BID into the `ROBID` sidecar consumed by
`ROBEntryBank`. RID remains allocated by the ROB bank.

## Open Items

- Replace the reduced harness with integrated ROB banks and CMT control in
  Phase 5.
- Define the recovery handoff between full hardware BID and ring `ROBID`
  sidecars so BROB flush and ROB row pruning share one explicit contract.
- Add rename cleanup, LSU/STQ side effects, precise trap ownership, and restart
  ownership to the entry-bank/CMT path; do not retrofit those behaviors into
  `ReducedCommitROB`.
- Add live Verilator trace dumping once the reduced harness has a small driver.
- Connect memory side-effect ownership to LSU/STQ instead of test-provided row
  payloads.
- Add precise trap and recovery restart ownership once FLS/recovery state is
  integrated.
