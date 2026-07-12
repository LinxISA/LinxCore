# STQCommitQueue

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/STQCommitQueue.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/STQCommitQueueSpec.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/ModelCommon/ROBID.cpp`
  - `model/LinxCoreModel/model/lsu/store_unit/stq.h`
  - `model/LinxCoreModel/model/lsu/store_unit/stq.cpp`
- Related Chisel contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/STQEntryBank.scala`
- Contract IDs: `LC-CHISEL-LSU-STQ-COMMITQ-001`

## Purpose

`STQCommitQueue` is the first Chisel owner for model `storeCommitQ` ordering.
It sits after `STQEntryBank` marks a ready store row as `Commit` and before
future SCB/MDB/cacheline-split owners send memory-side requests.

The module owns:

- ordered enqueue of committed scalar store STQ row indices,
- age comparison by `(bid, lsId)` using the same `ROBID` wrap convention as
  LinxCoreModel,
- drain selection up to `issueWidth`, matching the model's
  `store_commit_count` limit,
- downstream-ready skipping so a stalled older row remains queued while
  younger ready rows can issue, matching the model `STQ::commit` scan.

It does not own `isStqCmtable`, TTrans/tile side effects, cacheline splitting,
SCB coalescing, MDB conflict updates, data-array banking, BSB window slide, or
the final `STQEntryBank.commitFree` command.

## Interface

### Inputs

| Signal | Type | Description |
|---|---|---|
| `enqueueValid` | `Bool` | A committed STQ row should be appended into commit order. |
| `enqueueIndex` | STQ row index | Row index from `STQEntryBank.markCommitAccepted`. |
| `enqueueBid/enqueueLsId` | `ROBID` | Ordering identity copied from the committed STQ row. |
| `flushValid` | `Bool` | Clears the ordered queue and suppresses same-cycle issue/enqueue. |
| `issueEnable` | `Bool` | Enables a commit/drain scan for this cycle. |
| `readyMask` | `UInt(stqEntries.W)` | Downstream-ready mask by STQ row index. Future SCB/cacheline split owners drive this. |

### Outputs

| Signal | Description |
|---|---|
| `enqueueReady/enqueueAccepted` | Enqueue handshake. Capacity is evaluated after same-cycle issue compaction. |
| `enqueueDuplicate` | The row index is already resident after same-cycle issue removal. |
| `enqueueInsertPosition` | Queue position where the accepted row is inserted, or append position when no younger row exists. |
| `issue` | Up to `issueWidth` selected committed rows in queue scan order. |
| `issueValidMask/issueCount` | Compact issue diagnostics for future memory-side owners and tests. |
| `queued/queuedValidMask/queueCount` | Current queue contents and occupancy. |
| `empty/full` | Occupancy state. |
| `orderError` | Defensive diagnostic for an invalid hole before a valid row or adjacent ordering inversion. |

## State

- `queue`: compact ordered rows containing STQ row index, `bid`, and `lsId`.
- `count`: number of valid queue entries.

The queue is compacted after issue selection every cycle. An accepted enqueue
is inserted into that compacted queue, so a cycle can issue one or more old
stores and accept a new committed store if space becomes available.

## Logic Design

Enqueue insertion mirrors the sorted `store_list` that `STQ::retire` appends to
`storeCommitQ`: search from the oldest queued row and insert before the first
row for which the new `(bid, lsId)` is older than or equal to the resident row.
If no such row exists, append at the current tail.

Issue selection mirrors the model `STQ::commit` loop:

1. Scan `storeCommitQ` from oldest to youngest.
2. Select only rows whose downstream SCB/cacheline path is ready.
3. Skip not-ready rows instead of blocking the scan.
4. Stop once `issueWidth` rows have been selected.
5. Remove issued rows and compact the remaining queue.

This packet deliberately treats `readyMask` as an external predicate. Future
owners decide whether a row is ready after checking SCB backpressure, split
cacheline availability, TTrans response paths, and memory-side resource limits.

## Timing

`STQCommitQueue` is a single-cycle registered queue. Issue selection,
compaction, and optional enqueue insertion are combinational over the current
registered queue; the next compact queue is registered at the end of the
cycle.

## Flush/Recovery

`flushValid` is a local queue clear. It does not interpret `FlushBus`; the
parent must assert it only when the parent has decided that all resident commit
queue entries are invalid. While asserted, the queue issues nothing, accepts no
enqueue, clears all entries, and sets `count` to zero.

The queue contains rows already moved out of `STQ_WAIT`. The current
`STQFlushPrune` packet frees only WAIT rows, while committed rows are
preserved. Future integrated LSU work that invalidates committed STQ rows
before memory-side issue must drive this clear/drop boundary from the same
model-backed recovery decision.

## Trace/Observability

The issue outputs are intended to feed future memory-side request traces. This
module does not emit architectural commit trace rows directly.

## Verification

- `bash tools/chisel/run_chisel_tests.sh --only STQCommitQueue`
- `bash tools/chisel/run_chisel_tests.sh --only STQEntryBank`
- `bash tools/chisel/run_chisel_tests.sh --only STQFlushPrune`
- `bash tools/chisel/run_chisel_rob_bookkeeping.sh --reduced-rob`
- `python3 tools/chisel/trace_schema_adapter.py --self-test`
- `bash tools/chisel/run_chisel_qemu_crosscheck.sh --dry-run`

Focused reference tests cover sorted enqueue, `ROBID` wrap ordering, skipped
downstream stalls, same-cycle issue plus enqueue, duplicate/full rejection,
issue gating, flush clearing, and Chisel elaboration.

R670 stores and issues full-width LSID values. Same-BID ordering uses
`LSIDOrder.lessEqual`; cross-BID ordering still uses the ROBID ring helper.
The queue never derives LSID width from ROB, STQ, or queue depth.
