# LoadReplayReturnPipeW2ReplayRowLifecycleRequestControl

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2ReplayRowLifecycleRequestControl.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/LoadReplayReturnPipeW2ReplayRowLifecycleRequestControlSpec.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/lsu/load_unit/ldq.cpp`
    - `LDQInfo::returnData`
    - `LDQInfo::CheckMovRslvQ`
    - `ResolveQ::insert`
    - `ResolveQ::retired`
    - `ResolveQ::flush`
    - `LUEntryInfo::Reset`
- Related Chisel contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2AtomicLiveRequestControl.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2CommitRowCandidate.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2ReplayRowLifecycleReady.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2ReplayRowClearRequest.scala`
- Contract IDs: `LC-CHISEL-LSU-LRET-W2-370`

## Purpose

`LoadReplayReturnPipeW2ReplayRowLifecycleRequestControl` is the R370 owner for
the future replay-W2 replay-row lifecycle clear request arm. It replaces the
R369 top-level `false.B` lifecycle request tie-off with a named dormant
control point driven by the existing atomic W2 live request and the R366
row-fill candidate.

The integrated top still keeps `LoadReplayReturnPipeW2AtomicLiveRequestControl`
disabled, so this module cannot enable LIQ row clear, row fill, RF writeback,
ROB/PE resolve, issue wakeup, W2 clear/refill, or pipe residency mutation.

## Interface

| Direction | Signal | Description |
|---|---|---|
| input | `enable` | Opt-in reduced replay-LIQ path enable. |
| input | `flush` | Suppresses request control during reduced-store flush. |
| input | `atomicRequestActive` | R363 atomic W2 live request. Current top keeps this false. |
| input | `rowFillCandidateValid` | R366 commit-row candidate exists for the resident W2 row. |
| input | `lifecycleRowClearReady` | R368 found exactly one matching resolved LIQ row. |
| output | `active` | `enable && !flush`. |
| output | `requestCandidate` | Atomic request plus row-fill candidate under `active`. |
| output | `lifecycleClearRequestEnable` | Request arm passed to R369 clear-request selector. |
| output | blocker outputs | Disabled, flush, inactive request, missing row-fill candidate, and missing lifecycle-row diagnostics. |

## Logic Design

The request owner is deliberately smaller than the clear selector:

```text
active = enable && !flush
requestCandidate = active && atomicRequestActive && rowFillCandidateValid
lifecycleClearRequestEnable = requestCandidate
```

`lifecycleRowClearReady` is not used to suppress the outgoing request. If the
atomic request and row-fill candidate are present but no resolved LIQ row
matches, R369 can still report `blockedByNoLifecycleRow` at the clear-selector
boundary. This keeps request ownership separate from clear-port arbitration.

## Integration Notes

- R370 drives `LoadReplayReturnPipeW2ReplayRowClearRequest.lifecycleClearRequestEnable`.
- R369 still gives the existing ResolveQ delayed clear priority over lifecycle
  clear.
- R369 still gates any lifecycle `clearResolvedValid` by the R367
  `rowFillEnable`, so this module alone cannot clear a row.
- R363 keeps `atomicRequestActive` false in the integrated top, so the entire
  lifecycle-clear path remains dormant.

## Deferred Owners

- Live promotion of `LoadReplayReturnPipeW2AtomicLiveRequestControl.requestEnable`.
- Proving row fill, LIQ clear, W2 clear/refill, RF writeback, ROB/PE resolve,
  and issue wakeup as one atomic W2 operation.
- Full default-top replay row lifecycle mutation.

## Verification

Focused gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2ReplayRowLifecycleRequestControl
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2ReplayRowClearRequest
bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop
```

Reference tests cover request arming, inactive atomic request, missing
row-fill candidate, missing lifecycle row diagnostics, disabled/flush
suppression, and Chisel elaboration.
