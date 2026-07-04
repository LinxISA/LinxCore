# LoadReplayReturnSideEffectReady

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnSideEffectReady.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/LoadReplayReturnSideEffectReadySpec.scala`
- Integrated user: `rtl/LinxCore/chisel/src/main/scala/linxcore/top/LinxCoreFrontendFetchRfAluTraceTop.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/lsu/load_unit/ldq.cpp`
    - `LDQInfo::returnData`
    - `lsuIexLretArray[iexIdx]->Write(bus)`
    - `iex->setMemWakeup(bus)`
  - `model/LinxCoreModel/model/iex/iex.cpp`
    - `IEX::setMemData`
    - `IEX::setMemWakeup`
  - `model/LinxCoreModel/model/iex/iex_iq.cpp`
    - `IssueQueue::WakeupIQTag`
  - `model/LinxCoreModel/model/iex/rtable.cpp`
    - `ReadyState::SetRegReadyTable`
- Related Chisel contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnLretPayload.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnWritebackCandidate.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnWakeupCandidate.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/execute/ReducedScalarWritebackArbiter.scala`
- Contract IDs: `LC-CHISEL-LSU-REPLAY-SIDE-EFFECT-001`

## Purpose

`LoadReplayReturnSideEffectReady` names the final readiness join for a replay
return payload after data extraction, LRET formatting, reduced writeback
candidate formation, and reduced wakeup candidate formation. The model return
path publishes multiple effects for a returned load: `LDQInfo::returnData`
writes the LRET bus, `IEX::setMemData` stores returned data in the instruction
destination payloads, and `IEX::setMemWakeup` wakes dependent issue/ready-table
state when the request count allows it.

The current Chisel reduced top does not own live LRET enqueue, ready-table
mutation, issue wakeup, or replay RF writes. This module is therefore a
diagnostic owner only: it exposes the predicate a future live publish path must
satisfy without driving replay launch or mutating architectural state.

## Interface

| Direction | Signal | Description |
|---|---|---|
| input | `enable` | Replay-LIQ wrapper is active. |
| input | `payloadValid` | `LoadReplayReturnLretPayload` has a valid replay-return payload. |
| input | `lretSinkReady` | Future LRET/MemReqBus enqueue sink can accept the payload. |
| input | `writebackRequired` | Current payload has a reduced GPR RF writeback candidate. |
| input | `writebackSinkReady` | Future replay RF writeback side can accept the payload. |
| input | `wakeupRequired` | Current payload requires a regular memory wakeup. |
| input | `wakeupSinkReady` | Future ready-table/issue-wakeup sink can accept the payload. |
| output | `candidateValid` | Payload is valid while replay-LIQ mode is enabled. |
| output | `lretReady` | Candidate exists and the always-required LRET sink is ready. |
| output | `writebackReady` | Candidate exists and writeback is either not required or sink-ready. |
| output | `wakeupReady` | Candidate exists and wakeup is either not required or sink-ready. |
| output | `sideEffectsReady` | Candidate exists and every required side-effect sink is ready. |
| output | `blockedByDisabled` | Payload exists while replay-LIQ mode is disabled. |
| output | `blockedByNoPayload` | Replay-LIQ mode is enabled but no payload is valid. |
| output | `blockedByLret` | Candidate exists but LRET enqueue is not ready. |
| output | `blockedByWriteback` | Candidate requires reduced RF writeback but that sink is not ready. |
| output | `blockedByWakeup` | Candidate requires regular memory wakeup but that sink is not ready. |

## Logic Design

The module keeps the always-required LRET sink separate from optional side
effects:

```text
candidateValid = enable && payloadValid
writebackReady = !writebackRequired || writebackSinkReady
wakeupReady = !wakeupRequired || wakeupSinkReady
sideEffectsReady = candidateValid && lretSinkReady && writebackReady && wakeupReady
```

`blockedByWriteback` and `blockedByWakeup` report only when that side effect is
required for the current payload. `lretReady`, `writebackReady`, and
`wakeupReady` are all suppressed when there is no valid candidate, so stale
sink-ready inputs cannot look like a completed return.

In `LinxCoreFrontendFetchRfAluTraceTop`, R315 wires:

- `payloadValid` from `LoadReplayReturnLretPayload.payloadValid`;
- `writebackRequired` from `LoadReplayReturnWritebackCandidate.writeValid`;
- `writebackSinkReady` from the R314 writeback arbiter replay arm, which
  remains disabled;
- `wakeupRequired` from `LoadReplayReturnWakeupCandidate.wakeupRequired`;
- R378 LRET sink readiness from `LoadReplayReturnLretSink.enqueueReady`;
- wakeup sink readiness to the current tied-low reduced-top sink.

## Deferred Owners

- Live LRET queue enqueue fire and return-pipe drain backpressure.
- Replay-side RF writeback enable after LRET and wakeup sinks are live.
- Ready-table and issue-queue wakeup mutation.
- Multi-destination return publication and non-GPR destination sinks.
- Feeding `sideEffectsReady` back into replay launch/publish control.

## Verification

Focused gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnSideEffectReady
bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop
FETCH_REDUCED_STORE_REPLAY_LIQ=1 BUILD_DIR=generated/r315-replay-side-effect-ready-xcheck bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh
```

Reference tests cover all-side-effect readiness, LRET-only readiness, missing
sink diagnostics, optional writeback/wakeup suppression, disabled/empty payload
diagnostics, and Chisel elaboration.
