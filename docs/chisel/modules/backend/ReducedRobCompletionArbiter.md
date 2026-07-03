# ReducedRobCompletionArbiter

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/backend/ReducedRobCompletionArbiter.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/backend/ReducedRobCompletionArbiterSpec.scala`
- Integrated user: `rtl/LinxCore/chisel/src/main/scala/linxcore/top/LinxCoreFrontendFetchRfAluTraceTop.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/iex/pipe/lda_pipe.cpp`: `LDAPipe::runW2`
  - `model/LinxCoreModel/model/iex/pipe/agu_pipe.cpp`: `AGUPipe::runW2`
  - `model/LinxCoreModel/model/ModelCommon/SimInstInfo.cpp`: `SimInstInfo::GenRslvBus`
- Related Chisel contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/backend/DecodeRenameROBPath.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2RobCompleteSource.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/rob/ROBEntryBank.scala`
- Contract IDs: `LC-CHISEL-BACKEND-ROB-COMPLETE-ARBITER-001`

## Purpose

`ReducedRobCompletionArbiter` owns the reduced top's single ROB completion-port
choice before `DecodeRenameROBPath`. Before R364, the top wired execute
completion directly into the backend path. R364 adds a replay-W2 completion
source, so completion-port selection is now explicit and testable.

Execute keeps priority. Replay completion is selected only when execute has no
completion in the same cycle.

## Interface

| Direction | Signal | Description |
|---|---|---|
| input | `executeCompleteValid` | Ordinary execute completion candidate. |
| input | `executeCompleteRobValue` | Execute RID value. |
| input | `executeCompleteRowValid` / `executeCompleteRow` | Execute completion row replacement payload. |
| input | `replayCompleteValid` | Replay-W2 ROB completion candidate. |
| input | `replayCompleteRobValue` | Replay RID value. |
| input | `replayCompleteRowValid` / `replayCompleteRow` | Replay row replacement payload; currently false/zero from R364 source. |
| output | `completeValid` | Selected completion-port valid. |
| output | `completeRobValue` | Selected completion RID. |
| output | `completeRowValid` / `completeRow` | Selected row replacement payload. |
| output | `selectedExecute` / `selectedReplay` | Source selection diagnostics. |
| output | `replayBlockedByExecute` | Replay was present but execute owned the port. |

## Logic Design

```text
selectedExecute = executeCompleteValid
selectedReplay = replayCompleteValid && !executeCompleteValid
completeValid = selectedExecute || selectedReplay
```

All selected fields follow the chosen source. When neither source is selected,
`completeRobValue` is zero, `completeRowValid` is false, and `completeRow` is
zero.

This arbiter does not decide whether replay W2 may fire. The LSU
`LoadReplayReturnPipeW2RobCompleteSource` publishes structural port readiness
to the resolve sink before a future replay side-effect request can reach this
arbiter.

## Integration

`LinxCoreFrontendFetchRfAluTraceTop` wires:

- execute side from `ReducedScalarAluExecute.complete*`;
- replay side from `LoadReplayReturnPipeW2RobCompleteSource.complete*`;
- selected output to `DecodeRenameROBPath.complete*`;
- diagnostics under `robCompleteArbiter*`.

`DecodeRenameROBPath` still arbitrates marker-internal completion after this
top-level source choice. A selected execute or replay completion has priority
over marker completion in the path, preserving the existing execute-first
behavior and adding replay as the next external completion source.

## Deferred Owners

- Replay completion row replacement with full load commit trace sidebands.
- Multi-source ROB completion scaling beyond one execute and one replay source.
- Live replay completion enable; R363 still keeps replay W2 side effects
  dormant through the atomic live-request gate.

## Verification

Focused gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only ReducedRobCompletionArbiter
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2RobCompleteSource
bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop
FETCH_REDUCED_STORE_REPLAY_LIQ=1 BUILD_DIR=generated/r364-replay-w2-rob-complete-source-xcheck bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh
```

Reference tests cover execute priority, replay selection while execute is idle,
idle-output suppression, and Chisel elaboration.
