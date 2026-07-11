# ScalarLSUMDBPath

## Source Mapping

- Chisel: `chisel/src/main/scala/linxcore/lsu/ScalarLSUMDBPath.scala`
- Tests: `chisel/src/test/scala/linxcore/lsu/ScalarLSUMDBPathSpec.scala`
- Generated-RTL probe:
  `chisel/src/main/scala/linxcore/lsu/ScalarLSUMDBPathProbe.scala`,
  `tools/chisel/scalar_lsu_mdb_path_probe_tb.cpp`
- Children: `MDBConflictDetect`, `MDBQueueFanout`, `MDBSSIT`,
  `LoadReplayMdbLookupWaitPlan`, `LoadWaitStoreTimeout`
- Model:
  - `model/LinxCoreModel/model/lsu/load_unit/ldq.cpp`:
    `handleStateQuery`, `handleDetect`, `updateMDBInfo`, `checkLoadPending`,
    `handleFlush`
  - `model/LinxCoreModel/model/lsu/mdb/MDB.cpp`: `MDB::Work`
  - `model/LinxCoreModel/model/lsu/store_unit/store_unit.cpp`:
    `StoreUnit::mdbCheck`
- Contract IDs: `LC-MA-MEM-001`, `LC-MA-HAZ-001`

## Purpose

`ScalarLSUMDBPath` is the canonical scalar memory-dependence predictor beneath
`ScalarLSULoadPath`. It composes the existing ISA-neutral conflict scan,
store-set predictor, finite command queues, atomic LU/SU fanout, wait-store
planning, and LIQ mutation protocol under one Linx recovery owner.

It does not implement ARM exception levels, exclusive monitors, barrier
encodings, or ARM acquire/release behavior. Architectural recovery is expressed
only through Linx PE/STID/TID and BID/GID/RID/LSID identity.

## Command Ordering

1. An accepted scalar load allocation enqueues a PC-keyed lookup before first
   launch. Tile loads do not enter this predictor.
2. An accepted address-bearing scalar store scans active LIQ and ResolveQ rows.
   Scalar store insertion waits when the MDB record or wait-plan queue cannot
   retain the event.
3. Every unresolved active conflict bit is captured as a retained wait plan.
   One LIQ row is mutated per cycle in lowest-slot order until the entire mask
   is consumed; a multi-row conflict cannot collapse into one pulse.
4. The oldest resolved conflict is converted to confidence-1 SSIT record
   traffic and BMDB report intent.
5. Lookup is processed before delete and record, matching `MDB::Work`. The LU
   and SU result queues advance atomically. A lookup hit remains resident until
   its LIQ wait mutation is accepted.
6. A ready matching STQ row emits a registered store-unit wakeup. Registration
   separates MDB dequeue from LIQ replay arbitration and prevents a
   combinational feedback path.
7. A stable predicted-store wait ages in `LoadWaitStoreTimeout`. Expiry has
   priority over new wait mutations and remains asserted until delete-queue
   capacity and native LIQ mutation acceptance are both available.
8. The accepted expiry clears `waitStore` and enqueues one delete command in
   the same cycle. Lookup/delete/record ordering then applies the model SSIT
   confidence/weight decay policy.

## LIQ Mutation

MDB lookup occurs while a newly allocated row is normally still `Wait`.
Therefore the native row-mutation policy explicitly permits `Wait` and
`Repick` targets for MDB wait operations and does not require prior SCB return
evidence. All same-cycle LIQ writer exclusions remain active: E4 update,
resolved clear, replay wake, refill, launch/pick, SCB return, mark-resolved, and
allocation conflicts hold the MDB result until mutation can apply.

The mutation sets `waitStore`, stores the predicted store BID/PC and native
STQ index/LSID when available, clears stale return state, and keeps the row in
`Wait`. A missing native store row retains the model-required BID/PC and uses a
disabled store LSID so later wake matching can use the documented wildcard.

The failed-wait mutation uses the same writer exclusions but performs the
inverse transition: it clears return state and `waitStore`, keeps the row in
`Wait`, and cannot apply unless `MDBQueueFanout.deleteIn` is ready. Delete valid
depends on mutation acceptance, so prediction state and LIQ state cannot
diverge under backpressure.

## Recovery

- Same-BID resolved conflict emits `FlushType.InnerFlush`.
- Cross-BID resolved conflict emits `FlushType.NukeFlush`.
- The request carries the conflicting load's PE, STID, TID, BID, GID, RID,
  LSID, scalar execution-engine class, and fetch-PC-valid bit.
- The outer recovery arbiter remains responsible for accepting the request and
  enforcing precise ROB-head nuke policy.
- Hard or typed precise recovery clears MDB command/output queues, retained
  wait plans, and registered store wakeups.
- SSIT predictor state survives ordinary recovery. Reset and explicit
  delete/confidence policy are its clearing mechanisms.

## Parameters

`ScalarLsuParams` independently sizes `mdbSsitEntries`,
`mdbCommandQueueEntries`, `mdbOutputQueueEntries`, and
`mdbWaitPlanQueueEntries`. `mdbFailedWaitTimeoutCycles` sets the per-row
failed-prediction interval. Confidence/weight policy remains parameterized by
`mdbReleaseWeight`, `mdbMaxWeight`, `mdbIncStep`, and `mdbConfWidth`. None of
these capacities defines ROB identity width.

## Current Boundary

IEX-local MDB training, outer recovery arbitration/ROB-head nuke consumption,
cache and miss queues, and final LRET publication remain future integration
work. Reduced CoreMark regression is a no-regression gate until a natural
workload produces positive canonical MDB counters.

## Verification

- `bash tools/chisel/run_chisel_tests.sh --only ScalarLSUMDBPathSpec`
- `bash tools/chisel/run_chisel_tests.sh --only MDBConflictDetectSpec`
- `bash tools/chisel/run_chisel_tests.sh --only MDBSSITSpec`
- `bash tools/chisel/run_chisel_tests.sh --only MDBQueueFanoutSpec`
- `bash tools/chisel/run_chisel_tests.sh --only LoadReplayMdbLookupWaitPlanSpec`
- `bash tools/chisel/run_chisel_tests.sh --only LoadWaitStoreTimeoutSpec`
- `bash tools/chisel/run_chisel_tests.sh --only LoadInflightRowMutation`
- `bash tools/chisel/run_chisel_tests.sh --only ScalarLSUSpec`
- `bash tools/chisel/run_chisel_scalar_lsu_mdb_path_probe.sh`

The generated-RTL probe positively exercises same-BID `InnerFlush`, cross-BID
`NukeFlush`, conflict-record acceptance and BMDB processing, SSIT allocation
and reinforcement, first-after-nuke suppression, a trained lookup hit held
under mutation backpressure, and accepted mutation of a pre-launch `Wait` row.
R636 extends the same probe with live per-row timeout: three accepted expiries
atomically clear the wait and enqueue delete feedback, producing two
below-stall decays followed by zero-weight SSIT release. A second probe lane
uses a real `LoadInflightQueue` row and native mutation port to prove four-cycle
threshold timing, retained expiry during a writer conflict, same-cycle delete
acceptance, and wait-store clear in the next registered row image. A parallel
one-cycle timer proves the minimum legal threshold and flush suppression.
