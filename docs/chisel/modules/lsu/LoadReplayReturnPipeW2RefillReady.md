# LoadReplayReturnPipeW2RefillReady

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2RefillReady.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/LoadReplayReturnPipeW2RefillReadySpec.scala`
- Integrated user: `rtl/LinxCore/chisel/src/main/scala/linxcore/top/LinxCoreFrontendFetchRfAluTraceTop.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/iex/pipe/lda_pipe.cpp`
    - `LDAPipe::Work`, `LDAPipe::move`
  - `model/LinxCoreModel/model/iex/pipe/agu_pipe.cpp`
    - `AGUPipe::Work`, `AGUPipe::move`
- Related Chisel contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW1AdvanceCandidate.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2Slot.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2ClearIntent.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2AdvanceControl.scala`
- Contract IDs: `LC-CHISEL-LSU-REPLAY-PIPE-W2-REFILL-READY-001`

## Purpose

`LoadReplayReturnPipeW2RefillReady` names the future W1-to-W2 refill readiness
predicate for a model-compatible returned-load pipe. Today the reduced top lets
`LoadReplayReturnPipeW1AdvanceCandidate` advance only when the W2 slot is
empty. That is safe while W2 live clear is disabled, but LinxCoreModel
`LDAPipe::move` and `AGUPipe::move` assign `w2_inst = w1_inst` after
`runW2()` has consumed the old W2 entry. A live Chisel pipe therefore needs a
future readiness predicate that can admit W1 into W2 on the same cycle that W2
live clear retires the old entry.

This module is diagnostic evidence for R355
`LoadReplayReturnPipeW2AdvanceControl`. It compares the current empty-only
advance gate with the future `empty or same-cycle live clear` predicate and
exposes the same-cycle refill eligibility created by R351 clear intent. R355
now receives `futureAdvanceReady`, but the top keeps live promotion disabled,
so the actual W1 advance input remains empty-only.

## Interface

| Direction | Signal | Description |
|---|---|---|
| input | `enable` | Replay-LIQ wrapper is active. |
| input | `flush` | Suppresses refill-ready publication during replay flush. |
| input | `slotOccupied` | R333 W2 slot currently contains a resident entry. |
| input | `currentAdvanceReady` | Current top gate feeding W1-to-W2 advance, `!slotOccupied`. |
| input | `clearIntent` | R351 future clear intent for the resident W2 entry. |
| input | `liveClear` | R351 live clear pulse. Current top keeps this low. |
| output | `active` | Enabled and not flushed. |
| output | `emptyReady` | Active and W2 slot is empty. This is the current safe advance condition. |
| output | `sameCycleRefillEligible` | Active, W2 occupied, and clear intent exists. |
| output | `sameCycleRefillReady` | Active, W2 occupied, and live clear fires. |
| output | `futureAdvanceReady` | Future model-compatible W1-to-W2 readiness: empty or same-cycle live clear. |
| output | `currentMatchesFuture` | The current empty-only gate matches the future gate in this cycle. |
| output | blocker signals | Disabled, flush, occupied-without-live-clear, live-clear-disabled, and invalid-live-clear diagnostics. |

## Logic Design

```text
active = enable && !flush
emptyReady = active && !slotOccupied
sameCycleRefillEligible = active && slotOccupied && clearIntent
sameCycleRefillReady = active && slotOccupied && liveClear
futureAdvanceReady = emptyReady || sameCycleRefillReady
currentMatchesFuture = currentAdvanceReady == futureAdvanceReady
```

When live clear remains disabled, `futureAdvanceReady` matches the current
empty-only advance gate. Once a later packet enables live clear, this module
will identify the exact cycle where the model permits W2 to be consumed and
refilled by W1.

## Integration

`LinxCoreFrontendFetchRfAluTraceTop` wires this module after R351:

- `slotOccupied` comes from `LoadReplayReturnPipeW2Slot`;
- `currentAdvanceReady` is the current `!slotOccupied` W1-to-W2 advance gate;
- `clearIntent` and `liveClear` come from `LoadReplayReturnPipeW2ClearIntent`;
- compact diagnostics are exposed under
  `reducedLoadReplayLiqLretPipeW2RefillReady*`.

The integration feeds R355 `LoadReplayReturnPipeW2AdvanceControl` as dormant
future-readiness evidence. Because R355 ties `livePromotionEnable=false` in
the top, this module still does not change
`LoadReplayReturnPipeW1AdvanceCandidate.advanceEnable`, W2 slot clear, W2 side
effects, replay-row lifecycle, or ROB/RF/ready-table mutation.

## Deferred Owners

- Promote R355 `livePromotionEnable` after live W2 clear is implemented and
  verified so `futureAdvanceReady` selects the W1-to-W2 advance gate.
- Update W2 slot storage so a same-cycle clear/refill can replace the old W2
  entry with W1 payload, matching LinxCoreModel `move()`.
- Replay-row lifecycle retirement tied to the same consumed W2 entry.

## Verification

Focused gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2RefillReady
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2ClearIntent
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW1AdvanceCandidate
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2Slot
bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop
FETCH_REDUCED_STORE_REPLAY_LIQ=1 BUILD_DIR=generated/r352-replay-pipe-w2-refill-ready-xcheck bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh
```

Reference tests cover empty W2 readiness, occupied W2 blocking, dormant
same-cycle refill eligibility, future live clear refill readiness, invalid
live-clear evidence, disabled/flush suppression, and Chisel elaboration.
