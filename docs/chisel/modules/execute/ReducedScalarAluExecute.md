# ReducedScalarAluExecute

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/execute/ReducedScalarAluExecute.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/execute/ReducedScalarAluExecuteSpec.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/bctrl/spe/SPERename.cpp`
  - `model/LinxCoreModel/model/iex/pipe/alu_pipe.cpp`
  - `model/LinxCoreModel/model/iex/rtable.cpp`
  - `model/LinxCoreModel/model/ModelCommon/SimInstInfo.cpp`
  - `model/LinxCoreModel/isa/calculate/arithmetic/Arithmetic.cpp`
  - `model/LinxCoreModel/isa/calculate/others/Others.cpp`
  - `model/LinxCoreModel/isa/calculate/pc/PC.cpp`
  - `model/LinxCoreModel/isa/ISACommon/OpcodeManager.cpp`
- Contract IDs: `LC-IF-CHISEL-IEX-ALU-001`, `LC-IF-CHISEL-XCHK-006`

## Purpose

`ReducedScalarAluExecute` is the first Chisel scalar execute owner behind the
frontend trace path. It consumes one `RenamedUop`, computes a reduced
model-derived ALU subset, and emits both a ROB completion index and a
writeback-shaped `CommitTraceRow`. It also emits the reduced issue-queue
release identity when the row reaches W2.

This is not the final scalar integer execution unit. Source values are supplied
by the owning wrapper: the R81 top uses explicit testbench operands, while the
R82 RF-backed top reads `srcData` from a reduced scalar physical register file.
The full issue queue, bypass, wakeup, replay, and recovery paths remain later
owners.

## Interface

| Direction | Signal | Type | Valid/ready | Description |
|---|---|---|---|---|
| input | `inValid` | `Bool` | valid | One renamed scalar uop is available. |
| output | `inReady` | `Bool` | ready | The E stage can capture a new uop. |
| input | `in` | `RenamedUop` | with `inValid && inReady` | Renamed scalar uop from `DecodeRenameROBPath`. |
| input | `srcData` | `Vec(3, UInt(64.W))` by default | with accepted uop | Operand values supplied by the reduced top harness. |
| output | `completeValid` | `Bool` | valid | W2-stage supported uop completed. |
| output | `completeRobValue` | `UInt(log2Ceil(robEntries).W)` | with `completeValid` | ROB row to complete. |
| output | `completeRow` | `CommitTraceRow` | with `completeValid` | Commit-trace payload carrying PC, source data, destination data, writeback, ROB ID, and block BID sideband. |
| output | `completeDstPhysValid` | `Bool` | with `completeValid` | Destination physical tag and data are valid for a scalar GPR writeback. |
| output | `completeDstPhysTag` | `UInt(physRegWidth.W)` | with `completeDstPhysValid` | Renamed physical destination tag copied from `in.dst(0).physTag`. |
| output | `completeDstData` | `UInt(64.W)` by default | with `completeDstPhysValid` | ALU result for the RF/ready-table writeback owner. |
| output | `releaseValid` | `Bool` | valid | W2-stage uop reached the reduced issue-queue release point, including unsupported reduced opcodes. |
| output | `releaseBid` | `ROBID` | with `releaseValid` | Block identity copied from the W2 uop. |
| output | `releaseRid` | `ROBID` | with `releaseValid` | ROB identity copied from the W2 uop. |
| output | `releaseStid` | `UInt(threadIdWidth.W)` | with `releaseValid` | STID copied from the W2 uop. |
| output | `accepted` | `Bool` | pulse | `inValid && inReady`. |
| output | `busy` | `Bool` | state | Any E/W1/W2 pipe stage is occupied. |
| output | `unsupported` | `Bool` | pulse | W2 uop reached execute but is outside the reduced opcode subset. |
| output | `unsupportedOpcode` | `UInt(opcodeWidth.W)` | with `unsupported` | Unsupported opcode ID for diagnostics. |

## State

- `eValid/eUop/eSrcData`: accepted renamed uop and source values.
- `w1Valid/w1Uop/w1SrcData/w1Result/w1Supported`: first writeback pipe stage.
- `w2Valid/w2Uop/w2SrcData/w2Result/w2Supported`: completion pipe stage.

The pipe is intentionally single-issue and in-order for the reduced frontend
trace path.

## Logic Design

`SPERename::InsertToSIEXQ` routes `ARITHMETIC`, `IMMEDIATE`, `MOVE`, and the
PC-relative constant path used by `ADDTPC` toward scalar execution.
`ALUPipe::Work` advances the uop through P1/I1/I2/E0/EX/W1/W2, calls
`SimInstInfo::Execute` in EX, and publishes the resolve bus at W2.
`SimInstInfo::Execute` runs `PrePareSrc`, `Calculate`, and `ProcessDst`;
arithmetic `ADD` returns `src0 + src1`, `ADDI` uses the decoded immediate,
`MOVR/MOVI` move the source/immediate into the destination, and
`PC::CalcInstAddTPC` computes `(pc & ~0xfff) + imm`. `PC::CalcInstSetret`
computes `pc + imm`, and the compact `C.SETRET` form reaches execute as a
decoded alias of the `C.MOVI` low-opcode encoding when the destination is
`ra/x10`. `OP_HL_LUI` materializes the sign-extended 48-bit-format IMM32
payload directly into the destination; the frontend packs `pfx16[15:4]` over
`main32[31:12]` before execute receives `in.imm`. R111 adds `OP_SLL`, which
left-shifts SrcL by the low six bits of SrcR; CoreMark's first instance reads
T0 and U0 local sources and writes U0. R112 adds the matching logical-right
shift `OP_SRL` for the following CoreMark local-source row. R108 adds a narrow
`FENTRY`
macro-template subset based on
`GenCodeFENTRY`: for the current CoreMark single-save form, the reduced decoder
reads the saved GPR and old SP, execute writes `SP - imm` back to SP, and the
completion row carries one 8-byte store at `newSP + imm - 8`. This is not a
general stack-template or LSU implementation. `ReadyState::GetSrcData` and
`InitGGPRRtable` show that scalar source data is attached to physical GPR tags,
so the Chisel execute owner also exports the destination physical tag for the
reduced RF writeback path. R109 keeps that RF writeback side effect scoped to
`DestinationKind.Gpr`; a T/U destination still produces a QEMU-shaped
architectural `dst`/`wb` completion row, but the reduced scalar RF is not
cleared or written for the local-register alias.

The Chisel module implements the first reduced subset:

| Opcode | Result |
|---|---|
| `OP_ADD` | `srcData(0) + srcData(1)` |
| `OP_ADDI` | `srcData(0) + in.imm` |
| `OP_ADDTPC` | `(in.pc & ~0xfff) + in.imm` |
| `OP_C_MOVI` | `in.imm` |
| `OP_C_MOVR` | `srcData(0)` |
| `OP_C_SETRET` | `in.pc + in.imm` |
| `OP_FENTRY` | `srcData(1) - in.imm` for the reduced single-save SP update |
| `OP_HL_LUI` | `in.imm` |
| `OP_SLL` | `srcData(0) << srcData(1)(5, 0)` |
| `OP_SRL` | `srcData(0) >> srcData(1)(5, 0)` |

`OP_ADDTPC` uses the `FrontendOperandDecode` `ImmIMM20` path, where the
20-bit immediate is sign-extended and shifted left by 12 before reaching
execute. This matches the model and linker contract for page-relative address
materialization and is intentionally separate from `C.SETRET`, whose compact
form uses `uimm5 << 1` and `pc + imm` return-address semantics.

The completion row copies identity and control fields from the accepted
`RenamedUop`, fills source register/data fields only for scalar
`OperandClass.P` operands, fills `dst` and `wb` from `uop.dst(0)` plus the
computed result,
exports the same result and destination physical tag on `completeDst*` only for
scalar GPR destinations, and leaves memory/trap fields zero. T/U destinations
remain visible in the completion row for the comparator, but their local
register data path is owned by `ScalarTURenameBridge`, the reduced local-value
overlay in the live top, and later T/U issue owners rather than by the scalar
RF. T/U local sources are consumed internally and suppressed from the
QEMU-shaped source fields.
R110 proves this for CoreMark's first `HL.LUI`, which writes architectural tag
`31` as a T destination while scalar RF writeback remains gated off.
R111 proves the same local-source row shape for CoreMark `SLL`: the row reads
T0/U0, writes architectural U tag `30`, emits `0x100000000`, and leaves scalar
source fields invalid to match QEMU.
R112 proves that the same shift family can write either local class in the
reduced trace: the later `SLL` and `SRL` rows write architectural T tag `31`,
while scalar RF side effects remain gated to GPR destinations.

For reduced `OP_FENTRY`, the completion row intentionally suppresses internal
source fields so it matches QEMU's macro row, while preserving the architectural
SP writeback and one store sideband (`mem.valid`, `mem.isStore`, `mem.addr`,
`mem.wdata`, and `mem.size=8`). The reduced top/harness compares that memory
sideband through the common JSONL comparator.

`releaseValid` is intentionally tied to any valid W2 row, not only supported
rows. The reduced issue queue has already marked the row issued when execute
accepted it; an unsupported reduced opcode must still release that resident
queue entry so the top does not deadlock while surfacing `unsupported`.

## Timing

The reduced pipe captures a uop into E when `inValid && inReady`, computes the
result when E advances to W1, and emits completion and release from W2. There
is no downstream completion backpressure yet, so the owning top must only
connect this module to a completion consumer that can accept one completion
when `completeValid` pulses.

## Flush/Recovery

No flush or replay input is implemented in this reduced owner. Later execute
owners must add recovery cancellation before this module can be used behind a
multi-entry issue queue or speculative replay path.

## Trace/Observability

`completeRow` is the first Chisel-owned nonzero writeback payload in the
frontend trace lane. It is designed to feed `ROBEntryBank.completeRow` through
`LinxCoreFrontendAluTraceTop` and then the existing commit monitor and
QEMU-shaped comparator. `completeDstPhysValid/Tag/Data` feed
`ReducedScalarRegisterFile` in `LinxCoreFrontendRfAluTraceTop`, allowing later
frontend rows to read earlier Chisel writebacks through renamed physical tags.
`releaseValid/Bid/Rid/Stid` feed `ReducedScalarIssueQueue` so the queue
removes the issued row only after this pipe reaches the reduced release point.

## Verification

- `bash tools/chisel/run_chisel_tests.sh --only ReducedScalarAluExecute`
- `bash tools/chisel/run_chisel_tests.sh --only ReducedScalarRegisterFile`
- `bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendRfAluTraceTop`
- `bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendAluTraceTop`
- `bash tools/chisel/run_chisel_frontend_rf_alu_trace_top_xcheck.sh`
- `bash tools/chisel/run_chisel_frontend_alu_trace_top_xcheck.sh`
- `bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r106-coremark-addtpc-qemu-elf-xcheck --elf tests/benchmarks/build/coremark_real.elf --expected-rows 0 --capture-rows 4 --allow-block-markers --max-seconds 8 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf`
- `bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r107-coremark-hl-call-setret-qemu-elf-xcheck --elf tests/benchmarks/build/coremark_real.elf --expected-rows 0 --capture-rows 8 --allow-block-markers --max-seconds 8 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf`
- `bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r108-coremark-fentry-qemu-elf-xcheck --elf tests/benchmarks/build/coremark_real.elf --expected-rows 0 --capture-rows 11 --allow-block-markers --max-seconds 8 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf`
- `bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r110-coremark-hl-lui-qemu-elf-xcheck --elf tests/benchmarks/build/coremark_real.elf --expected-rows 0 --capture-rows 13 --allow-block-markers --max-seconds 8 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf`
- `bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r111-coremark-sll-tu-qemu-elf-xcheck --elf tests/benchmarks/build/coremark_real.elf --expected-rows 0 --capture-rows 14 --allow-block-markers --max-seconds 8 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf`
- `bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r112-coremark-sll-srl-tu-qemu-elf-xcheck --elf tests/benchmarks/build/coremark_real.elf --expected-rows 0 --capture-rows 17 --allow-block-markers --max-seconds 8 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf`
- `python3 tools/chisel/trace_schema_adapter.py --self-test`
