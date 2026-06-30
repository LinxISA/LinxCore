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
  - `model/LinxCoreModel/isa/calculate/store/Store.cpp`
  - `model/LinxCoreModel/isa/ISACommon/OpcodeManager.h`
  - `model/LinxCoreModel/isa/ISACommon/OpcodeManager.cpp`
  - `emulator/qemu/target/linx/translate.c`
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
| input | `flushValid` | `Bool` | pulse | Clears E/W1/W2 pipeline state and suppresses same-cycle completion/redirect outputs. |
| input | `loadLookupData` | `UInt(64.W)` by default | combinational with E-stage load lookup | Read-only reduced load data returned by the owning top for reduced load rows including `OP_C_LDI`, `OP_LDI`, `OP_LD_PCR`, and `OP_HL_LD_PCR`. |
| input | `fretStkFallbackTargetValid`, `fretStkFallbackTarget` | `Bool`, `UInt(pcWidth.W)` | combinational | Active-marker fallback target used by reduced `FRET.STK` when no explicit SETC target is live. |
| input | `stackPointerData` | `UInt(64.W)` by default | combinational | Reduced SP shadow used by macro `FENTRY` rows whose visible QEMU source fields are suppressed. |
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
| output | `branchConditionValid` | `Bool` | with `completeValid` | Reduced conditional-block decision is valid for a completed compare row. |
| output | `branchConditionTaken` | `Bool` | with `branchConditionValid` | `OP_C_SETC_EQ`, `OP_C_SETC_NE`, `OP_SETC_LTU`, and reduced SETC target rows used by the reduced block-control owner. |
| output | `loadLookupValid` | `Bool` | E-stage valid | The current E-stage uop requests reduced read-only load data. |
| output | `loadLookupAddr` | `UInt(64.W)` by default | with `loadLookupValid` | Byte address for the reduced read-only load lookup. |
| output | `redirectValid` | `Bool` | with `completeValid` | Reduced scalar redirect request for `FRET.STK` when an explicit SETC target or active-marker fallback target is available. |
| output | `redirectPc` | `UInt(pcWidth.W)` | with `redirectValid` | Return target selected from `C.SETC.TGT`/`SETC.TGT` first, then the active-marker fallback. |
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
`SUBI` subtracts the decoded immediate,
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
R113 adds `OP_OR` for the next local-source row and a narrow `OP_C_LDI`
zero-load row: the execute owner emits the QEMU-shaped 8-byte load sideband and
returns zero data only for the current reduced CoreMark prefix, not for general
memory execution. R114 adds the compressed local arithmetic row `OP_C_ADD`:
the compressed source fields select local T/U sources, the row writes implicit
T destination tag `31`, and QEMU still suppresses those local source fields in
commit JSONL. The current QEMU row also omits the local destination/writeback
fields for `C.ADD`, so the expected-row reducer synthesizes the implicit T
writeback from the LinxCoreModel compressed arithmetic contract while keeping
the QEMU PC/instruction stream and local-source history as the proof input.
R115 adds `OP_SRA` and the same dense-packet `OP_SLLI` row. `OP_SRA` follows
the LinxCoreModel arithmetic calculator default: signed 64-bit right shift by
`src1 & 0x3f`. `OP_SLLI` uses the model `@shift_i` immediate source,
`shamt_20_25`, carried in `RenamedUop.imm` by `FrontendOperandDecode`.

The Chisel module implements the first reduced subset:

| Opcode | Result |
|---|---|
| `OP_ADD` | `srcData(0) + srcData(1)` |
| `OP_ADDI` | `srcData(0) + in.imm` |
| `OP_SUB` | `srcData(0) - srcData(1)` |
| `OP_SUBI` | `srcData(0) - in.imm` |
| `OP_ANDI` | `srcData(0) & in.imm` |
| `OP_ANDIW` | sign-extended low-32-bit `srcData(0) & in.imm` |
| `OP_ADDTPC` | `(in.pc & ~0xfff) + in.imm` |
| `OP_C_MOVI` | `in.imm` |
| `OP_C_MOVR` | `srcData(0)` |
| `OP_C_ADD` | `srcData(0) + srcData(1)` |
| `OP_C_AND` | `srcData(0) & srcData(1)` |
| `OP_C_SUB` | `srcData(0) - srcData(1)` |
| `OP_C_LDI` | `loadLookupData`, with a reduced 8-byte load sideband at `srcData(0) + in.imm` |
| `OP_C_SDI` | `0`, with a reduced 8-byte store sideband at `srcData(0) + (in.imm << 3)` and store data `srcData(1)` |
| `OP_C_SETC_EQ` | `0`, with branch sideband taken when validity-masked `srcData(0) == srcData(1)` |
| `OP_C_SETC_NE` | `0`, with branch sideband taken when validity-masked `srcData(0) != srcData(1)` |
| `OP_C_SETC_TGT` | `0`, latches `srcData(0)` as the reduced dynamic target |
| `OP_C_SETRET` | `in.pc + in.imm` |
| `OP_FENTRY` | `stackPointerData - in.imm`; the store address uses the ranged save count to place the first saved register |
| `OP_FRET_STK` | `0`, no writeback, redirect to the latched SETC target or active-marker fallback target |
| `OP_HL_LUI` | `in.imm` |
| `OP_HL_LD_PCR` | `loadLookupData`, with an 8-byte load sideband at `in.pc + in.imm` |
| `OP_LD_PCR` | `loadLookupData`, with an 8-byte load sideband at `in.pc + in.imm` |
| `OP_LDI` | `loadLookupData`, with a reduced 8-byte load sideband at `srcData(0) + (in.imm << 3)` |
| `OP_SETC_TGT` | `0`, latches `srcData(0)` as the reduced dynamic target |
| `OP_SETC_LTU` | `0`, with branch sideband taken when unsigned `srcData(0) < srcData(1)` |
| `OP_SETC_LTUI` | `0`, with branch sideband taken when unsigned `srcData(0) < in.imm` |
| `OP_ADDW` | sign-extended low-32-bit `srcData(0) + srcData(1)` |
| `OP_SD` | `0`, with a reduced 8-byte indexed store sideband at `srcData(0) + (srcData(1) << 3)` and store data `srcData(2)` |
| `OP_SDI` | `0`, with a reduced 8-byte store sideband at `srcData(1) + (in.imm << 3)` and store data `srcData(0)` |
| `OP_SWI` | `0`, with a reduced 4-byte store sideband at `srcData(1) + (in.imm << 2)` and store data `srcData(0)` |
| `OP_MUL` | low 64 bits of `srcData(0) * srcData(1)` |
| `OP_MULW` | sign-extended low-32-bit `srcData(0) * srcData(1)` |
| `OP_SBI` | `0`, with a reduced 1-byte store sideband at `srcData(1) + in.imm` and store data `srcData(0)` |
| `OP_SLL` | `srcData(0) << srcData(1)(5, 0)` |
| `OP_SLLI` | `srcData(0) << in.imm(5, 0)` |
| `OP_SRL` | `srcData(0) >> srcData(1)(5, 0)` |
| `OP_SRA` | `srcData(0).asSInt >> srcData(1)(5, 0)` |
| `OP_OR` | `srcData(0) \| srcData(1)` |
| `OP_ORI` | `srcData(0) \| in.imm` |

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
R113 proves `OP_OR` as another local-source row writing U tag `30`, then admits
the immediately following `C.LDI` zero-load row writing T tag `31`. The
reduced memory sideband address is `srcData(0) + uop.imm`; starting in R117,
`FrontendOperandDecode` provides `uop.imm` as signed bits `[15:11]` shifted
left by 3. This remains a prefix gate only; nonzero load data must wait for a
real data-memory/LSU owner.
R114 proves `OP_C_ADD` at `pc=0x4000553c`, where compressed source fields
decode to T0/U0 and the implicit destination writes T0. This is still a
local-source reduced ALU row, not a general compressed ALU expansion. Because
QEMU currently emits that row without `dst`/`wb`, the reducer patches only this
expected row to the model-derived implicit T writeback before comparison.
R115 proves `OP_SRA` at `pc=0x4000553e` and the paired `OP_SLLI` at
`pc=0x40005542`. Both read local T state, write architectural T tag `31`, and
keep scalar RF side effects gated off; `SLLI` is included with `SRA` because
the live frontend emits both slots in the same dense packet.
R117 admits `OP_C_SETC_NE` as a reduced no-writeback compare row. The row
exists to keep the live CoreMark prefix moving through a local/scalar compare
that QEMU emits without destination fields; execute marks it supported and
returns a zero reference result, while the decoded invalid destination keeps
the commit row free of writeback.
R119 extends that row into the reduced conditional-block sideband. When
`OP_C_SETC_NE` completes, `branchConditionValid` pulses and
`branchConditionTaken` is the not-equal comparison of validity-masked source
data. Invalid operands are treated as zero for the decision sideband, matching
the completion-row source suppression policy used by local T/U operands.
R121 adds the matching compact equality compare `OP_C_SETC_EQ` and the 32-bit
`OP_LDI` zero-load row encountered after repeated CoreMark loop trips.
`OP_C_SETC_EQ` shares the no-writeback completion shape and branch sideband,
but the decision is `src0 == src1`. `OP_LDI` is a narrow reduced load bridge:
execute returns the observed zero read data and emits one 8-byte load sideband
at `srcData(0) + (uop.imm << 3)`. This remains a bounded CoreMark prefix
contract; nonzero loads require the real data-memory/LSU owner.
R122 replaces that zero-only load shortcut with an explicit read-only load
lookup owned by the live top and harness. For `OP_C_LDI` and `OP_LDI`, execute
publishes `loadLookupValid/loadLookupAddr` while the row is in E and captures
`loadLookupData` as the result and memory read data. The current live harness
serves that data from the sparse ELF image and returns zero for missing bytes;
this is still not an LSU, cache, store-forwarding, or memory-mutation owner.
R122 also admits the first 32-bit `OP_SETC_LTU` compare at `pc=0x400055e4`.
It is a no-writeback condition row whose branch sideband is the unsigned
comparison `src0 < src1`.
R124 admits the indexed 64-bit store `OP_SD` at `pc=0x400055f2`. The model
and QEMU decode use `SrcL` (`rs1`) as the base, `SrcR` (`rs2`) as the index,
and `SrcD` (`bits[31:27]`) as the store payload. The reduced execute owner
therefore emits a no-writeback 8-byte store sideband with
`addr = srcData(0) + (srcData(1) << 3)` and `wdata = srcData(2)`. The current
CoreMark row has a local T0 base, visible scalar `x2` index, and local U0
payload, so the QEMU-shaped source fields expose only `src1=x2` while local
source values remain internal. This is still a reduced sideband owner, not a
general LSU/STQ path or a scalar-src2 commit-trace schema.
R125 extends the reduced live CoreMark envelope with `OP_SUBI`, compressed
local `OP_C_AND`, a local/scalar `OP_ADD` row, and compressed store-immediate
`OP_C_SDI`. The reducer validates `SUBI` as `src0 - uimm12`, lets 32-bit `ADD`
use encoded local or scalar source fields independently, and applies the same
QEMU trace-gap synthesis used by `C.ADD` to `C.AND` when the QEMU row omits
the implicit T destination/writeback. For `C.SDI`, the compressed source
contract is the encoded base plus signed bits `[15:11] << 3`, with T0 as the
store payload; the Chisel execute row receives that as base on `srcData(0)`,
payload on `srcData(1)`, and emits a no-writeback 8-byte store sideband. This
is still a reduced committed-store sideband, not a full LSU/STQ path.
R118 admits the first ordinary scalar store-immediate row, `OP_SDI`. The C++
model decodes the 32-bit form as `src0=SrcL` store data, `src1=SrcR` address
base, and `src2=simm12_7_s5_25_7 << 3`; `Store::CalcStoreAddr` computes
`src1 + src2`, and `GetStoreDataSrcIndex` selects source 0 for non-PCR stores.
Linx QEMU's `trans_sdi` matches that contract with data from `SrcL` and
address `SrcR + simm12 * 8`. The reduced execute owner therefore emits a
no-writeback 8-byte store sideband with `addr = srcData(1) + (uop.imm << 3)`
and `wdata = srcData(0)`. The current CoreMark row uses a suppressed local T0
base and visible scalar x5 store data.
R126 extends the reduced envelope through the first PCR return sequence and
byte store. `LD.PCR` and `HL.LD.PCR` request load data at `pc + imm`, where
the frontend has already decoded the unshifted PCR immediate. `C.SETC.TGT` and
`SETC.TGT` latch a dynamic target from source 0. `FRET.STK` suppresses all
source/destination/writeback fields in the QEMU-shaped row, emits
`next_pc = latchedTarget`, requests a scalar frontend redirect, and clears the
latched target. Ranged `FENTRY` computes the first save address from the saved
register count instead of assuming one saved register. `ADDW` sign-extends the
low 32-bit sum, and `SBI` emits a no-writeback 1-byte store using the unscaled
split immediate and base on source 1. These are still reduced CoreMark-prefix
contracts, not a general LSU or dynamic-control implementation.
R127 extends that contract for return-block cleanup. `FRET.STK` redirects to
an explicit SETC target when one is live, otherwise to the active marker target
provided by the top. `flushValid` kills all pipeline stages and gates
completion/redirect side effects during backend cleanup. `FENTRY` now consumes
the top-owned SP shadow instead of a visible source lane; ranged forms in the
current reduced trace suppress store data rather than reading a synthetic
source that QEMU does not expose.
R128 admits the first 32-bit SETC immediate compare encountered by CoreMark,
`OP_SETC_LTUI` at `pc=0x4000d1e4`. It is a no-writeback condition row: execute
returns zero in the completion payload, asserts the branch-condition sideband,
and compares `srcData(0)` against the decoded unsigned immediate. It does not
claim the full commit-condition storage path.
R129 admits the following `OP_ANDIW` row at `pc=0x4000d210`. The model
calculates the immediate form as `src0 & imm`, truncates the result to
32 bits, and sign-extends it back to 64 bits. The current CoreMark row writes
architectural alias `x30/U0`, so it remains a local-destination completion row
without scalar RF writeback.
R130 admits `OP_MULW` at `pc=0x4000d21a` and the adjacent compressed
`OP_C_SUB` tail at `pc=0x4000d21e`. LinxCoreModel routes `MULW` through the
multicycle group, but the architectural result is still the low-32 product of
source 0 and source 1 sign-extended to 64 bits. The reduced RF/ALU top computes
that result for the bounded CoreMark prefix while preserving the future
ownership boundary: this is not a general multicycle-pipeline replacement. The
following `C.SUB` uses the same implicit-T local writeback contract as
`C.ADD`/`C.AND`; the QEMU trace row omits destination/writeback fields, so the
expected-row reducer synthesizes the `T0` completion from the encoded sources.
R131 extends the same CoreMark packet through immediate logical ALU rows,
word-store immediates, scalar subtraction, and a scalar-result multiply:
`OP_ANDI` at `pc=0x4000d220`, `OP_SWI` at `pc=0x4000d22a` and
`pc=0x4000d23a`, `OP_ORI` at `pc=0x4000d284`, `OP_SUB` at `pc=0x4000d288`,
and `OP_MUL` at `pc=0x4000d2a6`. `SWI` uses the model store-immediate shape
with source 0 as store data, source 1 as base, and the split immediate scaled
by four. The QEMU reducer also now lets ordinary shift and logical rows read
either scalar-visible or local-overlay sources per encoded operand, which is
needed by the scalar-visible `OP_SLL` row at `pc=0x4000d292`. The slice stops
before the richer `FRET.STK` return/load packet at `pc=0x4000d2d4`.

For reduced `OP_FENTRY`, the completion row intentionally suppresses internal
source fields so it matches QEMU's macro row, while preserving the architectural
SP writeback and one store sideband (`mem.valid`, `mem.isStore`, `mem.addr`,
`mem.wdata`, and `mem.size=8`). The reduced top/harness compares that memory
sideband through the common JSONL comparator.
For reduced `OP_SDI`, the completion row carries `mem.valid`, `mem.isStore`,
`mem.addr`, `mem.wdata`, and `mem.size=8` but leaves `dst`/`wb` invalid. It
does not model store queue drain, cache state, or memory mutation.
For reduced `OP_SWI`, the completion row has the same no-writeback store
shape, but the address is `srcData(1) + (uop.imm << 2)`, the store payload is
`srcData(0)`, and `mem.size=4`.
For reduced `OP_SD`, the completion row has the same no-writeback store shape,
but the address comes from base plus scaled index and the store payload comes
from `srcData(2)`.
For reduced `OP_C_SDI`, the completion row uses the same no-writeback store
shape, with address from the compressed base plus scaled immediate and payload
from the decoded T0 source carried on `srcData(1)`.

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

`flushValid` clears E/W1/W2 state, clears pending SETC target state, and gates
same-cycle completion, release, branch-decision, redirect, load-lookup, and
unsupported diagnostics. It is a reduced backend cleanup hook for live
CoreMark redirect evidence, not full replay or exception recovery.

Later execute owners must add recovery age checks, replay, bypass
invalidation, and exception-priority behavior before this module can sit
behind a general speculative issue queue.

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
- `bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r113-coremark-or-c-ldi-qemu-elf-xcheck --elf tests/benchmarks/build/coremark_real.elf --expected-rows 0 --capture-rows 19 --allow-block-markers --max-seconds 8 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf`
- `bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r118-coremark-sdi-42-qemu-elf-xcheck --elf tests/benchmarks/build/coremark_real.elf --expected-rows 0 --capture-rows 42 --allow-block-markers --max-seconds 8 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf`
- `bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r119-coremark-cond-bstart-50-qemu-elf-xcheck --elf tests/benchmarks/build/coremark_real.elf --expected-rows 0 --capture-rows 50 --allow-block-markers --max-seconds 8 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf`
- `bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r125-coremark-1024-frontier-probe-qemu-elf-xcheck --elf tests/benchmarks/build/coremark_real.elf --expected-rows 0 --capture-rows 1024 --allow-block-markers --max-seconds 8 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf`
- `bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r128-setc-ltui-1477-qemu-elf-xcheck --elf tests/benchmarks/build/coremark_real.elf --expected-rows 0 --capture-rows 1477 --allow-block-markers --max-seconds 8 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf`
- `bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r129-andiw-1479-qemu-elf-xcheck --elf tests/benchmarks/build/coremark_real.elf --expected-rows 0 --capture-rows 1479 --allow-block-markers --max-seconds 8 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf`
- `bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r130-mulw-csub-1481-qemu-elf-xcheck --elf tests/benchmarks/build/coremark_real.elf --expected-rows 0 --capture-rows 1481 --allow-block-markers --max-seconds 8 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf`
- `bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r131-andi-swi-ori-sub-mul-1595-qemu-elf-xcheck --elf tests/benchmarks/build/coremark_real.elf --expected-rows 0 --capture-rows 1595 --allow-block-markers --max-seconds 8 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf`
- `python3 tools/chisel/trace_schema_adapter.py --self-test`
