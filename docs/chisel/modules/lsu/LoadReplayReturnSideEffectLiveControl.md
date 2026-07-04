# LoadReplayReturnSideEffectLiveControl

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnSideEffectLiveControl.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/LoadReplayReturnSideEffectLiveControlSpec.scala`
- Integrated user: `rtl/LinxCore/chisel/src/main/scala/linxcore/top/LinxCoreFrontendFetchRfAluTraceTop.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/lsu/load_unit/ldq.cpp`
    - `LDQInfo::returnData`
    - `lsuIexLretArray[iexIdx]->Write(bus)`
    - `iex->setMemWakeup(bus)`
  - `model/LinxCoreModel/model/iex/iex.cpp`
    - `IEX::setMemData`
    - `IEX::setMemWakeup`
  - `model/LinxCoreModel/model/iex/pipe/lda_pipe.cpp`
    - `LDAPipe::runW2`
  - `model/LinxCoreModel/model/iex/pipe/agu_pipe.cpp`
    - `AGUPipe::runW2`
  - `model/LinxCoreModel/model/ModelCommon/SimInstInfo.cpp`
    - `SimInstInfo::GenRFReqBus`
- Related Chisel contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPublishControl.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnWritebackSinkReady.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnWakeupSinkReady.scala`
- Contract IDs: `LC-CHISEL-LSU-REPLAY-SIDE-EFFECT-LIVE-CTRL-001`

## Purpose

`LoadReplayReturnSideEffectLiveControl` names the future atomic live request
point for pre-W2 replay-return publication. The model return path always
publishes an LRET/MemReqBus payload, may publish a replay RF writeback through
returned-data state, and may publish regular memory wakeup side effects through
IEX ready-table and issue-queue paths.

R381 centralizes the reduced top's live enables for those pre-W2 side effects:
bit 0 is publish/LRET, bit 1 is optional replay RF writeback, and bit 2 is
optional wakeup. The integrated top ties `liveRequested=false`, so every live
enable remains false. This replaces local `false.B` gates without enabling
LRET enqueue, replay RF mutation, ready-table mutation, or issue wakeup.

## Interface

| Direction | Signal | Description |
|---|---|---|
| input | `enable` | Replay-LIQ wrapper is active. |
| input | `flush` | Suppresses live enables during replay flush. |
| input | `liveRequested` | Future atomic request to make pre-W2 replay-return side effects live. Current top ties this low. |
| input | `payloadValid` | LRET payload exists for the selected replay-return row. |
| input | `writebackRequired` | Payload requires reduced scalar RF writeback. |
| input | `wakeupRequired` | Payload requires regular memory wakeup. |
| output | `active` | Replay-LIQ mode active and not flushing. |
| output | `requestActive` | `active` and the future live request is asserted. |
| output | `candidateValid` | Payload exists while active. |
| output | `requiredMask` | Required side-effect mask `{wakeup, writeback, publish}`. Publish/LRET is always required when `payloadValid` is true. |
| output | `liveEnableMask` | Live-enabled side-effect mask after `liveRequested` and candidate gating. |
| output | `anyRequired` | At least one side-effect is required. |
| output | `allRequiredLiveEnabled` | Candidate exists, live request is asserted, and every required bit is live-enabled. |
| output | `publishLiveEnable` | Live enable for `LoadReplayReturnPublishControl`. |
| output | `writebackLiveEnable` | Live enable for `LoadReplayReturnWritebackSinkReady`. |
| output | `wakeupLiveEnable` | Live enable for `LoadReplayReturnWakeupSinkReady`. |
| output | `blockedByDisabled` | Payload exists while replay-LIQ mode is disabled. |
| output | `blockedByFlush` | Payload exists while flush suppresses live side effects. |
| output | `blockedByNoPayload` | Replay-LIQ mode is active but no payload exists. |
| output | `blockedByLiveDisabled` | Candidate exists but the future live request remains low. |

## Logic Design

```text
active = enable && !flush
candidateValid = active && payloadValid
requiredMask = payloadValid ? {wakeupRequired, writebackRequired, 1'b1} : 0
liveEnableMask = candidateValid && liveRequested ? requiredMask : 0
```

`publishLiveEnable`, `writebackLiveEnable`, and `wakeupLiveEnable` are the
individual bits of `liveEnableMask`. The module deliberately does not inspect
sink readiness; readiness remains owned by `LoadReplayReturnSideEffectReady`
and the individual sink-ready modules.

## Integration

R381 wires this module in `LinxCoreFrontendFetchRfAluTraceTop` after the LRET
payload, writeback candidate, and wakeup candidate are formed:

- `publishLiveEnable` drives `LoadReplayReturnPublishControl.liveEnable`;
- `writebackLiveEnable` drives `LoadReplayReturnWritebackSinkReady.liveEnable`;
- `wakeupLiveEnable` drives `LoadReplayReturnWakeupSinkReady.liveEnable`;
- `liveRequested` is tied low, preserving the existing dormant behavior;
- compact top diagnostics expose candidate, required mask, live-enable mask,
  and blocker signals under `reducedLoadReplayLiqReturnSideEffectLive*`.

Because the live request is still false, R381 does not enable normal replay-LIQ
relaunch, LRET enqueue, replay RF writeback, ready-table mutation, issue
wakeup, ROB/PE resolve, replay-row lifecycle clear, or pipe residency.

## Deferred Owners

- A real atomic live request that coordinates LRET publication, replay RF
  writeback, wakeup, replay-row lifecycle, and W2 side-effect promotion.
- Live LRET enqueue and FIFO drain.
- Live replay RF writeback and ready-table/issue-wakeup mutation.
- Cross-line, multi-destination, scalar-local-link, vector, tile, and non-GPR
  side-effect fanout.

## Verification

Focused gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnSideEffectLiveControl
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPublishControl
bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop
FETCH_REDUCED_STORE_REPLAY_LIQ=1 BUILD_DIR=generated/r381x bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh
```

Reference tests cover disabled live request, required-mask shaping, LRET-only
publication, disabled/flush/no-payload blockers, and Chisel elaboration.
