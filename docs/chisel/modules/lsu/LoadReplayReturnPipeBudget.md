# LoadReplayReturnPipeBudget

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeBudget.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/LoadReplayReturnPipeBudgetSpec.scala`
- Integrated user: `rtl/LinxCore/chisel/src/main/scala/linxcore/top/LinxCoreFrontendFetchRfAluTraceTop.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/lsu/load_unit/ldq.cpp`
    - `LDQInfo::pickL1`
    - `LDQInfo::returnData`
- Related Chisel contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplaySourceReturnReadiness.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipePermit.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeSelect.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnReadiness.scala`
- Contract IDs: `LC-CHISEL-LSU-REPLAY-PIPE-003`

## Purpose

`LoadReplayReturnPipeBudget` is the explicit reduced replay-LIQ boundary for
the future IEX load-return pipe budget. The LinxCoreModel `LDQInfo::pickL1`
loop computes `iexMaxPipe`, derives `lastPipeID`, and requires
`lastPipeID < iexMaxPipe` before `returnData(memReq, lastPipeID)` can publish
the load result. This module isolates the budget availability predicate from
source-return readiness and from the mask producer.

R303 wires this module into the opt-in replay-LIQ top. The current top still
ties `pipeBudgetEnable` low, so launch remains disabled while the budget owner
and blockers are visible as diagnostics.

## Interface

### Inputs

| Signal | Description |
|---|---|
| `enable` | Replay-LIQ wrapper is active. |
| `launchValid` | A resident LIQ row is selected for the launch path. |
| `sourcesReturned` | Source-return readiness has completed. Used for ordered blocker reporting. |
| `pipeBudgetEnable` | Future live return-pipe budget arm. R303 ties this low in the reduced top. |

### Outputs

| Signal | Description |
|---|---|
| `candidateValid` | `enable && launchValid`. |
| `pipeBudgetAvailable` | The budget owner says a return-pipe slot may be considered. In R303 this is `enable && pipeBudgetEnable`. |
| `blockedByDisabled` | Launch row exists while the wrapper is disabled. |
| `blockedByNoCandidate` | Wrapper is enabled but no launch row is selected. |
| `blockedBySources` | Candidate exists but source-return completion is absent. |
| `blockedByBudgetDisabled` | Sources have returned but the live return-pipe budget arm is still disabled. |

## State

The module is combinational and owns no state.

## Logic Design

The reduced-top boundary is intentionally conservative:

1. expose the replay-LIQ candidate predicate,
2. keep the future live pipe-budget arm as an explicit input,
3. report source-return blocking before budget blocking,
4. report budget-disabled blocking only after sources have returned, and
5. feed `pipeBudgetAvailable` to `LoadReplayReturnPipePermit`.

`LoadReplayReturnPipePermit` remains the owner that turns this budget predicate
into the one-bit pipe availability mask consumed by
`LoadReplayReturnPipeSelect`. Multi-pipe `lastPipeID` grouping, live IEX
pipe occupancy, and consumer wakeup remain later owners.

## Deferred Owners

- Real IEX return-pipe occupancy and budget counting.
- Multi-pipe `lastPipeID` carry and same `(BID, RID)` grouping.
- Consumer wakeup/ready-table publication.
- Enabling live replay launch in generated RTL.

## Verification

Focused gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeBudget
bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop
FETCH_REDUCED_STORE_REPLAY_LIQ=1 BUILD_DIR=generated/r303-replay-liq-return-pipe-budget-xcheck bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh
```

Reference tests cover budget availability, disabled wrapper gating,
source-return blocker ordering, budget-disabled blocker ordering, empty
candidate reporting, and Chisel elaboration.
