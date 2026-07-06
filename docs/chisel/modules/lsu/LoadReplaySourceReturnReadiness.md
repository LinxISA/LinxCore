# LoadReplaySourceReturnReadiness

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplaySourceReturnReadiness.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/LoadReplaySourceReturnReadinessSpec.scala`
- Integrated user: `rtl/LinxCore/chisel/src/main/scala/linxcore/top/LinxCoreFrontendFetchRfAluTraceTop.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/lsu/load_unit/ldq.cpp`
    - `LDQInfo::handleSCBReceive`
    - `LDQInfo::handleSTQReceive`
    - `LDQInfo::pickL1`
    - `LDQInfo::returnData`
- Related Chisel contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplaySourceReturnStoreSnapshotEvidence.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplaySourceReturnStoreSnapshotReadyControl.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplaySourceReturnScbLiveControl.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayLaunchReadiness.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnReadiness.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/ResidentStoreForwardStoreSnapshot.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadForwardPipeline.scala`
- Contract IDs: `LC-CHISEL-LSU-REPLAY-SRC-001`

## Purpose

`LoadReplaySourceReturnReadiness` is the source-return sideband owner for the
reduced replay-LIQ launch path. It separates the local resident-store snapshot
source from a future external SCB response source, then publishes the combined
source-return predicate consumed by `LoadReplayLaunchReadiness`. Since R418,
the reduced top sends `scbSourceReturned` to `LoadForwardPipeline.e2ScbReturned`
and `storeSourceReturned` to `LoadForwardPipeline.e2StqReturned`, so row state
can preserve the split source-return bits while downstream wakeup behavior
still observes the combined predicate.

R299 keeps this owner conservative. The current reduced top has a combinational
resident STQ snapshot and no live external SCB replay path. R391 now exposes
selected-row local-STQ evidence through
`LoadReplaySourceReturnStoreSnapshotEvidence`, but
`LoadReplaySourceReturnStoreSnapshotReadyControl.requestEnable` remains low and
the control still forwards the legacy snapshot-ready bit before this module.
The top also ties `LoadReplaySourceReturnScbLiveControl.requestEnable` and
source evidence low before this module. That keeps `externalScbPending` low and
lets source return become true only after the selected row has base data and
the local store snapshot is ready. The return pipe now has an explicit
`LoadReplayReturnReadiness` boundary, but the reduced top keeps that
boundary's pipe availability input disabled, so this packet does not relaunch
loads.

R484 uses the enabled early-STA fixture to split R483's parent
`LaunchReadinessBlockedByScb` report. The detailed source-return fields show
`scbSourceReturned=1`, `externalScbPending=0`, and
`blockedByScb=0`; the false combined `sourceReturned` bit comes from
`storeSnapshotReady=0` with `blockedByStoreSnapshot=1`. In other words, the
next live owner is local store-snapshot request/evidence readiness, not the
external SCB live-control path.

R541 extends the generated-RTL sideband report to persist this owner's existing
top outputs for the replay-loop fixture. The v16 report records
`source_return_candidate_valid=4`, `source_return_scb_source_returned=4`,
`source_return_store_source_returned=0`, `source_return_source_returned=0`, and
`source_return_blocked_by_store_snapshot=4`. The same run records
`source_return_scb_live_active=108` but no SCB live request/evidence/pending
activity, confirming the next owner is still the local store-snapshot readiness
path, not external SCB.

## Interface

### Inputs

| Signal | Description |
|---|---|
| `enable` | Replay-LIQ wrapper is active. |
| `launchValid` | A selected LIQ row is eligible for the launch path. |
| `baseDataReady` | Grant-qualified baseline load data has returned for the selected row. |
| `storeSnapshotReady` | The local resident-store snapshot feeding E2 is available. R390/R391 feed this from `LoadReplaySourceReturnStoreSnapshotReadyControl` after the R391 evidence classifier; the current live request remains disabled. |
| `externalScbPending` | A future external SCB source is required for this launch. R389 feeds this from `LoadReplaySourceReturnScbLiveControl`, whose current request is disabled. |
| `externalScbReturned` | The pending external SCB source has returned. R389 feeds this from `LoadReplaySourceReturnScbLiveControl`, whose current request is disabled. |

### Outputs

| Signal | Description |
|---|---|
| `candidateValid` | `enable && launchValid`. |
| `storeSourceReturned` | Candidate has grant-qualified base data and a local store snapshot. |
| `scbSourceReturned` | No external SCB source is pending, or the pending SCB source returned. |
| `sourceReturned` | Combined source-return predicate for replay launch and higher-level readiness diagnostics. |
| `blockedByDisabled` | Launch row exists while the wrapper is disabled. |
| `blockedByNoCandidate` | Wrapper is enabled but no launch row is selected. |
| `blockedByBaseData` | Source return is waiting on base data. |
| `blockedByStoreSnapshot` | Source return is waiting on the local resident-store snapshot. |
| `blockedByScb` | Source return is waiting on a pending external SCB response. |

## State

The module is combinational and owns no state. It only classifies the source
availability of the selected replay row.

## Logic Design

The model return loop only calls `returnData` after:

1. requested data bytes are complete,
2. `(ldqRnt || l1Rnt)` has returned,
3. `scbRnt` has returned,
4. `stqRnt` has returned, and
5. an IEX return pipe is available.

`LoadReplaySourceReturnReadiness` covers item 3 and the reduced-top shape of
item 4 for the replay-LIQ path:

1. Form `candidateValid` from `enable && launchValid`.
2. Require `baseDataReady` before claiming any source-return completion.
3. Require `storeSnapshotReady` for the local resident STQ source. R390 places
   `LoadReplaySourceReturnStoreSnapshotReadyControl` before this input, and
   R391 feeds that control's evidence inputs from
   `LoadReplaySourceReturnStoreSnapshotEvidence`. Current legacy mode forwards
   the opt-in wrapper enable because the snapshot is combinational, while
   R392 names the disabled selected-row query-issue side and future live mode
   must still provide selected-row STQ response evidence.
4. Treat SCB as returned when no external SCB path is pending. R389 now places
   `LoadReplaySourceReturnScbLiveControl` before these inputs; a future SCB
   owner must drive that control's pending/returned evidence instead of relying
   on this vacuous return.
5. Publish blocker diagnostics in base-data, store-snapshot, then SCB order.

`LoadReplayReturnReadiness` owns the return-pipe blocker after source return.
`LoadReplayLaunchReadiness` still owns the final launch arm.

## Timing

The source-return predicates are combinational in the same cycle as
selected-row base-data readiness. `LoadForwardPipeline` registers the split
SCB and STQ/store source bits into E3 through `e2ScbReturned` and
`e2StqReturned` when launch is eventually enabled.

## Flush/Recovery

The module has no flush input. Its parent drives `enable` and `launchValid`
from already flush-pruned replay-LIQ state.

## Deferred Owners

- Live raw STQ response source and selected-row identity inputs for the R393
  response-match owner.
- The R484 enabled early-STA blocker: local store-snapshot evidence for the
  selected `Wait` row. Parent launch readiness reports this as source-return
  false; this module's detailed blockers distinguish it from external SCB.
- External SCB replay response producer and pending/returned qualification.
- Return-pipe availability producer and arbitration behind
  `LoadReplayReturnReadiness`.
- Live replay launch, LHQ publication, ready-table wakeup, and memory trace
  rows.

## Verification

Focused gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadReplaySourceReturnReadiness
bash tools/chisel/run_chisel_tests.sh --only LoadReplaySourceReturnStoreSnapshotEvidence
bash tools/chisel/run_chisel_tests.sh --only LoadReplaySourceReturnStoreSnapshotReadyControl
bash tools/chisel/run_chisel_tests.sh --only LoadReplaySourceReturnScbLiveControl
bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop
FETCH_REDUCED_STORE_REPLAY_LIQ=1 BUILD_DIR=generated/r299-replay-liq-source-return-xcheck bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh
```

Reference tests cover the current no-external-SCB reduced path, base-data
blocking, local snapshot blocking, future pending-SCB blocking, disabled/no-row
diagnostics, and Chisel elaboration.
