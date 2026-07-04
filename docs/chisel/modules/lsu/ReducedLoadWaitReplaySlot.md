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
  - `tools/LinxCoreModel/model/ModelCommon/SimInstInfo.cpp`
    - `SimInstInfo::GenRFReqBus`
    - `SimInstInfo::RFRetSetData`
- Related Chisel contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/ReducedStoreResidentForward.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/ResidentStoreReplayWakeup.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayWakeup.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/ReducedLoadReplayRelaunchQueue.scala`
- Contract IDs: `LC-CHISEL-LSU-STQ-FWD-004`

## Purpose

`ReducedLoadWaitReplaySlot` is the first reduced-top consumer-side bridge for
resident store-data waits. It captures the E-stage load identity and selected
wait-store key while `ReducedStoreResidentForward` reports a wait hit, keeps
that key registered after the live forwarder stops reporting a wait, and feeds
the resulting store-unit wakeup through the existing `LoadReplayWakeup` owner.

This is still diagnostic integration. The slot proves the replay request shape
can clear a remembered wait-store row by `(BID, LSID, PC)` and now publishes a
one-cycle relaunch candidate carrying the remembered load identity. R274
extends that candidate to preserve the model `MemReqBus` ROB sidecars
`BID/GID/RID` plus reduced `LSID`, matching the identity carried through the
model LDQ insert/wait path. R275 also records the
`LoadInflightQueue` forwarding snapshot `(youngestStoreId,
youngestStoreLsId)` as explicit candidate sidecars, rather than leaving it
implicit in the reduced top's current BID/LSID aliases. R307 carries the
derived replay-return signedness bit beside address and size, using the model
opcode class that later feeds `SignExtend`. R311 carries the renamed load
destination sideband from the held execute uop beside the same replay
candidate, matching the model's later ROB `pdsts_` lookup. R375 also captures
the RF-derived `sourceTraceValid/source0/source1` sideband from
`ReducedScalarAluExecute`, preserving original load source operands through
the wait-store clear boundary instead of deriving them from ROB or LIQ
metadata later. In the reduced top after R272,
`ReducedLoadReplayRelaunchQueue` consumes that pulse into a finite pending
queue. The slot itself does not drive a launch port, wake dependent consumers,
or replace the full `LoadInflightQueue` owner.

## Interface

### Inputs

| Signal | Description |
|---|---|
| `flush` | Clears the registered wait slot. |
| `captureValid` | Captures the current reduced E-stage load as waiting on a resident store. |
| `capturePc` | PC of the waiting load. |
| `captureAddr` | Byte address of the waiting load. |
| `captureSize` | Byte size of the waiting load. |
| `captureReturnSignExtend` | Derived scalar return signedness for future replay-return data extraction. |
| `captureDst` | Renamed destination sideband from the held load uop. |
| `captureSourceTraceValid` | R375 RF-derived source-trace sideband valid from the held load uop. |
| `captureSource0`, `captureSource1` | R375 original source operand traces captured from execute-stage RF-return data. |
| `captureBid` | Load allocation snapshot BID. |
| `captureGid` | Load allocation snapshot GID sidecar. |
| `captureRid` | Load allocation snapshot RID sidecar. |
| `captureLsId` | Load allocation snapshot LSID in `ROBID` form. |
| `captureYoungestStoreId` | Forwarding snapshot BID used by the later LIQ/forwarding handoff. In the current reduced top this aliases `captureBid`. |
| `captureYoungestStoreLsId` | Forwarding snapshot LSID used by the later LIQ/forwarding handoff. In the current reduced top this aliases `captureLsId`. |
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
| `relaunch.valid` | One-cycle candidate pulse when the remembered wait-store key is cleared and no same-cycle capture wins the slot. |
| `relaunch.pc` | PC of the remembered load. |
| `relaunch.addr` | Byte address of the remembered load. |
| `relaunch.size` | Byte size of the remembered load. |
| `relaunch.returnSignExtend` | Captured scalar return signedness sideband. |
| `relaunch.dst` | Captured renamed destination sideband for future LRET/wakeup payload construction. |
| `relaunch.sourceTraceValid` | Captured source-trace validity for future replay W2 commit-row source fill. |
| `relaunch.source0`, `relaunch.source1` | Captured original source operand traces; not ROB allocation placeholders. |
| `relaunch.bid` | BID snapshot captured with the remembered load. |
| `relaunch.gid` | GID snapshot captured with the remembered load. |
| `relaunch.rid` | RID snapshot captured with the remembered load. |
| `relaunch.loadLsId` | Reduced LSID captured with the remembered load. |
| `relaunch.youngestStoreId` | Forwarding snapshot BID captured with the remembered load. |
| `relaunch.youngestStoreLsId` | Forwarding snapshot LSID captured with the remembered load. |
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
5. Publish a one-cycle relaunch candidate containing the stored load PC,
   address, size, derived return signedness, renamed destination, RF-derived
   source operand traces, BID, GID, RID, reduced LSID, and forwarding snapshot
   `(youngestStoreId, youngestStoreLsId)`. This is the future LIQ/issue
   handoff boundary; it does not itself relaunch the load.
6. In the reduced top, `ReducedLoadReplayRelaunchQueue` stores that one-cycle
   pulse as a stable pending diagnostic until a later LIQ/issue consumer
   exists.

## Timing

Capture is registered. A newly captured wait key becomes visible to
`ResidentStoreReplayWakeup` on the following cycle. Store wakeup consumption is
combinational through `LoadReplayWakeup` and clears the slot on the next clock
edge. The relaunch candidate is valid in that clear cycle and carries the
pre-clear registered load identity.

If capture and clear are both asserted in one cycle, capture wins and the
candidate is suppressed. In the integrated reduced top, the matching wakeup
appears when the store is ready and the live wait-hit has dropped, so same-cycle
capture/clear is not expected for the same load.

## Flush/Recovery

`flush` clears the slot and suppresses capture/wakeup consumption. The reduced
top drives it from the reduced-store flush condition: backend pipe flush,
run start, run restart, or disabling the optional reduced-STQ path.

## Deferred Owners

- Real `LoadInflightQueue` allocation for reduced execute-stage loads.
- Connecting the queued relaunch candidate to a real LIQ or issue/ready-table
  launch boundary.
- Consumer wakeup after wait-store clear.
- Multiple waiting loads and wakeup arbitration.
- Cross-line resident replay publication.
- MDB conflict publication and precise recovery pruning.

## Verification

Focused gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only ReducedLoadWaitReplaySlot
bash tools/chisel/run_chisel_tests.sh --only ReducedLoadReplayRelaunchQueue
bash tools/chisel/run_chisel_tests.sh --only ResidentStoreReplayWakeup
bash tools/chisel/run_chisel_tests.sh --only LoadReplayWakeup
bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop
git diff --check
```

Reference tests cover capture/clear behavior, mismatched wake suppression,
capture overwrite, relaunch-candidate identity, flush clearing, and Chisel
elaboration through `LoadReplayWakeup`.
