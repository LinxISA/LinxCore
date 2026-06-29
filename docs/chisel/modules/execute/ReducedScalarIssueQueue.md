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
hold entries until model-style release.

## Interface

| Direction | Signal | Type | Valid/ready | Description |
|---|---|---|---|---|
| input | `inValid` | `Bool` | valid | Rename output row is available for enqueue. |
| output | `inReady` | `Bool` | ready | Queue can accept the row, including same-cycle issue from a full queue. |
| input | `in` | `RenamedUop` | with `inValid` | Renamed scalar row, physical sources, destination physical tag, ROB identity, and decoded sidecars. |
| input | `flushValid` | `Bool` | valid | Clears the reduced queue. |
| output | `readValid` | `Vec(3, Bool)` | combinational | Valid source lanes for the queue head. |
| output | `readTags` | `Vec(3, UInt(physRegWidth.W))` | combinational | Physical source tags for the queue head. |
| input | `readReady` | `Vec(3, Bool)` | combinational | Physical RF readiness for the requested head source tags. |
| input | `readData` | `Vec(3, UInt(immWidth.W))` | combinational | Physical RF data for the requested head source tags. |
| output | `issueValid` | `Bool` | valid | Queue head is present and all valid source lanes are ready. |
| input | `issueReady` | `Bool` | ready | Downstream execute can accept the queue head. |
| output | `issueUop` | `RenamedUop` | with `issueValid` | Queue-head renamed row for execute. |
| output | `issueSrcData` | `Vec(3, UInt(immWidth.W))` | with `issueValid` | RF-sourced operand data for execute capture. |
| output | `enqueueFire` | `Bool` | pulse | Row accepted into the queue. |
| output | `issueFire` | `Bool` | pulse | Queue head accepted by execute. |
| output | `enqueueDstValid` | `Bool` | pulse | Enqueued row allocated a scalar destination physical tag. |
| output | `enqueueDstTag` | `UInt(physRegWidth.W)` | with `enqueueDstValid` | Destination physical tag to mark not-ready in the RF. |
| output | `empty`, `full` | `Bool` | diagnostic | Occupancy status. |
| output | `count` | `UInt(log2Ceil(depth + 1).W)` | diagnostic | Queue occupancy. |
| output | `headValid` | `Bool` | diagnostic | Queue has a head row. |
| output | `allSourcesReady` | `Bool` | diagnostic | Every valid source lane for the head is ready; invalid lanes are ready by definition. |
| output | `blockedBySource` | `Bool` | diagnostic | Head row is waiting on at least one valid source lane. |
| output | `blockedByOutput` | `Bool` | diagnostic | Head row is ready but execute is backpressuring. |

## State

- `entries[depth]`: FIFO storage for renamed rows.
- `valid[depth]`: per-entry validity. Occupancy is still governed by `count`;
  the valid vector is kept as explicit row state for future release/flush work.
- `head`, `tail`: circular FIFO pointers.
- `count`: queue occupancy.

The depth must be a power of two and greater than one.

## Logic Design

The queue accepts one row when `inValid && inReady`. `inReady` is high when the
queue is not full or when the current head also issues in the same cycle, so a
full queue can sustain one dequeue plus one enqueue.

Only the head row queries the RF. For each lane, the queue emits the physical
tag and valid bit from `RenamedUop.src(idx)`. A source lane is considered ready
when the queue is empty, the lane is invalid, or the RF reports `readReady`.
`issueValid` asserts when the queue has a head and all valid head sources are
ready. `issueFire` dequeues the head only when execute accepts it.

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

The deliberate reduction is release timing. The full model keeps issued rows
valid until a later release stage. This FIFO owner deallocates the head on
execute acceptance because `ReducedScalarAluExecute` is still the only
downstream in-flight owner in the trace top.

## Timing

The queue is combinational on head read and registered on enqueue, issue, and
flush. RF readiness can wake a head row for issue in the same cycle that the RF
reports the source ready. This is acceptable for the reduced top because there
is no separate model P1/I1/I2 issue timing yet.

Future full issue work must split wakeup, select, issued/in-flight tracking,
and release into model-aligned stages. In particular, wakeup should not let a
newly woken row win selection in the same cycle unless the full model evidence
proves that timing.

## Flush/Recovery

`flushValid` clears the queue pointers, validity, and count. It does not
restore RF readiness, rename maps, ROB rows, or speculative checkpoints; those
remain owned by existing recovery and future issue/ready-table packets.

## Trace/Observability

The parent `LinxCoreFrontendRfAluTraceTop` exposes enqueue, issue, count,
head-valid, source-block, and output-block diagnostics. The Verilator fixture
drives three dependent scalar rows, enqueues all rows before draining commits,
and compares the resulting commit rows against QEMU-shaped reference data.

## Verification

- `bash tools/chisel/run_chisel_tests.sh --only ReducedScalarIssueQueue`
- `bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendRfAluTraceTop`
- `bash tools/chisel/run_chisel_frontend_rf_alu_trace_top_xcheck.sh`
- `bash tools/chisel/run_chisel_frontend_alu_trace_top_xcheck.sh`
- `python3 tools/chisel/trace_schema_adapter.py --self-test`
- `bash tools/chisel/run_chisel_qemu_crosscheck.sh --dry-run`
- `bash tools/chisel/build_chisel.sh`
- `bash tools/chisel/run_chisel_verilator_lint.sh`
