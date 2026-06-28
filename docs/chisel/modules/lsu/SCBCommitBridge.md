# SCBCommitBridge

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/SCBCommitBridge.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/SCBCommitBridgeSpec.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/l1/SCB.h`
  - `model/LinxCoreModel/model/l1/SCB.cpp`
  - `model/LinxCoreModel/model/l1/cluster.cpp`
  - `model/LinxCoreModel/model/lsu/lsu.cpp`
  - `model/LinxCoreModel/model/lsu/store_unit/stq.cpp`
- Related Chisel contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/STQCommitDrain.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/SCBCommitIngress.scala`
- Contract IDs: `LC-CHISEL-LSU-SCB-BRIDGE-001`

## Purpose

`SCBCommitBridge` is the first Chisel owner for the capacity feedback boundary
between committed STQ drain descriptors and SCB line storage. It composes
`SCBCommitIngress`, gates ingress with the model `SCBuffer::full()` rule, and
returns the committed-row free mask only after the final descriptor for a row is
accepted into SCB.

The module owns:

- the model batch gate for committed-store SCB admission,
- masking `STQCommitDrainRequest.valid` before structural SCB insertion,
- stalled fragment reporting when the model batch gate is closed,
- final-fragment-to-`STQEntryBank.commitFreeMask` conversion,
- pass-through observability for SCB entries and wakeups.

It does not own DCache lookup/update, full/not-full eviction, L2/CHI request
generation, write-response matching, MDB conflict prediction, load forwarding,
or fence/store-drain completion. Those remain future LSU owner packets.

## Interface

### Inputs

| Signal | Type | Description |
|---|---|---|
| `reqs` | `Vec[STQCommitDrainRequest]` | Candidate store fragments from `STQCommitDrain`. Split stores must present the first fragment with `last=0` and the second with `last=1`. |

### Outputs

| Signal | Description |
|---|---|
| `modelBatchReady` | True when current SCB free entries are at least the bridge request width, matching model `!scb.full()`. |
| `modelFull` | Inverse of `modelBatchReady`; valid descriptors must stall before freeing STQ rows. |
| `acceptedMask` | Descriptor lanes admitted into `SCBCommitIngress`. |
| `stalledMask` | Valid descriptor lanes not admitted because the model gate or structural ingress blocked them. |
| `structuralBlockedMask` | Raw `SCBCommitIngress.blockedMask` after model gating. |
| `commitFreeMaskValid/commitFreeMask/commitFreeCount` | STQ bank free command for rows whose accepted descriptor has `last=1`. |
| `wakeups` | SCB line-valid wakeups from accepted fragments. |
| `entries/validMask/fullLineMask/entryCount/freeCount/ingressFull` | SCB entry diagnostics forwarded from `SCBCommitIngress`. |

## State

`SCBCommitBridge` owns no additional state. It owns the composition boundary
and instantiates `SCBCommitIngress`, which owns the registered SCB line entries.

## Logic Design

The bridge first observes the current SCB `freeCount` from the ingress owner.
The model `SCBuffer::full()` returns true when `free_list.size() < n_store_in`;
this Chisel packet maps `n_store_in` to `requestCount`. Therefore:

```text
modelBatchReady = freeCount >= requestCount
```

If `modelBatchReady` is false, every valid request lane is reported in
`stalledMask` and no descriptor is presented to `SCBCommitIngress`. This is
intentionally more conservative than the structural ingress rule: even a
same-line hit stalls while the model batch gate is closed.

If `modelBatchReady` is true, request lanes pass through to
`SCBCommitIngress`. Accepted lanes update SCB state and produce wakeups. The
bridge converts accepted descriptors with `last=1` into `commitFreeMask` bits
for their `stqIndex`; this preserves the model ordering where `STQ::free`
happens after the memory-side queue accepts all descriptors for the store.

## Timing

The bridge is combinational around the current registered SCB ingress state.
Accepted descriptors update SCB entries on the next clock through
`SCBCommitIngress`. `commitFreeMask` is valid in the admission cycle and should
be consumed only by an integration owner that uses this bridge as the sole
source of committed-row frees.

## Flush/Recovery

There is no flush input. The bridge handles committed stores that have already
left flushable STQ `Wait` state. Recovery-domain pruning remains in
`STQFlushPrune` and `STQEntryBank`.

## Trace/Observability

`modelBatchReady`, `modelFull`, `stalledMask`, and `commitFreeMask` are the
first probe surface for the model queue-stall boundary between STQ commit drain
and SCB insertion. They are not architectural commit rows and do not yet
participate in QEMU-vs-DUT trace comparison.

## Verification

- `bash tools/chisel/run_chisel_tests.sh --only SCBCommitBridge`
- `bash tools/chisel/run_chisel_tests.sh --only SCBCommitIngress`
- `bash tools/chisel/run_chisel_tests.sh --only STQCommitDrain`
- `bash tools/chisel/run_chisel_tests.sh --only STQCommitQueue`
- `bash tools/chisel/run_chisel_tests.sh --only STQEntryBank`
- `bash tools/chisel/run_chisel_rob_bookkeeping.sh --reduced-rob`
- `python3 tools/chisel/trace_schema_adapter.py --self-test`
- `bash tools/chisel/run_chisel_qemu_crosscheck.sh --dry-run`

Focused reference tests cover model-batch admission, split-store final-fragment
freeing, conservative same-line-hit stall under model full, exact free-count
admission, and Chisel elaboration.
