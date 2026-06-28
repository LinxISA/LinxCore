# SCBEgressSelect

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/SCBEgressSelect.scala`
- Shared state: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/SCBEntryState.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/SCBEgressSelectSpec.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/l1/SCB.h`
  - `model/LinxCoreModel/model/l1/SCB.cpp`
  - `model/LinxCoreModel/model/l1/cluster.cpp`
- Related Chisel contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/SCBCommitIngress.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/SCBCommitBridge.scala`
- Contract IDs: `LC-CHISEL-LSU-SCB-EGRESS-001`

## Purpose

`SCBEgressSelect` is the first Chisel owner for choosing which committed
store-coalescing buffer line should leave the SCB ingress store. It consumes
the registered SCB entries exposed by `SCBCommitBridge`, filters for model
`S_VALID` entries, prefers full valid lines, and otherwise selects a
deterministic valid not-full line when eviction pressure is asserted.

The module owns:

- the Chisel `SCBEntryState` vocabulary matching model `S_EMPTY`, `S_VALID`,
  `S_LOOKUP`, and `S_MISS`,
- candidate masks for valid, full, and not-full evictable SCB entries,
- one lookup request descriptor per cycle,
- deterministic first-entry fallback for the model's random not-full eviction.

It does not own DCache tag lookup, DCache data update, L2/CHI write or upgrade
request formatting, write-response matching, freeing SCB rows, MDB conflict
prediction, load forwarding, or final STQ free authorization.
`SCBLookupControl` consumes its `lookupRequest` and owns the next abstract
DCache/L2 outcome boundary.

## Interface

### Inputs

| Signal | Type | Description |
|---|---|---|
| `evictEnable` | `Bool` | Pressure bit equivalent to calling model `SCBuffer::handleFull()`. When false, candidate masks are still reported but no lookup request is issued. |
| `entries` | `Vec[SCBLineEntry]` | Registered SCB entries from the bridge/ingress owner. Only entries with `valid=1` and `state=Valid` can be selected. |

### Outputs

| Signal | Description |
|---|---|
| `validStateMask` | Entries currently in model `S_VALID` state. |
| `fullCandidateMask` | `S_VALID` entries whose byte mask is full. These have priority. |
| `notFullCandidateMask` | `S_VALID` entries with partial byte masks. These are the deterministic fallback candidates. |
| `lookupMask` | One-hot selected entry for the next lookup/request owner. |
| `lookupFull/lookupNotFull` | Classification of the selected request. |
| `noCandidate` | `evictEnable` was asserted but no `S_VALID` entry was available. |
| `lookupRequest` | Selected entry index, line address, byte mask, data, and full-line flag. |

## State

`SCBEgressSelect` is combinational. It introduces no SCB storage and does not
mutate entry state. `SCBLookupControl` classifies the selected lookup outcome,
and `SCBStateUpdate` consumes those masks to move selected rows from `Valid` to
`Lookup`, move misses to `Miss`, and move responses back to `Lookup` before
row free. A later registered SCB row-bank owner must store those transitions.

`SCBCommitIngress` initializes accepted entries to `SCBEntryState.Valid`.
Future ingress integration must avoid merging new stores into `Lookup` or
`Miss` rows unless the model and hardware policy are updated together.

## Logic Design

The model `SCBuffer::handleFull()` runs under SCB pressure. It scans all
entries, moves every full `S_VALID` row to `S_LOOKUP`, and returns if any full
row was found. If no full row exists, it chooses one random `S_VALID` row and
moves it to `S_LOOKUP`.

This Chisel packet turns that behavior into a one-request-per-cycle hardware
selector:

1. Build `validStateMask` from entries whose `valid` bit is set and state is
   `SCBEntryState.Valid`.
2. Build `fullCandidateMask` and `notFullCandidateMask` from that valid-state
   mask.
3. If at least one full candidate exists, choose the first full candidate.
4. If no full candidate exists, choose the first not-full candidate.
5. Gate the actual request with `evictEnable`.

The deterministic first-entry fallback replaces model randomness so RTL,
reference tests, and QEMU cross-check infrastructure remain reproducible.

## Timing

All outputs are combinational from the current SCB entries. The selected row is
not consumed or cleared in this packet. Backpressure from DCache/L2 request
owners should gate `evictEnable` or be handled by a later registered egress
queue.

## Flush/Recovery

There is no flush input. SCB rows are committed-store state, and this selector
only exposes candidates. Any future global SCB invalidation must be a separate
model-backed recovery packet.

## Trace/Observability

The candidate masks and lookup descriptor are intended as local LSU debug
surface. They are not architectural commit trace rows and do not yet
participate in QEMU-vs-DUT trace comparison.

## Verification

- `bash tools/chisel/run_chisel_tests.sh --only SCBEgressSelect`
- `bash tools/chisel/run_chisel_tests.sh --only SCBLookupControl`
- `bash tools/chisel/build_chisel.sh`
- `bash tools/chisel/run_chisel_tests.sh --only SCBCommitBridge`
- `bash tools/chisel/run_chisel_tests.sh --only SCBCommitIngress`
- `bash tools/chisel/run_chisel_tests.sh --only STQCommitDrain`
- `bash tools/chisel/run_chisel_rob_bookkeeping.sh --reduced-rob`
- `python3 tools/chisel/trace_schema_adapter.py --self-test`
- `bash tools/chisel/run_chisel_qemu_crosscheck.sh --dry-run`

Focused reference tests cover full-line priority, deterministic not-full
fallback, disabled-eviction masking, ignoring `Lookup`/`Miss` rows, no-candidate
reporting, and Chisel elaboration.
