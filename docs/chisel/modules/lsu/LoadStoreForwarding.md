# LoadStoreForwarding

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadStoreForwarding.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/LoadStoreForwardingSpec.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/lsu/store_unit/stq.cpp`
  - `model/LinxCoreModel/model/lsu/store_unit/stq.h`
  - `model/LinxCoreModel/model/lsu/load_unit/ldq.cpp`
  - `model/LinxCoreModel/model/core/Packet.h`
  - `model/LinxCoreModel/model/ModelCommon/LSUUtils.cpp`
  - `model/LinxCoreModel/model/ModelCommon/bus/MemReqBus.h`
- Related Chisel contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/ReducedStoreResidentForward.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/STQEntryBank.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/STQSCBCommitPath.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/MDBQueueFanout.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/rob/ROBID.scala`
- Contract IDs: `LC-CHISEL-LSU-STQ-FWD-001`

## Purpose

`LoadStoreForwarding` is the first Chisel owner for the scalar store-to-load
byte selection performed by model `STQ::lookupForLoad`. It consumes a load
query and an abstract set of STQ-like store candidates, selects the nearest
older store per requested byte, forwards ready bytes, reports bytes blocked by
not-ready stores, and merges forwarded bytes over a cache-data baseline.

The module deliberately stops before full LDQ/STQ integration. It does not own
STQ row allocation, store data RAM writes, LDQ row mutation, L1/SCB lookup,
MDB learning, BCTRL/IEX state, ROB recovery publication, or memory-event trace.

## Interface

### Load Query

| Signal | Description |
|---|---|
| `query.valid` | A scalar load lookup is active. |
| `query.lineAddr` | 64-byte cacheline tag, equivalent to model `MemReqBus::tag` for scalar loads. |
| `query.byteOffset` | Load byte offset inside the 64-byte line. |
| `query.size` | Load size in bytes. The module clips to the current 64-byte line. |
| `query.youngestStoreId` | Snapshot BID of the youngest store visible to this load at allocation. Candidate stores must be older than or equal to this `(BID, LSID)` pair. |
| `query.youngestStoreLsId` | Snapshot LSID of the youngest store visible to this load at allocation. |
| `query.isTile` | Suppresses scalar forwarding for tile load queries and reports tile suppression diagnostics. |

### Store Candidates

| Signal | Description |
|---|---|
| `stores[*].valid` | Candidate row is resident. |
| `stores[*].working` | Candidate is in a model-working STQ state and may participate in lookup. |
| `stores[*].addrReady` | Store address is known, matching `st_e.addrRdy`. |
| `stores[*].dataReady` | Store data is available, matching `e.dataRdy`. |
| `stores[*].isTile` | Suppresses scalar forwarding for tile stores. |
| `stores[*].storeIndex` | STQ row index diagnostic and future row-wakeup handle. |
| `stores[*].storeId` | Store BID used with `storeLsId` for snapshot filtering and nearest-older selection. |
| `stores[*].storeLsId` | Store LSID used with `storeId` for same-BID ordering. |
| `stores[*].pc` | Store PC diagnostic used by wait-store reporting. |
| `stores[*].lineAddr` | Store 64-byte line tag. |
| `stores[*].byteMask` | Store byte-valid mask clipped to the line. |
| `stores[*].data` | 64-byte little-endian line data image for ready forwarding. |

### Outputs

| Signal | Description |
|---|---|
| `loadByteMask` | Clipped load byte mask inside the queried line. |
| `eligibleStoreMask` | Store rows that are valid, working, address-ready, scalar, same-line, overlapping, and older than the load snapshot. |
| `tileSuppressedMask` | Otherwise eligible overlapping rows suppressed because the query or store is tile-class. |
| `coveredMask` | Load bytes covered by any selected scalar store candidate. |
| `forwardMask` | Covered load bytes whose nearest selected store has ready data. |
| `waitMask` | Covered load bytes whose nearest selected store is not data-ready. |
| `uncoveredLoadMask` | Load bytes not covered by selected store candidates. |
| `forwardData` | Forwarded byte values on `forwardMask` lanes, zero elsewhere. |
| `mergedData` | `cacheData` with `forwardData` overlaid by byte mask. |
| `forwardValid` | At least one requested load byte has ready forwarded data. |
| `storeBypassComplete` | All requested load bytes are supplied by ready store-forward data. |
| `waitStore` | Newest not-ready selected store that blocks at least one requested load byte. |
| `selectedStoreIndexByByte` | Per-byte selected row index diagnostic for later integration and waveform checks. |

## State

The module is purely combinational. Store rows, data arrays, wait/replay state,
and LDQ row updates remain owned by later STQ/LDQ integration modules.

## Logic Design

The model path first builds conflict stores from resident STQ rows:

- the row is valid and working,
- the address is ready,
- store and load byte ranges overlap,
- the store is older than or equal to the load by `(bid, lsID)`,
- tile forwarding is a TODO and is not treated as scalar forwarding.

It then scans ready and not-ready stores from old to young. Ready stores write
`ReqData.data` and set `ReqData.positionVld`. Not-ready stores set
`waitPosionVld`. A younger ready store clears wait bits for the bytes it
covers, so the effective rule is per-byte nearest older store selection.

The Chisel owner expresses that rule directly:

1. Build the clipped `loadByteMask` from `byteOffset` and `size`.
2. Mark eligible scalar stores by same-line overlap and
   `STQCommitQueue.lessEqualBidLs(storeId, storeLsId, youngestStoreId, youngestStoreLsId)`.
3. For each of 64 bytes, choose the eligible store with the greatest
   wrap-aware `(storeId, storeLsId)` pair.
4. Mark the byte as forwarded when the selected store is data-ready.
5. Mark the byte as waiting when the selected store is not data-ready.
6. Merge forwarded bytes over `cacheData`; uncovered bytes keep the cache
   baseline.
7. Select the newest not-ready store among requested waiting bytes for replay
   diagnostics.

This preserves the model's byte-granular `ReqData::merge` behavior and the
`LDQInfo::checkDataPosionValid` rule that a load cannot be store-bypassed until
all requested bytes are position-valid.

## Timing

The current owner is combinational and intended to sit at the future STQ lookup
or LDQ response-merge boundary. Later integration may pipeline the store CAM,
bank the data array, or split E2/E3/E4 stages, but it must preserve the visible
per-byte nearest-store result and wait-store replay mask.

R265 integrates the selector through `ReducedStoreResidentForward` in the
optional reduced-store trace top. That adapter maps resident `STQEntryBankRow`
state into this module, forwards only ready/no-wait bytes after the committed
store overlay, and leaves wait-hit replay control to later LIQ/LDQ packets.

## Flush/Recovery

`LoadStoreForwarding` has no flush input. Recovery and row validity are
represented by the candidate `valid` and `working` bits supplied by the future
STQ state owner. Wrong-path row cleanup must happen before candidates reach
this module.

## Trace/Observability

The module exposes candidate, byte, and wait-store diagnostics for waveform and
future cross-check plumbing. QEMU and LinxCoreModel cross-checks cannot observe
this packet directly until the Chisel top emits live memory or recovery trace
rows.

## Verification

- `bash tools/chisel/run_chisel_tests.sh --only LoadStoreForwarding`
- `bash tools/chisel/run_chisel_tests.sh --only ReducedStoreResidentForward`
- `bash tools/chisel/run_chisel_tests.sh --only MDBQueueFanout`
- `bash tools/chisel/run_chisel_tests.sh --only MDBSSIT`
- `bash tools/chisel/run_chisel_tests.sh --only MDBConflictDetect`
- `bash tools/chisel/run_chisel_tests.sh --only STQCommitQueue`
- `bash tools/chisel/run_chisel_rob_bookkeeping.sh --reduced-rob`
- `python3 tools/chisel/trace_schema_adapter.py --self-test`
- `bash tools/chisel/run_chisel_qemu_crosscheck.sh --dry-run`

Focused reference tests cover ready store byte forwarding, younger-than-snapshot
suppression, per-byte newest older selection, not-ready replay masks, tile and
different-line suppression, wrap-aware store age ordering, same-BID LSID
ordering, and Chisel elaboration with mask, merge, and wait diagnostics.
