# ScalarGPRFile

## Source Mapping

- Chisel: `chisel/src/main/scala/linxcore/execute/ScalarGPRFile.scala`
- Compatibility wrapper: `chisel/src/main/scala/linxcore/execute/ReducedScalarRegisterFile.scala`
- Tests: `chisel/src/test/scala/linxcore/execute/ScalarGPRFileSpec.scala`
- Model: `model/LinxCoreModel/model/iex/iex_rf.cpp`, `RegFile::recieveWFReqFromRT`;
  `model/LinxCoreModel/model/iex/iex_iq.cpp`, `IssueQueue::WakeupIQTag`;
  `model/LinxCoreModel/configs/core.toml`, `ggpr_count`

## Contract

`ScalarGPRFile` is the canonical scalar physical GPR data and non-speculative
P-tag ready-table owner. Its capacity, read-port count, and write-port count
are independent parameters. The default 128 physical entries match
LinxCoreModel `ggpr_count`; exactly 24 architectural identity tags reset ready.

A write port has two phases:

1. `requestValid` reserves/arbitrates the candidate tag and produces `ready`.
2. `commit` authorizes state mutation; `fire` is request, commit, and ready.

Request without commit never changes data or readiness. This distinction lets
a retained W2 producer hold a port candidate while ROB resolve or another
required side effect is blocked.

## Interface

| Signal | Contract |
|---|---|
| `readValid/readTag` | Parameterized combinational physical-tag reads. |
| `readData/readReady` | Registered data and non-speculative readiness; an invalid read request is ready by convention. |
| `initValid/initTag/initData` | Bring-up initialization of one identity-mapped tag. |
| `clearValid/clearTag` | Rename allocation marks one destination not-ready. |
| `write[*].requestValid/tag/data` | Candidate write payload used for port and same-tag arbitration. |
| `write[*].ready` | The port request has no higher-priority same-tag request. |
| `write[*].commit/fire` | Commit authorization and accepted state mutation. |
| `readyMask` | Current P-tag ready table consumed by issue queues. |
| `clearWriteCollision` | Rename clear and committed write target one tag in the same cycle. |
| `duplicateWriteCommit` | More than one committed port targets one tag. |
| `protocolError` | Non-unique or incoherent state mutation. |

## Arbitration And Timing

Lower-numbered ports have same-tag priority. Independent tags may commit in
the same cycle. A one-port configuration serializes every producer; additional
ports increase bandwidth without changing physical identity width.

Reads observe current registered state. Clear and committed writes update on
the rising edge. A committed write stores data and sets readiness together, so
an issue queue observing `readyMask` can capture the wakeup for its next pick
cycle. There is no same-cycle pick effect.

Clear/write collision and duplicate committed writes are errors rather than
implicit priority rules. Reusing a physical tag before its prior producer has
finished is an ownership failure that must be fixed at rename/recovery.

## Scope

This module owns global scalar GPR (`P`) state only. T/U local-link banks use
PE/STID-scoped storage and qtag point-to-point wakeup. Vector, predicate-mask,
stack, and architectural-control state are separate owners. No ARM register,
condition-code, exception-level, or exclusive-monitor behavior is present.

## Verification

- `bash tools/chisel/run_chisel_tests.sh --only ScalarGPRFileSpec`
- `bash tools/chisel/run_chisel_tests.sh --only ReducedScalarRegisterFileSpec`
- `bash tools/chisel/run_chisel_scalar_load_completion_rob_probe.sh`

The generated probe proves request hold without mutation, same-tag external
priority, independent dual-port writes, scalar data retention, P-tag readiness,
and exact ROB completion from one W2 acceptance.
