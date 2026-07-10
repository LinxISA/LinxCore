# LinxISA Block Control Flow (LinxCore/QEMU Parity)

Canonical contract summary:

- `rtl/LinxCore/docs/architecture/microarchitecture.md`

This note records the block-structured control-flow contract used for lockstep parity.

## 1) IFU prediction contract

- IFU keeps conservative static prediction in this milestone.
- `BSTART` metadata (`kind/target/pred_take`) is carried into ROB context.
- Prediction is advisory; architectural PC is committed by boundary authority.

## 2) BRU validation contract

- `setc.cond` executes in BRU and computes `actual_take`.
- BRU compares `actual_take` vs carried `pred_take`.
- On mismatch, BRU writes deferred correction (`br_corr_pending`, `br_corr_epoch`, `br_corr_take`, `br_corr_target`, checkpoint id).
- BRU does not directly redirect architectural PC.

## 3) Boundary-authoritative commit

- Architectural redirect is consumed at boundary commit.
- Boundary chooser applies, in order:
  1. epoch-matched deferred correction (if pending),
  2. current block state (`br_kind`, `commit_cond`, `commit_tgt`, static target).
- `BSTART/BSTOP` are D2-classified boundary entries; D3 atomically admits their
  ROB/BROB/checkpoint resources before they become ROB-visible.

## 4) BSTART split behavior

- `BSTART` at block head opens a block.
- `BSTART` encountered in block body terminates previous block and may restart fetch at the same PC (`C.BSTART.STD` split behavior), then execute again as block head.
- The same-PC head reentry carries the already allocated `(STID,BID)`,
  checkpoint, and boundary epoch. It does not allocate/retire a duplicate
  marker or fire scalar completion a second time.

This is the key behavior needed to avoid branch drift in CoreMark.

## 5) Recovery safety

- Recovery targets must resolve to legal block-start metadata: a `BSTART*`
  entry or a valid template start (`FENTRY`, `FEXIT`, `FRET.*`).
- Invalid target raises precise architectural `E_BLOCK(EC_CFI)` with
  `EC_CFI_KIND=CFI_BAD_TARGET`, `TRAPARG0=source PC/TPC`, and `ECSTATE.BI=0`.
- `TRAP_BRU_RECOVERY_NOT_BSTART (0x0000B001)` is a legacy internal diagnostic
  only and must be mapped to that architectural envelope.

## 6) QEMU mapping (reference)

- `linx_block_begin(...)`: block-head initialization.
- `linx_gen_block_end(...)`: boundary termination/redirect selection.
- `trans_c_bstart_std(...)`: split head vs in-body behavior.
- `trans_c_bstop(...)`: explicit boundary end.
- `trans_setc_*` / `trans_setc_tgt`: condition/target updates used by boundary resolution.
