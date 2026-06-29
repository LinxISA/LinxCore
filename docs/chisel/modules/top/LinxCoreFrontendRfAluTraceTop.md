# LinxCoreFrontendRfAluTraceTop

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/top/LinxCoreFrontendRfAluTraceTop.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/top/LinxCoreFrontendRfAluTraceTopSpec.scala`
- Verilator driver: `rtl/LinxCore/tools/chisel/frontend_alu_trace_top_tb.cpp`
- Gate: `rtl/LinxCore/tools/chisel/run_chisel_frontend_rf_alu_trace_top_xcheck.sh`
- Child owners:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/frontend/F4DecodeWindow.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/backend/DecodeRenameROBPath.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/execute/ReducedScalarRegisterFile.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/execute/ReducedScalarIssueQueue.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/execute/ReducedScalarAluExecute.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/bctrl/spe/GPRRename.cpp`
  - `model/LinxCoreModel/model/iex/rtable.cpp`
  - `model/LinxCoreModel/model/iex/iex_rf.cpp`
  - `model/LinxCoreModel/model/iex/iex_iq.cpp`
  - `model/LinxCoreModel/model/iex/iex_dispatch.cpp`
  - `model/LinxCoreModel/model/iex/pipe/alu_pipe.cpp`
  - `model/LinxCoreModel/model/ModelCommon/SimInstInfo.cpp`
- Contract IDs: `LC-IF-CHISEL-TOP-004`, `LC-IF-CHISEL-XCHK-008`

## Purpose

`LinxCoreFrontendRfAluTraceTop` is the R83 RF-backed successor to
`LinxCoreFrontendAluTraceTop`. It drives raw frontend packets through F4,
decode/rename, ROB allocation/update, a reduced scalar physical RF, a reduced
scalar issue queue, and the reduced scalar ALU execute pipe, then commits
through the monitored ROB path.

The top removes the R81 `operandData` per-uop fixture. Testbench preloads only
architectural identity registers, and later source values must come from
Chisel RF writeback state keyed by renamed physical tags. Unlike R82, rename
output no longer feeds execute directly: rows enqueue into
`ReducedScalarIssueQueue` and issue only when the queue-head RF source tags are
ready.

## Interface

| Direction | Signal | Type | Valid/ready | Description |
|---|---|---|---|---|
| input | `in` | `FrontendDecodePacket` | `in.valid` | Host/testbench supplied frontend window, PC, PE/STID, packet UID, and checkpoint ID. |
| input | `rfInitValid` | `Bool` | pulse | Preload one identity-mapped architectural scalar GPR. |
| input | `rfInitArchTag` | `UInt(archRegWidth.W)` | with `rfInitValid` | Architectural GPR tag for reduced initialization. |
| input | `rfInitData` | `UInt(64.W)` by default | with `rfInitValid` | Initial scalar GPR data. |
| input | `frontendFlushValid` | `Bool` | valid | Flushes F4/decode queue state in this reduced top. |
| input | `deallocReady` | `Bool` | ready | Allows retired rows to deallocate through the integrated ROB/T-U retire path. |
| output | `f4ValidMask`, `f4SlotCount` | mixed | combinational | F4 slot-valid shape for the input window. |
| output | `decodeReady` | `Bool` | ready | Wrapped decode/rename/ROB path can accept the next frontend packet. |
| output | `selectedValid`, `selectedRobValue`, `selectedBlockBid` | mixed | diagnostic | First valid decoded slot and allocator identities. |
| output | `decRenPushFire`, `decRenPopFire`, `decRenCount` | mixed | diagnostic | Decode-to-rename queue events and occupancy. |
| output | `renamedOutValid`, `renamedAccepted` | `Bool` | diagnostic | Rename output visibility from `DecodeRenameROBPath`. |
| output | `executeAccepted`, `executeBusy` | `Bool` | diagnostic | Reduced scalar ALU capture and occupancy. |
| output | `executeCompleteValid`, `executeCompleteRobValue` | mixed | diagnostic | ALU completion pulse and target ROB row. |
| output | `executeUnsupported`, `executeUnsupportedOpcode` | mixed | diagnostic | Reduced ALU unsupported-opcode pulse. |
| output | `rfReadReadyMask`, `rfAllReadReady`, `rfReadyMask` | mixed | diagnostic | Reduced physical RF readiness observability. |
| output | `rfWriteValid`, `rfWriteTag`, `rfWriteData` | mixed | diagnostic | Execute-to-RF physical writeback event. |
| output | `rfStateError` | `Bool` | diagnostic | Out-of-range RF init/clear/write attempt. |
| output | `issueQueueEnqueueFire`, `issueQueueIssueFire` | `Bool` | pulse | Reduced issue queue admission and execute-issue events. |
| output | `issueQueueCount`, `issueQueueHeadValid` | mixed | diagnostic | Reduced issue queue occupancy and head-valid state. |
| output | `issueQueueAllSourcesReady` | `Bool` | diagnostic | Queue-head source readiness after invalid-lane masking. |
| output | `issueQueueBlockedBySource`, `issueQueueBlockedByOutput` | `Bool` | diagnostic | Queue-head blocked by RF readiness or execute backpressure. |
| output | `robAllocFire`, `robRenameUpdateFire` | `Bool` | pulse | BROB/ROB reservation and post-rename row update pulses. |
| output | `completeAccepted`, `completeIgnored` | `Bool` | pulse | ROB completion result from ALU-produced completion. |
| output | `commit.rows` | `Vec(commitWidth, CommitTraceRow)` | row `valid` | Monitored commit rows including RF-sourced source values and ALU writeback data. |
| output | `commit*`, `dealloc*`, `*Mask`, `idle` | mixed | diagnostic | Commit monitor, ROB lifecycle, and occupancy observability inherited from `DecodeRenameROBPath`. |

## State

The wrapper owns no state directly. State lives in child owners:

- `F4DecodeWindow`: combinational frontend-window slicing.
- `DecodeRenameROBPath`: decode-to-rename queue, scalar/T-U rename, BROB/ROB,
  commit, and deallocation.
- `ReducedScalarRegisterFile`: reduced physical scalar GPR data and ready bits.
- `ReducedScalarIssueQueue`: reduced FIFO issue boundary, RF-read query, and
  source readiness gating.
- `ReducedScalarAluExecute`: reduced E/W1/W2 scalar ALU pipe.

## Logic Design

`DecodeRenameROBPath` emits a single accepted `RenamedUop` into
`ReducedScalarIssueQueue`. The issue queue owns the readiness boundary:
`path.io.renamedOutReady` is driven by queue capacity, while the queue head
drives `ReducedScalarRegisterFile.readValid/readTags` and consumes
`readReady/readData`. Invalid source lanes are treated as ready inside the
queue.

When a row enqueues with a scalar destination, the top clears the destination
physical tag readiness in the RF through `issue.enqueueDst*`. When the queue
head has all valid sources ready and execute is ready, `issue.issue*` drives
`ReducedScalarAluExecute`. When execute completes,
`completeDstPhysValid/Tag/Data` write the destination physical tag and mark it
ready for later queue-head rows.

This removes the R82 accepted-output readiness loop. `rfAllReadReady` remains a
top diagnostic, but readiness that affects execution now flows from the RF into
the issue queue head rather than back into rename acceptance.

## Model Alignment

`GPRRename::Build` initializes architectural scalar GPR tags as identity
physical tags and leaves higher physical tags free. `GPRRename::RenameSrc`
maps scalar sources through the speculative map, while `RenameDst` allocates a
new physical destination tag. `ReadyState::GetSrcData` and the RF read path
populate source operand data from the physical tag state, and the ALU pipe
publishes result data at W2.

The RF-backed top mirrors this reduced ordering:

1. preload identity physical tags for architectural fixture inputs;
2. decode/rename maps sources and allocates a physical destination;
3. the renamed row enters a reduced issue queue;
4. the issue queue reads RF readiness/data for the head physical source tags;
5. execute captures source data only after valid head sources are ready;
6. execute completion updates ROB commit payload and writes the destination RF
   tag;
7. later rows can source the just-written physical tag through the speculative
   rename map.

The R83 Verilator fixture proves this with dependent rows:

| Row | Expected data source |
|---|---|
| `ADD r3, r4, r5` | `r4/r5` are RF preloads. |
| `ADDI r6, r3, 0x7ff` | `r3` reads the physical tag written by row 0. |
| `C.MOVR r5, r6` | `r6` reads the physical tag written by row 1. |

## Timing

The top remains serialized and single-issue. Frontend packets can enqueue
renamed rows ahead of execute. The reduced issue queue only considers its head
for RF readiness and issue. The ALU completes from W2, and the ROB commit head
emits the monitored row after completion.

## Flush/Recovery

Only `frontendFlushValid` is live. It clears the frontend/decode path and the
reduced issue queue. Backend cleanup, checkpoint restore, physical-tag
free/restore, full issue replay, precise traps, LSU cleanup, and redirect
restart remain tied off or delegated to existing child owners. This top is
replacement evidence for RF-backed scalar operand sourcing through a reduced
issue boundary, not a full speculative execution path.

## Trace/Observability

`tools/chisel/run_chisel_frontend_rf_alu_trace_top_xcheck.sh` emits the top,
builds the shared Verilator harness with `LINXCORE_RF_ALU_TRACE_TOP`, preloads
`r4=10` and `r5=32`, enqueues the three dependent frontend packets before
draining commits, dumps DUT commit JSONL, and compares three QEMU-shaped rows
with nonzero source, destination, and writeback data.

The old `run_chisel_frontend_alu_trace_top_xcheck.sh` remains the R81
regression for the temporary operand fixture top.

## Verification

- `bash tools/chisel/run_chisel_tests.sh --only ReducedScalarRegisterFile`
- `bash tools/chisel/run_chisel_tests.sh --only ReducedScalarIssueQueue`
- `bash tools/chisel/run_chisel_tests.sh --only ReducedScalarAluExecute`
- `bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendRfAluTraceTop`
- `bash tools/chisel/run_chisel_frontend_rf_alu_trace_top_xcheck.sh`
- `bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendAluTraceTop`
- `bash tools/chisel/run_chisel_frontend_alu_trace_top_xcheck.sh`
- `bash tools/chisel/run_chisel_frontend_trace_top_xcheck.sh`
- `python3 tools/chisel/trace_schema_adapter.py --self-test`
- `bash tools/chisel/run_chisel_qemu_crosscheck.sh --dry-run`
- `bash tools/chisel/build_chisel.sh`
- `bash tools/chisel/run_chisel_verilator_lint.sh`
