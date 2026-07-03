# LoadReplayReturnPipeW2WritebackSinkReady

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2WritebackSinkReady.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/LoadReplayReturnPipeW2WritebackSinkReadySpec.scala`
- Integrated user: `rtl/LinxCore/chisel/src/main/scala/linxcore/top/LinxCoreFrontendFetchRfAluTraceTop.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/iex/pipe/lda_pipe.cpp`
    - `LDAPipe::runW2`
  - `model/LinxCoreModel/model/iex/pipe/agu_pipe.cpp`
    - `AGUPipe::runW2`
  - `model/LinxCoreModel/model/ModelCommon/SimInstInfo.cpp`
    - `SimInstInfo::GenRFReqBus`
  - `model/LinxCoreModel/model/iex/iex_rf.cpp`
    - `RegFile::Work`
- Related Chisel contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2CompletionCandidate.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2SideEffectReady.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/execute/ReducedScalarWritebackArbiter.scala`
- Contract IDs: `LC-CHISEL-LSU-REPLAY-PIPE-W2-WRITEBACK-SINK-READY-001`

## Purpose

`LoadReplayReturnPipeW2WritebackSinkReady` names the RF-writeback readiness
boundary for a W2 load-return completion. In LinxCoreModel, `LDAPipe::runW2`
and `AGUPipe::runW2` reset the pipe write bus, stamp W2 timing, optionally
generate a register-file write request with `GenRFReqBus(false)`, then publish
resolve data and wake scalar/local links.

The current reduced top does not yet own live W2 replay writeback into the
scalar RF arbiter. This module therefore separates abstract scalar RF sink
availability from live mutation enable: a W2 writeback can become
`writebackArmed`, but `writebackSinkReady` remains low until `liveEnable` is
asserted by a future packet.

## Interface

| Direction | Signal | Description |
|---|---|---|
| input | `enable` | Replay-LIQ wrapper is active. |
| input | `flush` | Suppresses W2 writeback readiness during replay flush. |
| input | `liveEnable` | Future live W2 replay writeback enable. R357 drives this from `LoadReplayReturnPipeW2SideEffectLiveControl`, whose request is tied low in the current top. |
| input | `writebackRequired` | W2 completion classifier says the legal W2 entry requires GPR writeback. |
| input | `sinkReady` | Abstract scalar RF writeback sink availability. Current top derives this from the reduced execute-priority writeback arbiter's execute-port selection. |
| output | `candidateValid` | Enabled, not flushing, and W2 writeback is required. |
| output | `writebackArmed` | Candidate exists and the abstract scalar RF writeback sink is ready. |
| output | `writebackSinkReady` | Candidate is armed and live W2 replay writeback is enabled. This feeds `LoadReplayReturnPipeW2SideEffectReady.writebackSinkReady`. |
| output | `blockedByDisabled` | Writeback is required while replay-LIQ mode is disabled. |
| output | `blockedByFlush` | Writeback is required while replay flush suppresses the sink. |
| output | `blockedByNoWriteback` | Replay-LIQ mode is enabled and not flushing, but no W2 writeback is required. |
| output | `blockedBySink` | Candidate exists but the abstract scalar RF writeback sink is not ready. |
| output | `blockedByLiveDisabled` | Candidate is armed, but live W2 replay writeback is still disabled. |

## Logic Design

```text
candidateValid = enable && !flush && writebackRequired
writebackArmed = candidateValid && sinkReady
writebackSinkReady = writebackArmed && liveEnable
```

`blockedBySink` is reported before live-disabled blocking because an unready
RF write port cannot arm a writeback request. With the R357 live-control
request disabled, the
current top can expose abstract writeback readiness without allowing W2
completion or clearing the W2 slot.

## Integration

`LinxCoreFrontendFetchRfAluTraceTop` wires this module behind
`LoadReplayReturnPipeW2CompletionCandidate`:

- `writebackRequired` comes from the W2 completion classifier;
- `sinkReady` is the abstract scalar RF write-port availability derived from
  `ReducedScalarWritebackArbiter` execute-port selection;
- `liveEnable` comes from R357 `LoadReplayReturnPipeW2SideEffectLiveControl`,
  whose request input is tied low, so `writebackSinkReady` remains false;
- `LoadReplayReturnPipeW2SideEffectReady.writebackSinkReady` now consumes this
  module output instead of a literal `false.B`;
- top-level diagnostics expose writeback-sink candidate, armed, ready, and
  blocker signals under `reducedLoadReplayLiqLretPipeW2WritebackSink*`.

Because `writebackSinkReady` is still false, R337 does not let W2 completion
fire or clear the W2 slot. It only names the second W2 side-effect sink owner
needed before live `runW2` retirement.

## Deferred Owners

- Real W2 replay RF writeback into the scalar RF arbiter.
- Replay ready-table update and issue wakeup fanout.
- Coordination with W2 resolve and wakeup sinks before W2 slot clear.
- Multi-pipe, vector, and non-GPR writeback publication.

## Verification

Focused gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2WritebackSinkReady
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2SideEffectReady
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2CompletionCandidate
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2Slot
bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop
FETCH_REDUCED_STORE_REPLAY_LIQ=1 BUILD_DIR=generated/r337-replay-pipe-w2-writeback-sink-xcheck bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh
```

Reference tests cover live writeback readiness, live-disabled arming, scalar
RF port backpressure priority, disabled/flush/no-writeback diagnostics, and
Chisel elaboration.
