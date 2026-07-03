# LoadReplayReturnPipeW2CompletionCandidate

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2CompletionCandidate.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/LoadReplayReturnPipeW2CompletionCandidateSpec.scala`
- Integrated user: `rtl/LinxCore/chisel/src/main/scala/linxcore/top/LinxCoreFrontendFetchRfAluTraceTop.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/iex/pipe/lda_pipe.cpp`
    - `LDAPipe::runW2`
  - `model/LinxCoreModel/model/iex/pipe/agu_pipe.cpp`
    - `AGUPipe::runW2`
- Related Chisel contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2Slot.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2SideEffectReady.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2ResolveSinkReady.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2WritebackSinkReady.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayDestination.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnSideEffectReady.scala`
- Contract IDs: `LC-CHISEL-LSU-REPLAY-PIPE-W2-COMPLETE-001`

## Purpose

`LoadReplayReturnPipeW2CompletionCandidate` is the combinational completion and
clear owner for a resident returned-load W2 slot. In LinxCoreModel, both
`LDAPipe::runW2` and `AGUPipe::runW2` reset the writeback request, return early
when `w2_inst` is empty, stamp the W2 pipe-cycle field for a resident entry,
generate RF writeback when applicable, publish a PE resolve bus, and call
`WakeupScalarLocalLinks`.

R334 names that W2 side-effect point in Chisel without enabling live replay
side effects in the reduced top. It reports which side effects a valid W2 entry
would require, fires completion only when all future sinks are ready, and emits
the W2 slot clear pulse only on that completion.

## Interface

| Direction | Signal | Description |
|---|---|---|
| input | `enable` | Replay-LIQ wrapper is active. |
| input | `flush` | Suppresses completion diagnostics during a replay flush; the W2 slot owns registered flush clearing. |
| input | `sideEffectsReady` | Future join of W2 resolve, RF writeback, and wakeup sink readiness. Current top feeds this from `LoadReplayReturnPipeW2SideEffectReady`; R336 names the resolve sink and R337 names the writeback sink, but both remain live-disabled while wakeup remains tied low. |
| input | `slotOccupied` | Registered W2 slot contains an entry. |
| input | `slotTargetIsAgu` / `slotTargetIsLda` | Mutually exclusive AGU/LDA W2 target carried by the slot. |
| input | `slotPipeIndex` | Return-pipe index associated with the W2 entry. |
| input | `slotDst` | Reduced destination sideband used for RF writeback and wakeup classification. |
| input | `slotData` | Returned scalar data for a future RF writeback. |
| input | `slotWakeupRequired` | Reduced issue-wakeup sideband carried by the W2 entry. |
| output | `candidateValid` / `targetValid` | W2 slot is visible and has a legal exclusive target. |
| output | `completeValid` / `clearSlot` | All W2 side effects can be accepted; clear the W2 slot this cycle. |
| output | `resolveRequired` / `resolveValid` | Every legal W2 entry requires a resolve side effect; it becomes valid only on completion. |
| output | `writebackRequired` / `writebackValid` / `writebackTag` / `writebackData` | GPR destination writeback requirement and completion-time request payload. |
| output | `wakeupRequired` / `wakeupValid` / `wakeupTag` | Wakeup requirement and completion-time reduced wakeup payload. |
| output | `blockedBy*` | Disabled, flush, no-slot, invalid-target, and side-effect-readiness blockers. |

Additional outputs classify non-GPR and missing destinations so later W2
integration can preserve the model distinction between completion and optional
scalar RF writeback.

## Logic Design

```text
candidateValid = enable && !flush && slotOccupied
targetValid = slotTargetIsAgu XOR slotTargetIsLda
sideEffectCandidate = candidateValid && targetValid
completeValid = sideEffectCandidate && sideEffectsReady
clearSlot = completeValid

resolveRequired = sideEffectCandidate
resolveValid = completeValid

writebackRequired = sideEffectCandidate && slotDst is GPR
writebackValid = completeValid && slotDst is GPR

wakeupRequired = sideEffectCandidate && slotWakeupRequired
wakeupValid = completeValid && slotWakeupRequired && slotDst exists
```

The module separates required side effects from accepted side effects. A W2
entry can report that writeback, resolve, or wakeup would be required while
`sideEffectsReady` is low, but it does not emit valid side effects or clear the
slot until that readiness join is true.

## Integration

`LinxCoreFrontendFetchRfAluTraceTop` wires this module after
`LoadReplayReturnPipeW2Slot`:

- slot occupancy, target, pipe index, destination, data, and wakeup sidebands
  come from the registered W2 slot;
- `sideEffectsReady` comes from `LoadReplayReturnPipeW2SideEffectReady` in
  R335. R336 feeds the resolve input from
  `LoadReplayReturnPipeW2ResolveSinkReady`, but that sink remains
  live-disabled. R337 feeds the writeback input from
  `LoadReplayReturnPipeW2WritebackSinkReady`, but that sink also remains
  live-disabled while wakeup is still tied low, so completion and clear remain
  dormant;
- the W2 slot `clear` input now comes from this completion candidate instead
  of a literal false;
- top-level diagnostics expose candidate, completion, resolve, writeback,
  wakeup, clear, and blocker signals.

Because upstream E4-to-W1 advance is still disabled and the W2 side-effect
readiness join still keeps at least one required sink not-ready, this path
preserves fixture-visible behavior while naming the owner boundary required for
live returned-load pipe retirement.

## Deferred Owners

- Real W2 side-effect readiness join from LRET/resolve, RF writeback, and
  wakeup sinks.
- Live resolve publication and ROB/replay-row lifecycle mutation.
- W2 pipe-cycle timestamp storage and timing/stat update.
- Ready-table update and full issue-wakeup fanout.
- Stack-load, tile, and vector destination-specific suppression beyond the
  current reduced destination classification.

## Verification

Focused gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2CompletionCandidate
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2SideEffectReady
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2ResolveSinkReady
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2WritebackSinkReady
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2Slot
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW1AdvanceCandidate
bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop
FETCH_REDUCED_STORE_REPLAY_LIQ=1 BUILD_DIR=generated/r334-replay-pipe-w2-completion-xcheck bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh
```

Reference tests cover ready scalar LDA completion, side-effect-not-ready hold,
missing and non-GPR destination classification, disabled/flush/no-slot/invalid
target blockers, optional wakeup suppression, and Chisel elaboration.
