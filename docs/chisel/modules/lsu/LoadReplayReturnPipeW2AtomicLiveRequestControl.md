# LoadReplayReturnPipeW2AtomicLiveRequestControl

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2AtomicLiveRequestControl.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/LoadReplayReturnPipeW2AtomicLiveRequestControlSpec.scala`
- Integrated user: `rtl/LinxCore/chisel/src/main/scala/linxcore/top/LinxCoreFrontendFetchRfAluTraceTop.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/iex/pipe/lda_pipe.cpp`
    - `LDAPipe::runW2`
    - `LDAPipe::move`
  - `model/LinxCoreModel/model/iex/pipe/agu_pipe.cpp`
    - `AGUPipe::runW2`
    - `AGUPipe::move`
- Related Chisel contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2SideEffectLiveControl.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2PromotionControl.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2ClearIntent.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW1AdvanceCandidate.scala`
- Contract IDs: `LC-CHISEL-LSU-REPLAY-PIPE-W2-ATOMIC-LIVE-REQUEST-001`

## Purpose

`LoadReplayReturnPipeW2AtomicLiveRequestControl` is the single request owner for
the future live W2 replay mode. LinxCoreModel emits W2 side effects before pipe
movement: optional RF writeback, PE/ROB resolve publication, ready-table/issue
wakeup, W2 clear, and W1-to-W2 refill are one resident-instruction boundary.

R363 removes the two remaining top-local request tie-offs and drives both
`LoadReplayReturnPipeW2SideEffectLiveControl.liveRequested` and
`LoadReplayReturnPipeW2PromotionControl.promotionRequested` from this owner. The
R530 moves the top-level instance under
`LoadReplayReturnPipeW2AtomicRequestGate`; this module remains the child that
fans a single request into side-effect live control and promotion control.

## Interface

| Direction | Signal | Description |
|---|---|---|
| input | `enable` | Replay-LIQ returned-load pipe wrapper is active. |
| input | `flush` | Suppresses live W2 request generation during reduced-store flush. |
| input | `requestEnable` | Atomic W2 live side-effect and promotion request gate. In the reduced top, R530 drives it through `LoadReplayReturnPipeW2AtomicRequestGate.gatedRequestEnable`, which remains false. |
| input | `sideEffectCandidateValid` | Resident W2 entry can require side-effect sinks. |
| input | `sideEffectRequiredMask` | `{wakeupRequired, writebackRequired, resolveRequired}` evidence from the W2 completion classifier. |
| input | `clearIntent` | R351 proof that the resident W2 entry would be clear-eligible. Used only as evidence. |
| input | `writeCandidateValid` | W1-to-W2 write candidate before selected advance. Used only as evidence. |
| output | `active` | Enabled and not flushed. |
| output | `requestActive` | Active plus `requestEnable`. |
| output | `requestEvidenceValid` | Active W2 side-effect, clear, or refill evidence exists. |
| output | `sideEffectLiveRequested` | Shared request for the W2 resolve/writeback/wakeup live-control owner. |
| output | `promotionRequested` | Shared request for W2 live clear/refill promotion. |
| output | blocker signals | Disabled, flush, request-disabled, and no-evidence diagnostics. |

## Logic Design

```text
active = enable && !flush
requestActive = active && requestEnable
rawEvidence =
  (sideEffectCandidateValid && sideEffectRequiredMask != 0) ||
  clearIntent ||
  writeCandidateValid
requestEvidenceValid = active && rawEvidence

sideEffectLiveRequested = requestActive
promotionRequested = requestActive
```

The request outputs intentionally do not depend on `clearIntent` or downstream
promotion readiness. `clearIntent` is diagnostic evidence only; this keeps the
request owner from creating a combinational loop through
`PromotionControl.liveClearEnable` and `ClearIntent.liveClear`. Subordinate
owners still decide whether a requested mode can produce live enables.

## Integration

`LinxCoreFrontendFetchRfAluTraceTop` now reaches this owner through
`LoadReplayReturnPipeW2AtomicRequestGate`:

- `requestEnable` is `liveModeEnable && policyRequestEnableCandidate`;
- `liveModeEnable` remains `false.B`;
- raw clear/refill request evidence can still reach this owner while policy
  prerequisites remain tied off in the reduced top;
- `sideEffectLiveRequested` feeds R357 side-effect live control;
- `promotionRequested` feeds R356 promotion control;
- compact diagnostics are exposed under
  `reducedLoadReplayLiqLretPipeW2AtomicLiveRequest*`.

Because the request remains false, R357 side-effect live enables, R356 live
promotion, R351 live clear, R355 replacement, replay RF writeback, ROB/PE
resolve, ready-table mutation, issue wakeup, and replay-row lifecycle mutation
remain disabled.

## Deferred Owners

- Replace the false `requestEnable` only after live W2 resolve, RF writeback,
  wakeup, clear/refill promotion, and replay-row lifecycle retirement can commit
  the same resident instruction atomically.
- Add per-return-pipe request policy when multiple returned-load pipes are
  instantiated.
- Feed real sink capacity and replay-row lifecycle readiness before live mutation
  is enabled in generated RTL.

## Verification

Focused gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2AtomicLiveRequestControl
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2SideEffectLiveControl
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2PromotionControl
bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop
FETCH_REDUCED_STORE_REPLAY_LIQ=1 BUILD_DIR=generated/r363-replay-pipe-w2-atomic-live-request-xcheck bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh
```

Reference tests cover disabled default behavior, the shared active request gate,
disabled/flush blockers, no-evidence diagnostics, and Chisel elaboration.
