# LoadReplayReturnIexDataCandidate

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnIexDataCandidate.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/LoadReplayReturnIexDataCandidateSpec.scala`
- Integrated user: `rtl/LinxCore/chisel/src/main/scala/linxcore/top/LinxCoreFrontendFetchRfAluTraceTop.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/iex/iex.cpp`
    - `IEX::receiveFromLSU`
    - `IEX::BackToPipe`
    - `CheckPipeValid`
    - `IEX::setMemData`
  - `model/LinxCoreModel/model/lsu/load_unit/ldq.cpp`
    - `LDQInfo::returnData`
    - `LDQInfo::sendCrossRtn`
- Related Chisel contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnLretSink.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnIexDrainPermit.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/rob/ROBRowStatusLookup.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnRobResolveDataCandidate.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnIexPipeInsertCandidate.scala`
- Contract IDs: `LC-CHISEL-LSU-REPLAY-IEX-DATA-001`

## Purpose

`LoadReplayReturnIexDataCandidate` is the diagnostic pre-mutation admission
owner for the model `IEX::setMemData` path. In LinxCoreModel,
`IEX::receiveFromLSU` reads an LRET queue entry only after `BackToPipe` proves a
return pipe has E4 capacity. `IEX::setMemData` then rejects invalid payloads and
ROB rows marked `INST_NEEDFLUSH` before resolving returned data into the ROB
instruction and inserting the cloned load-return instruction into an E4 pipe.

R320 names only that first admission boundary. It copies a drained
`LoadReplayReturnLretEntry` into a future `setMemData`-shaped diagnostic output
when all of these are true:

- replay-LIQ diagnostics are enabled;
- the sink head is valid and not flushed;
- the IEX drain permit is ready;
- the ROB row image is present;
- the ROB row is not marked need-flush.

R321 feeds `robRowValid` and `robRowNeedFlush` from the read-only
`ROBRowStatusLookup` path through `DecodeRenameROBPath`. The current reduced
top still keeps `LoadReplayReturnLretSink.drainReady` tied low and the IEX
return-pipe permit blocked. Therefore this packet cannot drain LRET, mutate
ROB/RF/local state, publish ready-table wakeup, or insert a load-return
instruction into an IEX return pipe.

R376 also copies the LRET entry source-trace pair into the admitted
`setMemData` diagnostic payload. The source sideband is valid only when
`setMemDataValid` is true, matching the identity/data copy guard.

R571's generated-RTL/QEMU sideband v24 evidence shows that source-return and
LRET-payload formation can overlap occupied W2, but the LRET sink has no pending
or drain-fire overlap in that same window. R572's schema v25 evidence proves
publish-control fire and LRET enqueue acceptance also overlap occupied W2
(`publish_control_fire_w2_occupied=3`,
`lret_sink_enqueue_accepted_w2_occupied=3`), yet `pending` and `drainFire`
remain zero during W2 occupancy. Treat this module's next proof as a
post-enqueue step: first make the accepted sink entry hold and drain while W2 is
occupied, then require nonzero `setMemData` / IEX insert overlap before changing
W2 slot storage.

## Interface

| Direction | Signal | Description |
|---|---|---|
| input | `enable` | Replay-LIQ wrapper is active. |
| input | `flush` | Suppresses candidate and drain diagnostics for the cycle. |
| input | `sinkValid` | LRET sink has a resident head entry. |
| input | `drainReady` | IEX drain permit from `LoadReplayReturnIexDrainPermit`. |
| input | `entry` | FIFO-head `LoadReplayReturnLretEntry`. |
| input | `robRowValid` | Future ROB row-image availability for `entry.rid`. |
| input | `robRowNeedFlush` | Future ROB row status equivalent to model `INST_NEEDFLUSH`. |
| output | `candidateValid` | Enabled, not flushing, sink entry exists, and entry payload is valid. |
| output | `wouldDrain` | Candidate plus drain permit; diagnostic equivalent of the model queue read point. |
| output | `setMemDataValid` | Future safe admission to `setMemData` after ROB row checks. |
| output | `memBid` / `memGid` / `memRid` / `memLoadLsId` | Copied identity only when `setMemDataValid` is true. |
| output | `memPc` / `memAddr` / `memSize` / `memData` / `memDst` | Copied returned-load payload only when admitted. |
| output | `memSourceTraceValid` / `memSource0` / `memSource1` | R376 source operand trace sideband copied from the drained LRET entry only when admitted. |
| output | `memPipeIndex` / `memSpecWakeup` / `memStackValid` | Copied return-pipe and wakeup sidebands only when admitted. |
| output | `blockedBy*` | Disabled, flush, no-entry, invalid-entry, drain, ROB-missing, and need-flush blockers. |

## Logic Design

```text
candidateValid = enable && !flush && sinkValid && entry.valid
wouldDrain = candidateValid && drainReady
setMemDataValid = wouldDrain && robRowValid && !robRowNeedFlush
```

Output payload fields are disabled or zero unless `setMemDataValid` is true.
This mirrors the model's early `setMemData` guards but deliberately stops before
`rob_next.resolveData`, scalar load-pair lane accounting, cloned
`SimInst` mutation, TLOAD SCB side effects, branch-load resolution, and E4 pipe
insertion.

## Integration

R320 wires the module behind `LoadReplayReturnLretSink` and
`LoadReplayReturnIexDrainPermit` in `LinxCoreFrontendFetchRfAluTraceTop`:

- `sinkValid` and `entry` come from the LRET sink head;
- `drainReady` comes from the R319 permit;
- `robRowValid` comes from the R321 ROB row status query;
- `robRowNeedFlush` comes from the R321 ROB row status query;
- `LoadReplayReturnLretSink.drainReady` remains `false`.

The top exposes candidate, would-drain, setMemData, ROB-row, and blocker
diagnostics only. No output feeds ROB, RF, ready-table, issue queue, replay-LIQ
row lifecycle, or return-pipe state. R322 consumes these diagnostics to form a
separate E4 insert-shaped candidate, but that module is also diagnostic-only
and does not feed live pipe residency.
R323 inserts `LoadReplayReturnRobResolveDataCandidate` as the immediate
consumer of `setMemDataValid` before the R322 E4 insert-shaped diagnostic. It
names the future `ROBState::resolveData` request shape but still leaves this
module side-effect-free and keeps FIFO drain disabled.

## Deferred Owners

- Driving the LRET sink's real `drainReady`.
- Proving nonzero LRET sink drain and `setMemData` overlap with occupied W2 in a
  passing generated-RTL/QEMU fixture.
- `IEX::setMemData` data resolution into ROB instruction destinations.
- Scalar load-pair lane completion and vector/MEM_IEX `realReqCnt` accounting.
- TLOAD tile-SCB side effects and load-branch resolve.
- Return-pipe E4 residency and subsequent commit/wakeup behavior behind the
  R322 insert candidate.

## Verification

Focused gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnIexDataCandidate
bash tools/chisel/run_chisel_tests.sh --only ROBRowStatusLookup
bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop
FETCH_REDUCED_STORE_REPLAY_LIQ=1 BUILD_DIR=generated/r321-replay-rob-row-status-xcheck bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh
```

Reference tests cover successful pre-mutation admission, missing ROB-row
blocking, need-flush skipping, disabled/flush/no-entry/invalid-entry blockers,
closed drain-permit blocking, and Chisel elaboration.
