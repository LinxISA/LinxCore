# LoadReplayReturnLaneCompletionCandidate

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnLaneCompletionCandidate.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/LoadReplayReturnLaneCompletionCandidateSpec.scala`
- Integrated user: `rtl/LinxCore/chisel/src/main/scala/linxcore/top/LinxCoreFrontendFetchRfAluTraceTop.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/iex/iex.cpp`
    - `IEX::setMemData`
  - `model/LinxCoreModel/model/pe/PECommon/PROBCommon.cpp`
    - `ROBState::resolveData`
  - `model/LinxCoreModel/model/ModelCommon/bus/MemReqBus.h`
- Related Chisel contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnRobResolveDataCandidate.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnIexPipeInsertCandidate.scala`
- Contract IDs: `LC-CHISEL-LSU-REPLAY-LANE-COMPLETION-001`

## Purpose

`LoadReplayReturnLaneCompletionCandidate` is the diagnostic post-resolve lane
completion boundary in the model `IEX::setMemData` sequence. After
`ROBState::resolveData` increments `retLane`, LinxCoreModel applies two
early-return checks before cloning the load-return instruction for E4:

- scalar load-pair rows write each returned lane's destination data and return
  while `retLane < mem.realReqCnt`;
- vector or MEM-IEX multi-lane rows with `inst->lanes > 0` also return while
  `retLane < mem.realReqCnt`.

R324 names this completion predicate without mutating ROB state. The current
reduced top ties the packet to one returned scalar lane with no load-pair or
vector/MEM multi-lane requirement, so it passes through exactly when the R323
resolve-data diagnostic is valid. Future owners can replace those tied inputs
with real load-pair, vector, MEM-IEX, and lane-count state.

## Interface

| Direction | Signal | Description |
|---|---|---|
| input | `enable` | Replay-LIQ wrapper is active. |
| input | `flush` | Suppresses lane-completion diagnostics for the cycle. |
| input | `resolveValid` | R323 ROB resolve-data request accepted. |
| input | `scalarLoadPair` | The target model instruction is a scalar load-pair row that must wait for all returned lanes. |
| input | `vectorOrMemMultiLane` | The target model instruction is a vector or MEM-IEX multi-lane row that must wait for all returned lanes. |
| input | `retLaneBefore` | Diagnostic return-lane count before this returned payload. |
| input | `returnedLaneCount` | Number of lanes represented by the current returned payload. |
| input | `realReqCnt` | Model `MemReqBus.realReqCnt`, used only for all-lane rows. |
| output | `candidateValid` | Enabled, not flushing, and post-resolve input is valid. |
| output | `retLaneAfterResolve` | `retLaneBefore + returnedLaneCount` when a candidate is valid. |
| output | `requiresAllLanes` | Candidate is a scalar load-pair or vector/MEM multi-lane row. |
| output | `completeValid` | Candidate has returned at least one lane and has satisfied any required all-lane count. |
| output | `readyForPipeInsert` | Alias for `completeValid`; feeds the R322 E4 insert-shaped diagnostic. |
| output | `blockedBy*` | Disabled, flush, no-resolve, zero-lane, invalid `realReqCnt`, scalar-pair incomplete, and vector/MEM incomplete diagnostics. |

## Logic Design

```text
candidateValid = enable && !flush && resolveValid
retLaneAfterResolve = retLaneBefore + returnedLaneCount
requiresAllLanes = scalarLoadPair || vectorOrMemMultiLane
hasValidReqCnt = !requiresAllLanes || realReqCnt != 0
allLanesReturned = !requiresAllLanes || retLaneAfterResolve >= realReqCnt
completeValid = candidateValid && returnedLaneCount != 0 &&
                hasValidReqCnt && allLanesReturned
readyForPipeInsert = completeValid
```

For ordinary scalar non-load-pair returns, the model does not consult
`realReqCnt` in `setMemData`; one returned lane is enough to continue to the
clone/E4 insertion diagnostic. For scalar load-pair and vector/MEM multi-lane
returns, this module blocks until the diagnostic post-resolve lane count reaches
`realReqCnt`.

## Integration

`LinxCoreFrontendFetchRfAluTraceTop` wires R324 between
`LoadReplayReturnRobResolveDataCandidate` and
`LoadReplayReturnIexPipeInsertCandidate`:

- `resolveValid` comes from R323 `readyForPipeInsert`;
- `returnedLaneCount` is `1` when R323 reports `retLaneIncrement`;
- `retLaneBefore` is tied to zero in the current reduced scalar subset;
- `realReqCnt` is tied to one in the current reduced scalar subset;
- `scalarLoadPair` and `vectorOrMemMultiLane` are tied false until those row
  classifiers and lane counters exist;
- R322 consumes R324 `readyForPipeInsert`.

This preserves the model order:

```text
setMemData admission
  -> ROB resolve-data intent
  -> lane-completion permit
  -> IEX E4 insert-shaped candidate
```

The module exposes the future load-pair/vector completion surface but does not
write ROB data, update RF, wake issue queues, update ready tables, drain LRET,
or clear replay-LIQ rows.

## Deferred Owners

- Real per-ROB-row `retLane` storage and update.
- Scalar load-pair opcode classification and per-lane destination data writes.
- Vector/MEM-IEX `inst->lanes` qualification and `realReqCnt` sideband carry.
- TLOAD sub-instruction completion and tile-SCB request side effects.
- Real E4 pipe residency, replay-row lifecycle, ready-table update, issue
  wakeup, and RF writeback after completion.

## Verification

Focused gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnLaneCompletionCandidate
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnRobResolveDataCandidate
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnIexPipeInsertCandidate
bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop
FETCH_REDUCED_STORE_REPLAY_LIQ=1 BUILD_DIR=generated/r324-replay-lane-completion-xcheck bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh
```

Reference tests cover ordinary scalar pass-through, scalar load-pair
incomplete/complete cases, vector/MEM incomplete/complete cases, disabled,
flush, no-resolve, zero-lane, invalid-count blockers, and Chisel elaboration.
