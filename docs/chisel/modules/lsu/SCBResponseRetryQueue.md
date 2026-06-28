# SCBResponseRetryQueue

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/SCBResponseRetryQueue.scala`
- Upstream response owners:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/SCBResponseBuffer.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/SCBResponseDecode.scala`
- Downstream retry selector:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/SCBResponseRetrySelect.scala`
- Composition owner:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/SCBRowBank.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/SCBResponseRetryQueueSpec.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/l1/SCB.cpp`
    - `SCBuffer::Work`
    - `SCBuffer::handleMemResp`
  - `model/LinxCoreModel/model/l1/SCB.h`
- Contract IDs: `LC-CHISEL-LSU-SCB-RESP-RETRYQ-001`

## Purpose

`SCBResponseRetryQueue` is the ordered row-id owner for the model
`SCBuffer::resp_list`. When `SCBResponseDecode` validates a raw response for a
valid `Miss` row, `SCBRowBank` moves that row back to `Lookup` and pushes the
row id into this queue. `SCBResponseRetrySelect` then retries only the queue
head before ordinary valid-row eviction.

The module owns:

- FIFO ordering for response-returned SCB row ids,
- backpressure from a full retry list to the raw response-buffer head,
- one-head-at-a-time exposure to `SCBResponseRetrySelect`,
- visible full/empty/count/head-consumed observability.

It does not own raw response packet storage, transaction-id decode, DCache/L2
lookup classification, row-state mutation, DCache RAM mutation, MDB policy, or
memory-event trace rows.

## Interface

### Inputs

| Signal | Type | Description |
|---|---|---|
| `pushValid` | `Bool` | A decoded legal response row id is ready to enter `resp_list`. |
| `pushEntryIndex` | `UInt(log2Ceil(scbEntries))` | SCB row id returned by the legal response. |
| `popReady` | `Bool` | Downstream lookup actually fired the retry queue head. |

### Outputs

| Signal | Description |
|---|---|
| `pushReady/pushAccepted` | Backpressure and enqueue handshake for legal decoded responses. |
| `headValid/headEntryIndex/headMask` | Ordered retry head presented to `SCBResponseRetrySelect`. |
| `headConsumed` | Current head was popped by a fired retry lookup. |
| `full/empty/count` | FIFO occupancy observability. |

## State

The module owns a registered circular FIFO of row ids. Depth is parameterized
and `SCBRowBank` instantiates it with `scbEntries`, matching the maximum number
of resident SCB rows that can be waiting for response retry.

## Logic Design

The model sequence is:

1. `SCBuffer::handleMemResp()` consumes one memory response from
   `mem_resp_q`.
2. It asserts the target row is `S_MISS`, changes it to `S_LOOKUP`, and pushes
   the row pointer into `resp_list`.
3. `SCBuffer::Work()` pops `resp_list.front()` before ordinary `lookup_list`
   work when DCache can issue.

This Chisel owner implements the storage part of that sequence. A legal
response is accepted only when the retry queue has space, including
simultaneous pop-and-push space. If the queue is full and no head is consumed,
the raw response-buffer head remains in place and the row-state update is not
applied.

## Timing

The FIFO is registered. A decoded response accepted in cycle N becomes visible
as a retry head in a later cycle, matching the model's `Work` then `Xfer`
ordering: current-cycle lookup work cannot consume a response that is returned
later in the same abstract cycle.

## Flush/Recovery

There is no flush input. SCB rows hold committed-store state. A future global
SCB invalidation or response-drain policy must define how queued retry row ids
are cleared or reported.

## Trace/Observability

The retry queue exposes local LSU occupancy and head state for future
memory-event traces. It is not an architectural commit trace source.

## Verification

- `bash tools/chisel/run_chisel_tests.sh --only SCBResponseRetryQueue`
- `bash tools/chisel/run_chisel_tests.sh --only SCBResponseRetrySelect`
- `bash tools/chisel/run_chisel_tests.sh --only SCBRowBank`
- `bash tools/chisel/run_chisel_tests.sh --only SCBResponseDecode`
- `bash tools/chisel/run_chisel_tests.sh --only SCBResponseBuffer`
- `bash tools/chisel/run_chisel_rob_bookkeeping.sh --reduced-rob`
- `python3 tools/chisel/trace_schema_adapter.py --self-test`
- `bash tools/chisel/run_chisel_qemu_crosscheck.sh --dry-run`
- `bash tools/chisel/run_chisel_reduced_rob_xcheck.sh`
- `bash tools/chisel/run_chisel_top_xcheck.sh`
- `bash tools/chisel/run_chisel_verilator_lint.sh`

Focused reference tests cover FIFO order, simultaneous pop/enqueue space,
full-state backpressure, and Chisel elaboration.
