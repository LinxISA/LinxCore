# LoadReplayReturnPipeSelect

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeSelect.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/LoadReplayReturnPipeSelectSpec.scala`
- Integrated user: `rtl/LinxCore/chisel/src/main/scala/linxcore/top/LinxCoreFrontendFetchRfAluTraceTop.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/lsu/load_unit/ldq.cpp`
    - `LDQInfo::pickL1`
    - `LDQInfo::returnData`
- Related Chisel contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplaySourceReturnReadiness.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipePermit.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnReadiness.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/ReducedLoadReplayLiqAllocPath.scala`
- Contract IDs: `LC-CHISEL-LSU-REPLAY-PIPE-001`

## Purpose

`LoadReplayReturnPipeSelect` owns the reduced replay-LIQ boundary between
source-return completion and IEX load-return pipe selection. The LinxCoreModel
`LDQInfo::pickL1` return loop computes `lastPipeID`, checks
`lastPipeID < iexMaxPipe`, and only then calls `returnData`. This module
captures that pipe-availability/selection decision as a local combinational
owner before a later packet connects it to a real IEX return-pipe producer.

R302 feeds this module from `LoadReplayReturnPipePermit` and forwards its result
into `LoadReplayReturnReadiness`. The reduced top still drives the permit
module's `pipeBudgetAvailable` input low, so launch remains disabled and this
packet does not relaunch loads, publish LHQ rows, or wake consumers.

## Interface

### Inputs

| Signal | Description |
|---|---|
| `enable` | Replay-LIQ wrapper is active. |
| `launchValid` | A selected LIQ row is eligible for the launch path. |
| `sourcesReturned` | Source-return readiness has completed the base/store/SCB predicate. |
| `pipeAvailableMask` | One bit per future IEX load-return pipe from `LoadReplayReturnPipePermit`. Current reduced top drives the permit's pipe budget low. |

### Outputs

| Signal | Description |
|---|---|
| `candidateValid` | `enable && launchValid`. |
| `pipeAvailable` | Candidate has returned sources and at least one pipe is available. |
| `selectedPipeIndex` | Lowest available pipe index when `pipeAvailable` is true, otherwise zero. |
| `blockedByDisabled` | Launch row exists while the wrapper is disabled. |
| `blockedByNoCandidate` | Wrapper is enabled but no launch row is selected. |
| `blockedBySources` | Candidate exists but source-return completion is absent. |
| `blockedByNoPipe` | Sources have returned but the pipe mask is empty. |

## State

The module is combinational and owns no state.

## Logic Design

The model return loop:

1. forms a replay candidate set from `LDQ_REPICK` entries,
2. assigns `lastPipeID` from the running IEX load-return pipe index,
3. advances the pipe index when the next returned row belongs to a different
   `(BID, RID)` group,
4. requires `(ldqRnt || l1Rnt) && scbRnt && stqRnt`,
5. requires `lastPipeID < iexMaxPipe`, and
6. calls `returnData(memReq, lastPipeID)`.

`LoadReplayReturnPipeSelect` owns the reduced single-row version of item 5:

1. form `candidateValid` from `enable && launchValid`,
2. wait for `sourcesReturned`,
3. test whether any pipe bit is present in the permit-produced `pipeAvailableMask`,
4. select the lowest available pipe index, and
5. expose ordered blockers so no-pipe blocking appears only after source
   completion.

The current reduced top has only one selected replay row per cycle and no real
IEX return-pipe budget producer. Multi-row same-ROB grouping and pipe-index
carry remain deferred.

## Deferred Owners

- Real IEX return-pipe budget producer behind `LoadReplayReturnPipePermit`.
- Multi-pipe `lastPipeID` carry and same `(BID, RID)` grouping.
- Return-pipe arbitration across scalar/vector load returns.
- Consumer wakeup and ready-table publication after replay return.

## Verification

Focused gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeSelect
bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop
FETCH_REDUCED_STORE_REPLAY_LIQ=1 BUILD_DIR=generated/r302-replay-liq-return-pipe-permit-xcheck bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh
```

Reference tests cover lowest-pipe selection, source blocking before pipe
blocking, no-pipe blocking after source completion, disabled/no-row
diagnostics, and Chisel elaboration.
