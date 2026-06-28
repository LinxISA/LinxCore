# ROB Commit Model Notes

## Source Mapping

- LinxCoreModel: `model/LinxCoreModel/model/bctrl/spe/SPEROB.cpp`
- LinxCoreModel: `model/LinxCoreModel/model/bctrl/spe/SPEROB.h`
- LinxCoreModel: `model/LinxCoreModel/model/veclite/VectorLiteROB.cpp`
- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/rob/ReducedCommitROB.scala`

## Model Contract

`SPEROB::commit` walks from `next.commitPtr` for at most
`speTop->configs.retiredWidth` entries. It retires only while the current head
entry is valid and `INST_COMPLETED`; the loop breaks at the first invalid or
incomplete head. For each retired row, the model marks the entry
`INST_RETIRED`, records trace, updates commit-visible statistics and side
effects, then advances `commitPtr` with `IncROBID`.

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

## Open Items

- Replace the reduced harness with integrated ROB banks and CMT control in
  Phase 5.
- Add live Verilator trace dumping once the reduced harness has a small driver.
- Connect memory side-effect ownership to LSU/STQ instead of test-provided row
  payloads.
- Add precise trap and recovery restart ownership once FLS/recovery state is
  integrated.
