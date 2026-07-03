# LoadReplayReturnPipeW2SideEffectFireVector

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2SideEffectFireVector.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/LoadReplayReturnPipeW2SideEffectFireVectorSpec.scala`
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
- Related Chisel contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2SideEffectIssuePermit.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2SideEffectPayloadPlan.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2SideEffectCompletionPermit.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2ResolveRequest.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2ResolveFirePayload.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2WritebackRequest.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2WritebackFirePayload.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2WakeupRequest.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2WakeupFirePayload.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2SideEffectFireComplete.scala`
- Contract IDs: `LC-CHISEL-LSU-REPLAY-PIPE-W2-SIDE-EFFECT-FIRE-VECTOR-001`

## Purpose

`LoadReplayReturnPipeW2SideEffectFireVector` names the dormant per-sink fire
vector after the W2 side-effect issue permit. R344 proves that a coherent
post-completion payload plan would be accepted by all required live-gated
sinks. R346 converts that accepted mask into explicit resolve, writeback, and
wakeup fire pulses for future sink owners while checking that the request and
payload-valid masks still match the accepted sinks.

In LinxCoreModel, `LDAPipe::runW2` and `AGUPipe::runW2` issue resolve,
optional RF writeback, and wakeup effects at one logical W2 completion point.
This module does not implement those mutations and does not feed W2
readiness. It only names the downstream fire surface that future live
ROB/resolve, replay RF, and ready-table/issue-wakeup owners can consume.

## Interface

| Direction | Signal | Description |
|---|---|---|
| input | `enable` | Reduced replay-LIQ feature enable. |
| input | `flush` | Suppresses fire diagnostics during replay flush. |
| input | `issueAccepted` | R344 issue permit accepted a coherent side-effect plan. |
| input | `acceptedMask` | `{wakeup, writeback, resolve}` accepted sink mask from the issue permit. |
| input | `requestMask` | `{wakeup, writeback, resolve}` request mask from the payload plan. |
| input | `payloadValidMask` | `{wakeup, writeback, resolve}` payload-valid mask from the payload plan. |
| output | `candidateValid` | Enabled, not flushed, and issue accepted. |
| output | `fireValid` | Candidate has a non-empty accepted mask and request/payload masks match it. |
| output | `fireMask` | Accepted sink mask when `fireValid`, otherwise zero. |
| output | `resolveFire` / `writebackFire` / `wakeupFire` | Per-sink future mutation fire pulses. |
| output | `requestMissingMask` / `payloadMissingMask` | Accepted sinks with no matching request or payload. |
| output | `requestExtraMask` / `payloadExtraMask` | Requests or payloads not covered by the accepted mask. |
| output | `requestMatchesAccepted` / `payloadMatchesAccepted` | Full-mask equality checks. |
| output | blocker signals | Feature, flush, no-accept, empty-mask, request-mismatch, and payload-mismatch diagnostics. |

## Logic Design

```text
candidateValid = enable && !flush && issueAccepted
requestMatchesAccepted = requestMask == acceptedMask
payloadMatchesAccepted = payloadValidMask == acceptedMask
fireValid =
  candidateValid &&
  acceptedMask != 0 &&
  requestMatchesAccepted &&
  payloadMatchesAccepted
fireMask = fireValid ? acceptedMask : 0
```

The fire pulses are derived only from `fireMask`. A request or payload mismatch
turns into a diagnostic and suppresses all fire outputs. This keeps the module
safe to place before real sink mutation: future owners should only see
per-sink fire pulses for exactly the side effects that were requested,
payload-shaped, and accepted by R344.

## Integration

`LinxCoreFrontendFetchRfAluTraceTop` wires this module behind
`LoadReplayReturnPipeW2SideEffectIssuePermit`:

- `issueAccepted` and `acceptedMask` come from the R344 issue permit;
- request and payload-valid masks come from the R343 payload plan;
- compact diagnostics are exposed under
  `reducedLoadReplayLiqLretPipeW2SideEffectFireVector*`.

The R346 output remains observational. It does not feed
`LoadReplayReturnPipeW2CompletionCandidate.sideEffectsReady`, W2 slot clear,
post-completion request generation, ROB resolve, replay RF writeback,
ready-table mutation, issue wakeup, or replay-row lifecycle.

R347 consumes only `resolveFire` with the R340 resolve payload in
`LoadReplayReturnPipeW2ResolveFirePayload`. That downstream boundary is also
observational and still does not feed ROB/PE resolve mutation or W2 clear.
R348 consumes only `writebackFire` with the R341 writeback payload in
`LoadReplayReturnPipeW2WritebackFirePayload`. That downstream boundary is also
observational and still does not feed RF writeback mutation, writeback
arbitration, or W2 clear.
R349 consumes only `wakeupFire` with the R342 wakeup payload in
`LoadReplayReturnPipeW2WakeupFirePayload`. That downstream boundary is also
observational and still does not feed ready-table mutation, issue wakeup, or
W2 clear.
R350 consumes the final `fireMask` with R347/R348/R349 fire-payload valids in
`LoadReplayReturnPipeW2SideEffectFireComplete`. That downstream boundary is
also observational and still does not feed W2 clear or replay-row lifecycle.

## Deferred Owners

- Live ROB/PE resolve mutation consuming `resolveFire`.
- Live replay RF writeback mutation consuming `writebackFire`.
- Live ready-table and issue-wakeup mutation consuming `wakeupFire`.
- Atomic side-effect issue and W2 clear once all sinks are real and the R345
  pre-completion permit stays equivalent to the readiness join.
- Replay-row lifecycle retirement after side-effect fire pulses mutate real
  state.

## Verification

Focused gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2SideEffectFireVector
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2SideEffectIssuePermit
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2SideEffectPayloadPlan
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2SideEffectCompletionPermit
bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop
FETCH_REDUCED_STORE_REPLAY_LIQ=1 BUILD_DIR=generated/r346-replay-pipe-w2-side-effect-fire-vector-xcheck bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh
```

Reference tests cover full-mask fire, resolve-only fire, no-accept dormancy,
disabled/flush blockers, empty accepted masks, request and payload mismatches,
and Chisel elaboration.
