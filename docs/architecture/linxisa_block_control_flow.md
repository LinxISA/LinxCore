# LinxISA Block Control Flow (LinxCore/QEMU Parity)

This note records the block-structured control-flow contract used for lockstep
parity between the current LinxCore path and QEMU.

## Frontend supply and metadata ownership

The current LinxCore bring-up path is not driven by a functional IFU predictor.

- `/Users/zhoubot/LinxCore/src/top/modules/export_core.py` hard-cuts the
  functional IFU path for the active bring-up lane
- host/QEMU packets are accepted into the on-chip IB and presented to backend as
  F4-like packets
- architectural block metadata becomes authoritative only when decoded boundary
  information is committed into the live block state

That means architectural control flow is not chosen in the frontend, even when
frontend-side prediction metadata exists in older or auxiliary modules.

## BRU validation contract

- `setc.cond` executes in BRU and computes `actual_take`
- BRU compares `actual_take` against committed block metadata
  (`pred_take`, `kind`, `epoch`)
- mismatch records deferred correction state
- BRU does not directly redirect architectural PC

## Boundary-authoritative commit

Architectural redirect is consumed at boundary commit.

- boundary commit first considers an epoch-matched deferred correction
- if no correction applies, commit uses the current committed block state plus
  `commit_cond` and `commit_tgt`
- `BSTART` and `BSTOP` remain ROB-visible, D2-resolved boundary uops

## Split and macro behavior

- `BSTART` at block head opens the block
- `BSTART` seen in block body closes the previous block and may restart at the
  same PC so the same instruction becomes the next block head
- `FENTRY`, `FEXIT`, `FRET.RA`, and `FRET.STK` are treated as standalone macro
  block boundaries with fall-through committed metadata

This is the behavior required to avoid drift on block-structured control-flow
tests such as CoreMark.

## Dynamic target setup and call/ret rules

- `RET`, `IND`, and `ICALL` blocks require explicit `SETC.TGT` in the same block
- returning `BSTART.CALL` headers require adjacent `SETRET` or `C.SETRET`
- `BSTART.CALL` has no implicit `ra` write
- return labels come from explicit `SETRET`, not lexical fall-through

These rules match
`/Users/zhoubot/linx-isa/docs/reference/linxisa-call-ret-contract.md`.

## Recovery safety

- dynamic recovery targets must resolve to valid block-start metadata entries
- the current LinxCore implementation checks the PC buffer for `is_bstart`
- invalid known targets raise the precise trap
  `TRAP_BRU_RECOVERY_NOT_BSTART (0x0000B001)`

## QEMU mapping

Reference QEMU concepts:

- `linx_block_begin(...)`: block-head initialization
- `linx_gen_block_end(...)`: boundary termination and redirect selection
- `trans_c_bstart_std(...)`: split head versus in-body `BSTART`
- `trans_c_bstop(...)`: explicit boundary end
- `trans_setc_*` and `trans_setc_tgt`: condition and target updates consumed by
  boundary resolution
