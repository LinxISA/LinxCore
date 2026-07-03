# LoadReplayReturnRobResolveDataCandidate

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnRobResolveDataCandidate.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/LoadReplayReturnRobResolveDataCandidateSpec.scala`
- Integrated user: `rtl/LinxCore/chisel/src/main/scala/linxcore/top/LinxCoreFrontendFetchRfAluTraceTop.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/pe/PECommon/PROBCommon.cpp`
    - `ROBState::resolveData`
  - `model/LinxCoreModel/model/iex/iex.cpp`
    - `IEX::setMemData`
  - `model/LinxCoreModel/model/ModelCommon/bus/MemReqBus.h`
- Related Chisel contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnIexDataCandidate.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnLaneCompletionCandidate.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnIexPipeInsertCandidate.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayDestination.scala`
- Contract IDs: `LC-CHISEL-LSU-REPLAY-ROB-RESOLVE-DATA-001`

## Purpose

`LoadReplayReturnRobResolveDataCandidate` is the diagnostic boundary for the
first ROB-side effect inside the model `IEX::setMemData` path. After the
R320/R321 admission checks prove that a returned LRET payload names a present
ROB row that is not `INST_NEEDFLUSH`, LinxCoreModel calls
`rob_next.resolveData(mem, lane, mem.simtMask)` for each returned lane. That
model helper marks every destination in the target instruction data-valid,
optionally writes vector lane data when the returned lane is enabled by
`simtMask`, increments `retLane`, and returns the new lane count.

R323 names only the reduced scalar request shape. It accepts an admitted
`setMemData` diagnostic payload, requires the current one-lane reduced replay
mode, and emits future ROB resolve-data diagnostics:

- target ROB RID;
- returned scalar data;
- compact one-destination sideband for reduced GPR visibility;
- all-destination data-valid intent;
- reduced destination data-valid intent;
- return-lane increment intent.

The module deliberately does not mutate the ROB row, write vector lane data,
count multi-lane or vector returns, drive RF/writeback, update ready tables,
wake issue queues, drain LRET, or insert an instruction into IEX E4 residency.

## Interface

| Direction | Signal | Description |
|---|---|---|
| input | `enable` | Replay-LIQ wrapper is active. |
| input | `flush` | Suppresses resolve diagnostics for the cycle. |
| input | `setMemDataValid` | R320/R321 admission result after LRET, drain-permit, and ROB row-status checks. |
| input | `reducedSingleLane` | Current supported subset: one scalar returned lane. False blocks the request. |
| input | `memRid` | Target ROB row from the admitted returned-load payload. |
| input | `memDst` | Compact reduced replay destination sideband. |
| input | `memData` | Extracted scalar return data. |
| output | `candidateValid` | Enabled, not flushing, and admitted to the reduced `setMemData` boundary. |
| output | `resolveValid` | Candidate is in the supported reduced one-lane subset and has a valid RID. |
| output | `readyForPipeInsert` | Resolve boundary has accepted the reduced request; feeds the later insert-shaped diagnostic. |
| output | `resolveRid` / `resolveDst` / `resolveData` | Copied ROB/data/destination request fields only when `resolveValid` is true. |
| output | `markAllDestinationsDataValid` | Future model `pdst->dataVld = true` intent for every destination. |
| output | `markDestinationDataValid` | Reduced one-destination data-valid diagnostic when `memDst.valid` is true. |
| output | `retLaneIncrement` | Future `retLane` increment intent for the admitted scalar lane. |
| output | `vectorLaneDataWrite` | Always false in R323; vector lane writes are deferred. |
| output | `blockedBy*` | Disabled, flush, no-setMemData, unsupported multi-lane, invalid RID, and no-destination diagnostics. |

## Logic Design

```text
candidateValid = enable && !flush && setMemDataValid
resolveValid = candidateValid && reducedSingleLane && memRid.valid
readyForPipeInsert = resolveValid
markAllDestinationsDataValid = resolveValid
markDestinationDataValid = resolveValid && memDst.valid
retLaneIncrement = resolveValid
vectorLaneDataWrite = false
```

`blockedByNoDestination` is diagnostic only. LinxCoreModel marks all
instruction destinations data-valid, so the reduced destination sideband being
absent does not prevent the row-level resolve request or the following
insert-shaped diagnostic from forming. It only prevents the reduced one-GPR
visibility signal from asserting.

## Integration

`LinxCoreFrontendFetchRfAluTraceTop` wires R323 between
`LoadReplayReturnIexDataCandidate` and
`LoadReplayReturnLaneCompletionCandidate`:

- `setMemDataValid`, `memRid`, `memDst`, and `memData` come from
  `LoadReplayReturnIexDataCandidate`;
- `reducedSingleLane` is tied true for the current scalar replay subset;
- `readyForPipeInsert` feeds the R324 lane-completion permit before the R322
  insert-shaped diagnostic.

This makes the integrated diagnostic order match the model:

```text
LRET drain permit
  -> setMemData admission and ROB status check
  -> ROB resolve-data request
  -> lane-completion permit
  -> IEX E4 insert-shaped candidate
```

The current top still keeps `LoadReplayReturnLretSink.drainReady` tied low and
keeps live IEX pipe residency disabled. Therefore this packet cannot drain a
FIFO entry or mutate ROB, RF, ready-table, issue, replay-row, or E4 pipe state.

## Deferred Owners

- Real `ROBState::resolveData` mutation of ROB instruction destination state.
- Real scalar load-pair lane completion and `retLane` count storage.
- Vector lane writes under `simtMask` and MEM/IEX `realReqCnt` accounting.
- RF/writeback, ready-table, and issue-wakeup side effects after returned data.
- Real LRET FIFO drain, replay-row lifecycle, and IEX E4 residency mutation.

## Verification

Focused gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnRobResolveDataCandidate
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnLaneCompletionCandidate
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnIexPipeInsertCandidate
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnIexDataCandidate
bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop
FETCH_REDUCED_STORE_REPLAY_LIQ=1 BUILD_DIR=generated/r323-replay-rob-resolve-data-xcheck bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh
```

Reference tests cover successful reduced scalar resolve request formation,
absent reduced-destination diagnostics, unsupported multi-lane blocking,
disabled/flush/no-setMemData/invalid-RID blockers, and Chisel elaboration.
