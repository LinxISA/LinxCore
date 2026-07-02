# ReducedLoadWaitReplaySlot

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/ReducedLoadWaitReplaySlot.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/ReducedLoadWaitReplaySlotSpec.scala`
- Integrated user: `rtl/LinxCore/chisel/src/main/scala/linxcore/top/LinxCoreFrontendFetchRfAluTraceTop.scala`
- LinxCoreModel evidence:
  - `tools/LinxCoreModel/model/lsu/store_unit/stq.cpp`
    - `STQ::lookupForLoad`
  - `tools/LinxCoreModel/model/lsu/load_unit/ldq.cpp`
    - `LDQInfo::waitStore`
    - `LDQInfo::handleSUWakeup`
- Related Chisel contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/ReducedStoreResidentForward.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/ResidentStoreReplayWakeup.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayWakeup.scala`
- Contract IDs: `LC-CHISEL-LSU-STQ-FWD-004`

## Purpose

`ReducedLoadWaitReplaySlot` is the first reduced-top consumer-side bridge for
resident store-data waits. It captures the E-stage load identity and selected
wait-store key while `ReducedStoreResidentForward` reports a wait hit, keeps
that key registered after the live forwarder stops reporting a wait, and feeds
the resulting store-unit wakeup through the existing `LoadReplayWakeup` owner.

This is still diagnostic integration. The slot proves the replay request shape
can clear a remembered wait-store row by `(BID, LSID, PC)`, but it does not
relaunch the load, wake dependent consumers, or replace the full
`LoadInflightQueue` owner.

## Interface

### Inputs

| Signal | Description |
|---|---|
| `flush` | Clears the registered wait slot. |
| `captureValid` | Captures the current reduced E-stage load as waiting on a resident store. |
| `capturePc` | PC of the waiting load. |
| `captureAddr` | Byte address of the waiting load. |
| `captureSize` | Byte size of the waiting load. |
| `captureBid` | Load allocation snapshot BID. |
| `captureLsId` | Load allocation snapshot LSID in `ROBID` form. |
| `captureWaitStore` | Selected not-ready store key from `ReducedStoreResidentForward`: STQ index, BID, LSID, and PC. |
| `replayWakeValid` | Store-unit replay wakeup valid from `ResidentStoreReplayWakeup`. |
| `replayWake` | Typed `LoadReplayWakeupRequest` consumed by `LoadReplayWakeup`. |

### Outputs

| Signal | Description |
|---|---|
| `active` | A reduced wait-store slot is currently remembered. |
| `captureAccepted` | A valid wait-store capture was accepted this cycle. |
| `waitStoreClear` | `LoadReplayWakeup` matched the remembered wait-store key and cleared the diagnostic slot. |
| `waitStoreClearMask` | Two-entry mask from the internal `LoadReplayWakeup`; bit 0 is the real slot and bit 1 is tied to an empty row. |
| `storedWaitStore` | Registered wait-store key. The reduced top feeds this back to `ResidentStoreReplayWakeup` so ready stores can wake a load after the live forwarder no longer reports a wait. |
| `slotPc` | PC of the remembered load row. |
| `slotAddr` | Address of the remembered load row. |

## State

The module owns one registered `LoadInflightRow` image plus an `active` bit.
Internally it presents a two-entry row vector to `LoadReplayWakeup` because
that owner requires a power-of-two LIQ depth greater than one. Entry 0 is the
real reduced wait slot; entry 1 is permanently empty.

## Logic Design

The model sequence is:

1. `STQ::lookupForLoad` finds an older store whose address overlaps the load
   but whose data is not ready.
2. `LDQInfo::waitStore` records that blocking store's identity and the load
   remains unresolved.
3. Later, `LDQInfo::handleSUWakeup` receives a store-unit wakeup and clears
   wait-store state when `(BID, LSID, PC)` matches.

R269 maps that sequence onto the reduced top:

1. Capture a synthetic one-row LIQ image when the E-stage load is held by a
   resident wait hit.
2. Publish the registered `waitStore` key back to `ResidentStoreReplayWakeup`.
   This registration is required because the live forwarder reports a wait
   only while the store data is not ready; once the store becomes ready, the
   live path switches to ready forwarding.
3. Consume the resulting `LoadReplayWakeupRequest` through the same
   `LoadReplayWakeup` module used by `LoadInflightQueue`.
4. Clear the diagnostic slot when the wakeup matches the remembered
   wait-store key by `(storeId, storeLsId, pc)`.

## Timing

Capture is registered. A newly captured wait key becomes visible to
`ResidentStoreReplayWakeup` on the following cycle. Store wakeup consumption is
combinational through `LoadReplayWakeup` and clears the slot on the next clock
edge.

If capture and clear are both asserted in one cycle, capture wins. In the
integrated reduced top, the matching wakeup appears when the store is ready and
the live wait-hit has dropped, so same-cycle capture/clear is not expected for
the same load.

## Flush/Recovery

`flush` clears the slot and suppresses capture/wakeup consumption. The reduced
top drives it from the reduced-store flush condition: backend pipe flush,
run start, run restart, or disabling the optional reduced-STQ path.

## Deferred Owners

- Real `LoadInflightQueue` allocation for reduced execute-stage loads.
- Relaunch and consumer wakeup after wait-store clear.
- Multiple waiting loads and wakeup arbitration.
- Cross-line resident replay publication.
- MDB conflict publication and precise recovery pruning.

## Verification

Focused gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only ReducedLoadWaitReplaySlot
bash tools/chisel/run_chisel_tests.sh --only ResidentStoreReplayWakeup
bash tools/chisel/run_chisel_tests.sh --only LoadReplayWakeup
bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop
```

Reference tests cover capture/clear behavior, mismatched wake suppression,
capture overwrite, flush clearing, and Chisel elaboration through
`LoadReplayWakeup`.
