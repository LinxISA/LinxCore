# LoadReplayReturnPipeResidencySlot

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeResidencySlot.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/LoadReplayReturnPipeResidencySlotSpec.scala`
- Integrated user: `rtl/LinxCore/chisel/src/main/scala/linxcore/top/LinxCoreFrontendFetchRfAluTraceTop.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/iex/iex.cpp`
    - `IEX::setMemData`
  - `model/LinxCoreModel/model/iex/pipe/lda_pipe.cpp`
  - `model/LinxCoreModel/model/iex/pipe/agu_pipe.cpp`
- Related Chisel contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnIexPipeInsertCandidate.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeResidencyCandidate.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeResidencyLiveControl.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeResidencyAdvanceCandidate.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW1Slot.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayDestination.scala`
- Contract IDs: `LC-CHISEL-LSU-REPLAY-PIPE-RESIDENCY-SLOT-001`

## Purpose

`LoadReplayReturnPipeResidencySlot` is the first registered state boundary for
the model's returned-load E4 residency assignment. In `IEX::setMemData`, after
the returned load is cloned and marked as a load return, the model writes the
clone into the first free AGU E4 slot for vector IEX machines or the first free
LDA E4 slot for scalar IEX machines. AGU and LDA pipe code then advances
`e4_inst` into the later writeback stages and clears or flushes the stage as
the pipe lifecycle proceeds.

R329 adds a one-entry Chisel slot behind the R328 residency diagnostic. The
slot captures the R322 insert-shaped payload only when R328 reports a live,
unblocked residency write. It exposes occupancy and blockers but does not yet
drive the real IEX pipe pipeline, RF/writeback, ready table, issue wakeup, or
replay-row lifecycle. R384 now drives R328 `liveEnable` from
`LoadReplayReturnPipeResidencyLiveControl`, whose request input remains false,
so this slot remains dormant in the generated cross-check fixture.

R330 consumes this slot's occupancy, target, and pipe-index diagnostics in
`LoadReplayReturnPipeResidencyAdvanceCandidate`. That candidate now owns the
slot `clear` input, but the current top keeps its `advanceEnable` false until a
later packet enables the R331 W1 slot and its downstream W2 clear path.

R376 adds registered source-trace sidebands to the E4 residency image. Accepted
writes store the insert payload's source trace pair; flush and clear reset the
registered trace fields with the rest of the slot image.

## Interface

| Direction | Signal | Description |
|---|---|---|
| input | `enable` | Replay-LIQ wrapper is active. |
| input | `flush` | Clears the slot and suppresses same-cycle writes. |
| input | `clear` | Explicit lifecycle clear for a consumed entry. Current top drives this from the live-disabled R330 advance candidate. |
| input | `writeValid` | Live residency write request from `LoadReplayReturnPipeResidencyCandidate`. |
| input | `writeTargetIsAgu` / `writeTargetIsLda` | Mutually exclusive E4 pipe-family target. |
| input | `writePipeIndex` | Selected E4 pipe index. |
| input | `writeLoadToUsePipeIndex` | Original MemReqBus load-to-use pipe sideband. |
| input | `writeBid` / `writeGid` / `writeRid` / `writeLoadLsId` | Returned-load identity copied from the insert-shaped payload. |
| input | `writePc` / `writeAddr` / `writeSize` / `writeData` | Returned-load scalar request/data fields. |
| input | `writeDst` | Reduced destination sideband. |
| input | `writeSourceTraceValid` / `writeSource0` / `writeSource1` | R376 source operand trace sideband from the insert payload. |
| input | `writeWakeupRequired` | Future issue-wakeup sideband from the insert-shaped payload. |
| output | `accepted` | Slot is enabled, not flushing or clearing, has a valid exclusive target, and is empty. |
| output | `occupied` / `entryValid` | Registered entry is resident. |
| output | `entry*` | Registered target, pipe index, identity, request, destination, source trace, data, and wakeup sidebands. |
| output | `blockedBy*` | Disabled, flush, clear, no-write, invalid-target, and occupied-slot blockers. |

## Logic Design

The slot is intentionally one-entry:

```text
targetValid = writeTargetIsAgu XOR writeTargetIsLda
accepted = enable && !flush && !clear && writeValid && targetValid && !occupied

if flush || clear:
  occupied = false
  entry = disabled payload
else if accepted:
  occupied = true
  entry = write payload
else:
  entry holds state
```

Flush and clear have priority over a same-cycle write. A write with neither or
both targets asserted is rejected with `blockedByInvalidTarget`, matching the
model contract that `setMemData` chooses exactly one pipe family before writing
`pipe.e4_inst`.

## Integration

`LinxCoreFrontendFetchRfAluTraceTop` wires this slot after R328:

- `writeValid` comes from R328 `residencyWriteValid`;
- `writeTargetIsAgu`, `writeTargetIsLda`, and `writePipeIndex` come from R328;
- payload and wakeup sidebands come from R322
  `LoadReplayReturnIexPipeInsertCandidate`;
- `clear` comes from R330 `LoadReplayReturnPipeResidencyAdvanceCandidate`,
  which is currently tied live-disabled;
- R331 wires the slot entry payload into `LoadReplayReturnPipeW1Slot` for the
  future E4-to-W1 handoff;
- the top exposes accepted, occupied, target, pipe-index, and blocker
  diagnostics only.

Because the R384 live-control owner keeps R328 live-disabled in the current
reduced scalar top, this module does not change fixture-visible replay behavior.
It only establishes the state owner that later packets can connect to an
LDA/AGU pipe pipeline.

## Deferred Owners

- Multi-entry and first-free AGU/LDA pipe-family residency state.
- Live E4-to-W1 enable and W1-to-W2 pipe storage/advance lifecycle.
- Real vector machine classification in the reduced top.
- RF/writeback, ready-table update, issue wakeup, LRET FIFO drain, and replay
  row retirement after pipe residency.
- ROB mutation and branch-recovery side effects around returned-load
  completion.

## Verification

Focused gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeResidencySlot
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeResidencyLiveControl
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeResidencyAdvanceCandidate
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW1Slot
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeResidencyCandidate
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnIexPipeInsertCandidate
bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop
FETCH_REDUCED_STORE_REPLAY_LIQ=1 BUILD_DIR=generated/r329-replay-pipe-residency-slot-xcheck bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh
```

Reference tests cover scalar LDA capture, vector AGU capture, occupied-slot
blocking, flush and clear priority, disabled/no-write/invalid-target blockers,
payload preservation, and Chisel elaboration.
