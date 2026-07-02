# ResidentStoreForwardStoreSnapshot

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/ResidentStoreForwardStoreSnapshot.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/ResidentStoreForwardStoreSnapshotSpec.scala`
- Integrated users:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/ReducedStoreResidentForward.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/top/LinxCoreFrontendFetchRfAluTraceTop.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/lsu/store_unit/stq.cpp`
    - `STQ::lookupForLoad`
  - `model/LinxCoreModel/model/lsu/load_unit/ldq.cpp`
    - `LDQInfo::waitStore`
- Related Chisel contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/STQEntryBank.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadStoreForwarding.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/ReducedStoreResidentForward.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/ReducedLoadReplayLiqAllocPath.scala`
- Contract IDs: `LC-CHISEL-LSU-STQ-FWD-003`

## Purpose

`ResidentStoreForwardStoreSnapshot` is the reusable boundary that converts the
registered reduced STQ row image into the abstract `LoadStoreForwardStore`
vector consumed by `LoadStoreForwarding` and `LoadForwardPipeline`.

R283 split this conversion out of `ReducedStoreResidentForward` so the current
ready resident-forward path and the future replay-LIQ E2 launch path cannot
drift on byte-mask generation, 64-byte line-data placement, or store identity
sidecars. The module is still a reduced scalar bridge: it accepts only
same-line resident `Wait` rows and reports cross-line rows through diagnostics
instead of trying to split them.

## Interface

### Inputs

| Signal | Description |
|---|---|
| `enable` | Arms forwarding-store validity. Diagnostic masks remain row-derived when disabled. |
| `rows` | Registered `STQEntryBankRow` image from `StoreDispatchSTQPath`. |

### Outputs

| Signal | Description |
|---|---|
| `stores` | One `LoadStoreForwardStore` per STQ row, carrying valid/working/ready bits, tile classification, STQ index, BID, LSID, PC, 64-byte line address, byte mask, and positioned 64-byte line data. |
| `validMask` | Rows exported as valid forwarding-store candidates after `enable`, resident-wait, and same-line filtering. |
| `waitMask` | Valid STQ rows in `STQEntryStatus.Wait`. |
| `addrReadyMask` | Valid rows with address ready. |
| `dataReadyMask` | Valid rows with data ready. |
| `scalarMask` | Valid scalar rows (`scalarIex`). |
| `crossLineMask` | Resident wait rows whose byte range crosses a 64-byte line and are therefore suppressed from `stores.valid`. |

## State

The module is combinational and owns no STQ, LIQ, LDQ, cache, or replay state.
It is intended to be instantiated near the row owner that supplies the STQ row
snapshot.

## Logic Design

The model `STQ::lookupForLoad` exposes resident store rows to load forwarding
as line-addressed byte masks with store identity attached. The Chisel snapshot
preserves that packet shape for reduced scalar rows:

1. For each row, detect resident wait state as `row.valid &&
   row.status === STQEntryStatus.Wait`.
2. Compute the 64-byte line base from the row address.
3. Compute a 64-byte store byte mask from row address offset and scalar store
   size.
4. Position the row's 64-bit store data into the 64-byte cacheline image using
   that byte mask.
5. Copy row BID, LSID, PC, STQ index, address-ready, data-ready, and tile
   classification into the corresponding `LoadStoreForwardStore`.
6. Assert `stores(idx).valid` only when `enable`, resident wait state, and
   same-line filtering all pass.
7. Publish row-derived masks separately so disabled or cross-line rows remain
   visible to diagnostics.

The conversion intentionally does not apply a load-specific older-than filter.
`LoadStoreForwarding` and `LoadForwardPipeline` own load-specific range and
nearest-older byte selection.

## Timing

The snapshot is combinational. `ReducedStoreResidentForward` consumes its
`stores` in the same cycle as the execute-stage load lookup. The replay-LIQ top
wires a separate instance into the path-local E2 store-vector input while
keeping replay launch disabled.

## Flush/Recovery

The module has no flush input. Flush and recovery pruning remain owned by
`StoreDispatchSTQPath` and `STQEntryBank`; invalid or pruned rows simply stop
appearing in the input row image.

## Deferred Owners

- Cross-line resident store/load forwarding.
- Live replay-LIQ relaunch enable, base-data ownership, and return readiness.
- LHQ/ResolveQ movement and dependent wakeup.

## Verification

Focused gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only ResidentStoreForwardStoreSnapshot
bash tools/chisel/run_chisel_tests.sh --only ReducedStoreResidentForward
bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop
```

Reference tests cover positioned byte masks/data, cross-line suppression with
diagnostics, disabled-mode validity, and elaboration of the forwarding-store
vector.
