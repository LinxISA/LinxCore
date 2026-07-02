# LoadReplayReturnReadiness

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnReadiness.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/LoadReplayReturnReadinessSpec.scala`
- Integrated user: `rtl/LinxCore/chisel/src/main/scala/linxcore/top/LinxCoreFrontendFetchRfAluTraceTop.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/lsu/load_unit/ldq.cpp`
    - `LDQInfo::pickL1`
    - `LDQInfo::returnData`
- Related Chisel contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplaySourceReturnReadiness.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayLaunchReadiness.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/ReducedLoadReplayLiqAllocPath.scala`
- Contract IDs: `LC-CHISEL-LSU-REPLAY-RTN-001`

## Purpose

`LoadReplayReturnReadiness` is the IEX load-return pipe readiness boundary for
the reduced replay-LIQ launch path. It sits after source-return readiness and
before the parent launch arm, matching the model rule that a repicked load may
call `returnData` only when all sources have returned and a load-return pipe is
available.

R300 keeps the boundary conservative. The opt-in replay-LIQ top instantiates
the module and forwards its result into `LoadReplayLaunchReadiness.returnReady`
and `ReducedLoadReplayLiqAllocPath.e2ReturnReady`, but still ties
`returnPipeAvailable` low. This exposes the missing IEX return-pipe owner
without enabling live relaunch, LHQ publication, or wakeup.

## Interface

### Inputs

| Signal | Description |
|---|---|
| `enable` | Replay-LIQ wrapper is active. |
| `launchValid` | A selected LIQ row is eligible for the launch path. |
| `sourcesReturned` | Source-return owner has completed the base/store/SCB source predicate. |
| `returnPipeAvailable` | Future IEX load-return pipe owner has an available pipe for this row. Current reduced top ties this low. |
| `returnPipeIndex` | Future selected return-pipe index. Current reduced top drives zero. |

### Outputs

| Signal | Description |
|---|---|
| `candidateValid` | `enable && launchValid`. |
| `returnReady` | Candidate has returned sources and an available IEX return pipe. |
| `selectedPipeIndex` | Selected return-pipe index when `returnReady` is true, otherwise zero. |
| `blockedByDisabled` | Launch row exists while the wrapper is disabled. |
| `blockedByNoCandidate` | Wrapper is enabled but no launch row is selected. |
| `blockedBySources` | Candidate is waiting for source-return completion. |
| `blockedByReturnPipe` | Sources have returned but no IEX return pipe is available. |

## State

The module is combinational and owns no state.

## Logic Design

The LinxCoreModel return loop computes an IEX return-pipe budget, assigns
`lastPipeID` to the selected row, and only calls `returnData` when:

1. the row is selected for replay,
2. the requested data bytes are complete,
3. `(ldqRnt || l1Rnt)`, `scbRnt`, and `stqRnt` have returned, and
4. `lastPipeID < iexMaxPipe`.

`LoadReplayReturnReadiness` owns item 4 in the Chisel split:

1. Form `candidateValid` from `enable && launchValid`.
2. Wait for `sourcesReturned` from `LoadReplaySourceReturnReadiness`.
3. Require `returnPipeAvailable` before asserting `returnReady`.
4. Publish the selected pipe index only when `returnReady` is true.
5. Order blocker diagnostics so return-pipe blocking is visible only after
   source-return completion.

`LoadReplayLaunchReadiness` remains the final parent launch arm. It consumes
`returnReady` from this module; it does not own pipe selection.

## Deferred Owners

- IEX return-pipe availability producer.
- Multi-pipe arbitration and model `lastPipeID` grouping for same `(BID, RID)`
  return rows.
- Consumer wakeup/ready-table publication after replay return.
- Cross-line replay return pipe behavior.

## Verification

Focused gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnReadiness
bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop
FETCH_REDUCED_STORE_REPLAY_LIQ=1 BUILD_DIR=generated/r300-replay-liq-return-readiness-xcheck bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh
```

Reference tests cover ready, source blocking before pipe blocking, return-pipe
blocking after source completion, disabled/no-row diagnostics, and Chisel
elaboration.
