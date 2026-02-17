# LinxCore Branch Recovery Rules

This document defines the canonical LinxISA block-control behavior implemented in:

- `/Users/zhoubot/LinxCore/src/bcc/backend/engine.py`
- `/Users/zhoubot/LinxCore/src/bcc/backend/rob.py`
- `/Users/zhoubot/LinxCore/src/bcc/backend/decode.py`

## Rule 1: Boundary-authoritative redirect

Architectural redirect is resolved at block boundary commit.

- BRU `setc.cond` does not directly change architectural PC.
- BRU mismatch only records deferred correction (`br_corr_*`) and epoch tags.
- Boundary commit consumes deferred correction if epoch-matched.

## Rule 2: BSTART head vs in-body behavior

LinxCore tracks whether next instruction is block-head (`state.block_head`).

- `BSTART` at block head: block begin marker (no old-block redirect).
- `BSTART` in block body: behaves as old-block terminator; for `C.BSTART.STD` it may fall through to the same PC and restart block decode.

This matches the QEMU split behavior where a BSTART seen in-body can first close the previous block, then be re-executed as the next block head.

## Rule 3: BSTART/BSTOP are ROB-visible and D2-resolved

`BSTART/BSTOP` allocate ROB entries and are marked resolved at D2:

- no IQ/FU issue required,
- still retire in-order with full commit trace payload.

## Rule 4: SETC validation contract

`setc.cond` is validated in BRU against carried prediction metadata:

- `actual_take` from BRU execute,
- `pred_take` from active block context.

Mismatch sets deferred correction state, consumed only by boundary commit.

## Rule 5: BSTOP-only block-private release

Block-private resources are released only when `BSTOP` retires (`commit_is_bstop=1`):

- T/U queues,
- BARG valid state.

No release is allowed on `BSTART`.

## Rule 6: Recovery target safety and precise trap

Any BRU/deferred correction target must map to valid BSTART metadata in PC buffer.

If target is invalid:

- precise trap is raised,
- `trap_cause = TRAP_BRU_RECOVERY_NOT_BSTART (0x0000B001)`,
- trap is attributed to the offending ROB entry.

## Rule 7: No implicit RA write on BSTART.CALL

`BSTART.CALL` does not implicitly write RA.
RA updates are only from explicit `setret`/`setc.tgt` behavior.
