# LoadReplayReturnPipeResidencyCandidate

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeResidencyCandidate.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/LoadReplayReturnPipeResidencyCandidateSpec.scala`
- Integrated user: `rtl/LinxCore/chisel/src/main/scala/linxcore/top/LinxCoreFrontendFetchRfAluTraceTop.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/iex/iex.cpp`
    - `IEX::setMemData`
- Related Chisel contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnIexDrainPermit.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnIexPipeInsertCandidate.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeResidencySlot.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnTimingStatsCandidate.scala`
- Contract IDs: `LC-CHISEL-LSU-REPLAY-PIPE-RESIDENCY-001`

## Purpose

`LoadReplayReturnPipeResidencyCandidate` is the diagnostic boundary for the
model's returned-load E4 pipe residency assignment. In `IEX::setMemData`, after
the returned load has passed final metadata and timing/stat sideband handling,
the model chooses the first free AGU E4 pipe for vector IEX machines and the
first free LDA E4 pipe for scalar IEX machines. If no target pipe is free, the
model asserts.

R328 names that post-insert residency intent without mutating live E4 pipe
state. It consumes the R322 insert-shaped diagnostic, reports the scalar-LDA or
vector-AGU target domain, carries the selected pipe index, and separates these
blockers:

- no insert candidate;
- no accepted insert;
- live residency writes disabled;
- selected pipe occupied.

The current reduced scalar top ties `liveEnable` low and `isVectorMachine` low,
so this packet exposes LDA residency intent and full-pipe diagnostics while
keeping actual pipe state disabled.

R329 consumes `residencyWriteValid`, target-domain, and selected-pipe outputs
in `LoadReplayReturnPipeResidencySlot`, a dormant one-entry state owner that
captures the R322 payload only when a future integration enables live residency
writes.

## Interface

| Direction | Signal | Description |
|---|---|---|
| input | `enable` | Replay-LIQ wrapper is active. |
| input | `flush` | Suppresses residency diagnostics for the cycle. |
| input | `insertCandidateValid` | R322 insert-shaped candidate exists after timing/stat handling. |
| input | `insertValid` | R322 accepted insert candidate. |
| input | `liveEnable` | Enables real residency write intent. Current top ties this false. |
| input | `isVectorMachine` | Selects AGU residency when true, LDA residency when false. |
| input | `selectedPipeIndex` | Pipe index selected by the IEX drain/pipe-capacity owner. |
| input | `selectedPipeOccupied` | Diagnostic occupancy for the selected return pipe. |
| output | `candidateValid` | Enabled, not flushing, and an insert-shaped candidate exists. |
| output | `residencyArmed` | Candidate plus accepted insert. |
| output | `residencyWriteValid` | Armed, live enabled, and selected pipe is not occupied. |
| output | `liveEnabled` | Mirrors `liveEnable` for top-level diagnostics. |
| output | `targetIsAgu` | Candidate targets the vector/AGU pipe family. |
| output | `targetIsLda` | Candidate targets the scalar/LDA pipe family. |
| output | `targetPipeIndex` | Selected pipe index when a candidate exists, otherwise zero. |
| output | `blockedBy*` | Disabled, flush, no-candidate, no-accepted-insert, live-disabled, and pipe-occupied blockers. |

## Logic Design

```text
candidateValid = enable && !flush && insertCandidateValid
residencyArmed = candidateValid && insertValid
pipeWritable = residencyArmed && !selectedPipeOccupied
residencyWriteValid = pipeWritable && liveEnable
targetIsAgu = candidateValid && isVectorMachine
targetIsLda = candidateValid && !isVectorMachine
```

`residencyArmed` names the model point where `pipe.e4_inst = inst` would be
legal if the selected target is free. `residencyWriteValid` remains false in
the current integrated top because live E4 pipe state is not owned yet.

## Integration

`LinxCoreFrontendFetchRfAluTraceTop` wires R328 after
`LoadReplayReturnIexPipeInsertCandidate`:

- `insertCandidateValid` comes from R322 `candidateValid`;
- `insertValid` comes from R322 `insertValid`;
- `selectedPipeIndex` comes from R322 `insertPipeIndex`;
- `selectedPipeOccupied` is derived from the R319 drain permit's free-pipe
  diagnostic;
- `liveEnable` is tied false;
- `isVectorMachine` is tied false for the current reduced scalar path.

The top exposes candidate, armed, write-valid, target-domain, pipe-index, and
blocker diagnostics.

R329 additionally feeds `residencyWriteValid`, target-domain, and pipe-index
diagnostics into `LoadReplayReturnPipeResidencySlot`. Since `liveEnable` remains
false in this top, the slot observes no writes and only reports dormant
occupancy/no-write state.

## Deferred Owners

- Live enable of the dormant one-entry LDA/AGU E4 pipe residency slot.
- Multi-entry LDA/AGU E4 pipe residency storage and lifecycle.
- Multi-pipe AGU/LDA first-free scan state beyond the current diagnostic
  selected index.
- Vector IEX machine classification in the reduced top.
- FIFO drain enable, ROB/RF/ready-table mutation, issue wakeup, and replay-row
  retirement after residency.

## Verification

Focused gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeResidencyCandidate
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeResidencySlot
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnIexPipeInsertCandidate
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnTimingStatsCandidate
bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop
FETCH_REDUCED_STORE_REPLAY_LIQ=1 BUILD_DIR=generated/r328-replay-pipe-residency-xcheck bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh
```

Reference tests cover scalar LDA target diagnostics, vector AGU target
diagnostics, live-disabled blocking, occupied-pipe blocking, disabled/flush
blocking, missing insert candidate, missing accepted insert, and Chisel
elaboration.
