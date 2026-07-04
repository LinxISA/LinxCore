# LoadReplaySourceReturnStoreSnapshotRequestControl

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplaySourceReturnStoreSnapshotRequestControl.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/LoadReplaySourceReturnStoreSnapshotRequestControlSpec.scala`
- Integrated user: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplaySourceReturnStoreSnapshotPath.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/mtccore/lsu/load_unit/ldq.cpp`
    - `MtcLDQInfo::pickL1`
    - `MtcLDQInfo::loadRepick`
    - `MtcLDQInfo::handleL1Lookup`
  - `model/LinxCoreModel/model/mtccore/lsu/store_unit/store_unit.cpp`
    - `MtcStoreUnit::handleLoadReq`
  - `model/LinxCoreModel/model/mtccore/lsu/load_unit/ldq.cpp`
    - `MtcLDQInfo::handleSTQReceive`
- Related Chisel contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplaySourceReturnStoreSnapshotQueryIssue.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplaySourceReturnStoreSnapshotAcceptedToken.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplaySourceReturnStoreSnapshotPath.scala`
- Contract IDs: `LC-CHISEL-LSU-REPLAY-STQ-SNAPSHOT-REQUEST-CONTROL-001`

## Purpose

`LoadReplaySourceReturnStoreSnapshotRequestControl` names the live request/sink
gate in front of local STQ snapshot query issue.

The model uses software deques: `MtcLDQInfo::handleL1Lookup` pushes the
selected repick request to `lookup_lu_su_q`, `MtcStoreUnit::handleLoadReq`
drains that queue, and `MtcLDQInfo::handleSTQReceive` later consumes the
returned `lookup_su_lu_q` response. Hardware needs an explicit valid/ready
boundary and must also respect the R397 accepted-query token capacity before
issuing another request. This module owns that gate.

R401 inserts this owner inside `LoadReplaySourceReturnStoreSnapshotPath`. The
top still ties `requestEnable=false` and `sinkReady=false`, so generated RTL
behavior remains dormant while the composite path now has a named promotion
point for future live STQ lookup issue.
R403 wires `rawSinkReady` to the internal request queue's enqueue capacity
inside the composite. The path-level `sinkReady` now drains that queue toward a
future raw store-unit sink.
R430 forwards `blockedByToken` through the composite path boundary so live-arm
promotion can distinguish request-FIFO capacity from accepted-query token
capacity before issuing another LU-to-SU lookup.

## Interface

### Inputs

| Signal | Description |
|---|---|
| `enable` | Replay-LIQ source-return path is active. |
| `flush` | Flush suppresses live request arming. |
| `requestEnable` | Future live-mode arm for issuing selected-row STQ snapshot requests. |
| `launchValid` | A selected replay row needs local STQ snapshot evidence. |
| `rawSinkReady` | Request acceptance capacity. In the R403 composite this is the request queue's enqueue readiness. |
| `tokenCanAccept` | Accepted-query token slot is empty or otherwise ready for a new outstanding request. |

### Outputs

| Signal | Description |
|---|---|
| `active` | `enable && !flush`. |
| `requestActive` | Live request mode is enabled in an active cycle. |
| `queryCandidate` | Active selected replay row is visible to the request owner. |
| `queryRequestEnable` | Request-enable value forwarded to `LoadReplaySourceReturnStoreSnapshotQueryIssue`. |
| `querySinkReady` | Sink readiness forwarded to query issue after active/request/raw-sink/token gating. |
| `blockedByDisabled` | Request, launch, or sink evidence appears while the path is disabled. |
| `blockedByFlush` | Request, launch, or sink evidence appears during flush. |
| `blockedByRequestDisabled` | A selected row needs a query while live issue remains disabled. |
| `blockedByNoLaunch` | Live issue is enabled without a selected row. |
| `blockedBySink` | A valid live query waits for request acceptance capacity. |
| `blockedByToken` | Raw sink is ready, but the accepted-query token cannot accept another request. |

## State

The module is combinational. It does not store selected-row identity or queue
requests. Outstanding request identity remains owned by
`LoadReplaySourceReturnStoreSnapshotAcceptedToken`.

## Logic Design

The owner separates request acceptance readiness from token capacity:

```text
active              = enable && !flush
requestActive       = active && requestEnable
queryCandidate      = active && launchValid
queryRequestEnable  = requestEnable
querySinkReady      = active && requestEnable && rawSinkReady && tokenCanAccept
blockedBySink       = requestActive && launchValid && !rawSinkReady
blockedByToken      = requestActive && launchValid && rawSinkReady && !tokenCanAccept
```

`querySinkReady` feeds `LoadReplaySourceReturnStoreSnapshotQueryIssue.sinkReady`.
That query owner still computes `queryIssued` from its own active/request/launch
predicate, so this module does not create an issue without a selected launch
row.

## Timing

The R401 owner is same-cycle. R403 adds the request queue behind this gate, but
token capacity remains part of the issue boundary.

## Flush/Recovery

Flush clears `active`, `requestActive`, `queryCandidate`, and
`querySinkReady`. Request evidence visible during flush is reported through
`blockedByFlush`.

## Deferred Owners

- Raw store-unit request sink behind the R403 request queue.
- Multi-token accepted-query queueing.
- Raw STQ response source wiring.
- Wait-store mutation and returned-data merge after response acceptance.

## Verification

Focused gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadReplaySourceReturnStoreSnapshotRequestControl
bash tools/chisel/run_chisel_tests.sh --only LoadReplaySourceReturnStoreSnapshotPath
bash tools/chisel/run_chisel_tests.sh --only LoadReplaySourceReturnStoreSnapshotQueryIssue
bash tools/chisel/run_chisel_tests.sh --only LoadReplaySourceReturnStoreSnapshotAcceptedToken
bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop
FETCH_REDUCED_STORE_REPLAY_LIQ=1 BUILD_DIR=generated/r403x bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh
```

Reference tests cover the dormant reduced-top shape, live-ready request,
raw-sink stall, token-capacity stall, disabled/flush/no-launch diagnostics, and
Chisel elaboration.
