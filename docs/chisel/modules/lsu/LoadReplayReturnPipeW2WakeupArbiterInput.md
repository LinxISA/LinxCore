# LoadReplayReturnPipeW2WakeupArbiterInput

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2WakeupArbiterInput.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/LoadReplayReturnPipeW2WakeupArbiterInputSpec.scala`
- Integrated user: `rtl/LinxCore/chisel/src/main/scala/linxcore/top/LinxCoreFrontendFetchRfAluTraceTop.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/iex/pipe/lda_pipe.cpp`
    - `WakeupScalarLocalLinks`
    - `LDAPipe::runW2`
  - `model/LinxCoreModel/model/iex/pipe/agu_pipe.cpp`
    - `WakeupScalarLocalLinks`
    - `AGUPipe::runW2`
  - `model/LinxCoreModel/model/iex/iex.cpp`
    - `IEX::setMemWakeup`
  - `model/LinxCoreModel/model/iex/iex_iq.cpp`
    - `IssueQueue::WakeupIQTag`
- Related Chisel contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2WakeupFirePayload.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2WakeupRequest.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2WakeupSinkReady.scala`
- Contract IDs: `LC-CHISEL-LSU-REPLAY-PIPE-W2-WAKEUP-ARBITER-INPUT-001`

## Purpose

`LoadReplayReturnPipeW2WakeupArbiterInput` names the final replay-load W2
wakeup payload boundary before a future ready-table/issue-wakeup owner can
consume the fire-qualified wakeup side effect. R349 already joins the R346
wakeup fire pulse with the R342 wakeup request payload. R360 keeps the next
handoff explicit: a valid wakeup fire payload becomes an arbiter candidate only
when replay-LIQ is enabled and flush is inactive, and it becomes a real wakeup
input only behind a separate live gate.

The current reduced top ties `liveEnable=false`, so this owner is
diagnostic-only. It cannot mutate ready-table state, cannot wake issue queues,
and cannot advance replay-row lifecycle.

## Interface

| Direction | Signal | Description |
|---|---|---|
| input | `enable` | Replay-LIQ integration is enabled. |
| input | `flush` | Current replay path is being flushed. |
| input | `liveEnable` | Future promotion gate for real ready-table/issue-wakeup side effects. |
| input | `firePayloadValid` | R349 wakeup fire payload is valid. |
| input | `fireReducedGprWakeup` / `fireNonGprWakeup` | Wakeup route class copied from the fire payload. |
| input | `fireTargetIsAgu` / `fireTargetIsLda` / `fireTargetPipeIndex` | Target route copied from the fire payload. |
| input | `fireBid` / `fireGid` / `fireRid` / `fireLoadLsId` | Wakeup identity copied from the fire payload. |
| input | `firePc` | Resident PC diagnostic copied from the fire payload. |
| input | `fireKind` / `fireArchTag` / `fireRelTag` / `firePhysTag` / `fireOldPhysTag` | Destination payload copied from the fire payload. |
| output | `active` | `enable && !flush`. |
| output | `candidateValid` | Active path has a fire payload ready for the wakeup boundary. |
| output | `wakeupValid` | Candidate is live-enabled for a future ready-table/issue-wakeup write. |
| output | copied `wakeup*` fields | Copied payload fields only when `wakeupValid` is true. |
| output | `blockedByDisabled` | Fire payload arrived while replay-LIQ is disabled. |
| output | `blockedByFlush` | Fire payload arrived during flush. |
| output | `blockedByNoPayload` | Active path has no fire payload. |
| output | `blockedByLiveDisabled` | Candidate is valid but the live wakeup gate is closed. |

## Logic Design

```text
active = enable && !flush
candidateValid = active && firePayloadValid
wakeupValid = candidateValid && liveEnable
```

When `wakeupValid` is false, all copied wakeup payload fields are disabled or
zero. This prevents stale destination identity from being mistaken for a future
live ready-table or issue-wakeup side effect.

## Integration

`LinxCoreFrontendFetchRfAluTraceTop` wires this module behind R349:

- `firePayloadValid` and copied fields come from
  `LoadReplayReturnPipeW2WakeupFirePayload`;
- `enable` follows the reduced replay-LIQ allocation enable;
- `flush` follows the reduced store/replay flush path;
- `liveEnable` is tied false until replay wakeup mutation, RF writeback,
  ROB/PE resolve, W2 clear, and replay-row lifecycle can be promoted
  atomically.

Top-level diagnostics are exposed under
`reducedLoadReplayLiqLretPipeW2WakeupArbiterInput*`.

## Deferred Owners

- Connecting replay wakeup candidates to a live ready-table/issue-wakeup owner.
- Reduced GPR versus scalar-local/non-GPR wakeup routing.
- Load spec-wakeup LPV/load-init sidebands.
- Promotion of live replay wakeup alongside W2 clear and replay-row lifecycle
  mutation.

## Verification

Focused gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2WakeupArbiterInput
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2WakeupFirePayload
bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop
FETCH_REDUCED_STORE_REPLAY_LIQ=1 BUILD_DIR=generated/r360-replay-pipe-w2-wakeup-arbiter-input-xcheck bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh
```

Reference tests cover live-disabled candidate hold, live-enabled payload copy,
disabled/flush blockers, active no-payload diagnostics, and Chisel elaboration.
The R360 generated-RTL/QEMU cross-check manifest at
`generated/r360-replay-pipe-w2-wakeup-arbiter-input-xcheck/report/crosscheck_manifest.json`
records `status: "pass"`, `comparator_status: 0`, `summary.compared_rows: 3`,
`summary.mismatch_count: 0`, and zero QEMU/DUT CBSTOP rows.
