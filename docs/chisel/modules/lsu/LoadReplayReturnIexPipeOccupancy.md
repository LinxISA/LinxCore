# LoadReplayReturnIexPipeOccupancy

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnIexPipeOccupancy.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/LoadReplayReturnIexPipeOccupancySpec.scala`
- Integrated user: `rtl/LinxCore/chisel/src/main/scala/linxcore/top/LinxCoreFrontendFetchRfAluTraceTop.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/iex/iex.cpp`
    - `IEX::receiveFromLSU`
    - `IEX::BackToPipe`
    - `CheckPipeValid`
    - `IEX::setMemData`
- Related Chisel contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnIexPipeOccupancyLiveControl.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnIexDrainPermit.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/ScalarLSULoadReturnQueue.scala`
- Contract IDs: `LC-CHISEL-LSU-REPLAY-IEX-OCCUPANCY-001`

## Purpose

`LoadReplayReturnIexPipeOccupancy` names the source of the IEX return-pipe
occupancy mask used before draining replay LRET payloads. In LinxCoreModel,
`IEX::receiveFromLSU` calls `BackToPipe`, which scans the scalar LDA or vector
AGU return pipes through `CheckPipeValid`; it reads an LRET queue entry only
when at least one E4 return-pipe slot is free, then calls `IEX::setMemData`.

R383 replaces the reduced top's raw tied-full occupancy literal with this
owner. R388 routes the live request and source mask through
`LoadReplayReturnIexPipeOccupancyLiveControl`; the top keeps that live-control
request disabled and supplies no source evidence, so this owner still sees
`liveRequested=false`, selects an all-occupied output mask, and
`LoadReplayReturnIexDrainPermit` still refuses to drain the FIFO. This keeps
replay-return mutation disabled while making the future live pipe occupancy
request and source explicit.

## Interface

| Direction | Signal | Description |
|---|---|---|
| input | `enable` | Replay-LIQ wrapper is active. |
| input | `flush` | Suppresses live occupancy use. |
| input | `liveRequested` | Future request to consume real IEX return-pipe occupancy. |
| input | `livePipeOccupiedMask` | Future one-bit-per-pipe occupancy mask from the IEX return-pipe state owner. |
| output | `active` | Wrapper enabled and not flushing. |
| output | `requestActive` | Live occupancy is allowed this cycle. |
| output | `allPipeMask` | Mask covering implemented return pipes. |
| output | `maskedLivePipeOccupiedMask` | Live input clipped to implemented return pipes. |
| output | `pipeOccupiedMask` | Occupancy mask sent to `LoadReplayReturnIexDrainPermit`. |
| output | `forcedFull` | Current path forced every return pipe occupied. |
| output | `anyPipeOccupied` | At least one pipe is occupied in the selected mask. |
| output | `allPipesOccupied` | Every implemented return pipe is occupied. |
| output | `anyPipeFree` | At least one implemented return pipe is free. |
| output | `blockedByDisabled` | Live occupancy was requested while the wrapper was disabled. |
| output | `blockedByFlush` | Live occupancy was requested during flush. |
| output | `blockedByLiveDisabled` | Wrapper is active, but live occupancy is not yet requested. |

## Logic Design

```text
active = enable && !flush
requestActive = active && liveRequested
maskedLivePipeOccupiedMask = livePipeOccupiedMask & allPipeMask
pipeOccupiedMask = requestActive ? maskedLivePipeOccupiedMask : allPipeMask
```

The forced-full default is the safety property: until the real IEX return-pipe
state owner is wired, the drain permit sees no free pipe and the LRET FIFO
cannot be consumed.

## Integration

R383/R388 wire `LinxCoreFrontendFetchRfAluTraceTop` as follows:

- `enable` comes from the reduced replay-LIQ allocation enable;
- `flush` comes from reduced-store flush;
- `liveRequested` comes from `LoadReplayReturnIexPipeOccupancyLiveControl`,
  whose current `requestEnable` and `sourceValid` inputs remain false;
- `livePipeOccupiedMask` also comes from that live-control owner, which emits
  zero while no real return-pipe owner is connected;
- `pipeOccupiedMask` feeds `LoadReplayReturnIexDrainPermit.pipeOccupiedMask`.

The top's existing `reducedLoadReplayLiqLretDrainPermitPipeOccupiedMask`
diagnostic now reports this module output. It remains `1` in the current
single-pipe reduced configuration.

## Deferred Owners

- Real scalar LDA/vector AGU E4 return-pipe occupancy.
- Enabling the R388 live occupancy request once `IEX::setMemData` mutation and
  returned load pipe residency are promoted.
- Multi-return-pipe policy beyond the current lowest-free-pipe diagnostic.
- FIFO drain, ROB mutation, RF replay writeback, ready-table mutation, issue
  wakeup, W2 row fill, and replay-row lifecycle clear.

## Verification

Focused gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnIexPipeOccupancy
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnIexPipeOccupancyLiveControl
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnIexDrainPermit
bash tools/chisel/run_chisel_tests.sh --only ScalarLSULoadReturnQueue
bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop
FETCH_REDUCED_STORE_REPLAY_LIQ=1 BUILD_DIR=generated/r383x bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh
```

Reference tests cover forced-full default behavior, live mask pass-through,
mask clipping, disabled/flush blockers, and Chisel elaboration.
