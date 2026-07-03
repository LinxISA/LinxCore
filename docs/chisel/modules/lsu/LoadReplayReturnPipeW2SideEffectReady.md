# LoadReplayReturnPipeW2SideEffectReady

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2SideEffectReady.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/LoadReplayReturnPipeW2SideEffectReadySpec.scala`
- Integrated user: `rtl/LinxCore/chisel/src/main/scala/linxcore/top/LinxCoreFrontendFetchRfAluTraceTop.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/iex/pipe/lda_pipe.cpp`
    - `LDAPipe::runW2`
  - `model/LinxCoreModel/model/iex/pipe/agu_pipe.cpp`
    - `AGUPipe::runW2`
- Related Chisel contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2CompletionCandidate.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2Slot.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnSideEffectReady.scala`
- Contract IDs: `LC-CHISEL-LSU-REPLAY-PIPE-W2-SIDE-EFFECT-READY-001`

## Purpose

`LoadReplayReturnPipeW2SideEffectReady` names the readiness join for the W2
side effects emitted by `LoadReplayReturnPipeW2CompletionCandidate`. In
LinxCoreModel, `LDAPipe::runW2` and `AGUPipe::runW2` retire a resident W2
entry by publishing resolve state, optionally generating an RF writeback, and
waking dependent scalar/local links. The Chisel reduced top has the W2
completion candidate, but the actual resolve, writeback, and wakeup sinks are
not live yet.

R335 makes that missing readiness contract explicit. The module reports when a
legal W2 completion candidate could retire if every required side-effect sink
were ready. In the current top, all three sink-ready inputs are still tied low,
so W2 completion and W2-slot clear remain dormant.

## Interface

| Direction | Signal | Description |
|---|---|---|
| input | `enable` | Replay-LIQ wrapper is active. |
| input | `candidateValid` | W2 completion has a legal side-effect candidate. Current top feeds this from `LoadReplayReturnPipeW2CompletionCandidate.resolveRequired`. |
| input | `resolveRequired` | Current candidate must publish resolve state. Legal W2 completions require this side effect. |
| input | `resolveSinkReady` | Future resolve/ROB sink can accept the W2 completion. Current top ties this low. |
| input | `writebackRequired` | Current candidate has a GPR destination writeback. |
| input | `writebackSinkReady` | Future replay RF writeback sink can accept the W2 data. Current top ties this low. |
| input | `wakeupRequired` | Current candidate requires scalar/local wakeup. |
| input | `wakeupSinkReady` | Future ready-table/issue-wakeup sink can accept the wakeup. Current top ties this low. |
| output | `readyCandidateValid` | Candidate is present while replay-LIQ mode is enabled. |
| output | `resolveReady` | Candidate exists and resolve is either not required or sink-ready. |
| output | `writebackReady` | Candidate exists and writeback is either not required or sink-ready. |
| output | `wakeupReady` | Candidate exists and wakeup is either not required or sink-ready. |
| output | `sideEffectsReady` | Candidate exists and every required W2 side-effect sink is ready. |
| output | `blockedByDisabled` | Candidate exists while replay-LIQ mode is disabled. |
| output | `blockedByNoCandidate` | Replay-LIQ mode is enabled but no W2 side-effect candidate exists. |
| output | `blockedByResolve` | Candidate requires resolve, but the resolve sink is not ready. |
| output | `blockedByWriteback` | Candidate requires writeback, but the writeback sink is not ready. |
| output | `blockedByWakeup` | Candidate requires wakeup, but the wakeup sink is not ready. |

## Logic Design

The module keeps the always-expected W2 resolve sink separate from optional
writeback and wakeup sinks:

```text
readyCandidateValid = enable && candidateValid
resolveReady = !resolveRequired || resolveSinkReady
writebackReady = !writebackRequired || writebackSinkReady
wakeupReady = !wakeupRequired || wakeupSinkReady
sideEffectsReady =
  readyCandidateValid && resolveReady && writebackReady && wakeupReady
```

The ready outputs are suppressed when no enabled candidate exists. Blocker
outputs report only the missing sink that is required by the current candidate,
so optional writeback and wakeup omissions do not falsely block a resolve-only
completion.

## Integration

`LinxCoreFrontendFetchRfAluTraceTop` wires this module between
`LoadReplayReturnPipeW2CompletionCandidate` and the completion candidate's
`sideEffectsReady` input:

- `candidateValid` and `resolveRequired` come from W2 completion
  `resolveRequired`, which identifies a legal W2 side-effect candidate;
- `writebackRequired` and `wakeupRequired` come from the W2 completion
  classifier;
- resolve, writeback, and wakeup sink readiness are tied low in R335;
- W2 completion consumes `sideEffectsReady` from this module rather than a
  literal `false.B`;
- top-level diagnostics expose candidate, per-sink readiness, final readiness,
  and blocker signals under `reducedLoadReplayLiqLretPipeW2SideEffect*`.

Because all W2 sinks remain inactive, R335 is an observability and ownership
packet only. It preserves fixture-visible behavior while naming the exact join
that later live sink wiring must satisfy before W2 entries can clear.

## Deferred Owners

- Real resolve/ROB sink readiness and mutation.
- Replay-side RF writeback sink readiness.
- Ready-table and issue-queue wakeup sink readiness.
- Multi-destination and non-GPR W2 side-effect routing beyond the reduced
  classifier already exposed by `LoadReplayReturnPipeW2CompletionCandidate`.
- Feeding live W2 side effects into ROB row lifecycle and replay-LIQ row
  retirement.

## Verification

Focused gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2SideEffectReady
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2CompletionCandidate
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2Slot
bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop
FETCH_REDUCED_STORE_REPLAY_LIQ=1 BUILD_DIR=generated/r335-replay-pipe-w2-side-effect-ready-xcheck bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh
```

Reference tests cover all-side-effect readiness, resolve-only readiness,
independent missing-sink blockers, optional writeback/wakeup suppression,
disabled/no-candidate diagnostics, and Chisel elaboration.
