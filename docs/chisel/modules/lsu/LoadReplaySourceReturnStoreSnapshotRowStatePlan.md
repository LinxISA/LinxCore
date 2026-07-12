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
  - `model/LinxCoreModel/model/lsu/load_unit/ldq.cpp`
    - `LDQInfo::handleSTQReceive`
    - `LDQInfo::receiveData`
  - `model/LinxCoreModel/model/lsu/load_unit/ldq_cluster.cpp`
    - `LUEntryInfo::rewait`
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
3. A no-data response that already has complete requested bytes sets final
   `stqRnt` and leaves the accepted row data image unchanged.
4. A no-data response that does not have complete requested bytes follows the
   model return loop into a rewait/miss-retry shape: the row goes back to
   `WAIT`, the accumulated source-return bits are cleared, and the row data
   image is cleared.

R409 captures that branch structure as a combinational plan only. It does not
mutate `LoadInflightQueue` rows. R543 corrects the complete no-data branch to
assert `lineWrite` with the accepted row image; otherwise a row can become
source-returned while retaining an old or empty valid-byte mask, which prevents
the complete-Repick selector from observing request completion.

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
| `priorLineData` / `priorValidMask` / `priorRequestComplete` | Accepted-row data image before STQ response application. `priorRequestComplete` decides whether a no-data STQ response can remain `Repick` or must rewait. |
| `mergedLineData` / `mergedValidMask` / `mergedRequestComplete` | Data-merge image from `ResponseApply`. |
| `rewaitApply` | Final row state is rewait. This covers explicit wait-store responses and incomplete no-data responses. |
| `dataMergePlan` / `dataNoMergePlan` | Non-wait response class. `dataNoMergePlan` is still true for an incomplete no-data response, but `rewaitApply` selects the final wait-state rewrite. |
| `nextScbReturned` / `nextStqReturned` | Final split source-return bits for a future row writer. |
| `nextStoreSourceReturned` | Store-side source completion, `nextScbReturned && nextStqReturned`, for non-rewait plans. |
| `nextLineData` / `nextValidMask` / `nextDataComplete` | Final row data image. Rewait clears the image; data merge uses the merged image; complete no-data preserves the accepted image. |
| `setWaitStatus` / `keepRepickStatus` | Future status-write intent. |
| `invalid*` | Malformed or impossible response-class diagnostics. |

## Logic Design

The plan is combinational:

```text
planValid         = enable && !flush && applyValid && applyStqReturned
dataNoMergePlan   = planValid && !waitStoreApply && dataNoMerge
dataNoMergeRewait = dataNoMergePlan && !priorRequestComplete
rewaitApply       = planValid && (waitStoreApply || dataNoMergeRewait)
dataMergePlan     = planValid && !waitStoreApply && dataMergeApply
```

For `rewaitApply`, the final row image is cleared and both split store-source
bits are false. Wait-store rewait also writes the wait-store identity; no-data
rewait clears return state without recording a wait-store dependency. For
non-rewait plans, `nextScbReturned` preserves the prior SCB bit,
`nextStqReturned` becomes true, and the row either takes the merged data image
or writes the accepted complete image.

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

Reference tests cover wait-store rewait clearing, data merge, complete no-data
STQ return, incomplete no-data rewait clearing, disabled/flush/no-apply
blockers, malformed response classes, and Chisel elaboration.

R669 gives the plan an independent `storeEntries` parameter. Wait-store row
selectors retain physical STQ width through the planned next-row image, while
the associated ordering identities stay ROB-sized.
