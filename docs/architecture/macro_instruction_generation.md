# LinxCore Macro Instruction Generation

This document records expansion order plus the reduced standalone
implementation. The canonical target lifecycle is
`code_template_unit.md`: F4 marks the parent, D3 reserves child rows plus a
final template row, and CTU children use normal precise backend ownership.

Relevant files:

- `rtl/LinxCore/src/common/decode.py`
- `rtl/LinxCore/src/bcc/backend/code_template_unit.py`
- `rtl/LinxCore/src/bcc/backend/backend.py`

## 1) Macro classes

Current macro classes:

- Frame template macros:
  - `FENTRY`
  - `FEXIT`
  - `FRET_STK`
  - `FRET_RA`

## 2) Current reduced frame-template model

In the current reduced implementation, frame templates decode as normal ops
and enter ROB. When one reaches ROB head and is done, backend starts
`CodeTemplateUnit`, globally blocks IFU, and emits one template-uop per cycle.
The target instead uses the D3 atomic parent/child reservation and final-row
ordering defined in `code_template_unit.md`.

That ROB-head/global-IFU-block/direct-child path is reduced implementation
evidence, not the normative multi-STID lifecycle. It must not bypass D3 atomic
reservation, child ROB/rename/LSU ownership, final-template-row ordering, or
checkpoint recovery.

CTU uop kinds:

- `SP_SUB`
- `SP_ADD`
- `STORE`
- `LOAD`
- `SETC_TGT`

Address generation:

- Save path (`FENTRY`): `addr = sp_base + (stack_size - (i + 1) * 8)`
- Restore path (`FEXIT/FRET.*`): `addr = sp_base - (i + 1) * 8`

Frame-adjust policy:

- Default is immediate-only semantics (`stack_size`) to match QEMU.
- Optional compatibility addend is `callframe_size`, default `0`.
- Override (opt-in): `LINXCORE_CALLFRAME_SIZE=<non-negative multiple of 8>`.

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

- `rtl/LinxCore/cosim/linxcore_lockstep_runner.cpp`

## 6) Interaction with block redirect authority

Template expansion does not bypass branch/block control rules:

- template-generated `setc.tgt` updates block target state like normal control ops,
- architectural redirect is still boundary-authoritative at commit,
- macro expansion does not create an alternate direct-PC override path.
