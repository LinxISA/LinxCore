# LoadReplayReturnPipeW2SideEffectIssuePermit

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2SideEffectIssuePermit.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/LoadReplayReturnPipeW2SideEffectIssuePermitSpec.scala`
- Integrated user: `rtl/LinxCore/chisel/src/main/scala/linxcore/top/LinxCoreFrontendFetchRfAluTraceTop.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/iex/pipe/lda_pipe.cpp`
    - `LDAPipe::runW2`
    - `WakeupScalarLocalLinks`
  - `model/LinxCoreModel/model/iex/pipe/agu_pipe.cpp`
    - `AGUPipe::runW2`
    - `WakeupScalarLocalLinks`
  - `model/LinxCoreModel/model/ModelCommon/SimInstInfo.cpp`
    - `SimInstInfo::GenRFReqBus`
    - `SimInstInfo::GenRslvBus`
  - `model/LinxCoreModel/model/iex/iex.cpp`
    - `IEX::setMemWakeup`
  - `model/LinxCoreModel/model/iex/iex_iq.cpp`
    - `IssueQueue::WakeupIQTag`
- Related Chisel contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2SideEffectPayloadPlan.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2ResolveSinkReady.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2WritebackSinkReady.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2WakeupSinkReady.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2SideEffectFireVector.scala`
- Contract IDs: `LC-CHISEL-LSU-REPLAY-PIPE-W2-SIDE-EFFECT-ISSUE-PERMIT-001`

## Purpose

`LoadReplayReturnPipeW2SideEffectIssuePermit` names the dormant accept point
after the R343 side-effect payload plan. The payload-plan owner proves that
the W2 required mask, post-completion request mask, and shaped payload-valid
mask are coherent. This module then joins that coherent plan with the
live-gated resolve, RF writeback, and wakeup sink readiness bits.

In LinxCoreModel, `LDAPipe::runW2` and `AGUPipe::runW2` treat W2 as one
logical completion point: optional RF writeback, mandatory resolve
publication, and scalar/local wakeup occur together for the resident W2
instruction. This Chisel module does not implement those mutations. It only
reports whether a coherent post-completion side-effect plan would be accepted
by all required live sinks.

## Interface

| Direction | Signal | Description |
|---|---|---|
| input | `enable` | Reduced replay-LIQ feature enable. |
| input | `flush` | Suppresses accept diagnostics during replay flush. |
| input | `payloadPlanIssueValid` | R343 payload plan proved candidate, request, and payload masks coherent. |
| input | `requiredMask` | `{wakeupRequired, writebackRequired, resolveRequired}` from the payload plan. |
| input | `resolveSinkReady` / `writebackSinkReady` / `wakeupSinkReady` | Live-gated sink readiness from the R336/R337/R338 sink owners. |
| output | `candidateValid` | Enabled, not flushed, and the payload plan is valid. |
| output | `issueArmed` | Candidate has at least one required W2 side effect. |
| output | `sinkReadyMask` | `{wakeupSinkReady, writebackSinkReady, resolveSinkReady}`. |
| output | `missingReadyMask` | Required sinks that are not ready, in the same bit order as `requiredMask`. |
| output | `acceptedMask` | Required mask when all required sinks accept, otherwise zero. |
| output | `allRequiredSinksReady` | All required sinks are ready; optional sinks do not block. |
| output | `issueAccepted` | Armed candidate and all required live-gated sinks are ready. |
| output | `blockedByDisabled` / `blockedByFlush` | Feature/flush blockers. |
| output | `blockedByNoPlan` | No coherent payload plan is available. |
| output | `blockedByNoRequiredSink` | Payload plan is valid but no W2 side effect is required. |
| output | `blockedByResolveSink` / `blockedByWritebackSink` / `blockedByWakeupSink` | A required sink is not live-ready. |

## Logic Design

```text
candidateValid = enable && !flush && payloadPlanIssueValid
issueArmed = candidateValid && requiredMask != 0

resolveReady = !requiredMask.resolve || resolveSinkReady
writebackReady = !requiredMask.writeback || writebackSinkReady
wakeupReady = !requiredMask.wakeup || wakeupSinkReady

allRequiredSinksReady = resolveReady && writebackReady && wakeupReady
issueAccepted = issueArmed && allRequiredSinksReady
acceptedMask = issueAccepted ? requiredMask : 0
missingReadyMask = required sinks whose sink-ready bit is false
```

Optional sinks are ignored for acceptance. A resolve-only W2 side effect may
accept even when writeback and wakeup sinks are not ready. A required sink that
is live-disabled remains a blocker because the R336/R337/R338 sink modules
already gate readiness with their `liveEnable` inputs.

## Integration

`LinxCoreFrontendFetchRfAluTraceTop` wires this module behind
`LoadReplayReturnPipeW2SideEffectPayloadPlan`:

- `payloadPlanIssueValid` and `requiredMask` come from the R343 payload plan;
- sink-ready bits come from `LoadReplayReturnPipeW2ResolveSinkReady`,
  `LoadReplayReturnPipeW2WritebackSinkReady`, and
  `LoadReplayReturnPipeW2WakeupSinkReady`;
- compact top diagnostics are exposed under
  `reducedLoadReplayLiqLretPipeW2SideEffectIssuePermit*`.

The R344 output remains observational. It must not feed
`LoadReplayReturnPipeW2CompletionCandidate.sideEffectsReady`, W2 slot clear, or
any live sink mutation, because the current request/payload path is
post-completion and feeding it back would create a side-effect readiness loop.
R346 consumes `issueAccepted` and `acceptedMask` in
`LoadReplayReturnPipeW2SideEffectFireVector` to expose downstream
resolve/writeback/wakeup fire pulses. That fire vector is also observational
and does not feed W2 clear.

## Deferred Owners

- Live ROB/PE resolve mutation.
- Live replay RF writeback mutation.
- Live ready-table and issue-wakeup mutation.
- A future pre-completion side-effect readiness contract that can safely feed
  W2 clear without depending on post-completion payloads.
- Replay-row lifecycle retirement after W2 side effects become live.

## Verification

Focused gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2SideEffectIssuePermit
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2SideEffectFireVector
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2SideEffectPayloadPlan
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2SideEffectReady
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2ResolveSinkReady
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2WritebackSinkReady
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2WakeupSinkReady
bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop
FETCH_REDUCED_STORE_REPLAY_LIQ=1 BUILD_DIR=generated/r344-replay-pipe-w2-side-effect-issue-permit-xcheck bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh
```

Reference tests cover full side-effect acceptance, resolve-only acceptance,
per-sink live-readiness blockers, dormant no-plan behavior, disabled/flush
blockers, no-required-sink diagnostics, and Chisel elaboration.
