# ROBEntryStatus

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/rob/ROBEntryStatus.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/rob/ROBEntryStatusSpec.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/pe/PECommon/PROBStatus.h`
  - `model/LinxCoreModel/model/pe/PECommon/PROBCommon.h`
  - `model/LinxCoreModel/model/bctrl/spe/SPEROB.cpp`
- Contract IDs: `LC-IF-CHISEL-ROB-STATUS-001`

## Purpose

`ROBEntryStatus` is the typed Chisel encoding for the LinxCoreModel
`PROBStatus` lifecycle. It is the first Phase 5 integrated ROB/CMT contract
slice after the reduced ROB harness: future ROB banks, CMT control, flush
pruning, deallocation, and trace publication must share the same status
vocabulary instead of inventing local booleans.

## Interface

`ROBEntryStatus` is a `ChiselEnum` with this model-preserving order:

| Value | Chisel name | Model name | Meaning |
|---:|---|---|---|
| 0 | `Free` | `INST_FREE` | Slot does not contain a live row |
| 1 | `Allocated` | `INST_ALLOCATED` | Row allocated but not renamed/issued |
| 2 | `Renamed` | `INST_RENAMED` | Row has entered renamed backend ownership |
| 3 | `Issued` | `INST_ISSUED` | Row has issued to an execution owner |
| 4 | `Completed` | `INST_COMPLETED` | Row is eligible for commit when it is the contiguous head |
| 5 | `Retired` | `INST_RETIRED` | Commit has marked the row retired; deallocation is still pending |
| 6 | `Fault` | `INST_FAULT` | Precise fault ownership placeholder |
| 7 | `NeedFlush` | `INST_NEEDFLUSH` | Row is still live but marked for recovery cleanup |

Helper predicates:

| Helper | True for | Intended owner |
|---|---|---|
| `occupiesRob` | all non-`Free` statuses | ROB size/residency accounting |
| `osdActive` | `Allocated`, `Renamed`, `Issued`, `Completed`, `NeedFlush` | model `osdSize`-like outstanding work |
| `canCommit` | `Completed` | CMT contiguous-head walk |
| `canDealloc` | `Retired` | deallocation/release walk |
| `flushClears` | same set as `osdActive` | recovery prune accounting |

## State

This file owns no registers. It is a shared enum and predicate package. A future
integrated ROB entry stores one `ROBEntryStatus.Type` per slot beside row
identity, decoded/renamed payload, block BID, checkpoint context, and side-effect
envelopes.

## Logic Design

The model has two distinct walks:

- `SPEROB::commit` walks from `commitPtr`, retires only contiguous
  `INST_COMPLETED` rows, and changes them to `INST_RETIRED`.
- `SPEROB::dealloc` walks from `deallocPtr`, releases only `INST_RETIRED` rows,
  applies side effects and cleanup, then frees the slot.

The enum keeps that separation explicit. `canCommit` must not include
`Retired`; `canDealloc` must not include `Completed`.

Flush/recovery cleanup also has a separate accounting shape. In the model,
`CheckNextEntryStatus` decrements outstanding work for allocated, renamed,
issued, completed, and need-flush rows. It does not treat free or already
retired rows as outstanding work. `flushClears` and `osdActive` preserve that
shape for the future integrated ROB.

## Timing

Status transitions are state-owner decisions. This package does not impose
cycle timing, but the future integrated ROB should keep the model's phase split:
completion marks a row completed, commit marks completed head rows retired, and
deallocation frees retired rows later.

## Flush/Recovery

`NeedFlush` remains a live slot state, not an immediate free. Flush owner logic
must still release rename/ready-table side effects and rebase pointers before a
slot becomes `Free`.

`Fault` is preserved as an encoded state even though the current Chisel reduced
ROB does not model precise traps yet. Later CMT/recovery work must define the
exact transition from `Fault` to trap commit or flush cleanup.

## Trace/Observability

The enum itself is not a trace row. Trace-producing ROB/CMT modules should map
`Completed -> Retired` commit events into `CommitTraceRow` and should expose
`Fault`/`NeedFlush` through recovery and trap sidebands before those rows are
trusted for QEMU comparison.

## Verification

- `bash tools/chisel/run_chisel_tests.sh --only ROBEntryStatus`
- `bash tools/chisel/build_chisel.sh`

The focused tests lock the model numeric order, residency versus outstanding
work predicates, commit/dealloc separation, flush-clear predicate, IO widths,
and Chisel elaboration of the helper predicates.
