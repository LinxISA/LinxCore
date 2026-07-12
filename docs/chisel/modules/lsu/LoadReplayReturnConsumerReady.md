# LoadReplayReturnConsumerReady

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnConsumerReady.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/LoadReplayReturnConsumerReadySpec.scala`
- Integrated user: `rtl/LinxCore/chisel/src/main/scala/linxcore/top/LinxCoreFrontendFetchRfAluTraceTop.scala`
- Packet baseline:
  - LinxCore: `bd33943af1fc93897faede47e7423709b93cf4ea`
  - LinxCoreModel: `793722e85c62eade9ab4e8481c9577dc5b9c98f7`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/lsu/load_unit/ldq.cpp`
    - `LDQInfo::receiveData`
    - `LDQInfo::returnData`
  - `model/LinxCoreModel/model/iex/iex.cpp`
    - `IEX::setMemWakeup`
- Related Chisel contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadInflightLaunchSelect.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/ReducedLoadReplayLiqAllocPath.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeBudget.scala`
- Contract IDs: `LC-CHISEL-LSU-REPLAY-RTN-002`

## Purpose

`LoadReplayReturnConsumerReady` names the downstream consumer side of a replay
return before the top enables live LIQ relaunch. The model `LDQInfo::returnData`
always writes the returned load onto `lsuIexLretArray[iexIdx]` for ordinary
non-tile returns. It then calls `IEX::setMemWakeup` only when the returned
request is not `specWakeup` and not `stack_vld`.

This module keeps that split explicit:

- the LRET sink is required for every selected replay return;
- the mem-wakeup sink is required only for selected rows that are neither
  speculative-wakeup rows nor stack-valid rows;
- R661 feeds LRET readiness from STID-local
  `ScalarLSULoadReturnQueueBank.preEnqueueReady`, before return-pipe choice;
- R379 feeds the conditional mem-wakeup sink from
  `LoadReplayReturnWakeupSinkReady`, whose abstract capacity can arm but whose
  live output is enabled only when the integrated W2 wakeup owner can accept
  the matching destination.

## Interface

### Inputs

| Signal | Description |
|---|---|
| `enable` | Replay-LIQ wrapper is active. |
| `launchValid` | A resident LIQ row is selected for launch consideration. |
| `sourcesReturned` | Base/load, store, and SCB source readiness has completed. Used for ordered blocker reporting. |
| `specWakeup` | Selected row has already produced the model speculative load wakeup. |
| `stackValid` | Selected row is a stack-valid load. |
| `lretSinkReady` | The selected STID has pre-admission load-return queue credit. Final selected-pipe acceptance is checked at publication. |
| `wakeupSinkReady` | The IEX memory-wakeup path can accept the dependent wakeup when one is required. |

### Outputs

| Signal | Description |
|---|---|
| `candidateValid` | `enable && launchValid`. |
| `wakeupRequired` | Selected-row mem wakeup is required: `candidateValid && !specWakeup && !stackValid`. |
| `consumerReady` | Downstream return consumer can accept this replay result. Requires the LRET sink and, when `wakeupRequired`, the mem-wakeup sink. |
| `blockedByDisabled` | A selected row exists while replay-LIQ mode is disabled. |
| `blockedByNoCandidate` | Replay-LIQ mode is enabled but no row is selected. |
| `blockedBySources` | Candidate exists but source-return completion is absent. |
| `blockedByLretSink` | Sources have returned but the always-required LRET sink is not ready. |
| `blockedByWakeupSink` | Sources and LRET are ready, the row requires mem wakeup, and the mem-wakeup sink is not ready. |

## State

The module is combinational and owns no state.

## Logic Design

The model path has two different consumers after data is complete:

1. `LDQInfo::receiveData` selects a replay row only after data/source return
   predicates are satisfied and an IEX return-pipe index is legal.
2. `LDQInfo::returnData` writes the result to `lsuIexLretArray[iexIdx]`.
3. For non-`specWakeup` and non-stack rows, `returnData` also calls
   `IEX::setMemWakeup`.
4. `IEX::setMemWakeup` itself ignores invalid or stack-valid requests.

The owner reports whether the live return publication may consume those sinks:

```text
wakeupRequired = enable && launchValid && !specWakeup && !stackValid
consumerReady = enable && lretSinkReady && (!wakeupRequired || wakeupSinkReady)
```

Blocker outputs are ordered for debug. Source-return blocking is reported
before sink blocking. LRET sink blocking is reported before mem-wakeup sink
blocking because model data return cannot skip the load-return queue.

## Deferred Owners

- Multiple live W1/W2 return pipes beyond the current shared scalar pipe.
- Tile-transfer return data and cross-line return splitting.

## Verification

Focused gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnConsumerReady
bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop
FETCH_REDUCED_STORE_REPLAY_LIQ=1 BUILD_DIR=generated/r305-replay-liq-return-consumer-ready-xcheck bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh
```

Reference tests cover normal replay returns requiring both sinks, speculative
and stack rows requiring only LRET, LRET-before-wakeup blocker ordering,
source-return ordering, empty/disabled candidate diagnostics, and Chisel
elaboration.
