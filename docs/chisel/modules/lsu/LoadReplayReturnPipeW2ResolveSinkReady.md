# LoadReplayReturnPipeW2ResolveSinkReady

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2ResolveSinkReady.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/LoadReplayReturnPipeW2ResolveSinkReadySpec.scala`
- Integrated user: `rtl/LinxCore/chisel/src/main/scala/linxcore/top/LinxCoreFrontendFetchRfAluTraceTop.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/iex/pipe/lda_pipe.cpp`
    - `LDAPipe::runW2`
  - `model/LinxCoreModel/model/iex/pipe/agu_pipe.cpp`
    - `AGUPipe::runW2`
  - `model/LinxCoreModel/model/pe/PECommon/PROBCommon.cpp`
    - `ROBState::resolveData`
- Related Chisel contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2CompletionCandidate.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2SideEffectReady.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnRobResolveDataCandidate.scala`
- Contract IDs: `LC-CHISEL-LSU-REPLAY-PIPE-W2-RESOLVE-SINK-READY-001`

## Purpose

`LoadReplayReturnPipeW2ResolveSinkReady` names the resolve-side readiness
boundary for a W2 load-return completion. In LinxCoreModel, `LDAPipe::runW2`
and `AGUPipe::runW2` publish W2 completion to the resolve side for every
resident W2 entry that reaches the completion path. The older
`LoadReplayReturnRobResolveDataCandidate` documents the pre-W2 `setMemData`
ROB data-valid intent; R336 names the later `runW2` resolve sink readiness
needed before the W2 slot may clear.

The current reduced top does not yet own live W2 resolve mutation. This module
therefore separates abstract sink capacity from live mutation enable: a W2
resolve can become `resolveArmed`, but `resolveSinkReady` remains low until
`liveEnable` is asserted by a future packet.

## Interface

| Direction | Signal | Description |
|---|---|---|
| input | `enable` | Replay-LIQ wrapper is active. |
| input | `flush` | Suppresses W2 resolve readiness during replay flush. |
| input | `liveEnable` | Future live W2 resolve mutation enable. Current top ties this low. |
| input | `resolveRequired` | W2 completion classifier says the current legal W2 entry requires resolve. |
| input | `sinkReady` | Abstract resolve sink capacity/readiness. Current top ties this high so the only R336 block is live-disabled. |
| output | `candidateValid` | Enabled, not flushing, and W2 resolve is required. |
| output | `resolveArmed` | Candidate exists and the abstract resolve sink is ready. |
| output | `resolveSinkReady` | Candidate is armed and live W2 resolve mutation is enabled. This feeds `LoadReplayReturnPipeW2SideEffectReady.resolveSinkReady`. |
| output | `blockedByDisabled` | Resolve is required while replay-LIQ mode is disabled. |
| output | `blockedByFlush` | Resolve is required while replay flush suppresses the sink. |
| output | `blockedByNoResolve` | Replay-LIQ mode is enabled and not flushing, but no W2 resolve is required. |
| output | `blockedBySink` | Candidate exists but the abstract sink is not ready. |
| output | `blockedByLiveDisabled` | Candidate is armed, but live W2 resolve mutation is still disabled. |

## Logic Design

```text
candidateValid = enable && !flush && resolveRequired
resolveArmed = candidateValid && sinkReady
resolveSinkReady = resolveArmed && liveEnable
```

`blockedBySink` is reported before live-disabled blocking because an unready
abstract sink cannot arm a resolve request. With `sinkReady=true` and
`liveEnable=false`, the current top can show that W2 resolve would be
otherwise acceptable while still preventing W2 completion.

## Integration

`LinxCoreFrontendFetchRfAluTraceTop` wires this module behind
`LoadReplayReturnPipeW2CompletionCandidate`:

- `resolveRequired` comes from the W2 completion classifier;
- `sinkReady` is tied high as an abstract one-entry diagnostic sink;
- `liveEnable` is tied low, so `resolveSinkReady` remains false;
- `LoadReplayReturnPipeW2SideEffectReady.resolveSinkReady` now consumes this
  module output instead of a literal `false.B`;
- top-level diagnostics expose resolve-sink candidate, armed, ready, and
  blocker signals under `reducedLoadReplayLiqLretPipeW2ResolveSink*`.

Because `resolveSinkReady` is still false, R336 does not let W2 completion
fire or clear the W2 slot. It only names the first W2 side-effect sink owner
needed before live `runW2` retirement.

## Deferred Owners

- Real W2 resolve mutation and ROB/replay-row lifecycle update.
- Capacity/backpressure from the real resolve/ROB sink.
- Coordination with replay RF writeback and wakeup sinks before W2 slot clear.
- Multi-pipe and vector/MEM W2 resolve publication.

## Verification

Focused gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2ResolveSinkReady
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2SideEffectReady
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2CompletionCandidate
bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop
FETCH_REDUCED_STORE_REPLAY_LIQ=1 BUILD_DIR=generated/r336-replay-pipe-w2-resolve-sink-xcheck bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh
```

Reference tests cover live resolve readiness, live-disabled arming, sink
backpressure priority, disabled/flush/no-resolve diagnostics, and Chisel
elaboration.
