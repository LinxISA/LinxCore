# ResidentStoreReplayWakeup

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/ResidentStoreReplayWakeup.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/ResidentStoreReplayWakeupSpec.scala`
- Integrated user: `rtl/LinxCore/chisel/src/main/scala/linxcore/top/LinxCoreFrontendFetchRfAluTraceTop.scala`
- LinxCoreModel evidence:
  - `tools/LinxCoreModel/model/lsu/store_unit/stq.cpp`
    - `STQ::lookupForLoad`
  - `tools/LinxCoreModel/model/lsu/load_unit/ldq.cpp`
    - `LDQInfo::waitStore`
    - `LDQInfo::handleSUWakeup`
- Related Chisel contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/ReducedStoreResidentForward.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayWakeup.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/STQEntryBank.scala`
- Contract IDs: `LC-CHISEL-LSU-STQ-FWD-003`

## Purpose

`ResidentStoreReplayWakeup` is the producer-side bridge between resident STQ
wait-store diagnostics and the generic `LoadReplayWakeupRequest` shape. It
observes a selected wait-store identity, checks that the named resident STQ row
still matches `(BID, LSID, PC)`, and emits a store-unit replay wakeup request
only when that row has address and data ready. In the reduced top after R269,
the identity is supplied by `ReducedLoadWaitReplaySlot`, not directly from the
live forwarder, because the live forwarder stops reporting a wait once the
store data becomes ready.

This is intentionally not a full LIQ/LDQ integration. It does not allocate a
load row, relaunch a load, or wake consumers. It gives the reduced top a typed
request boundary that the R269 reduced wait slot and later LIQ integration can
consume.

## Interface

### Inputs

| Signal | Description |
|---|---|
| `enable` | Enables the reduced resident replay-wakeup bridge. |
| `waitStore` | Selected not-ready store identity: valid bit, STQ index, BID, LSID, and PC. In the reduced top this is the registered key from `ReducedLoadWaitReplaySlot`. |
| `rows` | Resident STQ row image from `StoreDispatchSTQPath`. |

### Outputs

| Signal | Description |
|---|---|
| `wakeValid` | The selected row still matches the wait-store identity and is ready to publish store data. |
| `wake` | `LoadReplayWakeupRequest` with source `StoreUnit`, row `(BID, LSID, PC)`, line address, byte-valid mask, and 64-byte line data. |
| `selectedRowValid` | The wait-store index names a valid `STQ_WAIT` row while enabled. |
| `identityMatch` | The indexed row still matches the stored `(BID, LSID, PC)` identity. |
| `selectedRowReady` | The indexed row is address-ready, data-ready, scalar, non-cross-line, and identity-matched. |
| `selectedRowCrossesLine` | The selected row crosses a 64-byte line and is suppressed in the reduced scalar path. |
| `wakeByteMask` | Store byte lanes carried by `wake.data`; zero when `wakeValid` is false. |

## State

The module is combinational and stateless. It consumes live STQ row state and
produces a typed wakeup request image in the same cycle.

## Logic Design

The model sequence is:

1. `STQ::lookupForLoad` marks a load as waiting on a not-ready store and
   records the blocking store identity and PC.
2. `LDQInfo::waitStore` stores that wait-store key in the load row.
3. `LDQInfo::handleSUWakeup` clears wait-store state when a store-unit wakeup
   carries the same blocking identity and PC.

R268 covers the producer side of step 3 for the reduced top:

1. Select the STQ row named by `waitStore.storeIndex`.
2. Require `waitStore.valid`, `row.valid`, `row.status == Wait`, and exact
   `(row.bid, row.lsId, row.pc)` match with the stored wait key.
3. Require `addrReady`, `dataReady`, scalar store class, and non-cross-line
   range. Cross-line resident replay is deferred to a later owner.
4. Build the 64-byte line address, byte-valid mask, and line-positioned store
   data from the resident row.
5. Emit a `LoadReplayWakeupRequest` with source `StoreUnit`.

## Timing

The bridge is purely combinational. In the reduced top, `ReducedLoadWaitReplaySlot`
registers the wait-store key and feeds it here; this module then publishes the
typed wakeup image when the named row becomes ready. A future LIQ composition
can arbitrate the generated request before consuming it with `LoadReplayWakeup`.

## Flush/Recovery

The module has no flush input. It relies on `STQEntryBank` row validity and
identity matching to suppress stale wait-store keys after recovery or row
reuse.

## Deferred Owners

- LIQ allocation for reduced execute-stage loads.
- `LoadReplayWakeup` consumption in the reduced top.
- Store-unit wakeup arbitration across more than one waiting load.
- Cross-line resident replay data publication.
- MDB conflict publication, memory-event trace, and precise recovery pruning.

## Verification

Focused gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only ResidentStoreReplayWakeup
bash tools/chisel/run_chisel_tests.sh --only ReducedStoreResidentForward
bash tools/chisel/run_chisel_tests.sh --only LoadReplayWakeup
bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop
```

Reference tests cover matching ready-row wakeup emission, matching not-ready
suppression, stale identity suppression, cross-line suppression, and Chisel
elaboration with the typed wakeup request ports.
