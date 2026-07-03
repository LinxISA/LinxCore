# LoadReplayReturnPipeResidencyAdvanceCandidate

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeResidencyAdvanceCandidate.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/LoadReplayReturnPipeResidencyAdvanceCandidateSpec.scala`
- Integrated user: `rtl/LinxCore/chisel/src/main/scala/linxcore/top/LinxCoreFrontendFetchRfAluTraceTop.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/iex/pipe/lda_pipe.cpp`
    - `LDAPipe::move`
    - `LDAPipe::flush`
  - `model/LinxCoreModel/model/iex/pipe/agu_pipe.cpp`
    - `AGUPipe::move`
    - `AGUPipe::flush`
- Related Chisel contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeResidencySlot.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeResidencyCandidate.scala`
- Contract IDs: `LC-CHISEL-LSU-REPLAY-PIPE-RESIDENCY-ADVANCE-001`

## Purpose

`LoadReplayReturnPipeResidencyAdvanceCandidate` names the next lifecycle point
after the R329 returned-load E4 residency slot. In both LinxCoreModel LDA and
AGU pipes, `move()` copies `e4_inst` into `w1_inst` and clears `e4_inst`.
Their `flush()` paths can also clear the E4 stage before normal advance.

R330 adds a combinational advance/clear diagnostic behind the one-entry
residency slot. It observes whether the slot is occupied, checks that the slot
has exactly one target pipe family, and emits a future `clearSlot` request only
when an explicit `advanceEnable` input is true. The integrated reduced top ties
`advanceEnable` false, so the slot cannot be cleared through this path until a
later W1 pipe lifecycle owner exists.

## Interface

| Direction | Signal | Description |
|---|---|---|
| input | `enable` | Replay-LIQ wrapper is active. |
| input | `flush` | Suppresses normal advance diagnostics. The slot itself still sees the top-level flush. |
| input | `advanceEnable` | Enables the future E4-to-W1 clear/advance point. Current top ties this false. |
| input | `slotOccupied` | R329 slot contains a resident returned-load payload. |
| input | `slotTargetIsAgu` / `slotTargetIsLda` | Resident slot target family. Exactly one must be set. |
| input | `slotPipeIndex` | Resident slot's selected pipe index. |
| output | `candidateValid` | Enabled, not flushing, and a resident slot exists. |
| output | `advanceValid` | Candidate with a valid target and `advanceEnable` asserted. |
| output | `clearSlot` | Same as `advanceValid`; feeds the R329 slot clear input in the top. |
| output | `targetIsAgu` / `targetIsLda` | Target diagnostics when the resident target is valid. |
| output | `targetPipeIndex` | Resident pipe index when the candidate has a valid target. |
| output | `blockedBy*` | Disabled, flush, empty slot, advance-disabled, and invalid-target blockers. |

## Logic Design

```text
candidateValid = enable && !flush && slotOccupied
targetValid = slotTargetIsAgu XOR slotTargetIsLda
advanceValid = candidateValid && targetValid && advanceEnable
clearSlot = advanceValid
```

The module intentionally does not model W1/W2 pipe storage yet. It only creates
the clear point that later W1 ownership can enable after it has a place to
receive the resident E4 payload.

## Integration

`LinxCoreFrontendFetchRfAluTraceTop` wires this module after
`LoadReplayReturnPipeResidencySlot`:

- `slotOccupied`, target-domain, and pipe-index inputs come from the R329 slot;
- `clearSlot` feeds the R329 slot's `clear` input;
- `advanceEnable` is tied false in the current reduced top;
- top-level diagnostics expose candidate, advance, clear, target, pipe-index,
  and blockers.

Since R328 still live-disables residency writes and R330 live-disables advance,
the generated fixture observes the same replay behavior as before this packet.

## Deferred Owners

- W1/W2 returned-load pipe stage storage.
- Live enable for E4-to-W1 advance.
- Pipe-stage flush by precise ROB/LSID identity rather than top-level replay
  flush only.
- Downstream RF/writeback, ready-table update, issue wakeup, and replay-row
  retirement after W-stage completion.

## Verification

Focused gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeResidencyAdvanceCandidate
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeResidencySlot
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeResidencyCandidate
bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop
FETCH_REDUCED_STORE_REPLAY_LIQ=1 BUILD_DIR=generated/r330-replay-pipe-advance-xcheck bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh
```

Reference tests cover enabled LDA advance, live-disabled AGU blocking,
disabled/flush/no-slot blockers, invalid target rejection, and Chisel
elaboration.
