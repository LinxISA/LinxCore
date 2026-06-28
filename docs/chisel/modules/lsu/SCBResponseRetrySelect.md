# SCBResponseRetrySelect

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/SCBResponseRetrySelect.scala`
- Upstream normal egress owner:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/SCBEgressSelect.scala`
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

`SCBResponseRetrySelect` owns the model `resp_list` priority point at the
Chisel SCB egress boundary. A legal raw response moves an SCB row from `Miss`
back to `Lookup`; that returned row must retry DCache lookup before ordinary
`Valid` rows are evicted by `SCBEgressSelect`.

The module owns:

- retry candidate detection for valid rows in `SCBEntryState.Lookup`,
- first-row retry selection,
- priority override of the ordinary `SCBEgressSelect` lookup descriptor,
- final lookup mask and retry/normal observability.

It does not own raw response FIFO storage, transaction-id decode, DCache/L2
lookup outcome classification, row-state mutation, DCache RAM mutation, MDB
policy, or memory-event trace rows.

## Interface

### Inputs

| Signal | Type | Description |
|---|---|---|
| `entries` | `Vec[SCBLineEntry]` | Current post-ingress SCB row image. Valid `Lookup` rows are retry candidates. |
| `normalLookupRequest` | `SCBEgressLookupRequest` | Ordinary valid-row eviction descriptor from `SCBEgressSelect`. |
| `normalLookupMask` | `UInt(scbEntries.W)` | One-hot ordinary selected row mask from `SCBEgressSelect`. |

### Outputs

| Signal | Description |
|---|---|
| `retryCandidateMask` | All valid `Lookup` rows eligible for response retry. |
| `retryLookupMask` | One-hot selected retry row, or zero when no retry candidate exists. |
| `normalSelectedMask` | Forwarded ordinary egress mask when no retry wins. |
| `lookupMask` | Final one-hot row sent to `SCBLookupControl`. |
| `lookupRetry/lookupNormal` | Final selection source. |
| `lookupFull/lookupNotFull` | Classification of the final selected descriptor. |
| `noCandidate` | Neither retry nor ordinary egress produced a lookup descriptor. |
| `lookupRequest` | Final descriptor forwarded to `SCBLookupControl`. |

## State

The module is combinational. It stores no retry queue. In the current row-bank
abstraction, a valid `Lookup` row is the durable representation of a
response-returned retry candidate. A later packet can add a precise ordered
`resp_list` row-id FIFO if multiple same-cycle response returns require stricter
model ordering.

## Logic Design

The model sequence is:

1. `SCBuffer::handleMemResp()` consumes one queued memory response, asserts the
   row is `S_MISS`, changes it to `S_LOOKUP`, and pushes the row into
   `resp_list`.
2. `SCBuffer::Work()` checks `resp_list` before ordinary `lookup_list` work.
3. If DCache can issue, the response-returned row retries through
   `SCBuffer::handleLookup()`.

This Chisel owner implements the priority point:

1. Build `retryCandidateMask` from valid rows whose state is `Lookup`.
2. Select the first retry candidate when any retry row exists.
3. Otherwise forward the ordinary descriptor and one-hot mask from
   `SCBEgressSelect`.
4. Emit the selected descriptor to `SCBLookupControl`.

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

Focused reference tests cover retry priority over ordinary egress, first-row
selection among multiple retry candidates, normal fallback, no-candidate
reporting, and Chisel elaboration.
