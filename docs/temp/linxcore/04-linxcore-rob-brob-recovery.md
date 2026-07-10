# ROB, BROB, Recovery, and Privilege

This document covers instruction retirement, block retirement, recovery, traps, privilege transitions, and interrupt handling for LinxCore.

## 1 Retirement Model

LinxCore has two related retirement domains:

- the instruction ROB, which preserves precise instruction retirement;
- the BROB, which preserves block-command ordering and engine completion.

The two domains must agree at architectural boundaries. A scalar instruction may execute out of order, but architectural control, traps, interrupt entry, memory visibility, and block transition behavior are ordered by ROB/BROB retirement.

## 2 ROB Responsibilities

The ROB must:

- allocate entries in program order;
- track `rid`, `bid`, `lsid`, PC, instruction length, and raw instruction evidence;
- collect execution resolve information;
- track exception and trap metadata;
- determine commit readiness;
- retire up to the current commit-width limit;
- initiate precise flush on branch recovery, trap, interrupt, or fault;
- release `P` MapQ entries and related physical resources at commit or flush;
- coordinate release of LSQ/STQ resources;
- expose commit trace fields.

`BSTART` and `BSTOP` are ROB-visible boundary uops resolved by `D2`. They do not require IQ or FU issue to become architecturally visible.

## 3 Boundary Uops

Boundary uops carry:

- `sob` and `eob` flags;
- block type;
- branch/transition kind;
- predicted-taken state;
- boundary target if available;
- current `bid`;
- next/new `bid` for `BSTART`;
- descriptor context when needed.

`BSTART` at a block head opens a block. `BSTART` encountered in-body terminates the previous block and may restart at the same PC as the next block head.

`BSTOP` retires only when the active block is not blocked by engine completion.

## 4 BID and BROB

BROB generates dynamic BIDs. BID is the complete slot index of one STID's
BROB ring:

```text
BID_W = ceil(log2(BROB_ENTRIES))
BID   = BROB slot index[BID_W-1:0]
block identity on shared interfaces = (STID, BID)
```

Rules:

- `BROB_ENTRIES` is a power-of-two, per-STID ring depth. With the default 256
  entries, BID is 8 bits; another legal depth changes BID width with the
  formula above.
- `cmd_tag` equals BID and `cmd_stid` carries the separate ring selector. A
  fixed 8-bit command lane carries the default 8-bit BID directly and
  zero-extends a narrower configured BID.
- Wrap, allocation generation, age, and late-response correlation are
  separate internal/transaction state; none widens BID.
- A flush names `(flush_stid, flush_bid)` and BROB derives the wrapped live
  kill interval from head, tail, occupancy, and wrap state. Consumers use the
  resulting kill mask/context; unsigned `bid <= flush_bid` or `bid >
  flush_bid` comparisons are illegal across wrap.
- Only the oldest architecturally eligible block may retire.
- Younger engine-backed work must remain cancellable.

Block completion is:

```text
scalar_done && (needs_engine ? engine_done : 1)
```

`scalar_done` is triggered at both `BSTART` retire and `BSTOP` retire according to the promoted LinxCore contract.

## 5 Block Completion Classes

For completion semantics, LinxCore uses the ISA-visible domain:

```text
{STD, FP, SYS, MPAR, MSEQ, VPAR, VSEQ, TMA, CUBE, TEPL}
```

Dynamic block instances collapse to one of three participant sets:

| Participant set | Meaning |
| --- | --- |
| `{}` | Empty/control-only scalar-family block. |
| `{scalar}` | Scalar-family block with real scalar body. |
| `{non-scalar}` | Canonical non-scalar block type with one engine completion obligation. |

Dynamic degeneration to `{}` is allowed only for scalar/control-family block types. Non-scalar block types always carry a non-scalar completion obligation.

## 6 Boundary-Authoritative Redirect

Linx control flow is boundary-authoritative:

- `SETC.*` computes condition or target state.
- BRU-side execution may discover a mismatch early.
- Execute-side correction metadata records the correction with epoch/checkpoint information.
- Architectural redirect selection happens at the block boundary.
- Execute-side correction must not bypass ROB ordering.

Recovery targets must resolve to legal block starts. Non-block targets raise `TRAP_BRU_RECOVERY_NOT_BSTART (0x0000B001)` precisely in the LinxCore microarchitecture contract. The ISA manual also defines `E_BLOCK(EC_CFI)` for architectural control-flow-integrity violations.

## 7 Call and Return Rules

Linx call/return differs from ARM-style link-register branch semantics:

- Calls are block transitions such as `BSTART.STD CALL, <target>`.
- `BSTART.CALL` does not implicitly write `ra`.
- `SETRET` or `C.SETRET` materializes the return address.
- When `SETRET` is used for a call transition, it must appear immediately after the call-type block start marker.
- `RET`, `IND`, and `ICALL` require explicit `SETC.TGT` in the same block.
- `FENTRY`, `FEXIT`, `FRET.RA`, and `FRET.STK` are standalone macro block boundaries and valid control-flow targets.

## 8 Trap and Privilege Envelope

LinxISA `v0.56` uses Access Control Rings (`ACR0..ACR15`), `CSTATE`, `ECSTATE`, `BSTATE`, `EBARG`, `TRAPNO`, and `TRAPARG0`.

Trap entry is a precise service request. For a synchronous or asynchronous service request routed from ACR `n` to managing ring ACR `m`, the architecture saves:

- `CSTATE` into `ECSTATE_ACRm`;
- source block start into `EBARG_BPC_CUR_ACRm`;
- selected next-block PC into `EBARG_BPC_TGT_ACRm`;
- resume PC into `EBARG_TPC_ACRm` when `BI=1`;
- block-local queues and loop/context state required by the profile, such as `TQ`, `UQ`, `LB*`, and `LC*`;
- trap number and argument into `TRAPNO_ACRm` and `TRAPARG0_ACRm`.

On trap entry, interrupts are disabled by clearing `CSTATE.I`, active ring changes to the managing ring, `BARG` resets, and `BPC` vectors to `EVBASE_ACRm`.

`ACRE` requests ACR enter at block commit. `ACRC` triggers a synchronous trap immediately after execution and must be followed by an explicit block terminator in the same block under current bring-up restrictions.

## 9 Interrupt and MMU Rules

Interrupt entry and return must compose with:

- in-flight OOO execution;
- block-boundary redirect;
- replay and flush;
- LSID-ordered memory;
- engine-backed completion.

MMU success and failure must produce deterministic trap envelopes. Faults must preserve precise retirement order.

## 10 Open Questions

- Should the RTL contract expose separate trap causes for microarchitectural not-block recovery (`TRAP_BRU_RECOVERY_NOT_BSTART`) and ISA-level `E_BLOCK(EC_CFI)`, or should one be normalized before architectural reporting?
- What exact committed trace fields are required for BROB completion events beyond instruction commit fields?
- Should `ACRC` immediate-trap behavior be modeled as an execute-stage precise event or a commit-synchronized pseudo-boundary in the first RTL target?
