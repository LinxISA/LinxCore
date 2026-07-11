# LoadInflightRowMutationPath

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadInflightRowMutationPath.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/LoadInflightRowMutationPathSpec.scala`
- Related Chisel contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadInflightRowMutationRequestBridge.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadInflightRowMutationWriteControl.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadInflightRowMutationApply.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplaySourceReturnStoreSnapshotRowMutationRequest.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadInflightQueue.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/mtccore/lsu/load_unit/ldq.cpp`
    - `MtcLDQInfo::handleSTQReceive`
    - `MtcLDQInfo::handleSCBReceive`
    - `MtcLDQInfo::waitStore`
    - `MtcLDQInfo::handleMerge`
  - `model/LinxCoreModel/model/mtccore/lsu/load_unit/ldq_cluster.cpp`
    - `MTCLUEntryInfo::rewait`
- Contract IDs: `LC-CHISEL-LSU-LIQ-ROW-MUTATION-PATH-001`

## Purpose

`LoadInflightRowMutationPath` composes the three standalone row-mutation
owners built in R412-R414:

- `LoadInflightRowMutationRequestBridge` converts the R410 path-local request
  into the LIQ-native wait-store shape and blocks illegal payloads.
- `LoadInflightRowMutationWriteControl` admits the write only when the target
  row is still valid, `Repick`, SCB-returned, and free of same-cycle writer
  conflicts.
- `LoadInflightRowMutationApply` previews the next LIQ row image.

R635 adds explicit policy inputs for canonical MDB waits. MDB mutation may
target a resident `Wait` or `Repick` row and may waive prior SCB-return
evidence because lookup is issued at allocation before first launch. Legacy
source-return mutation keeps the stricter Repick plus SCB evidence policy.
Both policies retain every same-cycle writer exclusion.

R415 introduced this as a standalone combinational boundary. R416 also
instantiates it inside `LoadInflightQueue` with `sourceStoreEntries` equal to
the queue's native `storeEntries` shape so the queue can own the registered
write. The source-shaped R410 replay snapshot request is still not
live-connected, and generated-top replay behavior remains unchanged.

## Interface

| Signal | Description |
|---|---|
| `enable` / `flush` | Gate the bridge, write-control, and apply stages. |
| `requestValid` | R410 row-mutation request is present. |
| `requestTargetMask` / `requestTargetIndex` | One-hot LIQ target and encoded row index carried through from the request owner. |
| `row` | Current LIQ row image selected by the future registered writer. |
| `setWaitStatus` / `keepRepickStatus` | Status intent for wait-store rewait or continued repick. |
| `clearReturnState` | Clears coarse and split source-return state for wait-store rewait. |
| `lineWrite` / `waitStoreWrite` | Enables line-image and wait-store updates in the apply preview. |
| `nextWaitStore` / `nextWaitStoreInfo` | Source-shaped wait-store payload before native LIQ narrowing. |
| `nextLineData` / `nextValidMask` / `nextDataComplete` | Future row line image and completion state. |
| `nextScbReturned` / `nextStqReturned` / `nextStoreSourceReturned` | Split and coarse source-return state for non-rewait data merge/no-data return. |
| `allowWaitTarget` / `requireScbReturned` | Explicit admission policy. Canonical MDB uses `true/false`; legacy source-return mutation uses `false/true`. |
| `*Conflict` | Same-row conflict evidence for current registered LIQ writers: E4 update, clear-resolved, replay wakeup, refill, launch, and allocation. |
| `bridgeValid` | Payload survived the bridge shape and store-index checks. |
| `requestTargetMaskOut` / `requestTargetIndexOut` | Gated target identity from the bridge. |
| `nativeStoreIndexOut` / `sourceStoreIndexFits` | Native store-index diagnostic from the bridge. |
| `targetRowValid` / `targetRowRepick` / `targetScbReturned` | Row evidence consumed by write control. |
| `targetEvidenceValid` / `writeConflict` / `writeEnable` | Write-control decision and diagnostics. |
| `applyValid` / `nextRow` | Row-image apply result; `nextRow` is unchanged when no write is admitted. |
| `blockedByBridge` / `blockedByControl` / `blockedByApply` | Stage-level stop diagnostics. |
| `bridge*`, `control*`, `apply*` diagnostics | Forwarded per-owner blocker and invalid-shape diagnostics. |

## Logic Design

The path is purely combinational:

```text
bridgeValid = bridge(enable, flush, request).valid
status_ok   = row.status == Repick ||
              (allowWaitTarget && row.status == Wait)
evidence_ok = !requireScbReturned || row.scbReturned
writeEnable = bridgeValid && row.valid && status_ok && evidence_ok &&
              no_same_cycle_row_writer_conflict
applyValid  = apply(enable, flush, writeEnable, row, bridge.native_request).valid
nextRow     = apply.nextRow
```

The bridge is the only owner that may narrow a source-store index into the
native LIQ store-entry domain. The write-control stage receives only
`bridgeValid` requests, so malformed payloads cannot reach the row-image apply
preview. The apply stage receives only `writeEnable`, so target evidence and
same-cycle writer conflicts block mutation before row fields are rewritten.

This preserves the LinxCoreModel ordering rule that an ordered STQ response may
mutate only the row named by the accepted query while it is still in the repick
state and after the SCB side has already returned. Hardware-specific conflicts
remain explicit because the registered Chisel LIQ has additional same-cycle
writers that are not represented as separate callbacks in the C++ model.

## Deferred Owners

- Live source-shaped connection from
  `LoadReplaySourceReturnStoreSnapshotRowMutationRequest` into the native
  `LoadInflightQueue` row-mutation port.
- Live promotion control for
  `LoadReplaySourceReturnStoreSnapshotRowMutationRequest.liveEnable`.
- Replacement of coarse `sourcesReturned` launch readiness with row-owned split
  SCB/STQ return readiness after registered mutation is cross-checked.
- Generated-top/QEMU replay proof after the path is wired into the stateful LIQ
  owner.

## Verification

Focused gate:

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadInflightRowMutationPath
```

Adjacent gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadInflightRowMutationRequestBridge
bash tools/chisel/run_chisel_tests.sh --only LoadInflightRowMutationWriteControl
bash tools/chisel/run_chisel_tests.sh --only LoadInflightRowMutationApply
```

Reference tests cover wait-store rewait composition, data-merge composition,
out-of-range wait-store blocking before control/apply, target-evidence and
same-cycle writer conflict blocking before apply, and Chisel elaboration.
