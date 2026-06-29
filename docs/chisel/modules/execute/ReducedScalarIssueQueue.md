# ReducedScalarIssueQueue

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/execute/ReducedScalarIssueQueue.scala`
- Child picker: `rtl/LinxCore/chisel/src/main/scala/linxcore/execute/ReducedScalarIssuePick.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/execute/ReducedScalarIssueQueueSpec.scala`
- Parent top: `rtl/LinxCore/chisel/src/main/scala/linxcore/top/LinxCoreFrontendRfAluTraceTop.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/iex/iex_iq.cpp`
  - `model/LinxCoreModel/model/iex/iex_iq.h`
  - `model/LinxCoreModel/model/iex/iex_dispatch.cpp`
  - `model/LinxCoreModel/model/iex/iex.cpp`
  - `model/LinxCoreModel/model/iex/pipe/alu_pipe.cpp`
- Contract IDs: `LC-IF-CHISEL-IEX-IQ-001`, `LC-IF-CHISEL-XCHK-008`

## Purpose

`ReducedScalarIssueQueue` is the first Chisel issue boundary for the reduced
scalar ALU lane. It decouples `DecodeRenameROBPath` from
`ReducedScalarAluExecute`, stores renamed scalar rows, queries physical RF
source data for the selected ready row, and issues only when every valid source
lane for the selected row is ready and execute can accept a row.

This is a reduced FIFO issue queue for the RF-backed ALU trace top. It is not
the full model issue scheduler: it does not track P1/I1/I2 in-flight rows,
arbitrate RF read ports, apply cancellation, bypass, or replay cancelled rows.
It now preserves the model split between issue select and issue-queue release:
issue marks the selected row as in flight, and a later ALU release removes the
issued row.

R85 moves source readiness into registered per-entry state. The queue still
selects only the FIFO head, but RF ready-table changes update resident entries
on the next clock edge rather than feeding directly into same-cycle issue
selection.

R86 changes reduced selection from head-only to oldest-ready among resident
non-issued entries. Issued in-flight entries remain resident and ineligible,
and their age position is preserved until release compacts the queue.

R87 splits the selected-row pick/read/confirm logic into
`ReducedScalarIssuePick`. The queue remains the residency, source-readiness,
release, and compaction owner, while the child picker owns oldest-ready
selected-row RF read requests, read-confirm gating, issue fire, and block
diagnostics.

## Interface

| Direction | Signal | Type | Valid/ready | Description |
|---|---|---|---|---|
| input | `inValid` | `Bool` | valid | Rename output row is available for enqueue. |
| output | `inReady` | `Bool` | ready | Queue can accept the row, including same-cycle release from a full queue. |
| input | `in` | `RenamedUop` | with `inValid` | Renamed scalar row, physical sources, destination physical tag, ROB identity, and decoded sidecars. |
| input | `flushValid` | `Bool` | valid | Clears the reduced queue. |
| input | `releaseValid` | `Bool` | valid | ALU pipe has reached the reduced release point for an issued row. |
| input | `releaseBid` | `ROBID` | with `releaseValid` | Block identity of the issued row to remove. |
| input | `releaseRid` | `ROBID` | with `releaseValid` | ROB identity of the issued row to remove. |
| input | `releaseStid` | `UInt(threadIdWidth.W)` | with `releaseValid` | STID of the issued row to remove. |
| input | `readyMask` | `UInt(physRegCount.W)` | combinational | Reduced scalar RF ready-table snapshot used to initialize and update resident source-ready bits. |
| output | `readValid` | `Vec(3, Bool)` | combinational | Valid source lanes for the selected ready row. |
| output | `readTags` | `Vec(3, UInt(physRegWidth.W))` | combinational | Physical source tags for the selected ready row. |
| input | `readReady` | `Vec(3, Bool)` | combinational | Physical RF readiness for the requested source tags, retained as RF-read observability while issue eligibility comes from registered source-ready state. |
| input | `readData` | `Vec(3, UInt(immWidth.W))` | combinational | Physical RF data for the selected ready row. |
| output | `issueValid` | `Bool` | valid | At least one resident non-issued row has all valid sources ready. |
| input | `issueReady` | `Bool` | ready | Downstream execute can accept the selected row. |
| output | `issueUop` | `RenamedUop` | with `issueValid` | Oldest ready non-issued row for execute. |
| output | `issueSrcData` | `Vec(3, UInt(immWidth.W))` | with `issueValid` | RF-sourced operand data for execute capture. |
| output | `enqueueFire` | `Bool` | pulse | Row accepted into the queue. |
| output | `issueFire` | `Bool` | pulse | Selected row accepted by execute and marked issued. |
| output | `releaseFire` | `Bool` | pulse | One issued row matched the release identity and was removed. |
| output | `enqueueDstValid` | `Bool` | pulse | Enqueued row allocated a scalar destination physical tag. |
| output | `enqueueDstTag` | `UInt(physRegWidth.W)` | with `enqueueDstValid` | Destination physical tag to mark not-ready in the RF. |
| output | `empty`, `full` | `Bool` | diagnostic | Occupancy status. |
| output | `count` | `UInt(log2Ceil(depth + 1).W)` | diagnostic | Queue occupancy. |
| output | `issuedCount`, `notIssuedCount` | `UInt(log2Ceil(depth + 1).W)` | diagnostic | Current in-flight and selectable resident-row counts. |
| output | `headValid` | `Bool` | diagnostic | Queue has a head row. |
| output | `headIssued` | `Bool` | diagnostic | Queue head has already issued and is waiting for release. |
| output | `sourceReadyMask` | `UInt(3.W)` | diagnostic | Registered readiness bits for the current head, with invalid lanes treated as ready. |
| output | `allSourcesReady` | `Bool` | diagnostic | Every valid source lane for the head is ready; invalid lanes are ready by definition. |
| output | `selectedValid` | `Bool` | diagnostic | An oldest-ready non-issued row was found for issue. |
| output | `selectedIndex` | `UInt(log2Ceil(depth).W)` | with `selectedValid` | Queue slot selected for RF read and execute issue. |
| output | `selectedReadReady` | `Bool` | diagnostic | Selected row's valid RF read lanes are all ready. |
| output | `blockedBySource` | `Bool` | diagnostic | Queue contains non-issued work, but no resident row is currently source-ready. |
| output | `blockedByRead` | `Bool` | diagnostic | A resident row was selected, but RF read readiness was not confirmed. |
| output | `blockedByOutput` | `Bool` | diagnostic | Selected row is ready but execute is backpressuring. |
| output | `blockedByIssued` | `Bool` | diagnostic | Head row has issued and no younger non-issued row is currently selectable. |

## State

- `entries[depth]`: packed FIFO storage for renamed rows.
- `valid[depth]`: per-entry validity.
- `issued[depth]`: per-entry in-flight state after execute accepts the row.
- `srcReady[depth][3]`: registered source-ready state initialized at enqueue
  from `readyMask` and updated once per cycle while the row is resident.
- `count`: queue occupancy.

The depth must be a power of two and greater than one.

## Logic Design

The queue accepts one row when `inValid && inReady`. `inReady` is high when the
queue is not full or when an issued row releases in the same cycle, so a full
queue can sustain one release plus one enqueue.

The queue builds a selectable mask over resident rows. A row is selectable when
it is valid, not already issued, and every valid source lane's registered
`srcReady` bit is set. Invalid source lanes are initialized ready. The
selectable mask and resident payload image feed `ReducedScalarIssuePick`, which
chooses the oldest selectable row by queue slot, drives the RF read request,
checks selected-row RF read readiness, and returns `issueFire`. `issueFire`
marks that selected row issued when execute accepts it; it does not remove the
row.

`readyMask` snapshots the reduced scalar RF ready table. On enqueue, each
source lane captures `!src.valid || readyMask(src.physTag)`. While resident,
each lane ORs in the same ready-mask predicate on the next clock edge. This is
the reduced equivalent of model issue-queue wakeup: a ready-table bit observed
in cycle N cannot feed selection in cycle N, but it can make the dependent row
selectable after the issue queue samples that predicate. In this reduced top,
an ALU writeback that first updates the RF ready table can therefore include
the RF register boundary before the issue queue observes it. The RF `readReady`
response remains wired for observability and read-port contract continuity, not
as the direct issue predicate.

`releaseValid` removes the first issued row whose `(bid, rid, stid)` matches
the release payload. Remaining rows are compacted toward slot 0, preserving
FIFO order. Issued rows are ineligible for another pick, but they do not force
the queue back to head-only issue: a younger ready non-issued row can issue
while an older issued row waits for W2 release. This follows the C++ model's
select/release split without deallocating on execute acceptance.

On enqueue, `enqueueDstValid/enqueueDstTag` publish the allocated destination
physical tag so the RF can mark that tag not-ready at queue admission. That
matches the reduced dataflow: later rows may enqueue behind a producer and then
wait at the queue head until the producer's W2 writeback marks the tag ready.

## Model Alignment

The C++ model's `IssueState::insert` stores renamed instructions in an issue
queue, `IssueState::Wakeup` marks matching physical sources ready, and
`IssueState::Select` picks a ready row for issue while respecting model
restrictions. `IssueState::ReleaseEntry` removes an already issued row when the
model receives the later release event.

The reduced Chisel owner preserves the minimum contract needed by the current
RF-backed scalar ALU lane:

1. renamed rows are stored before execute;
2. source readiness is checked against physical RF state, not per-uop top
   fixtures;
3. dependent rows can enqueue before their producer commits;
4. a ready younger row can issue when older resident rows are not selectable;
5. issue preserves the row's ROB identity and renamed sidecars into execute.

The deliberate reduction is pipeline depth and cancellation behavior. The full
model tracks pipe stages and can cancel or replay issued entries. This reduced
owner selects the oldest ready resident row by FIFO slot and now checks
selected-row RF read readiness before issue, but still lacks the full model's
alternate global-register preference, read-port arbitration, cancel checks,
replay, and bypass.

R85 removes the earlier same-cycle RF-readiness-to-issue shortcut. This matches
the model rule that wakeup at cycle N is visible to pick no earlier than cycle
N+1, while still deferring full wakeup ports, P1/I1/I2 read-port arbitration,
age selection, cancel, replay, and bypass until later packets.

R86 implements the first age-selection slice: `IssueState::Select` scans
resident entries, ignores issued entries, and commits the oldest ready
candidate. Chisel preserves that ordering over the compacted queue image.

R87 introduces an explicit issue-owner child for the reduced P1/I1/I2 split:
queue state computes source-ready candidates, `ReducedScalarIssuePick` owns
pick/read/confirm, and execute/release remains in the ALU owner.

## Timing

The queue is combinational on selectable-mask generation, selected-row RF read,
and release match, and registered on enqueue, source-ready update, issue,
release, and flush. RF ready-table updates are sampled into `srcReady`; they do
not directly affect the current cycle's selection. Selected-row RF `readReady`
can suppress issue fire for that cycle without changing queue residency.

Future full issue work must split explicit wakeup payloads, select,
issued/in-flight tracking, and release into model-aligned stages. In
particular, wakeup should not let a newly woken row win selection in the same
cycle unless the full model evidence proves that timing.

## Flush/Recovery

`flushValid` clears the queue pointers, validity, and count. It does not
restore RF readiness, rename maps, ROB rows, or speculative checkpoints; those
remain owned by existing recovery and future issue/ready-table packets.

## Trace/Observability

The parent `LinxCoreFrontendRfAluTraceTop` exposes enqueue, issue, release,
count, issued/not-issued count, head-valid, head-issued, source-ready mask,
selected-valid/index, selected-read-ready, source-block, read-block,
issued-block, and output-block diagnostics.
The Verilator fixture drives three dependent scalar rows, enqueues all rows
before draining commits, and compares the resulting commit rows against
QEMU-shaped reference data.

## Verification

- `bash tools/chisel/run_chisel_tests.sh --only ReducedScalarIssueQueue`
- `bash tools/chisel/run_chisel_tests.sh --only ReducedScalarIssuePick`
- `bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendRfAluTraceTop`
- `bash tools/chisel/run_chisel_frontend_rf_alu_trace_top_xcheck.sh`
- `bash tools/chisel/run_chisel_frontend_alu_trace_top_xcheck.sh`
- `python3 tools/chisel/trace_schema_adapter.py --self-test`
- `bash tools/chisel/run_chisel_qemu_crosscheck.sh --dry-run`
- `bash tools/chisel/build_chisel.sh`
- `bash tools/chisel/run_chisel_verilator_lint.sh`
