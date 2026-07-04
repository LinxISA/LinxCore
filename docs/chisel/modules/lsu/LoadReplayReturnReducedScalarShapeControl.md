# LoadReplayReturnReducedScalarShapeControl

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnReducedScalarShapeControl.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/LoadReplayReturnReducedScalarShapeControlSpec.scala`
- Integrated user: `rtl/LinxCore/chisel/src/main/scala/linxcore/top/LinxCoreFrontendFetchRfAluTraceTop.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/iex/iex.cpp`
    - `IEX::setMemData`
  - `model/LinxCoreModel/model/ModelCommon/bus/MemReqBus.h`
- Related Chisel contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnLaneCompletionCandidate.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnTloadCompletionCandidate.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeResidencyCandidate.scala`
- Contract IDs: `LC-CHISEL-LSU-REPLAY-REDUCED-SCALAR-SHAPE-001`

## Purpose

`LoadReplayReturnReducedScalarShapeControl` names the reduced top's current
ordinary scalar replay-return shape. In LinxCoreModel `IEX::setMemData`, the
post-resolve flow branches on scalar load-pair rows, vector or MEM-IEX
multi-lane rows, MEM-IEX TLOAD rows, and vector-vs-scalar pipe residency. The
current reduced scalar top implements none of those alternate shapes yet: it
models one returned scalar lane, a single real request, non-MEM-IEX/non-TLOAD
metadata, and scalar LDA residency.

R386 replaces scattered raw constants for that shape with this owner. The
outputs intentionally preserve existing behavior while giving later agents a
single boundary to replace with real decoded instruction, ROB, and MemReqBus
shape sidebands.

## Interface

| Direction | Signal | Description |
|---|---|---|
| input | `enable` | Replay-LIQ wrapper is active. |
| input | `flush` | Suppresses active shape diagnostics for the cycle. |
| output | `active` | Wrapper enabled and not flushing. |
| output | `reducedScalarShapeValid` | Current reduced scalar shape is active. |
| output | `scalarLoadPair` | False in the current reduced scalar subset. |
| output | `vectorOrMemMultiLane` | False in the current reduced scalar subset. |
| output | `retLaneBefore` | Zero diagnostic lane count before this return. |
| output | `returnedLaneCount` | One returned lane when the upstream resolve candidate increments `retLane`. |
| output | `realReqCnt` | One real request for ordinary scalar replay returns. |
| output | `isMemIex` | False until MEM-IEX replay returns are promoted. |
| output | `isTload` | False until MEM-IEX `OP_TLD` replay returns are promoted. |
| output | `subInstCntBefore` | Zero in the non-TLOAD reduced subset. |
| output | `isVectorMachine` | False, selecting LDA residency instead of AGU residency. |
| output | `blockedByDisabled` / `blockedByFlush` | Shape diagnostics when the wrapper is inactive. |

## Logic Design

```text
active = enable && !flush
reducedScalarShapeValid = active
scalarLoadPair = false
vectorOrMemMultiLane = false
retLaneBefore = 0
returnedLaneCount = 1
realReqCnt = 1
isMemIex = false
isTload = false
subInstCntBefore = 0
isVectorMachine = false
```

The module does not classify decoded instructions. It records the reduced
fixture assumption that the current replay-return path is ordinary scalar LDA
with one returned lane and no TLOAD sub-instruction state.

## Integration

R386 wires `LinxCoreFrontendFetchRfAluTraceTop` through
`LinxCoreFrontendFetchRfAluTraceTopR386ReducedScalarShapeWiring`:

- `LoadReplayReturnLaneCompletionCandidate` receives scalar-pair,
  vector/MEM-multi-lane, `retLaneBefore`, returned-lane count, and `realReqCnt`
  from this owner;
- `LoadReplayReturnTloadCompletionCandidate` receives `isMemIex`, `isTload`,
  and `subInstCntBefore` from this owner;
- `LoadReplayReturnPipeResidencyCandidate` receives `isVectorMachine` from this
  owner.

The helper keeps the large integrated top below the JVM method-size cliff while
preserving the previous constants.

## Deferred Owners

- Scalar load-pair opcode classification and per-lane destination side effects.
- Vector/MEM-IEX multi-lane return classification and real `retLane` storage.
- MEM-IEX `OP_TLD` classification and real ROB `subInstCnt` state.
- Vector-vs-scalar return-pipe residency classification from decoded/renamed
  instruction state.
- Tile-SCB, ROB, RF, ready-table, issue-wakeup, and replay-row lifecycle
  mutations after the full shape path is promoted.

## Verification

Focused gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnReducedScalarShapeControl
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnLaneCompletionCandidate
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnTloadCompletionCandidate
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeResidencyCandidate
bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop
FETCH_REDUCED_STORE_REPLAY_LIQ=1 BUILD_DIR=generated/r386x bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh
```

Reference tests cover active scalar-shape outputs, disabled and flush
diagnostics, and Chisel elaboration.
