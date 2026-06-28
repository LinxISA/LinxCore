# ROB Commit Model Notes

## Source Mapping

- LinxCoreModel: `model/LinxCoreModel/model/bctrl/spe/SPEROB.cpp`
- LinxCoreModel: `model/LinxCoreModel/model/bctrl/spe/SPEROB.h`
- LinxCoreModel: `model/LinxCoreModel/model/pe/PECommon/PROBStatus.h`
- LinxCoreModel: `model/LinxCoreModel/model/pe/PECommon/PROBCommon.h`
- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/rob/ROBEntryStatus.scala`
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

## Open Items

- Replace the reduced harness with integrated ROB banks and CMT control in
  Phase 5.
- Connect `ROBEntryStatus` into the first integrated ROB entry bank; do not
  retrofit status mutation into `ReducedCommitROB`.
- Add live Verilator trace dumping once the reduced harness has a small driver.
- Connect memory side-effect ownership to LSU/STQ instead of test-provided row
  payloads.
- Add precise trap and recovery restart ownership once FLS/recovery state is
  integrated.
