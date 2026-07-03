# LoadReplayReturnPipeW2ReplayRowClearRequest

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2ReplayRowClearRequest.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/LoadReplayReturnPipeW2ReplayRowClearRequestSpec.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/lsu/load_unit/ldq.cpp`
    - `LDQInfo::returnData`
    - `LDQInfo::CheckMovRslvQ`
    - `ResolveQ::insert`
  - `model/LinxCoreModel/model/lsu/load_unit/ldq_cluster.cpp`
    - `LUEntryInfo::Reset`
- Related Chisel contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadInflightQueue.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/ReducedLoadReplayLiqAllocPath.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2ReplayRowLifecycleReady.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2ReplayRowLifecycleCommitPermit.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2RowFillEnableControl.scala`
- Contract IDs: `LC-CHISEL-LSU-REPLAY-W2-ROW-CLEAR-REQ-001`

## Purpose

`LoadReplayReturnPipeW2ReplayRowClearRequest` is the R369 owner for the
`LoadInflightQueue.clearResolvedValid/index` request selection point. It keeps
the existing ResolveQ delayed clear path as the priority request, exposes a
future replay-W2 lifecycle clear arm, and prevents that lifecycle arm from
mutating LIQ until row-fill commit enable is also true.

R370 drives `lifecycleClearRequestEnable` from
`LoadReplayReturnPipeW2ReplayRowLifecycleRequestControl`, whose upstream
atomic live request remains disabled in the integrated top. R371 drives
`lifecycleClearCommitEnable` from
`LoadReplayReturnPipeW2ReplayRowLifecycleCommitPermit`, which requires the
selected lifecycle arm plus R367 row-fill enable. Therefore this selector still
does not enable replay-row lifecycle mutation, replay ROB row fill, W2
clear/refill promotion, RF writeback, ROB/PE resolve mutation, ready-table
wakeup, or issue wakeup.

## Interface

| Direction | Signal | Description |
|---|---|---|
| input | `enable` | Replay-LIQ reduced path enable. |
| input | `flush` | Suppresses all clear candidates. |
| input | `existingClearValid` | Existing ResolveQ delayed clear request. |
| input | `existingClearIndex` | LIQ row selected by the existing clear request. |
| input | `lifecycleClearRequestEnable` | Future live arm for replay-W2 lifecycle clear. R370 now owns this request and keeps it dormant through the disabled atomic live request. |
| input | `lifecycleClearCommitEnable` | R371 lifecycle commit permit. Lifecycle clear can only drive LIQ when this is true. |
| input | `lifecycleRowClearReady` | R368 proof that exactly one resolved LIQ row matches the resident W2 slot. |
| input | `lifecycleRowClearIndex` | R368 resolved LIQ row index. |
| input | `clearResolvedAccepted` | Response from `LoadInflightQueue` after the selected request is driven. |
| output | `lifecycleClearEnable` | Readiness exported to R368. It is true only when the lifecycle request is live, the row is ready, and the existing clear path is not using the LIQ clear port. |
| output | `clearResolvedValid` / `clearResolvedIndex` | Selected request driven to `LoadInflightQueue`. |
| output | `existingClearAccepted` | Accepted response for the existing ResolveQ delayed clear request. |
| output | `lifecycleClearAccepted` | Accepted response for the future lifecycle clear request. |
| output | blocker diagnostics | Disabled, flush, existing-clear priority, missing lifecycle row, request-disabled, and commit-disabled blockers. |

## Logic Design

The selection rule is intentionally small:

```text
existingClearCandidate = active && existingClearValid
lifecycleClearCandidate =
  active && lifecycleClearRequestEnable && lifecycleRowClearReady
lifecycleClearSelected = lifecycleClearCandidate && !existingClearCandidate
lifecycleClearEnable = lifecycleClearSelected
committedLifecycleClear = lifecycleClearSelected && lifecycleClearCommitEnable
clearResolvedValid = existingClearCandidate || committedLifecycleClear
```

The existing ResolveQ delayed clear has priority because it already owns the
older resolved-row-to-ResolveQ movement path. The lifecycle arm is split into
readiness and mutation:

- `lifecycleClearEnable` can satisfy the R368 lifecycle prerequisite for row
  fill once a future live request is armed;
- `clearResolvedValid` for the lifecycle arm still waits for
  `lifecycleClearCommitEnable`, which R371 derives from the selected lifecycle
  arm and R367 row-fill enable.

This avoids clearing a resolved LIQ row merely because the row identity matched
while another atomic W2 prerequisite is still blocked.

## Integration

`LinxCoreFrontendFetchRfAluTraceTop` routes
`ReducedLoadReplayLiqAllocPath.clearResolvedValid/index` through this owner.
The old `reducedLoadReplayResolveClearPending` register now clears from
`existingClearAccepted`, so a future lifecycle clear cannot accidentally
consume the ResolveQ delayed-clear pending bit.
The lifecycle arm's commit input comes from
`LoadReplayReturnPipeW2ReplayRowLifecycleCommitPermit`; this keeps request
selection and final commit permission as separate top-visible diagnostics.

## Deferred Owners

- Live `LoadReplayReturnPipeW2ReplayRowLifecycleRequestControl` promotion
  through the R363 atomic live-request arm.
- Atomic live replay RF writeback, ROB/PE resolve, wakeup, row fill, LIQ
  lifecycle clear, and W2 clear/refill mutation in one W2 operation.

## Verification

Focused gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2ReplayRowClearRequest
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2ReplayRowLifecycleCommitPermit
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2ReplayRowLifecycleReady
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2RowFillEnableControl
bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop
```

The generated RTL/QEMU cross-check must be rerun when this owner changes,
because it directly selects the LIQ clear request driven by the reduced top.
