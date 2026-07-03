# LoadReplayReturnPipeW2ReplayRowLifecycleCommitPermit

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2ReplayRowLifecycleCommitPermit.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/LoadReplayReturnPipeW2ReplayRowLifecycleCommitPermitSpec.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/lsu/load_unit/ldq.cpp`
    - `LDQInfo::returnData`
    - `LDQInfo::CheckMovRslvQ`
    - `ResolveQ::insert`
    - `ResolveQ::retired`
    - `ResolveQ::flush`
  - `model/LinxCoreModel/model/lsu/load_unit/ldq_cluster.cpp`
    - `LUEntryInfo::Reset`
- Related Chisel contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2RowFillEnableControl.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2ReplayRowLifecycleReady.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2ReplayRowLifecycleRequestControl.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2ReplayRowClearRequest.scala`
- Contract IDs: `LC-CHISEL-LSU-LRET-W2-371`

## Purpose

`LoadReplayReturnPipeW2ReplayRowLifecycleCommitPermit` is the R371 owner for
the future replay-row lifecycle clear commit permit. R369 selects whether the
lifecycle clear arm owns the LIQ clear port, and R367 proves the row-fill
bundle is atomically ready. This module names the handoff between those two
owners before R369 may turn the selected lifecycle arm into
`clearResolvedValid`.

The integrated top still keeps the R363 atomic live request disabled, so this
module cannot enable replay-row lifecycle mutation. It only replaces the direct
R367 `rowFillEnable` feed into the R369 selector with a named dormant permit.

## Interface

| Direction | Signal | Description |
|---|---|---|
| input | `enable` | Opt-in reduced replay-LIQ path enable. |
| input | `flush` | Suppresses commit permit during reduced-store flush. |
| input | `lifecycleClearSelected` | R369 selected the lifecycle clear arm after existing ResolveQ clear priority. |
| input | `rowFillEnable` | R367 atomic row-fill enable for the same resident W2 row. |
| input | `rowFillCandidateValid` | R367 active row-fill candidate diagnostic. |
| input | `lifecycleRowClearReady` | R368 found exactly one matching resolved LIQ row. |
| output | `commitCandidate` | Active selected lifecycle-clear commit candidate. |
| output | `rowFillPermit` | Active row-fill enable observed as a permit. |
| output | `lifecycleClearCommitEnable` | Permit fed to R369 `lifecycleClearCommitEnable`. |
| output | blocker outputs | Disabled, flush, no selection, missing row-fill enable, missing row-fill candidate, missing lifecycle row, and invalid row-fill-without-selection diagnostics. |

## Logic Design

The permit is deliberately a pure gate:

```text
active = enable && !flush
commitCandidate = active && lifecycleClearSelected
rowFillPermit = active && rowFillEnable
lifecycleClearCommitEnable = commitCandidate && rowFillEnable
```

`lifecycleClearSelected` is independent of the commit permit inside R369: it
depends on the lifecycle request, the matched resolved row, and existing-clear
priority. Feeding that selection into this owner therefore does not create a
selection loop. R369 still gates the final LIQ clear as:

```text
committedLifecycleClear = lifecycleClearSelected && lifecycleClearCommitEnable
```

The resulting behavior matches the previous direct `rowFillEnable` feed while
making the final commit permit observable and independently testable.

## Integration Notes

- R371 consumes R369 `lifecycleClearSelected`.
- R371 consumes R367 `rowFillEnable` and `candidateValid`.
- R371 feeds R369 `lifecycleClearCommitEnable`.
- R370 still owns the request arm, and R369 still owns existing-clear priority.
- The current top remains dormant because R363 keeps the atomic live request
  false, so R367 `rowFillEnable` remains false.

## Deferred Owners

- Live promotion of the R363 atomic request.
- Full replay-row lifecycle mutation once RF writeback, ROB/PE resolve,
  wakeup, row fill, LIQ clear, and W2 clear/refill can commit as one resident
  W2 operation.
- Multi-return-pipe lifecycle clear arbitration.

## Verification

Focused gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2ReplayRowLifecycleCommitPermit
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2ReplayRowClearRequest
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2RowFillEnableControl
bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop
```

Reference tests cover the fully selected commit, selected-without-row-fill
blocker, row-fill-without-selection invalid shape, missing row-fill candidate
and lifecycle row diagnostics, disabled/flush suppression, and Chisel
elaboration.
