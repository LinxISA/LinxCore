# LoadReplayReturnWritebackCandidate

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnWritebackCandidate.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/LoadReplayReturnWritebackCandidateSpec.scala`
- Integrated user: `rtl/LinxCore/chisel/src/main/scala/linxcore/top/LinxCoreFrontendFetchRfAluTraceTop.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/iex/iex.cpp`
    - `IEX::setMemData`
    - `IEX::setMemWakeup`
  - `model/LinxCoreModel/model/iex/iex_iq.cpp`
    - `IssueQueue::WakeupIQTag`
- Related Chisel contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayDestination.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnLretPayload.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnWakeupCandidate.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/execute/ScalarGPRFile.scala`
- Contract IDs: `LC-CHISEL-LSU-REPLAY-WB-001`

## Purpose

`LoadReplayReturnWritebackCandidate` is the diagnostic boundary between the
R311 replay LRET payload and a future reduced RF writeback owner. LinxCoreModel
`IEX::setMemData` copies returned memory data into the ROB instruction's
destination payloads, while `IEX::setMemWakeup` and
`IssueQueue::WakeupIQTag` separately publish dependent wakeup and ready-table
state. The current reduced Chisel path does not own those side effects yet.

This module converts a valid replay LRET payload plus one compact destination
sideband into a GPR writeback-shaped candidate. It does not write the RF,
update the ready table, wake issue queues, enqueue LRET, or feed replay launch
readiness.

## Interface

| Direction | Signal | Description |
|---|---|---|
| input | `enable` | Replay-LIQ wrapper is active. |
| input | `payloadValid` | `LoadReplayReturnLretPayload` has a valid diagnostic payload. |
| input | `payloadDst` | One replay destination sideband from the payload. |
| input | `payloadData` | Sign/zero-extended scalar return data. |
| output | `candidateValid` | Payload is valid while the wrapper is enabled. |
| output | `writeValid` | Candidate is a reduced scalar GPR writeback. |
| output | `writeTag` | Destination physical tag for the future RF write port. |
| output | `writeData` | Return data for the future RF write port. |
| output | `ignoredNoDestination` | Payload exists but no destination sideband is present. |
| output | `ignoredNonGprDestination` | Payload destination is T/U or another non-GPR class. |
| output | `blockedByDisabled` | Payload exists while replay-LIQ mode is disabled. |

## Logic Design

The model return path has two separate effects:

1. `IEX::setMemData` writes returned data into ROB destination payloads.
2. `IEX::setMemWakeup` eventually calls `IssueQueue::WakeupIQTag`, which marks
   ready-table state and wakes dependent issue queues.

R312 mirrors only the first reduced scalar shape as diagnostics:

```text
candidateValid = enable && payloadValid
writeValid = candidateValid && payloadDst.valid && payloadDst.kind == Gpr
writeTag = writeValid ? payloadDst.physTag : 0
writeData = writeValid ? payloadData : 0
```

Non-GPR destinations are explicit diagnostics because the current reduced RF
only owns scalar GPR state. T/U, vector, tile, load-pair, and multi-destination
returns remain future owners.

## Deferred Owners

- Arbitration into the real reduced RF write port.
- Ready-table and issue-queue wakeup publication.
- ROB destination-data update storage.
- T/U, vector, tile, and load-pair destination writeback.
- LRET enqueue and replay launch backpressure from writeback/wakeup queues.

## Verification

Focused gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnWritebackCandidate
bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop
FETCH_REDUCED_STORE_REPLAY_LIQ=1 BUILD_DIR=generated/r312-replay-return-writeback-candidate-xcheck bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh
```

Reference tests cover GPR writeback candidate formation, missing destination
diagnostics, non-GPR suppression, disabled payload blocking, stale-field
suppression, and Chisel elaboration.
