# LoadReplayReturnPublishReady

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPublishReady.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/LoadReplayReturnPublishReadySpec.scala`
- Integrated user: `rtl/LinxCore/chisel/src/main/scala/linxcore/top/LinxCoreFrontendFetchRfAluTraceTop.scala`
- Packet baseline:
  - LinxCore: `00697f670488cf259c7cda71c3bd0309749e5983`
  - LinxCoreModel: `793722e85c62eade9ab4e8481c9577dc5b9c98f7`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/lsu/load_unit/ldq.cpp`
    - `LDQInfo::returnData`
  - `model/LinxCoreModel/model/iex/iex.cpp`
    - `IEX::setMemWakeup`
- Related Chisel contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnDataExtract.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnConsumerReady.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeBudget.scala`
- Contract IDs: `LC-CHISEL-LSU-REPLAY-RTN-004`

## Purpose

`LoadReplayReturnPublishReady` is the diagnostic boundary between extracted
replay return data and the downstream return consumers. The model
`LDQInfo::returnData` extracts and sign/zero extends the scalar result, writes
the result to `lsuIexLretArray[iexIdx]`, and then conditionally calls
`IEX::setMemWakeup` for rows that are neither `specWakeup` nor `stack_vld`.

This module does not publish data. It names the final all-clear predicate that
a future LRET enqueue owner will need:

- a replay return candidate is selected,
- `LoadReplayReturnDataExtract` has a valid scalar data result,
- `LoadReplayReturnConsumerReady` says the LRET and optional mem-wakeup sinks
  can accept the result.

R309 wires this module into the opt-in replay-LIQ top as diagnostics only.
`publishReady` is not fed into `launchEnable`, LRET enqueue, or mem wakeup.

## Interface

### Inputs

| Signal | Description |
|---|---|
| `enable` | Replay-LIQ wrapper is active. |
| `launchValid` | A resident LIQ row is selected for return publication consideration. |
| `dataValid` | `LoadReplayReturnDataExtract` has complete scalar data for this row. |
| `consumerReady` | `LoadReplayReturnConsumerReady` reports that downstream sinks can accept this row. |

### Outputs

| Signal | Description |
|---|---|
| `candidateValid` | `enable && launchValid`. |
| `dataReady` | Candidate is valid and extracted data is valid. |
| `publishReady` | Candidate, data, and consumers are all ready. Diagnostic-only in R309. |
| `blockedByDisabled` | A selected row exists while replay-LIQ mode is disabled. |
| `blockedByNoCandidate` | Replay-LIQ mode is enabled but no row is selected. |
| `blockedByData` | Candidate exists but extracted scalar data is not valid. |
| `blockedByConsumer` | Candidate data is ready but the LRET or mem-wakeup consumer is not ready. |

## State

The module is combinational and owns no state.

## Logic Design

The model publishes a replay return only after data is complete and consumers
can be updated:

```text
candidateValid = enable && launchValid
dataReady = candidateValid && dataValid
publishReady = dataReady && consumerReady
```

Blocker reporting follows the model order: disabled/no-candidate shape first,
then data extraction completion, then downstream consumer readiness. This keeps
the data-transform owner separate from the LRET/wakeup sink owner while making
their join point observable at the top boundary.

## Deferred Owners

- Real IEX LRET payload formatting and enqueue.
- Real mem-wakeup payload publication and ready-table/issue wakeup fanout.
- Backpressure from real LRET and wakeup queues into replay launch.
- Cross-line return merge publication.

## Verification

Focused gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPublishReady
bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop
FETCH_REDUCED_STORE_REPLAY_LIQ=1 BUILD_DIR=generated/r309-replay-return-publish-ready-xcheck bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh
```

Reference tests cover publish-ready assertion, data-before-consumer blocker
ordering, empty/disabled candidate diagnostics, and Chisel elaboration.
