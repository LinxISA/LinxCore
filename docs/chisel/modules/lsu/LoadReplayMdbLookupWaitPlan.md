# LoadReplayMdbLookupWaitPlan

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayMdbLookupWaitPlan.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/LoadReplayMdbLookupWaitPlanSpec.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/mtccore/lsu/load_unit/ldq.cpp`
    - `LDQInfo::updateMDBInfo`
    - oldest wait-store delete publication path that pushes `delete_lu_mdb_q`
  - `model/LinxCoreModel/model/mtccore/lsu/MDB.cpp`
    - `MDB::Work`
  - `model/LinxCoreModel/model/mtccore/lsu/store_unit/store_unit.cpp`
    - store-side MDB lookup/wakeup scan
- Contract IDs: `LC-CHISEL-LSU-MDB-LOOKUP-WAIT-PLAN-001`

## Purpose

`LoadReplayMdbLookupWaitPlan` is the first standalone load-side consumer shape
for MDB lookup results after `MDBQueueFanout` has produced an LU hit.

The model `LDQInfo::updateMDBInfo` uses the LU lookup result to find a matching
working load by `(load BID, load LSID)`, marks the row as waiting on the
predicted store, stores the predicted store BID/PC, and makes the load pending
again. The same MDB lookup result also fans out to the store unit, where the
store-side scan can resolve a native STQ row when a resident store with the
predicted `(store BID, store PC)` is present and ready.

The current Chisel replay wakeup path needs native wait-store identity
(`storeIndex`, `storeLsId`, `storeId`, `pc`) before it can safely arm a future
`LoadStoreForwardWait`. The MDB LU result by itself carries store BID and PC,
but not the native STQ index or store LSID. This module therefore separates two
events:

1. `waitIntentValid`: a scalar MDB LU hit found exactly one resident repick LIQ
   row that matches the MDB load identity.
2. `requestValid`: the same wait intent also has a resolved native store index
   and store LSID supplied by a future SU/store-row matching owner.

The R463/R464 generated-RTL fixture wires this request shape into
`ReducedLoadReplayLiqAllocPath` row mutation for proof. R463 proves bridge and
control reachability for a live target row; R464 adds explicit SCB
source-return evidence through the existing replay-wakeup path and proves the
native LIQ row write. R498 connects the live top through
`LoadReplayRowMutationSourceMux`, but the first live lookup fixture still
misses before `waitIntentValid` or `requestValid` can assert.

## Interface

| Signal | Description |
|---|---|
| `enable` / `flush` | Gate the planner. |
| `luOutValid` / `luOut` | MDB LU output from `MDBQueueFanout`; must be valid, hit, scalar, and carry valid load/store sidecars. |
| `rows` | Current LIQ row image used for exact resident-row matching. |
| `storeIndexValid` / `storeIndex` | Native STQ row index from a future store-side match. Required for `requestValid`. |
| `storeLsIdValid` / `storeLsId` | Native store LSID from a future store-side match. Required for `requestValid`. |
| `candidateMask` / `candidateCount` | Repick scalar LIQ rows matching MDB load `(BID, LSID)`. |
| `targetValid` / `targetIndex` | Exactly one candidate row was found. |
| `waitIntentValid` | MDB LU hit can name the waiting load row, independent of native store identity readiness. |
| `requestValid` / `requestTarget*` | Native LIQ row-mutation request shape is safe to consume. |
| `setWaitStatus` / `clearReturnState` / `lineWrite` / `waitStoreWrite` | Future row write intent for returning the target repick row to wait-store state. |
| `nextWaitStoreInfo` | Native wait-store identity. Valid only when `requestValid` is high. |
| `blockedBy*` | Diagnostic blockers for disabled/flush, miss, missing sidecars, tile suppression, no target, multi-target, and missing native store identity. |

## Logic Design

The planner is combinational:

```text
lookupHit =
  enable && !flush &&
  luOutValid && luOut.valid && luOut.hit &&
  luOut.ldInfo.{valid,bid.valid,lsId.valid} &&
  luOut.stInfo.{valid,bid.valid} &&
  !luOut.ldInfo.isTile && !luOut.stInfo.isTile

candidate[i] =
  lookupHit &&
  row[i].valid &&
  row[i].status == Repick &&
  !row[i].isTile &&
  row[i].bid == luOut.ldInfo.bid &&
  row[i].loadLsId == luOut.ldInfo.lsId

waitIntentValid = PopCount(candidate) == 1
requestValid = waitIntentValid && storeIndexValid && storeLsIdValid && storeLsId.valid
```

When `requestValid` is true, the output row-mutation shape clears accumulated
return state and line-valid state, writes `waitStore=true`, and records the
native store identity:

- `storeIndex` from the external resolved store-row match,
- `storeId` from `luOut.stInfo.bid`,
- `storeLsId` from the external resolved store-row match,
- `pc` from `luOut.stInfo.pc`.

The explicit `waitIntentValid`/`requestValid` split prevents the LU result from
inventing a store index or store LSID. A later owner can either provide those
fields from `MDBQueueFanout` SU matching or from a resident STQ row scan before
wiring this plan into `LoadInflightQueue` mutation.

## Deferred Owners

- Live positive MDB lookup hit after a learned same-PC load occurs after record
  publication and the model first-after-nuke suppression window.
- Live source-return evidence owner for default/top MDB wait mutation.
- Live failed-wait timer that publishes MDB delete commands from LIQ state.

## Verification

Focused gate:

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadReplayMdbLookupWaitPlan
```

Reference tests cover exact one-row planning, missing native store index/LSID
blocking, multi-target suppression, tile suppression, disabled/flush/miss and
metadata blockers, and Chisel elaboration.

Generated-RTL fixture gate:

```bash
bash tools/chisel/run_chisel_tests.sh --only ReducedStoreWaitReplayChiselPath
bash tools/chisel/run_chisel_reduced_store_wait_replay_chisel_path.sh
```

R462 composes this planner into `ReducedStoreWaitReplayChiselPathProbe` behind
`MDBQueueFanout`. The fixture report records
`mdb_lookup_wait_plan_no_target=true` and
`mdb_lookup_wait_plan_candidate_mask=0` at the existing MDB lookup point. That
lookup still has `mdb_lookup_hit=true`, `mdb_su_wakeup=true`, and
`mdb_su_wakeup_store_index=0`, so the planner is seeing the LU hit plus
store-side native identity but refusing to emit `requestValid` because the
source LIQ row has already resolved and cleared. This proves the no-target
guard in generated RTL; it is not a positive live mutation proof yet.

R463 wires the planner's `request*` outputs into the fixture-local
`ReducedLoadReplayLiqAllocPath` row-mutation inputs. The same generated-RTL
fixture now drives a second live load identity into MDB lookup while that load
is resident in LIQ row 1 and has just entered `Repick`. The report records
`mdb_lookup_wait_plan_live_target=true`,
`mdb_lookup_wait_plan_request=true`, `mdb_lookup_wait_plan_bridge=true`,
`mdb_lookup_wait_plan_control_blocked=true`,
`mdb_lookup_wait_plan_live_candidate_mask=2`, and
`mdb_lookup_wait_plan_live_target_index=1`. This proves the request can reach
the native LIQ mutation bridge/control surface for a live target row. It still
is not a row-write proof because the existing control path blocks without
source-return evidence.

R464 keeps the same live-target lookup but first drives an SCB replay wake
through the reduced LIQ path, proving row 1 has
`scbReturned/sourcesReturned` evidence before launch. The generated-RTL report
records `mdb_lookup_wait_plan_scb_evidence=true`,
`mdb_lookup_wait_plan_control_blocked=false`,
`mdb_lookup_wait_plan_write=true`, `mdb_lookup_wait_plan_apply=true`,
`liq_replay_wake_completed_mask=2`, and
`liq_wait_store_mask_after_mdb_write=2`. This proves the planner request can
produce a native wait-store row mutation when the existing LIQ write-control
preconditions are satisfied. It remains fixture evidence; live MDB lookup
timing and live SCB response ownership are still deferred.

R493 makes that generated-RTL row-write evidence mandatory in the wrapper:
`run_chisel_reduced_store_wait_replay_chisel_path.sh` now validates the JSON
schema and fails unless the R464 SCB evidence, row write, row apply, and
post-write wait/wait-store masks are present by default. This does not promote
the fixture to live-top evidence; it prevents later packets from silently
regressing the only positive MDB wait-plan row-mutation proof while live QEMU
still reports the R491 zero-counter MDB boundary.

R498 connects this planner's raw request fields into the live replay-LIQ row
mutation path through `LoadReplayRowMutationSourceMux`, with source-return
mutation taking priority. The same packet moves the live lookup producer from
`ReducedLoadReplayLiqAllocPath.launchAccepted` to
`LoadReplaySourceReturnStoreSnapshotPath.queryIssueIssued`, matching the model
`LDQInfo::handleStateQuery` boundary more closely. The live
`replay-ldi-sdi-ldi` gate at
`generated/r498-replay-liq-mdb-lookup-query-gate` passes QEMU/DUT comparison
with `mdb_fanout_lookup_valid=1`, `mdb_fanout_lookup_accepted=1`,
`mdb_fanout_lookup_processed=1`, `mdb_fanout_lu_out_valid=1`, and
`mdb_fanout_lu_out_hit=0`. Therefore `mdb_lookup_wait_plan_lookup_hit=0`,
`mdb_lookup_wait_plan_wait_intent_valid=0`, and
`mdb_lookup_wait_plan_request_valid=0` remain the expected live result for this
fixture. A later packet needs a repeated same-PC post-record lookup or
equivalent model stimulus before claiming live wait-plan mutation.
