# LoadReplayReturnPipeW2Slot

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2Slot.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/LoadReplayReturnPipeW2SlotSpec.scala`
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
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW1AdvanceCandidate.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2CompletionCandidate.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2SideEffectReady.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2ResolveSinkReady.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2WritebackSinkReady.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayDestination.scala`
- Contract IDs: `LC-CHISEL-LSU-REPLAY-PIPE-W2-SLOT-001`

## Purpose

`LoadReplayReturnPipeW2Slot` is the dormant registered W2 stage owner for a
returned-load pipe payload. In LinxCoreModel LDA and AGU pipes, `move()`
assigns `w2_inst = w1_inst`; `runW1()` only stamps the W1 cycle, while
`runW2()` stamps the W2 cycle and owns RF writeback, resolve publication, and
local scalar wakeup.

R333 adds the W2 payload state boundary without enabling downstream W2 side
effects. R334 wires clear from `LoadReplayReturnPipeW2CompletionCandidate`,
and R335 feeds that completion from `LoadReplayReturnPipeW2SideEffectReady`
while R336 names the resolve sink, R337 names the writeback sink, and R338
names the wakeup sink; all stay live-disabled. R354 adds an explicit
`replaceOnClear` storage mode for the later model-compatible cycle where W2
side effects clear the old entry and W1 refills W2 in the same cycle. The
reduced top now drives that mode through R355
`LoadReplayReturnPipeW2AdvanceControl`, whose live-promotion input remains
tied false. W1 advance readiness still comes from W2 emptiness and
fixture-visible replay behavior stays unchanged.

## Interface

| Direction | Signal | Description |
|---|---|---|
| input | `enable` | Replay-LIQ wrapper is active. |
| input | `flush` | Clears the W2 slot and suppresses same-cycle writes. |
| input | `clear` | Explicit lifecycle clear for a consumed W2 entry. Current top drives this from R334 W2 completion through the R335 W2 side-effect readiness join; R336 keeps resolve live-disabled, R337 keeps writeback live-disabled, and R338 keeps wakeup live-disabled. |
| input | `replaceOnClear` | Enables the future same-cycle clear/refill storage mode. Current top drives this from R355 `LoadReplayReturnPipeW2AdvanceControl`, whose live-promotion input keeps it false. |
| input | `writeValid` | W1-to-W2 advance pulse from `LoadReplayReturnPipeW1AdvanceCandidate`. |
| input | `writeTargetIsAgu` / `writeTargetIsLda` | Mutually exclusive W2 pipe-family target. |
| input | `writePipeIndex` | Selected return-pipe index carried from the W1 slot. |
| input | `writeLoadToUsePipeIndex` | Original MemReqBus load-to-use pipe sideband. |
| input | `writeBid` / `writeGid` / `writeRid` / `writeLoadLsId` | Returned-load identity copied from the W1 slot. |
| input | `writePc` / `writeAddr` / `writeSize` / `writeData` | Returned-load scalar request/data fields. |
| input | `writeDst` | Reduced destination sideband. |
| input | `writeWakeupRequired` | Future issue-wakeup sideband. |
| output | `accepted` | W2 slot accepted the incoming write, either as an empty-slot write or as a gated clear/refill replacement. |
| output | `occupied` / `entryValid` | Registered W2 entry is resident. |
| output | `entry*` | Registered target, pipe index, identity, request, destination, data, and wakeup sidebands. |
| output | `acceptedEmpty` | Write accepted into an empty slot with no same-cycle clear. |
| output | `replacedOnClear` | Write accepted while `clear` consumes the previous resident slot. |
| output | `blockedBy*` | Disabled, flush, clear, no-write, invalid-target, occupied-slot, and replace-disabled blockers. |

## Logic Design

The W2 slot is a one-entry registered stage:

```text
active = enable && !flush
targetValid = writeTargetIsAgu XOR writeTargetIsLda
writeCandidate = active && writeValid && targetValid

acceptedEmpty = writeCandidate && !clear && !occupied
replacedOnClear = writeCandidate && clear && replaceOnClear && occupied
accepted = acceptedEmpty || replacedOnClear

if flush:
  occupied = false
  entry = disabled payload
else if accepted:
  occupied = true
  entry = write payload
else if clear:
  occupied = false
  entry = disabled payload
else:
  entry holds state
```

Flush has priority over same-cycle writes. Clear also has priority while
`replaceOnClear=false`; when it is true, a clear plus valid resident slot and
valid exclusive write target replaces the old W2 payload with the incoming W1
payload. A write with neither or both target bits asserted is rejected,
preserving the invariant that each returned load belongs to exactly one LDA or
AGU W2 pipe target.

## Integration

`LinxCoreFrontendFetchRfAluTraceTop` wires this slot after R332:

- `writeValid` comes from W1 advance `advanceValid`;
- target-domain and selected pipe index come from the W1 advance candidate;
- payload sidebands come from the R331 W1 slot entry outputs;
- W1 advance `advanceEnable` is driven by R355
  `LoadReplayReturnPipeW2AdvanceControl`, which currently selects
  `!W2Slot.occupied`;
- `replaceOnClear` is driven by R355
  `LoadReplayReturnPipeW2AdvanceControl`, whose live-promotion input is tied
  false;
- `clear` comes from `LoadReplayReturnPipeW2CompletionCandidate.clearSlot`,
  which remains false while the R335/R336/R337/R338 W2 side-effect readiness path
  keeps at least one required sink not-ready;
- top-level diagnostics expose accepted, occupied, target, pipe-index, and
  blocker signals.

Because R328/R330 still keep upstream E4 residency and E4-to-W1 advance
disabled, this module does not change fixture-visible replay behavior. It
names the W2 payload owner required before real RF, ROB, ready-table,
issue-wakeup, or replay-row lifecycle side effects can consume returned loads.

## Deferred Owners

- W2 pipe-cycle timestamp storage.
- W2 writeback, resolve publication, ready-table update, issue wakeup, and
  replay-row retirement.
- Live W2 clear after all side effects are accepted.
- Promotion of R355 `livePromotionEnable` from false so `replaceOnClear` can
  consume the R351/R352/R353 live clear/refill predicate.
- Live enable for upstream E4-to-W1 advance.
- Per-pipe first-free/multi-pipe W-stage occupancy.
- Precise pipe-stage flush by ROB/LSID identity rather than top-level replay
  flush only.

## Verification

Focused gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2Slot
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2CompletionCandidate
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW1AdvanceCandidate
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW1Slot
bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop
FETCH_REDUCED_STORE_REPLAY_LIQ=1 BUILD_DIR=generated/r354-replay-pipe-w2-slot-replace-mode-xcheck bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh
```

Reference tests cover scalar LDA capture, vector AGU capture, occupied-slot
blocking, flush and clear priority, disabled/no-write/invalid-target blockers,
payload preservation, disabled replacement, same-cycle replacement when
`replaceOnClear` is enabled, flush priority over replacement, and Chisel
elaboration.
