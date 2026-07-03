# ReducedScalarWritebackArbiter

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/execute/ReducedScalarWritebackArbiter.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/execute/ReducedScalarWritebackArbiterSpec.scala`
- LinxCoreModel evidence:
  - `tools/LinxCoreModel/model/iex/iex.cpp`: `IEX::setMemData`
  - `tools/LinxCoreModel/model/iex/iex.cpp`: `IEX::setMemWakeup`
  - `tools/LinxCoreModel/model/iex/iex_iq.cpp`: `IssueQueue::WakeupIQTag`
  - `tools/LinxCoreModel/model/iex/rtable.cpp`: `ReadyState::SetRegReadyTable`
- Contract IDs: `LC-IF-CHISEL-IEX-WB-001`, `LC-IF-CHISEL-LSU-REPLAY-RETURN-014`

## Purpose

`ReducedScalarWritebackArbiter` owns the single reduced scalar RF write-port
choice between ordinary execute completion and the future replay-return GPR
writeback path.

The current top wires the replay side from the W2
`LoadReplayReturnPipeW2WritebackArbiterInput` boundary. R362 drives
`replayEnable` from the shared W2 side-effect live-control owner; that owner's
request remains false, so the arbiter still preserves execute-only RF mutation
while proving the final replay writeback handoff path.

## Interface

| Direction | Signal | Type | Valid/ready | Description |
|---|---|---|---|---|
| input | `executeValid` | `Bool` | pulse | Ordinary ALU/load execute completion has a scalar physical GPR result. |
| input | `executeTag` | `UInt(physRegWidth.W)` | with `executeValid` | Physical destination tag for execute writeback. |
| input | `executeData` | `UInt(dataWidth.W)` | with `executeValid` | Data for execute writeback. |
| input | `replayEnable` | `Bool` | static/dynamic gate | Permits replay-return writeback candidates to use the RF port. |
| input | `replayValid` | `Bool` | pulse | Replay-return path has a GPR writeback candidate. |
| input | `replayTag` | `UInt(physRegWidth.W)` | with `replayValid` | Physical destination tag for replay writeback. |
| input | `replayData` | `UInt(dataWidth.W)` | with `replayValid` | Data for replay writeback. |
| output | `writeValid` | `Bool` | pulse | Selected RF write-port valid. |
| output | `writeTag` | `UInt(physRegWidth.W)` | with `writeValid` | Selected physical destination tag. |
| output | `writeData` | `UInt(dataWidth.W)` | with `writeValid` | Selected writeback data. |
| output | `selectedExecute` | `Bool` | diagnostic | Execute completion won the port. |
| output | `selectedReplay` | `Bool` | diagnostic | Replay completion won the port. |
| output | `replayBlockedByDisabled` | `Bool` | diagnostic | A replay candidate was present while replay writeback was disabled. |
| output | `replayBlockedByExecute` | `Bool` | diagnostic | A replay candidate was present but execute used the port. |

## Logic Design

The LinxCoreModel splits load-return side effects across several owners:
`IEX::setMemData` copies returned load data into ROB destination state,
`IEX::setMemWakeup` decides when a returned memory request has enough real
responses to wake dependents, `IssueQueue::WakeupIQTag` fans the wakeup through
issue queues and calls `SetRegReadyTable`, and `ReadyState::SetRegReadyTable`
marks the destination ready. The reduced Chisel lane still has one scalar RF
write port, so the replay-return data side effect must arbitrate with ordinary
execute completion before it can become live.

The arbiter is combinational:

- execute writeback always has priority;
- replay can write only when `replayEnable && replayValid && !executeValid`;
- disabled replay candidates are reported without mutating outputs;
- blocked same-cycle replay candidates are reported when execute owns the port;
- stale output tag/data fields are zero when no source is selected.

In `LinxCoreFrontendFetchRfAluTraceTop`, `replayValid` is the W2 writeback
arbiter-input candidate valid, and `replayTag`/`replayData` are the same W2
boundary's live-gated write fields. Because `replayEnable` remains false
through the shared W2 live-control owner, candidate rows cannot select the RF
port until W2 writeback, resolve, wakeup, clear/refill, and replay-row
lifecycle mutation are promoted together.

## Timing

No state is stored in the arbiter. The selected output feeds
`ReducedScalarRegisterFile.write*`, which performs the actual RF mutation on the
rising edge.

## Trace/Observability

`LinxCoreFrontendFetchRfAluTraceTop` exposes the selected RF write as the
existing `rfWrite*` signals and publishes replay-specific arbitration
diagnostics under `reducedLoadReplayLiqWritebackArbiter*`.

## Verification

- `bash tools/chisel/run_chisel_tests.sh --only ReducedScalarWritebackArbiter`
- `bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop`
- `FETCH_REDUCED_STORE_REPLAY_LIQ=1 BUILD_DIR=generated/r314-replay-writeback-arbiter-xcheck bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh`
- `FETCH_REDUCED_STORE_REPLAY_LIQ=1 BUILD_DIR=generated/r362-replay-pipe-w2-writeback-rf-arbiter-xcheck bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh`
