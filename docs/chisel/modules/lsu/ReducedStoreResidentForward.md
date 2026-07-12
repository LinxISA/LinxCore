# ReducedStoreResidentForward

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/ReducedStoreResidentForward.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/ReducedStoreResidentForwardSpec.scala`
- Integrated user: `rtl/LinxCore/chisel/src/main/scala/linxcore/top/LinxCoreFrontendFetchRfAluTraceTop.scala`
- LinxCoreModel evidence:
  - `tools/LinxCoreModel/model/lsu/store_unit/stq.cpp`
    - `STQ::lookupForLoad`
    - `STQ::retire`
    - `STQ::commit`
  - `tools/LinxCoreModel/model/lsu/store_unit/stq.h`
  - `tools/LinxCoreModel/model/lsu/load_unit/ldq.cpp`
    - `LDQInfo::handleSTQReceive`
    - `LDQInfo::waitStore`
- Related Chisel contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadStoreForwarding.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/ResidentStoreForwardStoreSnapshot.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/STQEntryBank.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/ReducedStoreMemoryOverlay.scala`
- Contract IDs: `LC-CHISEL-LSU-STQ-FWD-002`

## Purpose

`ReducedStoreResidentForward` is the reduced-top adapter that lets ready
resident STQ rows feed scalar load data before the store commits. It uses
`ResidentStoreForwardStoreSnapshot` to map `STQEntryBankRow` state into the
generic `LoadStoreForwarding` selector, constructs a synthetic 64-byte
cacheline from the current 64-bit load window, and extracts the returned 64-bit
load data after ready store bytes are overlaid.

This is still a reduced trace-top bridge. The module preserves the incoming
base load data when an older store is not data-ready and reports the wait hit
through diagnostics. R266 wires that diagnostic to an execute-stage hold in
`LinxCoreFrontendFetchRfAluTraceTop`, but this module still does not mutate
LIQ/LDQ state or consume store-unit wakeups. A later replay owner must consume
the wait-store identity before this can replace the full model load pipeline.

## Sizing Contract

`entries` sizes the resident STQ vector, eligible mask, and wait-store index;
`robEntries` sizes load/store BID and current transitional LSID identity
fields. `lsidWidth` independently sizes the authoritative full LSID carried by
resident rows and the selected wait-store result. Forwarding selection never
derives either identity width from the number of resident rows. The focused
suite includes 16-STQ/8-ROB and 40-bit full-LSID contracts.

## Interface

### Load Query

| Signal | Description |
|---|---|
| `enable` | Enables the reduced resident-forward path. Disabled mode passes through `baseLoadData`. |
| `loadValid` | Execute-stage scalar load lookup is active. |
| `loadAddr` | Byte address of the 64-bit reduced load window. |
| `loadSize` | Load size in bytes. The R265 integration uses 1-byte `LBUI` and 8-byte scalar load forms. |
| `loadBid` | Load BID used as the youngest visible store snapshot. |
| `loadLsId` | Reduced load-LSID projection retained for compatibility diagnostics. |
| `loadLsIdFullValid` | The load snapshot carries authoritative full-LSID order. |
| `loadLsIdFull` | Parameterized full load snapshot LSID used for same-BID forwarding order. |
| `baseLoadData` | 64-bit load data after the committed-store memory overlay. |
| `rows` | Resident STQ row image from `StoreDispatchSTQPath`. |

### Outputs

| Signal | Description |
|---|---|
| `loadData` | Final 64-bit load data. Ready resident store bytes are included only when no wait byte is selected. |
| `loadForwardMask` | One bit per returned byte forwarded from ready resident STQ rows. |
| `waitMask` | One bit per returned byte whose nearest older resident store is not data-ready. |
| `eligibleStoreMask` | STQ rows accepted by the resident-forward candidate filter. |
| `waitStore` | Selected not-ready resident store identity from `LoadStoreForwarding`: valid bit, STQ index, BID, projected LSID, full-LSID authority/value, and PC. Same-BID selection uses the full identity. |
| `fullLsIdMissingMask` | Relevant same-BID rows excluded because full-LSID authority is absent. |
| `fullLsIdAmbiguousMask` | Relevant same-BID rows excluded by exactly half-range serial ambiguity. |
| `readyForward` | At least one byte was forwarded and no wait byte blocked the load. |
| `waitBlocked` | A nearest older resident store is not data-ready for at least one requested byte. R266 top integration uses this to hold execute. |
| `loadCrossesLine` | Load crosses a 64-byte line and is left on the base overlay path in this ready-only packet. |

## State

The module is combinational. It owns no STQ, LDQ, SCB, cache, or replay state.
`LinxCoreFrontendFetchRfAluTraceTop` wires it after `ReducedStoreMemoryOverlay`
and before `ReducedScalarAluExecute.loadLookupData`.

## Logic Design

The model `STQ::lookupForLoad` first scans working resident STQ rows whose
address is ready, whose byte range overlaps the load, and whose `(bid, lsID)`
is older than or equal to the load's `(bid, lsID)`. Ready stores provide bytes.
Not-ready stores set wait bits. The nearest older store wins per byte.

The Chisel adapter preserves that selector rule through `LoadStoreForwarding`
with reduced-top limits:

1. Retain the execute load's full LSID beside its reduced compatibility
   projection. Same-BID ordering uses only the full value.
2. Suppress cross-line load queries and cross-line resident store candidates.
   The committed-store overlay still handles cross-line committed fragments.
3. Build a synthetic 64-byte `cacheData` line by placing the eight
   `baseLoadData` bytes at the load address offset.
4. Use `ResidentStoreForwardStoreSnapshot` to convert resident STQ rows into
   line-addressed `LoadStoreForwardStore` candidates with model-style BID,
   LSID, PC, byte-mask, and positioned-line-data sidecars.
5. Run `LoadStoreForwarding`, then extract the load-window bytes from the
   merged 64-byte line.
6. If `waitMask` is nonzero, preserve `baseLoadData` and report
   `waitBlocked`. The R266 top consumes that diagnostic as execute
   backpressure; replay row mutation remains outside this adapter.

## Timing

R265 wires the adapter combinationally after `ReducedStoreMemoryOverlay`.
The final reduced load-data order is:

1. harness sparse ELF base data,
2. committed-store/SCB accepted overlay data,
3. ready resident STQ forwarding data when no wait byte is selected.

Ready hits continue through the reduced execute pipe. Wait hits hold the
E-stage load through the R266 execute wait input until the resident store data
becomes ready or a flush clears the pipe. A later packet may move this work
behind a registered LIQ/forward pipeline.

## Flush/Recovery

The module has no flush input. The top disables it when
`useReducedStoreDispatchStq=false`; STQ row validity and flush pruning are
owned by `StoreDispatchSTQPath` and `STQEntryBank`.

## Deferred Owners

- LIQ/LDQ wait-store row mutation and replay wakeups.
- Cross-line resident store/load forwarding.
- MDB conflict publication and recovery cleanup.

## Verification

Focused gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only ReducedStoreResidentForward
bash tools/chisel/run_chisel_tests.sh --only ResidentStoreForwardStoreSnapshot
bash tools/chisel/run_chisel_tests.sh --only LoadStoreForwarding
bash tools/chisel/run_chisel_tests.sh --only ReducedScalarAluExecute
bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop
```

Reference tests cover ready resident forwarding over committed overlay data,
same-BID full-LSID source selection beyond the projection width, missing
authority refusal, not-ready wait pass-through, cross-line
suppression, selected wait-store identity including PC, and Chisel elaboration
with diagnostics. A 40-bit structural test locks resident-row to selected-wait
full-LSID width preservation.
