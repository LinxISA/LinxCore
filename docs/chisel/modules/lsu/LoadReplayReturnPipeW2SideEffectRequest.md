# LoadReplayReturnPipeW2SideEffectRequest

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2SideEffectRequest.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/LoadReplayReturnPipeW2SideEffectRequestSpec.scala`
- Integrated user: `rtl/LinxCore/chisel/src/main/scala/linxcore/top/LinxCoreFrontendFetchRfAluTraceTop.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/iex/pipe/lda_pipe.cpp`
    - `LDAPipe::runW2`
  - `model/LinxCoreModel/model/iex/pipe/agu_pipe.cpp`
    - `AGUPipe::runW2`
- Related Chisel contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2CompletionCandidate.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2SideEffectReady.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2ResolveSinkReady.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2ResolveRequest.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2WritebackSinkReady.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2WritebackRequest.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2WakeupSinkReady.scala`
- Contract IDs: `LC-CHISEL-LSU-REPLAY-PIPE-W2-SIDE-EFFECT-REQUEST-001`

## Purpose

`LoadReplayReturnPipeW2SideEffectRequest` names the W2 side-effect request
vector that appears after a W2 completion fires. In LinxCoreModel,
`LDAPipe::runW2` and `AGUPipe::runW2` publish resolve state, optionally write
back a GPR destination, and optionally wake dependent consumers before the W2
pipe entry is retired.

R339 keeps those mutations dormant, but it separates the post-completion
request shape from the earlier readiness join. The module consumes the R334
completion valid signals, emits a compact resolve/writeback/wakeup request
mask, and reports illegal shapes such as a completed W2 entry without the
mandatory resolve request.

## Interface

| Direction | Signal | Description |
|---|---|---|
| input | `sideEffectCandidateValid` | W2 completion classifier found a legal side-effect candidate. Current top feeds this from `LoadReplayReturnPipeW2CompletionCandidate.resolveRequired`. |
| input | `completeValid` | W2 completion fired after every required W2 side-effect sink reported ready. |
| input | `resolveValid` | Completion classifier emits a W2 resolve side-effect. Legal completions require this. |
| input | `writebackValid` | Completion classifier emits an optional replay RF writeback side-effect. |
| input | `wakeupValid` | Completion classifier emits an optional wakeup side-effect. |
| output | `requestValid` | Candidate exists and W2 completion fired. |
| output | `resolveRequest` | Post-completion resolve request pulse. |
| output | `writebackRequest` | Post-completion replay RF writeback request pulse. |
| output | `wakeupRequest` | Post-completion ready-table/issue-wakeup request pulse. |
| output | `requestMask` | `{wakeupRequest, writebackRequest, resolveRequest}` packed into a 3-bit diagnostic vector. |
| output | `blockedByNoComplete` | Candidate exists, but W2 completion is still blocked by readiness. |
| output | `invalidCompleteWithoutCandidate` | Completion fired without a legal side-effect candidate. |
| output | `invalidCompleteWithoutResolve` | Completion fired without the mandatory resolve request. |
| output | `invalidResolveWithoutComplete` | Resolve valid appeared before W2 completion. |
| output | `invalidWritebackWithoutComplete` | Writeback valid appeared before W2 completion. |
| output | `invalidWakeupWithoutComplete` | Wakeup valid appeared before W2 completion. |

## Logic Design

```text
requestValid = completeValid && sideEffectCandidateValid
resolveRequest = requestValid && resolveValid
writebackRequest = requestValid && writebackValid
wakeupRequest = requestValid && wakeupValid
requestMask = Cat(wakeupRequest, writebackRequest, resolveRequest)
```

Resolve is mandatory for a legal W2 completion. Writeback and wakeup remain
optional because replayed loads can target non-GPR destinations or suppress
wakeup for the current reduced scenario. The invalid-shape diagnostics make
future sink wiring failures visible before those request pulses mutate ROB,
RF, ready-table, or issue-queue state.

## Integration

`LinxCoreFrontendFetchRfAluTraceTop` wires this module behind
`LoadReplayReturnPipeW2CompletionCandidate`:

- `sideEffectCandidateValid` comes from the completion classifier's
  `resolveRequired` output, which already identifies a legal W2 candidate;
- `completeValid`, `resolveValid`, `writebackValid`, and `wakeupValid` come
  directly from the W2 completion classifier;
- top-level diagnostics expose request valid, per-sink request pulses, the
  packed request mask, and invalid-shape blockers under
  `reducedLoadReplayLiqLretPipeW2SideEffectRequest*`.

Because R336-R338 keep all live W2 sinks disabled, `completeValid` remains low
in the current reduced top. R339 therefore names the post-completion request
surface without enabling W2 slot clear or any live side effect.

R340 consumes `resolveRequest` in `LoadReplayReturnPipeW2ResolveRequest` to
shape the future W2 `PEResolveBus` payload. That payload remains dormant until
the live W2 resolve sink becomes enabled.
R341 consumes `writebackRequest` in
`LoadReplayReturnPipeW2WritebackRequest` to shape the future reduced scalar RF
writeback payload. That payload remains dormant until the live W2 writeback
sink becomes enabled.

## Deferred Owners

- Real ROB resolve mutation driven by `resolveRequest`.
- Replay-side RF writeback driven by `writebackRequest`.
- Ready-table and issue-queue wakeup mutation driven by `wakeupRequest`.
- Arbitration and ordering between the request vector and live W2 slot clear.
- Error handling if future live wiring ever observes an invalid request shape.

## Verification

Focused gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2SideEffectRequest
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2ResolveRequest
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2CompletionCandidate
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2SideEffectReady
bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop
FETCH_REDUCED_STORE_REPLAY_LIQ=1 BUILD_DIR=generated/r339-replay-pipe-w2-side-effect-request-xcheck bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh
```

Reference tests cover all-side-effect requests, resolve-only requests,
pre-completion blocking, illegal completed shapes, side-effect valid pulses
that bypass completion, and Chisel elaboration.
