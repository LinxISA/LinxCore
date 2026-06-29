# ReducedCommitROB

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/rob/ReducedCommitROB.scala`
- Emitter: `rtl/LinxCore/chisel/src/main/scala/linxcore/rob/EmitReducedCommitROB.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/rob/ReducedCommitROBSpec.scala`
- Verilator smoke: `rtl/LinxCore/tools/chisel/reduced_rob_trace_tb.cpp`
- Previous pyCircuit owner: `rtl/LinxCore/src/bcc/backend/rob.py`, `rtl/LinxCore/src/bcc/backend/commit.py`
- LinxCoreModel evidence: `model/LinxCoreModel/model/bctrl/spe/SPEROB.cpp`
- Contract IDs: `LC-IF-CHISEL-ROB-001`, `LC-IF-CHISEL-XCHK-001`

## Purpose

`ReducedCommitROB` is the Phase 0B ROB/commit harness used before frontend,
decode, execute, and LSU are available in Chisel. It provides a concrete
head-ordered retirement owner and emits `CommitTraceRow` payloads for the neutral
QEMU cross-check path.

## Interface

| Direction | Signal | Type | Valid/ready | Description |
|---|---|---|---|---|
| input | `allocValid` | `Bool` | valid | Allocates `allocRow` into the tail when `allocReady` is true. |
| output | `allocReady` | `Bool` | ready | True when the ROB is not full and the identity is not duplicated. |
| output | `allocDuplicateIdentity` | `Bool` | combinational | True when `allocRow.identity.(bid,gid,rid)` matches a live entry. |
| input | `allocRow` | `CommitTraceRow` | `allocValid && allocReady` | Payload stored for later commit trace emission. |
| input | `completeValid` | `Bool` | valid | Marks a live ROB slot complete. |
| input | `completeRobValue` | `UInt(log2Ceil(entries).W)` | `completeValid` | ROB slot to mark complete. |
| output | `commit.rows` | `Vec(commitWidth, CommitTraceRow)` | row `valid` | Retired rows in head order. Invalid rows are zeroed. |
| output | `commitValidMask` | `UInt(commitWidth.W)` | combinational | One bit per retiring row. |
| output | `commitCount` | `UInt` | combinational | Number of rows retiring this cycle. |
| output | `commitMonitorValidMask` | `UInt(commitWidth.W)` | combinational | Monitor-derived valid mask over the exported `commit.rows`. |
| output | `commitMonitorValidCount` | `UInt` | combinational | Monitor-derived number of valid exported rows. |
| output | `commitSkippedSlot` | `Bool` | combinational | True when the exported fixed-width window has a valid row after an invalid older slot. |
| output | `commitDuplicateIdentity` | `Bool` | combinational | True when two exported valid rows share `identity.(bid,gid,rid)`. |
| output | `commitSlotMismatch` | `Bool` | combinational | True when an exported valid row's `slot` field does not match its vector position. |
| output | `commitInvalidSideEffect` | `Bool` | combinational | True when an exported invalid slot carries active side-effect envelopes. |
| output | `commitContractError` | `Bool` | combinational | OR of all monitor error flags; Verilator harnesses assert this is false. |
| output | `empty` | `Bool` | combinational | No live entries. |
| output | `full` | `Bool` | combinational | All entries live. |
| output | `size` | `UInt` | combinational | Number of live entries. |
| output | `headValid` | `Bool` | combinational | The head slot contains a live entry. |
| output | `headComplete` | `Bool` | combinational | The head slot is live and complete. |
| output | `headRobValue` | `UInt` | combinational | Current head slot index. |

## State

- `table`: `entries` stored `CommitTraceRow` payloads.
- `valid`: live bit per entry.
- `complete`: completion bit per entry.
- `headValue/headWrap`: circular commit pointer.
- `tailValue/tailWrap`: circular allocation pointer.
- `size`: live entry count.

On allocation, the module overrides `row.valid`, `row.rob.valid`,
`row.rob.wrap`, and `row.rob.value` so the emitted trace carries the actual
reduced ROB slot identity.

## Logic Design

Allocation scans live entries for duplicate `CommitInfo` identity. A duplicate
or full ROB deasserts `allocReady`.

Commit selection starts at `headValue`. Slot 0 may retire only when the head is
live and complete. Slot N may retire only when every prior slot retires and the
next circular entry is live and complete. This preserves the LinxCoreModel
`SPEROB::commit` behavior that breaks at the first incomplete head.

The module emits rows through `CommitTracePort` without compaction beyond the
natural head-ordered commit slots. Invalid output slots are zeroed and have
`valid=false`.

The exported commit port is also checked by an embedded `CommitTraceMonitor`.
The monitor does not affect retirement; it exposes structural contract flags for
the same window visible on `commit.rows`.

## Timing

Completion is registered and is not visible to commit selection until the next
cycle. Commit selection observes current state, then retiring entries clear and
the head pointer advances by `commitCount`.

## Flush/Recovery

Full flush and pointer rebasing are deferred. `ReducedCommitROB` is intentionally
used only for reduced commit ordering and trace schema proof. Phase 5 integrated
ROB/recovery must add checkpoint-aware flush, precise trap ownership, dealloc,
rename cleanup, and LSU/STQ side effects.

## Trace/Observability

Retiring rows use the same `CommitTraceRow` schema consumed by
`tools/chisel/trace_schema_adapter.py`. The row keeps `identity.(bid,gid,rid)`
for LinxCoreModel identity and `blockBid` for the 64-bit hardware block sideband.

`tools/chisel/run_chisel_reduced_rob_xcheck.sh` emits this module to
SystemVerilog, builds a Verilator harness, drives deterministic alloc/complete
traffic, writes nested Chisel JSONL, and compares it against a matching
QEMU-shaped reference trace through the neutral adapter. The harness asserts
`commitContractError == false` for the two-row retire window, the final
single-row retire window, and the post-drain idle window. The smoke deliberately
writes one invalid fixed-width output slot; the adapter must filter it before
comparison.

The same C++ harness also has an `--input-trace` mode used by
`run_chisel_trace_replay_xcheck.sh`. In that mode it loads bounded normalized
commit rows, allocates and completes one row at a time through the top shell,
writes DUT JSONL from the Chisel commit port, and writes a matching
QEMU-shaped reference stream for the comparator. This is cross-check
infrastructure; it does not replace the future live ROB/CMT execution path.

## Verification

- `bash tools/chisel/run_chisel_tests.sh --only ReducedCommitROB`
- `bash tools/chisel/run_chisel_rob_bookkeeping.sh --reduced-rob`
- `bash tools/chisel/run_chisel_reduced_rob_xcheck.sh`
- `bash tools/chisel/run_chisel_trace_replay_xcheck.sh`
- `python3 tools/chisel/trace_schema_adapter.py --self-test`

Current tests cover contiguous retirement, incomplete-head blocking, duplicate
identity rejection, independent `blockBid` preservation, Chisel elaboration, and
Verilator-driven trace comparison for a 3-row retire window with monitor-backed
contract assertions.
