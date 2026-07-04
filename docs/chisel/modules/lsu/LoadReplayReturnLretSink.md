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
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnIexPipeOccupancy.scala`
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
generated-RTL fixtures. R378 feeds the upstream LRET readiness predicate from
the FIFO `enqueueReady` capacity signal. R382 drives `drainReady` from the
named `LoadReplayReturnIexDrainPermit` owner. R383 replaces the raw tied-full
return-pipe occupancy literal with `LoadReplayReturnIexPipeOccupancy`, whose
live-disabled policy still forces the single reduced return pipe occupied. The
permit remains false and IEX drain remains disabled. This lets
return-readiness, drain-permit, and pipe-occupancy diagnostics observe named
predicates without making LRET publication, RF writeback, ready-table mutation,
wakeup, or row lifecycle state live.

R376 extends `LoadReplayReturnLretEntry` with the row-owned source trace pair
from the LRET payload. The FIFO stores and drains these fields as ordinary
payload state; flush and reset use the bundle zero image so stale source
operands cannot survive an invalid entry.

## Interface

| Direction | Signal | Description |
|---|---|---|
| input | `flush` | Clears all queued LRET entries and suppresses same-cycle enqueue/drain. |
| input | `enqueueValid` | Post-fire LRET request from `LoadReplayReturnPublishRequest`. |
| input | `enqueue` | Typed LRET entry carrying payload validity, BID/GID/RID, load LSID, PC, address, size, destination, source traces, data, pipe index, `specWakeup`, and `stackValid`. |
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
- `LoadReplayReturnIexDrainPermit.drainReady` for the future IEX consumer
  readiness.

R378 replaces the old upstream `lretSinkReady` literal with
`LoadReplayReturnLretSink.enqueueReady`. This is still a capacity-only
promotion: `LoadReplayReturnPublishControl.liveEnable` remains false, so
`LoadReplayReturnPublishRequest.lretRequest` cannot enqueue an entry.

R382 replaces the previous direct false `drainReady` tie with the drain permit
output. R383 feeds that permit from `LoadReplayReturnIexPipeOccupancy` rather
than a raw literal. The current owner still forces all pipes occupied, so
`drainReady` is false until real IEX return-pipe occupancy is live. The storage
owner can now contribute real LRET-capacity blockers and named drain/occupancy
blockers without enabling replay launch side effects before RF writeback,
ready-table update, issue wakeup, and replay-row lifecycle owners exist.

## Deferred Owners

- Live LRET enqueue fire from replay-return publication.
- Real IEX return-pipe free-capacity input and `IEX::setMemData` mutation.
- Feeding accepted LRET enqueue back into replay-row clear/return lifecycle.
- Multi-pipe LRET queue fanout and arbitration.
- Ready-table and issue-wakeup side effects after LRET enqueue.
- Cross-line return publication into the same sink.

## Verification

Focused gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnLretSink
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnIexPipeOccupancy
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnIexDrainPermit
bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop
FETCH_REDUCED_STORE_REPLAY_LIQ=1 BUILD_DIR=generated/r383x bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh
```

Reference tests cover FIFO order, full-state backpressure, same-cycle
drain/enqueue capacity, flush clearing, missing-payload diagnostics, drain
backpressure, and Chisel elaboration.
