# SCBStateUpdate

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/SCBStateUpdate.scala`
- Upstream selector/control:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/SCBEgressSelect.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/SCBLookupControl.scala`
- Shared state:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/SCBEntryState.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/SCBCommitIngress.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/SCBStateUpdateSpec.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/l1/SCB.cpp`
  - `model/LinxCoreModel/model/l1/SCB.h`
  - `model/LinxCoreModel/model/l1/cluster.cpp`
- Contract IDs: `LC-CHISEL-LSU-SCB-STATE-001`

## Purpose

`SCBStateUpdate` is the Chisel owner for model-derived SCB row state
transitions after egress lookup outcome classification. It consumes current
SCB line entries, the masks emitted by `SCBLookupControl`, and one decoded
memory response entry id from `SCBResponseDecode`. It produces the next row
image plus transition and illegal-state masks for the registered SCB
composition owner.

The module owns:

- `Valid -> Lookup` for a selected row that starts a lookup,
- writable-hit row clear/free after DCache update intent,
- lookup miss transition to `Miss`,
- memory response `Miss -> Lookup`,
- illegal transition reporting for response/mask plumbing mistakes.

It does not own SCB row registers, DCache RAM mutation, L2/CHI queues, raw
WriteResp/UpgradeResp transaction-id decode, MDB conflict prediction,
store-to-load forwarding, or final STQ free authorization.

## Interface

### Inputs

| Signal | Type | Description |
|---|---|---|
| `entries` | `Vec[SCBLineEntry]` | Current SCB row image. |
| `acceptedMask` | `UInt(scbEntries.W)` | Selected row accepted from `SCBLookupControl`. |
| `missMask` | `UInt(scbEntries.W)` | Selected row observed as non-writable and sent to L2 ownership. |
| `freeMask` | `UInt(scbEntries.W)` | Selected row hit writable DCache and can be cleared after update intent. |
| `memRespValid` | `Bool` | `SCBResponseDecode` decoded a legal response for an SCB row. |
| `memRespEntryIndex` | `UInt` | Decoded SCB row id from the memory response transaction id. |

### Outputs

| Signal | Description |
|---|---|
| `nextEntries` | Row image after applying the local transition priority. |
| `memRespMask` | One-hot response row decoded from `memRespEntryIndex`. |
| `acceptedToLookupMask` | Rows moved from `Valid` to `Lookup` without same-cycle hit or miss completion. |
| `missStateMask` | Rows moved to `Miss`. |
| `respToLookupMask` | Rows returned from `Miss` to `Lookup` by memory response. |
| `clearedMask` | Rows cleared/freed by writable-hit completion. |
| `acceptedIllegalMask` | `acceptedMask` targeted a row that was not valid `Valid`. |
| `missIllegalMask` | `missMask` targeted a row outside valid `Valid` or `Lookup`. |
| `freeIllegalMask` | `freeMask` targeted a row outside valid `Valid` or `Lookup`. |
| `memRespIllegalMask` | Memory response targeted a row outside valid `Miss`. |
| `illegalMask/stateError` | Combined illegal transition observability. |

## State

The module is combinational. It computes a next row image but does not store
it. `SCBRowBank` is the first registered owner that stores `nextEntries` after
staging committed-store ingress and lookup outcome classification.

## Logic Design

The model sequence is:

1. `SCBuffer::handleFull()` selects `S_VALID` rows and moves selected work to
   `S_LOOKUP`.
2. `SCBuffer::handleLookup()` calls the DCache lookup path.
3. Writable hits update valid bytes, erase the combine map, and free the row.
4. Non-writable lookups move the row to `S_MISS` and send an ownership request.
5. `SCBuffer::handleMemResp()` asserts the response row is `S_MISS`, moves it
   back to `S_LOOKUP`, and queues it for another lookup attempt.

`SCBStateUpdate` uses a deterministic transition priority for one row:

1. Legal `freeMask` clears the row.
2. Legal `missMask` moves the row to `Miss`.
3. Legal memory response moves the row to `Lookup`.
4. Legal `acceptedMask` moves the row to `Lookup`.
5. Otherwise the row is preserved.

`acceptedMask` may appear with `freeMask` or `missMask` in the same abstract
cycle because `SCBLookupControl` can classify the accepted lookup immediately.
That case is legal when the current row is `Valid`; the final state is the hit
or miss result, not an intermediate `Lookup` row.

## Timing

All outputs are combinational from the current row image and masks. This
packet deliberately leaves queueing, response ordering, and registered egress
issue in later owner modules.

## Flush/Recovery

There is no flush input. SCB rows hold committed stores, and this packet only
models the DCache/L2 row lifecycle. Global SCB invalidation, LSU recovery
cleanup, or nuke behavior must be introduced as separate model-backed owners.

## Trace/Observability

The transition and illegal masks are local LSU observability surfaces. They
are not architectural commit rows and do not participate in QEMU-vs-DUT trace
comparison until the full LSU exposes memory event trace payloads.

## Verification

- `bash tools/chisel/run_chisel_tests.sh --only SCBStateUpdate`
- `bash tools/chisel/build_chisel.sh`
- `bash tools/chisel/run_chisel_tests.sh --only SCBLookupControl`
- `bash tools/chisel/run_chisel_tests.sh --only SCBEgressSelect`
- `bash tools/chisel/run_chisel_tests.sh --only SCBCommitBridge`
- `bash tools/chisel/run_chisel_rob_bookkeeping.sh --reduced-rob`
- `python3 tools/chisel/trace_schema_adapter.py --self-test`
- `bash tools/chisel/run_chisel_qemu_crosscheck.sh --dry-run`
- `bash tools/chisel/run_chisel_reduced_rob_xcheck.sh`
- `bash tools/chisel/run_chisel_top_xcheck.sh`
- `bash tools/chisel/run_chisel_verilator_lint.sh`

Focused reference tests cover valid lookup start, same-cycle miss, writable-hit
free, registered lookup miss, memory response return, illegal response target,
free-priority behavior, illegal miss/free visibility, and Chisel elaboration.
