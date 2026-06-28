# StoreDispatchQueues

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/StoreDispatchQueues.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/StoreDispatchQueuesSpec.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/bctrl/spe/SPERename.cpp`
    - `SPERename::InsertToStoreIEX`
    - `HandleSta`
  - `model/LinxCoreModel/model/ModelCommon/bus/MemReqBus.h`
    - `type` encoding comments for `ST_ALL`, `ST_ADDR`, and `ST_DATA`
- Related Chisel:
  - `chisel/src/main/scala/linxcore/rename/StoreSplitPayload.scala`
  - `chisel/src/main/scala/linxcore/backend/DecodeRenameROBPath.scala`
  - `chisel/src/main/scala/linxcore/lsu/StoreDispatchToSTQ.scala`
  - `chisel/src/main/scala/linxcore/lsu/StoreDispatchSTQPath.scala`
  - `chisel/src/main/scala/linxcore/lsu/STQEntryBank.scala`

## Purpose

`StoreDispatchQueues` is the first queue-backed Chisel owner for the model
STA/STD dispatch boundary behind scalar rename. It receives the
`StoreSplitPayload` output for one accepted renamed store row and enqueues
either:

- one `STA` payload and one `STD` payload atomically for split stores, or
- one `ST_ALL` payload into the STA queue for unsplit stores.

The module represents the admission behavior of the model
`pe_iex_sta_array`/`pe_iex_std_array` queues. It deliberately stops at queued
dispatch payloads. It does not compute store addresses, compute store data,
allocate or merge STQ rows, publish load-conflict probes, mutate SCB/MDB
state, or emit memory trace events.

## Interface

Inputs:

- `staIn`, `stdIn`: split-store address and data payload candidates from
  `StoreSplitPayload`, including `tSeq/uSeq` and T/U destination sidecars.
- `unsplitIn`: single `ST_ALL` store payload candidate from
  `StoreSplitPayload`, including the same row-owned T/U sidecars.
- `flushValid`: clears both queues and suppresses same-cycle enqueue/dequeue.
- `staDequeueReady`, `stdDequeueReady`: future execution-side consumers can
  accept the current STA or STD queue head.

Outputs:

- `staReady`, `stdReady`: capacity-only admission readiness used by upstream
  rename/store-split logic.
- `inputProtocolError`: diagnostic for impossible payload combinations.
- `splitInput`, `unsplitInput`: classified input shape.
- `staEnqueueFire`, `stdEnqueueFire`: accepted enqueue events.
- `staDequeueFire`, `stdDequeueFire`: accepted dequeue events.
- `staOutValid`, `stdOutValid`, `staOut`, `stdOut`: visible queue heads.
- `staCount`, `stdCount`, `staEmpty`, `stdEmpty`, `staFull`, `stdFull`:
  occupancy observability.

## Logic Design

The valid input shapes are mutually exclusive:

- split input: `staIn.valid && stdIn.valid && !unsplitIn.valid`;
- unsplit input: `unsplitIn.valid && !staIn.valid && !stdIn.valid`.

Mixed unsplit plus split-half inputs and lone split halves raise
`inputProtocolError`. Protocol errors block enqueue, but they do not feed back
into `staReady` or `stdReady`. Readiness is capacity-only:

```text
staReady = !flushValid && (staCount != depth || staDequeueFire)
stdReady = !flushValid && (stdCount != depth || stdDequeueFire)
```

This rule is intentional. `StoreSplitPayload` produces valid split payloads
from accepted rename output, and accepted rename output depends on store
dispatch readiness. Feeding payload validity back into readiness creates a
ready/valid combinational cycle. Protocol errors remain observable and still
suppress enqueue at this boundary.

Split stores enqueue only when both queues can accept in the same cycle. If
one side lacks space, neither side enqueues. Unsplit stores enqueue only to the
STA queue. A dequeue in the same cycle as an enqueue can free a full queue row
and keep occupancy stable. Flush clears both head/tail pointers and counts,
and suppresses enqueue and dequeue events for that cycle.

The queue entries preserve the complete `StoreSplitIssuePayload`, including
the row-owned `tSeq/uSeq` and T/U destination sidecars. The queue does not
interpret those fields; it only prevents the store-dispatch boundary from
discarding model `MemReqBus` recovery metadata before STQ request formation.

## Model Alignment

`SPERename::InsertToStoreIEX` first checks the model STA and STD dispatch
queues. Split stores require both queues to be non-stalled before the model
clones the store into `ST_ADDR` and `ST_DATA` rows and writes both queues.
Unsplit stores require only the STA queue and write the original store as
`ST_ALL`.

`StoreSplitPayload` already performs the clone-shape work for the reduced
Chisel path, including PCR source selection and ordinary STA source-zeroing.
`StoreDispatchQueues` owns the next model-equivalent boundary: finite queue
admission, backpressure, flush clearing, and visible queue-head handoff.
`StoreDispatchToSTQ` is the next bridge that consumes those heads after
explicit execution results are available. `StoreDispatchSTQPath` is the first
composition owner that wires these queues through `StoreDispatchToSTQ`,
per-candidate `STQInsertProbe` readiness, and `STQEntryBank` mutation.
R61 adds the T/U sidecar carry-through so cloned store halves keep the same
local-register recovery snapshots that the model stores on the cloned
`SimInst` and later `MemReqBus`.

## Deferred Owners

- STA address generation and STD data selection.
- Ready-table/source wakeup effects before store execution.
- Complementary partial-store merge in `STQEntryBank`.
- Store-arrival conflict probes to load/MDB logic.
- Memory-side trace, exception, and recovery side effects.

## Verification

Focused gate:

```bash
bash tools/chisel/run_chisel_tests.sh --only StoreDispatchQueues
```

Affected gates:

```bash
sbt --client --error 'Test / compile'
bash tools/chisel/run_chisel_tests.sh --only DecodeRenameROBPath
bash tools/chisel/run_chisel_tests.sh --only StoreDispatchSTQPath
bash tools/chisel/run_chisel_tests.sh --only StoreDispatchToSTQ
bash tools/chisel/run_chisel_tests.sh --only StoreSplitPayload
bash tools/chisel/run_chisel_tests.sh --only DecodeLoadStoreIdAssign
bash tools/chisel/run_chisel_tests.sh --only ScalarDecodeRenameBridge
bash tools/chisel/run_chisel_tests.sh --only DecodeRenameQueue
bash tools/chisel/run_chisel_tests.sh --only STQEntryBank
bash tools/chisel/run_chisel_tests.sh --only InterfaceBundles
bash tools/chisel/run_chisel_tests.sh --only DispatchROBAllocator
bash tools/chisel/run_chisel_rob_bookkeeping.sh --reduced-rob
bash tools/chisel/run_chisel_top_xcheck.sh
bash tools/chisel/run_chisel_qemu_crosscheck.sh --dry-run
bash tools/chisel/build_chisel.sh
bash tools/chisel/run_chisel_verilator_lint.sh
```

The focused tests cover atomic split enqueue, unsplit STA-only enqueue,
partial blocking when either queue is full, same-cycle dequeue/reenqueue,
protocol-error reporting, flush clearing, IO widths, enum ordering, and CIRCT
elaboration. R61 extends the IO and elaboration checks to the preserved T/U
sidecar fields.
