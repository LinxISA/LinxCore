# LoadReplayReturnIexDrainPermit

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnIexDrainPermit.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/LoadReplayReturnIexDrainPermitSpec.scala`
- Integrated user: `rtl/LinxCore/chisel/src/main/scala/linxcore/top/LinxCoreFrontendFetchRfAluTraceTop.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/iex/iex.cpp`
    - `IEX::receiveFromLSU`
    - `IEX::BackToPipe`
    - `CheckPipeValid`
    - `IEX::setMemData`
- Related Chisel contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnLretSink.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnLretPayload.scala`
- Contract IDs: `LC-CHISEL-LSU-REPLAY-LRET-DRAIN-001`

## Purpose

`LoadReplayReturnIexDrainPermit` is the diagnostic owner for the IEX side of
the model LRET handoff. In LinxCoreModel, `IEX::receiveFromLSU` loops over
`lsu_iex_lret_array`, checks `BackToPipe`, and only reads an LRET queue entry
when at least one return pipe is not occupied by an E4 instruction. The popped
payload is then passed to `IEX::setMemData`.

R382 wires this permit to the Chisel FIFO's real `drainReady` input while still
tying current IEX return-pipe occupancy to full. The permit therefore remains
false in the reduced top, exposes the pipe-full blocker, and names the
downstream readiness predicate without discarding payloads before the real IEX
data-mutation owner exists.

## Interface

| Direction | Signal | Description |
|---|---|---|
| input | `enable` | Replay-LIQ wrapper is active. |
| input | `flush` | Suppresses a drain permit while the sink is being cleared. |
| input | `sinkValid` | The LRET sink has a resident head entry. |
| input | `pipeOccupiedMask` | One bit per future IEX return pipe; `1` means occupied. |
| output | `pipeFreeMask` | Inverse of `pipeOccupiedMask`, masked to `returnPipeCount`. |
| output | `anyPipeFree` | At least one IEX return pipe can accept a returned load. |
| output | `selectedPipeIndex` | Lowest-numbered free pipe when `drainReady` is true. |
| output | `drainReady` | Future drain permit: enabled, not flushed, sink valid, and pipe free. |
| output | `blockedByDisabled` | Sink entry exists while the permit owner is disabled. |
| output | `blockedByFlush` | Sink entry exists but flush suppresses drain. |
| output | `blockedByNoEntry` | Wrapper is enabled and not flushing, but the sink has no head entry. |
| output | `blockedByPipeFull` | Sink entry exists but every return pipe is occupied. |

## Logic Design

```text
pipeFreeMask = ~pipeOccupiedMask & allPipeMask
candidateValid = enable && !flush && sinkValid
drainReady = candidateValid && pipeFreeMask != 0
```

The selected pipe is the lowest free pipe. This is diagnostic only in the
current reduced top; the model's final `setMemData` mutation, pipe residency,
ROB destination writes, and branch-load resolution are deferred.

## Integration

The R319 top wiring uses:

- `LoadReplayReturnLretSink.drainValid` as `sinkValid`;
- reduced-store flush as `flush`;
- replay-LIQ wrapper enable as `enable`;
- a tied-full one-bit `pipeOccupiedMask`.

R382 drives `LoadReplayReturnLretSink.drainReady` from this permit's
`drainReady` output. The tied-full mask preserves the disabled-live-replay
contract, so the permit remains false and this packet alone cannot drain or
discard an LRET payload.

## Deferred Owners

- Real IEX return-pipe occupancy from scalar/vector return-pipe state.
- Allowing the tied-full permit to observe real free pipe capacity.
- `IEX::setMemData` effects on ROB rows, destination payload data, load-pair
  lane counts, TLOAD side effects, and load branch resolution.
- Multi-thread and multi-return-pipe queue arbitration beyond lowest-free-pipe
  diagnostics.

## Verification

Focused gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnIexDrainPermit
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnLretSink
bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop
FETCH_REDUCED_STORE_REPLAY_LIQ=1 BUILD_DIR=generated/r382x bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh
```

Reference tests cover pipe-free mask formation, lowest-free-pipe selection,
full-pipe blocking, disabled/flush/no-entry blocker ordering, masked occupancy
bits, and Chisel elaboration.
