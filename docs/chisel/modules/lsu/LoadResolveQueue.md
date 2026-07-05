# LoadResolveQueue

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadResolveQueue.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/LoadResolveQueueSpec.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/lsu/load_unit/ldq.cpp`
    - `ResolveQ::insert`
    - `ResolveQ::retired`
    - `ResolveQ::detect`
    - `ResolveQ::flush`
    - `LDQInfo::CheckMovRslvQ`
    - `LDQInfo::handleDetect`
  - `model/LinxCoreModel/model/ModelCommon/bus/FlushBus.h`
    - `FlushBus::match`
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
top integration is storage-only in the live reduced top because replay launch
remains disabled there. R287 wires ROB commit memory-order retire watermarks.
R288 adds queue-local precise `FlushBus` pruning. R289 has the opt-in
replay-LIQ top drive that port from execute-owned scalar redirect cleanup when
the redirecting uop supplies a valid all-row LSID sidecar; marker-only cleanup
without an LSID still uses the hard all-clear fallback. R455 proves this queue
under the executable reduced-store replay fixture: the generated RTL drives a
real LIQ E4 LHQ record into `LoadResolveQueue`, then applies the delayed LIQ
`clearResolved` feedback while the ResolveQ record remains resident. That is
fixture evidence because launch and return sidebands are harness-driven. R456
extends the same fixture with a commit-style retire watermark and proves the
resolved row drains by the strict older-than `(BID, LSID)` rule. Default/live
LIQ insertion and exported conflict rows feeding the live MDB/recovery path
remain deferred owner packets.

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
| `retireValid` | Commit identity is valid for model-style `ResolveQ::retired` pruning. R287 wires the opt-in replay-LIQ top from the ROB commit memory-order watermark. |
| `retireBid` / `retireLsId` | Commit-row BID plus pre-increment LSID snapshot. Rows strictly older by `(BID, LSID)` are removed. |
| `retireMask` | Pre-cycle queue rows retired this cycle. |
| `retireCount` | Number of retired rows. |

### Precise Flush

| Signal | Description |
|---|---|
| `flush` | Hard all-clear used by reset/restart and current reduced-store top flushing. |
| `preciseFlush` | Model-shaped `FlushBus` for row-selective pruning. It matches STID first, optional PE/thread scopes, then either base-on-BID, base-on-group, or `(BID, LSID)` ordering. |
| `flushPruneMask` | Pre-cycle queue rows matched by `preciseFlush`. |
| `flushPruneCount` | Number of precise-pruned rows. |

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
ROB nuke state, final recovery publication, or the top-level choice of which
recovery intent should drive `preciseFlush`.

## Logic Design

The C++ model has a simple owner split:

1. `LDQInfo::returnData` resolves a load, and `CheckMovRslvQ` moves resolved
   rows into `ResolveQ`.
2. `ResolveQ::insert` appends the resolved `MemReqBus`.
3. `ResolveQ::retired` removes queue rows strictly older than the commit
   identity.
4. `ResolveQ::flush` removes rows matched by `FlushBus::match`.
5. `ResolveQ::detect` scans remaining rows with the same address-overlap and
   `(BID, LSID)` age predicate used for active LDQ rows.

The Chisel queue implements the storage half of those rules:

1. Build a queue entry from `pushRecord` and explicit thread sidecars.
2. On each cycle, mark rows for retire when
   `row.(BID, loadLsId) < retire.(BID, LSID)`.
3. Mark rows for precise prune when `preciseFlush` is valid, STID and optional
   PE/thread scopes match, and the model BID/group/LSID comparison covers the
   row.
4. Compact rows not removed by retire or precise prune in order.
5. Append one accepted push after the compacted tail.
6. Convert every valid entry into an `MDBConflictLoadEntry` with the preserved
   load PC, address, size, BID/GID/RID, and load LSID.

The strict retire comparison intentionally does not remove a row with exactly
the same `(BID, LSID)` as the commit identity, matching `ResolveQ::retired`.

## Timing

Push, retire, and precise prune can occur in the same cycle. Removal
compaction happens before push capacity and insertion-index calculation, so a
full queue can accept a new record when at least one older or flushed row is
removed in the same cycle. Outputs show the pre-cycle queue image; the
next-cycle image reflects retire, precise prune, and push.

## Flush/Recovery

`flush` clears the whole queue. `preciseFlush` preserves older rows and removes
only rows covered by the same `FlushBus::match` rules used by the model:
matching STID, optional PE/thread scopes, base-on-BID coverage,
base-on-group coverage, or non-group `(BID, LSID)` coverage. In the opt-in
replay-LIQ top, execute-owned scalar redirects build a ResolveQ-specific copy
of the path cleanup flush and override `req.lsId` with the redirecting uop's
reduced all-row LSID snapshot. The generic path cleanup bus still keeps its
ROB/rename-oriented RID semantics. Marker-only cleanup without a valid LSID
does not drive `preciseFlush` and instead keeps the previous hard-clear
fallback.

## Deferred Owners

- Default/live LIQ insertion beyond the opt-in replay-LIQ diagnostic path.
- Clear-resolved feedback outside the opt-in replay-LIQ top path.
- Live MDB/recovery publication using `MDBConflictDetect`.

## Verification

Focused gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadResolveQueue
bash tools/chisel/run_chisel_tests.sh --only LoadInflightQueue
bash tools/chisel/run_chisel_tests.sh --only MDBConflictDetect
bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop
bash tools/chisel/run_chisel_tests.sh --only ReducedStoreWaitReplayChiselPath
```

Generated-RTL fixture gate:

```bash
bash tools/chisel/run_chisel_reduced_store_wait_replay_chisel_path.sh
jq . generated/chisel-reduced-store-wait-replay-chisel-path/report/reduced_store_wait_replay_chisel_path.json
```

The R455/R456 generated reports record `resolve_queue_push=true`,
`liq_clear_resolved=true`, `resolve_queue_count=1`,
`resolve_queue_retired=true`, and `resolve_queue_count_after_retire=0`,
proving that this queue accepts the fixture LHQ record, the parent delayed
clear frees the source LIQ row after acceptance, and a later commit watermark
retires the ResolveQ row. Older focused fixtures remain:

```bash
FETCH_REDUCED_STORE_REPLAY_LIQ=1 BUILD_DIR=generated/r285-replay-liq-resolveq-xcheck bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh
FETCH_REDUCED_STORE_REPLAY_LIQ=1 BUILD_DIR=generated/r286-replay-liq-resolve-clear-xcheck bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh
FETCH_REDUCED_STORE_REPLAY_LIQ=1 BUILD_DIR=generated/r287-replay-liq-resolve-retire-xcheck bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh
FETCH_REDUCED_STORE_REPLAY_LIQ=1 BUILD_DIR=generated/r289-replay-liq-resolve-precise-flush-xcheck bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh
```

Reference tests cover push/backpressure, strict retire pruning, flush clearing,
precise `FlushBus` pruning and compaction, conflict-row sidecar preservation,
and Chisel elaboration with push, retire, precise flush, entry, and
conflict-row ports.
