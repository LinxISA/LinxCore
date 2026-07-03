# LoadReplayReturnPipeW2SideEffectCompletionPermit

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2SideEffectCompletionPermit.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/LoadReplayReturnPipeW2SideEffectCompletionPermitSpec.scala`
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
- Related Chisel contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2CompletionCandidate.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2SideEffectReady.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2ResolveSinkReady.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2WritebackSinkReady.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2WakeupSinkReady.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2SideEffectIssuePermit.scala`
- Contract IDs: `LC-CHISEL-LSU-REPLAY-PIPE-W2-SIDE-EFFECT-COMPLETION-PERMIT-001`

## Purpose

`LoadReplayReturnPipeW2SideEffectCompletionPermit` names the pre-completion
permit for a replay-LIQ W2 entry to clear after all required side-effect sinks
are ready. It is the pre-clear counterpart to the R344 post-plan issue permit.

In LinxCoreModel, `LDAPipe::runW2` and `AGUPipe::runW2` stamp W2 timing, may
generate an RF write request with `SimInstInfo::GenRFReqBus(false)`, publish a
resolve bus with `SimInstInfo::GenRslvBus`, and then wake scalar/local links.
The Chisel reduced top already has the R335 readiness join that feeds
`LoadReplayReturnPipeW2CompletionCandidate.sideEffectsReady`. R345 keeps that
live path unchanged and adds an observational permit that exposes the same
pre-completion predicate as masks plus a join-equivalence diagnostic.

## Interface

| Direction | Signal | Description |
|---|---|---|
| input | `enable` | Replay-LIQ wrapper is active. |
| input | `flush` | Suppresses the pre-completion permit during replay flush. |
| input | `sideEffectCandidateValid` | Legal W2 completion candidate from the completion classifier. Current top feeds this from `resolveRequired`. |
| input | `resolveRequired` | W2 completion must publish resolve state. |
| input | `writebackRequired` | W2 completion needs a reduced scalar GPR writeback. |
| input | `wakeupRequired` | W2 completion needs a ready-table/issue wakeup. |
| input | `resolveSinkReady` | Live-gated resolve sink readiness from R336. |
| input | `writebackSinkReady` | Live-gated writeback sink readiness from R337. |
| input | `wakeupSinkReady` | Live-gated wakeup sink readiness from R338. |
| input | `readyJoinSideEffectsReady` | Existing R335 readiness result consumed by `LoadReplayReturnPipeW2CompletionCandidate`. |
| output | `candidateValid` | Enabled, not flushing, and the W2 completion classifier has a legal side-effect candidate. |
| output | `requiredMask` | `{wakeup, writeback, resolve}` required-side-effect mask. |
| output | `sinkReadyMask` | `{wakeup, writeback, resolve}` live-gated sink-ready mask. |
| output | `missingReadyMask` | Required sinks that are not ready. |
| output | `allRequiredSinksReady` | Optional sinks are ignored and every required sink is ready. |
| output | `completionPermitted` | Candidate exists, at least one side effect is required, and every required sink is ready. |
| output | `matchesReadyJoin` | `completionPermitted` matches the existing R335 `sideEffectsReady` result. |
| output | `blockedByDisabled` | Candidate was presented while replay-LIQ mode was disabled. |
| output | `blockedByFlush` | Candidate was presented during flush. |
| output | `blockedByNoCandidate` | Enabled, not flushing, and no W2 side-effect candidate exists. |
| output | `blockedByNoRequiredSink` | Candidate exists with an empty required-side-effect mask. |
| output | `blockedByResolveSink` | Resolve is required but not ready. |
| output | `blockedByWritebackSink` | Writeback is required but not ready. |
| output | `blockedByWakeupSink` | Wakeup is required but not ready. |
| output | `blockedByReadyJoinMismatch` | The named permit diverges from the existing R335 readiness join. |

## Logic Design

The module is intentionally pure combinational logic:

```text
candidateValid = enable && !flush && sideEffectCandidateValid
requiredMask = {wakeupRequired, writebackRequired, resolveRequired}
sinkReadyMask = {wakeupSinkReady, writebackSinkReady, resolveSinkReady}
missingReadyMask = requiredMask & ~sinkReadyMask
completionPermitted =
  candidateValid && requiredMask.orR && missingReadyMask == 0
matchesReadyJoin = readyJoinSideEffectsReady == completionPermitted
```

Resolve is expected for every legal W2 side-effect candidate. The empty-mask
blocker is retained as a wiring diagnostic because a legal candidate with no
required side effect would be inconsistent with the model `runW2` path.

## Integration

`LinxCoreFrontendFetchRfAluTraceTop` wires the module beside the existing R335
readiness join:

- candidate and required inputs come from
  `LoadReplayReturnPipeW2CompletionCandidate`;
- sink readiness comes from R336/R337/R338 live-gated sink owners;
- `readyJoinSideEffectsReady` comes from `LoadReplayReturnPipeW2SideEffectReady`;
- compact top diagnostics expose candidate, required mask, missing-ready mask,
  permit, join-equivalence, and aggregate blocked state under
  `reducedLoadReplayLiqLretPipeW2SideEffectCompletionPermit*`.

R345 does not feed `LoadReplayReturnPipeW2CompletionCandidate.sideEffectsReady`,
W2 clear, post-completion request generation, ROB resolve, RF writeback,
ready-table mutation, issue wakeup, or replay-row lifecycle. The current live
path remains the R335 readiness join, so fixture behavior is unchanged.

## Deferred Owners

- Replace or merge the R335 readiness join only after the named permit has
  stayed equivalent under live sink bring-up.
- Real W2 resolve mutation and ROB/PE resolve-array publication.
- Real replay RF writeback mutation and arbitration.
- Real ready-table and issue-queue wakeup mutation.
- Atomic side-effect issue/clear ordering once all three live sinks are owned.

## Verification

Focused gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2SideEffectCompletionPermit
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2SideEffectReady
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2ResolveSinkReady
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2WritebackSinkReady
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2WakeupSinkReady
bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop
FETCH_REDUCED_STORE_REPLAY_LIQ=1 BUILD_DIR=generated/r345-replay-pipe-w2-side-effect-completion-permit-xcheck bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh
```

Reference tests cover full side-effect permit, resolve-only permit, missing
sink masks, disabled/flush/no-candidate dormancy, empty required-mask
diagnostics, R335 readiness-join divergence, and Chisel elaboration.
