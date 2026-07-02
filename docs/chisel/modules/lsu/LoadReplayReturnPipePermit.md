# LoadReplayReturnPipePermit

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipePermit.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/LoadReplayReturnPipePermitSpec.scala`
- Integrated user: `rtl/LinxCore/chisel/src/main/scala/linxcore/top/LinxCoreFrontendFetchRfAluTraceTop.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/lsu/load_unit/ldq.cpp`
    - `LDQInfo::pickL1`
    - `LDQInfo::returnData`
- Related Chisel contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplaySourceReturnReadiness.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeBudget.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeSelect.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnReadiness.scala`
- Contract IDs: `LC-CHISEL-LSU-REPLAY-PIPE-002`

## Purpose

`LoadReplayReturnPipePermit` is the explicit producer boundary for the
return-pipe availability mask consumed by `LoadReplayReturnPipeSelect`. The
model `LDQInfo::pickL1` return loop computes an IEX pipe budget and requires
`lastPipeID < iexMaxPipe` before calling `returnData`. This module captures the
single-pipe reduced-top permit condition separately from pipe selection.

R303 feeds this module from `LoadReplayReturnPipeBudget`. The top still ties
the budget owner's live arm low, so this module produces an empty mask while
preserving the disabled live-relaunch boundary.

## Interface

### Inputs

| Signal | Description |
|---|---|
| `enable` | Replay-LIQ wrapper is active. |
| `launchValid` | A selected LIQ row is eligible for the launch path. |
| `sourcesReturned` | Base/load, store, and SCB source readiness has completed. |
| `pipeBudgetAvailable` | `LoadReplayReturnPipeBudget` says a future IEX return-pipe slot may be considered. Current reduced top ties that budget arm low. |

### Outputs

| Signal | Description |
|---|---|
| `candidateValid` | `enable && launchValid`. |
| `pipeAvailableMask` | Single-pipe availability mask. Bit zero is set only when the permit is valid. |
| `permitValid` | Candidate has returned sources and the future pipe-budget owner permits return. |
| `blockedByDisabled` | Launch row exists while the wrapper is disabled. |
| `blockedByNoCandidate` | Wrapper is enabled but no launch row is selected. |
| `blockedBySources` | Candidate exists but source-return completion is absent. |
| `blockedByPipeBudget` | Sources have returned but the future pipe budget is unavailable. |

## State

The module is combinational and owns no state.

## Logic Design

The reduced-top contract is intentionally narrower than the full model:

1. form `candidateValid` from `enable && launchValid`,
2. wait for source-return completion,
3. require `LoadReplayReturnPipeBudget` to assert `pipeBudgetAvailable`,
4. produce a one-bit pipe-zero mask for `LoadReplayReturnPipeSelect`, and
5. report pipe-budget blocking only after source completion.

This module does not choose among multiple pipes. It only produces the current
single-pipe permit mask. Multi-pipe `lastPipeID` carry and same `(BID, RID)`
grouping remain deferred to a later pipe-budget owner.

## Deferred Owners

- Real IEX return-pipe occupancy and budget counting behind `LoadReplayReturnPipeBudget`.
- Consumer wakeup/ready-table readiness behind the pipe budget.
- Multi-pipe mask generation and `(BID, RID)` grouping.
- Enabling live replay launch in generated RTL.

## Verification

Focused gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipePermit
bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop
FETCH_REDUCED_STORE_REPLAY_LIQ=1 BUILD_DIR=generated/r303-replay-liq-return-pipe-budget-xcheck bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh
```

Reference tests cover permit-valid mask generation, source blocking before
pipe-budget blocking, disabled/no-row diagnostics, and Chisel elaboration.
