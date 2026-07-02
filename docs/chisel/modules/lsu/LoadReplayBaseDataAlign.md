# LoadReplayBaseDataAlign

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayBaseDataAlign.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/LoadReplayBaseDataAlignSpec.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/lsu/load_unit/ldq.cpp`
    - `LDQInfo::handleStateQuery`
    - `LDQInfo::pickL1`
    - `LDQInfo::loadRepick`
- Related Chisel contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadLookupArbiter.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadInflightQueue.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadForwardPipeline.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadInflightLaunchSelect.scala`
- Contract IDs: `LC-CHISEL-LSU-LIQ-004`

## Purpose

`LoadReplayBaseDataAlign` converts a scalar 64-bit sparse-memory load response
into the 64-byte line shape consumed by `LoadForwardPipeline`. It is the
replay-LIQ companion to the reduced top's execute-time load lookup: the top can
name the selected replay row, request baseline memory bytes for that row, and
present line-aligned data plus a valid-byte mask to the dormant relaunch path.

This module does not decide whether replay may launch. It only shapes data once
the parent has a selected row and a scalar load response. R295 wires it into the
replay-LIQ top while keeping `launchEnable` low and
`e2ScbReturned/e2ReturnReady` false. R296 adds `LoadLookupArbiter` so the
parent can distinguish a raw replay base request from a replay-granted shared
sparse-memory response.

## Interface

### Inputs

| Signal | Description |
|---|---|
| `enable` | Enables replay-LIQ base lookup shaping. |
| `loadValid` | Selected replay row is a launch candidate. |
| `loadAddr` | Selected replay row byte address. |
| `loadSize` | Selected replay row scalar byte count. |
| `loadData` | Scalar 64-bit data returned by the parent memory lookup boundary. |

### Outputs

| Signal | Description |
|---|---|
| `requestValid` | `enable && loadValid`, nonzero size, and not crossing the 64-byte line. |
| `requestCrossesLine` | Selected scalar request crosses the modeled 64-byte line. Cross-line replay remains a later owner. |
| `requestByteMask` | 64-byte line mask for bytes requested by `loadAddr/loadSize`. |
| `lineData` | `loadData` bytes positioned at `loadAddr[5:0]` within a 64-byte line. |
| `lineValidMask` | Valid bytes supplied by `lineData`; currently identical to `requestByteMask`. |
| `dataReturned` | Source-return sideband for the baseline memory response; high only with `requestValid`. |

## Logic Design

`LDQInfo::handleStateQuery` sends scalar loads to the model L1/SCB/store-query
paths, and `LDQInfo::pickL1` only repicks rows once the row reports a data hit.
The Chisel replay-LIQ path therefore needs two separable boundaries before
launch is enabled:

1. `LoadInflightLaunchSelect` chooses the model-eligible row and publishes its
   PC, address, size, load ID, and request mask.
2. `LoadReplayBaseDataAlign` shapes the parent-provided scalar baseline data
   for that same row into the `LoadForwardPipeline` line-data contract.

The module rejects zero-size and cross-line requests by clearing
`requestValid`, `requestByteMask`, `lineValidMask`, `lineData`, and
`dataReturned`. For an in-line request, it places all eight scalar response
bytes at the selected line offset and uses `loadSize` to mark only the requested
bytes valid. Store forwarding later merges resident store bytes over this
baseline line data.

R295 does not use this as replacement evidence for live replay launch. R296
keeps replay launch disabled but gates the top-level LIQ `e2BaseData`,
`e2BaseValidMask`, and `e2LoadDataReturned` inputs with
`LoadLookupArbiter.replayGranted`, so execute-returned bytes cannot be
misinterpreted as replay base data when both paths request the shared lookup
port. Future launch enablement must still set SCB/return readiness from real
source-return ownership.

## Verification

- `bash tools/chisel/run_chisel_tests.sh --only LoadReplayBaseDataAlign`
- `bash tools/chisel/run_chisel_tests.sh --only LoadLookupArbiter`
- `bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop`
- `FETCH_REDUCED_STORE_REPLAY_LIQ=1 BUILD_DIR=generated/r295-replay-liq-base-align-xcheck bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh`
- `FETCH_REDUCED_STORE_REPLAY_LIQ=1 BUILD_DIR=generated/r296-replay-liq-load-lookup-arb-xcheck bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh`
