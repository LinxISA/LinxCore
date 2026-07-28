# LinxCore CodeTemplateUnit

Canonical contract summary:

- `rtl/LinxCore/docs/architecture/microarchitecture.md`

## Purpose

`CodeTemplateUnit` expands template blocks (`FENTRY`, `FEXIT`, `FRET_RA`,
`FRET_STK`) into an ordered canonical-child stream for the owning STID. It is
external to IFU and OOO. `OooCtuIngressBridge` claims one D1-classified raw
parent exactly once, retains an expansion lease, and inserts CTU children into
the OOO ingress before D2; CTU is not a post-D3 allocator or
architectural-effect owner.

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

1. OOO D1 decodes only far enough to classify the fixed-64-bit template parent
   and retains the complete raw parent/prediction sideband. The production
   `OooCtuIngressBridge` claims it after D1 and before D2. I-F4 predecode
   remains limited to BSTART/BSTOP.
2. CTU computes the exact ordered child recipe and resource envelope. The
   bridge assigns a retained
   `{PE,STID,parent,templateGroupId,generation}` ingress lease before the first
   child is visible.
3. CTU emits children carrying
   `{PE,STID,parentId,templateGroupId,generation,ordinal,count}`. Children enter
   normal D1 validation, D2 virtual grouping, D3 rename/reservation, and S1
   publication; CTU never allocates RID, BID, PTag, IQ, LSID, or PC state.
4. Children pass through normal execute/LSU, precise trap, commit, and trace
   ownership. The final child gates the one architectural parent retirement
   record, even when children span several RID groups.
5. Recovery prepares/fences CTU before O3 admission, then cancels queued/active
   expansion on the exact O3 common apply. Abort only releases the fence; a
   stale post-cancel plan or child cannot advance the lease.
6. No CTU child writes PRF, D-memory, `setc`, ROB, BROB, or architectural state
   directly.
7. CTU backpressure is per STID; it must not stall unrelated STIDs.

The production OOO-side claim/lease/reinsertion and recovery boundary is now
implemented in `chisel/src/main/scala/linxcore/ooo/OooCtuIngressBridge.scala`
and composed by `OooIfuD1Ingress`. `OooS1GroupedRob` permits a zero-parent row
only for an exact nonfinal template continuation and requires the final child
to own the single parent. Focused UT/IT cover six-child multi-RID expansion,
stale/order rejects, STID isolation, and common recovery apply.

The external recipe engine and its production-top connection remain O9 work.
Until that promotion, the legacy `LinxCoreBackend` integration uses CTU outputs
to:

- gate pipeline run (`can_run = base_can_run & ~block_ifu`)
- arbitrate template load/store use of the D-memory path
- drive template PRF updates (SP adjust and register restore)
- drive explicit `setc.tgt` updates for `FRET_STK/FRET_RA`
- preserve commit/redirect semantics

Those direct-write/global-block paths and the current Template-D3 reservation
modules are migration oracles only. They are forbidden from the production
composition once the external producer is connected to `OooCtuIngressBridge`.

Integration point:

- `rtl/LinxCore/src/bcc/backend/backend.py`
