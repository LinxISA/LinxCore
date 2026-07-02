# LoadLookupArbiter

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadLookupArbiter.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/LoadLookupArbiterSpec.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/lsu/load_unit/ldq.cpp`
    - `LDQInfo::handleStateQuery`
    - `LDQInfo::pickL1`
    - `LDQInfo::loadRepick`
- Related Chisel contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/execute/ReducedScalarAluExecute.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayBaseDataAlign.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/ReducedLoadReplayLiqAllocPath.scala`
- Contract IDs: `LC-CHISEL-LSU-LIQ-005`

## Purpose

`LoadLookupArbiter` is the shared sparse-memory lookup request selector for the
reduced execute load path and the opt-in replay-LIQ base-data path. Execute is
the priority owner because `ReducedScalarAluExecute` still consumes
`loadLookupData` in the same cycle it publishes `loadLookupValid`; replay must
not reinterpret execute-returned bytes as selected replay-row base data.

The module does not relaunch LIQ rows, mark SCB readiness, or publish load-hit
records. It only selects which lookup address/PC leaves the top-level sparse
memory port and reports whether replay was granted or blocked by execute.

## Interface

### Inputs

| Signal | Description |
|---|---|
| `executeValid` | Current execute-stage reduced load lookup request is valid. |
| `executeAddr` | Execute-stage byte address. |
| `executePc` | Execute-stage PC sideband. |
| `replayValid` | Selected replay-LIQ row has a non-cross-line base lookup request. |
| `replayAddr` | Selected replay-LIQ row byte address. |
| `replayPc` | Selected replay-LIQ row PC sideband. |

### Outputs

| Signal | Description |
|---|---|
| `lookupValid` | A selected source owns the shared sparse-memory lookup port. |
| `lookupAddr` | Selected source byte address; zero when idle. |
| `lookupPc` | Selected source PC sideband; zero when idle. |
| `executeGranted` | Execute selected the shared lookup port. |
| `replayGranted` | Replay selected the shared lookup port. |
| `replayBlockedByExecute` | Replay requested while execute held priority. |
| `idle` | No source requested the shared lookup port. |

## Logic Design

`LDQInfo::handleStateQuery` shows that new non-tile load requests first query
MDB, store, L1, and SCB source paths. `LDQInfo::pickL1` later selects only
resident `Wait` rows whose source data has already hit, and `loadRepick`
records that selected row for lookup/return. R296 preserves that staging in
the reduced Chisel top by adding an explicit lookup-request arbitration point
before any replay launch is enabled.

The arbiter is purely combinational:

1. If `executeValid` is high, grant execute and drive execute address/PC.
2. Else if `replayValid` is high, grant replay and drive replay address/PC.
3. Else drive an idle zeroed lookup.

The top uses `replayGranted` to gate replay-LIQ `e2BaseData`,
`e2BaseValidMask`, and `e2LoadDataReturned`. `replayValid` remains observable
as the raw selected-row base lookup request, while `replayGranted` is the proof
that `loadLookupData` belongs to that replay row. `launchEnable`,
`e2ScbReturned`, and `e2ReturnReady` remain inactive until a later packet owns
source-return arbitration and full replay relaunch.

## Verification

- `bash tools/chisel/run_chisel_tests.sh --only LoadLookupArbiter`
- `bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop`
- `FETCH_REDUCED_STORE_REPLAY_LIQ=1 BUILD_DIR=generated/r296-replay-liq-load-lookup-arb-xcheck bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh`
