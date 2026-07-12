# LoadReplaySourceReturnStoreSnapshotLookup

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplaySourceReturnStoreSnapshotLookup.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/LoadReplaySourceReturnStoreSnapshotLookupSpec.scala`
- Integrated user: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplaySourceReturnStoreSnapshotPath.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/mtccore/lsu/store_unit/store_unit.cpp`
    - `MtcStoreUnit::handleLoadReq`
  - `model/LinxCoreModel/model/mtccore/lsu/store_unit/stq.cpp`
    - `MtcSTQ::getData`
    - `MtcSTQ::lookupForLoad`
  - `model/LinxCoreModel/model/mtccore/lsu/load_unit/ldq.cpp`
    - `MtcLDQInfo::handleL1Lookup`
    - `MtcLDQInfo::handleSTQReceive`
- Related Chisel contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplaySourceReturnStoreSnapshotRequestPayload.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/ResidentStoreForwardStoreSnapshot.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadStoreForwarding.scala`
- Contract IDs: `LC-CHISEL-LSU-REPLAY-STQ-SNAPSHOT-LOOKUP-001`

## Purpose

`LoadReplaySourceReturnStoreSnapshotLookup` is the resident-STQ byte lookup
owner for replay-LIQ local source-return requests.

The model store unit drains `lookup_lu_su_q`, calls `MtcSTQ::getData`, and
pushes the mutated request to `lookup_su_lu_q`. This module implements the
same reduced scalar lookup shape by converting current `STQEntryBankRow`
state into `LoadStoreForwardStore` candidates, then using
`LoadStoreForwarding` to choose the nearest older resident store per requested
byte.

The owner still stops before replay-row mutation. It reports wait-store and
data evidence to the existing request-sink/response-queue path, but it does
not update LIQ/LDQ row state, merge data into the replay row, or publish
wait-store wakeup state.

## Sizing Contract

`stqEntries` sizes resident rows, snapshot masks, forwarding candidates, and
wait-store indices. `idEntries` sizes BID and physical compatibility identity;
`lsidWidth` independently sizes authoritative request/store order. The composed
lookup passes both values explicitly and its unequal-size test proves that a
16-row STQ does not widen the 8-entry ROB identity domain.

## Interface

### Inputs

| Signal | Description |
|---|---|
| `enable` / `flush` | Arms lookup and suppresses query evidence during recovery. |
| `requestValid` / `request` | Visible queued selected-row request from `LoadReplaySourceReturnStoreSnapshotRequestQueue`. |
| `rows` | Current resident STQ row image from `STQEntryBank`. |
| `cacheData` | 64-byte baseline line used by `LoadStoreForwarding.mergedData`. The current composite ties it to zero because replay-row data merge remains deferred. |

### Outputs

| Signal | Description |
|---|---|
| `requestActive` / `queryValid` | Active non-flushed request and scalar same-line lookup predicate. |
| `loadCrossesLine` | Request window crosses the 64-byte line and is suppressed by this reduced owner. |
| `requestMaskMismatch` | The request payload byte mask does not match the address/size-derived mask. |
| `storeSnapshot*` | Row-export diagnostics from `ResidentStoreForwardStoreSnapshot`. |
| `eligibleStoreMask` | Resident scalar stores older than the request snapshot and overlapping the load bytes. |
| `forwardMask` / `waitMask` / `uncoveredLoadMask` | Byte-granular nearest-store result from `LoadStoreForwarding`. |
| `waitStore` / `waitStoreValid` | Newest not-ready selected store that blocks at least one requested byte. |
| `rawDataValid` | At least one requested byte has ready resident-store data. This mirrors model `data_vld` evidence. |
| `responseDataValid` | Data evidence allowed onto the replay response. It is suppressed when `waitStoreValid` is set because `handleSTQReceive` rewaits before merging data. |
| `dataSuppressedByWait` | Diagnostic for the model-visible case where ready bytes exist but wait-store control wins. |
| `storeBypassComplete` | All requested bytes are covered by ready resident-store data. |
| `forwardData` | Store-only 64-byte line data for ready forwarded bytes. R406 feeds this into the typed response payload with `forwardMask` as the valid mask. |
| `mergedData` | Baseline line with ready store bytes overlaid; reserved for the later data-merge owner. |

## State

The module is combinational. STQ rows are owned by `STQEntryBank`, request
ordering by `LoadReplaySourceReturnStoreSnapshotRequestQueue`, response
ordering by `LoadReplaySourceReturnStoreSnapshotResponseQueue`, and replay-row
mutation by later owners.

## Logic Design

The model `MtcSTQ::lookupForLoad` builds overlapping resident store candidates
whose address is ready, whose row is working, and whose `(bid, lsID)` is older
than or equal to the load request. It updates ready data bytes and tracks
not-ready bytes; a younger ready store clears wait poison for the bytes it
covers, leaving the nearest older store as the per-byte owner.

The Chisel owner reuses the existing reduced store-forwarding primitives:

1. Suppress the query when disabled, flushed, invalid, zero-sized, or
   cross-line.
2. Convert `STQEntryBankRow` values into abstract forwarding stores with
   `ResidentStoreForwardStoreSnapshot`.
3. Build a `LoadStoreForwardQuery` from the request address, size, BID, and
   full youngest-store LSID snapshot. The reduced LSID is not an ordering
   fallback.
4. Run `LoadStoreForwarding` over the current resident store vector.
5. Publish wait-store evidence directly from the selected not-ready store.
6. Publish `rawDataValid` from any ready forwarded byte.
7. Publish raw store-only `forwardData` and `forwardMask` for the R406 response
   payload.
8. Publish `responseDataValid` only when no wait-store rewait is required.

The `rawDataValid` / `responseDataValid` split is intentional. The C++ model
can leave `data_vld` set when a later wait-store check also sets
`wait_store`, but `MtcLDQInfo::handleSTQReceive` handles the wait-store path
first and returns before data merge. R406 preserves the raw data mask/data in
the typed response payload while leaving response-visible `dataValid` false
for later mutation.

## Flush/Recovery

`flush` suppresses lookup queries and all response-visible sidebands. Wrong
path STQ rows are expected to be pruned by the STQ owner before they reach this
lookup.

## Deferred Owners

- Store-data merge into the replay row line image using the R406 response
  payload.
- Wait-store row mutation and dependent replay wakeup using the R406 response
  payload.
- Cross-line replay source-return lookup.
- Live promotion of `requestEnable` and request sink readiness in the top.

## Verification

Focused gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadReplaySourceReturnStoreSnapshotLookup
bash tools/chisel/run_chisel_tests.sh --only LoadReplaySourceReturnStoreSnapshotPath
bash tools/chisel/run_chisel_tests.sh --only LoadReplaySourceReturnStoreSnapshotRequestSink
bash tools/chisel/run_chisel_tests.sh --only ResidentStoreForwardStoreSnapshot
bash tools/chisel/run_chisel_tests.sh --only LoadStoreForwarding
bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop
```

Reference tests cover ready resident-store data, nearest not-ready wait-store
selection, younger-store suppression, disabled/flush/cross-line suppression,
request-mask mismatch diagnostics, and Chisel elaboration with both reused
forwarding children plus the raw forward-data output.
