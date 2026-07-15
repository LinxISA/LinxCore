# LinxCore Opcode Parity Baseline

This document captures the opcode/decode parity baseline used for the QEMU-aligned opcode refactor.

## Sources

- QEMU decode ground truth:
  - `emulator/qemu/target/linx/insn16.decode`
  - `emulator/qemu/target/linx/insn32.decode`
  - `emulator/qemu/target/linx/insn48.decode`
  - `emulator/qemu/target/linx/insn64.decode`
- LinxCore catalog:
  - `rtl/LinxCore/src/common/opcode_catalog.yaml`

## Measured Inventory

- QEMU unique mnemonics from decode trees: `645`
- LinxCore catalog rows: `648`
  - `645` QEMU mnemonics
  - `3` internal synthetic rows:
    - `internal_invalid`
    - `internal_c_bstart_std`
    - `internal_c_setret`
- Unique internal opcode symbols: `281`

## Category Partition (major)

- `BLOCK_BOUNDARY`
- `BLOCK_ARGS_DESC`
- `ALU_INT`
- `BRU_SETC_CMP`
- `LOAD`
- `STORE`
- `CMD_PIPE`
- `MACRO_TEMPLATE`
- `HL_PCR`
- `VECTOR`
- `FP_SYS`
- `COMPRESSED`
- `MISC`

## Canonical Symbol Mapping

The generated catalog maps the canonical block-header spellings directly:

- `b_text -> OP_BTEXT`
- `b_ior -> OP_BIOR`
- `b_iot -> OP_B_IOT`
- `b_catr -> OP_B_CATR`
- `b_datr -> OP_B_DATR`
- `bstart_{direct,cond,call} -> OP_BSTART_STD_*`
- `hl_bstart_std_* -> OP_BSTART_STD_*`
- Internal synthetic ops retained for bring-up:
  - `OP_C_BSTART_STD`
  - `OP_C_SETRET`

## Current Gate

- `python3 rtl/LinxCore/tools/generate/check_decode_parity.py --qemu-linx-dir emulator/qemu/target/linx --catalog rtl/LinxCore/src/common/opcode_catalog.yaml`
- Expected result: `decode parity check passed: 645 mnemonics`
