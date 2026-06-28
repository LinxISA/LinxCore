# FrontendOperandDecode

## Purpose

`FrontendOperandDecode` is the scalar field extractor behind
`FrontendDecodeStage`. It converts generated opcode metadata plus the raw
instruction into the architectural source tags, destination tag, and immediate
payload carried by `DecodedUop`.

This module is a D1 decode owner. It does not allocate LSIDs, split stores into
STA/STD uops, mutate block headers, rename architectural tags, allocate ROB
rows, or publish backend checkpoints.

The implementation is grounded in:

- `rtl/LinxCore/src/common/decode.py`
- `rtl/LinxCore/src/common/opcode_meta_gen.py`
- `model/LinxCoreModel/model/bctrl/spe/Decoder.cpp`
- `model/LinxCoreModel/model/bctrl/spe/SPERename.cpp`
- `model/LinxCoreModel/model/bctrl/spe/GPRRename.cpp`

## Interface

Inputs:

- `active`: qualified slot-valid bit from `FrontendDecodeStage`.
- `meta`: generated opcode metadata from `FrontendOpcodeDecodeTable`.
- `insn`: 64-bit raw instruction payload from the F4 slot.

Outputs:

- `src[3]`: architectural source operands in pyCircuit decode order:
  `srcl`, `srcr`, and `srcp`.
- `dst[1]`: architectural destination operand from `regdst`.
- `imm`: 64-bit immediate payload.
- `immValid`: immediate payload validity.

All emitted register operands currently use `OperandClass.P` /
`DestinationKind.Gpr` in the reg6 namespace. Unsupported or inactive operands
emit `REG_INVALID`.

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
- 48-bit HL.LUI fields:
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
- `IMM32` for `HL.LUI`
- compressed `SIMM12` branch offsets

Explicit pyCircuit overrides currently cover:

- fixed-destination compressed ALU/load forms that write architectural tag 31;
- compressed stores that use architectural tag 24 as the data source;
- macro forms `FENTRY`, `FEXIT`, `FRET.RA`, and `FRET.STK` that carry
  `rs1_32`, `rs2_32`, and macro immediate fields despite catalog
  `rs*_kind=NONE`;
- `BTEXT` source plus 25-bit immediate;
- `BLOAD`/`BSTORE` register fields;
- `MADD`, `MADDW`, `CSEL`, `BIOR`, and indexed stores that carry `srcp32`.

## Model Alignment

The C++ SPE `Decoder` calls `ConvertPOperand()` before forwarding instructions
to rename. That establishes the boundary this module serves: architectural
operand structure must exist before rename, but physical tag allocation belongs
to `SPERename` and `GPRRename`.

`FrontendOperandDecode` therefore emits architectural tags and immediates only.
It leaves `physTag`, `ready`, free-list state, map-queue state, `tSeq/uSeq`,
store split rewrite, and checkpoint restore to rename, dispatch, ROB, and
recovery owners.

## Verification

Focused gate:

```bash
bash tools/chisel/run_chisel_tests.sh --only FrontendDecodeStage
```

The `FrontendDecodeStageSpec` reference cases cover:

- 32-bit register ALU (`ADD`)
- 32-bit unsigned immediate (`ADDI`)
- 32-bit signed immediate (`ANDI`)
- indexed store `srcp` (`SD`)
- direct block-start byte offset (`BSTART.DIRECT`)
- fixed-destination compressed ALU (`C.ADD`)
- compressed signed immediate (`C.MOVI`)

## Open Work

- Add cycle-level simulation checks once the Chisel test lane has a probe
  driver for `DecodedUop` payload values.
- Add T/U/SGPR and tile/vector operand classes.
- Add shift/source-type sidebands (`srcr_type`, `shamt`) instead of only
  reg/immediate fields.
- Add LSID allocation, D2 queueing, store split rewrite, block split queues,
  and rename/ROB admission in their own owner packets.
