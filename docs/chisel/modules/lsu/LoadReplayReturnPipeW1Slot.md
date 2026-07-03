# LoadReplayReturnPipeW1Slot

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW1Slot.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/LoadReplayReturnPipeW1SlotSpec.scala`
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
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeResidencySlot.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeResidencyAdvanceCandidate.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW1AdvanceCandidate.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2Slot.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeResidencyCandidate.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayDestination.scala`
- Contract IDs: `LC-CHISEL-LSU-REPLAY-PIPE-W1-SLOT-001`

## Purpose

`LoadReplayReturnPipeW1Slot` is the dormant registered W1 stage owner for a
returned-load pipe payload after the R329 E4 residency slot. In the
LinxCoreModel LDA and AGU pipes, `move()` first assigns `w2_inst = w1_inst`,
then `w1_inst = e4_inst`, and finally clears `e4_inst`. `runW1()` records only
the W1 pipe-cycle timestamp; RF writeback, resolve publication, and scalar
wakeup are deferred until `runW2()`.

R331 adds the W1 payload state boundary without enabling live advance. The
current top still ties the R330 `advanceEnable` input false, so this slot sees
no writes in the generated fixture. It exists so the later W2/writeback
ownership chain has a typed W1 producer before any RF, ROB, ready-table,
issue-wakeup, or replay-row lifecycle side effects are enabled.

## Interface

| Direction | Signal | Description |
|---|---|---|
| input | `enable` | Replay-LIQ wrapper is active. |
| input | `flush` | Clears the W1 slot and suppresses same-cycle writes. |
| input | `clear` | Explicit lifecycle clear for a consumed W1 entry. Current top drives this from the R332 W1 advance candidate, whose readiness now comes from the R333 W2 slot being empty. |
| input | `writeValid` | Future E4-to-W1 advance pulse from `LoadReplayReturnPipeResidencyAdvanceCandidate`. |
| input | `writeTargetIsAgu` / `writeTargetIsLda` | Mutually exclusive W1 pipe-family target. |
| input | `writePipeIndex` | Selected return-pipe index carried from the E4 slot. |
| input | `writeLoadToUsePipeIndex` | Original MemReqBus load-to-use pipe sideband. |
| input | `writeBid` / `writeGid` / `writeRid` / `writeLoadLsId` | Returned-load identity copied from the E4 slot. |
| input | `writePc` / `writeAddr` / `writeSize` / `writeData` | Returned-load scalar request/data fields. |
| input | `writeDst` | Reduced destination sideband. |
| input | `writeWakeupRequired` | Future issue-wakeup sideband. |
| output | `accepted` | W1 slot is enabled, not flushing or clearing, has a valid exclusive target, and is empty. |
| output | `occupied` / `entryValid` | Registered W1 entry is resident. |
| output | `entry*` | Registered target, pipe index, identity, request, destination, data, and wakeup sidebands. |
| output | `blockedBy*` | Disabled, flush, clear, no-write, invalid-target, and occupied-slot blockers. |

## Logic Design

The W1 slot is a one-entry registered stage:

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

Flush and clear have priority over same-cycle writes. A write with neither or
both targets asserted is rejected, preserving the invariant that the returned
load has exactly one LDA or AGU target before entering the pipe stages.

## Integration

`LinxCoreFrontendFetchRfAluTraceTop` wires this slot after R330:

- `writeValid` comes from R330 `advanceValid`;
- target-domain and selected pipe index come from the R330 advance candidate;
- payload sidebands come from the R329 E4 residency slot entry outputs;
- `clear` comes from `LoadReplayReturnPipeW1AdvanceCandidate.clearSlot`, which
  can fire only when a W1 entry exists and the R333 W2 slot is empty;
- top-level diagnostics expose accepted, occupied, target, pipe-index, and
  blocker signals.

Because R328 still live-disables E4 residency writes and R330 still
live-disables E4-to-W1 advance, this module does not change fixture-visible
replay behavior. It only names the W1 stage boundary that the model advances
through before W2 side effects.

## Deferred Owners

- Live upstream E4-to-W1 advance.
- Live enable for R330 E4-to-W1 advance.
- Per-pipe first-free/multi-pipe W-stage occupancy.
- Pipe-cycle timestamp storage for W1/W2.
- RF writeback, resolve publication, ready-table update, issue wakeup, and
  replay-row retirement after W2 completion.
- Precise pipe-stage flush by ROB/LSID identity rather than top-level replay
  flush only.

## Verification

Focused gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW1Slot
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW1AdvanceCandidate
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2Slot
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeResidencyAdvanceCandidate
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeResidencySlot
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeResidencyCandidate
bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop
FETCH_REDUCED_STORE_REPLAY_LIQ=1 BUILD_DIR=generated/r333-replay-pipe-w2-slot-xcheck bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh
```

Reference tests cover scalar LDA capture, vector AGU capture, occupied-slot
blocking, flush and clear priority, disabled/no-write/invalid-target blockers,
payload preservation, and Chisel elaboration.
