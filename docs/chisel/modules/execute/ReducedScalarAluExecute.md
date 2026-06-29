# ReducedScalarAluExecute

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/execute/ReducedScalarAluExecute.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/execute/ReducedScalarAluExecuteSpec.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/bctrl/spe/SPERename.cpp`
  - `model/LinxCoreModel/model/iex/pipe/alu_pipe.cpp`
  - `model/LinxCoreModel/model/ModelCommon/SimInstInfo.cpp`
  - `model/LinxCoreModel/isa/calculate/arithmetic/Arithmetic.cpp`
  - `model/LinxCoreModel/isa/calculate/others/Others.cpp`
  - `model/LinxCoreModel/isa/ISACommon/OpcodeManager.cpp`
- Contract IDs: `LC-IF-CHISEL-IEX-ALU-001`, `LC-IF-CHISEL-XCHK-006`

## Purpose

`ReducedScalarAluExecute` is the first Chisel scalar execute owner behind the
frontend trace path. It consumes one `RenamedUop`, computes a reduced
model-derived ALU subset, and emits both a ROB completion index and a
writeback-shaped `CommitTraceRow`.

This is not the final scalar integer execution unit. Source values are supplied
by the wrapper/testbench until the register file, ready table, issue queue, and
wakeup path are implemented.

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

`SPERename::InsertToSIEXQ` routes `ARITHMETIC`, `IMMEDIATE`, and `MOVE`
instructions to the scalar ALU IEX path. `ALUPipe::Work` advances the uop
through P1/I1/I2/E0/EX/W1/W2, calls `SimInstInfo::Execute` in EX, and publishes
the resolve bus at W2. `SimInstInfo::Execute` runs `PrePareSrc`, `Calculate`,
and `ProcessDst`; arithmetic `ADD` returns `src0 + src1`, `ADDI` uses the
decoded immediate, and `MOVR/MOVI` move the source/immediate into the
destination.

The Chisel module implements the first reduced subset:

| Opcode | Result |
|---|---|
| `OP_ADD` | `srcData(0) + srcData(1)` |
| `OP_ADDI` | `srcData(0) + in.imm` |
| `OP_C_MOVI` | `in.imm` |
| `OP_C_MOVR` | `srcData(0)` |

The completion row copies identity and control fields from the accepted
`RenamedUop`, fills source register/data fields from `uop.src` plus
`srcData`, fills `dst` and `wb` from `uop.dst(0)` plus the computed result,
and leaves memory/trap fields zero.

## Timing

The reduced pipe captures a uop into E when `inValid && inReady`, computes the
result when E advances to W1, and emits completion from W2. There is no
downstream completion backpressure yet, so the owning top must only connect
this module to a completion consumer that can accept one completion when
`completeValid` pulses.

## Flush/Recovery

No flush or replay input is implemented in this reduced owner. Later execute
owners must add recovery cancellation before this module can be used behind a
multi-entry issue queue or speculative replay path.

## Trace/Observability

`completeRow` is the first Chisel-owned nonzero writeback payload in the
frontend trace lane. It is designed to feed `ROBEntryBank.completeRow` through
`LinxCoreFrontendAluTraceTop` and then the existing commit monitor and
QEMU-shaped comparator.

## Verification

- `bash tools/chisel/run_chisel_tests.sh --only ReducedScalarAluExecute`
- `bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendAluTraceTop`
- `bash tools/chisel/run_chisel_frontend_alu_trace_top_xcheck.sh`
- `python3 tools/chisel/trace_schema_adapter.py --self-test`
