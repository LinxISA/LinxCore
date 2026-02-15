# LinxCore CodeTemplateUnit

## Purpose

`CodeTemplateUnit` expands template blocks (`FENTRY`, `FEXIT`, `FRET_RA`, `FRET_STK`) into a one-uop-per-cycle stream and asserts an IFU block while template execution is active.

Source:

- `/Users/zhoubot/LinxCore/src/bcc/backend/code_template_unit.py`
- `/Users/zhoubot/LinxCore/docs/linxcore_macro_instruction_generation.md`

Generated split modules:

- `/Users/zhoubot/LinxCore/generated/cpp/linxcore_top/CodeTemplateUnit__*.hpp`
- `/Users/zhoubot/LinxCore/generated/verilog/linxcore_top/CodeTemplateUnit__*.v`

## Uop model

The unit emits one template-uop per active cycle:

- `SP_SUB`: frame allocation at `FENTRY` start
- `STORE`: save register during `FENTRY` memory phase
- `SP_ADD`: stack release (`FEXIT`, `FRET_STK`) or `FRET_RA` phase-2
- `LOAD`: restore register during `FEXIT/FRET.*` memory phase
- `SETC_TGT`: return target set for `FRET_STK/FRET_RA`

Address model:

- `FENTRY` save address: `addr = sp_base + (stack_size - (i + 1) * 8)`
- `FEXIT/FRET.*` restore address: `addr = sp_base - (i + 1) * 8`

Primary outputs:

- `block_ifu` (frontend stall)
- `uop_valid`, `uop_kind`, `uop_reg`, `uop_addr`, `uop_size`
- `uop_is_sp_sub`, `uop_is_store`, `uop_is_load`, `uop_is_sp_add`, `uop_is_setc_tgt`

## Expansion contract

`f.entry [s1 ~ s8], sp!, 256`:

1. `subi sp, 256, -> sp`
2. `sdi s1, [sp, 248]`
3. `sdi s2, [sp, 240]`
4. `sdi s3, [sp, 232]`
5. `sdi s4, [sp, 224]`
6. `sdi s5, [sp, 216]`
7. `sdi s6, [sp, 208]`
8. `sdi s7, [sp, 200]`
9. `sdi s8, [sp, 192]`

`f.exit [s0 ~ s6], sp!, 72` (no wrap):

1. `addi sp, 72, -> sp`
2. `ldi [sp, -8],  -> s0`
3. `ldi [sp, -16], -> s1`
4. `ldi [sp, -24], -> s2`
5. `ldi [sp, -32], -> s3`
6. `ldi [sp, -40], -> s4`
7. `ldi [sp, -48], -> s5`
8. `ldi [sp, -56], -> s6`

`f.ret.stk [ra ~ s5], sp!, 64` (no wrap):

1. `addi sp, 64, -> sp`
2. `ldi [sp, -8], -> ra`
3. `setc.tgt ra`
4. `ldi [sp, -16], -> s0`
5. `ldi [sp, -24], -> s1`
6. `ldi [sp, -32], -> s2`
7. `ldi [sp, -40], -> s3`
8. `ldi [sp, -48], -> s4`
9. `ldi [sp, -56], -> s5`

`f.ret.ra [s0 ~ s6], sp!, 64` (no wrap):

1. `setc.tgt ra`
2. `addi sp, 64, -> sp`
3. `ldi [sp, -8],  -> s0`
4. `ldi [sp, -16], -> s1`
5. `ldi [sp, -24], -> s2`
6. `ldi [sp, -32], -> s3`
7. `ldi [sp, -40], -> s4`
8. `ldi [sp, -48], -> s5`
9. `ldi [sp, -56], -> s6`

## 中文语义记忆点

按约定，模板块展开顺序固定为：

- `f.entry [s1~s8], sp!, 256`:
  - 先 `subi sp, 256 -> sp`
  - 再按 `s1..s8` 顺序保存到 `[sp + 248]..[sp + 192]`
- `f.exit [s0~s6], sp!, 72`:
  - 先 `addi sp, 72 -> sp`
  - 再按 `s0..s6` 顺序从 `[sp - 8]..[sp - 56]` 恢复
- `f.ret.stk [ra~s5], sp!, 64`:
  - 先 `addi sp, 64 -> sp`
  - `ldi [sp, -8] -> ra`
  - `setc.tgt ra`
  - 再恢复 `s0..s5`
- `f.ret.ra [s0~s6], sp!, 64`:
  - 先 `setc.tgt ra`
  - 再 `addi sp, 64 -> sp`
  - 再恢复 `s0..s6`

另外，`BSTART CALL` 保持既有边界/返回目标语义，不改变本页定义的模板块展开顺序。

## Backend integration

`LinxCoreBackend` uses `CodeTemplateUnit` outputs to:

- gate pipeline run (`can_run = base_can_run & ~block_ifu`)
- arbitrate template load/store use of the D-memory path
- drive template PRF updates (SP adjust and register restore)
- drive explicit `setc.tgt` updates for `FRET_STK/FRET_RA`
- preserve commit/redirect semantics

Integration point:

- `/Users/zhoubot/LinxCore/src/bcc/backend/backend.py`
