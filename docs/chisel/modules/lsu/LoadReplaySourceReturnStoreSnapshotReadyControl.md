# LoadReplaySourceReturnStoreSnapshotReadyControl

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplaySourceReturnStoreSnapshotReadyControl.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/LoadReplaySourceReturnStoreSnapshotReadyControlSpec.scala`
- Integrated user: `rtl/LinxCore/chisel/src/main/scala/linxcore/top/LinxCoreFrontendFetchRfAluTraceTop.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/lsu/load_unit/ldq.cpp`
    - `LDQInfo::handleSTQReceive`
    - `LDQInfo::pickL1`
    - `LDQInfo::waitStore`
    - `LDQInfo::handleMerge`
- Related Chisel contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplaySourceReturnStoreSnapshotQueryIssue.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplaySourceReturnStoreSnapshotEvidence.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplaySourceReturnReadiness.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplaySourceReturnScbLiveControl.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/ResidentStoreForwardStoreSnapshot.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/ReducedLoadReplayLiqAllocPath.scala`
- Contract IDs: `LC-CHISEL-LSU-REPLAY-STQ-SNAPSHOT-001`

## Purpose

`LoadReplaySourceReturnStoreSnapshotReadyControl` names the local
resident-store snapshot readiness boundary before
`LoadReplaySourceReturnReadiness.storeSnapshotReady`.

R390 keeps the integrated reduced top behavior unchanged. The current top uses
a combinational `ResidentStoreForwardStoreSnapshot`, so the control runs with
`requestEnable=false` and forwards the legacy snapshot-ready input exactly.
R391 feeds `snapshotRequired` and `snapshotValid` from
`LoadReplaySourceReturnStoreSnapshotEvidence`, but leaves the live request gate
disabled. R392 names the selected-row STQ query-issue boundary but leaves that
request disabled too. Future live mode can enable this control only after
selected-row STQ query issue and response matching are both stable.

## Interface

### Inputs

| Signal | Description |
|---|---|
| `enable` | Replay-LIQ wrapper is active. |
| `flush` | Store/replay flush suppresses live snapshot request outputs. |
| `requestEnable` | Future mode gate for consuming live selected-row snapshot evidence. Current top ties this false. |
| `legacySnapshotReady` | Current reduced-top readiness bit. It preserves the pre-R390 combinational snapshot behavior. |
| `snapshotRequired` | Selected replay row requires a local STQ source-return response. R391 feeds this from `LoadReplaySourceReturnStoreSnapshotEvidence`. |
| `snapshotValid` | Selected replay row has completed local STQ evidence. R391 feeds this from `LoadReplaySourceReturnStoreSnapshotEvidence`. |

### Outputs

| Signal | Description |
|---|---|
| `active` | `enable && !flush`. |
| `requestActive` | Active plus `requestEnable`. |
| `snapshotEvidenceValid` | Active cycle with required or valid snapshot evidence present. |
| `legacyReady` | The unmodified legacy snapshot-ready input. |
| `liveReady` | Live request is active and either no snapshot is required or the required snapshot is valid. |
| `storeSnapshotReady` | Legacy readiness when `requestEnable=false`; otherwise live readiness. |
| `blockedByDisabled` | Request/evidence exists while the wrapper is disabled. |
| `blockedByFlush` | Request/evidence exists during flush. |
| `blockedByRequestDisabled` | Snapshot evidence exists while the live request gate is disabled. |
| `blockedByLegacySnapshot` | Legacy mode is active but the legacy snapshot-ready input is false. |
| `blockedBySnapshot` | Live request requires selected snapshot evidence that has not returned. |

## State

The module is combinational and owns no state.

## Logic Design

The model keeps STQ/source return separate from SCB/source return. In
`LDQInfo::handleSTQReceive`, non-`LDQ_REPICK` responses are ignored, `scbRnt`
must already be set, and the row sets `stqRnt` before either waiting for a
store or merging returned bytes. `LDQInfo::pickL1` later calls `returnData`
only when data is complete, `(ldqRnt || l1Rnt)` is true, `scbRnt` is true,
`stqRnt` is true, and an IEX return pipe is available.

This Chisel owner controls the local STQ snapshot side of that predicate:

1. Form `active = enable && !flush`.
2. Form `requestActive = active && requestEnable`.
3. Report snapshot evidence when either R391 `snapshotRequired` or
   `snapshotValid` is present in an active cycle.
4. In legacy mode, forward `legacySnapshotReady` directly to preserve the
   current reduced top behavior.
5. In live mode, require `requestActive` and either no required snapshot or a
   valid selected snapshot.
6. Publish disabled, flush, request-disabled, legacy-not-ready, and
   live-snapshot blockers separately.

`LoadReplaySourceReturnReadiness` remains the combined source-return owner.
This module only produces its `storeSnapshotReady` input.

## Timing

The control is same-cycle combinational logic in front of
`LoadReplaySourceReturnReadiness`. It does not latch resident snapshot state;
R391 provides the current combinational evidence classifier and R392 provides a
disabled query-issue owner. Future selected-row STQ response matching must
provide stable response inputs to that classifier before live request mode is
enabled.

## Flush/Recovery

Flush clears `active` and suppresses live readiness. Legacy mode still forwards
the legacy input so R390 does not alter the current reduced top's disabled-live
behavior.

## Deferred Owners

- Live selected-row STQ response matching.
- Stateful STQ/source return tracking across replay-row repick cycles.
- Full LIQ/LDQ relaunch, LHQ publication, ready-table wakeup, and memory trace
  rows.

## Verification

Focused gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadReplaySourceReturnStoreSnapshotReadyControl
bash tools/chisel/run_chisel_tests.sh --only LoadReplaySourceReturnStoreSnapshotQueryIssue
bash tools/chisel/run_chisel_tests.sh --only LoadReplaySourceReturnStoreSnapshotEvidence
bash tools/chisel/run_chisel_tests.sh --only LoadReplaySourceReturnReadiness
bash tools/chisel/run_chisel_tests.sh --only LoadReplayLaunchReadiness
bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop
FETCH_REDUCED_STORE_REPLAY_LIQ=1 BUILD_DIR=generated/r392x bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh
```

Reference tests cover legacy snapshot readiness preservation, optional live
snapshot release, required live snapshot blocking/release, disabled/flush/
request-disabled suppression, and Chisel elaboration.
