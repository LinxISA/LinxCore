# LoadReplayReturnPipeResidencyAdvanceLiveControl

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeResidencyAdvanceLiveControl.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/LoadReplayReturnPipeResidencyAdvanceLiveControlSpec.scala`
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
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeResidencyAdvanceCandidate.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW1Slot.scala`
- Contract IDs: `LC-CHISEL-LSU-REPLAY-PIPE-RESIDENCY-ADVANCE-LIVE-001`

## Purpose

`LoadReplayReturnPipeResidencyAdvanceLiveControl` names the live request gate
for returned-load E4-to-W1 advancement. In the LinxCoreModel LDA and AGU pipes,
`move()` copies `e4_inst` into `w1_inst` and then clears `e4_inst`; their
`flush()` paths can clear E4, W1, and W2 pipe stages by precise identity.

R385 replaces the reduced top's raw
`LoadReplayReturnPipeResidencyAdvanceCandidate.advanceEnable=false.B` tie with
this owner. The reduced top still drives `requestEnable=false`, so the R330
advance candidate sees a disabled live request, the E4 residency slot is not
cleared, and the W1 slot observes no writes.

## Interface

| Direction | Signal | Description |
|---|---|---|
| input | `enable` | Replay-LIQ wrapper is active. |
| input | `flush` | Suppresses the live request and slot evidence. |
| input | `requestEnable` | Future top-level request to enable E4-to-W1 advance. Current top ties this false. |
| input | `slotOccupied` | E4 residency slot contains a returned-load payload. |
| input | `slotTargetIsAgu` / `slotTargetIsLda` | Resident slot target family. Exactly one must be set before evidence is valid. |
| input | `slotPipeIndex` | Resident return-pipe index for evidence diagnostics. |
| output | `active` | Wrapper enabled and not flushing. |
| output | `requestActive` | A live advance request is active this cycle. |
| output | `advanceEvidenceValid` | Active wrapper with an occupied slot and valid exclusive AGU/LDA target. |
| output | `advanceEnable` | Live enable for `LoadReplayReturnPipeResidencyAdvanceCandidate`. |
| output | `evidenceTargetIsAgu` / `evidenceTargetIsLda` | Target-family evidence when the resident slot is valid. |
| output | `evidenceTargetPipeIndex` | Resident pipe index when evidence is valid. |
| output | `blockedBy*` | Disabled, flush, request-disabled, no-slot, invalid-target, and no-evidence blockers. |

## Logic Design

```text
active = enable && !flush
requestActive = active && requestEnable
targetValid = slotTargetIsAgu XOR slotTargetIsLda
rawEvidence = slotOccupied && targetValid
advanceEvidenceValid = active && rawEvidence
advanceEnable = requestActive && rawEvidence
```

The gate is intentionally stricter than a raw top-level enable. A future live
request can advance only when the resident E4 slot exists and carries a valid
exclusive target. With the current request disabled, the module reports valid
resident-slot evidence as request-disabled rather than mutating pipe state.

## Integration

R385 wires `LinxCoreFrontendFetchRfAluTraceTop` as follows:

- `enable` comes from the reduced replay-LIQ allocation enable;
- `flush` comes from reduced-store flush;
- `requestEnable` remains `false`;
- slot evidence comes from `LoadReplayReturnPipeResidencySlot`;
- `advanceEnable` feeds `LoadReplayReturnPipeResidencyAdvanceCandidate`.

Because `requestEnable` is false, `LoadReplayReturnPipeResidencyAdvanceCandidate`
keeps `advanceValid` and `clearSlot` false even when a future resident slot
exists.

## Deferred Owners

- Top-level request enable for live E4-to-W1 advance.
- Real multi-entry AGU/LDA W-stage occupancy and replacement policy.
- W1/W2 advance, RF replay writeback, ROB/PE resolve, ready-table/issue wakeup,
  replay-row lifecycle clear, and commit-row fill after W-stage completion.
- Precise pipe-stage flush by ROB/LSID identity rather than top-level replay
  flush only.

## Verification

Focused gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeResidencyAdvanceLiveControl
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeResidencyAdvanceCandidate
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW1Slot
bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop
FETCH_REDUCED_STORE_REPLAY_LIQ=1 BUILD_DIR=generated/r385x bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh
```

Reference tests cover disabled request gating, live request with resident-slot
evidence, disabled/flush blockers, empty-slot and invalid-target blockers, and
Chisel elaboration.
