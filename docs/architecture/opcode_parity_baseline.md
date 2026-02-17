# LinxCore Opcode Parity Baseline

This document captures the opcode/decode parity baseline used for the QEMU-aligned opcode refactor.

## Sources

- QEMU decode ground truth:
  - `/Users/zhoubot/qemu/target/linx/insn16.decode`
  - `/Users/zhoubot/qemu/target/linx/insn32.decode`
  - `/Users/zhoubot/qemu/target/linx/insn48.decode`
  - `/Users/zhoubot/qemu/target/linx/insn64.decode`
- LinxCore catalog:
  - `/Users/zhoubot/LinxCore/src/common/opcode_catalog.yaml`

## Measured Inventory

- QEMU unique mnemonics from decode trees: `281`
- LinxCore catalog rows: `284`
  - `281` QEMU mnemonics
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

## Known Legacy Compatibility Mapping

To keep existing LinxCore backend behavior stable during renumbering:

- `b_text -> OP_BTEXT`
- `b_ior -> OP_BIOR`
- `b_iot -> OP_BLOAD`
- `b_ioti -> OP_BSTORE`
- `bstart_{direct,cond,call} -> OP_BSTART_STD_*`
- `hl_bstart_std_* -> OP_BSTART_STD_*`
- Internal synthetic ops retained for bring-up:
  - `OP_C_BSTART_STD`
  - `OP_C_SETRET`

## Current Gate

- `python3 /Users/zhoubot/LinxCore/tools/generate/check_decode_parity.py`
- Expected result: `decode parity check passed: 281 mnemonics`
