# LoadReplayReturnPublishControl

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPublishControl.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/LoadReplayReturnPublishControlSpec.scala`
- Integrated user: `rtl/LinxCore/chisel/src/main/scala/linxcore/top/LinxCoreFrontendFetchRfAluTraceTop.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/lsu/load_unit/ldq.cpp`
    - `LDQInfo::returnData`
    - `lsuIexLretArray[iexIdx]->Write(bus)`
    - `iex->setMemWakeup(bus)`
    - cross-line return `lsuIexLretArray[iexIdx]->Write(crossBus)`
  - `model/LinxCoreModel/model/iex/iex.cpp`
    - `IEX::setMemData`
    - `IEX::setMemWakeup`
  - `model/LinxCoreModel/model/iex/iex_iq.cpp`
    - `IssueQueue::WakeupIQTag`
  - `model/LinxCoreModel/model/iex/rtable.cpp`
    - `ReadyState::SetRegReadyTable`
- Related Chisel contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPublishReady.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnSideEffectReady.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnLretPayload.scala`
- Contract IDs: `LC-CHISEL-LSU-REPLAY-PUBLISH-CTRL-001`

## Purpose

`LoadReplayReturnPublishControl` is the named fire-point for a future live
replay return publication. The model return path first has valid extracted
data, then publishes the returned memory request to LRET, and also performs
returned-data, ready-table, and issue-wakeup side effects through IEX-owned
paths. R315 names side-effect readiness; R316 adds the final explicit
`liveEnable` guard so a reduced top can expose the full publish predicate
without accidentally making replay return state live.

R381 feeds `liveEnable` from `LoadReplayReturnSideEffectLiveControl` instead
of a top-local false constant. The current top ties that owner's
`liveRequested` input low. Therefore `publishFire` is still always false in
the reduced replay-LIQ wrapper, even when R378 feeds real LRET sink capacity
into the side-effect readiness join.

## Interface

| Direction | Signal | Description |
|---|---|---|
| input | `enable` | Replay-LIQ wrapper is active. |
| input | `liveEnable` | Global owner-controlled permission to fire live replay-return publication. R381 currently drives this from a live-control owner whose request input is tied low. |
| input | `payloadValid` | LRET payload exists for the selected replay-return row. |
| input | `publishReady` | Data-valid plus consumer-ready join from `LoadReplayReturnPublishReady`. |
| input | `sideEffectsReady` | LRET/writeback/wakeup side-effect readiness from `LoadReplayReturnSideEffectReady`. |
| output | `candidateValid` | Payload is valid while replay-LIQ mode is enabled. |
| output | `publishArmed` | Payload, publish readiness, and side-effect readiness are all true. |
| output | `publishFire` | `publishArmed` and `liveEnable`; the future side-effect fire pulse. |
| output | `blockedByDisabled` | Payload exists while replay-LIQ mode is disabled. |
| output | `blockedByNoPayload` | Replay-LIQ mode is enabled but no payload is valid. |
| output | `blockedByPublish` | Payload exists, but data/consumer publish readiness is false. |
| output | `blockedBySideEffects` | Payload and publish readiness are true, but a side-effect sink is not ready. |
| output | `blockedByLiveDisabled` | The publish predicate is fully armed but the live fire gate is disabled. |

## Logic Design

The publish gate is deliberately simple and staged after the existing
diagnostic owners:

```text
candidateValid = enable && payloadValid
publishArmed = candidateValid && publishReady && sideEffectsReady
publishFire = publishArmed && liveEnable
```

Blocker precedence mirrors the pipeline:

1. `blockedByPublish` covers data/consumer readiness.
2. `blockedBySideEffects` is checked only after `publishReady`.
3. `blockedByLiveDisabled` reports only when the whole publish predicate is
   armed but the top-level live gate is still disabled.

This keeps the current reduced top from interpreting diagnostic payload
validity as permission to enqueue LRET, write RF, update ready state, or wake
issue queues.

## Deferred Owners

- Replacing the R381 live-control `liveRequested := false.B` tie-off with an
  owner-gated mode bit after LRET, replay RF writeback, ready-table, and
  wakeup sinks are implemented.
- Driving LRET enqueue, RF writeback, ready-table update, and wakeup fire from
  `publishFire`.
- Feeding a live publish result back into replay launch/clear/retry policy.
- Cross-line return publication and multi-destination side-effect fanout.

## Verification

Focused gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPublishControl
bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop
FETCH_REDUCED_STORE_REPLAY_LIQ=1 BUILD_DIR=generated/r316-replay-publish-control-xcheck bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh
```

Reference tests cover full live fire, armed-but-disabled behavior,
publish-readiness blockers, side-effect blockers, disabled/empty payload
diagnostics, live-disabled blocker suppression when not armed, and Chisel
elaboration.

The R316 fixture manifest at
`generated/r316-replay-publish-control-xcheck/report/crosscheck_manifest.json`
records `status: "pass"`, `summary.compared_rows: 3`, and
`summary.mismatch_count: 0`.
