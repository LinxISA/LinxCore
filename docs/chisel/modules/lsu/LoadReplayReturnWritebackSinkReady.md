# LoadReplayReturnWritebackSinkReady

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnWritebackSinkReady.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/LoadReplayReturnWritebackSinkReadySpec.scala`
- Integrated user: `rtl/LinxCore/chisel/src/main/scala/linxcore/top/LinxCoreFrontendFetchRfAluTraceTop.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/iex/iex.cpp`
    - `IEX::setMemData`
  - `model/LinxCoreModel/model/iex/pipe/lda_pipe.cpp`
    - `LDAPipe::runW2`
  - `model/LinxCoreModel/model/iex/pipe/agu_pipe.cpp`
    - `AGUPipe::runW2`
  - `model/LinxCoreModel/model/ModelCommon/SimInstInfo.cpp`
    - `SimInstInfo::GenRFReqBus`
- Related Chisel contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnWritebackCandidate.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnSideEffectReady.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/execute/ReducedScalarWritebackArbiter.scala`
- Contract IDs: `LC-CHISEL-LSU-REPLAY-WRITEBACK-SINK-READY-001`

## Purpose

`LoadReplayReturnWritebackSinkReady` names the pre-W2 scalar RF writeback sink
readiness boundary for replay-return publication. LinxCoreModel carries
returned load data through `IEX::setMemData`; W2 load pipes then generate RF
writeback requests from the resident instruction with `GenRFReqBus(false)`.

The current reduced top does not own live replay RF writeback from the
pre-W2 publication path. R380 therefore separates abstract RF write-port
availability from live mutation enable. The sink can arm when the execute
writeback port is idle, but `writebackSinkReady` remains low while
`liveEnable` is false, preserving the disabled-live-replay contract.

## Interface

| Direction | Signal | Description |
|---|---|---|
| input | `enable` | Replay-LIQ wrapper is active. |
| input | `flush` | Suppresses writeback readiness during replay flush. |
| input | `liveEnable` | Future live replay RF writeback enable. The current top ties this low. |
| input | `writebackRequired` | Current replay-return payload requires scalar GPR writeback. |
| input | `sinkReady` | Abstract scalar RF write-port availability. R380 derives this from execute writeback arbitration. |
| output | `candidateValid` | Enabled, not flushing, and writeback is required. |
| output | `writebackArmed` | Candidate exists and the abstract RF writeback sink is ready. |
| output | `writebackSinkReady` | Candidate is armed and live writeback mutation is enabled. This feeds `LoadReplayReturnSideEffectReady`. |
| output | `blockedByDisabled` | Writeback is required while replay-LIQ mode is disabled. |
| output | `blockedByFlush` | Writeback is required while flush suppresses the sink. |
| output | `blockedByNoWriteback` | Replay-LIQ mode is enabled and not flushing, but no writeback is required. |
| output | `blockedBySink` | Candidate exists but the abstract RF writeback sink is not ready. |
| output | `blockedByLiveDisabled` | Candidate is armed, but live RF writeback is still disabled. |

## Logic Design

```text
candidateValid = enable && !flush && writebackRequired
writebackArmed = candidateValid && sinkReady
writebackSinkReady = writebackArmed && liveEnable
```

`blockedBySink` is reported before live-disabled blocking because an occupied
RF write port cannot arm a replay writeback request. With `sinkReady` true and
`liveEnable` false, the current top can show that writeback would otherwise be
acceptable while still preventing replay launch and side-effect publication.

## Integration

R380 wires this module in `LinxCoreFrontendFetchRfAluTraceTop` after
`LoadReplayReturnWritebackCandidate`:

- `writebackRequired` comes from the pre-W2 replay-return writeback candidate;
- `sinkReady` is `!ReducedScalarWritebackArbiter.selectedExecute`, the
  abstract single-port scalar RF availability after execute priority;
- `liveEnable` is tied low, so `writebackSinkReady` remains false;
- `LoadReplayReturnSideEffectReady.writebackSinkReady` consumes this module
  output instead of deriving readiness from the W2 live-control path;
- top-level diagnostics expose candidate, armed, ready, and blocker signals
  under `reducedLoadReplayLiqReturnWritebackSink*`.

Because `writebackSinkReady` is still false, R380 does not enable normal
replay-LIQ relaunch, LRET enqueue, RF writeback, ready-table mutation, issue
wakeup, ROB/PE resolve, replay-row lifecycle clear, or pipe residency.

## Deferred Owners

- Live replay RF writeback from the pre-W2 replay-return publication path.
- Atomic promotion with LRET publication, ready-table/issue wakeup, row
  lifecycle, and W2 side-effect owners.
- Multi-destination, scalar-local-link, vector, tile, and non-GPR writeback
  routing beyond the reduced one-GPR diagnostic.

## Verification

Focused gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnWritebackSinkReady
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnSideEffectReady
bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop
FETCH_REDUCED_STORE_REPLAY_LIQ=1 BUILD_DIR=generated/r380x bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh
```

Reference tests cover live writeback readiness, live-disabled arming, RF port
backpressure priority, disabled/flush/no-writeback diagnostics, and Chisel
elaboration.
