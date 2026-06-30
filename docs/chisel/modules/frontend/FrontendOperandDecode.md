# FrontendOperandDecode

## Purpose

`FrontendOperandDecode` is the scalar field extractor behind
`FrontendDecodeStage`. It converts generated opcode metadata plus the raw
instruction into architectural source operands, destination operands, and
immediate payloads carried by `DecodedUop`.

This module is a D1 decode owner. It does not allocate LSIDs, split stores into
STA/STD uops, mutate block headers, rename architectural tags, allocate ROB
rows, or publish backend checkpoints.

The implementation is grounded in:

- `rtl/LinxCore/src/common/decode.py`
- `rtl/LinxCore/src/common/opcode_meta_gen.py`
- `model/LinxCoreModel/model/bctrl/spe/Decoder.cpp`
- `model/LinxCoreModel/model/bctrl/spe/SPERename.cpp`
- `model/LinxCoreModel/model/bctrl/spe/GPRRename.cpp`
- `model/LinxCoreModel/isa/ISACommon/GPR.h`
- `model/LinxCoreModel/isa/ISACommon/DecodeUtiles.h`
- `model/LinxCoreModel/isa/MInst.cpp`
- `model/LinxCoreModel/isa/codec/decodefiles/block16.decode`

## Interface

Inputs:

- `active`: qualified slot-valid bit from `FrontendDecodeStage`.
- `meta`: generated opcode metadata from `FrontendOpcodeDecodeTable`.
- `insn`: 64-bit raw instruction payload from the F4 slot.

Outputs:

- `src[3]`: architectural source operands in pyCircuit decode order:
  `srcl`, `srcr`, and `srcp`, classified by
  `FrontendRegAliasClassify.source()`.
- `dst[1]`: architectural destination operand from `regdst`.
- `imm`: 64-bit immediate payload.
- `immValid`: immediate payload validity.

Register operands use the model-derived scalar reg6 classification:

- source tags `0..23`: `OperandClass.P`;
- source tags `24..27`: `OperandClass.T`;
- source tags `28..31`: `OperandClass.U`;
- destination tags `0..23`: `DestinationKind.Gpr`;
- destination tag `31`: `DestinationKind.T`;
- destination tag `30`: `DestinationKind.U`.

Unsupported active aliases keep `valid=1` but emit `Invalid` / `None` with
`REG_INVALID` as `relTag`, so downstream owners can reject or route them
explicitly.

## Logic Design

The module uses generated `rdKind`, `rs1Kind`, `rs2Kind`, and `immKind`
metadata as the default extraction contract, then applies explicit pyCircuit
overrides for instructions whose decode fields are not expressible by those
four catalog kinds.

Implemented scalar field sources:

- 32-bit common fields:
  - `rd32 = insn[11:7]`
  - `rs1_32 = insn[19:15]`
  - `rs2_32 = insn[24:20]`
  - `srcp32 = insn[31:27]`
- 16-bit compressed fields:
  - `rd16 = insn[15:11]`
  - `rs16 = insn[10:6]`
- 48-bit HL fields:
  - prefix `pfx16 = insn[15:0]`
  - main word `main32 = insn[47:16]`
  - `rd_hl = main32[11:7]`

Implemented immediate forms:

- `UIMM12`
- `SIMM12_20_S12`
- `SIMM12_7_S5_25_7`
- `SIMM17` as a byte offset (`signext(bits[31:15]) << 1`)
- `SIMM25`
- `SIMM5_11_S5`
- `SIMM5_6_S5`
- `UIMM5`
- `FENTRY_UIMM_HI`
- `IMM20`, including `SETRET`'s unsigned shifted return-label form
- `IMM32` for `HL.LUI` and related HL immediate forms, packed as
  `Cat(pfx16[15:4], main32[31:12])` and sign-extended to 64 bits
- `SIMM_4_S12_31_17` for HL `BSTART` target byte offsets
- compressed `SIMM12` branch offsets
- explicit `shamt_20_25` for 32-bit shift-immediate opcodes whose generated
  catalog metadata currently reports `ImmNONE`

Explicit pyCircuit/model overrides currently cover:

- compact `C.SETRET`, which aliases the `C.MOVI` low-opcode form, forces
  destination architectural tag `x10/ra`, and uses `uimm5 << 1` as the
  PC-relative return-label immediate;
- reduced single-save `FENTRY`, which maps the saved GPR field to source 0,
  clears source 1 so QEMU's suppressed macro source shape is preserved, maps
  destination SP (`x1`) to the scalar GPR destination, and keeps the macro
  immediate as the stack-frame size for the reduced execute owner. The live
  top supplies old SP through a reduced SP shadow;
- fixed-destination compressed ALU/load forms that write architectural tag 31,
  classified as a T-queue destination;
- compressed stores and compare forms that use architectural tag 24 as a T-link
  source;
- macro forms `FENTRY`, `FEXIT`, `FRET.RA`, and `FRET.STK` that carry
  `rs1_32`, `rs2_32`, and macro immediate fields despite catalog
  `rs*_kind=NONE`. `FRET.STK` clears all visible sources in the reduced
  scalar row; its target is supplied by execute SETC/active-marker state, not
  by a decoded source operand;
- `BTEXT` source plus 25-bit immediate;
- `BLOAD`/`BSTORE` register fields;
- `MADD`, `MADDW`, `CSEL`, `BIOR`, and indexed stores that carry `srcp32`;
- R115 `SLLI`/`SRLI`/`SRAI` immediate extraction, where LinxCoreModel
  `@shift_i` uses `src1=%shamt_20_25` even though the generated Chisel opcode
  table still marks those opcodes as `ImmNONE`.
- R117 `C.LDI` immediate extraction, where bits `[15:11]` are a signed 5-bit
  doubleword offset and must be shifted left by 3 before execute. This
  intentionally overrides the generic `ImmSIMM5_11_S5` path used by ordinary
  compressed signed immediates.
- R126 PCR load immediate extraction. `LD.PCR` and the 32-bit PCR load family
  use the unshifted signed 17-bit field `insn[31:15]`, while `HL.*.PCR` load
  forms use the unshifted signed 29-bit `Cat(pfx16[15:4], main32[31:15])`.
  `BSTART` forms keep their shifted byte-target immediates, so PCR loads must
  not reuse the generic shifted `SIMM17` or HL `BSTART` paths.
- R128 `SETC.*I` immediate compare rows. The generated metadata exposes the
  encoded immediate compare register field through `rdKind=REG`, but the
  reduced scalar trace treats these rows as no-writeback condition producers.
  Decode therefore clears the visible destination for the current immediate
  SETC family while preserving source 0 and the decoded immediate. The
  promoted CoreMark case is `OP_SETC_LTUI` at `pc=0x4000d1e4`
  (`insn=0x00326075`), with source `x4`, immediate `3`, and no destination.

## Model Alignment

The C++ SPE `Decoder` calls `ConvertPOperand()` before forwarding instructions
to rename. That establishes the boundary this module serves: architectural
operand structure must exist before rename, but physical tag allocation belongs
to `SPERename` and `GPRRename`.

`FrontendOperandDecode` therefore emits architectural tags, class-relative
tags, operand classes, destination kinds, and immediates only. It leaves
`physTag`, `ready`, free-list state, map-queue state, `tSeq/uSeq`, store split
rewrite, and checkpoint restore to rename, dispatch, ROB, and recovery owners.

## Verification

Focused gate:

```bash
bash tools/chisel/run_chisel_tests.sh --only FrontendDecodeStage
```

The `FrontendDecodeStageSpec` reference cases cover:

- 32-bit register ALU (`ADD`)
- 32-bit unsigned immediate (`ADDI`)
- 32-bit signed immediate (`ANDI`)
- shift-immediate shamt extraction (`SLLI`)
- indexed store `srcp` (`SD`)
- direct block-start byte offset (`BSTART.DIRECT`)
- fixed-destination compressed ALU (`C.ADD`)
- compressed signed immediate (`C.MOVI`)
- compact return-label alias (`C.SETRET`)
- HL block-start byte offset (`HL.BSTART.STD CALL`)
- reduced single-save macro prologue (`FENTRY`)
- model-derived reg6 alias classification for scalar GPR, T-link, U-link, and
  T/U queue destination boundaries
- CoreMark `HL.LUI` at `0x4000551a`, which decodes destination tag `31`
  (`DestinationKind.T`) and immediate `1`
- CoreMark `C.LDI` at `0x40005556`, which decodes destination tag `31`,
  source tag `24` (`T0`), and immediate `0xfffffffffffffff0`
- CoreMark `HL.LD.PCR` at `0x40005700`, which decodes destination `x5` and
  immediate `0xa728`, while the paired `HL.BSTART` regression still decodes a
  shifted branch target
- CoreMark `FRET.STK` and ranged `FENTRY` macro rows, where visible source
  suppression is part of the QEMU-shaped reduced trace contract
- CoreMark `SETC.LTUI` at `0x4000d1e4`, where the encoded shamt/register field
  is not an architectural destination in the reduced no-writeback condition row

## Open Work

- Add cycle-level simulation checks once the Chisel test lane has a probe
  driver for `DecodedUop` payload values.
- Add T/U rename/queue consumption, SGPR aliases, and tile/vector operand
  classes.
- Promote shift/source-type sidebands (`srcr_type`, `shamt`) into generated
  metadata once the frontend table generator owns them directly.
- Add LSID allocation, D2 queueing, store split rewrite, block split queues,
  and rename/ROB admission in their own owner packets.
