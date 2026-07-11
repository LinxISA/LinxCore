# Upgrade Documentation Split

This folder contains the maintained Markdown specification set for the OOO, IEX, and LSU upgrade material. These files are the golden reference for this upgrade snapshot.

Sanitization applied:

- Removed author-initial suffixes from headings.
- Generalized variant-specific labels, product aliases, and project-specific variant names.
- Redacted direct contact-style data patterns if present.
- Replaced informal implementation-control wording with professional terminology.
- Preserved technical architecture terms such as register names, signal names, architectural features, and memory-ordering concepts.

## Files

- [OOO Overview](01-ooo-overview.md) - This document introduces out-of-order execution terminology, the OOO unit responsibilities, feature scope, major structures, pipeline stages, and stall behavior.
- [OOO Decode](02-ooo-decode.md) - This document rewrites the decode flow, uop break rules, grouping, branch-ID handling, PC management, fusion, early exception detection, and special decode behavior.
- [OOO Rename and Dispatch](03-ooo-rename-dispatch.md) - This document covers architectural-to-physical register mapping, speculative and committed maps, free lists, mapping queues, dispatch packet generation, issue-queue allocation, and virtual load/store indices.
- [OOO ROB, Exceptions, PC, SMT, and SVE](04-ooo-rob-exception-pc-smt-sve.md) - This document covers ROB organization, commit and non-flush behavior, exception handling, PC buffering, SRF behavior, SMT controls, resource sharing, SVE rename support, and related traps.
- [IEX Overview and Structure](05-iex-overview.md) - This document introduces the integer execution unit, feature scope, issue queues, execution pipes, register files, implementation-specific variants, and high-level structure.
- [IEX Issue Queues and Execution](06-iex-issue-execute.md) - This document covers ALU, AGU, and BRU issue queues, ready tables, register files, bypass networks, execution pipes, execution units, move optimizations, profiling, and power mitigation.
- [IEX Special Instruction Flows](07-iex-special-flows.md) - This document covers floating-point interactions, system instruction handling, instruction fusion, SVE support, and pointer-authentication handling.
- [IEX Control, Safety, Interfaces, and MDB Support](08-iex-control-safety-interfaces.md) - This document covers SMT behavior, reset, implementation control registers, safe mode, parity protection, interfaces, physical-design notes, fast store-to-load forwarding, and IEX-side MDB support.
- [LSU Overview, Features, and Basic Flows](09-lsu-overview-features.md) - This document introduces the load/store unit, its feature set, supported instruction classes, basic load/store flows, ordering requirements, structures, arbiters, pipelines, and external interfaces.
- [LSU Structures and Instruction Requirements](10-lsu-structures-instructions.md) - This document covers issue pipes, data cache, translation, memory-order structures, queues, store queues, special system instructions, barriers, address translation, cache maintenance, acquire/release, exclusives, and non-temporal behavior.
- [LSU Pipelines and Datapaths](11-lsu-pipelines.md) - This document covers load, store, L2C, and MMU pipelines and datapaths, including load result timing, store retirement, fills, snoops, and request paths.
- [LSU Control, Ordering, Resources, and Coherence](12-lsu-control-ordering-resources.md) - This document covers flush and no-flush procedures, ROB commit behavior, load/store ordering, LHQ ordering, cross-page and device flows, non-cacheable store flows, resources, coherence, and SVE store handling.
- [LSU MDB, Exclusive, and Atomic Flows](13-lsu-mdb-exclusive-atomic.md) - This document covers memory-disambiguation buffer behavior, exclusive access flows, atomic implementation, interfaces, always-near policy, and terminology annex content.
