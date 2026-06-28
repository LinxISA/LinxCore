# STQSCBCommitPath

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/STQSCBCommitPath.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/STQSCBCommitPathSpec.scala`
- Child Chisel contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/STQEntryBank.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/STQCommitDrain.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/SCBRowBank.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/lsu/store_unit/stq.cpp`
    - `STQ::retire`
    - `STQ::commit`
  - `model/LinxCoreModel/model/lsu/store_unit/store_unit.cpp`
    - `StoreUnit::GetCommitID`
- Contract IDs: `LC-CHISEL-LSU-STQ-SCB-COMMITPATH-001`

## Purpose

`STQSCBCommitPath` is the first full STQ-to-SCB composition owner. Earlier
packets proved the STQ bank, ordered commit queue, split-aware drain, and
registered SCB row bank separately. This module wires those owners into one
store-commit path so accepted SCB admission, not early drain issue, is the only
source of committed-row free commands back to `STQEntryBank`.

The module owns:

- `STQEntryBank` row allocation, mark-commit, flush-prune consumption, and final
  committed-row free mutation;
- `STQCommitDrain` queue ordering and split request descriptor generation;
- `SCBRowBank` model-batch admission, row-bank insertion, egress lookup, and
  final free-mask authorization for accepted `last` fragments;
- raw WriteResp/UpgradeResp tag handoff into `SCBRowBank`;
- the composition rule that drains are held while the registered SCB model
  batch gate is closed or while an STQ flush-prune cycle owns the bank.

It does not own L2/CHI queue storage or response arbitration, DCache RAM
mutation, MDB conflict prediction, store-to-load forwarding, BSB window-slide
side effects, or live memory-event trace rows.

## Interface

### Inputs

| Signal | Type | Description |
|---|---|---|
| `flush` | `FlushBus` | Recovery cleanup request consumed by `STQEntryBank` through `STQFlushPrune`. |
| `insertValid/insert` | `STQStoreRequest` | Store dispatch input for the STQ bank. |
| `markCommitValid/markCommitIndex` | command | Marks one locally ready STQ row `Commit` and enqueues it into the ordered commit drain. |
| `issueEnable` | `Bool` | Enables memory-side drain issue when the SCB model-batch gate is also open. |
| `evictEnable` | `Bool` | Enables one SCB egress lookup candidate after accepted ingress. |
| `dcacheReady/dcacheWriteHit/dcacheTagHit` | abstract DCache result | Lookup outcome inputs forwarded to `SCBRowBank`. |
| `l2RequestReady` | `Bool` | Abstract ownership request queue readiness. |
| `rawRespValid/rawRespTxnId` | raw response | Raw response tag candidate forwarded into `SCBRowBank`. |
| `rawRespWrite/rawRespUpgrade` | response type | WriteResp/UpgradeResp type flags forwarded into `SCBRowBank`. |

### Outputs

| Signal | Description |
|---|---|
| `insert*` / `markCommit*` | STQ bank insertion and commit-mark acknowledgement. |
| `scbReadyForDrain` | Registered SCB row bank has enough pre-cycle free rows for the worst-case request batch and no STQ flush-prune is active. |
| `drainIssueEnable/downstreamReadyMask` | The derived drain issue gate and all-row downstream-ready mask used for SCB issue. |
| `stq*` | STQ row image, occupancy, wait/commit masks, flush masks, and final free acknowledgements. |
| `drain*` | Ordered commit queue observability, issued rows, generated request descriptors, and debug-only early drain free mask. |
| `scb*` | SCB model-batch status, accepted/stalled masks, final free mask, wakeups, row-bank state, response decode flags, and lookup/state-update descriptors. |

## State

The module owns no registers directly. It composes three state owners:

- `STQEntryBank` owns STQ rows and resident/outstanding counts.
- `STQCommitDrain` owns the ordered store-commit queue.
- `SCBRowBank` owns SCB line entries.

## Logic Design

The LinxCoreModel store unit calls `STQ::retire()` before `STQ::commit()` in
`StoreUnit::GetCommitID()`. `retire()` changes ready, non-flushable stores from
`STQ_WAIT` to `STQ_COMMIT` and appends their row indices to `storeCommitQ`.
`commit()` then walks `storeCommitQ` from old to young, skips entries whose SCB
target is stalled, sends one or two split fragments, and frees the STQ row only
after the memory-side send succeeds.

The Chisel composition maps that behavior into registered owner boundaries:

1. `STQEntryBank.markCommitAccepted` enqueues the current row identity into
   `STQCommitDrain`.
2. `SCBRowBank.modelBatchReady` gates the drain issue path. If the row bank has
   fewer free rows than the worst-case split request batch, the drain queue does
   not issue or compact.
3. `STQEntryBank.flushApplied` also suppresses drain issue so a flush-prune
   cycle cannot allow SCB insertion while the bank intentionally ignores free
   commands.
4. `STQCommitDrain` shapes selected committed rows into one or two
   `STQCommitDrainRequest` fragments.
5. `SCBRowBank` accepts those fragments, coalesces them into line entries, and
   emits `commitFreeMask` only for accepted fragments with `last=1`.
6. `STQEntryBank.commitFreeMask` is driven only from
   `SCBRowBank.commitFreeMask`. `STQCommitDrain.commitFreeMask` is exported as
   `drainEarlyFreeMask` for debug visibility and is not wired to bank mutation.

This preserves the model requirement that committed stores remain resident when
the SCB path cannot accept them.

## Timing

`STQCommitDrain` observes the registered STQ row image, so a row marked
`Commit` in the current cycle is enqueued immediately but becomes eligible for
issue from the next visible bank row image. Older already-committed rows can
issue in the same cycle that a younger row is marked and enqueued.

`SCBRowBank` still uses pre-cycle free count for the model batch gate. A
same-cycle SCB hit free does not reopen the drain path in that cycle.

## Flush/Recovery

`STQEntryBank` remains the flush-prune state owner. When `flushApplied` is high,
this composition suppresses drain issue even if SCB capacity is available. That
keeps the bank's flush-owned cycle from accepting SCB-side free commands that
the bank is required to ignore.

SCB rows are committed-store state and are not flushed by this module.

## Trace/Observability

This module exposes enough STQ/drain/SCB masks to become the future memory-event
trace source, but it does not emit architectural trace rows yet. QEMU
cross-check evidence remains through the existing ROB/top synthetic trace path
until the top can retire live memory operations.

## Verification

- `bash tools/chisel/run_chisel_tests.sh --only STQSCBCommitPath`
- `bash tools/chisel/build_chisel.sh`
- `bash tools/chisel/run_chisel_tests.sh --only SCBRowBank`
- `bash tools/chisel/run_chisel_tests.sh --only STQCommitDrain`
- `bash tools/chisel/run_chisel_tests.sh --only STQEntryBank`
- `bash tools/chisel/run_chisel_tests.sh --only STQCommitQueue`
- `bash tools/chisel/run_chisel_rob_bookkeeping.sh --reduced-rob`
- `python3 tools/chisel/trace_schema_adapter.py --self-test`
- `bash tools/chisel/run_chisel_qemu_crosscheck.sh --dry-run`
- `bash tools/chisel/run_chisel_reduced_rob_xcheck.sh`
- `bash tools/chisel/run_chisel_top_xcheck.sh`
- `bash tools/chisel/run_chisel_verilator_lint.sh`

Focused reference tests cover final `last`-fragment free ownership, SCB
model-batch backpressure, split-store final free, concurrent older drain plus
younger enqueue, and Chisel elaboration with `STQEntryBank`, `STQCommitDrain`,
and `SCBRowBank` children.
