# LoadReplayReturnPipeW2WakeupSinkReady

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2WakeupSinkReady.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/LoadReplayReturnPipeW2WakeupSinkReadySpec.scala`
- Integrated user: `rtl/LinxCore/chisel/src/main/scala/linxcore/top/LinxCoreFrontendFetchRfAluTraceTop.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/iex/pipe/lda_pipe.cpp`
    - `LDAPipe::runW2`
    - `WakeupScalarLocalLinks`
  - `model/LinxCoreModel/model/iex/pipe/agu_pipe.cpp`
    - `AGUPipe::runW2`
    - `WakeupScalarLocalLinks`
  - `model/LinxCoreModel/model/iex/iex.cpp`
    - `IEX::setMemWakeup`
  - `model/LinxCoreModel/model/iex/iex_iq.cpp`
    - `IssueQueue::WakeupIQTag`
- Related Chisel contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2CompletionCandidate.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2SideEffectReady.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnWakeupCandidate.scala`
- Contract IDs: `LC-CHISEL-LSU-REPLAY-PIPE-W2-WAKEUP-SINK-READY-001`

## Purpose

`LoadReplayReturnPipeW2WakeupSinkReady` names the ready-table and issue-wakeup
readiness boundary for a W2 load-return completion. In LinxCoreModel,
`LDAPipe::runW2` and `AGUPipe::runW2` publish W2 resolve data, optionally
produce RF writeback, and call `WakeupScalarLocalLinks` for scalar local-link
destinations. Load-return memory wakeup also reaches `IEX::setMemWakeup`,
which fans destination wakeups through `IssueQueue::WakeupIQTag`; that fanout
updates dependent issue queues and the ready table.

The current reduced top does not yet own live W2 ready-table mutation or
issue-wakeup fanout. This module therefore separates abstract wakeup sink
capacity from live mutation enable: a W2 wakeup can become `wakeupArmed`, but
`wakeupSinkReady` remains low until `liveEnable` is asserted by a future
packet.

## Interface

| Direction | Signal | Description |
|---|---|---|
| input | `enable` | Replay-LIQ wrapper is active. |
| input | `flush` | Suppresses W2 wakeup readiness during replay flush. |
| input | `liveEnable` | Future live W2 ready-table and issue-wakeup mutation enable. Current top ties this low. |
| input | `wakeupRequired` | W2 completion classifier says the current legal W2 entry requires a wakeup side effect. |
| input | `sinkReady` | Abstract ready-table/issue-wakeup sink capacity. Current top ties this high so the only R338 block is live-disabled. |
| output | `candidateValid` | Enabled, not flushing, and W2 wakeup is required. |
| output | `wakeupArmed` | Candidate exists and the abstract wakeup sink is ready. |
| output | `wakeupSinkReady` | Candidate is armed and live W2 wakeup mutation is enabled. This feeds `LoadReplayReturnPipeW2SideEffectReady.wakeupSinkReady`. |
| output | `blockedByDisabled` | Wakeup is required while replay-LIQ mode is disabled. |
| output | `blockedByFlush` | Wakeup is required while replay flush suppresses the sink. |
| output | `blockedByNoWakeup` | Replay-LIQ mode is enabled and not flushing, but no W2 wakeup is required. |
| output | `blockedBySink` | Candidate exists but the abstract wakeup sink is not ready. |
| output | `blockedByLiveDisabled` | Candidate is armed, but live W2 wakeup mutation is still disabled. |

## Logic Design

```text
candidateValid = enable && !flush && wakeupRequired
wakeupArmed = candidateValid && sinkReady
wakeupSinkReady = wakeupArmed && liveEnable
```

`blockedBySink` is reported before live-disabled blocking because an unready
wakeup sink cannot arm a request. With `sinkReady=true` and
`liveEnable=false`, the current top can show that W2 wakeup would be otherwise
acceptable while still preventing W2 completion.

## Integration

`LinxCoreFrontendFetchRfAluTraceTop` wires this module behind
`LoadReplayReturnPipeW2CompletionCandidate`:

- `wakeupRequired` comes from the W2 completion classifier;
- `sinkReady` is tied high as an abstract one-entry diagnostic sink;
- `liveEnable` is tied low, so `wakeupSinkReady` remains false;
- `LoadReplayReturnPipeW2SideEffectReady.wakeupSinkReady` now consumes this
  module output instead of a literal `false.B`;
- top-level diagnostics expose wakeup-sink candidate, armed, ready, and
  blocker signals under `reducedLoadReplayLiqLretPipeW2WakeupSink*`.

Because `wakeupSinkReady` is still false, R338 does not let W2 completion fire
or clear the W2 slot. It only names the third W2 side-effect sink owner needed
before live `runW2` retirement.

## Deferred Owners

- Real W2 ready-table mutation and issue-wakeup fanout.
- Capacity/backpressure from the real wakeup sink.
- Coordination with replay RF writeback and resolve sinks before W2 slot clear.
- Multi-destination, scalar-local-link, and non-GPR wakeup routing beyond the
  reduced classifier already exposed by `LoadReplayReturnPipeW2CompletionCandidate`.

## Verification

Focused gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2WakeupSinkReady
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2SideEffectReady
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2CompletionCandidate
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2Slot
bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop
FETCH_REDUCED_STORE_REPLAY_LIQ=1 BUILD_DIR=generated/r338-replay-pipe-w2-wakeup-sink-xcheck bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh
```

Reference tests cover live wakeup readiness, live-disabled arming, abstract
wakeup-sink backpressure priority, disabled/flush/no-wakeup diagnostics, and
Chisel elaboration.
