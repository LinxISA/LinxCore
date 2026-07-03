# LoadReplayReturnTloadCompletionCandidate

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnTloadCompletionCandidate.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/LoadReplayReturnTloadCompletionCandidateSpec.scala`
- Integrated user: `rtl/LinxCore/chisel/src/main/scala/linxcore/top/LinxCoreFrontendFetchRfAluTraceTop.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/iex/iex.cpp`
    - `IEX::setMemData`
    - `IEX::TloadSendSeqToTileScb`
    - `IEX::PrintTlsPipeViewLog`
  - `model/LinxCoreModel/model/ModelCommon/bus/MemReqBus.h`
- Related Chisel contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnLaneCompletionCandidate.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnFinalMetadataCandidate.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnIexPipeInsertCandidate.scala`
- Contract IDs: `LC-CHISEL-LSU-REPLAY-TLOAD-COMPLETION-001`

## Purpose

`LoadReplayReturnTloadCompletionCandidate` is the diagnostic TLOAD
sub-instruction completion boundary in the model `IEX::setMemData` sequence.
After ROB resolve-data and lane completion, LinxCoreModel handles
`MEM_IEX` `OP_TLD` specially:

- stamp the cloned instruction PE and load-return cycle sideband;
- decrement the ROB instruction's `subInstCnt`;
- call `TloadSendSeqToTileScb(mem, inst, islast)` on every returned
  sub-instruction;
- return early while the decremented `subInstCnt` is not zero;
- only the final TLOAD sub-instruction continues toward `isLoadReturn` and E4
  insertion.

R325 names that predicate without mutating ROB `subInstCnt` and without
driving a real tile-SCB request. The current reduced scalar top ties
`isMemIex=false`, `isTload=false`, and `subInstCntBefore=0`, so ordinary scalar
replay passes through after the R324 lane-completion permit.

## Interface

| Direction | Signal | Description |
|---|---|---|
| input | `enable` | Replay-LIQ wrapper is active. |
| input | `flush` | Suppresses TLOAD diagnostics for the cycle. |
| input | `laneCompletionValid` | R324 post-resolve lane-completion permit. |
| input | `isMemIex` | The cloned instruction is owned by model `MEM_IEX`. |
| input | `isTload` | The cloned instruction opcode is model `OP_TLD`. |
| input | `subInstCntBefore` | Diagnostic ROB `subInstCnt` value before this returned TLOAD sub-instruction. |
| output | `candidateValid` | Enabled, not flushing, and lane-completion input is valid. |
| output | `tloadCandidateValid` | Candidate is specifically a `MEM_IEX` TLOAD row. |
| output | `subInstCntAfter` | Diagnostic decremented count for TLOAD rows, or the pass-through count otherwise. |
| output | `tileScbSendValid` | Future tile-SCB send intent for every valid TLOAD sub-instruction return. |
| output | `tileScbIsLast` | Future tile-SCB `islast` sideband when the decremented count reaches zero. |
| output | `completeValid` | Candidate may continue to `isLoadReturn` and E4 insertion. |
| output | `readyForPipeInsert` | Alias for `completeValid`; feeds the R326 final metadata diagnostic. |
| output | `blockedBy*` | Disabled, flush, no-lane-completion, invalid sub-instruction count, and pending TLOAD diagnostics. |

## Logic Design

```text
candidateValid = enable && !flush && laneCompletionValid
tloadCandidateValid = candidateValid && isMemIex && isTload
decrementValid = tloadCandidateValid && subInstCntBefore != 0
subInstCntAfter = decrementValid ? subInstCntBefore - 1 : subInstCntBefore
tloadComplete = decrementValid && subInstCntAfter == 0
completeValid = candidateValid && (!tloadCandidateValid || tloadComplete)
readyForPipeInsert = completeValid
```

For non-TLOAD rows, the model skips the TLOAD block and proceeds to
`inst->isLoadReturn = true`; this module therefore passes non-TLOAD candidates
through. For TLOAD rows, a zero incoming `subInstCntBefore` is invalid, a
nonzero post-decrement count emits a tile-SCB send intent and blocks pipe
insertion, and the final post-decrement zero emits tile-SCB `islast` plus the
pipe-insert permit.

## Integration

`LinxCoreFrontendFetchRfAluTraceTop` wires R325 between
`LoadReplayReturnLaneCompletionCandidate` and
`LoadReplayReturnFinalMetadataCandidate`:

- `laneCompletionValid` comes from R324 `readyForPipeInsert`;
- `isMemIex`, `isTload`, and `subInstCntBefore` are tied to the ordinary
  scalar pass-through shape in the current reduced top;
- R326 consumes R325 `readyForPipeInsert`.

This preserves the model order:

```text
setMemData admission
  -> ROB resolve-data intent
  -> lane-completion permit
  -> TLOAD sub-instruction completion permit
  -> final load-return metadata
  -> IEX E4 insert-shaped candidate
```

The module exposes the future tile-SCB completion surface but does not mutate
ROB `subInstCnt`, publish tile-SCB data, update pipe-view logs, set
`isLoadReturn`, resolve load branches, or write E4 pipe state.

## Deferred Owners

- Real ROB `subInstCnt` storage, decrement, and commit interaction.
- `MEM_IEX`/`OP_TLD` classification sidebands from decoded/renamed rows.
- Tile-SCB request payload construction from `TloadSendSeqToTileScb`.
- Pipe-view cycle sideband carry for TLOAD returns.
- Load-branch resolve and final `isLoadReturn` marking.
- Real E4 pipe residency, replay-row lifecycle, ready-table update, issue
  wakeup, and RF writeback after completion.

## Verification

Focused gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnTloadCompletionCandidate
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnLaneCompletionCandidate
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnFinalMetadataCandidate
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnIexPipeInsertCandidate
bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop
FETCH_REDUCED_STORE_REPLAY_LIQ=1 BUILD_DIR=generated/r325-replay-tload-completion-xcheck bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh
```

Reference tests cover ordinary scalar pass-through, non-final and final TLOAD
sub-instruction returns, disabled/flush/no-lane-completion/invalid-count
blockers, and Chisel elaboration.
