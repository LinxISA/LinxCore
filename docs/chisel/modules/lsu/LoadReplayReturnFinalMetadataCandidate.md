# LoadReplayReturnFinalMetadataCandidate

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnFinalMetadataCandidate.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/LoadReplayReturnFinalMetadataCandidateSpec.scala`
- Integrated user: `rtl/LinxCore/chisel/src/main/scala/linxcore/top/LinxCoreFrontendFetchRfAluTraceTop.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/iex/iex.cpp`
    - `IEX::setMemData`
    - `IEX::loadBranchResolve`
  - `model/LinxCoreModel/model/ModelCommon/bus/MemReqBus.h`
- Related Chisel contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnTloadCompletionCandidate.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnTimingStatsCandidate.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnIexPipeInsertCandidate.scala`
- Contract IDs: `LC-CHISEL-LSU-REPLAY-FINAL-METADATA-001`

## Purpose

`LoadReplayReturnFinalMetadataCandidate` is the diagnostic final metadata
boundary in the model `IEX::setMemData` sequence. After TLOAD completion, the
model marks the cloned instruction as a load return, invokes
`loadBranchResolve(inst)`, copies memory-return timing sidebands into
`inst->pipeCycle`, updates load-latency statistics, and then inserts the clone
into the first free E4 pipe.

Current LinxCoreModel `loadBranchResolve` is intentionally a no-op: the body
is commented out and the function returns after `(void)inst`. R326 therefore
records that the call point exists while exposing `loadBranchResolveSideEffectValid`
as false. This keeps the branch-resolve boundary explicit for future model
support without inventing recovery side effects today.

## Interface

| Direction | Signal | Description |
|---|---|---|
| input | `enable` | Replay-LIQ wrapper is active. |
| input | `flush` | Suppresses final metadata diagnostics for the cycle. |
| input | `tloadCompletionValid` | R325 post-TLOAD completion permit. |
| output | `candidateValid` | Enabled, not flushing, and post-TLOAD completion input is valid. |
| output | `isLoadReturnMarked` | Diagnostic equivalent of model `inst->isLoadReturn = true`. |
| output | `loadBranchResolveCalled` | Diagnostic call-point marker for `IEX::loadBranchResolve(inst)`. |
| output | `loadBranchResolveSideEffectValid` | Always false for the current model because `loadBranchResolve` is a no-op. |
| output | `pipeCycleSidebandValid` | Timing/stat sidebands would be copied for this returned clone. |
| output | `readyForPipeInsert` | Alias for `candidateValid`; feeds the R327 timing/stat sideband diagnostic. |
| output | `blockedBy*` | Disabled, flush, and missing post-TLOAD completion diagnostics. |

## Logic Design

```text
candidateValid = enable && !flush && tloadCompletionValid
isLoadReturnMarked = candidateValid
loadBranchResolveCalled = candidateValid
loadBranchResolveSideEffectValid = false
pipeCycleSidebandValid = candidateValid
readyForPipeInsert = candidateValid
```

The module is intentionally a side-effect-free metadata marker. It does not
drive a branch recovery command, mutate pipe-cycle storage, update statistics,
or write an E4 pipe.

## Integration

`LinxCoreFrontendFetchRfAluTraceTop` wires R326 between
`LoadReplayReturnTloadCompletionCandidate` and
`LoadReplayReturnTimingStatsCandidate`:

- `tloadCompletionValid` comes from R325 `readyForPipeInsert`;
- R327 consumes R326 `readyForPipeInsert`;
- top-level diagnostics expose the load-return marker, branch-resolve call
  point, no-op side-effect flag, pipe-cycle sideband validity, and blockers.

This preserves the model order:

```text
setMemData admission
  -> ROB resolve-data intent
  -> lane-completion permit
  -> TLOAD sub-instruction completion permit
  -> final load-return metadata
  -> timing/stat sideband intent
  -> IEX E4 insert-shaped candidate
```

## Deferred Owners

- Real `isLoadReturn` storage on the cloned pipe instruction.
- Future load-branch resolve support if LinxCoreModel enables the commented
  load-compare path.
- Pipe-cycle timing sideband storage and load-latency statistics.
- Real E4 pipe residency, replay-row lifecycle, ready-table update, issue
  wakeup, and RF writeback after insertion.

## Verification

Focused gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnFinalMetadataCandidate
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnTloadCompletionCandidate
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnTimingStatsCandidate
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnIexPipeInsertCandidate
bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop
FETCH_REDUCED_STORE_REPLAY_LIQ=1 BUILD_DIR=generated/r326-replay-final-metadata-xcheck bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh
```

Reference tests cover final metadata pass-through, no-op branch-resolve
side-effect reporting, disabled/flush/missing-input blockers, and Chisel
elaboration.
