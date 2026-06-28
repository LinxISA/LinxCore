# SCBRowBank

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/SCBRowBank.scala`
- Child Chisel contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/SCBEgressSelect.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/SCBLookupControl.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/SCBStateUpdate.scala`
- Related ingress/bridge contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/SCBCommitIngress.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/SCBCommitBridge.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/SCBRowBankSpec.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/l1/SCB.cpp`
  - `model/LinxCoreModel/model/l1/SCB.h`
  - `model/LinxCoreModel/model/l1/cluster.cpp`
- Contract IDs: `LC-CHISEL-LSU-SCB-ROWBANK-001`

## Purpose

`SCBRowBank` is the first registered SCB composition owner. Earlier packets
proved ingress, model-batch admission, egress selection, lookup classification,
and row-state update as independent owners. This packet puts those contracts
around one row bank so later full LSU composition has a single state owner.

The module owns:

- the registered SCB row image,
- model-batch ingress admission using pre-cycle free count,
- committed-store fragment merge only into `Valid` rows or empty rows,
- final committed STQ free masks for accepted `last` fragments,
- lookup candidate selection after accepted ingress updates,
- DCache/L2 lookup outcome classification,
- row-state registration after hit, miss, or decoded memory response.

It does not own raw CHI TxnID decode, L2/CHI queue storage, DCache RAM
mutation, MDB conflict prediction, store-to-load forwarding, or wiring the
full `STQEntryBank` free path.

## Interface

### Inputs

| Signal | Type | Description |
|---|---|---|
| `reqs` | `Vec[STQCommitDrainRequest]` | Committed store fragments from the drain/bridge boundary. |
| `evictEnable` | `Bool` | Enables one SCB lookup candidate selection. |
| `dcacheReady` | `Bool` | Abstract DCache lookup port readiness. |
| `dcacheWriteHit` | `Bool` | Writable-hit result from the abstract DCache lookup. |
| `dcacheTagHit` | `Bool` | Tag-present result used to choose upgrade versus write ownership. |
| `l2RequestReady` | `Bool` | Ownership request queue readiness. |
| `memRespValid` | `Bool` | A future response decoder found a row response. |
| `memRespEntryIndex` | `UInt` | Decoded SCB row id for WriteResp/UpgradeResp. |

### Outputs

| Signal | Description |
|---|---|
| `modelBatchReady/modelFull` | Conservative `SCBuffer::full()` style admission gate from pre-cycle free count. |
| `acceptedMask/stalledMask/structuralBlockedMask` | Per-ingress-lane admission and stall summary. |
| `commitFreeMaskValid/commitFreeMask/commitFreeCount` | STQ free authorization for accepted `last` fragments. |
| `wakeups` | Post-merge line-valid wakeup descriptors. |
| `entries/nextEntries` | Current registered row image and next row image. |
| `validMask/fullLineMask/entryCount/freeCount/ingressFull` | Current row-bank occupancy summary. |
| `lookupRequest`, candidate masks, and lookup classification | Pass-through observability from `SCBEgressSelect` and `SCBLookupControl`. |
| `dcacheUpdate/l2Request` | Abstract DCache update and L2 ownership request descriptors. |
| `state*Mask/stateError` | Transition and illegal-state observability from `SCBStateUpdate`. |

## State

`SCBRowBank` owns the SCB entry registers. Outputs named `entries`, counts, and
occupancy masks describe the current registered state. `nextEntries` exposes
the combinational image that will be written at the next clock edge.

## Logic Design

The model `SCBuffer::Xfer()` performs insert work before lookup and response
side effects. This Chisel owner preserves the useful hardware boundary from
that order while retaining deterministic single-request egress:

1. Count current free entries and compute `modelBatchReady` before same-cycle
   hit frees are visible.
2. If the batch gate is open, apply committed-store fragments in lane order.
   Hits are allowed only against valid `SCBEntryState.Valid` rows with the
   same line address. `Lookup` and `Miss` rows are not merge targets.
3. Emit wakeups and final STQ free masks only for accepted ingress lanes.
4. Select one valid egress candidate from the post-ingress staged row image.
5. Classify the abstract DCache/L2 outcome with `SCBLookupControl`.
6. Apply hit clear, miss state, and response return with `SCBStateUpdate`.
7. Register the resulting row image.

This owner intentionally keeps the pre-cycle model batch gate. A row freed by
a same-cycle writable DCache hit does not allow a committed-store fragment to
enter in that same cycle.

## Timing

The row image updates once per cycle. Ingress is staged combinationally before
egress lookup classification so a same-cycle merge can be included in the
lookup payload. The conservative batch gate still uses the registered
pre-cycle free count.

## Flush/Recovery

There is no flush input. SCB rows represent committed-store state. Later LSU
cleanup, nuke, or global invalidation work must add a separate model-backed
owner rather than overloading this row-bank packet.

## Trace/Observability

`SCBRowBank` exposes local LSU masks and descriptors for future memory-event
trace work. It is not yet an architectural trace source and is not used by the
QEMU-vs-DUT commit comparator.

## Verification

- `bash tools/chisel/run_chisel_tests.sh --only SCBRowBank`
- `bash tools/chisel/build_chisel.sh`
- `bash tools/chisel/run_chisel_tests.sh --only SCBStateUpdate`
- `bash tools/chisel/run_chisel_tests.sh --only SCBLookupControl`
- `bash tools/chisel/run_chisel_tests.sh --only SCBEgressSelect`
- `bash tools/chisel/run_chisel_tests.sh --only SCBCommitBridge`
- `bash tools/chisel/run_chisel_tests.sh --only SCBCommitIngress`
- `bash tools/chisel/run_chisel_rob_bookkeeping.sh --reduced-rob`
- `python3 tools/chisel/trace_schema_adapter.py --self-test`
- `bash tools/chisel/run_chisel_qemu_crosscheck.sh --dry-run`
- `bash tools/chisel/run_chisel_reduced_rob_xcheck.sh`
- `bash tools/chisel/run_chisel_top_xcheck.sh`
- `bash tools/chisel/run_chisel_verilator_lint.sh`

Focused reference tests cover model-batch admission, pre-cycle free-count
gating, same-cycle ingress merge plus writable hit, miss ownership request,
response return, outstanding-row non-merge, illegal response reporting, and
Chisel elaboration with the egress/lookup/state-update children.
