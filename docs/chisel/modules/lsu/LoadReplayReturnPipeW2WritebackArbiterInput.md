# LoadReplayReturnPipeW2WritebackArbiterInput

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2WritebackArbiterInput.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/LoadReplayReturnPipeW2WritebackArbiterInputSpec.scala`
- Integrated user: `rtl/LinxCore/chisel/src/main/scala/linxcore/top/LinxCoreFrontendFetchRfAluTraceTop.scala`
- Related Chisel contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2WritebackFirePayload.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2WritebackRequest.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2SideEffectFireVector.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/execute/ReducedScalarWritebackArbiter.scala`
- Contract IDs: `LC-CHISEL-LSU-REPLAY-PIPE-W2-WRITEBACK-ARBITER-INPUT-001`

## Purpose

`LoadReplayReturnPipeW2WritebackArbiterInput` names the final replay-load W2
writeback payload boundary before a future owner can feed the scalar RF
writeback arbiter. R348 already fire-qualifies the shaped W2 writeback payload.
R358 keeps the next handoff explicit: a valid fire payload becomes an arbiter
candidate only when replay-LIQ is enabled and flush is inactive, and it becomes
a real write only behind a separate live gate.

The current reduced top drives `liveEnable` from
`LoadReplayReturnPipeW2SideEffectLiveControl.writebackLiveEnable`. R362 also
feeds this boundary into the replay side of `ReducedScalarWritebackArbiter`.
That shared live-control owner still has `liveRequested=false`, so the RF
arbiter cannot select replay writeback, cannot contend with execute writeback,
and cannot advance replay-row lifecycle.

## Interface

| Direction | Signal | Description |
|---|---|---|
| input | `enable` | Replay-LIQ integration is enabled. |
| input | `flush` | Current replay path is being flushed. |
| input | `liveEnable` | Future promotion gate for real replay RF writes. |
| input | `firePayloadValid` | R348 writeback fire payload is valid. |
| input | `firePhysTag` | Reduced scalar physical register tag from the fire payload. |
| input | `fireData` | Returned scalar data from the fire payload. |
| output | `active` | `enable && !flush`. |
| output | `candidateValid` | Active path has a fire payload ready for the arbiter boundary. |
| output | `writeValid` | Candidate is live-enabled for a future scalar RF write. |
| output | `writeTag` / `writeData` | Copied payload fields only when `writeValid` is true. |
| output | `blockedByDisabled` | Fire payload arrived while replay-LIQ is disabled. |
| output | `blockedByFlush` | Fire payload arrived during flush. |
| output | `blockedByNoPayload` | Active path has no fire payload. |
| output | `blockedByLiveDisabled` | Candidate is valid but the live RF write gate is closed. |

## Logic Design

```text
active = enable && !flush
candidateValid = active && firePayloadValid
writeValid = candidateValid && liveEnable
```

When `writeValid` is false, `writeTag` and `writeData` are driven to zero so no
stale payload can be mistaken for a future arbiter request.

## Integration

`LinxCoreFrontendFetchRfAluTraceTop` wires this module behind R348:

- `firePayloadValid`, `firePhysTag`, and `fireData` come from
  `LoadReplayReturnPipeW2WritebackFirePayload`;
- `enable` follows the reduced replay-LIQ allocation enable;
- `flush` follows the reduced store/replay flush path;
- `liveEnable` comes from R357/R361
  `LoadReplayReturnPipeW2SideEffectLiveControl.writebackLiveEnable`, whose
  request remains false until replay RF writeback, ready-table wakeup, W2
  clear, and replay-row lifecycle can be promoted atomically;
- R362 connects `candidateValid`, `writeTag`, and `writeData` to the replay
  side of `ReducedScalarWritebackArbiter`, with that arbiter's `replayEnable`
  driven by the same shared live-control output.

Top-level diagnostics are exposed under
`reducedLoadReplayLiqLretPipeW2WritebackArbiterInput*`, while the final
single-port RF arbitration diagnostics remain under
`reducedLoadReplayLiqWritebackArbiter*`.

## Deferred Owners

- Enabling replay write candidates in `ReducedScalarWritebackArbiter`.
- Coordinating RF writeback selection with ready-table updates in the same
  cycle.
- Promotion of live replay RF writes alongside W2 clear and replay-row
  lifecycle mutation.

## Verification

Focused gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2WritebackArbiterInput
bash tools/chisel/run_chisel_tests.sh --only ReducedScalarWritebackArbiter
bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop
FETCH_REDUCED_STORE_REPLAY_LIQ=1 BUILD_DIR=generated/r362-replay-pipe-w2-writeback-rf-arbiter-xcheck bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh
```

Reference tests cover live-disabled candidate hold, live-enabled payload copy,
disabled/flush blockers, active no-payload diagnostics, and Chisel elaboration.
The R358 generated-RTL/QEMU fixture manifest passed with three compared rows,
zero mismatches, and no CBSTOP inflation.
