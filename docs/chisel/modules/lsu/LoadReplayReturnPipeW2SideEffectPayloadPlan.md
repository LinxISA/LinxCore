# LoadReplayReturnPipeW2SideEffectPayloadPlan

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2SideEffectPayloadPlan.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/LoadReplayReturnPipeW2SideEffectPayloadPlanSpec.scala`
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
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2CompletionCandidate.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2SideEffectRequest.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2ResolveRequest.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2WritebackRequest.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2WakeupRequest.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2SideEffectIssuePermit.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2SideEffectFireVector.scala`
- Contract IDs: `LC-CHISEL-LSU-REPLAY-PIPE-W2-SIDE-EFFECT-PAYLOAD-PLAN-001`

## Purpose

`LoadReplayReturnPipeW2SideEffectPayloadPlan` is the dormant atomicity checker
between the W2 side-effect request vector and future live side-effect sinks.
The R340/R341/R342 modules shape the resolve, RF writeback, and wakeup payloads
independently. R343 joins their valid bits back against the required W2
side-effect mask before any later packet can allow W2 slot clear to depend on
live mutation.

In LinxCoreModel, `LDAPipe::runW2` and `AGUPipe::runW2` emit W2 effects as one
logical completion point: optional RF writeback, mandatory resolve publication,
and scalar/local wakeup. This module does not implement those mutations. It
only proves that the Chisel request mask and payload-valid mask match the
completion classifier before a future sink is allowed to consume them.

## Interface

| Direction | Signal | Description |
|---|---|---|
| input | `enable` | Reduced replay-LIQ feature enable. |
| input | `flush` | Suppresses side-effect issue during replay flush. |
| input | `sideEffectCandidateValid` | W2 completion classifier says a legal side-effect candidate exists. |
| input | `requestValid` | Post-completion side-effect request vector is present. |
| input | `resolveRequired` / `writebackRequired` / `wakeupRequired` | Required side-effect mask from the W2 completion classifier. |
| input | `resolveRequest` / `writebackRequest` / `wakeupRequest` | Request mask from `LoadReplayReturnPipeW2SideEffectRequest`. |
| input | `resolvePayloadValid` / `writebackPayloadValid` / `wakeupPayloadValid` | Valid payload bits from the R340/R341/R342 payload modules. |
| output | `candidateValid` | Enabled, not flushed, and a W2 side-effect candidate exists. |
| output | `requiredMask` | `{wakeupRequired, writebackRequired, resolveRequired}`. |
| output | `requestMask` | `{wakeupRequest, writebackRequest, resolveRequest}`. |
| output | `payloadValidMask` | `{wakeupPayloadValid, writebackPayloadValid, resolvePayloadValid}`. |
| output | `requestMatchesRequired` | Request mask exactly matches the required side-effect mask. |
| output | `payloadMatchesRequired` | Payload-valid mask exactly matches the required side-effect mask. |
| output | `issueValid` | Candidate, request, request mask, and payload mask are all coherent. |
| output | `blockedByDisabled` / `blockedByFlush` | Feature/flush blockers. |
| output | `blockedByNoCandidate` / `blockedByNoRequest` | No legal W2 candidate or no post-completion request. |
| output | `blockedByRequestMismatch` | Request mask differs from the required mask. |
| output | `blockedByMissingResolvePayload` / `blockedByMissingWritebackPayload` / `blockedByMissingWakeupPayload` | A required side effect has no valid payload. |
| output | `blockedByUnexpectedResolvePayload` / `blockedByUnexpectedWritebackPayload` / `blockedByUnexpectedWakeupPayload` | A non-required side effect has a valid payload. |
| output | `invalidRequestWithoutCandidate` | Request arrived without a side-effect candidate. |
| output | `invalidPayloadWithoutRequest` | A payload appeared without a post-completion request. |

## Logic Design

```text
requiredMask = {wakeupRequired, writebackRequired, resolveRequired}
requestMask = {wakeupRequest, writebackRequest, resolveRequest}
payloadValidMask = {wakeupPayloadValid, writebackPayloadValid, resolvePayloadValid}
candidateValid = enable && !flush && sideEffectCandidateValid

requestMatchesRequired = requestMask == requiredMask
payloadMatchesRequired = payloadValidMask == requiredMask
issueValid =
  candidateValid &&
  requestValid &&
  requestMatchesRequired &&
  payloadMatchesRequired
```

The module intentionally treats unexpected payloads as blockers, not harmless
extras. Future live sinks should consume only the side effects that the W2
completion classifier required for that resident entry.

## Integration

`LinxCoreFrontendFetchRfAluTraceTop` wires this module behind the existing W2
payload modules:

- requirements come from `LoadReplayReturnPipeW2CompletionCandidate`;
- request bits come from `LoadReplayReturnPipeW2SideEffectRequest`;
- payload-valid bits come from `LoadReplayReturnPipeW2ResolveRequest`,
  `LoadReplayReturnPipeW2WritebackRequest`, and
  `LoadReplayReturnPipeW2WakeupRequest`;
- compact top diagnostics are exposed under
  `reducedLoadReplayLiqLretPipeW2SideEffectPayloadPlan*`.
- R344 consumes `issueValid` and `requiredMask` in
  `LoadReplayReturnPipeW2SideEffectIssuePermit`, but that downstream module is
  still observational and does not feed W2 completion or slot clear.
- R346 consumes the request and payload-valid masks in
  `LoadReplayReturnPipeW2SideEffectFireVector` only after R344 accepts the
  plan, and remains observational.

The R343 output remains observational. It does not feed
`LoadReplayReturnPipeW2SideEffectReady`, W2 slot clear, ROB resolve mutation,
replay RF writeback, ready-table state, or issue wakeup.

## Deferred Owners

- Live resolve, replay RF writeback, and wakeup sink mutation.
- Multi-pipe and multi-destination payload arbitration.
- W2 slot clear enable after all required live sinks accept the same resident
  entry.
- Replay-row lifecycle retirement after W2 side effects become live.

## Verification

Focused gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2SideEffectPayloadPlan
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2SideEffectRequest
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2ResolveRequest
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2WritebackRequest
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2WakeupRequest
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2SideEffectIssuePermit
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2SideEffectFireVector
bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop
FETCH_REDUCED_STORE_REPLAY_LIQ=1 BUILD_DIR=generated/r343-replay-pipe-w2-side-effect-payload-plan-xcheck bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh
```

Reference tests cover full side-effect masks, resolve-only masks, dormant
request behavior, disabled/flush blockers, request-mask mismatch, missing
payloads, unexpected payloads, request/payload bypass diagnostics, and Chisel
elaboration.
