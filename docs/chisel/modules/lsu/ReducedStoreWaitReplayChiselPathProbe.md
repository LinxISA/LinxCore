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
- `MDBQueueFanout`
- `LoadReplayMdbLookupWaitPlan`

The probe exists because R450 showed that delaying split STD data in the live
top does not force the younger load to execute while the resident store is
address-ready and data-late. This fixture drives that microarchitectural
window directly and proves the owner chain through generated RTL.

## Sizing and Authority

`lsidWidth` is independent of `entries` and defaults to 40 for this fixture.
It sizes STQ rows, resident forwarding, selected wait keys, replay wakeups,
LIQ/ResolveQ rows, and MDB payloads. The fixture accepts explicit
`loadLsIdFullValid/loadLsIdFull` inputs, captures them with the waiting load,
and retains them per allocated LIQ slot for ResolveQ and MDB planner evidence.
It never reconstructs full LSID from the projected `ROBID` fields.

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
| `loadLsIdFullValid` / `loadLsIdFull` | Explicit full-LSID authority and value retained by the fixture for ResolveQ/MDB evidence. |
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
| `liqReplayWakeValid` / `liqReplayWake` | Fixture-driven typed `LoadReplayWakeupRequest` passed through the reduced LIQ path for source-return evidence. |
| `liqReplayWakeMergeMask` / `liqReplayWakeCompletedMask` | Child LIQ replay-wakeup merge and completion masks. |
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
| `mdbFanoutRecordReady` / `mdbFanoutRecordAccepted` / `mdbFanoutRecordProcessed` | Fixture-local `MDBQueueFanout.recordIn` queue and process diagnostics. |
| `mdbFanoutBmdbReportValid` / `mdbFanoutBmdbLoadBid` / `mdbFanoutBmdbStoreBid` / `mdbFanoutBmdbStoreStid` | BMDB report intent produced when `MDBQueueFanout` accepts the conflict record into `MDBSSIT`. |
| `mdbFanoutSsitValidMask` | Fixture-local SSIT valid rows after the conflict record is processed. |
| `mdbLookupValid` | Fixture-owned MDB lookup request for the resolved load. |
| `mdbFanoutLookupReady` / `mdbFanoutLookupAccepted` / `mdbFanoutLookupProcessed` | `MDBQueueFanout.lookupIn` queue and lookup-processing diagnostics. |
| `mdbDeleteValid` / `mdbDeleteWaitStorePc` | Fixture-owned failed-wait delete request equivalent to `delete_lu_mdb_q`; `mdbDeleteWaitStorePc` supplies model `wait_tpc`. |
| `mdbFanoutDeleteReady` / `mdbFanoutDeleteAccepted` / `mdbFanoutDeleteProcessed` | `MDBQueueFanout.deleteIn` queue and delete-processing diagnostics. |
| `mdbFanoutDeleteMatched` / `mdbFanoutDeleteDroppedBelowStall` / `mdbFanoutDeleteReleased` | SSIT delete result diagnostics for decay below stall threshold and final zero-weight release. |
| `mdbFanoutLuOutDequeued` / `mdbFanoutLuOutHit` | LU lookup-result fanout diagnostics. |
| `mdbFanoutSuOutDequeued` / `mdbFanoutSuOutHit` | SU lookup-result fanout diagnostics. |
| `mdbFanoutSuMatchedStore` / `mdbFanoutSuStorePending` / `mdbFanoutSuWakeupValid` | Store-side MDB check result against the resident STQ rows. |
| `mdbFanoutSuWakeupStoreIndex` / `mdbFanoutSuWakeupBid` | Store-side wakeup identity selected by `MDBQueueFanout`. |
| `mdbLookupUseLiveLoad` | Fixture selector that builds `MDBQueueFanout.lookupIn.ldInfo` from the current live load identity inputs instead of the resident ResolveQ row. |
| `mdbLookupWaitPlanLookupHit` | Fixture-local `LoadReplayMdbLookupWaitPlan` saw the LU lookup hit. |
| `mdbLookupWaitPlanCandidateMask` / `mdbLookupWaitPlanTargetIndex` | Current LIQ row match diagnostics for the LU hit. |
| `mdbLookupWaitPlanWaitIntentValid` / `mdbLookupWaitPlanRequestValid` | Load-side wait intent and native mutation request diagnostics from the planner. |
| `mdbLookupWaitPlanBlockedByNoTarget` | The planner saw an LU hit but found no current matching LIQ row. |
| `mdbLookupWaitPlanBlockedByMissingStoreIndex` / `mdbLookupWaitPlanBlockedByMissingStoreLsId` | Native store identity blockers from the planner. |
| `rowMutationBridgeValid` / `rowMutationTargetEvidenceValid` | LIQ row-mutation bridge/control evidence for the planner request. |
| `rowMutationWriteEnable` / `rowMutationApplyValid` | Native LIQ row-mutation write and apply diagnostics. |
| `rowMutationBlockedByControl` / `rowMutationControlBlockedByScbNotReturned` / `rowMutationControlBlockedByE4UpdateConflict` | Control blockers proving why a valid fixture request did not write the LIQ row. |
| `liqClearResolvedPending` / `liqClearResolvedAccepted` | Probe-local delayed clear request back into LIQ after ResolveQ accepts the LHQ record. |
| `liqWaitMask` / `liqWaitStoreMask` / `liqRepickMask` / `liqResolvedMask` | LIQ row status and wait-store masks before launch, after launch, and after MDB/E4 mutation. |
| `liqSourcesReturnedMask` / `liqScbReturnedMask` / `liqStqReturnedMask` | LIQ row source-return evidence masks. |
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
   `e4WakeupValid`, and `lhqRecordValid`. The fixture then performs the
   explicit `markResolved` handshake; E4 does not bypass the return owner.
5. The probe appends the LHQ hit record to `LoadResolveQueue` on the E4
   cycle. A fixture-local pending-clear register captures the accepted
   `loadId.value`, waits for `markResolved`, and only then drives
   `clearResolved`, clearing the LIQ row while the ResolveQ record remains
   resident.
6. The harness drives a cross-BID ResolveQ retire watermark, which needs no
   same-BID LSID ordering authority. The fixture intentionally keeps retire
   full-LSID valid low because it has no independent retire full-LSID input;
   the following cycle compacts ResolveQ to empty without fabricating one.
7. Before the retire watermark, the harness drives a fixture-owned store probe
   matching the older resident store. `MDBConflictDetect` consumes
   `LoadResolveQueue.conflictRows`, selects ResolveQ row zero as a scalar
   conflict, and classifies the cross-BID conflict as a nuke flush.
8. The same conflict cycle feeds the selected record into a fixture-local
   `MDBQueueFanout`. The next cycle processes the queued record, publishes
   BMDB report intent, and allocates an SSIT row. Lookup, delete, and
   store-wakeup fanout remain tied off in this fixture.
9. The harness drives a second identical conflict record to reinforce the SSIT
   row above the model stall threshold, then drives two lookup requests. The
   first lookup after nuke learning is suppressed and clears the nuke marker;
   the second lookup fans out a hit to LU and SU. The SU path scans the
   resident STQ rows and emits a store wakeup for row zero.
10. The harness then drives three fixture-owned MDB delete requests with the
    learned load PC and store wait PC. The first two deletes decay the SSIT row
    below the stall threshold while retaining the row, and the third delete
    releases the zero-weight row, matching `MDB::handleMDBDelete` /
    `MDB::dec`.
11. R462 composes `LoadReplayMdbLookupWaitPlan` beside `MDBQueueFanout`. At the
    existing second lookup point, LU/SU lookup hits and SU wakeup has native
    store identity, but the original source LIQ row has already resolved and
    been cleared into ResolveQ. The planner therefore reports
    `blockedByNoTarget` with an empty candidate mask and does not emit a native
    wait-store mutation request. This is the desired guard for this fixture
    ordering; a positive mutation proof needs a later dynamic load that is
    currently resident in LIQ when the learned SSIT lookup hits.
12. R463 wires the planner request into `ReducedLoadReplayLiqAllocPath`'s
    existing row-mutation bridge and adds a fixture live-lookup selector. The
    harness allocates a second replay row, accepts the live MDB lookup one
    cycle before launching that row, and samples the LU/SU hit after the row
    enters `Repick`. The planner now reports a live candidate at LIQ row 1 and
    emits `requestValid`; the request reaches the bridge/control path, then
    stops because the fixture has not yet supplied source-return evidence for a
    safe row write.
13. R464 drives the existing LIQ replay-wakeup path with an SCB wake for the
    second row before launch. `LoadReplayWakeup` marks row 1
    `scbReturned/sourcesReturned`, launch preserves that evidence into
    `Repick`, and the MDB wait-plan request then passes native write control.
    The generated-RTL harness requires `rowMutationWriteEnable=true`,
    `rowMutationApplyValid=true`, and the following row image to return to
    `Wait` with `waitStore` armed and return-state evidence cleared.

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

R493 adds `tools/chisel/validate_reduced_store_wait_replay_chisel_path.py` and
makes the wrapper validate the R464 positive MDB wait-plan row-write proof by
default. The packet also ties the newer `ReducedLoadReplayLiqAllocPath`
`pick`, `scbReturn`, and `markResolved` inputs inactive in this probe, because
this fixture's R464 positive path uses `LoadReplayWakeup` for SCB evidence and
does not exercise those later live-top ports. A passing wrapper run now
requires:

- `mdb_lookup_wait_plan_scb_evidence=true`
- `mdb_lookup_wait_plan_write=true`
- `mdb_lookup_wait_plan_apply=true`
- `mdb_lookup_wait_plan_wait_status_after_write=true`
- nonzero `liq_replay_wake_completed_mask`
- nonzero `liq_scb_returned_mask_before_mdb_write`
- nonzero `liq_sources_returned_mask_before_mdb_write`
- nonzero `liq_wait_mask_after_mdb_write`
- nonzero `liq_wait_store_mask_after_mdb_write`

The requirement lists can be overridden with
`REDUCED_STORE_WAIT_REPLAY_REQUIRE_TRUE` and
`REDUCED_STORE_WAIT_REPLAY_REQUIRE_NONZERO` when a future packet deliberately
builds a narrower diagnostic report. The default remains a regression guard
for the generated-RTL proof that learned MDB lookup can write a live LIQ row
back to native wait-store state after SCB source-return evidence arrives.

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

R458 extends the report with `mdb_fanout_record_accepted=true`,
`mdb_fanout_record_processed=true`, `mdb_bmdb_report=true`, and
`mdb_fanout_ssit_valid_mask=1`. The probe converts the selected
`MDBConflictDetect.record` into the same `MDBQueueBus` shape used by the live
top and feeds a fixture-local `MDBQueueFanout.recordIn` path. This proves
generated RTL can move the ResolveQ conflict record into MDB record learning
and BMDB report intent. It is still fixture evidence: lookup/delete producers,
store-wakeup fanout, live BMDB mutation, recovery publication, and ROB nuke
retirement are not driven here.

R459 extends the report with `mdb_fanout_record_reinforced=true`,
`mdb_lookup_first_suppressed=true`, `mdb_lookup_hit=true`,
`mdb_su_wakeup=true`, and `mdb_su_wakeup_store_index=0`. The probe now feeds
`MDBQueueFanout.lookupIn` from the resident ResolveQ row and exposes real
resident STQ rows to the SU wakeup scan. The harness follows the model SSIT
contract: reinforce the same load/store pair, consume the first-after-nuke
suppressed lookup, then prove the later lookup hits and wakes the ready store.
This remains fixture evidence because lookup timing is harness-owned and no
live load wait-state mutation, recovery flush publication, or ROB nuke
retirement is driven.

R460 extends the report with `mdb_delete_accepted=true`,
`mdb_delete_dropped_below_stall=true`, and `mdb_delete_released=true`. The
probe now feeds `MDBQueueFanout.deleteIn` from the resident ResolveQ load PC
plus fixture-provided `wait_tpc`, proving generated RTL can decay the learned
SSIT row after a failed wait and release it only after the model zero-weight
delete. This remains fixture evidence because the failed-wait timer and delete
producer are harness-owned.

R462 extends the report with `mdb_lookup_wait_plan_no_target=true` and
`mdb_lookup_wait_plan_candidate_mask=0` while preserving the R459
`mdb_lookup_hit=true`, `mdb_su_wakeup=true`, and
`mdb_su_wakeup_store_index=0` fields. This proves the fixture-composed
`LoadReplayMdbLookupWaitPlan` sees the LU hit and resolved SU store identity,
but correctly suppresses a native wait-store mutation request because the
source LIQ row has already been cleared after ResolveQ accepted the LHQ record.
The next positive mutation proof must use a later dynamic load row that is
still resident in LIQ when the learned MDB lookup hits.

R463 extends the report with `mdb_lookup_wait_plan_live_target=true`,
`mdb_lookup_wait_plan_request=true`, `mdb_lookup_wait_plan_bridge=true`,
`mdb_lookup_wait_plan_control_blocked=true`,
`mdb_lookup_wait_plan_live_candidate_mask=2`, and
`mdb_lookup_wait_plan_live_target_index=1`. The probe now wires
`LoadReplayMdbLookupWaitPlan.request*` into `ReducedLoadReplayLiqAllocPath`
row mutation and lets the harness drive a second live load identity into MDB
lookup. This proves generated RTL can carry a learned SSIT lookup into a
specific live LIQ `Repick` row and through the row-mutation bridge/control
surface. It is still not a positive LIQ row-write proof because the control
path correctly blocks the write until source-return evidence is available.

R464 extends the report with `mdb_lookup_wait_plan_scb_evidence=true`,
`mdb_lookup_wait_plan_control_blocked=false`,
`mdb_lookup_wait_plan_write=true`, `mdb_lookup_wait_plan_apply=true`,
`mdb_lookup_wait_plan_wait_status_after_write=true`,
`liq_replay_wake_completed_mask=2`,
`liq_scb_returned_mask_before_mdb_write=2`,
`liq_sources_returned_mask_before_mdb_write=2`,
`liq_wait_mask_after_mdb_write=2`, and
`liq_wait_store_mask_after_mdb_write=2`. The proof is still generated-RTL
fixture evidence: the SCB wake, live MDB lookup timing, and launch arm are
harness-driven, but the row write itself uses the native
`LoadReplayWakeup -> LoadInflightQueue -> LoadInflightRowMutationPath`
ownership path.
