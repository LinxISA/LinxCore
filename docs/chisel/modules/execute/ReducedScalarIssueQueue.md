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

R88 turns that child into a reduced P1/I1/I2 issue owner. The queue now locks
the selected row at P1 `pickFire`, clears the lock on I1 `cancelFire`, and
keeps the row resident until the existing ALU release path removes it.

## Interface

| Direction | Signal | Type | Valid/ready | Description |
|---|---|---|---|---|
| input | `inValid` | `Bool` | valid | Rename output row is available for enqueue. |
| output | `inReady` | `Bool` | ready | Queue can accept the row, including one or two same-cycle releases from a full queue. |
| input | `in` | `RenamedUop` | with `inValid` | Renamed scalar row, physical sources, destination physical tag, ROB identity, and decoded sidecars. |
| input | `flushValid` | `Bool` | valid | Clears the reduced queue. |
| input | `releaseValid` | `Bool` | valid | ALU pipe has reached the reduced release point for an issued row. |
| input | `releaseBid` | `ROBID` | with `releaseValid` | Block identity of the issued row to remove. |
| input | `releaseRid` | `ROBID` | with `releaseValid` | ROB identity of the issued row to remove. |
| input | `releaseStid` | `UInt(threadIdWidth.W)` | with `releaseValid` | STID of the issued row to remove. |
| input | `secondaryReleaseValid` | `Bool` | valid | Independent second release lane, used when a live E1 load transfers ownership to LIQ while another row reaches W2. |
| input | `secondaryReleaseBid`, `secondaryReleaseRid`, `secondaryReleaseStid` | mixed | with `secondaryReleaseValid` | Issued-row identity to remove on the independent second release lane. |
| input | `readyMask` | `UInt(physRegCount.W)` | combinational | Reduced scalar RF ready-table snapshot used to initialize and update resident source-ready bits. |
| input | `localTReadyMask`, `localUReadyMask` | `UInt(4.W)` | combinational | Reduced local T/U queue readiness snapshots for source aliases in the live CoreMark prefix. |
| output | `readValid` | `Vec(3, Bool)` | combinational | Valid source lanes for the selected ready row. |
| output | `readTags` | `Vec(3, UInt(physRegWidth.W))` | combinational | Physical source tags for the selected ready row. |
| output | `readOperandClass` | `Vec(3, OperandClass)` | combinational | Source operand class for each selected read lane so the top can choose scalar RF versus local T/U data. |
| output | `readRelTag` | `Vec(3, UInt(archRegWidth.W))` | combinational | Frontend relative T/U source tag for local queue lookup. |
| input | `readReady` | `Vec(3, Bool)` | combinational | Physical RF readiness for the requested source tags, retained as RF-read observability while issue eligibility comes from registered source-ready state. |
| input | `readData` | `Vec(3, UInt(immWidth.W))` | combinational | Physical RF data for the selected ready row. |
| output | `issueValid` | `Bool` | valid | At least one resident non-issued row has all valid sources ready. |
| input | `issueReady` | `Bool` | ready | Downstream execute can accept the selected row. |
| output | `issueUop` | `RenamedUop` | with `issueValid` | Oldest ready non-issued row for execute. |
| output | `issueSrcData` | `Vec(3, UInt(immWidth.W))` | with `issueValid` | RF-sourced operand data for execute capture. |
| output | `enqueueFire` | `Bool` | pulse | Row accepted into the queue. |
| output | `pickFire` | `Bool` | pulse | P1 selected a resident row and the queue locked it in-flight. |
| output | `issueFire` | `Bool` | pulse | I2 row accepted by execute. This does not deallocate queue residency. |
| output | `cancelFire` | `Bool` | pulse | I1 read readiness failed and the selected row's in-flight lock was cleared. |
| output | `releaseFire` | `Bool` | pulse | At least one issued row matched a release identity and was removed. Two distinct rows may retire in one cycle. |
| output | `enqueueDstValid` | `Bool` | pulse | Enqueued row allocated a scalar GPR destination physical tag. T/U destinations do not clear scalar RF readiness. |
| output | `enqueueDstTag` | `UInt(physRegWidth.W)` | with `enqueueDstValid` | Destination physical tag to mark not-ready in the RF. |
| output | `empty`, `full` | `Bool` | diagnostic | Occupancy status. |
| output | `count` | `UInt(log2Ceil(depth + 1).W)` | diagnostic | Queue occupancy. |
| output | `issuedCount`, `notIssuedCount` | `UInt(log2Ceil(depth + 1).W)` | diagnostic | Current in-flight and selectable resident-row counts. |
| output | `headValid` | `Bool` | diagnostic | Queue has a head row. |
| output | `headIssued` | `Bool` | diagnostic | Queue head has already issued and is waiting for release. |
| output | `headPc`, `headOpcode`, `headSrcValidMask`, `headSrcOperandClass`, `headSrcPhysTag`, `headSrcRelTag` | mixed | diagnostic | Head-row payload and source-lane shape used by live CoreMark stall diagnostics. |
| output | `sourceReadyMask` | `UInt(3.W)` | diagnostic | Registered readiness bits for the current head, with invalid lanes treated as ready. |
| output | `allSourcesReady` | `Bool` | diagnostic | Every valid source lane for the head is ready; invalid lanes are ready by definition. |
| output | `selectedValid` | `Bool` | diagnostic | An oldest-ready non-issued row was found for issue. |
| output | `selectedIndex` | `UInt(log2Ceil(depth).W)` | with `selectedValid` | Queue slot selected for RF read and execute issue. |
| output | `selectedReadReady` | `Bool` | diagnostic | Selected row's valid RF read lanes are all ready. |
| output | `i1Valid`, `i2Valid`, `stageBusy` | `Bool` | diagnostic | Reduced issue-owner P1/I1/I2 stage occupancy from `ReducedScalarIssuePick`. |
| output | `blockedBySource` | `Bool` | diagnostic | Queue contains non-issued work, but no resident row is currently source-ready. |
| output | `blockedByRead` | `Bool` | diagnostic | A resident row was selected, but RF read readiness was not confirmed. |
| output | `blockedByOutput` | `Bool` | diagnostic | Selected row is ready but execute is backpressuring. |
| output | `blockedByIssued` | `Bool` | diagnostic | Head row has issued and no younger non-issued row is currently selectable. |

## State

- `entries[depth]`: packed FIFO storage for renamed rows.
- `valid[depth]`: per-entry validity.
- `issued[depth]`: reduced in-flight lock. P1 pick sets it, I1 cancel clears
  it, and ALU release removes the row. The name stays `issued` to preserve the
  existing issue/release accounting surface until a fuller issue-state type is
  introduced.
- `srcReady[depth][3]`: registered source-ready state initialized at enqueue
  from `readyMask` and updated once per cycle while the row is resident.
- `count`: queue occupancy.

The depth must be a power of two and greater than one.

## Logic Design

The queue accepts one row when `inValid && inReady`. `inReady` is high when the
queue is not full or when an issued row releases in the same cycle, so a full
queue can sustain one release plus one enqueue.

On enqueue, `enqueueDstValid` is asserted only for `DestinationKind.Gpr`.
`ScalarTURenameBridge` may overlay a T/U destination onto the `RenamedUop` for
model-local register ownership, but that destination must not invalidate a
scalar physical RF entry in this reduced queue/RF composition.

The queue builds a selectable mask over resident rows. A row is selectable when
it is valid, not already in flight, and every valid source lane's registered
`srcReady` bit is set. Invalid source lanes are initialized ready. The
selectable mask and resident payload image feed `ReducedScalarIssuePick`, which
chooses the oldest selectable row by queue slot, locks that row at P1
`pickFire`, drives RF read request tags from I1, cancels the lock if I1 RF
readiness is not confirmed, and presents a read-confirmed row to execute from
I2.

`readyMask` snapshots the reduced scalar RF ready table. On enqueue, each
scalar P source lane captures `!src.valid || readyMask(src.physTag)`. T and U
source lanes instead sample `localTReadyMask(src.relTag(1,0))` or
`localUReadyMask(src.relTag(1,0))`. While resident, each lane ORs in the same
class-specific readiness predicate on the next clock edge. This is the reduced
equivalent of model issue-queue wakeup: a ready-table bit observed in cycle N
cannot feed selection in cycle N, but it can make the dependent row selectable
after the issue queue samples that predicate. In this reduced top, an ALU
writeback that first updates the RF ready table or local T/U overlay can
therefore include the register/local-value boundary before the issue queue
observes it. The RF `readReady` response remains wired for observability and
read-port contract continuity for scalar P sources, not as the direct issue
predicate.

R141 adds one top-specific scalar exception: architectural `x1/sp` sources are
considered ready by the queue because `LinxCoreFrontendFetchRfAluTraceTop`
feeds their data from the reduced `scalarSpValue` shadow rather than from the
ordinary scalar RF readiness table. This prevents model-sized 128-entry
renaming from stalling when `sp` has been renamed to a non-identity physical
tag but the reduced top still owns SP as macro-control state.

R111 also exposes each selected source lane's `operandClass` and `relTag`.
`LinxCoreFrontendFetchRfAluTraceTop` uses those sidebands to suppress scalar RF
reads for local T/U sources and to feed operand data from the reduced local
T/U overlay. This is a temporary value path for the live CoreMark prefix until
full local-bank data execution replaces it.

R127 adds head-row diagnostics for PC, opcode, source-valid mask, operand
classes, physical tags, and relative tags. These are diagnostic-only outputs
used by the live QEMU harness to distinguish true local-source waits from
stale local-overlay backpressure.

Each release lane removes at most one in-flight row whose `(bid, rid, stid)`
matches its release payload. The primary lane has priority if both lanes name
the same row; otherwise two distinct issued rows may be removed in one cycle.
Remaining rows are compacted toward slot 0, preserving FIFO order. In-flight
rows are ineligible for another pick, but they do not force the queue back to
head-only issue: a younger ready non-issued row can be picked while an older
in-flight row waits for W2 release. This follows the C++ model's
select/release split without deallocating on P1 pick or execute acceptance.

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
6. T/U local sources use local queue readiness and relative tags rather than
   scalar RF physical tags.

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

R88 makes that split stateful. `IssueState::Select` marks the selected entry
issued before the pipe moves it through `p1_inst`, `i1_inst`, and `i2_inst`.
`ALUPipe::runI1` generates RF read requests, `runI2` consumes RF return, and
`ALUPipe::move` queues scalar ALU IQ release only when the I2 row enters
execution. The reduced Chisel queue therefore locks on P1, may cancel the lock
from I1 read readiness failure, emits execute valid from I2, and removes the
row only on the existing ALU release identity.

## Timing

The queue is combinational on selectable-mask generation and release match,
and registered on enqueue, source-ready update, P1 lock, I1 cancel, release,
and flush. RF ready-table updates are sampled into `srcReady`; they do not
directly affect the current cycle's selection. Selected-row RF `readReady` is
now consumed in I1: a failed read readiness pulse clears only the in-flight
lock and leaves the row resident.

Future full issue work must split explicit wakeup payloads, select,
issued/in-flight tracking, and release into model-aligned stages. In
particular, wakeup should not let a newly woken row win selection in the same
cycle unless the full model evidence proves that timing.

## Flush/Recovery

`flushValid` clears the queue pointers, validity, and count. It does not
restore RF readiness, rename maps, ROB rows, or speculative checkpoints; those
remain owned by existing recovery and future issue/ready-table packets.

## Trace/Observability

The parent `LinxCoreFrontendRfAluTraceTop` exposes enqueue, P1 pick, I1 cancel,
I2 issue, release, count, issued/not-issued count, head-valid, head-issued,
head payload/source-lane shape, source-ready mask, selected-valid/index,
selected-read-ready, I1/I2 stage valids, source-block, read-block,
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
- `bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r111-coremark-sll-tu-qemu-elf-xcheck --elf tests/benchmarks/build/coremark_real.elf --expected-rows 0 --capture-rows 14 --allow-block-markers --max-seconds 8 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf`
- `python3 tools/chisel/trace_schema_adapter.py --self-test`
- `bash tools/chisel/run_chisel_qemu_crosscheck.sh --dry-run`
- `bash tools/chisel/build_chisel.sh`
- `bash tools/chisel/run_chisel_verilator_lint.sh`
