# LinxCore Branch Recovery Rules

This document defines the branch/block recovery behavior enforced by the canonical LinxCore backend.

## Rule 1: F3 captures BSTART metadata

When a `BSTART*` instruction is seen, frontend/decode captures and carries:

- `bstart_valid`
- `bstart_pc`
- `bstart_kind`
- `bstart_target`
- `pred_take`

These values are written into the PC-buffer table and reused during BRU validation/recovery.

## Rule 2: Prediction is backend-visible

`pred_take` is part of boundary metadata and is retained through ROB lifetime for block context.

Current baseline policy:

- conditional/return classes start with `pred_take=0`
- direct/call/indirect classes are not validated as conditional outcomes in this pass

## Rule 3: SETC.cond validation in BRU

BRU validates `setc.cond` by comparing:

- `actual_take` from execution result
- `pred_take` from active block metadata

On mismatch:

- compute recovery target from current block metadata
- request redirect to that target if valid

## Rule 4: BSTART/BSTOP resolve in ROB at D2

`BSTART/BSTOP` are normal ROB entries, but are marked D2-resolved:

- they do not go through IQ/FU execution lanes
- they can commit in-order like other ROB entries

## Rule 5: BSTOP-only release

Block-private resources are released only on `BSTOP` retire (`commit_is_bstop=1`):

- T/U private stacks/queues
- block argument validity state

No release is permitted on `BSTART`.

## Rule 6: BRU recovery target must be BSTART

Every BRU recovery target must map to a valid PC-buffer `is_bstart` entry.

If target validation fails:

- set precise trap on offending ROB entry
- `trap_valid=1`
- `trap_cause=TRAP_BRU_RECOVERY_NOT_BSTART (0x0000B001)`
- halt through normal trap flow

## Compatibility rule: BSTART.CALL and setret

- `BSTART.CALL` does not imply any implicit RA write.
- Return-address update is only from explicit `setret` instruction behavior.
