# SCBCommitIngress

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/SCBCommitIngress.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/SCBCommitIngressSpec.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/l1/SCB.h`
  - `model/LinxCoreModel/model/l1/SCB.cpp`
  - `model/LinxCoreModel/model/l1/cluster.cpp`
  - `model/LinxCoreModel/model/lsu/store_unit/stq.cpp`
- Related Chisel contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/STQCommitDrain.scala`
- Contract IDs: `LC-CHISEL-LSU-SCB-INGRESS-001`

## Purpose

`SCBCommitIngress` is the first Chisel owner for the scalar store coalescing
buffer ingress after `STQCommitDrain`. It accepts memory-side store fragments,
allocates 64-byte SCB line entries, merges same-line stores, and publishes
post-merge byte-valid wakeup masks.

The module owns:

- 64-byte line address formation from store fragment addresses,
- line allocation when no existing SCB line matches,
- same-line byte-granular data and valid-mask merge,
- lane-order admission for multiple fragments in one cycle,
- blocked request reporting when no matching line or free entry exists,
- SCB wakeup masks equivalent to the model `sendLUwakeup` line-valid payload.

It does not own DCache lookup/update, random/not-full eviction, L2/CHI request
generation, write response matching, MDB conflict prediction, load forwarding
selection, or store-drain fence completion. Those remain future LSU owner
packets.

## Interface

### Inputs

| Signal | Type | Description |
|---|---|---|
| `reqs` | `Vec[STQCommitDrainRequest]` | Store fragments emitted by `STQCommitDrain`. Each valid descriptor is already single-cacheline after split handling. |

### Outputs

| Signal | Description |
|---|---|
| `acceptedMask` | Request lanes accepted into an existing or newly allocated SCB line. |
| `blockedMask` | Valid request lanes rejected because no matching line or free entry was available. |
| `wakeups` | Per-accepted-lane line address plus post-merge byte-valid mask. |
| `entries` | Current SCB line entries: valid bit, line address, byte mask, 512-bit data, full flag. |
| `validMask/fullLineMask` | Entry diagnostics for occupancy and full-line writeback selection. |
| `entryCount/freeCount/full` | Occupancy diagnostics. |

## State

- `entries`: fixed-depth line buffer. Each entry contains one 64-byte line
  address, 64 byte-valid bits, 512 data bits, and a full-line flag.

Rows are non-flushable by construction in this packet: upstream
`STQCommitDrain` only handles committed stores. Recovery-domain pruning stays
in `STQFlushPrune` and `STQEntryBank`.

## Logic Design

Each cycle, valid request lanes are processed in lane order:

1. Compute `lineAddr = addr & ~0x3f`.
2. Search the current staged SCB state for a matching line.
3. If a match exists, merge the request bytes into that line.
4. If no match exists and a free entry exists, allocate the first free entry
   and merge the request bytes.
5. If no match or free entry exists, report the lane in `blockedMask`.

The byte merge mirrors model `SCBuffer::handleInsert`: request data is treated
as little-endian scalar store data, byte offset comes from `addr[5:0]`, and
only bytes covered by `size` update line data and valid bits.

Accepted lanes produce wakeup records with the line address and post-merge
byte mask. This is the Chisel boundary for the model `sendLUwakeup` behavior;
future load-side owners can consume it to wake loads waiting on newly valid
store bytes.

## Timing

The line entries are registered. Multiple request lanes are staged
combinationally before the next state is registered, so a later lane in the
same cycle can merge into a line allocated by an earlier lane.

The current module reports blocking after admission; a later integration
packet must feed SCB capacity back into `STQCommitDrain` readiness so the STQ
row is not freed unless every fragment is guaranteed to enter SCB.

## Flush/Recovery

There is no flush input. SCB rows represent committed stores that have already
left flushable STQ state. If a future precise exception path needs to invalidate
SCB state, it must be a separate model-backed packet.

## Trace/Observability

`acceptedMask`, `blockedMask`, `wakeups`, and line-entry diagnostics are
intended as the first probe surface for committed-store SCB ingress. They are
not architectural commit rows and do not yet participate in QEMU-vs-DUT trace
comparison.

## Verification

- `bash tools/chisel/run_chisel_tests.sh --only SCBCommitIngress`
- `bash tools/chisel/run_chisel_tests.sh --only STQCommitDrain`
- `bash tools/chisel/run_chisel_tests.sh --only STQCommitQueue`
- `bash tools/chisel/run_chisel_tests.sh --only STQEntryBank`
- `bash tools/chisel/run_chisel_rob_bookkeeping.sh --reduced-rob`
- `python3 tools/chisel/trace_schema_adapter.py --self-test`
- `bash tools/chisel/run_chisel_qemu_crosscheck.sh --dry-run`

Focused reference tests cover first-line allocation, same-line merge, full-SCB
blocking with same-line hit acceptance, split-fragment line allocation, and
Chisel elaboration.
