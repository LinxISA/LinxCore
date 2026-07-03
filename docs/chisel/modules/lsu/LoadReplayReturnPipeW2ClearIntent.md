# LoadReplayReturnPipeW2ClearIntent

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2ClearIntent.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/LoadReplayReturnPipeW2ClearIntentSpec.scala`
- Integrated user: `rtl/LinxCore/chisel/src/main/scala/linxcore/top/LinxCoreFrontendFetchRfAluTraceTop.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/iex/pipe/lda_pipe.cpp`
    - `LDAPipe::Work`, `LDAPipe::runW2`, `LDAPipe::move`
  - `model/LinxCoreModel/model/iex/pipe/agu_pipe.cpp`
    - `AGUPipe::Work`, `AGUPipe::runW2`, `AGUPipe::move`
- Related Chisel contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2Slot.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2CompletionCandidate.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2SideEffectCompletionPermit.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2SideEffectFireComplete.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2PromotionControl.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2RefillReady.scala`
- Contract IDs: `LC-CHISEL-LSU-REPLAY-PIPE-W2-CLEAR-INTENT-001`

## Purpose

`LoadReplayReturnPipeW2ClearIntent` names the future W2 slot-clear decision
without changing the current slot clear path. It observes three independently
documented predicates:

- the R334 `LoadReplayReturnPipeW2CompletionCandidate.clearSlot` pre-clear
  owner;
- the R345 `LoadReplayReturnPipeW2SideEffectCompletionPermit` pre-completion
  permit mirror;
- the R350 `LoadReplayReturnPipeW2SideEffectFireComplete.futureClearEligible`
  post-fire completeness proof.

In LinxCoreModel, `LDAPipe::Work` and `AGUPipe::Work` run W2 before later pipe
stages. Their `move()` methods then overwrite `w2_inst` from `w1_inst`, so W2
side effects are consumed before the pipe advances. The Chisel top still keeps
real W2 side effects and replay-row lifecycle mutation disabled. This module
only exposes the point where a future live owner can require pre-clear permit,
post-fire completeness, and an explicit live-clear enable to agree.
R356 routes that explicit enable through `LoadReplayReturnPipeW2PromotionControl`;
R363 still keeps the shared live request false.

## Interface

| Direction | Signal | Description |
|---|---|---|
| input | `enable` | Replay-LIQ wrapper is active. |
| input | `flush` | Suppresses clear-intent publication during replay flush. |
| input | `slotOccupied` | R333 W2 slot currently contains a resident entry. |
| input | `completionClearSlot` | R334 pre-clear slot clear pulse. |
| input | `completionPermitted` | R345 pre-completion permit mirror. |
| input | `fireComplete` | R350 post-fire completeness predicate. |
| input | `liveClearEnable` | Explicit future live-clear arm from R356 `LoadReplayReturnPipeW2PromotionControl`. R363 keeps the shared request gate false. |
| output | `candidateValid` | Enabled, not flushed, and a resident W2 slot is visible. |
| output | `preClearEligible` | Candidate plus R334 clear evidence. |
| output | `permitEligible` | Candidate plus R345 permit evidence. |
| output | `postFireEligible` | Candidate plus R350 post-fire evidence. |
| output | `completionPermitMatchesClear` | R334 and R345 pre-clear predicates agree. |
| output | `fireCompleteMatchesClear` | R334 pre-clear and R350 post-fire predicates agree. |
| output | `clearIntent` | Candidate has R334, R345, and R350 clear evidence. |
| output | `liveClear` | `clearIntent` gated by `liveClearEnable`. |
| output | blocker signals | Disabled, flush, no-slot, missing evidence, live-clear-disabled, mismatch, and stray-evidence diagnostics. |

## Logic Design

```text
active = enable && !flush
candidateValid = active && slotOccupied
completionPermitMatchesClear = completionClearSlot == completionPermitted
fireCompleteMatchesClear = completionClearSlot == fireComplete
clearIntent = candidateValid && completionClearSlot && completionPermitted && fireComplete
liveClear = clearIntent && liveClearEnable
```

`clearIntent` is deliberately stricter than any single producer. It requires
the current pre-clear owner, the pre-completion permit mirror, and the post-fire
completeness proof to agree on the same resident W2 slot. `liveClear` is a
separate output so the current top can expose the future clear decision while
keeping live slot clearing and replay-row retirement disabled.

## Integration

`LinxCoreFrontendFetchRfAluTraceTop` wires this module after the W2 fire
complete checker:

- `slotOccupied` comes from `LoadReplayReturnPipeW2Slot`;
- `completionClearSlot` comes from `LoadReplayReturnPipeW2CompletionCandidate`;
- `completionPermitted` comes from
  `LoadReplayReturnPipeW2SideEffectCompletionPermit`;
- `fireComplete` comes from
  `LoadReplayReturnPipeW2SideEffectFireComplete.futureClearEligible`;
- `liveClearEnable` comes from
  `LoadReplayReturnPipeW2PromotionControl.liveClearEnable`, whose external
  promotion request is tied false in R356.

The integration exposes compact diagnostics under
`reducedLoadReplayLiqLretPipeW2ClearIntent*`. It does not feed the W2 slot
`clear` input, W2 readiness, ROB/PE resolve, replay RF writeback, ready-table
mutation, issue wakeup, or replay-row lifecycle.

## Deferred Owners

- Enable the R356 W2 promotion request after side-effect sinks mutate real
  state.
- Replay-row lifecycle retirement after the same resident W2 instruction
  completes live side effects.
- Replacement of the current observational post-fire path with a live sink
  accept path.
- Same-cycle W2 clear/refill storage semantics; R352 observes the future
  readiness predicate but does not feed W1-to-W2 advance.

## Verification

Focused gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2ClearIntent
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2SideEffectFireComplete
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2SideEffectCompletionPermit
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2CompletionCandidate
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2PromotionControl
bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop
FETCH_REDUCED_STORE_REPLAY_LIQ=1 BUILD_DIR=generated/r351-replay-pipe-w2-clear-intent-xcheck bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh
```

Reference tests cover dormant clear intent, live-clear gating, no-slot stray
evidence, disabled/flush suppression, missing pre-clear evidence, missing
post-fire evidence, missing permit evidence, and Chisel elaboration.
