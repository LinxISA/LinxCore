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

`LoadReplayReturnLretPayload` formats the retained scalar terminal result that
becomes publishable once a selected LIQ attempt has exactly one outcome:
return data or a terminal fault. In LinxCoreModel, `LDQInfo::returnData` sets
`data_vld`, writes the updated bus into `lsuIexLretArray[iexIdx]`, then calls
`IEX::setMemWakeup` only when the row is neither `specWakeup` nor `stack_vld`.

This combinational formatter does not own queue state or wake dependents. The
canonical `ScalarLSULoadPath` and the compatibility workload bridge consume its
output at LRET admission. It preserves:

- BID/GID/RID, full load LSID, canonical LIQ slot/generation lease, and exact
  load-attempt identity,
- PC, address, size, scalar return data,
- terminal fault validity/cause, with data forced to zero for a fault result,
- one renamed destination sideband,
- selected return-pipe index,
- `specWakeup`/`stackValid` and derived wakeup-required predicate.

R311 carries the reduced one-destination payload from the load uop captured by
`ReducedLoadWaitReplaySlot`, through the replay FIFO and LIQ row, and into this
formatter. Later packets connected the formatter to the retained LRET queue;
production IEX terminal metadata and final atomic side effects remain separate
owners.

R376 carries the RF-derived `CommitOperandTrace` pair from the selected
replay-LIQ launch row into this LRET payload. The sideband is copied only when
`payloadValid` is true, so blocked or invalid return-data cycles expose a
disabled source trace instead of stale launch operands.
R540 adds harness-only generated-RTL sideband counters for
`lret_payload_candidate_valid`, `lret_payload_valid`,
`lret_payload_wakeup_required`, `lret_payload_blocked_by_no_candidate`, and
`lret_payload_blocked_by_data`. These counters decide whether the empty LRET
FIFO seen in R539 is caused by this payload formatter never seeing a selected
return candidate or by selected rows lacking complete scalar return data.
The R540 replay-loop probe observed `lret_payload_candidate_valid=4`,
`lret_payload_valid=0`, `lret_payload_blocked_by_data=4`, and
`lret_payload_blocked_by_no_candidate=105`, proving the formatter sees selected
return candidates but has no complete scalar return data to publish.

## Interface

### Inputs

| Signal | Description |
|---|---|
| `enable` | Replay-LIQ wrapper is active. |
| `launchValid` | A resident LIQ row is selected for return consideration. |
| `dataValid` | `LoadReplayReturnDataExtract` has a complete scalar return value. |
| `selectedBid`/`selectedGid`/`selectedRid` | Selected row identity. |
| `selectedLoadLsId` | Selected row load sequence identity. |
| `selectedLoadId` | Canonical LIQ slot plus generation lease for the selected resident row. |
| `selectedAttempt` | Exact producer and replay-attempt generation bound to this load. |
| `selectedPc`/`selectedAddr`/`selectedSize` | Request PC, address, and size. |
| `selectedDst` | Selected row destination sideband captured from the renamed load uop. |
| `selectedSourceTraceValid` / `selectedSource0` / `selectedSource1` | R376 source operand trace sideband captured at reduced RF load execution and preserved through the replay-LIQ row. |
| `returnData` | Sign/zero-extended scalar return data. |
| `faultValid` / `faultCause` | Terminal fault outcome and cause. Exactly one of `dataValid` and `faultValid` must be asserted for publication. |
| `returnPipeIndex` | Selected future IEX return-pipe index. |
| `specWakeup` | Model row suppresses regular dependent wakeup. |
| `stackValid` | Model row suppresses regular dependent wakeup. |

### Outputs

| Signal | Description |
|---|---|
| `candidateValid` | `enable && launchValid`. |
| `payloadValid` | Candidate has exactly one terminal outcome: data XOR fault. |
| `payload*` | Selected identity, request, destination, data, pipe, and sideband fields, zeroed when invalid. |
| `payloadLoadId` / `payloadAttempt` | Canonical row lease and exact attempt retained for stale-return rejection after LIQ reuse. |
| `payloadFaultValid` / `payloadFaultCause` | Fault result retained to terminal IEX publication; payload data is zero on fault. |
| `payloadSourceTraceValid` / `payloadSource0` / `payloadSource1` | R376 source operand trace sideband, copied from the selected replay-LIQ row only while `payloadValid` is true. |
| `wakeupRequired` | `payloadValid && !specWakeup && !stackValid`. |
| `blockedByDisabled` | A selected row exists while replay-LIQ mode is disabled. |
| `blockedByNoCandidate` | Replay-LIQ mode is enabled but no row is selected. |
| `blockedByData` | Candidate exists but scalar return data is not valid. |
| `malformedOutcome` | Candidate has neither outcome or asserts data and fault together. |

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
outcomeExact = dataValid XOR faultValid
payloadValid = candidateValid && outcomeExact
wakeupRequired = payloadValid && !specWakeup && !stackValid
```

The payload fields are forwarded only while `payloadValid` is true. This avoids
stale identity/data diagnostics when the selected row is absent or data
extraction is blocked. R376 applies the same guard to source-trace fields.

## Deferred Owners

- Production OOO/IEX terminal sidecar allocation and exact terminal-fire
  retirement for every execution cluster.
- Full mem-wakeup publication and ready-table/issue wakeup fanout.
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
stack wakeup suppression, exact attempt/row-lease retention, data-or-fault
exclusivity, destination forwarding, stale-field suppression, and Chisel
elaboration. The current integration repair also runs
`LoadAttemptBinding`, `ScalarLSULoadPath`, the 44-case live-top suite, the
15-case autonomous-top suite, and the bounded generated-RTL xcheck.
