# LoadInflightRowMutationApply

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadInflightRowMutationApply.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/LoadInflightRowMutationApplySpec.scala`
- Related Chisel contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadInflightQueue.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplaySourceReturnStoreSnapshotRowMutationRequest.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/mtccore/lsu/load_unit/ldq_cluster.cpp`
    - `MTCLUEntryInfo::rewait`
  - `model/LinxCoreModel/model/mtccore/lsu/load_unit/ldq.cpp`
    - `MtcLDQInfo::handleSTQReceive`
    - `MtcLDQInfo::waitStore`
    - `MtcLDQInfo::handleMerge`
- Contract IDs: `LC-CHISEL-LSU-LIQ-ROW-MUTATION-APPLY-001`

## Purpose

`LoadInflightRowMutationApply` is the concrete LIQ row-image apply preview for
the R410 row-mutation request shape. It does not own registered
`LoadInflightQueue` storage. Instead, it consumes one current `LoadInflightRow`
image plus one future mutation request and returns the row image that a later
registered writer may commit.

This keeps the row-write semantics testable before wiring live STQ response
application into the stateful LIQ owner.

## Interface

| Signal | Description |
|---|---|
| `enable` / `flush` | Gate the apply preview. |
| `requestValid` | Future row-mutation request is active. |
| `row` | Current LIQ row image selected by the future registered writer. |
| `setWaitStatus` / `keepRepickStatus` | Status update intent for wait-store rewait or continued repick. |
| `clearReturnState` | Clears coarse and split source-return state for rewait. |
| `lineWrite` | Writes `lineData`, `validMask`, and `dataComplete`. |
| `waitStoreWrite` | Writes wait-store state from `nextWaitStore*`. |
| `nextScbReturned` / `nextStqReturned` / `nextStoreSourceReturned` | Final split and coarse source-return state. |
| `applyValid` | Request is enabled, row is valid `Repick`, and payload shape is legal. |
| `nextRow` | Preview of the row image after applying the request; unchanged when `applyValid=false`. |
| `blockedBy*` | Disabled, flush, no-request, invalid-row, and not-repick blockers. |
| `invalid*` | Conflicting status, wait-store-without-wait-status, and source-return-without-both-split-bits diagnostics. |

## Logic Design

The module is combinational:

```text
active     = enable && !flush
applyValid = active && requestValid && row.valid &&
             row.status == Repick && legal_payload_shape
```

When `applyValid` is false, `nextRow` is the unmodified input row. When it is
true, status, return-state, line-data, and wait-store fields are updated from
the request. Identity, PC, address, destination, source traces, and load/store
ordering fields are preserved from the original row.

The payload guards mirror the model constraints already captured by R410:

- a row cannot be set to both wait and repick;
- a wait-store payload must also request wait status;
- coarse returned-source state must imply both split SCB and STQ return bits.

## Deferred Owners

- Registered `LoadInflightQueue` row mutation using this preview.
- Conversion from the R410 path-local `idEntries` wait-store shape to the
  native LIQ `storeEntries` wait-store shape.
- Live promotion control for `LoadReplaySourceReturnStoreSnapshotPath`
  `rowMutationRequest.liveEnable`.
- Replacement of coarse `sourcesReturned` readiness with row-owned split-bit
  readiness after the registered writer is cross-checked.

## Verification

Focused gate:

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadInflightRowMutationApply
```

Reference tests cover wait-store rewait, data-merge/no-rewait, missing or
non-repick rows, invalid request shapes, and Chisel elaboration.
