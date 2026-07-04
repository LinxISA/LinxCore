# LoadInflightRowMutationRequestBridge

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadInflightRowMutationRequestBridge.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/LoadInflightRowMutationRequestBridgeSpec.scala`
- Related Chisel contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplaySourceReturnStoreSnapshotRowMutationRequest.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadInflightRowMutationApply.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadInflightQueue.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/mtccore/lsu/load_unit/ldq_cluster.cpp`
    - `MTCLUEntryInfo::rewait`
  - `model/LinxCoreModel/model/mtccore/lsu/load_unit/ldq.cpp`
    - `MtcLDQInfo::handleSTQReceive`
    - `MtcLDQInfo::waitStore`
    - `MtcLDQInfo::handleMerge`
- Contract IDs: `LC-CHISEL-LSU-LIQ-ROW-MUTATION-REQUEST-BRIDGE-001`

## Purpose

`LoadInflightRowMutationRequestBridge` converts the live-capable R410 replay
source-return row-mutation request shape into the LIQ-native request shape
expected by the R412 row-image apply preview and a future registered
`LoadInflightQueue` writer.

The important ownership split is the wait-store payload width. The replay path
currently carries `nextWaitStoreInfo` as
`LoadStoreForwardWait(idEntries, sourceStoreEntries, pcWidth)`, while a real
LIQ row stores `LoadStoreForwardWait(idEntries, storeEntries, pcWidth)`. This
bridge checks that the source store index fits the native LIQ store-entry
domain before narrowing and forwarding the request.

R413 is still standalone. It does not wire registered row mutation into
`LoadInflightQueue`, does not enable
`LoadReplaySourceReturnStoreSnapshotRowMutationRequest.liveEnable`, and does
not change generated-top behavior.

## Interface

| Signal | Description |
|---|---|
| `enable` / `flush` | Gate all bridge outputs. |
| `requestValid` | R410 row-mutation request is active. |
| `requestTargetMask` / `requestTargetIndex` | One-hot LIQ target and encoded row index from the request owner. |
| `setWaitStatus` / `keepRepickStatus` | Status write intent passed to the native apply owner. |
| `clearReturnState` | Clears coarse and split source-return state for wait-store rewait. |
| `lineWrite` / `waitStoreWrite` | Future row-data and wait-store write enables. |
| `nextWaitStore` / `nextWaitStoreInfo` | Source-shaped wait-store payload before native LIQ narrowing. |
| `nextLineData` / `nextValidMask` / `nextDataComplete` | Future row line image and completion state. |
| `nextScbReturned` / `nextStqReturned` / `nextStoreSourceReturned` | Future split and coarse source-return state. |
| `bridgeValid` | Request is active, not flushed, and has a legal payload shape for native LIQ apply. |
| `*Out` | Native request payload fields gated by `bridgeValid`. |
| `nativeNextWaitStoreInfoOut` | LIQ-native wait-store payload with a `storeEntries`-width store index. |
| `nativeStoreIndexOut` | Narrowed native store index diagnostic; zero when no wait-store payload is forwarded. |
| `sourceStoreIndexFits` | True when the source wait-store index is representable in the native LIQ store-entry domain. |
| `blockedBy*` | Disabled, flush, and no-request diagnostics. |
| `invalid*` | Store-index narrowing, conflicting-status, wait-store-without-wait-status, and inconsistent split-source diagnostics. |

## Logic Design

The module is combinational:

```text
active      = enable && !flush
requestLive = active && requestValid
bridgeValid = requestLive && legal_payload_shape
```

The bridge forwards target, status, return-state, line-data, and split-return
fields only when `bridgeValid` is true. The native wait-store payload is
forwarded only when both `bridgeValid` and `nextWaitStore` are true; otherwise
it is zero.

When `sourceStoreEntries` is wider than `storeEntries`, the bridge requires
`nextWaitStoreInfo.storeIndex < storeEntries` before narrowing to the native
store-index width. If `nextWaitStore` is false, the range check remains visible
through `sourceStoreIndexFits` but does not block non-wait-store data-merge
requests.

The other payload guards mirror the adjacent R410/R412 contracts:

- a request cannot set both wait and repick status;
- `nextWaitStore` must pair with wait-status intent;
- coarse returned-source state must imply both split SCB and STQ return bits.

## Deferred Owners

- Registered `LoadInflightQueue` row mutation using this bridge plus the R412
  `LoadInflightRowMutationApply` preview.
- Live promotion control for
  `LoadReplaySourceReturnStoreSnapshotRowMutationRequest.liveEnable`.
- Replacement of coarse `sourcesReturned` readiness with row-owned split-bit
  readiness after registered mutation is cross-checked.

## Verification

Focused gate:

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadInflightRowMutationRequestBridge
```

Reference tests cover in-range wait-store forwarding, out-of-range wait-store
blocking, non-wait-store requests with out-of-range source indexes, disabled
and flush blockers, malformed payload guards, and Chisel elaboration.
