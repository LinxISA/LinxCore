# LinxCore Macro Instruction Generation

This document describes how macro-like instructions are generated and executed in the standalone LinxCore path.

Relevant files:

- `/Users/zhoubot/LinxCore/src/common/decode.py`
- `/Users/zhoubot/LinxCore/src/bcc/backend/code_template_unit.py`
- `/Users/zhoubot/LinxCore/src/bcc/backend/backend.py`

## 1) Macro classes

Current macro classes:

- Frame template macros:
  - `FENTRY`
  - `FEXIT`
  - `FRET_STK`
  - `FRET_RA`

## 2) Frame template decode and execute model

Frame templates decode as normal ops and enter ROB. When a frame template reaches ROB head and is done, backend starts `CodeTemplateUnit`, blocks IFU, and emits one template-uop per cycle.

CTU uop kinds:

- `SP_SUB`
- `SP_ADD`
- `STORE`
- `LOAD`
- `SETC_TGT`

Address generation:

- Save path (`FENTRY`): `addr = sp_base + (stack_size - (i + 1) * 8)`
- Restore path (`FEXIT/FRET.*`): `addr = sp_base - (i + 1) * 8`

## 3) Normative expansion order

`f.entry [s1 ~ s8], sp!, 256`

1. `subi sp, 256, -> sp`
2. `sdi s1, [sp, 248]`
3. `sdi s2, [sp, 240]`
4. `sdi s3, [sp, 232]`
5. `sdi s4, [sp, 224]`
6. `sdi s5, [sp, 216]`
7. `sdi s6, [sp, 208]`
8. `sdi s7, [sp, 200]`
9. `sdi s8, [sp, 192]`

`f.exit [s0 ~ s6], sp!, 72` (no wrap)

1. `addi sp, 72, -> sp`
2. `ldi [sp, -8],  -> s0`
3. `ldi [sp, -16], -> s1`
4. `ldi [sp, -24], -> s2`
5. `ldi [sp, -32], -> s3`
6. `ldi [sp, -40], -> s4`
7. `ldi [sp, -48], -> s5`
8. `ldi [sp, -56], -> s6`

`f.ret.stk [ra ~ s5], sp!, 64` (no wrap)

1. `addi sp, 64, -> sp`
2. `ldi [sp, -8], -> ra`
3. `setc.tgt ra`
4. `ldi [sp, -16], -> s0`
5. `ldi [sp, -24], -> s1`
6. `ldi [sp, -32], -> s2`
7. `ldi [sp, -40], -> s3`
8. `ldi [sp, -48], -> s4`
9. `ldi [sp, -56], -> s5`

`f.ret.ra [s0 ~ s6], sp!, 64` (no wrap)

1. `setc.tgt ra`
2. `addi sp, 64, -> sp`
3. `ldi [sp, -8],  -> s0`
4. `ldi [sp, -16], -> s1`
5. `ldi [sp, -24], -> s2`
6. `ldi [sp, -32], -> s3`
7. `ldi [sp, -40], -> s4`
8. `ldi [sp, -48], -> s5`
9. `ldi [sp, -56], -> s6`

## 4) BSTART CALL generation contract

`BSTART_STD_CALL` is treated as boundary control only.

Rule:

- There is no implicit RA write on `BSTART_STD_CALL`.
- Return-address updates must come from explicit `setret` instruction execution/retire.

## 5) Trace and co-sim visibility

Template expansion uops are emitted as retire-visible events in co-sim trace path, with fields filled by operation type:

- SP adjust uops: `wb_valid=1`, `wb_rd=sp`
- STORE uops: `mem_valid=1`, `mem_is_store=1`
- LOAD uops: `mem_valid=1`, `mem_is_store=0`, plus load writeback
- `SETC_TGT`-only uops: metadata/control event

This behavior is compared lockstep against QEMU trace by:

- `/Users/zhoubot/LinxCore/cosim/linxcore_lockstep_runner.cpp`
