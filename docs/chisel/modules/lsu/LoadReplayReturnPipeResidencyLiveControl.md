# LoadReplayReturnPipeResidencyLiveControl

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeResidencyLiveControl.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/LoadReplayReturnPipeResidencyLiveControlSpec.scala`
- Integrated user: `rtl/LinxCore/chisel/src/main/scala/linxcore/top/LinxCoreFrontendFetchRfAluTraceTop.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/iex/iex.cpp`
    - `IEX::receiveFromLSU`
    - `IEX::BackToPipe`
    - `CheckPipeValid`
    - `IEX::setMemData`
- Related Chisel contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnIexDrainPermit.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnIexPipeInsertCandidate.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeResidencyCandidate.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeResidencySlot.scala`
- Contract IDs: `LC-CHISEL-LSU-REPLAY-PIPE-RESIDENCY-LIVE-001`

## Purpose

`LoadReplayReturnPipeResidencyLiveControl` names the live request gate for
returned-load E4 pipe residency writes. In LinxCoreModel, `IEX::receiveFromLSU`
first checks for a free return pipe with `BackToPipe` and `CheckPipeValid`,
then drains the LRET queue and calls `IEX::setMemData`. After ROB/data,
lane-completion, TLOAD, final metadata, and timing/stat updates, `setMemData`
writes the returned instruction into the first free AGU E4 pipe for vector IEX
or the first free LDA E4 pipe for scalar IEX.

R384 replaces the reduced top's raw `LoadReplayReturnPipeResidencyCandidate`
`liveEnable=false.B` tie with this owner. The top still drives
`requestEnable=false`, so no E4 residency write, W1/W2 advance, W2 side effect,
or replay-row lifecycle mutation becomes live.

## Interface

| Direction | Signal | Description |
|---|---|---|
| input | `enable` | Replay-LIQ wrapper is active. |
| input | `flush` | Suppresses the live request and evidence. |
| input | `requestEnable` | Future top-level request to enable live E4 residency writes. Current top ties this false. |
| input | `insertCandidateValid` | Insert-shaped candidate exists after `setMemData` admission and sideband stages. |
| input | `insertValid` | Insert-shaped payload is accepted and has a valid RID/pipe. |
| input | `selectedPipeOccupied` | Selected return pipe is already occupied. |
| output | `active` | Wrapper enabled and not flushing. |
| output | `requestActive` | A live residency request is active this cycle. |
| output | `residencyEvidenceValid` | Active, accepted insert evidence exists, and the selected pipe is free. |
| output | `liveEnable` | Live write enable for `LoadReplayReturnPipeResidencyCandidate`. |
| output | `blockedByDisabled` | Request or evidence present while wrapper disabled. |
| output | `blockedByFlush` | Request or evidence present while flushing. |
| output | `blockedByRequestDisabled` | Residency evidence exists but the top request is disabled. |
| output | `blockedByNoInsertCandidate` | Active request without an insert candidate. |
| output | `blockedByNoInsertValid` | Active request with a candidate but no accepted insert. |
| output | `blockedByPipeOccupied` | Active request and accepted insert targeting an occupied pipe. |
| output | `blockedByNoEvidence` | Active request without full free-pipe insert evidence. |

## Logic Design

```text
active = enable && !flush
requestActive = active && requestEnable
rawEvidence = insertCandidateValid && insertValid && !selectedPipeOccupied
residencyEvidenceValid = active && rawEvidence
liveEnable = requestActive && rawEvidence
```

The request is intentionally stricter than a raw top-level enable: even after a
future request is armed, `liveEnable` only fires with an accepted insert-shaped
payload and a free selected pipe.

## Integration

R384 wires `LinxCoreFrontendFetchRfAluTraceTop` as follows:

- `enable` comes from the reduced replay-LIQ allocation enable;
- `flush` comes from reduced-store flush;
- `requestEnable` remains `false`;
- insert evidence comes from `LoadReplayReturnIexPipeInsertCandidate`;
- selected-pipe occupancy comes from the IEX drain permit free-pipe diagnostic;
- `liveEnable` feeds `LoadReplayReturnPipeResidencyCandidate.liveEnable`.

`LoadReplayReturnPipeResidencyCandidate.residencyWriteValid` therefore remains
false in the current top, and `LoadReplayReturnPipeResidencySlot` observes no
write.

## Deferred Owners

- Top-level request enable for live E4 residency writes.
- Real multi-entry AGU/LDA E4 occupancy source and replacement policy.
- W1/W2 advance, RF replay writeback, ROB/PE resolve, ready-table/issue wakeup,
  replay-row lifecycle clear, and commit-row fill after residency writes.

## Verification

Focused gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeResidencyLiveControl
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeResidencyCandidate
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeResidencySlot
bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop
FETCH_REDUCED_STORE_REPLAY_LIQ=1 BUILD_DIR=generated/r384x bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh
```

Reference tests cover disabled request gating, live request with accepted
free-pipe evidence, disabled/flush blockers, missing insert evidence, occupied
pipe blocking, and Chisel elaboration.
