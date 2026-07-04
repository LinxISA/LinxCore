# LoadReplaySourceReturnStoreSnapshotRowStatePlan

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplaySourceReturnStoreSnapshotRowStatePlan.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/LoadReplaySourceReturnStoreSnapshotRowStatePlanSpec.scala`
- Integrated user: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplaySourceReturnStoreSnapshotPath.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/mtccore/lsu/load_unit/ldq_cluster.cpp`
    - `MTCLUEntryInfo::rewait`
  - `model/LinxCoreModel/model/mtccore/lsu/load_unit/ldq.cpp`
    - `MtcLDQInfo::pickL1`
    - `MtcLDQInfo::handleSTQReceive`
    - `MtcLDQInfo::waitStore`
    - `MtcLDQInfo::handleMerge`
- Contract IDs: `LC-CHISEL-LSU-REPLAY-STQ-SNAPSHOT-ROW-STATE-PLAN-001`

## Purpose

`LoadReplaySourceReturnStoreSnapshotRowStatePlan` names the future row-state
write after an ordered local STQ snapshot response has been decoded by
`LoadReplaySourceReturnStoreSnapshotResponseApply`.

The C++ model sets `entry.stqRnt = true` when an ordered STQ response targets a
row still in `MTC_LDQ_REPICK`, but the final row state depends on the response
class:

1. A `wait_store` response calls `waitStore`, which changes the row back to
   `MTC_LDQ_WAIT`, records wait-store identity, and calls `rewait`.
   `rewait` clears `ldqRnt`, `l1Rnt`, `scbRnt`, `stqRnt`, and the accumulated
   request data.
2. A data response keeps the row in repick state, preserves the already-returned
   SCB source, sets final `stqRnt`, and uses the merged request-data image.
3. A no-data response still sets final `stqRnt`, but leaves the accepted row
   data image unchanged.

R409 captures that branch structure as a combinational plan only. It does not
mutate `LoadInflightQueue` rows.

R427 wires the path-level `priorScbReturned` input from the reduced response
head's row mask proof as well as the external SCB-return input. That keeps this
plan aligned with the same `scbRnt` evidence that allowed the response matcher
to consume the ordered STQ response.

## Interface

| Signal | Description |
|---|---|
| `enable` / `flush` | Gate all row-state planning. |
| `applyValid` / `applyStqReturned` | Ordered response apply event from `ResponseApply`; both must be true for a row plan. |
| `waitStoreApply` | The response asks the row to wait on an older store and rewait. |
| `waitStoreInfo` / `waitStoreRid` | Wait-store identity copied into the future row write. |
| `dataMergeApply` / `dataNoMerge` | Non-wait response class. |
| `priorScbReturned` / `priorStqReturned` | Current split source-return bits. `priorScbReturned` must already be true for an STQ apply. |
| `priorLineData` / `priorValidMask` / `priorRequestComplete` | Accepted-row data image before STQ response application. |
| `mergedLineData` / `mergedValidMask` / `mergedRequestComplete` | Data-merge image from `ResponseApply`. |
| `rewaitApply` | Final row state is wait-store rewait. |
| `dataMergePlan` / `dataNoMergePlan` | Final row state remains repick with final `stqRnt` set. |
| `nextScbReturned` / `nextStqReturned` | Final split source-return bits for a future row writer. |
| `nextStoreSourceReturned` | Store-side source completion, `nextScbReturned && nextStqReturned`, for non-rewait plans. |
| `nextLineData` / `nextValidMask` / `nextDataComplete` | Final row data image. Wait-store rewait clears the image; data merge uses the merged image; no-data preserves the accepted image. |
| `setWaitStatus` / `keepRepickStatus` | Future status-write intent. |
| `invalid*` | Malformed or impossible response-class diagnostics. |

## Logic Design

The plan is combinational:

```text
planValid    = enable && !flush && applyValid && applyStqReturned
rewaitApply  = planValid && waitStoreApply
dataMerge    = planValid && !waitStoreApply && dataMergeApply
dataNoMerge  = planValid && !waitStoreApply && dataNoMerge
```

For `rewaitApply`, the final row image is cleared and both split store-source
bits are false. For non-wait plans, `nextScbReturned` preserves the prior SCB
bit, `nextStqReturned` becomes true, and the row either takes the merged data
image or preserves the accepted image.

`invalidStqApplyWithoutScb` is asserted when a plan is formed without the SCB
return bit. This mirrors the model `ASSERT(entry.scbRnt)` in
`handleSTQReceive`; it is diagnostic-only until the registered row writer owns
the assertion policy. In the reduced composite, R427 sources this bit from the
targeted row's `rowScbReturnedMask` proof when available.

## Deferred Owners

- Registered `LoadInflightQueue` mutation from this plan.
- Real row-carried `scbRnt`/`stqRnt` bits instead of the current coarse
  `sourcesReturned` diagnostic.
- Full launch-readiness replacement that joins base-data return with
  `nextStoreSourceReturned`.

## Verification

Focused gate:

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadReplaySourceReturnStoreSnapshotRowStatePlan
```

Reference tests cover wait-store rewait clearing, data merge, no-data STQ
return, disabled/flush/no-apply blockers, malformed response classes, and
Chisel elaboration.
