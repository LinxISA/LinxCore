# LoadReplayReturnPipeW2PromotionControl

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2PromotionControl.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/LoadReplayReturnPipeW2PromotionControlSpec.scala`
- Integrated user: `rtl/LinxCore/chisel/src/main/scala/linxcore/top/LinxCoreFrontendFetchRfAluTraceTop.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/iex/pipe/lda_pipe.cpp`
    - `LDAPipe::Work`
    - `LDAPipe::runW2`
    - `LDAPipe::move`
  - `model/LinxCoreModel/model/iex/pipe/agu_pipe.cpp`
    - `AGUPipe::Work`
    - `AGUPipe::runW2`
    - `AGUPipe::move`
- Related Chisel contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2ClearIntent.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2RefillReady.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2SlotReplacePlan.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2AdvanceControl.scala`
- Contract IDs: `LC-CHISEL-LSU-REPLAY-PIPE-W2-PROMOTION-CONTROL-001`

## Purpose

`LoadReplayReturnPipeW2PromotionControl` is the single live-promotion switch
for the returned-load W2 pipe. LinxCoreModel evaluates W2 side effects before
`move()` overwrites W2 from W1, so a future Chisel promotion must enable W2
clear and W1-to-W2 same-cycle refill as one mode, not as two unrelated wires.

R356 adds that mode owner but keeps the top-level request disabled. The packet
therefore preserves the current empty-only advance behavior while making the
future enable point explicit for later live W2 clear/refill work.

## Interface

| Direction | Signal | Description |
|---|---|---|
| input | `enable` | Replay-LIQ returned-load pipe wrapper is active. |
| input | `flush` | Suppresses promotion during reduced-store flush. |
| input | `promotionRequested` | External request to use the live W2 promotion mode. Current top ties this false. |
| input | `slotOccupied` | W2 currently holds a resident returned-load entry. |
| input | `clearIntent` | R351 clear-intent proof for the resident W2 entry. |
| input | `writeCandidateValid` | W1-to-W2 write candidate before the selected advance gate. |
| output | `active` | Enabled and not flushed. |
| output | `requestActive` | Active plus external promotion request. |
| output | `emptyPromotion` | Promotion is requested while W2 is empty. |
| output | `sameCyclePromotion` | Promotion is requested for an occupied W2 slot with clear intent. |
| output | `livePromotionEnable` | Any legal live promotion mode is active. |
| output | `liveClearEnable` | Enables R351 live W2 clear only for same-cycle clear/refill promotion. |
| output | `advanceLivePromotionEnable` | Enables R355 future-advance selection. |
| output | blocker signals | Disabled, flush, promotion-disabled, missing-clear, and invalid clear-without-slot diagnostics. |

## Logic Design

```text
active = enable && !flush
requestActive = active && promotionRequested

emptyPromotion = requestActive && !slotOccupied && !clearIntent
sameCyclePromotion = requestActive && slotOccupied && clearIntent

livePromotionEnable = emptyPromotion || sameCyclePromotion
liveClearEnable = sameCyclePromotion
advanceLivePromotionEnable = livePromotionEnable
```

The owner intentionally consumes only acyclic evidence: slot occupancy, the
R351 `clearIntent` bit, and the external request. It does not consume R352
future readiness or R353 replacement-plan outputs, because those already
depend on `liveClear`. This lets the owner drive both `liveClearEnable` and
R355 `livePromotionEnable` without creating a combinational loop.

## Integration

`LinxCoreFrontendFetchRfAluTraceTop` wires this module after R351:

- `promotionRequested` is tied false in R356;
- `liveClearEnable` feeds `LoadReplayReturnPipeW2ClearIntent`;
- `advanceLivePromotionEnable` feeds
  `LoadReplayReturnPipeW2AdvanceControl.livePromotionEnable`;
- compact diagnostics are exposed under
  `reducedLoadReplayLiqLretPipeW2PromotionControl*`.

The module does not feed W2 slot clear directly, mutate W2 storage, issue
W2 side effects, retire replay-row lifecycle state, drive ROB/PE resolve,
write the replay RF path, mutate ready-table state, or wake the issue queue.

## Deferred Owners

- Turn `promotionRequested` on only after the W2 side-effect sinks and
  replay-row lifecycle can commit the same resident instruction.
- Replace the current W2 slot clear path with the live clear intent after the
  side-effect sinks mutate real state.
- Retire or update consumed replay rows in the same cycle that live W2
  side effects and clear/refill promotion fire.
- Extend the single reduced-pipe switch into a per-return-pipe policy when
  multiple returned-load pipes are instantiated.

## Verification

Focused gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2PromotionControl
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2ClearIntent
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2RefillReady
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2SlotReplacePlan
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2AdvanceControl
bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop
FETCH_REDUCED_STORE_REPLAY_LIQ=1 BUILD_DIR=generated/r356-replay-pipe-w2-promotion-control-xcheck bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh
```

Reference tests cover default disabled behavior, empty-slot promotion,
same-cycle clear/refill promotion, disabled/flush/missing-clear blockers,
invalid clear-intent evidence with an empty W2 slot, and Chisel elaboration.
