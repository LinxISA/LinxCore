# LinxCoreFrontendAluTraceTop

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/top/LinxCoreFrontendAluTraceTop.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/top/LinxCoreFrontendAluTraceTopSpec.scala`
- Verilator driver: `rtl/LinxCore/tools/chisel/frontend_alu_trace_top_tb.cpp`
- Gate: `rtl/LinxCore/tools/chisel/run_chisel_frontend_alu_trace_top_xcheck.sh`
- Child owners:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/frontend/F4DecodeWindow.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/backend/DecodeRenameROBPath.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/execute/ReducedScalarAluExecute.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/bctrl/spe/SPERename.cpp`
  - `model/LinxCoreModel/model/iex/pipe/alu_pipe.cpp`
  - `model/LinxCoreModel/model/ModelCommon/SimInstInfo.cpp`
  - `model/LinxCoreModel/isa/calculate/arithmetic/Arithmetic.cpp`
  - `model/LinxCoreModel/isa/calculate/others/Others.cpp`
- Contract IDs: `LC-IF-CHISEL-TOP-003`, `LC-IF-CHISEL-XCHK-006`

## Purpose

`LinxCoreFrontendAluTraceTop` is the R81 successor to the R80 frontend trace
top. It drives raw frontend packets through F4 decode, decode/rename,
BROB/ROB reservation, post-rename row update, and a reduced scalar ALU execute
owner before committing rows through the monitored ROB path.

This wrapper removes the external completion surrogate for the scalar
`ADD`/`ADDI`/compressed move smoke. It still is not a bootable core because
fetch, issue, register-file reads, LSU, traps, recovery, and memory are not
live behind this top.

R82 adds `LinxCoreFrontendRfAluTraceTop` as the RF-backed successor. Keep this
R81 top and gate as the regression for the temporary `operandData` path, but do
not extend it with more per-uop operand fixture inputs.

## Interface

| Direction | Signal | Type | Valid/ready | Description |
|---|---|---|---|---|
| input | `in` | `FrontendDecodePacket` | `in.valid` | Host/testbench supplied 8-byte frontend window, PC, PE/STID, packet UID, and checkpoint ID. |
| input | `operandData` | `Vec(3, UInt(64.W))` by default | held until `executeAccepted` | Reduced source values for the selected renamed uop. |
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
| output | `robAllocFire`, `robRenameUpdateFire` | `Bool` | pulse | BROB/ROB reservation and post-rename row update pulses. |
| output | `completeAccepted`, `completeIgnored` | `Bool` | pulse | ROB completion result from ALU-produced completion. |
| output | `commit.rows` | `Vec(commitWidth, CommitTraceRow)` | row `valid` | Monitored commit rows, now including ALU-produced source, destination, and writeback data for the reduced opcode subset. |
| output | `commit*`, `dealloc*`, `*Mask`, `idle` | mixed | diagnostic | Commit monitor, ROB lifecycle, and occupancy observability inherited from `DecodeRenameROBPath`. |

## State

The wrapper owns no architectural state. State lives in child owners:

- `F4DecodeWindow`: combinational frontend-window slicing.
- `DecodeRenameROBPath`: decode-to-rename queue, scalar/T-U rename, BROB/ROB,
  commit, and deallocation.
- `ReducedScalarAluExecute`: reduced E/W1/W2 scalar ALU pipe.

## Logic Design

The wrapper is identical to `LinxCoreFrontendTraceTop` up to the rename output,
then connects `path.io.renamedOutValid/renamedOut` to
`ReducedScalarAluExecute`. `path.io.renamedOutReady` is driven by the ALU
input readiness. The ALU completion outputs drive
`path.io.completeValid`, `path.io.completeRobValue`,
`path.io.completeRowValid`, and `path.io.completeRow`.

`operandData` is an explicit bring-up input. Verilator drivers must hold it
stable until `executeAccepted` because the ALU captures source values at the
rename-to-execute handoff. `LinxCoreFrontendRfAluTraceTop` is the replacement
boundary for persistent RF-sourced operands; future packets should extend that
RF/issue lane rather than adding more top-level operand fixtures here.

## Model Alignment

`SPERename::InsertToSIEXQ` sends arithmetic, immediate, and move instructions
to the scalar ALU IEX queue. `ALUPipe` resolves the instruction in its execute
pipe and publishes a resolve bus at W2. The wrapper mirrors that ordering at
the reduced top boundary: decode/rename allocates and patches the ROB row
first, ALU completion later marks the same ROB row complete with result data,
and the ROB commit head emits the architectural trace row.

## Timing

One frontend packet can be accepted when `decodeReady` is high. The selected
uop later leaves the decode-to-rename queue when the reduced ALU is ready.
ALU completion arrives from W2 and the ROB can commit the completed row on a
later cycle if it is at the commit head.

## Flush/Recovery

Only the frontend flush input is live. Backend cleanup, checkpoint restore,
ROB prune, LSU cleanup, precise traps, and redirect restart remain tied off in
this wrapper.

## Trace/Observability

`tools/chisel/run_chisel_frontend_alu_trace_top_xcheck.sh` emits the top,
builds the shared Verilator harness in the default operand-fixture mode, drives
three frontend packets (`ADD`, `ADDI`, and compressed `MOVR`), supplies operand
data, waits for ALU completion, dumps DUT commit JSONL, and compares it against
QEMU-shaped rows with nonzero source, destination, and writeback data.

The current gate is a reduced generated-RTL proof, not a full QEMU boot
comparison.

## Verification

- `bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendAluTraceTop`
- `bash tools/chisel/run_chisel_tests.sh --only ReducedScalarAluExecute`
- `bash tools/chisel/run_chisel_frontend_alu_trace_top_xcheck.sh`
- `bash tools/chisel/run_chisel_frontend_rf_alu_trace_top_xcheck.sh`
- `bash tools/chisel/run_chisel_frontend_trace_top_xcheck.sh`
- `python3 tools/chisel/trace_schema_adapter.py --self-test`
- `bash tools/chisel/run_chisel_qemu_crosscheck.sh --dry-run`
