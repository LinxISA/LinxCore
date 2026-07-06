# LoadReplayReturnPublishRequest

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPublishRequest.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/LoadReplayReturnPublishRequestSpec.scala`
- Integrated user: `rtl/LinxCore/chisel/src/main/scala/linxcore/top/LinxCoreFrontendFetchRfAluTraceTop.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/lsu/load_unit/ldq.cpp`
    - `LDQInfo::returnData`
    - `lsuIexLretArray[iexIdx]->Write(bus)`
    - `iex->setMemWakeup(bus)`
  - `model/LinxCoreModel/model/iex/iex.cpp`
    - `IEX::receiveFromLSU`
    - `IEX::setMemData`
    - `IEX::setMemWakeup`
  - `model/LinxCoreModel/model/iex/iex_iq.cpp`
    - `IssueQueue::WakeupIQTag`
  - `model/LinxCoreModel/model/iex/rtable.cpp`
    - `ReadyState::SetRegReadyTable`
- Related Chisel contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPublishControl.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnWritebackCandidate.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnWakeupCandidate.scala`
- Contract IDs: `LC-CHISEL-LSU-REPLAY-PUBLISH-REQ-001`

## Purpose

`LoadReplayReturnPublishRequest` names the request vector that a future live
replay-return publish pulse would fan out after R316 `publishFire`. In the
model, `LDQInfo::returnData` writes the returned `MemReqBus` to the LRET queue,
`IEX::receiveFromLSU` later consumes that queue and calls `IEX::setMemData`,
and non-speculative/non-stack returns may also call `IEX::setMemWakeup`, which
updates issue queues and ready-table state.

The current Chisel top still ties `publishFire` low through
`LoadReplayReturnPublishControl.liveEnable`. Therefore every request output in
the integrated reduced replay-LIQ wrapper remains false. This module is only a
diagnostic owner for the future side-effect fanout shape.
R540 adds harness-only sideband counters for this request boundary:
`publish_request_valid`, `publish_request_lret`, `publish_request_writeback`,
`publish_request_wakeup`, `publish_request_mask_nonzero`,
`publish_request_blocked_by_no_fire`, and
`publish_request_invalid_fire_without_payload`. These counters are used with
the publish-control and LRET-payload counters to prove whether the LRET FIFO
stays empty because no payload is valid or because a valid payload is blocked
at the live fire/request fanout point.
The R540 replay-loop probe observed all request counters at zero, including
`publish_request_valid=0`, `publish_request_lret=0`, `publish_request_writeback=0`,
`publish_request_wakeup=0`, and `publish_request_invalid_fire_without_payload=0`.
This is expected for the current blocker because `lret_payload_valid=0` and
`publish_control_fire=0`.

## Interface

| Direction | Signal | Description |
|---|---|---|
| input | `publishFire` | Future live replay-return publish pulse from `LoadReplayReturnPublishControl`. |
| input | `payloadValid` | LRET payload exists for the selected replay-return row. |
| input | `writebackRequired` | Reduced GPR writeback candidate exists for this payload. |
| input | `wakeupRequired` | Payload requires regular memory wakeup/ready-table publication. |
| output | `requestValid` | A valid payload is being published. |
| output | `lretRequest` | Request to enqueue the returned payload on the LRET path. Always follows `requestValid`. |
| output | `writebackRequest` | Request to publish the reduced replay RF writeback side effect. |
| output | `wakeupRequest` | Request to publish the wakeup/ready-table side effect. |
| output | `requestMask` | Three-bit diagnostic mask `{wakeupRequest, writebackRequest, lretRequest}`. |
| output | `blockedByNoFire` | Payload exists, but the publish fire pulse is not asserted. |
| output | `invalidFireWithoutPayload` | Defensive diagnostic for a fire pulse without a payload. |

## Logic Design

The module is a pure fanout request shaper:

```text
requestValid = publishFire && payloadValid
lretRequest = requestValid
writebackRequest = requestValid && writebackRequired
wakeupRequest = requestValid && wakeupRequired
requestMask = {wakeupRequest, writebackRequest, lretRequest}
```

`blockedByNoFire` is expected to assert in the current top whenever a replay
payload reaches this boundary, because live publication is intentionally
disabled. `invalidFireWithoutPayload` should stay false when driven by
`LoadReplayReturnPublishControl`, because that control only fires from a valid
payload.

## Deferred Owners

- Driving real LRET enqueue from `lretRequest`.
- Driving replay-side RF writeback from `writebackRequest`.
- Driving ready-table and issue-queue wakeup mutation from `wakeupRequest`.
- Feeding successful request acceptance back into replay-row clear/retry
  policy.
- Cross-line return publication and multi-destination fanout.

## Verification

Focused gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPublishRequest
bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop
FETCH_REDUCED_STORE_REPLAY_LIQ=1 BUILD_DIR=generated/r317-replay-publish-request-xcheck bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh
```

Reference tests cover all-request fanout, LRET-only fanout, no-fire
suppression, illegal fire-without-payload diagnostics, independent request mask
bits, and Chisel elaboration.

The R317 fixture manifest at
`generated/r317-replay-publish-request-xcheck/report/crosscheck_manifest.json`
records `status: "pass"`, `summary.compared_rows: 3`, and
`summary.mismatch_count: 0`.
