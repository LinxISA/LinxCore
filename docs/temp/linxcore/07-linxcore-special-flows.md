# Special Instruction and Block Flows

This document covers LinxISA flows that do not map cleanly to a simple ALU/load/store branch pipeline.

## 1 SETC and Boundary Conditions

`SETC.*` writes block condition or block target state. It updates `BARG.CARG`, not an ordinary architectural register.

Rules:

- `SETC.*` executes inside a block.
- `SETC.*` may appear anywhere in the block.
- `SETC.TGT` selects the target for `RET`, `IND`, and `ICALL` transitions.
- Conditional transitions use a two-step pattern: first `SETC.*`, then `BSTART.* COND, <target>`.
- BRU may compute condition/correction early, but boundary commit selects architectural redirect.

## 2 SETRET and Calls

Calls are block transitions. `BSTART.CALL` does not write a link register implicitly.

Rules:

- source-level direct calls should use `BSTART.STD CALL, <target>, ra=<return_label>` or an equivalent canonical form;
- if `SETRET` or `C.SETRET` is used to materialize a return address, it must appear immediately after the call-type block start marker;
- no unrelated instruction may be placed between the call boundary and the `SETRET` pair when the pair is used as a fused call header;
- returns require explicit target state through `SETC.TGT`.

## 3 Template Blocks

Template blocks are standalone blocks executed through a code-template generator. They include:

- `FENTRY`;
- `FEXIT`;
- `FRET.RA`;
- `FRET.STK`;
- template memory operations such as `MCOPY` and `MSET` where defined.

Template blocks are valid block start markers and valid architectural control-flow targets. They must not appear inside a `BSTART..BSTOP` coupled block or inside a decoupled body.

Trap/replay for template execution uses `BSTATE.Template`, including `TemplateKind`, and the trap snapshot rules for `BSTATE`/`EBSTATE`.

## 4 Decoupled Blocks

A decoupled block splits execution into a linear header and an out-of-line body:

- header starts with `BSTART.<type>`;
- header contains only `B.*` descriptors after the block start;
- header contains exactly one `B.TEXT <label>` when the block type requires a body;
- `B.TEXT` defines `BodyTPC`, the initial temporary PC for body execution;
- header ends at `BSTOP`, `C.BSTOP`, or implicitly at the next block start marker;
- body executes at `BodyTPC`;
- body completion returns to the header continuation address.

`BodyTPC` need not be a block start marker because it is an engine-internal entrypoint. Architectural control flow to `BodyTPC` is illegal and must raise a control-flow-integrity exception.

## 5 MPAR and MSEQ SIMT Kernels

`MPAR` and `MSEQ` are the canonical shader-kernel block types in LinxISA `v0.56`.

Properties:

- body is selected by exactly one `B.TEXT`;
- body is a structured instruction stream, not a fixed lexical snippet;
- body termination happens when control reaches a legal body terminator;
- valid terminators include `BSTOP`, `C.BSTOP`, `BSTART.*`, `C.BSTART.*`, and `HL.BSTART.*`;
- in-body control flow is group-granular and must remain inside the current body region;
- `p` is the architectural 64-bit EXEC mask;
- `V.CMP.* ->p` is the normative lane-mask generation rule;
- `B.Z` and `B.NZ` test whether `p` is zero or non-zero.

`MSEQ` commits group-visible effects in lexicographic group order. `MPAR` may conceptually run groups in parallel, but must preserve defined dependence and memory-order rules.

## 6 Unified lx64 Vector Domain

Canonical kernel bodies use one `lx64` instruction space:

- if any operand names per-lane state (`vt`, `vu`, `vm`, `vn`), the operation executes as `v.*`;
- otherwise it executes as `l.*`;
- scalar/group-domain operands broadcast when consumed by `v.*`;
- direct `v.*` writes into scalar/group-domain destinations are illegal except through explicit reductions or mask-producing instructions.

Global memory inside canonical shader kernels uses bridged forms only:

- `l.*.brg`;
- `v.*.brg`.

There is no implicit lane-index term just because an instruction is `v.*.brg`; the effective address is built only from explicitly encoded base, index, immediate, and shift terms.

## 7 TMA Tile Memory Blocks

Canonical TMA memory aliases:

- `BSTART.TLOAD <DataType>` maps to `BSTART.TMA Function=0`;
- `BSTART.TSTORE <DataType>` maps to `BSTART.TMA Function=1`;
- `BSTART.TMOV <DataType>` maps to `BSTART.TMA Function=2` but is not a memory operation.

TLOAD/TSTORE consume descriptor state:

- dimensions from `B.DIM` or `C.B.DIM*`;
- layout and pad policy from `B.ARG`;
- GPR bindings from `B.IOR`;
- tile IO bindings and transfer size from `B.IOT`;
- element data type from `BSTART.TMA`.

TMA execution state is not sourced from `B.CATR`/`B.DATR` in the current strict profile.

## 8 CUBE, TAU, and TEPL Blocks

CUBE and TAU integrate through the same block/BID contract as scalar and TMA blocks. They do not get an independent global retirement path.

Rules:

- block headers select engine operation;
- descriptors bind tile/scalar operands and attributes;
- engine issue, completion, exception, and flush are visible through ROB/BROB/trace;
- TAU is tile-to-tile in the current rendering profile;
- TEPL uses the same descriptor families and must not bypass privilege or memory-order contracts.

## 9 System and Breakpoint Flows

System flows include `ACRE`, `ACRC`, `SRET`, traps, interrupts, and breakpoint-like events.

Current LinxISA hardening requires:

- U-to-S trap entry and `SRET` return preserve architected control and state transitions;
- trap envelopes include `CSTATE`, `ECSTATE`, `BSTATE`, `EBARG`, `TRAPNO`, and `TRAPARG0` state as required;
- `EBREAK` defaults to an architectural breakpoint trap unless a platform profile explicitly opts into semihosting behavior;
- semihosting is opt-in, not the default behavior.

## 10 Open Questions

- Which template forms should be implemented by CTU in the first RTL slice, and which should trap or decode as unsupported until promoted?
- Should `MPAR` and `MSEQ` body execution first be modeled as a BCTRL/engine command only, or should the scalar frontend participate in body fetch for the first executable RTL target?
- Which CUBE/TAU/TEPL selectors are required for the first LinxCore bring-up milestone?
