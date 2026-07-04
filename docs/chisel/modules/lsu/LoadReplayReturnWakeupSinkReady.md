# LoadReplayReturnWakeupSinkReady

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnWakeupSinkReady.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/LoadReplayReturnWakeupSinkReadySpec.scala`
- Integrated user: `rtl/LinxCore/chisel/src/main/scala/linxcore/top/LinxCoreFrontendFetchRfAluTraceTop.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/lsu/load_unit/ldq.cpp`
    - `LDQInfo::returnData`
    - `iex->setMemWakeup(bus)`
  - `model/LinxCoreModel/model/iex/iex.cpp`
    - `IEX::setMemWakeup`
  - `model/LinxCoreModel/model/iex/iex_iq.cpp`
    - `IssueQueue::WakeupIQTag`
  - `model/LinxCoreModel/model/iex/rtable.cpp`
    - `ReadyState::SetRegReadyTable`
- Related Chisel contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnConsumerReady.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnSideEffectReady.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnWakeupCandidate.scala`
- Contract IDs: `LC-CHISEL-LSU-REPLAY-WAKEUP-SINK-READY-001`

## Purpose

`LoadReplayReturnWakeupSinkReady` names the pre-W2 ready-table and issue-wakeup
sink boundary for replay-return publication. LinxCoreModel publishes regular
load wakeups from `LDQInfo::returnData` through `IEX::setMemWakeup` unless the
request is a speculative wakeup or stack-valid row. `IEX::setMemWakeup` then
fans destination wakeups through `IssueQueue::WakeupIQTag`, which updates issue
queues and the ready table.

The current reduced Chisel top does not own live ready-table mutation or issue
wakeup for replay returns. R379 therefore separates abstract sink capacity from
live mutation enable. The sink can be armed for diagnostics, but
`wakeupSinkReady` remains low while `liveEnable` is false. R381 now drives
that input from `LoadReplayReturnSideEffectLiveControl`, whose current
`liveRequested` input is tied low, preserving the existing
disabled-live-replay contract.

## Interface

| Direction | Signal | Description |
|---|---|---|
| input | `enable` | Replay-LIQ wrapper is active. |
| input | `flush` | Suppresses wakeup readiness during replay flush. |
| input | `liveEnable` | Future live ready-table and issue-wakeup mutation enable. The current top ties this low. |
| input | `wakeupRequired` | Current replay-return payload requires a regular memory wakeup. |
| input | `sinkReady` | Abstract ready-table/issue-wakeup sink capacity. R379 ties this high in the reduced top. |
| output | `candidateValid` | Enabled, not flushing, and wakeup is required. |
| output | `wakeupArmed` | Candidate exists and the abstract wakeup sink is ready. |
| output | `wakeupSinkReady` | Candidate is armed and live mutation is enabled. This feeds `LoadReplayReturnConsumerReady` and `LoadReplayReturnSideEffectReady`. |
| output | `blockedByDisabled` | Wakeup is required while replay-LIQ mode is disabled. |
| output | `blockedByFlush` | Wakeup is required while flush suppresses the sink. |
| output | `blockedByNoWakeup` | Replay-LIQ mode is enabled and not flushing, but no wakeup is required. |
| output | `blockedBySink` | Candidate exists but the abstract sink is not ready. |
| output | `blockedByLiveDisabled` | Candidate is armed, but live wakeup mutation is still disabled. |

## Logic Design

```text
candidateValid = enable && !flush && wakeupRequired
wakeupArmed = candidateValid && sinkReady
wakeupSinkReady = wakeupArmed && liveEnable
```

`blockedBySink` is reported before live-disabled blocking because an unready
ready-table/issue-wakeup sink cannot arm a request. With `sinkReady=true` and
`liveEnable=false`, the current top can show that regular memory wakeup would
otherwise be acceptable while still preventing replay launch and side-effect
publication.

## Integration

R379 wires this module in `LinxCoreFrontendFetchRfAluTraceTop` after
`LoadReplayReturnWakeupCandidate`:

- `wakeupRequired` comes from the pre-W2 replay-return wakeup candidate;
- `sinkReady` is tied high as an abstract diagnostic capacity source;
- R381 `LoadReplayReturnSideEffectLiveControl.wakeupLiveEnable` feeds
  `liveEnable`, and its live request is tied low, so the output
  `wakeupSinkReady` remains false;
- `LoadReplayReturnConsumerReady.wakeupSinkReady` and
  `LoadReplayReturnSideEffectReady.wakeupSinkReady` consume the module output
  instead of a raw false tie-off;
- top-level diagnostics expose candidate, armed, ready, and blocker signals
  under `reducedLoadReplayLiqReturnWakeupSink*`.

Because `wakeupSinkReady` is still false, R379 does not enable normal
replay-LIQ relaunch, LRET enqueue, ready-table mutation, issue wakeup, RF
writeback, ROB/PE resolve, replay-row lifecycle clear, or pipe residency.

## Deferred Owners

- Live ready-table mutation and issue-queue wakeup fanout.
- Capacity/backpressure from the real wakeup sink.
- Atomic promotion with replay RF writeback, LRET publication, row lifecycle,
  and W2 side-effect owners.
- Multi-destination, scalar-local-link, and non-GPR wakeup routing beyond the
  reduced one-destination diagnostic.

## Verification

Focused gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnWakeupSinkReady
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnConsumerReady
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnSideEffectReady
bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop
FETCH_REDUCED_STORE_REPLAY_LIQ=1 BUILD_DIR=generated/r379x bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh
```

Reference tests cover live wakeup readiness, live-disabled arming, sink
backpressure priority, disabled/flush/no-wakeup diagnostics, and Chisel
elaboration.
