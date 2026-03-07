# LinxCore Branch and Block Recovery Rules

This document defines the canonical LinxCore block-control behavior implemented
by the current backend path:

- `/Users/zhoubot/LinxCore/src/bcc/backend/modules/commit_slot_step.py`
- `/Users/zhoubot/LinxCore/src/bcc/backend/modules/block_meta_step.py`
- `/Users/zhoubot/LinxCore/src/bcc/backend/modules/commit_redirect.py`
- `/Users/zhoubot/LinxCore/src/bcc/backend/modules/recovery_checks.py`
- `/Users/zhoubot/LinxCore/src/bcc/backend/modules/trace_export_core.py`
- `/Users/zhoubot/LinxCore/src/common/decode.py`
- `/Users/zhoubot/LinxCore/src/common/exec_uop.py`

## Boundary-authoritative redirect

Architectural redirect is resolved at commit-side block boundary handling.

- `setc.cond` executes in BRU and may record deferred correction
  (`br_corr_pending`, `br_corr_take`, `br_corr_target`, `br_corr_epoch`,
  `br_corr_checkpoint_id`)
- execution does not directly rewrite architectural control flow
- `commit_slot_step.py` selects the redirect target at boundary commit
- `commit_redirect.py` packages the already-selected redirect result; it is not
  a second control-flow decision point

## BSTART head versus in-body behavior

Committed block state keeps an explicit `block_head` bit and active block
identity.

- `BSTART` at block head opens or re-opens the committed block context
- `BSTART` seen in block body terminates the previous committed block
- a mid-block `BSTART` that is not taken can immediately seed the next block
  metadata
- the fall-through case for a mid-block `BSTART` may restart at the same PC so
  the instruction can be observed again as the next block head

This is the split behavior needed to keep LinxCore aligned with QEMU on block
boundaries such as `C.BSTART.STD`.

## ROB-visible boundary uops

`BSTART` and `BSTOP` are architectural boundary uops, not hidden control
markers.

- they allocate ROB entries
- they are resolved in decode/D2 rather than waiting for a normal execute unit
- they still retire in order and carry full commit-visible metadata

The same commit path also treats `FENTRY`, `FEXIT`, `FRET.RA`, and `FRET.STK`
as block-boundary-visible macro control instructions.

## Explicit target materialization

LinxCore follows the Linx ISA rule that dynamic targets are explicit.

- `RET`, `IND`, and `ICALL` blocks require `SETC.TGT` in the same block
- returning `BSTART.CALL` headers require adjacent `SETRET` or `C.SETRET`
- `BSTART.CALL` itself does not implicitly write `ra`
- `SETRET` computes the explicit return label, and `SETC.TGT` selects the
  dynamic branch/return target source

If the program violates these rules, that is an ISA contract violation, not a
microarchitectural shortcut.

## Macro block treatment

Macro control instructions are treated as standalone fall-through blocks in the
committed block metadata.

- `FENTRY`, `FEXIT`, `FRET.RA`, and `FRET.STK` are considered block boundaries
- on macro entry, live committed block metadata becomes conservative
  fall-through metadata rooted at that macro PC
- after a taken boundary or a committed `BSTOP`, live block metadata is reset to
  conservative fall-through state until the next `BSTART` head installs new
  decoded metadata

## Recovery target safety

Deferred correction is only valid when the target is a legal block boundary.

- `recovery_checks.py` validates `setc.cond` mismatch only for the relevant
  boundary kinds (`COND` and `RET`) and only when the epoch matches
- the target must be known in the PC buffer and tagged as `is_bstart`
- a known target that is not a legal block start raises a precise trap:
  `TRAP_BRU_RECOVERY_NOT_BSTART (0x0000B001)`
- an unknown target does not authorize a correction; the correction path only
  arms when the target metadata is present and legal

## Block-private release and block retire

Block-private state is released only by `BSTOP`, never by `BSTART`.

- `T/U` queues and block-private argument state are released on `BSTOP` commit
- `BSTOP` retirement is additionally gated by BROB state for engine-backed
  blocks
- when `BSTOP` commits, commit emits the BROB retire event for the active block
  BID

This keeps block resource lifetime aligned with the ISA-level rule that `BSTART`
starts a new block but does not implicitly retire the previous block's private
resources.
