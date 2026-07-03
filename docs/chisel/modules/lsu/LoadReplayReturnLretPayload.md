# LoadReplayReturnLretPayload

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnLretPayload.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/LoadReplayReturnLretPayloadSpec.scala`
- Integrated user: `rtl/LinxCore/chisel/src/main/scala/linxcore/top/LinxCoreFrontendFetchRfAluTraceTop.scala`
- Packet baseline:
  - LinxCore: `a496d8933ea3840d0f5303e685befd6796c47562`
  - LinxCoreModel: `793722e85c62eade9ab4e8481c9577dc5b9c98f7`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/ModelCommon/bus/MemReqBus.h`
    - `MemReqBus`
  - `model/LinxCoreModel/model/lsu/load_unit/ldq.cpp`
    - `LDQInfo::returnData`
    - `LDQInfo::sendCrossRtn`
  - `model/LinxCoreModel/model/iex/iex.cpp`
    - `IEX::setMemWakeup`
- Related Chisel contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnDataExtract.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPublishReady.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnConsumerReady.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnWritebackCandidate.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnWakeupCandidate.scala`
- Contract IDs: `LC-CHISEL-LSU-REPLAY-LRET-001`, `LC-CHISEL-LSU-REPLAY-DST-001`

## Purpose

`LoadReplayReturnLretPayload` formats the diagnostic subset of the model
`MemReqBus` that becomes observable once a selected replay-LIQ row has valid
scalar return data. In LinxCoreModel, `LDQInfo::returnData` sets
`data_vld`, writes the updated bus into `lsuIexLretArray[iexIdx]`, then calls
`IEX::setMemWakeup` only when the row is neither `specWakeup` nor `stack_vld`.

This module does not enqueue LRET and does not wake dependents. It preserves
the known request identity and data fields so the future enqueue owner has a
named boundary:

- BID/GID/RID and load LSID,
- PC, address, size, scalar return data,
- one renamed destination sideband,
- selected return-pipe index,
- `specWakeup`/`stackValid` and derived wakeup-required predicate.

R311 carries the reduced one-destination payload from the load uop captured by
`ReducedLoadWaitReplaySlot`, through the replay FIFO and LIQ row, and into this
diagnostic formatter. The real IEX LRET enqueue and mem-wakeup fanout remain
deferred.

## Interface

### Inputs

| Signal | Description |
|---|---|
| `enable` | Replay-LIQ wrapper is active. |
| `launchValid` | A resident LIQ row is selected for return consideration. |
| `dataValid` | `LoadReplayReturnDataExtract` has a complete scalar return value. |
| `selectedBid`/`selectedGid`/`selectedRid` | Selected row identity. |
| `selectedLoadLsId` | Selected row load sequence identity. |
| `selectedPc`/`selectedAddr`/`selectedSize` | Request PC, address, and size. |
| `selectedDst` | Selected row destination sideband captured from the renamed load uop. |
| `returnData` | Sign/zero-extended scalar return data. |
| `returnPipeIndex` | Selected future IEX return-pipe index. |
| `specWakeup` | Model row suppresses regular dependent wakeup. |
| `stackValid` | Model row suppresses regular dependent wakeup. |

### Outputs

| Signal | Description |
|---|---|
| `candidateValid` | `enable && launchValid`. |
| `payloadValid` | Candidate has valid extracted data. Diagnostic-only in R311. |
| `payload*` | Selected identity, request, destination, data, pipe, and sideband fields, zeroed when invalid. |
| `wakeupRequired` | `payloadValid && !specWakeup && !stackValid`. |
| `blockedByDisabled` | A selected row exists while replay-LIQ mode is disabled. |
| `blockedByNoCandidate` | Replay-LIQ mode is enabled but no row is selected. |
| `blockedByData` | Candidate exists but scalar return data is not valid. |

## State

The module is combinational and owns no state.

## Logic Design

The model updates the same `MemReqBus` before LRET publication:

```text
bus.data_vld = true
bus.data = ExtractData(...)
bus.data = SignExtend(...)
lsuIexLretArray[iexIdx]->Write(bus)
if (!bus.specWakeup && !bus.stack_vld) IEX::setMemWakeup(bus)
```

R310 mirrors only the known scalar subset:

```text
candidateValid = enable && launchValid
payloadValid = candidateValid && dataValid
wakeupRequired = payloadValid && !specWakeup && !stackValid
```

The payload fields are forwarded only while `payloadValid` is true. This avoids
stale identity/data diagnostics when the selected row is absent or data
extraction is blocked.

## Deferred Owners

- Real IEX LRET queue entry type and enqueue.
- Real mem-wakeup publication and ready-table/issue wakeup fanout.
- Real ready-table/issue wakeup mutation; R313 exposes only a diagnostic
  wakeup candidate.
- Real RF writeback arbitration; R312 exposes only a diagnostic GPR writeback
  candidate.
- Multi-destination load-pair/vector/tile payloads.
- Cross-line merged payload publication.
- Backpressure from LRET and wakeup queues into replay launch.

## Verification

Focused gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnLretPayload
bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop
FETCH_REDUCED_STORE_REPLAY_LIQ=1 BUILD_DIR=generated/r311-replay-destination-sideband-xcheck bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh
```

Reference tests cover selected-row identity/data forwarding, speculative and
stack wakeup suppression, invalid-data blocking, destination forwarding,
stale-field suppression, and Chisel elaboration.
