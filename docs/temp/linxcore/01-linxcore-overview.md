# LinxCore Overview

This document defines the top-level migration from the ARM-oriented upgrade documentation to a LinxISA `v0.56` LinxCore microarchitecture specification.

## 1 Terminology

| Term | Meaning in this draft |
| --- | --- |
| LinxISA `v0.56` | The canonical ISA profile described by `isa/v0.56/linxisa-v0.56.json` and the v0.56 ISA manual. |
| LinxCore | The canonical superscalar out-of-order core for LinxISA. It owns precise retirement, block-aware recovery, interrupt/trap ordering, MMU-facing behavior, and architectural trace. |
| Block | A basic-block execution unit delimited by `BSTART.*`, `BSTOP`, `C.BSTART.*`, `C.BSTOP`, `HL.BSTART.*`, or valid template block markers. |
| BBD | Block Boundary Delimiter instruction family. This includes `BSTART.*`, `BSTOP`, compressed boundary forms, extended boundary forms, and template markers. |
| BARG | Block Argument Register. Per-block architectural control and descriptor state evaluated at block commit. |
| CARG | Block condition/argument source operand resolved through the active `BID`; it is not renamed as an independent register. |
| BID | Per-STID BROB slot index. `BID_W = ceil(log2(BROB_ENTRIES))`; wrap, generation, age, and correlation metadata are separate from BID. |
| BROB | Block reorder buffer. Tracks block commands, engine completion, and per-STID ring-ordered block retirement. |
| ROB | Instruction reorder buffer. Preserves precise instruction retirement and coordinates flush, trap, and commit-visible state. |
| `P` | Map-renamed global/scalar architectural register class. |
| `T` / `U` | Block-local ClockHands temporary queues with lifetime bounded by block lifecycle. |
| LSID | Monotonic load/store ordering identifier allocated to memory uops. |
| BCC | Scalar core/block-control channel. Owns scalar block sequencing and scalar memory ordering points. |
| MTC | Tile and bridged memory channel used by tile memory and shader-kernel memory operations. |
| VEC | Programmable SIMT vector engine used for canonical `MPAR`/`MSEQ` shader kernels and data-parallel loop execution. |
| TMA | Tile memory/access block family, including canonical `BSTART.TLOAD`, `BSTART.TSTORE`, and `BSTART.TMOV` aliases. |
| CUBE | Matrix/tile compute engine selected by `BSTART.CUBE` aliases such as `BSTART.TMATMUL`. |
| TAU / TEPL | Tile-to-tile accelerator execution domains selected through block headers and descriptors. |

## 2 Architectural Position

LinxCore is not an ARM-compatible OOO core with renamed opcodes. It is an out-of-order implementation of a block-structured ISA. The migration keeps generic superscalar machinery only where it remains valid:

- fetch, decode, rename, dispatch, issue, execute, writeback, and commit are still pipeline concepts;
- physical register rename and MapQ still eliminate WAW/WAR hazards for global `P` destinations;
- issue queues still hold ready-tracked uops until execution resources are available;
- the ROB still commits precise architectural effects in order;
- branch recovery, trap entry, interrupt entry, MMU fault reporting, and memory visibility remain commit-ordered.

The dominant change is that architectural control flow is block-granular. Control-flow targets MUST resolve to legal block start markers. The core must therefore preserve both instruction-level OOO precision and block-level ordering through `BID`, `BROB`, `BSTART`, `BSTOP`, and `BARG`.

## 3 LinxCore Baseline

The live `v0.56` LinxCore baseline is:

| Item | Baseline |
| --- | --- |
| Fetch width | 4 instructions in the promoted architecture contract; LinxCoreModel PE fetch width is 16 bytes per cycle. |
| Decode/dispatch width | 4 uops per cycle in the promoted closure baseline. |
| Issue width | Up to 4 issue events per cycle in the promoted baseline. |
| Commit width | Up to 4 retired uops per cycle. |
| LSU width | 1 scalar LSU in the normative LinxCore contract. |
| Hardware threads | Model config has `threadCount=4`, `stdThreadCount=1`, and `scalar_smt_thread=1`. |
| SIMT lanes | Model config has `simtLane=64` and `simtEnable=true`. |
| Block ROB depth | `BROB_ENTRIES` is a power-of-two per-STID parameter. Model evidence is 256 entries; at that depth `BID_W=8`. |

Where the architecture contract and model parameters differ in presentation, the architecture contract is normative and the model value is evidence for the current executable-reference configuration.

## 4 Pipeline Stage Map

The promoted stage contract covers the path from fetch through late execute/wakeup:

| Stage | Owner and responsibility |
| --- | --- |
| F0 | PC selection, per-thread candidate arbitration, and registered handoff to F1. |
| F1 | I-cache lookup and hit/miss control. Current physical path arbitrates a single I-cache read among threads. |
| F2 | I-cache data/ECC staging and fetch-side fault reporting. |
| F3 | Variable-length instruction assembly, static prediction, block-boundary recognition, template interaction, and per-thread stitch buffering. |
| F4/IB | The fourth fetch stage and per-thread instruction-buffer boundary. It owns final lightweight predecode, prediction, block metadata, and instruction residency before D1. |
| D1 | Early decode, exception detection, split/fuse recognition, and contiguous group formation. |
| D2 | Operand extraction, canonical uop shaping, boundary resolution, and resource-demand preparation. |
| D3 | Atomic ROB/BROB/rename/IQ/memory-order admission, physical rename, and dispatch-packet formation. |
| S1 | Post-rename classification, IQ routing, and ready-state query. |
| S2 | Actual IQ enqueue. |
| S3/IQ | Resident issue queue entry with non-speculative and speculative readiness, now pick-visible. |
| P1 | Oldest-ready pick and inflight marking. |
| I1 | Operand-read planning and global RF read-port arbitration. |
| I2 | Issue confirmation and IQ deallocation. |
| E1/E2/E3/... | Absolute execute cycles after issue confirmation. A pipe retains its E coordinate even when its result is not yet available. |
| W1/W2/W3/... | Producer-relative actual result ages overlaid on E: W1 is data/bypass, W2 is baseline RF write, and W3 is baseline RF-visible/retained bypass. |

## 5 ARM-to-Linx Migration Summary

| ARM-oriented concept | LinxCore replacement |
| --- | --- |
| AArch64 decoder and feature list | LinxISA `v0.56` catalog with scalar, block, command, vector, tile, system, and privileged instruction groups. |
| ARM branch IDs and branch predictor recovery | Boundary-authoritative `BSTART`/`BSTOP` redirect, `SETC` correction metadata, legal block-start target checks, and precise CFI traps. |
| ARM GPR/FPR/SVE predicate rename | Linx `P` map rename plus block-local `T`/`U` FIFO rename and SIMT/vector/tile register domains. |
| ARM exception model | Linx `ACR`, `CSTATE`, `ECSTATE`, `BSTATE`, `EBARG`, `TRAPNO`, and precise service-request envelope. |
| ARM SVE lanes | Linx SIMT `MPAR`/`MSEQ` kernel bodies with a 64-bit architectural EXEC mask `p`. |
| ARM LSU memory model | Linx TSO with BCC/MTC channels, LSID-ordered scalar LSU issue, and bridged shader memory forms. |
| ARM accelerator/SVE special flows | Linx block-fabric engines selected by typed `BSTART.*` headers and descriptor streams. |

## 6 Architectural Guarantees

LinxCore MUST preserve these guarantees:

1. Block-structured execution is mandatory.
2. Architectural control-flow targets resolve only to legal block starts.
3. `BSTART` and `BSTOP` are ROB-visible boundary uops resolved before IQ issue.
4. `BSTART.CALL` does not implicitly update a return-address register; return addresses are created only by explicit `SETRET` or `C.SETRET`.
5. `RET`, `IND`, and `ICALL` require explicit `SETC.TGT` in the same block.
6. `BID`-carrying queues flush from BROB-qualified ring-age/kill context, never from an unsigned comparison of wrapped BID values.
7. `P` rename is MapQ-backed and instruction-precise; `T`/`U` are block-private queue allocations and are released only at the block lifecycle point defined by the block contract.
8. Scalar memory issue uses monotonic LSID ordering.
9. Engine-backed work remains subordinate to ROB/BROB precise completion, flush, exception, and trace rules.
10. Privilege, trap, MMU, and interrupt state changes are commit-visible or trap-visible only.

## 7 Open Questions

- Should the published LinxCore draft use the architecture-contract width numbers exclusively, or should it include side-by-side model configuration values where they are useful for implementation planning?
- Should the 256-entry LinxCoreModel BROB depth become the normative first RTL
  target, or remain a sizing recommendation while `BROB_ENTRIES` stays a DSE
  parameter with `BID_W = ceil(log2(BROB_ENTRIES))`?
- Which resource numbers should be treated as closure-baseline requirements versus tunable implementation parameters in the first RTL target?
