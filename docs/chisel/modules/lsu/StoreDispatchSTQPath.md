# StoreDispatchSTQPath

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/StoreDispatchSTQPath.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/StoreDispatchSTQPathSpec.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/lsu/store_unit/store_unit.cpp`
    - `StoreUnit::store`
    - `StoreUnit::insertStq`
  - `model/LinxCoreModel/model/lsu/store_unit/stq.cpp`
    - `STQ::insert`
    - `STQ::mergeStore`
    - `STQueueEntryInfo::init`
  - `model/LinxCoreModel/model/ModelCommon/bus/MemReqBus.h`
    - `MemReqBus::tSeq/uSeq`
- Related Chisel:
  - `chisel/src/main/scala/linxcore/lsu/StoreDispatchQueues.scala`
  - `chisel/src/main/scala/linxcore/lsu/StoreDispatchToSTQ.scala`
  - `chisel/src/main/scala/linxcore/lsu/STQInsertProbe.scala`
  - `chisel/src/main/scala/linxcore/lsu/STQEntryBank.scala`

## Purpose

`StoreDispatchSTQPath` is the first Chisel composition owner for queue-backed
store dispatch into STQ row mutation. It wires:

- `StoreDispatchQueues` for finite STA/STD queue storage,
- `StoreDispatchToSTQ` for typed request formation after explicit execution
  results,
- two `STQInsertProbe` instances for independent STA and STD readiness from
  the live STQ row image,
- `STQEntryBank` for accepted insert, merge, commit-mark, committed free, and
  recovery mutation.

The module does not compute STA addresses or STD data. Execution results
remain explicit inputs until the real AGU and store-data owners exist.

## Interface

Inputs:

- `flush`: recovery bus consumed by the queues and `STQEntryBank`.
- `staIn`, `stdIn`, `unsplitIn`: store split payloads from the rename/store
  split owner.
- `staExec`, `stdExec`: explicit execution results for the visible queue
  heads.
- `markCommit*`, `commitFree*`, `commitFreeMask*`: current bank-side commit
  and memory-free hooks.

Outputs:

- Queue admission and dequeue diagnostics: `staReady`, `stdReady`,
  `inputProtocolError`, enqueue/dequeue fires, queue heads, and counts.
- Per-candidate insert diagnostics: `staInsertReady`, `stdInsertReady`,
  merge/allocation/conflict bits, and selected insert indices.
- Bridge diagnostics: candidates, selected side, execution-result stalls,
  insert stalls, and `stdBypassStaBlocked`.
- STQ bank diagnostics: insert result, commit/free result, flush masks, row
  image, occupancy masks, resident count, outstanding-WAIT count, and
  empty/full/stall.
- LSU T/U source diagnostics: `lsuTULinkSource`,
  `lsuTULinkSourceMatched`, and `lsuTULinkSourceMultipleMatch`, forwarded from
  `STQEntryBank`.

## Logic Design

The queue stage preserves the R48 contract: split stores enqueue STA and STD
atomically, unsplit stores enqueue only STA, readiness is capacity-only and
flush-qualified, and protocol-shape errors are observable but not readiness
inputs.

The bridge stage preserves the R49 contract: a queue head is dequeued only when
its execution result is valid and its own STQ insert probe reports readiness.
When both candidates are ready, STA wins. If a present executed STA cannot
insert but the STD candidate can merge or allocate, STD is allowed to issue.

The STQ probes are deliberately independent. STA and STD readiness are computed
from the same pre-cycle STQ row image before the bridge chooses one request.
This is required for the model progress case:

```text
STA head: new ST_ADDR row, STQ full -> cannot allocate
STD head: matching ST_DATA for an existing ST_ADDR row -> can merge
decision: select STD and dequeue only STD
```

The selected request is the only request sent to `STQEntryBank` in a cycle.
The bank uses the same `STQInsertProbe` predicate internally, so the composed
path and standalone bank cannot drift on insert readiness.

The path forwards the STQ bank's LSU T/U source candidate unchanged. This
keeps queue-backed store dispatch and STQ row mutation as the source owner, but
gives the future recovery-cleanup composition a stable LSU-side port for exact
non-base `(bid,rid,stid)` cleanup.

## Model Alignment

`StoreUnit::store` drains STA-side queues before STD-side queues, but
`StoreUnit::insertStq` allows split halves to merge through `STQ::mergeStore`
even when new allocation is blocked by `STQ::full()`. `StoreDispatchSTQPath`
preserves both properties: STA wins when both candidates are insertable, while
STD can bypass a blocked STA when the STD half is independently insertable.

## Deferred Owners

- Real STA address generation and STD data selection.
- Ready-table/source wakeup effects before store execution.
- Load-conflict probe publication after accepted STQ insert.
- Store-data wakeup, memory trace, exceptions, and SCB/MDB integration.
- Integration that replaces the reduced `DecodeRenameROBPath` queue instance
  with this LSU-side composition.
- Top-level wiring from `lsuTULinkSource` into
  `TULinkRecoveryCleanupPath.lsuSource`.

## Verification

Focused gate:

```bash
bash tools/chisel/run_chisel_tests.sh --only StoreDispatchSTQPath
```

Affected gates:

```bash
sbt --client --error 'Test / compile'
bash tools/chisel/run_chisel_tests.sh --only STQInsertProbe
bash tools/chisel/run_chisel_tests.sh --only StoreDispatchToSTQ
bash tools/chisel/run_chisel_tests.sh --only StoreDispatchQueues
bash tools/chisel/run_chisel_tests.sh --only STQEntryBank
bash tools/chisel/run_chisel_tests.sh --only StoreSplitPayload
bash tools/chisel/run_chisel_tests.sh --only DecodeRenameROBPath
bash tools/chisel/run_chisel_tests.sh --only InterfaceBundles
bash tools/chisel/run_chisel_rob_bookkeeping.sh --reduced-rob
bash tools/chisel/run_chisel_top_xcheck.sh
bash tools/chisel/run_chisel_qemu_crosscheck.sh --dry-run
bash tools/chisel/build_chisel.sh
bash tools/chisel/run_chisel_verilator_lint.sh
```

Focused tests cover STD merge-bypass against a full STQ, STA priority when both
candidates can insert, flush suppression, IO widths, and CIRCT elaboration
through all composed child modules.
