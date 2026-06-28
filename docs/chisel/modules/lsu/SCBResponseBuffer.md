# SCBResponseBuffer

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/SCBResponseBuffer.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/SCBResponseBufferSpec.scala`
- Downstream decode owner:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/SCBResponseDecode.scala`
- Composition owner:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/SCBRowBank.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/l1/SCB.cpp`
    - `SCBuffer::setMemResp`
    - `SCBuffer::Xfer`
    - `SCBuffer::handleMemResp`
    - `SCBuffer::Work`
  - `model/LinxCoreModel/model/l1/SCB.h`
- Contract IDs: `LC-CHISEL-LSU-SCB-RESP-BUFFER-001`

## Purpose

`SCBResponseBuffer` is the raw L2/CHI response queue boundary in front of
`SCBResponseDecode`. The model stores returned SCB completions in
`SCBuffer::mem_resp_q`; `SCBuffer::handleMemResp()` consumes queued responses
one at a time and asserts that the target row is still `S_MISS`.

The module owns:

- FIFO ordering for raw WriteResp/UpgradeResp candidates,
- ready/valid backpressure for the later L2/CHI response source,
- one-head-at-a-time exposure to `SCBResponseDecode`,
- retention of illegal or stale heads until the decode owner reports them,
- visible count/full/empty/head-consumed observability.

It does not own transaction-id legality, stale-target classification, SCB row
mutation, response-retry selection, `resp_list` row-id storage, DCache RAM
mutation, MDB policy, or memory-event trace rows.

## Interface

### Inputs

| Signal | Type | Description |
|---|---|---|
| `rawValid` | `Bool` | A raw response candidate is present. |
| `rawTxnId` | `UInt(log2Ceil(scbEntries)+2)` | Raw response transaction id, expected later to match `(entryIndex << 2) | 2`. |
| `rawWriteResp` | `Bool` | Candidate is a write-ownership response. |
| `rawUpgradeResp` | `Bool` | Candidate is an upgrade-ownership response. |
| `headReady` | `Bool` | Downstream decode/state owner consumed the current head. |

### Outputs

| Signal | Description |
|---|---|
| `rawReady/rawAccepted` | Response-source backpressure and enqueue handshake. |
| `headValid/headTxnId/headWriteResp/headUpgradeResp` | Current FIFO head presented to `SCBResponseDecode`. |
| `headConsumed` | Current head dequeued this cycle. |
| `empty/full/count` | FIFO occupancy observability. |

## State

The module owns a registered circular FIFO of raw response records. It does not
inspect SCB rows and therefore cannot decide whether a response is legal.
`SCBRowBank` drives `headReady` only from `SCBResponseDecode.memRespValid`, so
illegal heads are retained and continue to report through the decode flags.

## Logic Design

The model flow is:

1. L2/CHI returns a WriteResp or UpgradeResp for an outstanding SCB miss.
2. `SCBuffer::setMemResp()` writes the returned SCB id into `mem_resp_q`.
3. `SCBuffer::Xfer()` calls `handleMemResp()` for one queued response.
4. `handleMemResp()` asserts the row is `S_MISS`, changes it to `S_LOOKUP`,
   and pushes the row into `resp_list`.
5. `SCBuffer::Work()` gives `resp_list` priority over ordinary lookup work.

`SCBResponseBuffer` implements only the first queue boundary in that chain. It
accepts raw packets while space is available, supports simultaneous dequeue
and enqueue when a legal head is consumed, and preserves FIFO order. A head is
removed only when the downstream decoder validates it as a legal response for a
valid `Miss` row. Wrong type, wrong low-bit tag, out-of-range index, duplicate
response, or stale non-`Miss` target therefore stays visible at the FIFO head
instead of being silently dropped.

`SCBResponseRetryQueue` owns the next row-id storage point after this
FIFO/decode boundary. A legal head is consumed only when that retry queue can
accept the decoded row id, so the response-buffer head and `Miss -> Lookup`
state transition share one accepted-response handshake.

## Timing

The FIFO is registered. A newly accepted response is visible to
`SCBResponseDecode` on a later cycle, matching the model's queued
`mem_resp_q.Work()` boundary more closely than the earlier combinational raw
input path. When the FIFO is full, a legal head dequeue opens enqueue space in
the same cycle.

## Flush/Recovery

There is no flush input. SCB rows contain committed-store state. A future owner
must define any global SCB invalidation or response-drain policy before adding
response dropping behavior.

## Trace/Observability

The FIFO exposes local count, full, empty, head valid, and consumed status for
later LSU debug and memory-event trace work. It is not an architectural trace
source.

## Verification

- `bash tools/chisel/run_chisel_tests.sh --only SCBResponseBuffer`
- `bash tools/chisel/run_chisel_tests.sh --only SCBResponseDecode`
- `bash tools/chisel/run_chisel_tests.sh --only SCBRowBank`
- `bash tools/chisel/run_chisel_tests.sh --only STQSCBCommitPath`
- `bash tools/chisel/run_chisel_rob_bookkeeping.sh --reduced-rob`
- `python3 tools/chisel/trace_schema_adapter.py --self-test`
- `bash tools/chisel/run_chisel_qemu_crosscheck.sh --dry-run`
- `bash tools/chisel/run_chisel_reduced_rob_xcheck.sh`
- `bash tools/chisel/run_chisel_top_xcheck.sh`
- `bash tools/chisel/run_chisel_verilator_lint.sh`

Focused reference tests cover FIFO order, simultaneous legal-head pop plus
enqueue, illegal/stale head retention, and Chisel elaboration with response
backpressure and head observability.
