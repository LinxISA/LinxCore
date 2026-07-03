# LoadReplayReturnPipeW2ResolveArbiterInput

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2ResolveArbiterInput.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/LoadReplayReturnPipeW2ResolveArbiterInputSpec.scala`
- Integrated user: `rtl/LinxCore/chisel/src/main/scala/linxcore/top/LinxCoreFrontendFetchRfAluTraceTop.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/iex/pipe/lda_pipe.cpp`
    - `LDAPipe::runW2`
  - `model/LinxCoreModel/model/iex/pipe/agu_pipe.cpp`
    - `AGUPipe::runW2`
  - `model/LinxCoreModel/model/ModelCommon/SimInstInfo.cpp`
    - `SimInstInfo::GenRslvBus`
  - `model/LinxCoreModel/model/ModelCommon/bus/PEResolveBus.h`
    - `PEResolveBus`
- Related Chisel contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2ResolveFirePayload.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2ResolveRequest.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2SideEffectFireVector.scala`
- Contract IDs: `LC-CHISEL-LSU-REPLAY-PIPE-W2-RESOLVE-ARBITER-INPUT-001`

## Purpose

`LoadReplayReturnPipeW2ResolveArbiterInput` names the final replay-load W2
resolve payload boundary before a future ROB/PE resolve owner can consume the
`PEResolveBus`-shaped side effect. R347 already fire-qualifies the W2 resolve
payload. R359 keeps the next handoff explicit: a valid resolve fire payload
becomes an arbiter candidate only when replay-LIQ is enabled and flush is
inactive, and it becomes a real resolve input only behind a separate live gate.

The current reduced top drives `liveEnable` from
`LoadReplayReturnPipeW2SideEffectLiveControl.resolveLiveEnable`. That shared
live-control request now comes from R363
`LoadReplayReturnPipeW2AtomicLiveRequestControl`, whose `requestEnable` remains
false, so this owner is diagnostic-only. It cannot mutate ROB/PE resolve state,
cannot publish branch or recovery side effects, and cannot advance replay-row
lifecycle.

## Interface

| Direction | Signal | Description |
|---|---|---|
| input | `enable` | Replay-LIQ integration is enabled. |
| input | `flush` | Current replay path is being flushed. |
| input | `liveEnable` | Future promotion gate for real replay resolve side effects. |
| input | `firePayloadValid` | R347 resolve fire payload is valid. |
| input | `fireTargetIsAgu` / `fireTargetIsLda` / `fireTargetPipeIndex` | Target route copied from the fire payload. |
| input | `fireBid` / `fireGid` / `fireRid` / `fireLoadLsId` | Resolve identity copied from the fire payload. |
| input | `firePc` / `fireAddr` / `fireSize` | Instruction and memory sidebands copied from the fire payload. |
| input | `fireDst` / `fireData` | Destination and returned data sidebands copied from the fire payload. |
| output | `active` | `enable && !flush`. |
| output | `candidateValid` | Active path has a fire payload ready for the resolve boundary. |
| output | `resolveValid` | Candidate is live-enabled for a future ROB/PE resolve write. |
| output | copied `resolve*` fields | Copied payload fields only when `resolveValid` is true. |
| output | `blockedByDisabled` | Fire payload arrived while replay-LIQ is disabled. |
| output | `blockedByFlush` | Fire payload arrived during flush. |
| output | `blockedByNoPayload` | Active path has no fire payload. |
| output | `blockedByLiveDisabled` | Candidate is valid but the live resolve gate is closed. |

## Logic Design

```text
active = enable && !flush
candidateValid = active && firePayloadValid
resolveValid = candidateValid && liveEnable
```

When `resolveValid` is false, all copied resolve payload fields are disabled or
zero. This prevents stale resolve identity from being mistaken for a future
live ROB/PE side effect.

## Integration

`LinxCoreFrontendFetchRfAluTraceTop` wires this module behind R347:

- `firePayloadValid` and copied fields come from
  `LoadReplayReturnPipeW2ResolveFirePayload`;
- `enable` follows the reduced replay-LIQ allocation enable;
- `flush` follows the reduced store/replay flush path;
- `liveEnable` comes from R357/R361
  `LoadReplayReturnPipeW2SideEffectLiveControl.resolveLiveEnable`, whose
  R363 request remains false until replay resolve mutation, RF writeback,
  ready-table wakeup, W2 clear, and replay-row lifecycle can be promoted
  atomically.

Top-level diagnostics are exposed under
`reducedLoadReplayLiqLretPipeW2ResolveArbiterInput*`.

## Deferred Owners

- Connecting replay resolve candidates to a live ROB/PE resolve owner.
- Scalar/vector PE/thread resolve routing beyond the reduced diagnostics.
- Branch/recovery side effects carried by full `PEResolveBus` metadata.
- Promotion of live replay resolve alongside W2 clear and replay-row lifecycle
  mutation.

## Verification

Focused gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2ResolveArbiterInput
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2ResolveFirePayload
bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop
FETCH_REDUCED_STORE_REPLAY_LIQ=1 BUILD_DIR=generated/r359-replay-pipe-w2-resolve-arbiter-input-xcheck bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh
```

Reference tests cover live-disabled candidate hold, live-enabled payload copy,
disabled/flush blockers, active no-payload diagnostics, and Chisel elaboration.
The R359 generated-RTL/QEMU cross-check manifest at
`generated/r359-replay-pipe-w2-resolve-arbiter-input-xcheck/report/crosscheck_manifest.json`
records `status: "pass"`, `comparator_status: 0`, `summary.compared_rows: 3`,
`summary.mismatch_count: 0`, and zero QEMU/DUT CBSTOP rows.
