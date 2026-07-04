# LoadReplaySourceReturnStoreSnapshotLiveArmPolicy

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplaySourceReturnStoreSnapshotLiveArmPolicy.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/LoadReplaySourceReturnStoreSnapshotLiveArmPolicySpec.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/mtccore/lsu/load_unit/ldq.cpp`
    - `MtcLDQInfo::handleL1Lookup`
    - `MtcLDQInfo::handleSTQReceive`
  - `model/LinxCoreModel/model/mtccore/lsu/store_unit/store_unit.cpp`
    - `MtcStoreUnit::handleLoadReq`
- Contract ID: `LC-CHISEL-LSU-REPLAY-STQ-LIVE-ARM-POLICY-001`

## Purpose

`LoadReplaySourceReturnStoreSnapshotLiveArmPolicy` is the first named policy
owner for promoting the replay-LIQ local STQ snapshot path from dormant
diagnostics toward live request and sink arms.

R436 wires the policy into `LoadReplaySourceReturnStoreSnapshotPath` as
path-local diagnostics. The path exposes the policy outputs, but it still drives
`RequestControl.io.requestEnable` from the existing path `requestEnable` input
and `RequestSink.io.rawSinkReady` from the existing path `sinkReady` input.

R435 keeps `rowMutationLiveEnable` as a hard safety gate. The source-return
path must not issue a local STQ request or let a request sink generate a
response before the row-mutation side is intentionally armed; otherwise a
matched no-wait response could make store-snapshot evidence look complete while
the LIQ row still has not recorded the returned store state.

## Interface

### Inputs

| Signal | Description |
|---|---|
| `enable` | Replay source-return cluster is active. |
| `flush` | Hard clear/recovery cycle; suppresses both live arms. |
| `policyEnable` | Caller intent to use this policy instead of dormant tie-offs. |
| `rowMutationLiveEnable` | Safety gate proving the downstream row-mutation request boundary is intentionally live. |
| `launchValid` | A selected replay row wants a local STQ snapshot request. |
| `requestQueueCanAccept` | Request FIFO has enqueue capacity for a new selected request. |
| `acceptedTokenCanAccept` | Accepted-query owner can capture the request identity. |
| `requestHeadValid` | A resident request FIFO head is visible to the sink. |
| `rawSinkAvailable` | Local/raw store-unit request sink is allowed to consume a visible request. |
| `responseQueueFull` | Response FIFO is full, so a sink-generated response would be blocked. |
| `rawResponseValid` | A live raw response candidate has priority over a sink-generated response. |

### Outputs

| Signal | Description |
|---|---|
| `requestEnable` | Future drive value for `LoadReplaySourceReturnStoreSnapshotPath.requestEnable`. |
| `sinkReady` | Future drive value for `LoadReplaySourceReturnStoreSnapshotPath.sinkReady`. |
| `requestCandidate` | Policy-active launch request candidate. |
| `sinkCandidate` | Policy-active resident or same-cycle accepted request can reach the sink. |
| `responsePortBlocked` | The request sink may be raw-sink ready, but response enqueue is blocked by FIFO/full or raw-response priority. |
| `blockedBy*` and `request*/sink*/response*Blocked*` | Path-local blocker diagnostics for policy-disabled, no-launch, row-mutation-disabled, request FIFO, accepted-token, no-request, raw-sink, full-response-FIFO, and raw-response-priority cases. |

## State

The module is purely combinational. It does not store accepted query identity,
request payloads, response payloads, or row-mutation requests.

## Logic Design

The policy separates three concerns:

1. A new request can issue only when policy is active, a launch row is present,
   row mutation is live, the request FIFO can accept, and the accepted-token
   owner can capture the load identity.
2. The sink arm follows a resident request head or same-cycle accepted request,
   but still requires row mutation to be live and the raw/local sink to be
   available.
3. Response-port blockers are diagnostic only at this boundary. They do not
   force `sinkReady` low, because `LoadReplaySourceReturnStoreSnapshotRequestSink`
   already owns response-port blocking and must continue reporting
   response-FIFO or raw-response arbitration separately from raw-sink
   backpressure.

This mirrors the C++ model ownership split: `MtcLDQInfo::handleL1Lookup`
publishes the load request, `MtcStoreUnit::handleLoadReq` consumes and returns
the same request after STQ lookup, and `MtcLDQInfo::handleSTQReceive` mutates
the selected LDQ row only after identity and SCB-order proof.

## Deferred Owners

- Driving the reduced top's dormant `requestEnable` and `sinkReady` inputs from
  policy outputs.
- Promoting `rowMutationLiveEnable` after row-state and native LIQ write-control
  blockers are proven live.
- Full external `lookup_lu_su_q` / `lookup_su_lu_q` producer wiring.

## Verification

Focused gate:

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadReplaySourceReturnStoreSnapshotLiveArmPolicy
```

Reference tests cover the all-ready arm case, the row-mutation hard gate,
request FIFO and accepted-token blockers, resident request sink arming,
response-port blocker separation, no-request and raw-sink blockers, disabled
and flush suppression, policy-disabled suppression, and Chisel elaboration.
