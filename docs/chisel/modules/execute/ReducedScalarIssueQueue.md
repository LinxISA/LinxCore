# ReducedScalarIssueQueue

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/execute/ReducedScalarIssueQueue.scala`
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
source readiness for the queue head, and issues only when every valid source
lane is ready and execute can accept a row.

This is a reduced FIFO issue queue for the RF-backed ALU trace top. It is not
the full model issue scheduler: it does not age-select among many ready rows,
track P1/I1/I2 in-flight rows, arbitrate issue ports, apply cancellation, or
replay cancelled rows. It now preserves the model split between issue select
and issue-queue release: issue marks the head as in flight, and a later ALU
release removes the issued row.

R85 moves source readiness into registered per-entry state. The queue still
selects only the FIFO head, but RF ready-table changes update resident entries
on the next clock edge rather than feeding directly into same-cycle issue
selection.

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
| output | `readValid` | `Vec(3, Bool)` | combinational | Valid source lanes for the queue head. |
| output | `readTags` | `Vec(3, UInt(physRegWidth.W))` | combinational | Physical source tags for the queue head. |
| input | `readReady` | `Vec(3, Bool)` | combinational | Physical RF readiness for the requested head source tags, retained as RF-read observability while issue eligibility comes from registered source-ready state. |
| input | `readData` | `Vec(3, UInt(immWidth.W))` | combinational | Physical RF data for the requested head source tags. |
| output | `issueValid` | `Bool` | valid | Queue head is present, not already issued, and all valid source lanes are ready. |
| input | `issueReady` | `Bool` | ready | Downstream execute can accept the queue head. |
| output | `issueUop` | `RenamedUop` | with `issueValid` | Queue-head renamed row for execute. |
| output | `issueSrcData` | `Vec(3, UInt(immWidth.W))` | with `issueValid` | RF-sourced operand data for execute capture. |
| output | `enqueueFire` | `Bool` | pulse | Row accepted into the queue. |
| output | `issueFire` | `Bool` | pulse | Queue head accepted by execute and marked issued. |
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
| output | `blockedBySource` | `Bool` | diagnostic | Head row is waiting on at least one valid source lane. |
| output | `blockedByOutput` | `Bool` | diagnostic | Head row is ready but execute is backpressuring. |
| output | `blockedByIssued` | `Bool` | diagnostic | Head row has issued and is blocking younger FIFO rows until release. |

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

Only the head row queries the RF. For each lane, the queue emits the physical
tag and valid bit from `RenamedUop.src(idx)` for data readout. A source lane is
considered ready for issue when the queue is empty, the head has already
issued, or the lane's registered `srcReady` bit is set. Invalid source lanes
are initialized ready. `issueValid` asserts when the queue has a non-issued
head and all valid head sources are ready. `issueFire` marks that head issued
when execute accepts it.

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
FIFO order. This keeps the reduced owner intentionally conservative: younger
ready rows still do not bypass an issued head, but the row lifetime now follows
the C++ model's select/release split instead of deallocating on execute
acceptance.

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
4. a blocked head cannot issue until valid source tags become ready;
5. issue preserves the row's ROB identity and renamed sidecars into execute.

The deliberate reduction is selection policy and pipeline timing. The full
model can select among ready rows by age, tracks pipe stages, and can cancel or
replay issued entries. This reduced owner only selects the FIFO head and blocks
behind an issued head until the ALU W2 release identity arrives.

R85 removes the earlier same-cycle RF-readiness-to-issue shortcut. This matches
the model rule that wakeup at cycle N is visible to pick no earlier than cycle
N+1, while still deferring full wakeup ports, P1/I1/I2 read-port arbitration,
age selection, cancel, replay, and bypass.

## Timing

The queue is combinational on head read and release match and registered on
enqueue, source-ready update, issue, release, and flush. RF ready-table updates
are sampled into `srcReady`; they do not directly affect the current cycle's
`issueValid`.

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
source-block, issued-block, and output-block diagnostics. The Verilator
fixture drives three dependent scalar rows, enqueues all rows before draining
commits, and compares the resulting commit rows against QEMU-shaped reference
data.

## Verification

- `bash tools/chisel/run_chisel_tests.sh --only ReducedScalarIssueQueue`
- `bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendRfAluTraceTop`
- `bash tools/chisel/run_chisel_frontend_rf_alu_trace_top_xcheck.sh`
- `bash tools/chisel/run_chisel_frontend_alu_trace_top_xcheck.sh`
- `python3 tools/chisel/trace_schema_adapter.py --self-test`
- `bash tools/chisel/run_chisel_qemu_crosscheck.sh --dry-run`
- `bash tools/chisel/build_chisel.sh`
- `bash tools/chisel/run_chisel_verilator_lint.sh`
