# LoadReplayReturnPipeW2AdvanceControl

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2AdvanceControl.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/LoadReplayReturnPipeW2AdvanceControlSpec.scala`
- Integrated user: `rtl/LinxCore/chisel/src/main/scala/linxcore/top/LinxCoreFrontendFetchRfAluTraceTop.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/iex/pipe/lda_pipe.cpp`
    - `LDAPipe::Work`
    - `LDAPipe::runW2`
    - `LDAPipe::runW1`
    - `LDAPipe::move`
  - `model/LinxCoreModel/model/iex/pipe/agu_pipe.cpp`
    - `AGUPipe::Work`
    - `AGUPipe::runW2`
    - `AGUPipe::runW1`
    - `AGUPipe::move`
- Related Chisel contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW1AdvanceCandidate.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2Slot.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2RefillReady.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2SlotReplacePlan.scala`
- Contract IDs: `LC-CHISEL-LSU-REPLAY-PIPE-W2-ADVANCE-CONTROL-001`

## Purpose

`LoadReplayReturnPipeW2AdvanceControl` is the W2 pipe promotion-control owner
between the R352/R353 future-readiness diagnostics and the live W1-to-W2
advance/storage controls. LinxCoreModel LDA and AGU pipes evaluate W2 before
W1 and then run `move()`, where `w2_inst = w1_inst`. A live Chisel pipe
therefore needs one control point that can switch from the current safe
empty-only advance rule to the future model-compatible same-cycle
clear/refill rule.

R355 adds that control point but keeps the live promotion disabled in the top.
The current top still advances W1 only when W2 is empty and still keeps
`LoadReplayReturnPipeW2Slot.replaceOnClear` false through this module. The
packet changes ownership and observability, not fixture-visible replay
behavior.

## Interface

| Direction | Signal | Description |
|---|---|---|
| input | `enable` | Replay-LIQ return-pipe wrapper is active. |
| input | `flush` | Suppresses live promotion and reports flush blocking. |
| input | `livePromotionEnable` | Enables future model-compatible same-cycle W2 clear/refill promotion. Current top ties this false. |
| input | `currentAdvanceReady` | Current empty-only advance readiness, normally `!W2Slot.occupied`. |
| input | `futureAdvanceReady` | R352 future readiness, `empty || sameCycleLiveClear`. |
| input | `sameCycleReplaceReady` | R353 same-cycle replacement readiness. |
| input | `futureWriteAccept` | R353 future W2 write acceptance diagnostic. |
| input | `writeCandidateValid` | W1-to-W2 write candidate before the current advance gate suppresses it. |
| output | `active` | Enabled and not flushed. |
| output | `livePromotionActive` | Active plus live promotion enabled. |
| output | `advanceEnable` | Selected W1-to-W2 advance gate. |
| output | `replaceOnClear` | Selected W2 slot same-cycle replacement enable. |
| output | `usesFutureAdvance` | Future readiness differs from the current empty-only gate and is being selected. |
| output | `currentMatchesFuture` | Current and future advance predicates agree. |
| output | blocker signals | Disabled, flush, live-promotion-disabled, future-readiness, and future-write coherence diagnostics. |

## Logic Design

```text
active = enable && !flush
livePromotionActive = active && livePromotionEnable

advanceEnable =
  if livePromotionActive then futureAdvanceReady
  else currentAdvanceReady

replaceOnClear = livePromotionActive && sameCycleReplaceReady
```

When `livePromotionEnable=false`, the owner is a pass-through for the current
empty-only W1-to-W2 readiness. When it becomes true in a later packet, it
selects R352 future readiness for W1 advance and drives R354
`replaceOnClear` from the R353 same-cycle replacement predicate.

The top wires only acyclic inputs into this owner. `sameCycleReplaceReady` and
`futureWriteAccept` are R353 future predicates that do not depend on the
current W2 slot `accepted` result, so the selected advance gate does not feed
back through current storage acceptance.

## Integration

`LinxCoreFrontendFetchRfAluTraceTop` wires this module after R352 and R353:

- `currentAdvanceReady` comes from `!LoadReplayReturnPipeW2Slot.occupied`;
- `futureAdvanceReady` comes from `LoadReplayReturnPipeW2RefillReady`;
- replacement evidence comes from `LoadReplayReturnPipeW2SlotReplacePlan`;
- `advanceEnable` now drives `LoadReplayReturnPipeW1AdvanceCandidate`;
- `replaceOnClear` now drives `LoadReplayReturnPipeW2Slot`;
- `livePromotionEnable` is tied false in R355.

Top-level diagnostics are exposed under
`reducedLoadReplayLiqLretPipeW2AdvanceControl*`. The module does not drive W2
clear, W2 side effects, replay-row lifecycle retirement, ROB/PE resolve,
replay RF writeback, ready-table mutation, or issue wakeup.

## Deferred Owners

- Promote `livePromotionEnable` after W2 side effects, clear intent, storage
  replacement, and replay-row lifecycle can all accept the same model cycle.
- Tie W2 clear to the live side-effect completion owner instead of the current
  dormant completion path.
- Retire or update the consumed replay-row lifecycle when W2 side effects
  become live.
- Extend the single-pipe reduced control into per-pipe occupancy and
  first-free selection when multiple return pipes are instantiated.

## Verification

Focused gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2AdvanceControl
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2SlotReplacePlan
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2RefillReady
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2Slot
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW1AdvanceCandidate
bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop
FETCH_REDUCED_STORE_REPLAY_LIQ=1 BUILD_DIR=generated/r355-replay-pipe-w2-advance-control-xcheck bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh
```

Reference tests cover current empty-only pass-through, future promotion
selection, disabled/flush blockers, incoherent future write evidence, and
Chisel elaboration.
