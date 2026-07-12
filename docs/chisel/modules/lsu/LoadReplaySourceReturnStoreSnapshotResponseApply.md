# LoadReplaySourceReturnStoreSnapshotResponseApply

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplaySourceReturnStoreSnapshotResponseApply.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/LoadReplaySourceReturnStoreSnapshotResponseApplySpec.scala`
- Integrated user: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplaySourceReturnStoreSnapshotPath.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/mtccore/lsu/load_unit/ldq.cpp`
    - `MtcLDQInfo::handleSTQReceive`
    - `MtcLDQInfo::waitStore`
    - `MtcLDQInfo::handleMerge`
  - `model/LinxCoreModel/model/mtccore/lsu/store_unit/stq.cpp`
    - `MtcSTQ::lookupForLoad`
  - `model/LinxCoreModel/model/ModelCommon/bus/MemReqBus.h`
    - `wait_store`, `wait_tpc`, `wait_bid`, `wait_rid`, `mtc_reqData`
- Contract IDs: `LC-CHISEL-LSU-REPLAY-STQ-SNAPSHOT-RESPONSE-APPLY-001`

## Purpose

`LoadReplaySourceReturnStoreSnapshotResponseApply` names the LIQ-side action
for an ordered local STQ snapshot response without mutating the row image yet.

The C++ model sequence in `handleSTQReceive` is:

1. Ignore responses whose target row is no longer `MTC_LDQ_REPICK`.
2. Require SCB return ordering before STQ response application.
3. Set `stqRnt`.
4. If `wait_store` is set, call `waitStore` and return before any data merge.
5. If `data_vld` is clear, return with only STQ-returned state.
6. Otherwise merge `mtc_reqData` into the row request data.

R407 maps that branch structure into a combinational Chisel intent surface.
The later LIQ row-mutation packet will consume this intent to update row
status, wait-store fields, valid masks, and line data.

R408 wires the row-image inputs from
`LoadReplaySourceReturnStoreSnapshotAcceptedToken`, so `rowLineData`,
`rowValidMask`, and `rowRequestMask` are delayed with the accepted local STQ
query instead of sampled from the current launch selector.

R409 consumes this intent in
`LoadReplaySourceReturnStoreSnapshotRowStatePlan`, which names the future
final row-state branch. `ResponseApply.stqReturned` remains an event-level
intent; for wait-store responses the row-state plan clears final `stqRnt`
again because the model immediately calls `rewait`.

## Interface

| Signal | Description |
|---|---|
| `enable` / `flush` | Gate all apply intent. |
| `orderedConsumed` | Response queue head was consumed as an ordered response after identity and SCB-order checks. |
| `targetRepick` | Reduced row-state proof says the targeted row is still in the repick state. |
| `targetOneHot` | One-hot target row from `ResponseHeadState`. |
| `response` | Typed STQ response payload from `ResponseQueue`. |
| `rowLineData` / `rowValidMask` | Current row byte image used to compute the future merged image. |
| `rowRequestMask` | Requested load bytes used to report whether the merge completes the request. |
| `applyValid` | Ordered, payload-valid response targeting a still-repick row. |
| `stqReturned` | Model `entry.stqRnt = true` equivalent; asserted for wait-store, data, and no-data responses. |
| `waitStoreApply` / `waitStoreInfo` / `waitStoreRid` | Wait-store rewait intent and preserved wait-store identity. |
| `dataMergeApply` | Data merge intent; suppressed whenever `waitStoreApply` is true. |
| `dataNoMerge` | Ordered STQ response with no wait-store and no data. |
| `mergedValidMask` / `mergedLineData` | Byte-merged row image for a future row-state writer. |
| `mergedRequestComplete` | The merged valid mask covers `rowRequestMask`. |
| `invalid*` | Malformed ordered payload diagnostics. |

## Logic Design

The module is combinational:

```text
applyCandidate = enable && !flush && orderedConsumed && response.valid
applyValid     = applyCandidate && targetRepick
stqReturned    = applyValid
waitStoreApply = applyValid && response.waitStore
dataMergeApply = applyValid && !response.waitStore && response.dataValid
dataNoMerge    = applyValid && !response.waitStore && !response.dataValid
```

When `waitStoreApply` is true, the output `LoadStoreForwardWait` is populated
from the response payload and `waitStoreRid` preserves the model RID sideband
that the existing `LoadStoreForwardWait` type does not carry.

R672-B also copies `waitStoreLsIdFullValid/waitStoreLsIdFull` without narrowing;
row-state planning and mutation preserve the parameterized authority.

When `dataMergeApply` is true, each `response.dataMask` byte lane replaces the
current `rowLineData` lane and the valid mask is ORed with the response mask.
The merge path intentionally ignores `rawDataValid`; model-visible merge is
controlled by `dataValid` after the wait-store priority rule.

## Deferred Owners

- Registered LIQ row mutation from the R409 row-state plan.
- Real row-carried `stqRnt` and `scbRnt` state instead of coarse
  `sourcesReturned`.
- Full raw external STQ response data payload wiring.

## Verification

Focused gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadReplaySourceReturnStoreSnapshotResponseApply
bash tools/chisel/run_chisel_tests.sh --only LoadReplaySourceReturnStoreSnapshotPath
```

Reference tests cover wait-store priority over raw data, data-byte merge,
no-data STQ-returned intent, disabled/flush/not-repick blockers, malformed
payload diagnostics, and Chisel elaboration.

R669 parameterizes the physical store capacity independently. The apply owner
copies the full `storeEntries`-sized row index into `LoadStoreForwardWait`
without changing ROB-sized BID, RID, or LSID identity fields.
