# ReducedStoreWaitReplayChiselPathProbe

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/ReducedStoreWaitReplayChiselPathProbe.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/ReducedStoreWaitReplayChiselPathSpec.scala`
- Verilator harness: `rtl/LinxCore/tools/chisel/reduced_store_wait_replay_chisel_path_tb.cpp`
- Wrapper: `rtl/LinxCore/tools/chisel/run_chisel_reduced_store_wait_replay_chisel_path.sh`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/lsu/store_unit/stq.cpp`
    - `STQ::lookupForLoad`
  - `model/LinxCoreModel/model/lsu/load_unit/ldq.cpp`
    - `LDQInfo::handleSTQReceive`
    - `LDQInfo::waitStore`
    - `LDQInfo::handleSUWakeup`
- Contract IDs: `LC-CHISEL-LSU-STQ-FWD-006`

## Purpose

`ReducedStoreWaitReplayChiselPathProbe` is a bounded generated-RTL fixture for
the reduced store-wait replay path. It composes the real Chisel owners used by
the reduced replay-LIQ path:

- `STQEntryBank`
- `ReducedStoreResidentForward`
- `ReducedLoadWaitReplaySlot`
- `ResidentStoreReplayWakeup`
- `ReducedLoadReplayRelaunchQueue`
- `ReducedLoadReplayLiqAllocPath`
- `LoadResolveQueue`
- `MDBConflictDetect`

The probe exists because R450 showed that delaying split STD data in the live
top does not force the younger load to execute while the resident store is
address-ready and data-late. This fixture drives that microarchitectural
window directly and proves the owner chain through generated RTL.

## Interface

### Store Insert

| Signal | Description |
|---|---|
| `storeInsertValid` | Drives one STQ insert request. |
| `storeInsert` | Native `STQStoreRequest`. The harness uses `Addr` for STA, `Data` for STD merge, and `All` for the ready-store negative case. |
| `storeInsertAccepted` | STQ insert accepted. |
| `stqOccupiedMask` | Resident STQ rows. |
| `stqAddrReadyMask` | Address-ready resident rows. |
| `stqDataReadyMask` | Data-ready resident rows. |

### Load Lookup

| Signal | Description |
|---|---|
| `loadValid` | Younger load lookup is active. |
| `loadAddr` | Load byte address. |
| `loadSize` | Load size in bytes. |
| `loadBid` | Younger load BID. |
| `loadLsId` | Younger load LSID. |
| `baseLoadData` | Base data presented to the resident-forward path. |
| `captureEnable` | Allows `ReducedLoadWaitReplaySlot` to capture a wait-store hit. |

### Diagnostics

| Signal | Description |
|---|---|
| `forwardWaitBlocked` | Resident store forwarder selected a not-ready older store. |
| `forwardReady` | Resident store forwarder can return ready store bytes. |
| `forwardWaitStoreValid` | Forwarder produced a wait-store identity. |
| `forwardWaitStoreIndex` | STQ row selected as the blocking store. |
| `waitSlotCaptureAccepted` | Wait slot captured the blocking store key. |
| `waitSlotActive` | Wait slot is resident. |
| `wakeValid` | Ready store row emitted a store-unit replay wakeup. |
| `waitStoreClear` | Store wakeup cleared the wait slot. |
| `relaunchQueueOutFire` | Relaunch queue handed the replay candidate to LIQ allocation. |
| `liqAllocAccepted` | LIQ accepted the replay candidate. |
| `liqRefillValid` / `liqRefillLineAddr` / `liqRefillData` | Fixture-driven refill wakeup for the allocated replay row. |
| `liqRefillAccepted` / `liqRefillWakeMask` | LIQ refill wakeup was accepted and matched resident rows. |
| `liqLaunchEnable` | Fixture-owned launch arm for `ReducedLoadReplayLiqAllocPath`. |
| `liqE2LoadDataReturned` / `liqE2ScbReturned` / `liqE2StqReturned` / `liqE2ReturnReady` | Fixture-owned source-return and return-port sidebands for the launched replay row. |
| `liqLaunchValid` / `liqLaunchReady` / `liqLaunchDriveValid` / `liqLaunchAccepted` | Selector, readiness, gated drive, and accepted launch diagnostics. |
| `liqLaunchSelectedLoadLsId` | Selected launch row preserved the original load LSID. |
| `liqE4UpdateValid` / `liqE4UpdateIndex` / `liqE4WakeupValid` | E4 outcome diagnostics for the launched replay row. |
| `liqLhqRecordValid` / `liqLhqRecordLoadLsId` / `liqLhqRecordData` | LHQ hit-record diagnostics emitted when the replay row resolves. |
| `resolveQueuePushAccepted` | Probe-local `LoadResolveQueue` accepted the LHQ hit record. |
| `resolveQueueRetireValid` / `resolveQueueRetireBid` / `resolveQueueRetireLsId` | Fixture-owned ResolveQ retire watermark input. |
| `resolveQueueRetireMask` / `resolveQueueRetireCount` | ResolveQ rows selected by the retire watermark before compaction. |
| `resolveQueueValidMask` / `resolveQueueCount` / `resolveQueueFirstLoadLsId` | ResolveQ residency diagnostics after the LHQ record is appended. |
| `mdbStore` | Fixture-owned store-arrival probe for the local `MDBConflictDetect` instance. |
| `mdbResolveCandidateMask` | ResolveQ rows classified as scalar conflicts for the current store probe. |
| `mdbConflictValid` / `mdbConflictFromResolveQueue` / `mdbConflictResolveIndex` | Selected MDB conflict source and index diagnostics. |
| `mdbInnerFlush` / `mdbNukeFlush` | Same-BID versus cross-BID conflict classification from `MDBConflictDetect`. |
| `mdbConflictLoadLsId` / `mdbConflictLoadPc` | Selected load identity retained in the MDB conflict record. |
| `liqClearResolvedPending` / `liqClearResolvedAccepted` | Probe-local delayed clear request back into LIQ after ResolveQ accepts the LHQ record. |
| `liqWaitMask` / `liqRepickMask` / `liqResolvedMask` | LIQ row status masks before launch, after launch, and after E4 resolution. |
| `liqFirstYoungestStoreLsId` | Allocated LIQ row preserved the forwarding snapshot sidecar. |

## Logic Design

The generated-RTL harness runs two scenarios:

1. A ready `ST_ALL` row is inserted, then a younger load looks up the same
   address. The forwarder reports ready forwarding and no wait-store key is
   captured.
2. A split `STA` row is inserted, then a younger load looks up the same
   address while the store has `addrReady=1` and `dataReady=0`. The forwarder
   reports a wait hit, the wait slot captures the store key, a later matching
   `STD` merge makes the row ready, `ResidentStoreReplayWakeup` emits a store
   wakeup, the wait slot clears into a relaunch candidate, the queue fires, and
   `ReducedLoadReplayLiqAllocPath` allocates a resident LIQ row.
3. The harness drives a read-refill wakeup for the allocated row's cacheline.
   This gives the row complete row-owned bytes through the existing
   `LoadRefillWakeup` path, making `LoadInflightLaunchSelect` assert
   `launchValid`. With `liqLaunchEnable` asserted for one cycle, the LIQ row
   accepts launch and enters `Repick`.
4. The same launch cycle drives all source-return sidebands and return-port
   readiness. One cycle later the fixture observes `e4UpdateValid`,
   `e4WakeupValid`, and `lhqRecordValid`; after the following clock, the row
   status mask moves from `Repick` to `Resolved`.
5. The probe appends the LHQ hit record to `LoadResolveQueue` on the E4
   cycle. A fixture-local pending-clear register captures the accepted
   `loadId.value` and drives `clearResolved` on the next cycle, clearing the
   LIQ row while the ResolveQ record remains resident.
6. The harness can drive a model-style ResolveQ retire watermark. A watermark
   strictly newer than the resolved row's `(BID, loadLsId)` selects that row,
   and the following cycle compacts ResolveQ to empty.
7. Before the retire watermark, the harness drives a fixture-owned store probe
   matching the older resident store. `MDBConflictDetect` consumes
   `LoadResolveQueue.conflictRows`, selects ResolveQ row zero as a scalar
   conflict, and classifies the cross-BID conflict as a nuke flush.

This is fixture evidence for the reduced owner chain. It is not architectural
QEMU/DUT replacement evidence and does not prove live top scheduling can yet
create this timing window.

## Verification

Focused gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only ReducedStoreWaitReplayChiselPath
bash tools/chisel/run_chisel_reduced_store_wait_replay_chisel_path.sh
```

The wrapper emits SystemVerilog under
`generated/chisel-verilog/reduced-store-wait-replay-chisel-path`, builds a
Verilator executable, and writes:

```text
generated/chisel-reduced-store-wait-replay-chisel-path/report/reduced_store_wait_replay_chisel_path.json
```

The JSON schema is `linxcore.reduced_store_wait_replay_chisel_path.v1`. A
passing R452 report records `ready_forward_observed=true`,
`sta_wait_capture=true`, `not_ready_wake_blocked=true`,
`std_wake_clear=true`, `relaunch_queue_fire=true`, `liq_alloc=true`, and
`youngest_store_lsid=1`.

R453 extends the same report with `liq_refill=true`,
`liq_launch_valid=true`, `liq_launch_accepted=true`, and
`launch_load_lsid=3`. This remains generated-RTL fixture evidence: the refill
and launch arm are harness-driven, and the live reduced top still ties the
alloc-path refill wakeup inactive.

R454 extends the report with `liq_e4_update=true`, `liq_lhq_record=true`,
`liq_resolved=true`, and `e4_cycles_after_launch=1`. This proves the fixture
can carry the launched replay row through the existing `LoadForwardPipeline`
E4 outcome and `LoadInflightQueue` resolved-row write. It still is not live
QEMU/DUT replay-LIQ promotion evidence because the launch, source-return
sidebands, return readiness, and refill are harness-driven.

R455 extends the report with `resolve_queue_push=true`,
`liq_clear_resolved=true`, and `resolve_queue_count=1`. The probe now composes
the real `LoadResolveQueue` beside the LIQ path and applies the existing
delayed clear rule after the LHQ record is accepted. This remains fixture
evidence because the replay launch and return sidebands are still
harness-driven.

R456 extends the report with `resolve_queue_retired=true` and
`resolve_queue_count_after_retire=0`. The harness drives a commit watermark
one LSID newer than the resolved load, proving the same generated-RTL fixture
can drain the ResolveQ row through the model strict older-than retire rule
after the source LIQ row has been cleared.

R457 extends the report with `mdb_resolve_conflict=true`,
`mdb_nuke_flush=true`, `mdb_resolve_candidate_mask=1`, and
`mdb_conflict_load_lsid=3`. The probe composes `MDBConflictDetect` with the
real `LoadResolveQueue.conflictRows`, ties active LDQ rows inactive, and lets
the harness drive an older overlapping store probe before ResolveQ retire.
This proves generated RTL exposes the resolved replay row to the MDB conflict
classifier and applies the model cross-BID nuke classification. It is still
fixture evidence, not live MDB/recovery publication, because the store probe,
replay launch, refill, source-return sidebands, return readiness, and retire
watermark are harness-driven.
