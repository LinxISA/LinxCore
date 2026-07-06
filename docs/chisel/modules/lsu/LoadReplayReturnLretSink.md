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

R571 adds harness-only schema v24 counters around W2 occupancy and narrows the
next live owner to this sink boundary. The early-STA delay-12 replay-LIQ gate
records upstream source and payload overlap while W2 is occupied
(`source_return_candidate_w2_occupied=4`,
`return_publish_ready_w2_occupied=3`,
`lret_payload_valid_w2_occupied=3`), but this sink is not resident or draining in
that window (`lret_sink_pending_w2_occupied=0`,
`lret_sink_drain_fire_w2_occupied=0`). Future implementation should first make
publish-to-LRET-sink enqueue and IEX drain capacity observable under the passing
fixture before promoting W2 slot replacement.
R572 then proves publish admission is already live in the same window:
`publish_control_fire_w2_occupied=3` and
`lret_sink_enqueue_accepted_w2_occupied=3`, with zero enqueue drops. Those
accepted enqueues are not same-cycle drains
(`lret_sink_enqueue_accepted_same_cycle_drain_fire_w2_occupied=0`) and still do
not make `pending` or `drainFire` overlap W2. The next implementation owner is
therefore enqueue-to-pending/drain timing and the IEX drain-capacity path, not
publication readiness.
R573 adds one-cycle follow-up buckets after W2-overlap enqueue acceptance. The
accepted entry becomes `pending`, `drainValid`, and `drainFire` with drain
permit ready in the following cycle, but W2 has already cleared in every
follow-up sample (`lret_sink_followup_w2_cleared=3`,
`lret_sink_followup_w2_still_occupied=0`). The sink and permit are not the live
capacity blocker on this fixture; the next owner is W2 hold/live-clear phasing
relative to this registered FIFO visibility.
R574 adds clear-classification buckets and proves the same accepted W2-overlap
enqueues coincide with W2 completion clear, clear intent, side-effect
fire-complete, and live clear (`lret_sink_enqueue_accepted_w2_clear_intent=3`,
`lret_sink_enqueue_accepted_w2_live_clear=3`). The next-cycle follow-up shows
the registered FIFO entry is pending/drain-valid/drain-fired with permit ready,
but after W2 has already cleared even when the prior accepted enqueue carried
live-clear evidence (`lret_sink_followup_after_enqueue_live_clear_w2_cleared=3`).
The sink is therefore behaving as a registered FIFO boundary; the next live
owner is W2 clear/retire phasing, not FIFO capacity or drain permit.

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

- Real IEX return-pipe free-capacity input and `IEX::setMemData` mutation.
- Default-off W2 clear/retire phasing experiment or model-derived W2 retire
  boundary that keeps W2 occupied through the cycle after accepted LRET enqueue,
  when the registered FIFO entry is pending/draining.
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
