# LoadResolveQueue

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadResolveQueue.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/LoadResolveQueueSpec.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/lsu/load_unit/ldq.cpp`
    - `ResolveQ::insert`
    - `ResolveQ::retired`
    - `ResolveQ::detect`
    - `LDQInfo::CheckMovRslvQ`
    - `LDQInfo::handleDetect`
  - `model/LinxCoreModel/model/lsu/load_unit/ldq.h`
- Related Chisel contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadInflightQueue.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/MDBConflictDetect.scala`
- Contract IDs: `LC-CHISEL-LSU-LDQ-RSLVQ-001`

## Purpose

`LoadResolveQueue` is the first Chisel owner for the model `ResolveQ`
boundary. It accepts resolved load hit records from `LoadInflightQueue`,
retains their load identity, PC, address, size, and thread sidecars, and
exports `MDBConflictLoadEntry` rows that `MDBConflictDetect` can scan when a
younger store arrives.

R285 wires the opt-in replay-LIQ top path's path-local `lhqRecord` output into
this queue and exposes queue/head diagnostics. R286 adds the matching
parent-owned delayed `clearResolved` request for that opt-in top path. That
integration is still storage-only because replay launch remains disabled in the
current fixture. Precise retire/flush sidebands, default/live LIQ insertion, and
exported conflict rows feeding the live MDB/recovery path remain deferred owner
packets.

## Interface

### Push

| Signal | Description |
|---|---|
| `pushValid` | One resolved load record is available. |
| `pushPeId` / `pushStid` / `pushTid` | Thread sidecars paired with the record. |
| `pushRecord` | `LoadHitRecord` from `LoadInflightQueue`, including load ID, BID/GID/RID, load LSID, PC, address, size, byte mask, line data, and forwarded mask. |
| `pushReady` | Queue has capacity after same-cycle retire compaction and is not flushing. |
| `pushAccepted` | Record was appended after same-cycle retired rows are removed. |
| `pushInsertIndex` | Packed insertion index used for diagnostics. |

### Retire

| Signal | Description |
|---|---|
| `retireValid` | Commit identity is valid for model-style `ResolveQ::retired` pruning. |
| `retireBid` / `retireLsId` | Commit row identity. Rows strictly older by `(BID, LSID)` are removed. |
| `retireMask` | Pre-cycle queue rows retired this cycle. |
| `retireCount` | Number of retired rows. |

### Outputs

| Signal | Description |
|---|---|
| `entries` | Packed queue entries retaining the full `LoadHitRecord` plus PE/STID/TID sidecars. |
| `conflictRows` | Resolved-load view for `MDBConflictDetect`; `resolved` is always true for valid rows, `isTile` is false in this scalar queue, and `lsId` comes from `LoadHitRecord.loadLsId`. |
| `validMask` | Valid packed queue rows. |
| `count` / `empty` / `full` | Occupancy diagnostics. |

## State

The module owns a packed register queue of resolved scalar load records and an
occupancy count. It does not own active LIQ rows, store probes, MDB records,
ROB nuke state, precise flush pruning, or final recovery publication.

## Logic Design

The C++ model has a simple owner split:

1. `LDQInfo::returnData` resolves a load, and `CheckMovRslvQ` moves resolved
   rows into `ResolveQ`.
2. `ResolveQ::insert` appends the resolved `MemReqBus`.
3. `ResolveQ::retired` removes queue rows strictly older than the commit
   identity.
4. `ResolveQ::detect` scans remaining rows with the same address-overlap and
   `(BID, LSID)` age predicate used for active LDQ rows.

The Chisel queue implements the storage half of those rules:

1. Build a queue entry from `pushRecord` and explicit thread sidecars.
2. On each cycle, mark rows for retire when
   `row.(BID, loadLsId) < retire.(BID, LSID)`.
3. Compact non-retired rows in order.
4. Append one accepted push after the compacted tail.
5. Convert every valid entry into an `MDBConflictLoadEntry` with the preserved
   load PC, address, size, BID/GID/RID, and load LSID.

The strict retire comparison intentionally does not remove a row with exactly
the same `(BID, LSID)` as the commit identity, matching `ResolveQ::retired`.

## Timing

Push and retire can occur in the same cycle. Retire compaction happens before
push capacity and insertion-index calculation, so a full queue can accept a
new record when at least one older row retires in the same cycle. Outputs show
the pre-cycle queue image; the next-cycle image reflects retire and push.

## Flush/Recovery

`flush` currently clears the whole queue. Precise model `FlushBus` matching is
deferred until the top-level load/recovery owner can provide exact BID/GID/RID
flush intents.

## Deferred Owners

- Default/live LIQ insertion beyond the opt-in replay-LIQ diagnostic path.
- Clear-resolved feedback outside the opt-in replay-LIQ top path.
- Precise `FlushBus` pruning by recovery intent.
- Retire sideband wiring from ROB commit identity.
- Live MDB/recovery publication using `MDBConflictDetect`.

## Verification

Focused gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadResolveQueue
bash tools/chisel/run_chisel_tests.sh --only LoadInflightQueue
bash tools/chisel/run_chisel_tests.sh --only MDBConflictDetect
bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop
FETCH_REDUCED_STORE_REPLAY_LIQ=1 BUILD_DIR=generated/r285-replay-liq-resolveq-xcheck bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh
FETCH_REDUCED_STORE_REPLAY_LIQ=1 BUILD_DIR=generated/r286-replay-liq-resolve-clear-xcheck bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh
```

Reference tests cover push/backpressure, strict retire pruning, flush clearing,
conflict-row sidecar preservation, and Chisel elaboration with push, retire,
entry, and conflict-row ports.
