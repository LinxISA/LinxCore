# STQEntryBank

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/STQEntryBank.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/STQEntryBankSpec.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/ModelCommon/ModelEnumDefines.h`
  - `model/LinxCoreModel/model/ModelCommon/bus/MemReqBus.h`
  - `model/LinxCoreModel/model/lsu/store_unit/store_unit.cpp`
  - `model/LinxCoreModel/model/lsu/store_unit/stq.h`
  - `model/LinxCoreModel/model/lsu/store_unit/stq.cpp`
- Related Chisel contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/StoreDispatchToSTQ.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/STQFlushPrune.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/recovery/RecoveryCleanupControl.scala`
- Contract IDs: `LC-CHISEL-LSU-STQ-BANK-001`

## Purpose

`STQEntryBank` is the first Chisel store-queue state owner. It holds STQ row
sidecars and applies the model-derived lifecycle that can be verified before
the full LSU exists:

- first-free store allocation,
- complementary `ST_ADDR`/`ST_DATA` merge into `ST_ALL`,
- resident `size` and WAIT/outstanding `osdSize` accounting,
- local WAIT-to-COMMIT marking for ready stores,
- single-row and multi-row committed free,
- recovery cleanup through `STQFlushPrune.freeMask`.

It does not own store-commit queue ordering, SCB/MDB traffic, cacheline split
requests, tile/TTrans side effects, load forwarding, or LSU wakeup routing.

## Interface

### Store Request

`STQStoreRequest` is the Chisel subset of model `MemReqBus` needed by the first
state owner. `StoreDispatchToSTQ` is the first upstream bridge that forms this
request type from executed store-dispatch queue heads.

| Field | Description |
|---|---|
| `storeType` | `All`, `Addr`, or `Data`, matching model `ST_ALL=0`, `ST_ADDR=1`, `ST_DATA=2`. |
| `peId/stid/tid` | Scope fields consumed by recovery and later LSU routing. |
| `bid/gid/rid/lsId` | Ring identity sidecars from the model memory request. |
| `addr/data/size` | Store address, data, and byte size sidecars. |
| `stackValid` | Stack marker preserved across merge. |
| `scalarIex/simtLane` | Merge scoping: scalar stores ignore SIMT lane, vector/SIMT stores require matching lane. |

### Inputs

| Signal | Type | Description |
|---|---|---|
| `flush` | `FlushBus` | Selected recovery flush consumed by the internal `STQFlushPrune`. |
| `insertValid/insert` | valid plus `STQStoreRequest` | Store allocate or merge request. |
| `markCommitValid/markCommitIndex` | command | Mark a locally ready `Wait` row as `Commit`. |
| `commitFreeValid/commitFreeIndex` | command | Free a row that is already in `Commit` state. |
| `commitFreeMaskValid/commitFreeMask` | command mask | Free one or more rows already in `Commit` state. In the full STQ-to-SCB path this is driven by `STQSCBCommitPath` from accepted `SCBRowBank` `last` fragments. |

### Outputs

| Signal | Description |
|---|---|
| `insertReady` | High when the request can allocate or merge this cycle. |
| `insertAccepted/insertAllocated/insertMerged` | Insert result classification. |
| `insertConflict` | A split-store half matched an existing row but was not the complementary half. |
| `insertIndex` | Row allocated or merged by the accepted insert. |
| `markCommitAccepted/markCommitIgnored` | WAIT-to-COMMIT command result. |
| `commitFreeAccepted/commitFreeIgnored` | committed-row free command result. |
| `commitFreeAcceptedMask/commitFreeIgnoredMask/commitFreeCount` | Multi-row committed-free result. Accepted bits clear committed rows; ignored bits name requested rows that were not committed or were blocked by recovery. |
| `flushApplied` | At least one `STQ_WAIT` row was freed by recovery. |
| `flushMatchMask/flushFreeMask/flushStatusBlockedMask/flushFreeCount` | Internal `STQFlushPrune` diagnostics. |
| `rows` | Current row state, exposed for later owner integration and tests. |
| `occupiedMask/waitMask/commitMask/addrReadyMask/dataReadyMask` | Compact row-state diagnostics. |
| `residentCount/outstandingWaitCount` | Model `size` and `osdSize` equivalents. |
| `empty/full/stall` | Queue occupancy state; `stall` follows `full && osdSize == size`. |

## State

- `rows`: STQ row sidecars and state.
- `residentCount`: model `size`, incremented on allocation and decremented on
  committed-row free or recovery free.
- `outstandingWaitCount`: model `osdSize`, incremented on allocation and
  decremented on WAIT-to-COMMIT or recovery free of a WAIT row.
- internal `STQFlushPrune`: combinational recovery mask generator over the
  registered row state.

## Logic Design

Allocation follows `STQ::insert`: choose the first invalid row, initialize it
as valid `Wait`, and derive readiness from store type:

- `All`: address-ready and data-ready;
- `Addr`: address-ready only;
- `Data`: data-ready only.

Partial-store merge follows `STQ::mergeStore` and `STQueueEntryInfo::init`.
The bank searches existing `Wait` rows by `(bid, lsId)` and SIMT lane when the
request is not scalar. A complementary `Addr` plus `Data` pair merges in place
and becomes `All`. An incompatible same-ID split-store half raises
`insertConflict` instead of allocating a duplicate row.

`markCommit` is the local state transition corresponding to the state part of
`STQ::retire`: only valid `Wait` rows that are address-ready, data-ready, and
`All` can move to `Commit`; the transition decrements `outstandingWaitCount`.
The global `isStqCmtable` age and oldest-block policy is intentionally outside
this packet.

`commitFree` is the row-clear part of `STQ::commit` after the memory-side owner
has accepted the store. In the full STQ-to-SCB path, `STQSCBCommitPath` drives
the mask from `SCBRowBank.commitFreeMask`. The legacy single-index command and
the multi-row mask command both accept only `Commit` rows. Requests targeting
WAIT, invalid, or non-COMMIT rows are reported as ignored. Accepted rows are
de-duplicated through `commitFreeAcceptedMask`, cleared once, and decrement
`residentCount` by `commitFreeCount`. `outstandingWaitCount` is unchanged
because committed rows already left the model `osdSize` domain at
WAIT-to-COMMIT time.

Recovery has priority over insert/mark/free commands. When the internal
`STQFlushPrune` reports a nonzero `freeMask`, the bank clears those WAIT rows
and decrements both resident and outstanding-WAIT counts by `flushFreeCount`.
Matched non-WAIT rows are reported through `flushStatusBlockedMask` and remain
resident.

## Timing

The bank is a single-cycle state owner. Recovery mask generation is
combinational over the current row state, then applied in the bank update
phase. Insert, mark-commit, and committed-row free commands are suppressed on
a recovery-apply cycle.

## Flush/Recovery

`STQEntryBank` is the first module that consumes `STQFlushPrune.freeMask` and
mutates STQ row state. It still does not own broader recovery side effects:
LSID rebasing, load queue pruning, frontend restart, rename restore, and ROB
pruning remain in their own owners.

## Trace/Observability

The masks and counters are intended to feed future recovery and memory trace
events. No architectural commit trace row is emitted by this module.

## Verification

- `bash tools/chisel/run_chisel_tests.sh --only STQEntryBank`
- `bash tools/chisel/run_chisel_tests.sh --only StoreDispatchToSTQ`
- `bash tools/chisel/run_chisel_tests.sh --only STQCommitQueue`
- `bash tools/chisel/run_chisel_tests.sh --only STQFlushPrune`
- `bash tools/chisel/run_chisel_tests.sh --only RecoveryCleanupControl`
- `bash tools/chisel/run_chisel_tests.sh --only FlushControl`
- `bash tools/chisel/build_chisel.sh`
- `bash tools/chisel/run_chisel_rob_bookkeeping.sh --reduced-rob`
- `python3 tools/chisel/trace_schema_adapter.py --self-test`
- `bash tools/chisel/run_chisel_qemu_crosscheck.sh --dry-run`

Focused tests cover first-free allocation, split store merge, full-queue merge
acceptance, full-queue allocation rejection, WAIT-to-COMMIT accounting, single
and masked committed-row free, WAIT-only recovery free, committed-row
preservation on flush, and Chisel elaboration with the `STQFlushPrune` child.
