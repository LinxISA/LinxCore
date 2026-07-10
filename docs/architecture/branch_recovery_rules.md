# LinxCore Branch Recovery Rules

Canonical contract summary:

- `rtl/LinxCore/docs/architecture/microarchitecture.md`

This document defines the canonical LinxISA block-control behavior implemented in:

- `rtl/LinxCore/src/bcc/backend/engine.py`
- `rtl/LinxCore/src/bcc/backend/rob.py`
- `rtl/LinxCore/src/bcc/backend/decode.py`

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

The first D3 admission allocates the new `(STID,BID)` and one marker ROB row.
If boundary commit restarts at the same PC, R4/F0 carries a marker-reentry token
containing STID, BID, checkpoint, and boundary epoch. D1 validates the refetched
marker against that token, reestablishes head context, and emits no second
ROB/BROB allocation or commit row. The original marker fires old-block
`scalar_done` once; reentry never completes the new block or duplicates trace.

## Rule 3: BSTART/BSTOP are D2-classified and D3-admitted

`BSTART/BSTOP` are completely classified at D2. D3 atomically allocates their
ROB/BROB/checkpoint resources and installs the already-resolved boundary row:

- no IQ/FU issue required,
- still retire in-order with full commit trace payload.

## Rule 4: SETC validation contract

`setc.cond` is validated in BRU against carried prediction metadata:

- `actual_take` from BRU execute,
- `pred_take` from active block context.

Mismatch sets deferred correction state, consumed only by boundary commit.

## Rule 5: BID-qualified block-private release

Block-private resources are released only by the ordered local block-commit
event for the completed BID/STID:

- an explicit `BSTOP` may complete the active block;
- the next `BSTART` may implicitly terminate and commit the prior block;
- a template block commits at its completion boundary.

Raw `BSTART` decode or retire is not itself permission to clear the new block's
state. The prior block's relation cleanup and BID-qualified local block commit
must occur first. T/U rows are marked retired before they are freed, and only
consecutive matching rows at the local deallocation head may be released.

## Rule 6: Recovery target safety and precise trap

Any BRU/deferred correction target must map to legal block-start metadata in
the PC buffer: a `BSTART*` form or a valid template start such as `FENTRY`,
`FEXIT`, or `FRET.*`.

If target is invalid:

- raise the precise architectural `E_BLOCK(EC_CFI)` envelope with
  `EC_CFI_KIND=CFI_BAD_TARGET`,
- set `TRAPARG0` to the source PC/TPC and `ECSTATE.BI=0`,
- attribute the trap to the offending ROB entry and commit none of its
  architectural effects.

`TRAP_BRU_RECOVERY_NOT_BSTART (0x0000B001)` may remain an internal legacy
diagnostic code, but commit/trap export must map it to the architectural
envelope above; it is not an ISA trap encoding.

## Rule 7: No implicit RA write on BSTART.CALL

`BSTART.CALL` does not implicitly write RA.
Return-label state is created only by an explicit returning-call encoding or an
adjacent `SETRET`/`C.SETRET`/`HL.SETRET` materialization. A fused returning
header is an explicit encoded owner; it does not imply that every CALL marker
writes RA.
