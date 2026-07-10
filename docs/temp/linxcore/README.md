# LinxCore LinxISA Specification Draft

This folder contains the maintained Markdown draft for the LinxISA version of the core microarchitecture documentation. Treat these Markdown files as the golden working source for this draft.

The material is migrated from the ARM-oriented upgrade structure into LinxCore terms. The organization intentionally keeps the same broad OOO, IEX, and LSU section split so reviewers can compare coverage, but the technical contract is LinxISA `v0.56` and LinxCore-specific.

## Source Corpus

The draft is grounded in the following repository sources:

- `/Users/zhoubot/linx-isa/docs/architecture/v0.56-architecture-contract.md`
- `/Users/zhoubot/linx-isa/docs/architecture/isa-manual/src/linxisa-isa-manual.adoc`
- `/Users/zhoubot/linx-isa/isa/v0.56/linxisa-v0.56.json`
- `/Users/zhoubot/linx-isa/rtl/LinxCore/docs/architecture/overview.md`
- `/Users/zhoubot/linx-isa/rtl/LinxCore/docs/architecture/microarchitecture.md`
- `/Users/zhoubot/linx-isa/rtl/LinxCore/docs/architecture/interfaces.md`
- `/Users/zhoubot/linx-isa/rtl/LinxCore/docs/architecture/verification-matrix.md`
- `/Users/zhoubot/linx-isa/rtl/LinxCore/docs/architecture/linxisa_block_control_flow.md`
- `/Users/zhoubot/linx-isa/rtl/LinxCore/docs/architecture/block_fabric_contract.md`
- `/Users/zhoubot/linx-isa/rtl/LinxCore/docs/architecture/lsid_memory_ordering.md`
- `/Users/zhoubot/linx-isa/rtl/LinxCore/docs/architecture/branch_recovery_rules.md`
- `/Users/zhoubot/linx-isa/rtl/LinxCore/docs/architecture/block_private_rf.md`
- `/Users/zhoubot/linx-isa/model/LinxCoreModel/configs/*.toml`
- `/Users/zhoubot/linx-isa/model/LinxCoreModel/model/`

## Migration Principles

- Preserve precise architectural behavior from LinxISA, not ARM-compatible behavior.
- Replace ARM instruction features with Linx block-structured control flow, `BSTART`/`BSTOP`, `BID`, `BROB`, `BARG`, `CARG`, `T`/`U` local queues, and block-fabric engines.
- Keep superscalar OOO concepts only where LinxCore explicitly uses them: decode, rename, dispatch, issue, ROB, precise retirement, replay, flush, and memory ordering.
- Use LinxCoreModel parameter values as implementation evidence, not as a substitute for the architectural contract.
- Record unresolved design questions inside the documents rather than inventing behavior.

## Files

- [ARM Core Section Parity](00-arm-core-section-parity.md) - This document maps the ARM-oriented core specification section by section onto the LinxCore microarchitecture contract.
- [ISA-Neutral Microarchitecture Alignment](00a-isa-neutral-microarchitecture-alignment.md) - This document preserves reusable structures such as SMAP, CMAP, MapQ, issue queues, PC buffer, IEX, LSU, cache, SCB, and MDB before binding them to LinxISA.
- [LinxCore Overview](01-linxcore-overview.md) - Top-level migration, terminology, stage map, and architecture guarantees.
- [Frontend and Decode](02-linxcore-frontend-decode.md) - F0, the four fetch stages F1-F4/IB, D1-D3, variable-length fetch, block-boundary detection, template expansion, decode grouping, and admission preparation.
- [Rename and Dispatch](03-linxcore-rename-dispatch.md) - `P`/`T`/`U`/`CARG` rename, MapQ, T/U FIFO allocation, D3, S1, S2, and IQ routing.
- [ROB, BROB, Recovery, and Privilege](04-linxcore-rob-brob-recovery.md) - ROB precision, boundary uops, BID/BROB lifecycle, branch recovery, traps, privilege, and interrupts.
- [IEX Overview](05-linxcore-iex-overview.md) - Execution-unit structure, queue families, issue-class ownership, and Linx-specific differences from the ARM-oriented IEX material.
- [Issue Queues and Execution](06-linxcore-issue-execute.md) - IQ residency, pick/issue timing, register-file arbitration, wakeup, bypass, replay, and load speculation.
- [Special Instruction and Block Flows](07-linxcore-special-flows.md) - `SETC`, `SETRET`, call/return, template blocks, vector/SIMT kernels, TMA, CUBE, TAU, TEPL, and system flows.
- [Control, Safety, Interfaces, and Verification](08-linxcore-control-safety-interfaces.md) - Trace, pyCircuit interface, model comparison, conformance gates, and safety rules.
- [LSU Overview](09-linxcore-lsu-overview.md) - Linx memory model, BCC/MTC channels, LSID ordering, scalar LSU scope, and strict profile behavior.
- [LSU Structures and Instructions](10-linxcore-lsu-structures-instructions.md) - Load/store structures, STQ, SCB, memory descriptors, scalar memory operations, atomics, fences, and tile-memory descriptors.
- [LSU Pipelines](11-linxcore-lsu-pipelines.md) - Load, store, drain, forwarding, replay, MMIO, TMA, and bridged shader memory paths.
- [Ordering, Resources, and Forward Progress](12-linxcore-ordering-resources.md) - Resource tables, ordering invariants, backpressure, flush/rebase, and progress requirements.
- [Block Fabric, Engines, and Open Questions](13-linxcore-block-fabric-engines-open-questions.md) - Command/response envelopes, engine completion, block-fabric migration, and consolidated questions for follow-up.
- [OOO Neutral Modules Applied to LinxCore](14-ooo-neutral-modules-to-linxcore.md) - Module-by-module application of PC buffer, decode, SMAP/CMAP/MapQ, T/U rename, ROB/BROB, issue queues, recovery, and trace contracts to LinxCore.
- [LSU Neutral Modules Applied to LinxCore](15-lsu-neutral-modules-to-linxcore.md) - Module-by-module application of LSID, AGU, STQ, LIQ/LHQ, forwarding, cache/TLB, SCB, MDB, atomics, fences, and tile-memory paths to LinxCore.
- [Cross-Check Matrix](16-cross-check-matrix.md) - Explicit matrix across the LinxISA spec, LinxCoreModel evidence, Chisel owners, and migrated neutral OOO/LSU structures.

## Canonical Migration Decisions

The ARM material is a reference for mature OOO mechanisms and timing
coordinates, not an architectural template. The following LinxCore decisions
are therefore consistent across this draft:

- `F0` is frontend control. `F1`, `F2`, `F3`, and `F4/IB` are the four fetch
  stages; `F4` and `IB` are one final fetch/instruction-buffer boundary, not
  serial stages and not a name for the four-wide D1 decode group.
- `D1` forms and validates a contiguous decode group, `D2` constructs the
  canonical uop and admission request, and `D3` atomically admits resources,
  renames, and dispatches. The decode width is a parameter, not a stage name.
- `E1..En` are absolute execute-cycle coordinates after `I2`. `W1..Wn` are
  producer-relative data/result/writeback ages overlaid on those E stages;
  they are not a serial tail after execute.
- A BID is exactly a per-STID BROB slot index:
  `BID_W = ceil(log2(BROB_ENTRIES))`. Ring wrap, allocation generation, age,
  and trace correlation are separate state. Shared block interfaces carry
  `(STID, BID)` and consume BROB-provided ring-age/kill context; they must not
  use a widened BID or unsigned BID comparison across wrap.

## Open Question Convention

Each document may include an **Open Questions** section. Those questions are intentionally part of the draft review surface. They mark points where the current LinxISA/LinxCore contracts defer exact policy, where LinxCoreModel behavior needs promotion into the RTL contract, or where reviewer direction is needed.
