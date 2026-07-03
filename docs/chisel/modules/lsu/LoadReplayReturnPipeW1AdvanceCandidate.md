# LoadReplayReturnPipeW1AdvanceCandidate

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW1AdvanceCandidate.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/LoadReplayReturnPipeW1AdvanceCandidateSpec.scala`
- Integrated user: `rtl/LinxCore/chisel/src/main/scala/linxcore/top/LinxCoreFrontendFetchRfAluTraceTop.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/iex/pipe/lda_pipe.cpp`
    - `LDAPipe::move`
    - `LDAPipe::runW1`
    - `LDAPipe::runW2`
    - `LDAPipe::flush`
  - `model/LinxCoreModel/model/iex/pipe/agu_pipe.cpp`
    - `AGUPipe::move`
    - `AGUPipe::runW1`
    - `AGUPipe::runW2`
    - `AGUPipe::flush`
- Related Chisel contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW1Slot.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeResidencyAdvanceCandidate.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeResidencySlot.scala`
- Contract IDs: `LC-CHISEL-LSU-REPLAY-PIPE-W1-ADVANCE-001`

## Purpose

`LoadReplayReturnPipeW1AdvanceCandidate` names the dormant W1-to-W2 advance and
clear point for a returned-load pipe payload. In LinxCoreModel `LDAPipe::move`
and `AGUPipe::move`, the W-stage transfer is ordered as `w2_inst = w1_inst`
before `w1_inst = e4_inst`; `runW1()` only stamps the W1 cycle, while `runW2()`
performs RF writeback, resolve publication, and wakeup side effects.

R332 adds the W1 advance diagnostic without creating W2 storage or live side
effects. The current top wires the R331 W1 slot into this candidate but keeps
`advanceEnable` false, so no W1 entry can clear through this path yet.

## Interface

| Direction | Signal | Description |
|---|---|---|
| input | `enable` | Replay-LIQ wrapper is active. |
| input | `flush` | Suppresses candidate formation during replay-pipe flush. |
| input | `advanceEnable` | Future W2 owner readiness. Current top ties this false. |
| input | `slotOccupied` | R331 W1 slot contains a returned-load payload. |
| input | `slotTargetIsAgu` / `slotTargetIsLda` | Mutually exclusive W1 pipe-family target. |
| input | `slotPipeIndex` | Selected return-pipe index carried by the W1 slot. |
| output | `candidateValid` | W1 slot is occupied while enabled and not flushing. |
| output | `advanceValid` | Candidate has exactly one target and the future W2 owner is ready. |
| output | `clearSlot` | W1 slot clear pulse for a successful future advance. |
| output | `targetIsAgu` / `targetIsLda` | Qualified target-domain sideband for the W2 stage. |
| output | `targetPipeIndex` | Qualified return-pipe index for the W2 stage, or zero when not valid. |
| output | `blockedBy*` | Disabled, flush, empty-slot, advance-disabled, and invalid-target diagnostics. |

## Logic Design

The candidate is purely combinational:

```text
candidateValid = enable && !flush && slotOccupied
targetValid = slotTargetIsAgu XOR slotTargetIsLda
advanceValid = candidateValid && targetValid && advanceEnable
clearSlot = advanceValid
```

It rejects a resident W1 entry with neither or both target bits asserted. The
clear pulse is identical to `advanceValid`, matching the model's ownership move
where the W1 payload is consumed only when it becomes the W2 payload.

## Integration

`LinxCoreFrontendFetchRfAluTraceTop` wires this module after the R331 W1 slot:

- `slotOccupied`, target-domain bits, and pipe index come from the W1 slot;
- `clearSlot` drives the W1 slot `clear` input;
- `advanceEnable` is tied false until the W2 storage owner is implemented;
- top-level diagnostics expose candidate, advance, clear, target, pipe-index,
  and blocker signals.

Because R328 still live-disables E4 residency writes and R330/R332 advance
enables are tied false, this packet does not change fixture-visible replay
behavior. It only creates the named handoff that a later W2 slot must consume.

## Deferred Owners

- W2 returned-load pipe stage storage and payload capture.
- Live enable for W1-to-W2 advance once the W2 stage has capacity.
- Pipe-cycle timestamp storage for W1/W2.
- RF writeback, resolve publication, ready-table update, issue wakeup, and
  replay-row retirement after W2 completion.
- Precise pipe-stage flush by ROB/LSID identity rather than top-level replay
  flush only.

## Verification

Focused gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW1AdvanceCandidate
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW1Slot
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeResidencyAdvanceCandidate
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeResidencySlot
bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop
FETCH_REDUCED_STORE_REPLAY_LIQ=1 BUILD_DIR=generated/r332-replay-pipe-w1-advance-xcheck bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh
```

Reference tests cover scalar LDA advance, vector AGU live-disabled residency,
disabled/flush/empty-slot blockers, invalid target-family rejection, and Chisel
elaboration of the clear and blocker diagnostics.
