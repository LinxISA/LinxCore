# LoadReplayLaunchReadiness

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayLaunchReadiness.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/LoadReplayLaunchReadinessSpec.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/lsu/load_unit/ldq.cpp`
    - `LDQInfo::pickL1`
    - `LDQInfo::handleL1Receive`
    - `LDQInfo::handleSCBReceive`
    - `LDQInfo::handleSTQReceive`
    - `LDQInfo::returnData`
- Related Chisel contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadLookupArbiter.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayBaseDataAlign.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnConsumerReady.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeBudget.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipePermit.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeSelect.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnReadiness.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/ReducedLoadReplayLiqAllocPath.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadForwardPipeline.scala`
- Contract IDs: `LC-CHISEL-LSU-LIQ-006`

## Purpose

`LoadReplayLaunchReadiness` is the parent-level replay-LIQ launch arm gate. It
combines the selected LIQ launch-row predicate with the source-return and
return-slot predicates required before the top may drive
`ReducedLoadReplayLiqAllocPath.launchEnable`.

R297 factors this predicate out of the top without turning live replay launch
on. R299 drives the SCB/source-return input from
`LoadReplaySourceReturnReadiness`. R300 drives `returnReady` from
`LoadReplayReturnReadiness`. R301 inserts `LoadReplayReturnPipeSelect` ahead of
that readiness gate. R302 adds `LoadReplayReturnPipePermit` as the producer of
that selector's mask. R304 drives `LoadReplayReturnPipeBudget` from a separate
budget arm and consumer-ready input. R305 drives that consumer-ready input from
`LoadReplayReturnConsumerReady`, keeping the model LRET sink separate from the
conditional mem-wakeup sink. The current top arms the budget under the opt-in
wrapper but keeps both sinks low. The module therefore exposes why launch
remains blocked while keeping LIQ row state unchanged.

## Interface

### Inputs

| Signal | Description |
|---|---|
| `enable` | Replay-LIQ wrapper is active. |
| `launchValid` | `LoadInflightLaunchSelect` has a selected replay candidate. |
| `baseLookupGranted` | `LoadLookupArbiter` granted the selected replay row on the shared sparse-memory lookup port. |
| `baseDataReturned` | `LoadReplayBaseDataAlign` has in-line baseline load data for the selected row. |
| `scbReturned` | Source-return owner has observed the model `scbRnt/stqRnt` equivalent for the reduced replay path. |
| `returnReady` | `LoadReplayReturnReadiness` has observed source completion plus a selected load return pipe from `LoadReplayReturnPipeSelect`. Current top leaves this low because upstream LRET/mem-wakeup sinks are not ready. |

### Outputs

| Signal | Description |
|---|---|
| `candidateValid` | Replay-LIQ mode is enabled and the selector has a row. |
| `baseDataReady` | Replay owned the lookup response and baseline data has returned. |
| `sourcesReturned` | Baseline data and SCB/source sideband have both returned. |
| `launchReady` | All launch predicates are true. |
| `launchEnable` | Alias of `launchReady`, wired to `ReducedLoadReplayLiqAllocPath.launchEnable`. |
| `blockedByDisabled` | A selected row exists while replay-LIQ mode is disabled. |
| `blockedByNoCandidate` | Replay-LIQ mode is enabled but no selected row exists. |
| `blockedByBaseLookup` | The selected row did not own the shared lookup port. |
| `blockedByBaseData` | The selected row owned lookup but did not receive baseline data. |
| `blockedByScb` | Baseline data is ready but the SCB/source-return sideband is absent. |
| `blockedByReturn` | All sources returned but no return/wakeup pipe is available. |

## Logic Design

The model return loop in `LDQInfo::pickL1` and `returnData` only returns a
repicked load after these conditions are true:

1. the row was selected as a scalar `LDQ_REPICK` candidate,
2. requested bytes are present through LDQ/L1 data or merged store/SCB bytes,
3. the SCB path has returned,
4. the store path is not blocking on not-ready data,
5. an IEX load-return pipe is available.

The current Chisel split maps those conditions across modules:

1. `LoadInflightLaunchSelect` proves the row is a scalar data-hit candidate.
2. `LoadLookupArbiter` proves the replay row, not execute, owned the shared
   sparse-memory lookup response.
3. `LoadReplayBaseDataAlign` converts the scalar response into the
   `LoadForwardPipeline` line-data shape.
4. `LoadStoreForwarding` inside `LoadForwardPipeline` checks resident STQ data
   and wait-store blocking once launch is allowed.
5. `LoadReplaySourceReturnReadiness` separates local resident-store snapshot
   readiness from future external SCB response readiness.
6. `LoadReplayReturnConsumerReady` separates the always-required LRET sink from
   the conditional mem-wakeup sink and feeds the consumer-ready predicate.
7. `LoadReplayReturnPipeBudget` exposes the future IEX return-pipe budget arm
   and downstream consumer-readiness blocker.
8. `LoadReplayReturnPipePermit` maps that budget predicate into a
   single-pipe mask.
9. `LoadReplayReturnPipeSelect` maps the future IEX return-pipe mask into a
   selected pipe index for the current row.
10. `LoadReplayReturnReadiness` turns source return plus IEX return-pipe
   availability into the final return-ready predicate.
11. `LoadReplayLaunchReadiness` gates the parent launch arm on base-data
   readiness, source return, and return readiness.

The blocker outputs are not mutually exclusive across independent conditions,
but `blockedByBaseData`, `blockedByScb`, and `blockedByReturn` are ordered so a
later condition is only reported after the earlier source conditions are met.

## Deferred Owners

- External SCB response producer for replay rows.
- Real LRET sink, mem-wakeup sink, and ready-table publication behind
  `LoadReplayReturnConsumerReady`.
- Cross-line replay base-data ownership.
- Multiple load-return pipe arbitration and age grouping.

## Verification

- `bash tools/chisel/run_chisel_tests.sh --only LoadReplayLaunchReadiness`
- `bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop`
- `FETCH_REDUCED_STORE_REPLAY_LIQ=1 BUILD_DIR=generated/r305-replay-liq-return-consumer-ready-xcheck bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh`
