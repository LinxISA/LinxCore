# LoadReplayReturnIexPipeOccupancyLiveControl

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnIexPipeOccupancyLiveControl.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/LoadReplayReturnIexPipeOccupancyLiveControlSpec.scala`
- Integrated user: `rtl/LinxCore/chisel/src/main/scala/linxcore/top/LinxCoreFrontendFetchRfAluTraceTop.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/iex/iex.cpp`
    - `IEX::receiveFromLSU`
    - `IEX::BackToPipe`
    - `CheckPipeValid`
    - `IEX::setMemData`
  - `model/LinxCoreModel/model/lsu/load_unit/ldq.cpp`
    - `LDQInfo::returnData`
    - `lsuIexLretArray[iexIdx]->Write`
- Related Chisel contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnIexPipeOccupancy.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnIexDrainPermit.scala`
- Contract IDs: `LC-CHISEL-LSU-REPLAY-IEX-OCCUPANCY-LIVE-001`

## Purpose

`LoadReplayReturnIexPipeOccupancyLiveControl` names the request side of the
future live IEX return-pipe occupancy source. In LinxCoreModel, returned loads
are written by the LSU into `lsuIexLretArray[iexIdx]`. `IEX::receiveFromLSU`
then checks `BackToPipe`; that scan reports whether every scalar LDA or vector
AGU E4 return pipe is occupied. Only when at least one pipe is free does the
model read an LRET entry and call `IEX::setMemData`.

R388 keeps the current reduced top dormant. It routes the former direct
`liveRequested=false` and `livePipeOccupiedMask=0` constants through this
module before `LoadReplayReturnIexPipeOccupancy`. Because both `requestEnable`
and `sourceValid` are tied low in the top, `liveRequested` remains false and
the downstream occupancy owner still forces every pipe occupied.

## Interface

| Direction | Signal | Description |
|---|---|---|
| input | `enable` | Replay-LIQ wrapper is active. |
| input | `flush` | Suppresses the live occupancy request. |
| input | `requestEnable` | Future top-level permission to consume real return-pipe occupancy. |
| input | `sourceValid` | Future evidence that the IEX return-pipe occupancy source is valid this cycle. |
| input | `livePipeOccupiedMaskIn` | Future one-bit-per-return-pipe occupancy mask from the IEX pipe-state owner. |
| output | `active` | Wrapper enabled and not flushing. |
| output | `requestActive` | The live request gate is active before source-evidence qualification. |
| output | `occupancyEvidenceValid` | The live source is valid while the wrapper is active. |
| output | `allPipeMask` | Mask covering implemented return pipes. |
| output | `maskedLivePipeOccupiedMask` | Source mask clipped to implemented return pipes. |
| output | `liveRequested` | Request sent to `LoadReplayReturnIexPipeOccupancy`. |
| output | `livePipeOccupiedMask` | Mask sent to `LoadReplayReturnIexPipeOccupancy`; zero while not requested. |
| output | `blockedByDisabled` | Request or source evidence arrived while disabled. |
| output | `blockedByFlush` | Request or source evidence arrived during flush. |
| output | `blockedByRequestDisabled` | Source evidence exists, but the top has not enabled live occupancy. |
| output | `blockedByNoSource` | The top requested live occupancy without a valid source. |

## Logic Design

```text
active = enable && !flush
requestActive = active && requestEnable
occupancyEvidenceValid = active && sourceValid
maskedLivePipeOccupiedMask = livePipeOccupiedMaskIn & allPipeMask
liveRequested = requestActive && sourceValid
livePipeOccupiedMask = liveRequested ? maskedLivePipeOccupiedMask : 0
```

The zero-mask output is harmless while `liveRequested=false`, because
`LoadReplayReturnIexPipeOccupancy` ignores the mask and forces the selected
pipe-occupied mask to `allPipeMask`. This preserves the current disabled drain
behavior while separating the future live request and source-validity checks
from top-level glue.

## Integration

R388 wires `LinxCoreFrontendFetchRfAluTraceTop` as follows:

- `enable` comes from the reduced replay-LIQ allocation enable;
- `flush` comes from reduced-store flush;
- `requestEnable` remains `false`;
- `sourceValid` remains `false`;
- `livePipeOccupiedMaskIn` remains zero;
- `liveRequested` and `livePipeOccupiedMask` feed
  `LoadReplayReturnIexPipeOccupancy`.

`LoadReplayReturnIexPipeOccupancy` therefore still emits `pipeOccupiedMask=1`
for the single reduced return pipe, and `LoadReplayReturnIexDrainPermit` still
keeps `drainReady=false`.

## Deferred Owners

- Real scalar LDA/vector AGU E4 return-pipe occupancy source.
- Source-valid timing for the selected IEX pipe family.
- LRET FIFO drain and `IEX::setMemData` mutation.
- ROB resolve, RF replay writeback, ready-table mutation, issue wakeup, W2
  row fill, replay-row lifecycle clear, and live E4 residency writes.

## Verification

Focused gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnIexPipeOccupancyLiveControl
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnIexPipeOccupancy
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnIexDrainPermit
bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop
FETCH_REDUCED_STORE_REPLAY_LIQ=1 BUILD_DIR=generated/r388x bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh
```

Reference tests cover disabled request behavior, live source pass-through,
source-missing blocking, disabled/flush blockers, mask clipping, and Chisel
elaboration.
