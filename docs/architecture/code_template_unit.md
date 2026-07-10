# LinxCore CodeTemplateUnit

Canonical contract summary:

- `rtl/LinxCore/docs/architecture/microarchitecture.md`

## Purpose

`CodeTemplateUnit` expands template blocks (`FENTRY`, `FEXIT`, `FRET_RA`,
`FRET_STK`) into a one-uop-per-cycle stream for the owning STID. It is an
internal producer at F4/IB, not a serial F5 stage.

Source:

- `rtl/LinxCore/src/bcc/backend/code_template_unit.py`
- `rtl/LinxCore/docs/architecture/macro_instruction_generation.md`

Generated split modules (on demand; they may be absent before generation):

- `rtl/LinxCore/generated/verilog/linxcore_top/CodeTemplateUnit__*.v`

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

- owning `uop_stid`, `uop_bid`, and checkpoint identity
- per-STID expansion hold/backpressure
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

Canonical target integration is:

1. F4 predecode marks a template parent, sends it through D1/D2, and holds
   later ordinary records only for that STID. F4 does not allocate BID.
2. D3 atomically reserves one `(STID,BID)`, checkpoint/resource credits, N
   child ROB rows, and a final template completion/trace row. The child count
   and resource demand are derived from the classified template.
3. CTU fills the reserved child rows in program order. Children reuse and
   validate the parent's `(STID,BID)`; they allocate ordinary rename/IQ/LSU
   resources from the reservation and never allocate a new BROB slot unless a
   child is itself an architecturally legal new boundary.
4. Children pass through normal execute/LSU, precise trap, commit, and trace
   ownership. The final template row becomes complete only after expansion and
   all child rows complete, and therefore retires after all child side effects.
5. A flush removes filled and unfilled reserved rows, cancels CTU state by STID
   and checkpoint, and cannot expose partial wrong-path side effects.
6. No CTU child writes PRF, D-memory, `setc`, or architectural state directly.
7. Global frontend serialization is legal only in an explicitly single-STID
   configuration; an SMT implementation must not stall unrelated STIDs.

The current reduced `LinxCoreBackend` integration instead uses CTU outputs to:

- gate pipeline run (`can_run = base_can_run & ~block_ifu`)
- arbitrate template load/store use of the D-memory path
- drive template PRF updates (SP adjust and register restore)
- drive explicit `setc.tgt` updates for `FRET_STK/FRET_RA`
- preserve commit/redirect semantics

Those direct-write/global-block paths are implementation evidence only. They
must converge to the canonical path above before claiming precise multi-STID
LinxCore behavior.

Integration point:

- `rtl/LinxCore/src/bcc/backend/backend.py`
