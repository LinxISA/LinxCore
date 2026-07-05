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

This packet does not mutate `LoadInflightQueue` rows and is not top-integrated
yet.

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

- Top integration behind the replay-LIQ path.
- Store-side native identity producer for MDB LU wait mutation.
- Registered `LoadInflightQueue` mutation using the existing row-mutation
  bridge/write-control path or a dedicated MDB update writer.
- Live failed-wait timer that publishes MDB delete commands from LIQ state.

## Verification

Focused gate:

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadReplayMdbLookupWaitPlan
```

Reference tests cover exact one-row planning, missing native store index/LSID
blocking, multi-target suppression, tile suppression, disabled/flush/miss and
metadata blockers, and Chisel elaboration.
