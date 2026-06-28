# SCBResponseRetrySelect

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/SCBResponseRetrySelect.scala`
- Upstream normal egress owner:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/SCBEgressSelect.scala`
- Upstream retry queue:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/SCBResponseRetryQueue.scala`
- Composition owner:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/SCBRowBank.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/SCBResponseRetrySelectSpec.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/l1/SCB.cpp`
    - `SCBuffer::Work`
    - `SCBuffer::handleMemResp`
    - `SCBuffer::handleLookup`
  - `model/LinxCoreModel/model/l1/SCB.h`
- Contract IDs: `LC-CHISEL-LSU-SCB-RESP-RETRY-001`

## Purpose

`SCBResponseRetrySelect` owns the arbitration point between the model
`resp_list` head and ordinary SCB egress work. A legal raw response moves an
SCB row from `Miss` back to `Lookup` and pushes its row id into
`SCBResponseRetryQueue`; this selector retries the queue head before any
ordinary `Valid` row from `SCBEgressSelect`.

The module owns:

- retry candidate observability for valid rows in `SCBEntryState.Lookup`,
- queued-head retry selection,
- priority override of the ordinary `SCBEgressSelect` lookup descriptor,
- stale/blocked retry-head reporting,
- final lookup mask and retry/normal observability.

It does not own raw response FIFO storage, ordered retry row-id storage,
transaction-id decode, DCache/L2 lookup outcome classification, row-state
mutation, DCache RAM mutation, MDB policy, or memory-event trace rows.

## Interface

### Inputs

| Signal | Type | Description |
|---|---|---|
| `entries` | `Vec[SCBLineEntry]` | Current post-ingress SCB row image. Valid `Lookup` rows are retry candidates. |
| `normalLookupRequest` | `SCBEgressLookupRequest` | Ordinary valid-row eviction descriptor from `SCBEgressSelect`. |
| `normalLookupMask` | `UInt(scbEntries.W)` | One-hot ordinary selected row mask from `SCBEgressSelect`. |
| `retryHeadValid` | `Bool` | `SCBResponseRetryQueue` has an ordered retry head. |
| `retryHeadEntryIndex` | `UInt` | Ordered retry head row id. |

### Outputs

| Signal | Description |
|---|---|
| `retryCandidateMask` | All valid `Lookup` rows eligible for response retry. |
| `retryLookupMask` | One-hot selected retry head, or zero when no legal retry head exists. |
| `normalSelectedMask` | Forwarded ordinary egress mask when no retry wins. |
| `lookupMask` | Final one-hot row sent to `SCBLookupControl`. |
| `lookupRetry/lookupNormal` | Final selection source. |
| `retryHeadBlocked` | Retry queue head exists but the row is no longer valid `Lookup`. |
| `lookupFull/lookupNotFull` | Classification of the final selected descriptor. |
| `noCandidate` | Neither retry nor ordinary egress produced a lookup descriptor. |
| `lookupRequest` | Final descriptor forwarded to `SCBLookupControl`. |

## State

The module is combinational. It stores no retry queue; `SCBResponseRetryQueue`
owns the ordered row-id FIFO. The selector blocks ordinary egress whenever the
retry queue has a head, even if that head is stale, so queue-order errors do not
silently fall through to normal eviction.

## Logic Design

The model sequence is:

1. `SCBuffer::handleMemResp()` consumes one queued memory response, asserts the
   row is `S_MISS`, changes it to `S_LOOKUP`, and pushes the row into
   `resp_list`.
2. `SCBuffer::Work()` checks `resp_list` before ordinary `lookup_list` work.
3. If DCache can issue, the response-returned row retries through
   `SCBuffer::handleLookup()`.

This Chisel owner implements the arbitration point:

1. Build `retryCandidateMask` from valid rows whose state is `Lookup`.
2. If `SCBResponseRetryQueue` has a valid head and that row is valid `Lookup`,
   format that row as the retry lookup request.
3. If the queue head exists but is not valid `Lookup`, report
   `retryHeadBlocked` and suppress ordinary egress.
4. If the retry queue is empty, forward the ordinary descriptor and one-hot mask from
   `SCBEgressSelect`.
5. Emit the selected descriptor to `SCBLookupControl`.

`SCBEgressSelect` still owns only ordinary `Valid` row eviction, including
full-line priority and deterministic not-full fallback.

## Timing

All outputs are combinational from the staged row image and the ordinary
egress descriptor. Backpressure remains in `SCBLookupControl`: if DCache or
L2 ownership request queues are not ready, the selected row is not consumed and
the row state is preserved by `SCBStateUpdate`.

## Flush/Recovery

There is no flush input. SCB rows hold committed-store state. Any future global
SCB invalidation or response-drain policy must define how `Lookup` retry rows
are reported or cleared.

## Trace/Observability

The retry masks are local LSU observability for future memory-event traces.
They are not architectural commit rows and do not participate in QEMU-vs-DUT
comparison until live memory sideband trace rows exist.

## Verification

- `bash tools/chisel/run_chisel_tests.sh --only SCBResponseRetrySelect`
- `bash tools/chisel/run_chisel_tests.sh --only SCBRowBank`
- `bash tools/chisel/run_chisel_tests.sh --only SCBStateUpdate`
- `bash tools/chisel/run_chisel_tests.sh --only SCBEgressSelect`
- `bash tools/chisel/run_chisel_rob_bookkeeping.sh --reduced-rob`
- `python3 tools/chisel/trace_schema_adapter.py --self-test`
- `bash tools/chisel/run_chisel_qemu_crosscheck.sh --dry-run`
- `bash tools/chisel/run_chisel_reduced_rob_xcheck.sh`
- `bash tools/chisel/run_chisel_top_xcheck.sh`
- `bash tools/chisel/run_chisel_verilator_lint.sh`

Focused reference tests cover retry priority over ordinary egress,
queued-head ordering across multiple `Lookup` rows, normal fallback, stale
head blocking, no-candidate reporting, and Chisel elaboration.
