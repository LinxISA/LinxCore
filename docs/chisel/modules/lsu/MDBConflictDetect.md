# MDBConflictDetect

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/MDBConflictDetect.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/MDBConflictDetectSpec.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/lsu/store_unit/store_unit.cpp`
  - `model/LinxCoreModel/model/lsu/store_unit/stq.cpp`
  - `model/LinxCoreModel/model/lsu/load_unit/ldq.cpp`
  - `model/LinxCoreModel/model/lsu/load_unit/ldq.h`
  - `model/LinxCoreModel/model/lsu/mdb/MDB.cpp`
  - `model/LinxCoreModel/model/ModelCommon/bus/MemReqBus.h`
- Related Chisel contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/STQCommitQueue.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadResolveQueue.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/rob/ROBID.scala`
- Contract IDs: `LC-CHISEL-LSU-MDB-CONFLICT-001`

## Purpose

`MDBConflictDetect` is the first Chisel owner for the model store-arrival
load/store conflict classifier. It consumes a store probe from the future STQ
insert path, scans active LDQ rows plus the resolved-load queue, and emits the
oldest scalar load that must be reported to MDB and recovery.

R290 wires the detector into the replay-LIQ reduced top as a diagnostic
composition point. The top builds the store probe from the accepted
`StoreDispatchSTQPath` insert request, builds active load entries from
`ReducedLoadReplayLiqAllocPath` resident LIQ rows, and feeds resolved rows from
`LoadResolveQueue.conflictRows`. The detector's candidate masks, selected
record, and inner/nuke classification are exposed through top-level diagnostics,
but no MDB fanout, ready-store wakeup, or recovery flush is emitted yet.
R291 feeds the selected `record` into the diagnostic `MDBQueueFanout.recordIn`
path with model confidence `1`, while lookup/delete producers, recovery flush,
and live load/store wakeup side effects remain deferred.
R457 also proves the detector at a smaller executable fixture boundary:
`ReducedStoreWaitReplayChiselPathProbe` feeds a real `LoadResolveQueue`
`conflictRows` export into a local `MDBConflictDetect`, ties active rows
inactive, and drives a harness-owned older overlapping store probe before
ResolveQ retire. The generated-RTL report observes the ResolveQ candidate mask,
selected load LSID, and cross-BID nuke classification. This is fixture
composition evidence only; it does not publish live MDB or recovery effects.

The module owns:

- scalar address-overlap detection for store probes against load entries,
- model `(bid, lsID)` age filtering through the existing `ROBID` helper,
- tile-conflict suppression until the model TODO is implemented,
- ST_ADDR wait-store masks for younger unresolved active loads,
- oldest resolved-load selection across active LDQ rows and `ResolveQ`,
- same-BID `innerFlush` versus cross-BID `nukeFlush` classification.

It does not own the MDB SSIT table, `lookup_lu_mdb_q`, `lookup_mdb_su_q`,
store wakeup, STQ/SCB byte forwarding, ROB nuke retirement, BCTRL `bmdb`,
IEX-local MDB learning, or final flush publication.

## Interface

### Inputs

| Signal | Type | Description |
|---|---|---|
| `store` | `MDBConflictStoreProbe` | Store probe equivalent to the `MemReqBus` pushed into `detect_su_lu_q`. |
| `store.valid` | `Bool` | Probe is live. |
| `store.addrOnly` | `Bool` | Models `ST_ADDR`; enables unresolved-load wait-store marking. |
| `store.isTile` | `Bool` | Suppresses tile conflict reporting for now, matching the model TODO. |
| `store.bid/store.lsId` | `ROBID` | Store age identity. The store must be older than or equal to the load to conflict. |
| `store.pc/addr/size` | `UInt` | Store PC and byte range. |
| `activeLoads` | `Vec[MDBConflictLoadEntry]` | Active LDQ cluster rows. Rows with `resolved=1` may become flush candidates; rows with `resolved=0` may become wait-store rows for `ST_ADDR`. |
| `resolvedQueue` | `Vec[MDBConflictLoadEntry]` | Resolved-load queue rows equivalent to `ResolveQ::entryArr`, supplied by `LoadResolveQueue` in the replay-LIQ top integration. |

### Outputs

| Signal | Description |
|---|---|
| `activeCandidateMask` | Active LDQ rows that are scalar, resolved, overlapping, and younger/equal to the store. |
| `resolveCandidateMask` | `ResolveQ` rows with the same scalar conflict predicate. |
| `tileSuppressedActiveMask` / `tileSuppressedResolveMask` | Rows that would conflict by address and age but involve tile load/store traffic. |
| `waitStoreMask` | Active unresolved scalar rows that must wait for an `ST_ADDR` store probe. |
| `waitStoreCount` | Popcount of `waitStoreMask`. |
| `conflictValid` | At least one resolved scalar conflict exists. |
| `conflictFromResolveQueue` | Selected conflict came from `resolvedQueue`, not `activeLoads`. |
| `conflictActiveIndex` / `conflictResolveIndex` | Index of the selected load in the relevant source. |
| `conflictOrdinal` | Combined ordinal, with active rows first and resolved-queue rows after them. |
| `record` | Load/store record payload for future `record_lu_mdb_q`, `bmdb`, IEX MDB, and flush owners. |
| `innerFlush` | Selected load and store have the same BID. Future recovery emits `INNER_FLUSH`. |
| `nukeFlush` | Selected load and store have different BIDs. Future recovery emits `NUKE_FLUSH` attributed to the load. |

## State

`MDBConflictDetect` is stateless. In R290 it sits between the reduced top's
accepted STQ insert request, replay-LIQ resident rows, ResolveQ rows, and later
MDB/recovery publication owners.

## Logic Design

The model source path is:

1. `StoreUnit::insertStq` pushes store requests into `detect_su_lu_q` when a
   store row is inserted.
2. `LDQInfo::conflictDetect` pops that queue and calls `handleDetect`.
3. `handleDetect` scans active LDQ clusters. Overlap plus
   `LessEqual(store.bid, store.lsID, load.bid, load.lsID)` is the conflict
   predicate.
4. Tile load/store conflicts are skipped by a model TODO.
5. Resolved active loads are flush candidates. Unresolved active loads are
   marked wait-store only for `ST_ADDR` probes.
6. `ResolveQ::detect` applies the same predicate to resolved queue rows.
7. `handleDetect` selects the oldest conflicting load by `(bid, lsID)`,
   records MDB learning, reports BCTRL `bmdb`, updates IEX-local MDB, and
   calls `handleFlush`.
8. `handleFlush` emits an inner flush when store BID equals load BID; otherwise
   it emits a nuke flush attributed to the load.

The Chisel module implements steps 3 through 8 up to the classification
boundary. It preserves the model's tie-breaking behavior by scanning active
rows first, resolved-queue rows second, and allowing a later equal-age
candidate to replace an earlier one because the C++ uses `LessEqual`.

## Timing

All outputs are combinational over the current store probe and load snapshots.
The future integrated LDQ/STQ owner decides whether to register the probe,
pipeline the scan, or partition it by LDQ cluster.

## Flush/Recovery

The module does not directly emit `FlushBus`. It emits the selected load/store
record plus `innerFlush` or `nukeFlush` so a later recovery owner can build the
model `FlushReq`:

- flush PC comes from the selected load PC,
- RID, BID, GID, LSID, PE, thread, and STID come from the selected load,
- cross-BID conflicts become load-attributed nuke flushes,
- same-BID conflicts become inner flushes unless a valid block command blocks
  the model path.

ROB nuke retirement remains outside this owner. Later ROB integration must
preserve the existing LinxCore rule that nuke intent is attributed to the load
and only redirects when that load reaches the ROB head.

## Trace/Observability

`record`, candidate masks, tile-suppressed masks, and wait-store masks are
designed for later memory/recovery trace rows. R290 exposes the same signals at
`LinxCoreFrontendFetchRfAluTraceTop` for generated-RTL visibility, and R291
passes the selected record into `MDBQueueFanout` diagnostics, but QEMU and
LinxCoreModel architectural rows cannot observe MDB effects directly until live
memory or recovery events are emitted from the Chisel top.

## R672 Full-LSID Contract

Scalar conflict eligibility and oldest-conflict selection compare same-BID
loads and stores with full modular LSID order. Missing full authority blocks a
same-BID conflict instead of accepting a low-bit alias. Tile rows remain
suppressed without importing the ARM-specific model bypass.

## Verification

- `bash tools/chisel/run_chisel_tests.sh --only MDBConflictDetect`
- `bash tools/chisel/run_chisel_tests.sh --only LoadResolveQueue`
- `bash tools/chisel/run_chisel_tests.sh --only ReducedStoreWaitReplayChiselPath`
- `bash tools/chisel/run_chisel_reduced_store_wait_replay_chisel_path.sh`
- `bash tools/chisel/run_chisel_tests.sh --only DecodeRenameROBPath`
- `bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop`
- `FETCH_REDUCED_STORE_REPLAY_LIQ=1 BUILD_DIR=generated/r290-replay-liq-mdb-detect-xcheck bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh`
- `bash tools/chisel/run_chisel_tests.sh --only STQCommitQueue`
- `bash tools/chisel/run_chisel_tests.sh --only STQEntryBank`
- `bash tools/chisel/run_chisel_tests.sh --only STQSCBCommitPath`
- `bash tools/chisel/run_chisel_rob_bookkeeping.sh --reduced-rob`
- `python3 tools/chisel/trace_schema_adapter.py --self-test`
- `bash tools/chisel/run_chisel_qemu_crosscheck.sh --dry-run`

Focused reference tests cover same-BID inner conflict, oldest selection across
active LDQ rows and `ResolveQ`, younger-store rejection, `ST_ADDR` wait-store
marking, tile suppression, zero-size non-overlap, and Chisel elaboration.
The R457 generated-RTL fixture report additionally records
`mdb_resolve_conflict=true`, `mdb_nuke_flush=true`,
`mdb_resolve_candidate_mask=1`, and `mdb_conflict_load_lsid=3` for a
ResolveQ-only conflict path.
