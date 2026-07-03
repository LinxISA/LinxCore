# LoadReplayReturnIexPipeInsertCandidate

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnIexPipeInsertCandidate.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/LoadReplayReturnIexPipeInsertCandidateSpec.scala`
- Integrated user: `rtl/LinxCore/chisel/src/main/scala/linxcore/top/LinxCoreFrontendFetchRfAluTraceTop.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/iex/iex.cpp`
    - `IEX::receiveFromLSU`
    - `IEX::BackToPipe`
    - `IEX::setMemData`
  - `model/LinxCoreModel/model/ModelCommon/bus/MemReqBus.h`
- Related Chisel contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnIexDrainPermit.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnIexDataCandidate.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnRobResolveDataCandidate.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnLaneCompletionCandidate.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnTloadCompletionCandidate.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnFinalMetadataCandidate.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnTimingStatsCandidate.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeResidencyCandidate.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayDestination.scala`
- Contract IDs: `LC-CHISEL-LSU-REPLAY-IEX-PIPE-INSERT-001`

## Purpose

`LoadReplayReturnIexPipeInsertCandidate` is the diagnostic payload boundary
after the reduced `IEX::setMemData` admission checks and before any real IEX
E4 pipe residency mutation. In LinxCoreModel, `IEX::receiveFromLSU` reads an
LRET queue only after `BackToPipe` finds free pipe capacity. `IEX::setMemData`
then skips invalid or need-flush ROB rows, resolves returned data into the ROB
instruction, clones the instruction as a load-return, marks `isLoadReturn`,
and inserts that clone into the first free LDA or AGU E4 pipe.

R322 names only the final insert-shaped diagnostic payload. After R327, the
integrated path consumes the timing/stat boundary's `readyForPipeInsert`
result, still derived from the R320/R321 `setMemDataValid` admission checks,
R323 ROB resolve-data diagnostic, R324 lane-completion diagnostic, R325 TLOAD
completion diagnostic, plus the R319 IEX pipe selection, and emits an
`insertValid` candidate with:

- the chosen IEX E4 insertion pipe index;
- the original MemReqBus load-to-use pipe sideband as a separate index;
- returned-load identity and scalar data;
- the compact reduced destination sideband;
- `isLoadReturn` and wakeup-required diagnostics.

This module does not write an IEX pipe, mutate the ROB, update RF state,
publish ready-table state, wake issue queues, clear replay rows, or drive the
LRET FIFO drain.

R323 inserts `LoadReplayReturnRobResolveDataCandidate` before this module, and
R324 inserts `LoadReplayReturnLaneCompletionCandidate` after R323. R325 then
inserts `LoadReplayReturnTloadCompletionCandidate` after lane completion. R326
inserts `LoadReplayReturnFinalMetadataCandidate` before this insert-shaped
diagnostic, and R327 inserts `LoadReplayReturnTimingStatsCandidate` after that
metadata point. The integrated top now feeds this module from R327
`readyForPipeInsert`, matching the model order where `ROBState::resolveData`
runs, scalar load-pair/vector/MEM lane completion is checked, MEM-IEX TLOAD
sub-instruction completion is checked, final load-return metadata is stamped,
and timing/stat sideband intent is emitted before the cloned instruction is
inserted into E4. R328 consumes this module's insert diagnostics in
`LoadReplayReturnPipeResidencyCandidate` to expose the following LDA/AGU E4
residency intent while keeping real pipe state disabled.

## Interface

| Direction | Signal | Description |
|---|---|---|
| input | `enable` | Replay-LIQ wrapper is active. |
| input | `flush` | Suppresses insert diagnostics for the cycle. |
| input | `setMemDataValid` | R320/R321 admission result after LRET head, drain permit, and ROB row-status checks. |
| input | `pipeInsertReady` | IEX-side E4 insertion capacity predicate from the R319 drain permit. |
| input | `pipeInsertIndex` | Chosen IEX E4 insertion pipe. |
| input | `memBid` / `memGid` / `memRid` / `memLoadLsId` | Returned-load identity copied from the admitted LRET payload. |
| input | `memPc` / `memAddr` / `memSize` / `memData` / `memDst` | Returned-load payload and reduced destination sideband. |
| input | `memLoadToUsePipeIndex` | Original MemReqBus `pipeID`/load-to-use sideband, kept separate from `pipeInsertIndex`. |
| input | `memSpecWakeup` / `memStackValid` | Wakeup suppression sidebands. |
| output | `candidateValid` | Enabled, not flushing, and admitted to the reduced `setMemData` boundary. |
| output | `insertValid` | Candidate plus pipe-ready plus valid RID. |
| output | `insertIsLoadReturn` | Diagnostic equivalent of model `inst->isLoadReturn = true`, asserted only with `insertValid`. |
| output | `insertPipeIndex` | Chosen IEX E4 insertion pipe when valid. |
| output | `insertLoadToUsePipeIndex` | Original load-to-use sideband when valid. |
| output | `insert*` payload fields | Copied identity, request, destination, and data fields when valid. |
| output | `insertWakeupRequired` | `insertValid && !memSpecWakeup && !memStackValid`. |
| output | `blockedBy*` | Disabled, flush, no-setMemData, no-pipe, and invalid-RID blockers. |

## Logic Design

```text
candidateValid = enable && !flush && setMemDataValid
insertValid = candidateValid && pipeInsertReady && memRid.valid
insertIsLoadReturn = insertValid
insertWakeupRequired = insertValid && !memSpecWakeup && !memStackValid
```

All payload outputs are disabled or zero unless `insertValid` is true. The
module intentionally keeps two pipe identifiers distinct:

- `pipeInsertIndex` is the IEX E4 pipe chosen by the drain/pipe-capacity owner.
- `memLoadToUsePipeIndex` is the MemReqBus `pipeID` sideband that originated
  from load execution and load-to-use tracking.

## Integration

`LinxCoreFrontendFetchRfAluTraceTop` wires R322 behind
`LoadReplayReturnTimingStatsCandidate`:

- `setMemDataValid` is driven by the R327 `readyForPipeInsert` diagnostic;
- returned-load payload fields still come from
  `LoadReplayReturnIexDataCandidate`;
- `pipeInsertReady` and `pipeInsertIndex` come from
  `LoadReplayReturnIexDrainPermit`;
- top-level outputs expose insert validity, pipe indices, load-return marker,
  wakeup-required, and blockers.

The integrated top still ties `LoadReplayReturnLretSink.drainReady` low and
ties the current IEX return-pipe occupied mask full. Therefore R322 remains a
diagnostic insert boundary only.
R328 observes this module's candidate and insert-valid outputs, reports the
scalar LDA or vector AGU residency target, and keeps `liveEnable` false in the
current reduced top.

## Deferred Owners

- Real IEX LDA/AGU E4 pipe residency mutation.
- Real residency owner behind the R328 diagnostic boundary.
- `rob_next.resolveData` and ROB destination data-valid mutation.
- Real scalar load-pair lane writes and vector/MEM_IEX request-count state.
- Real TLOAD tile-SCB side effects and load-branch resolve.
- Real final load-return metadata, pipe-cycle/stat storage, and timing sidecar
  payload sources.
- LRET FIFO drain enable and replay-row lifecycle retirement.

## Verification

Focused gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnIexPipeInsertCandidate
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeResidencyCandidate
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnTimingStatsCandidate
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnFinalMetadataCandidate
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnTloadCompletionCandidate
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnLaneCompletionCandidate
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnIexDataCandidate
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnIexDrainPermit
bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop
FETCH_REDUCED_STORE_REPLAY_LIQ=1 BUILD_DIR=generated/r322-replay-iex-pipe-insert-xcheck bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh
```

Reference tests cover successful insert-candidate formation, wakeup-required
suppression for speculative or stack rows, disabled/flush/no-setMemData/no-pipe
blockers, invalid-RID blocking, and Chisel elaboration.
