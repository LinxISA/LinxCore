# STQCommitDrain

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/STQCommitDrain.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/STQCommitDrainSpec.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/ModelCommon/LSUUtils.cpp`
  - `model/LinxCoreModel/model/lsu/lsu.cpp`
  - `model/LinxCoreModel/model/lsu/store_unit/stq.cpp`
- Related Chisel contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/STQEntryBank.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/STQCommitQueue.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/SCBCommitBridge.scala`
- Contract IDs: `LC-CHISEL-LSU-STQ-DRAIN-001`

## Purpose

`STQCommitDrain` is the first Chisel memory-side owner for committed scalar
stores. It composes `STQCommitQueue`, checks committed STQ rows against
downstream segment availability, emits one or two memory request descriptors,
and drives the `STQEntryBank.commitFreeMask` boundary only for rows whose
memory-side issue has been accepted.

The module owns:

- the handoff from ordered `storeCommitQ` issue selection to memory request
  descriptors,
- scalar 64-byte cacheline split detection matching `AddrCrossCacheline`,
- split request shaping matching `GetCrossReq`,
- free-mask generation for issued committed rows,
- stalled-row skipping through the existing `STQCommitQueue` scan.

It does not own SCB storage, MDB conflict updates, TTrans/tile side effects,
BSB window slide, CHI completion, load forwarding, data-array banking, or
global LSU arbitration. Those remain future LSU owner packets.

## Interface

### Inputs

| Signal | Type | Description |
|---|---|---|
| `enqueueValid` | `Bool` | A committed STQ row should enter the ordered commit queue. |
| `enqueueIndex` | STQ row index | Row index from `STQEntryBank.markCommitAccepted`. |
| `enqueueBid/enqueueLsId` | `ROBID` | Ordering identity copied from the committed row. |
| `issueEnable` | `Bool` | Enables a commit drain scan for this cycle. |
| `primaryReadyMask` | `UInt(entries.W)` | Per-row acceptance for the first or only memory segment. |
| `secondaryReadyMask` | `UInt(entries.W)` | Per-row acceptance for the second memory segment of a split store. |
| `rows` | `Vec[STQEntryBankRow]` | Current STQ row sidecars from `STQEntryBank`. |

### Outputs

| Signal | Description |
|---|---|
| `enqueueReady/enqueueAccepted` | Forwarded queue enqueue handshake. |
| `enqueueDuplicate/enqueueInsertPosition` | Forwarded queue diagnostics for duplicate live row and sorted insertion. |
| `commitEligibleMask` | Valid committed `ST_ALL` rows with address and data ready. |
| `splitMask` | Rows whose scalar address and size cross a 64-byte cacheline. |
| `readyMask` | Rows eligible for queue issue after downstream readiness checks. |
| `issue/issueValidMask/issueCount` | Forwarded selected committed rows from `STQCommitQueue`. |
| `memReqs` | Up to two request descriptors per issue lane; second descriptor is valid only for split stores. |
| `commitFreeMaskValid/commitFreeMask/commitFreeCount` | Bank free command for rows whose request descriptors were issued. |
| `queued/queuedValidMask/queueCount/empty/full/orderError` | Forwarded queue visibility. |

## State

`STQCommitDrain` owns no additional registered state. The resident ordered
queue is the child `STQCommitQueue`; row contents remain owned by
`STQEntryBank`.

## Logic Design

The drain computes a per-row `readyMask`:

1. The row must be valid, in `Commit` state, `ST_ALL`, address-ready, and
   data-ready.
2. A non-split row needs `primaryReadyMask(index)`.
3. A split row needs both `primaryReadyMask(index)` and
   `secondaryReadyMask(index)`.

That mask feeds `STQCommitQueue`. The queue keeps model `(bid, lsId)` order,
skips not-ready rows, and selects up to `issueWidth` rows. Each selected row
produces:

- one request when `(addr[5:0] + size) <= 64`,
- two requests when the store crosses a 64-byte scalar cacheline.

For split stores, the first descriptor keeps the original address and uses
`64 - addr[5:0]` as size. The second descriptor uses the next 64-byte line
base and carries the remaining size. Store data is shifted the same way as the
model `GetCrossReq`: the second descriptor is `data >> (first_size * 8)`.

`commitFreeMask` is the OR of selected issue lane indices. It is intentionally
generated only after the downstream-ready predicate allowed issue, matching the
model `STQ::commit` order where `free(i)` happens after `sendSimL1`.

When the downstream target is the registered Chisel SCB path,
`STQSCBCommitPath` owns final STQ frees. It gates drain issue with the
registered `SCBRowBank` model-batch capacity rule and wires accepted
`SCBRowBank` `last` fragments to `STQEntryBank.commitFreeMask`. Full LSU
composition must use that SCB-side free mask rather than treating this module's
abstract issue/free mask as final.

## Timing

The module is combinational around the child queue's current registered state.
Newly enqueued rows do not issue in the same cycle because
`STQCommitQueue` selects from its current resident queue before registering the
next compacted queue.

## Flush/Recovery

Committed rows are not freed by `STQFlushPrune`, which only clears valid
`STQ_WAIT` rows. This module therefore has no flush input. A future precise
exception owner that invalidates committed stores before memory issue must add
a separate model-backed queue drop path.

## Trace/Observability

`memReqs`, `readyMask`, `splitMask`, and `commitFreeMask` are the first
observable Chisel boundary for the model `STQ::commit` memory-side handoff.
They are not architectural commit rows and do not yet participate in the
QEMU-vs-DUT trace compare.

## Verification

- `bash tools/chisel/run_chisel_tests.sh --only STQCommitDrain`
- `bash tools/chisel/run_chisel_tests.sh --only SCBCommitBridge`
- `bash tools/chisel/run_chisel_tests.sh --only STQCommitQueue`
- `bash tools/chisel/run_chisel_tests.sh --only STQEntryBank`
- `bash tools/chisel/run_chisel_tests.sh --only STQFlushPrune`
- `bash tools/chisel/run_chisel_rob_bookkeeping.sh --reduced-rob`
- `python3 tools/chisel/trace_schema_adapter.py --self-test`
- `bash tools/chisel/run_chisel_qemu_crosscheck.sh --dry-run`

Focused reference tests cover single-line issue/free, split stores requiring
both segment targets, younger-row progress around an older split-stalled row,
issue gating, and Chisel elaboration of the memory/free boundary.
