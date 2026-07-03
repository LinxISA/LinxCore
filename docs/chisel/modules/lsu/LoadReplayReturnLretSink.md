# LoadReplayReturnLretSink

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnLretSink.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/LoadReplayReturnLretSinkSpec.scala`
- Integrated user: `rtl/LinxCore/chisel/src/main/scala/linxcore/top/LinxCoreFrontendFetchRfAluTraceTop.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/lsu/load_unit/ldq.cpp`
    - `LDQInfo::returnData`
    - `LDQInfo::sendCrossRtn`
    - `lsuIexLretArray[iexIdx]->Write(bus)`
  - `model/LinxCoreModel/model/iex/iex.cpp`
    - `IEX::receiveFromLSU`
    - `IEX::setMemData`
- Related Chisel contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnLretPayload.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPublishRequest.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnSideEffectReady.scala`
- Contract IDs: `LC-CHISEL-LSU-REPLAY-LRET-SINK-001`

## Purpose

`LoadReplayReturnLretSink` is the first concrete Chisel owner for the model
LRET queue boundary between LSU return publication and IEX returned-data
consumption. In LinxCoreModel, `LDQInfo::returnData` writes every completed
scalar load return to `lsuIexLretArray[iexIdx]`; `IEX::receiveFromLSU` later
drains that queue only while the target return pipe has capacity, then calls
`IEX::setMemData`.

The current reduced top keeps replay-return publication disabled through
`LoadReplayReturnPublishControl.liveEnable`, so the sink is dormant in
generated-RTL fixtures. Its capacity and blockers are exposed as diagnostics,
but upstream replay launch still treats the LRET sink as unavailable until the
remaining RF writeback, ready-table, wakeup, and row-lifecycle owners are live.

## Interface

| Direction | Signal | Description |
|---|---|---|
| input | `flush` | Clears all queued LRET entries and suppresses same-cycle enqueue/drain. |
| input | `enqueueValid` | Post-fire LRET request from `LoadReplayReturnPublishRequest`. |
| input | `enqueue` | Typed LRET entry carrying payload validity, BID/GID/RID, load LSID, PC, address, size, destination, data, pipe index, `specWakeup`, and `stackValid`. |
| input | `drainReady` | Future IEX return-pipe consumer readiness. |
| output | `enqueueReady` | Sink has capacity, including same-cycle drain capacity. |
| output | `enqueueAccepted` | Valid entry accepted into the FIFO. |
| output | `enqueueDropped` | Valid entry could not enqueue because the FIFO was full. |
| output | `drainValid` | FIFO head is valid and not flushed. |
| output | `drain` | FIFO head entry for the future IEX consumer. |
| output | `drainFire` | FIFO head consumed by `drainReady`. |
| output | `pending` / `full` / `empty` / `count` | Occupancy diagnostics. |
| output | `blockedByFlush` | Enqueue request suppressed by flush. |
| output | `blockedByNoPayload` | Enqueue request arrived without a valid payload entry. |
| output | `blockedByFull` | Valid enqueue request stalled by a full FIFO. |
| output | `blockedByDrain` | FIFO head exists but the future IEX consumer is not ready. |

## Logic Design

The module is a bounded FIFO for `LoadReplayReturnLretEntry`.

```text
inputValid = enqueueValid && enqueue.valid
drainValid = count != 0 && !flush
drainFire = drainValid && drainReady
enqueueReady = !flush && (count != depth || drainFire)
enqueueAccepted = inputValid && enqueueReady
```

Flush clears every resident entry, head and tail pointers, and occupancy.
Same-cycle drain plus enqueue is legal and keeps occupancy stable, matching the
model queue handoff where IEX can free return-pipe space while LSU publishes a
new return. The entry payload is stored unchanged except that accepted entries
force `valid := true`.

## Integration

R318 wires the sink in `LinxCoreFrontendFetchRfAluTraceTop` from:

- `LoadReplayReturnLretPayload` for the typed entry fields;
- `LoadReplayReturnPublishRequest.lretRequest` for `enqueueValid`;
- reduced-store flush for `flush`;
- tied-low `drainReady` because the IEX return-pipe consumer is not owned yet.

The top deliberately keeps the existing upstream `lretSinkReady` literal low.
This preserves the R315-R317 disabled-live-replay contract: adding the storage
owner must not make `LoadReplayReturnReadiness` or `LoadReplayLaunchReadiness`
launch replay rows before RF writeback, ready-table update, issue wakeup, and
replay-row lifecycle owners exist.

## Deferred Owners

- Live IEX return-pipe drain readiness and `IEX::setMemData` mutation.
- Feeding accepted LRET enqueue back into replay-row clear/return lifecycle.
- Multi-pipe LRET queue fanout and arbitration.
- Ready-table and issue-wakeup side effects after LRET enqueue.
- Cross-line return publication into the same sink.

## Verification

Focused gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnLretSink
bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop
FETCH_REDUCED_STORE_REPLAY_LIQ=1 BUILD_DIR=generated/r318-replay-lret-sink-xcheck bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh
```

Reference tests cover FIFO order, full-state backpressure, same-cycle
drain/enqueue capacity, flush clearing, missing-payload diagnostics, drain
backpressure, and Chisel elaboration.
