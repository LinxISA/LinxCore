# LoadInflightRowMutationWriteControl

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadInflightRowMutationWriteControl.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/LoadInflightRowMutationWriteControlSpec.scala`
- Related Chisel contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadInflightRowMutationRequestBridge.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadInflightRowMutationApply.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadInflightQueue.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/mtccore/lsu/load_unit/ldq.cpp`
    - `MtcLDQInfo::handleSTQReceive`
    - `MtcLDQInfo::handleSCBReceive`
    - `MtcLDQInfo::waitStore`
    - `MtcLDQInfo::handleMerge`
  - `model/LinxCoreModel/model/mtccore/lsu/load_unit/ldq_cluster.cpp`
    - `MTCLUEntryInfo::rewait`
- Contract IDs: `LC-CHISEL-LSU-LIQ-ROW-MUTATION-WRITE-CONTROL-001`

## Purpose

`LoadInflightRowMutationWriteControl` is the admission boundary for a future
registered replay-STQ row mutation in `LoadInflightQueue`.

The C++ model applies an ordered STQ response only to a row that still exists
in `MTC_LDQ_REPICK` and has already observed the SCB side of the source-return
sequence. The current Chisel LIQ also has several same-cycle row writers:
E4 update, clear-resolved, replay wakeup, refill wakeup, launch, and
allocation. R414 keeps the precedence policy explicit by permitting a future
mutation write only when the target row evidence is valid and none of those
same-cycle writers is targeting the same row.

R414 is standalone. It does not write `LoadInflightQueue` storage and does not
enable `LoadReplaySourceReturnStoreSnapshotRowMutationRequest.liveEnable`.

## Interface

| Signal | Description |
|---|---|
| `enable` / `flush` | Gate the control decision. |
| `requestValid` | Native row-mutation request is present after the R413 bridge. |
| `targetRowValid` | Target LIQ row is resident. |
| `targetRowRepick` | Target row is still in the repick state. |
| `targetScbReturned` | Target row has already observed the SCB source return. |
| `*Conflict` | Same-cycle writer evidence for E4 update, clear-resolved, replay wakeup, refill, launch, or allocation to the same row. |
| `targetEvidenceValid` | Target row is valid, repick, and SCB-returned. |
| `writeConflict` | Any same-cycle row writer conflict is present. |
| `writeEnable` | Future registered mutation writer may update the row. |
| `blockedBy*` | Disabled, flush, no-request, target-evidence, and per-writer conflict diagnostics. |

## Logic Design

The control is combinational:

```text
active              = enable && !flush
requestActive       = active && requestValid
targetEvidenceValid = targetRowValid && targetRowRepick && targetScbReturned
writeConflict       = any same-cycle writer conflict
writeEnable         = requestActive && targetEvidenceValid && !writeConflict
```

The target evidence keeps the row-state proof local to the future writer:

- missing rows cannot be mutated;
- rows that left `Repick` cannot be mutated by a stale STQ response;
- STQ response mutation waits for the SCB-return side of the split source
  return contract.

The conflict inputs are deliberately separate instead of a packed mask so the
future `LoadInflightQueue` integration can preserve and test each writer
precedence boundary independently.

## Deferred Owners

- Composition with `LoadInflightRowMutationRequestBridge` and
  `LoadInflightRowMutationApply`.
- Registered `LoadInflightQueue` row mutation using `writeEnable`.
- Live promotion control for
  `LoadReplaySourceReturnStoreSnapshotRowMutationRequest.liveEnable`.

## Verification

Focused gate:

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadInflightRowMutationWriteControl
```

Reference tests cover permitted writes, invalid target rows, non-repick rows,
SCB-not-returned rows, each same-cycle writer conflict, disabled/flush/no-request
blockers, and Chisel elaboration.
