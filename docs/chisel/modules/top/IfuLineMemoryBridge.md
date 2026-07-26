# IfuLineMemoryBridge

## Purpose

`IfuLineMemoryBridge` is the production transport adapter between
`LinxCoreIfu.lineRead/lineRefill` and an external tagged 64-byte instruction
memory port. It allows several cacheline reads to remain outstanding and lets
responses return out of order without exposing PE, STID, epoch, predictor, or
other IFU-internal identity to the memory fabric.

The bridge is not an I-SIDE pipeline stage. I-F2 and `ISideFetchMissTable`
remain the owners of miss allocation, orphaning, L1I refill, and retry.

## Interfaces

The IFU-facing request is the complete `ISideLineReadRequest`. The external
request carries only:

```text
tag
linePa
```

The external response carries:

```text
tag
linePa
lineData[511:0]
```

`linePa` is a guard in addition to the opaque tag. A tag hit with the wrong
physical line is stale and cannot release or mutate a live row.

The IFU refill is reconstructed only from a retained request row:

| Refill field | Source |
|---|---|
| `peId` | `request.identity.peId` |
| `transactionId` | `request.transactionId` |
| `threadId` | `request.identity.threadId` |
| `fetchPacketUid` | `request.identity.fetchPacketUid` |
| `fetchSeq` | `request.identity.fetchSeq` |
| `checkpointId` | `request.identity.checkpointId` |
| `epoch` | `request.identity.epoch` |
| `lineVa` | `request.lineVa` |
| `linePa` | retained request `linePa` |
| `lineData` | retained memory response data |

No field is inferred from PC, PA, packet UID, transaction ID, or fetch
sequence. Same-PA virtual aliases and repeated fetches therefore remain
independent.

## Row lifetime and backpressure

Each accepted IFU request atomically allocates a row and enqueues one external
request carrying a monotonic opaque tag. The request FIFO makes tag and PA
stable under external backpressure. The row progresses through:

```text
allocated -> issued -> response-pending -> IFU refill accepted -> free
```

Responses may arrive in any tag order. A matching response is captured into
its row even while the IFU refill sink is blocked. The row and credit are not
released until `ifuRefill.fire`. Unknown, wrong-line, or duplicate tags drain
with `staleResponse` and do not mutate live rows.

The bridge intentionally has no speculative flush input. Once `lineRead.fire`
has allocated the corresponding IFU miss row, the lower request must issue and
drain even if later recovery makes it an orphan. Cancelling only the bridge row
would leak the IFU miss row. `ISideFetchMissTable` also blocks refill admission
during an inner-flush cycle, so a coincident retained refill is retried after
the orphan state is recorded.

## Verification

```bash
bash tools/chisel/run_chisel_tests.sh --only IfuLineMemoryBridge
bash tools/chisel/run_chisel_tests.sh --only ISideFetchMissTable
bash tools/chisel/run_chisel_ifu_line_memory_bridge_probe.sh
```

The Chisel suite covers multiple outstanding requests, out-of-order response,
independent transaction/packet/sequence identity, same-PA aliases, wrong-line
and unknown tags, request and refill backpressure, retained response data, and
credit recovery. The emitted-RTL probe repeats blocked request stability,
out-of-order identity reconstruction, stale response drain, and retained
refill behavior through Verilator.

## Production composition

`LinxCoreProductionComposition` instantiates this bridge with capacity at least
equal to the IFU miss-table capacity and connects it directly to
`LinxCoreIfu.lineRead/lineRefill`. Lower-memory denied/corrupt completion is not
yet represented by `ISideLineResponse`; production fabrics that can fail a
read require an explicit terminal fetch-fault extension rather than a zero-data
refill.
