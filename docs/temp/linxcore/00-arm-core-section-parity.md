# ARM Core Section Parity for LinxCore

This document is the section-by-section migration layer from the ARM-oriented core specification to the LinxCore microarchitecture specification. It keeps the ARM document order as a coverage checklist, preserves ISA-neutral microarchitecture first, and then replaces each ARM-specific architectural binding with the corresponding LinxISA `v0.56` / LinxCore contract.

The detailed chapter material lives in the sibling files `01` through `13`. This file is the reviewer-facing parity index: every ARM section cluster has an explicit LinxCore microarchitecture answer and a pointer to the detailed LinxCore chapter.

## Normative Migration Rules

- First preserve ISA-neutral structures such as SMAP, CMAP, MapQ, free lists, ROB, PC buffer, issue queues, RF/bypass, IEX pipes, LSU queues, cache/TLB, SCB, MDB, and refill/response paths.
- Then rebind architectural fields and semantics to LinxISA. ARM architectural features are not carried forward unless they describe a generic OOO mechanism also used by LinxCore.
- LinxCore control flow is block-structured: `BSTART`, `BSTOP`, template markers, `BARG`, `BID`, and `BROB` replace ARM branch-centric control semantics.
- LinxCore register naming is operand-class based: `P`, `T`, `U`, and `CARG` replace ARM integer/VFP/SVE/CC register classes.
- LinxCore memory ordering is TSO with BCC/MTC channels and LSID-ordered scalar LSU issue.
- Engine-backed work is represented as block-fabric commands tracked by BID/BROB, not as separate GPU-style packets.
- ARM-only features such as PAC, BTI, SVE predicate rename, ARM exception levels, and ARM system registers are replaced by Linx control-flow safety, ACR/SSR trap state, SIMT EXEC mask `p`, and block-engine contracts.

The alignment-first rule is specified in [00a-isa-neutral-microarchitecture-alignment.md](00a-isa-neutral-microarchitecture-alignment.md).

## 1. OOO Overview

Detailed LinxCore chapters: [01-linxcore-overview.md](01-linxcore-overview.md), [02-linxcore-frontend-decode.md](02-linxcore-frontend-decode.md), [03-linxcore-rename-dispatch.md](03-linxcore-rename-dispatch.md), [04-linxcore-rob-brob-recovery.md](04-linxcore-rob-brob-recovery.md).

| ARM section | LinxCore microarchitecture spec |
| --- | --- |
| Terminology | Keep neutral OOO terms such as ROB, MapQ, SMAP, CMAP, IQ, RF, LSU, TLB, and cache. Add Linx terms: `BBD`, `BARG`, `CARG`, `BID`, `BROB`, `P`, `T`, `U`, `LSID`, `BCC`, `MTC`, `VEC`, `TMA`, `CUBE`, `TAU`, and `TEPL`. |
| Overview | LinxCore is a 4-wide promoted OOO baseline that retires precisely while enforcing block-ordered architectural control. It is not an ARM core clone. |
| Feature list | Feature coverage is LinxISA `v0.56`: variable-length instruction fetch, generated opcode decode, scalar OOO execution, block boundary handling, scalar GPR rename, local `T/U` rename, LSID memory ordering, block-fabric command issue, ACR trap/return, and VEC/TMA/CUBE/TAU/TEPL integration. |
| Structure description | Preserve neutral OOO blocks and rename them only where Linx requires a new owner: F0, F1/F2/F3/F4/IB, D1/D2/D3, SMAP/CMAP/MapQ/free list, S1/S2/S3-IQ, P0/P1/I1/I2, E1/E2/E3..., W1/W2/W3 overlays, R0-R4, ROB, PC buffer, LSU/cache/TLB, BROB, BCTRL/BISQ, trace. |
| Decode | Decode is generated from Linx opcode metadata and must preserve instruction length, raw bits, operand classes, block metadata, and checkpoint identity. |
| Rename | `P` uses `SMAP`/`CMAP`/MapQ. `T/U` use local FIFO/ClockHands owners. `CARG` resolves by `BID`. |
| Dispatch | Dispatch routes by Linx uop class into `alu_iq0`, `shared_iq1`, `bru_iq`, `agu_iq0/1`, `std_iq0/1`, and `cmd_iq`. Boundary-only BBD rows bypass ordinary IQ issue. |
| PC buffer | Preserve the neutral PC buffer idea: carry compact PC index/base/offset/hash metadata instead of full PC everywhere. Upgrade it with block-boundary prediction, frontend/backend checkpoint identity, `BSTART` target legality, and recovery restart tokens. PC-relative scalar operations such as `ADDTPC` compute `(pc & ~0xfff) + imm`. |
| Overall pipeline | LinxCore pipeline is F0 -> F1 -> F2 -> F3 -> F4/IB -> D1 -> D2 -> D3 -> S1 -> S2 -> S3/IQ -> P1 -> I1 -> I2 -> E1/E2/E3..., with W1/W2/W3 result-age overlays and R0-R4 commit/recovery. |
| D1/D2/D3/S1 stage descriptions | D1 decodes and forms a contiguous group, D2 forms canonical uops and an admission request, D3 atomically admits resources/renames/dispatches, and S1 captures/routs/query readiness. |
| Pipeline stall | Stalls come from frontend miss/ECC/template priority, ROB/BROB/MapQ/resource pressure, `T/U` local bank pressure, IQ fullness, RF read-port arbitration, LSID head blocking, STQ/SCB capacity, BCTRL/BISQ pressure, engine response backpressure, and recovery cleanup barriers. |

## 2. OOO Decode

Detailed LinxCore chapters: [02-linxcore-frontend-decode.md](02-linxcore-frontend-decode.md), [07-linxcore-special-flows.md](07-linxcore-special-flows.md), [08-linxcore-control-safety-interfaces.md](08-linxcore-control-safety-interfaces.md).

| ARM section | LinxCore microarchitecture spec |
| --- | --- |
| Predecode in IFU | F3 performs variable-length assembly, static prediction, and semantic boundary annotation. It recognizes `BSTART`, `BSTOP`, compressed/extended boundary forms, and template markers. |
| InstQ read restrictions | F4 and IB are one per-thread final-fetch boundary. D1 reads up to `decodeWidth` contiguous entries, stops at the first invalid/killed/incomplete entry, and never compacts younger entries around it. |
| D1 stage | D1 consumes a contiguous program-order group and decodes via generated mask/match tables. D2 prepares demand; D3 atomically receives `RID`, `BID`, and pre-increment LSID snapshots when it admits the group. |
| Uop break and mapping | Linx splits stores into STA/STD ownership, keeps descriptor/CMD work on the command path, and treats BBD marker rows as boundary rows. Complex block operations become block-fabric commands rather than ARM-style microcode expansion. |
| Destination number | Destination accounting is by Linx destination kind: scalar GPR `P`, local `T`, local `U`, no destination, or engine/tile descriptor side effect. Source alias tags `0..23` are `P`, `24..27` are `T`, `28..31` are `U`; destination tag `31` is `T`, `30` is `U`. |
| Decode for ROB grouping | ROB rows preserve `(bid,gid/rid)` identity and block sidecars. Marker rows may be admitted in marker-row mode or skipped in reduced modes, but full spec requires marker lifecycle visibility. |
| Preparation for BID calculation | `BID` is generated by BROB. `BSTART` rows carry the new BID. Following scalar rows reuse active block context until `BSTOP`, redirect, or block-last cleanup. |
| Decode for PC management | Decode carries PC, length, raw instruction, boundary target, checkpoint, and predicted direction. Target-bearing `BSTART` rows compute boundary target from PC-relative immediates; dynamic targets come from live `SETC.TGT` owner state. |
| Instruction fusion | ARM fusion is replaced by Linx canonical marker/header materialization. Legal call headers may be fused returning forms or adjacent `CALL` + `SETRET`; unrelated SETRET adjacency is a precise strict-mode fault. |
| Early exception identification | Decode must identify illegal encodings, unsupported operand aliases, bad block-target forms, missing marker cases, BodyTPC architectural entry attempts, and descriptor legality where decode owns enough evidence. |
| D2 decoder block | D2 builds the canonical uop shape with operand classes, destination kind, `rid`, `bid`, `lsid`, `sob/eob`, boundary kind, boundary target, prediction, instruction length, and raw bits. |
| Decode for rename | D2 must not hand non-scalar aliases to scalar GPR rename. `ScalarDecodeRenameBridge` accepts only `P` GPR tags until the wrapper overlays `T/U` rename through `ScalarTURenameBridge`. |
| Decode for dispatch | Dispatch class is Linx uop kind: ALU, BRU, AGU/LDA, STA, STD, FSU/SYS, CMD, or BBD boundary. |
| Destination lane mapping | Lanes map to operand class and issue target, not ARM integer/VFP/CC lanes. Same-cycle allocation order is decode-slot order. |
| ROB id calculation | RID is allocated by the ROB owner; hardware BID is the `BID_W`-bit BROB slot index, carried with separate STID and ring-age/epoch state where required. It remains distinct from model `CommitInfo.bid`. |
| PC management in D2 | D2 preserves PC and checkpoint identity for recovery. Frontend checkpoint IDs and backend start-marker checkpoint IDs are distinct. |
| Move optimization | ARM MOV optimizations become reduced scalar ALU/register-forwarding cases only when semantically valid for Linx opcodes. Local `T/U` destinations must not write scalar RF. |
| Saveop / injected instruction | Replace with Linx template/macro flow: `FENTRY`, `FEXIT`, `FRET.*`, `MCOPY`, `MSET`, and CTU-generated child streams. CTU injection has priority over ordinary IFU writes into IB. |
| Thread arbiter | Thread arbitration remains per-stage and per-owner. Current model evidence has `threadCount=4`, `stdThreadCount=1`, and `scalar_smt_thread=1`; block marker lifecycle is STID-indexed, not global. |
| Special instruction behavior | Special flows are Linx `SETC`, `SETRET`, `ACRE`, `ACRC`, `SRET`, `EBREAK`, template blocks, and engine descriptors. PAC/BTI/HVC/SMC/ERETAA-style ARM flows are not LinxCore features. |
| New uop break | Store split is the key scalar split: STA and STD share identity. PCR stores, store-immediate forms, indexed stores, and compressed stores have strict source ordering contracts. |
| Gather/scatter | ARM gather/scatter maps to Linx VEC bridged memory or future tile/vector owners. Canonical shader global memory is `l.*.brg` / `v.*.brg` only. |

## 3. OOO Rename and Dispatch

Detailed LinxCore chapters: [03-linxcore-rename-dispatch.md](03-linxcore-rename-dispatch.md), [06-linxcore-issue-execute.md](06-linxcore-issue-execute.md), [12-linxcore-ordering-resources.md](12-linxcore-ordering-resources.md).

| ARM section | LinxCore microarchitecture spec |
| --- | --- |
| Architectural/physical registers | Scalar model GPR owner has 24 architectural GPRs. Wider tags are decoded as `T/U` or invalid by the alias classifier. |
| VFP/predicate/CC registers | Replace with Linx SIMT/tile/local domains. The scalar core spec covers `P/T/U/CARG`; VEC/tile operands are block-engine work unless promoted into scalar decode owners. |
| Zero register rename | Treat zero/one special register behavior only when the Linx opcode metadata marks it. Do not inherit ARM XZR semantics broadly. |
| Speculative map table | Preserve SMAP as latest speculative arch-to-physical map. Bind it to scalar `P` destinations; checkpoint capture happens at start-marker/block context. Restore uses `flush_checkpoint_id` / cleanup BID. |
| Commit map table | Preserve CMAP as committed arch-to-physical map. Linx commit updates CMAP by walking MapQ rows in ROB retirement/program order; `(STID,BID)` identifies block ownership while BROB provides cross-block age. Same-architectural multiple writes in a block release intermediate tags correctly. |
| Ptag free list | Preserve physical free-list allocation/release. Scalar `P` physical tags come from GGPR free state; `ggpr_count=128` is model evidence. Local `T/U` physical allocation is independent. |
| Mapping queue | Preserve MapQ as an ordered remap log with old/new ptag, arch tag, `(STID,BID)`, RID, and insertion order. Scalar `gprMapQDepth=256` is distinct from local `T/U` mapQ depth. Do not tie GPR MapQ capacity to local queues. |
| MPQ flush/recover | Flush restores from checkpoint `flush.bid - 1` or `CMAP`, then re-applies surviving rows. Block-stop cleanup must pass the correct next-block BID when preserving the finished block. |
| Append/commit/recover mapping | Append at rename, commit on ROB retirement, recover on registered recovery cleanup, with backend marker-mode differences explicitly handled. |
| Rename stall | Stall on scalar GPR free/MapQ exhaustion, local T/U map/physical pressure, recovery cleanup barriers, unsupported operand class, ROB/BROB allocation pressure, and store-dispatch readiness. |
| Life of a ptag | `P` ptag lives from rename allocation until committed replacement releases old mapping or flush prunes speculative row. `T/U` tags live through local relation-cmap and block-commit cleanup. |
| Register MOV/SXTW/FM0V optimizations | Only implement Linx opcodes with confirmed model/QEMU/Sail semantics. W-form shifts and sign/zero extension follow Linx model contracts, not ARM shortcuts. |
| Dispatch feature | Dispatch packages renamed uops with `stid`, `bid`, `rid`, `BID_W`-bit `blockBid`, required ring-age/epoch sidecars, operand tags, local sequences, store split intent, and execution class. |
| IQ payload | IQ payload preserves uop kind for trace even if physical queues merge. `CARG` must be materialized before ordinary IQ entry. |
| Issue queue allocation | ALU prefers `alu_iq0`, spills to `shared_iq1`; BRU to `bru_iq`; AGU to `agu_iq0/1`; STD to `std_iq0/1`; CMD to `cmd_iq`; BBD marker rows do not enter ordinary IQ. |
| Virtual STQ/LIQ index | Decode stamps LSID snapshots in slot order. Store split STA/STD share SID/LSID identity. LIQ uses `LID`; STQ uses `SID`. |
| DPDE/load hint/MDB info | Linx replacement is LSID snapshot, youngest-store/load sidecars, MDB SSIT lookup/record/delete commands, and block-aware flush/nuke metadata. |

## 4. ROB, Exception, PC, SMT, and SVE

Detailed LinxCore chapters: [04-linxcore-rob-brob-recovery.md](04-linxcore-rob-brob-recovery.md), [08-linxcore-control-safety-interfaces.md](08-linxcore-control-safety-interfaces.md), [13-linxcore-block-fabric-engines-open-questions.md](13-linxcore-block-fabric-engines-open-questions.md).

| ARM section | LinxCore microarchitecture spec |
| --- | --- |
| ROB feature | ROB retires contiguous completed rows, preserves precise traps, publishes commit trace, owns deallocation separately from commit, and rejects duplicate live identities. |
| Grouping concept | Linx grouping is block-aware. `BSTART` starts a new block/BID; `BSTOP` completes current active block; marker-row mode must split decode-time context from retire-time effects. |
| ROB format | ROB rows carry `rid`, model identity sidecars, `BID_W`-bit `blockBid`, separate STID and required ring-age/epoch sidecars, local T/U sequences, boundary/memory metadata, completion status, and trap metadata. |
| ROB commit | Commit consumes `Completed` rows and marks them `Retired`; deallocation later frees retired rows. Marker rows may be filtered from scalar comparator but must remain visible to block lifecycle. |
| Non-flush | Non-flush/commit-visible state is the point at which stores may enter committed drain and local relation cleanup may progress. SCB contains only non-flushable stores. |
| Interrupt handling | Interrupt entry composes with block boundaries, recovery, LSID memory, and engine completion. Trap state saves `CSTATE`, `ECSTATE`, `EBARG`, `BSTATE`, `TQ/UQ`, and continuation PCs as required. |
| Exception handling | Precise exceptions include illegal instruction, block CFI, bad dynamic target, missing dynamic target, stale target epoch, MMU faults, device faults, and EBREAK breakpoint trap. |
| ROB stall | Stall on head incomplete, unresolved trap/interrupt serialization, BROB completion wait, non-flush pointer pressure, local cleanup barrier, store drain pressure, and recovery owner work. |
| Exception pipeline | Linx exception flow is service-request based through ACR routing and trap envelope state. `ACRC` immediate trap and `ACRE` block-commit transition are distinct. |
| Flush type table | Flush classes include boundary redirect, deferred BRU correction, nuke flush, trap/interrupt, global replay, PE-scoped replay, STQ/LIQ prune, and block/BID younger kill. |
| System register RW self-sync | Replace ARM sysreg self-sync with Linx SSR/ACR state transitions and block-commit/trap-visible synchronization. |
| PC buffer | PC management is split between frontend packet checkpointing, backend start-marker checkpointing, boundary target ownership, and explicit `FLS -> F0` restart tokens. |
| SRF/system state | Linx uses ACR, SSR, `CSTATE`, `ECSTATE`, `BSTATE`, `BARG`, `EBARG`, `TRAPNO`, `TRAPARG0`. |
| WFx | Map to Linx wait/interrupt/system behavior only if present in v0.56 catalog; do not inherit ARM WFI/WFE state machines by default. |
| SMT | Current scalar path is STID-aware with model evidence for multiple threads but one scalar active thread. Marker lifecycle, local bank selection, and flush compare must include STID. |
| SVE | Replace with Linx VEC/SIMT: `MPAR`/`MSEQ`, 64-bit EXEC mask `p`, `V.CMP.* ->p`, `B.Z/B.NZ`, and bridged memory. |

## 5. IEX Overview and Structure

Detailed LinxCore chapters: [05-linxcore-iex-overview.md](05-linxcore-iex-overview.md), [06-linxcore-issue-execute.md](06-linxcore-issue-execute.md).

| ARM section | LinxCore microarchitecture spec |
| --- | --- |
| IEX terminology | Use Linx uop kinds: ALU, BRU, AGU/LDA, STA, STD, FSU/SYS, CMD, BBD marker. |
| IEX overview | IEX executes scalar/control/address/store-data/system/command uops and reports precise resolve to ROB/BROB. |
| IEX IQ | Current evidence: CMD depth 24 count 1; ALU depth 24 count 8; LDA depth 32 count 4; STA/STD depth 16 count 4; BRU depth 32 count 4. Promoted physical IQ names remain trace-visible. |
| IEX pipes | Pipeline is P1/I1/I2 followed by absolute E1/E2/E3... coordinates; W1/W2/W3 are producer-relative result overlays. LSU load stages are E1 through E6 with a baseline hit at E4/W1. |
| IEX RF | Scalar RF serves `P`; local bank array serves `T/U`; STD consumes reads but has no write port. |
| IEX misc | Includes CARG materialization, branch-decision sidebands, local-source suppression in QEMU-shaped trace, and reduced load/store sidebands where LSU is not yet fully connected. |
| Implementation-specific features | Treat reduced Chisel live-fetch RF/ALU/CoreMark gates as evidence surfaces, not as full architectural replacement for LSU/marker/BROB until promoted. |
| Overall pipeline | Dispatch to IQ, pick, read, issue confirm, execute, wake/resolve, ROB commit. |
| RF write pipeline | Write only when destination kind is `P`; local `T/U` destinations update local owner state and QEMU-shaped architectural fields without scalar RF mutation. |

## 6. IEX Issue Queues and Execution

Detailed LinxCore chapters: [06-linxcore-issue-execute.md](06-linxcore-issue-execute.md), [09-linxcore-lsu-overview.md](09-linxcore-lsu-overview.md), [10-linxcore-lsu-structures-instructions.md](10-linxcore-lsu-structures-instructions.md).

| ARM section | LinxCore microarchitecture spec |
| --- | --- |
| ALU ISSQ | ALU rows enqueue by class, pick oldest-ready, remain resident while inflight, and deallocate only at I2. Local-source operations must wait for local readiness and preserve T/U sidecars. |
| AGU ISSQ | AGU handles load/store address work. Store split STA/STD share identity. LSID/SID sidecars must be preserved into STQ/LSU owners. |
| BRU ISSQ | BRU computes `SETC` condition/target sidebands and deferred correction metadata. It does not directly redirect architectural PC. |
| Ready table | `P/T/U` ready tables are non-speculative only. Speculative readiness is per-IQ-entry. |
| Age matrix | Oldest-ready ordering uses ROB age relative to head; inflight rows are ineligible but retain age. |
| Speculative issue/recovery | Load spec wakeup at LD_E1 has no data; data arrives at LD_E4; dependent issue must use E4-to-I2 bypass or suppress on miss. |
| Dependence tracking vector | `ld_gen_vec` is a bitset for E1-E4 and propagates through dependent chains. |
| Allocation/deallocation | Pick sets inflight; cancel clears inflight; I2 clears valid. No pick-then-reinsert model. |
| Register files | Default integer RF read ports are 3. I1 arbitrates oldest-first; loser cancels. Dedicated write ports prevent write contention. |
| Bypass network | `P` uses global ptag bypass; `T/U` use qtag point-to-point wakeup; load data bypasses from LD_E4 to consumer I2. |
| Execution pipes | ALU implements confirmed Linx scalar ops. BRU validates boundary-owned prediction. AGU computes effective addresses and memory sidebands. |
| Mov optimization | Use only confirmed Linx reduced cases. `C.MOVR`, `C.MOVI`, `C.SETRET`, and local-destination variants must preserve destination class. |
| Performance monitor/profile | Trace and perf must preserve uop kind, stage owner, block rows, commit rows, and BLOCK_EVT auxiliary rows without adding trace-only functional state. |
| Power mitigation/safe mode | Use forward-progress, oldest-ready, and safe-mode policy over Linx queues. Do not import ARM override bits. |

## 7. IEX Special Instruction Flows

Detailed LinxCore chapters: [07-linxcore-special-flows.md](07-linxcore-special-flows.md), [13-linxcore-block-fabric-engines-open-questions.md](13-linxcore-block-fabric-engines-open-questions.md).

| ARM section | LinxCore microarchitecture spec |
| --- | --- |
| Floating point interaction | Scalar FP-like work is not inherited from ARM VFP. Linx FP/SIMT/tile behavior must be catalog-driven and routed through scalar FSU/SYS or engine domains as assigned. |
| System instruction handling | Linx system flows are `ACRE`, `ACRC`, `SRET`, `EBREAK`, SSR/ACR reads/writes, trap entry/return, and system-call blocks. |
| Speculative/non-speculative sys ops | Trap/privilege-visible operations are precise. `ACRE` occurs at block commit; `ACRC` traps immediately after execute under bring-up restrictions. |
| L2/L3 MRS/MSR | Replace with Linx memory/system interfaces and platform SSR behavior; no ARM cache system register behavior is implied. |
| Instruction fusion | Replace with call-header and template materialization contracts. Fused/adjacent call return materialization must preserve owner row and materialization kind. |
| SVE IEX handling | Replace with VEC `MPAR/MSEQ`, EXEC mask `p`, `lx64` scalar-uniform/per-lane rule, and bridged memory. |
| PAC handling | No PAC. Control-flow integrity is enforced by block-start target legality and precise target-owner traps. |

## 8. IEX Control, Safety, Interfaces, and MDB

Detailed LinxCore chapters: [08-linxcore-control-safety-interfaces.md](08-linxcore-control-safety-interfaces.md), [12-linxcore-ordering-resources.md](12-linxcore-ordering-resources.md).

| ARM section | LinxCore microarchitecture spec |
| --- | --- |
| SMT support in IEX | STID must be carried through frontend, decode, rename, ROB, T/U bank selection, marker lifecycle, and flush compare. |
| Clock/reset | Reset initializes scalar GPR maps identity, frees physical tags above architectural range, clears local banks/queues, clears ROB/BROB/STQ/SCB/LIQ, and starts at configured boot PC/ACR state. |
| Configuration bits | Replace ARM override registers with LinxCore config knobs: IQ depth/count, GGPR count/MapQ depth, local T/U sizes, LSU windows, BCTRL bandwidth, and engine enable flags. |
| Safe mode | Safe mode means deterministic oldest-ready progress, no wrong-path commit, no same-cycle wake-to-pick loops, and forward progress under branch/load/store/engine pressure. |
| Register parity/check | Protection must cover IQ rows, RF/local banks, ROB/BROB, STQ/SCB/LIQ, and traceable error reporting. Exact parity fields are implementation-specific until promoted. |
| Interface | Commit trace must include QEMU-shaped fields plus Linx sidebands: valid, seq/cycle/slot, bid/gid/rid, ROB id, block_bid, LSID, block event metadata. |
| Physical design | Preserve stage-owner hierarchy and compile-budget discipline. Avoid parent fanout hotspots; keep scans inside owner modules and export compact results. |
| Fast STLF | Linx replacement is byte-granular store-to-load forwarding from resident STQ rows and committed overlay/SCB state, ordered by BROB ring age then LSID. |
| MDB support | MDB detects store-arrival conflicts, owns SSIT lookup/record/delete, marks wait-store rows, classifies same-BID inner flush vs cross-BID nuke, and publishes precise recovery metadata. |

## 9. LSU Overview, Features, and Basic Flows

Detailed LinxCore chapters: [09-linxcore-lsu-overview.md](09-linxcore-lsu-overview.md), [10-linxcore-lsu-structures-instructions.md](10-linxcore-lsu-structures-instructions.md), [11-linxcore-lsu-pipelines.md](11-linxcore-lsu-pipelines.md).

| ARM section | LinxCore microarchitecture spec |
| --- | --- |
| Features | TSO, LSID-ordered scalar issue, LIQ/LHQ, STQ, SCB, byte forwarding, committed-store drain, Device/MMIO precision, BCC/MTC channel interaction. |
| Instructions | Linx scalar loads/stores/atomics/fences, PCR loads/stores, store-immediate/indexed/compressed forms, TLOAD/TSTORE, and bridged shader memory. |
| Basic load flow | Decode stamps LSID, AGU launches when LSID head-eligible, forwarding/cache/refill path resolves data, E4 wakes or records miss/replay. |
| Basic store flow | Decode/split creates STA/STD, STQ merges address/data, commit marks non-flushable, STQCommitQueue drains to SCB, SCB writes DCache/CHI. |
| Load/store ordering | Scalar issue is LSID ordered. Store drain preserves program order. Forwarding selects nearest older store per byte. |
| Structures | LIQ, LHQ, STQ, SCB, MDB, L1D/DCache, response buffers, refill/replay wakeup, committed-store queue. |
| Arbiters | I1 RF ports, LD/ST issue, STQ insert, STQ commit/drain, SCB ingress/egress, response retry, LIQ launch/refill/replay. |
| Pipelines | Load E2/E3/E4 forwarding, store STA/STD/STQ/SCB, refill/response, MMU/translation, Device/MMIO serialization. |
| Interfaces | IEX-to-LSU AGU/STA/STD, LSU-to-IEX load return/wakeup, ROB commit/noflush, recovery flush, BCTRL/MTC for tile memory, trace. |
| uOP ID | Use `RID`, `BID_W`-bit `blockBid`, separate STID, required BROB ring-age/epoch sidecars, all-row LSID snapshot, `LID`, `SID`, and PE ID. |
| Partitioning | Scalar BCC LSU is separate from MTC/tile memory, but both obey one architectural TSO boundary model. |

## 10. LSU Structures and Instruction Requirements

Detailed LinxCore chapters: [10-linxcore-lsu-structures-instructions.md](10-linxcore-lsu-structures-instructions.md), [12-linxcore-ordering-resources.md](12-linxcore-ordering-resources.md).

| ARM section | LinxCore microarchitecture spec |
| --- | --- |
| Issue pipe I1/I2/E1 | AGU/STA/STD issue follows IQ P1/I1/I2 and LSU eligibility. I1 arbitration loss cancels attempt, not IQ entry. |
| Load issue pipe | Loads allocate LIQ/LID, snapshot youngest store, launch through forwarding/cache path, and publish LHQ only on E4 hit. |
| Store issue pipe | STA and STD insert/merge into STQ. Split store pair is atomic at rename queueing but STQ merge progress can accept complementary halves independently. |
| TLB translation | MMU faults are precise; translation metadata must preserve source row and trap envelope. Platform memory type determines Normal vs Device/MMIO behavior. |
| Misalign/cross-line | Cross-cacheline stores split into one or two descriptors. Loads build clipped 64-byte masks and may need replay/refill for incomplete bytes. |
| Load cancel | Cancel arises from flush, miss, wait-store, return-port wait, source wait, or replay. Consumer wakeup must not commit wrong data. |
| L1 Data Cache | Preserve the ISA-neutral tag/state/data array, lookup, refill, and write-update structure. Bind it to Linx TSO, Device/MMIO precision, SCB non-flush policy, and CHI/response completion rules. SCB lookup control distinguishes writable hit, non-writable hit needing upgrade, and miss needing ownership request. |
| MOB | Replaced by explicit LSID ordering plus LIQ/LHQ/STQ/MDB sidecars. |
| MDB | Store-arrival conflict detection uses address overlap and BROB ring age followed by LSID ordering, with SSIT confidence/weight policy. |
| LHQ | Stores resolved hit metadata for conflict checks and ordering. |
| LIQ | Owns resident load state, wait-store, miss/refill, replay wakeup, and relaunch. |
| LDLD snoop | Map to Linx refill/replay/coherence owners when cache-coherent multi-agent behavior is promoted. |
| LFB/FDB | Linx equivalent is refill/miss request and response path. Keep response queues and retry ordering explicit. |
| STQ | Owns speculative store rows, merge of STA/STD into `ST_ALL`, WAIT/COMMIT states, flush pruning, and committed-row issue. |
| SCB | Contains only non-flushable stores, coalesces by physical cacheline, preserves TSO drain, waits for WriteResp/UpgradeResp completion. |
| Watchpoints | Platform debug/watchpoint support must report precise trap envelope; not inherited from ARM fields. |
| Barriers | Linx fences/atomics with `.aq/.rl/.aqrl`, `FENCE.D`, `FENCE.I`, and BCC/MTC acquire/release kernel boundaries replace ARM DMB/DSB/ESB. |
| Address translate / TLBI / cache maintenance | Only Linx catalog-defined system operations are implemented. Effects must be precise and platform-profile defined. |
| Acquire/release | `.aq` orders atomic before later operations; `.rl` orders earlier operations before atomic. Shader kernel entry/exit uses acquire/release-style BCC/MTC boundaries. |
| Exclusives | If Linx catalog/profile defines exclusive-like operations, they must use Linx trap/memory ordering. ARM LDXR/STXR semantics are not assumed. |
| Non-temporal/prefetch/DGH | Only catalog-defined Linx prefetch/hint behavior is implemented; otherwise treat as no-op/reserved/illegal per spec. |
| Atomic operations | Atomics are scalar memory operations, participate in LSID ordering, and report precise traps. Near/far ARM atomic machinery is not inherited. |

## 11. LSU Pipelines and Datapaths

Detailed LinxCore chapters: [11-linxcore-lsu-pipelines.md](11-linxcore-lsu-pipelines.md).

| ARM section | LinxCore microarchitecture spec |
| --- | --- |
| Load flow control | LIQ launch controls load progression. Load-forward pipeline E2/E3/E4 decides forwarded/cache/refill data and wait/replay classification. |
| General load flow | AGU, LSID grant, forwarding, cache/refill, E4 resolve, LHQ publish, wakeup/bypass. |
| Load IEX issue pipeline | AGU/LDA picked from IQ, RF/local operands read at I1, issue confirmed at I2, LSU owns later stages. |
| LHQ sliding window | LHQ stores hit metadata for ordering/conflict. Window details are Linx LIQ/LHQ owner policy, not ARM LHQ format. |
| L1 miss/bank conflict | Miss creates LIQ miss state and later refill wakeup. Bank conflict is a replay/wait cause if cache owner exposes it. |
| Misaligned load | Cross-line/cross-page loads split masks and may require multiple source responses; Device/MMIO misalign policy is platform-defined. |
| TLB miss | Precise MMU fault or refill wait; no wrong-path completion. |
| I2 arbitration loss/skid | I1/I2 loss cancels inflight issue attempt and keeps IQ entry valid. LSU-specific skid buffers are owner-local when implemented. |
| Load reissue/repick | LIQ returns completed replay/refill rows to `Wait` for normal relaunch; it does not directly publish hits from replay sidecars. |
| Load result/wakeup | E4 hit triggers wakeup/return. Store-data-not-ready rows wait for store-unit replay wakeup. |
| Store-load nuke | Cross-block address conflict can mark the oldest conflicting load for nuke; ROB triggers nuke only at head and asks that STID's BROB to derive the younger-block kill set from the head block. |
| Store pipe | STA/STD queues, STQ insert/merge, commit queue, drain, SCB ingress, SCB egress. |
| Store retire | ROB commit makes stores non-flushable; accepted SCB `last` fragment is the full composition free source. |
| L2C/CHI pipeline | SCB lookup emits writable hit/update, upgrade, or write request; completion is WriteResp/UpgradeResp decoded by TxnID `(entryIndex << 2) | 2`. |
| MMU pipeline | Translation success/fault produces deterministic trap envelope and memory-type classification. |

## 12. LSU Control, Ordering, Resources, and Coherence

Detailed LinxCore chapters: [12-linxcore-ordering-resources.md](12-linxcore-ordering-resources.md), [13-linxcore-block-fabric-engines-open-questions.md](13-linxcore-block-fabric-engines-open-questions.md).

| ARM section | LinxCore microarchitecture spec |
| --- | --- |
| Flush procedure | RecoveryCleanupControl publishes frontend, rename, ROB, LSU/STQ, tile, PE, and BROB cleanup intents. STQFlushPrune matches model FlushBus rules; LIQ/ResolveQ pruning uses LSID snapshots. |
| Instruction noflush | Non-flush pointer separates speculative STQ from SCB. SCB contains only stores guaranteed not to be flushed. |
| ROB commit | Commit sidecars drive STQ mark/free, local relation release, GPR MapQ commit, trace, and block scalar_done sequencing. |
| Load/store ordering | TSO, LSID issue order, BROB-ring-age then LSID forwarding order, store drain program order. |
| LHQ ordering | LHQ records E4 hit metadata. Oldest conflicts are selected by `(bid, lsID)`. |
| Cross-page/device/non-cache flows | Platform memory type controls Device/MMIO ordering; precise trap envelope required. |
| SVE load/store flows | Replace with VEC/MPAR/MSEQ bridged memory and tile memory. |
| Atomic/streaming/DCZVA/mem copy | Use Linx catalog-defined atomics, fences, block templates, `MCOPY`, `MSET`, or TMA/TEPL equivalents. |
| Resource | Current model evidence: STQ 2048, scalar STQ 256, scalar load windows 64, scalar store windows 256, SCB default in skill 16 entries, outstanding 8. |
| Coherence | SCB/DCache/CHI response owners preserve request/response ordering; WriteResp defines completion. |
| RAS/error flows | Map to precise trap/error reporting with owner row metadata and trace-visible trap payload. |
| Skid/misalign handling | Keep skid/cancel owner-local and precise; crossline/crosspage paths split masks/descriptors explicitly. |
| LIQ/LHQ structure | LIQ holds working loads and replay/refill state; LHQ holds resolved hit info. Flush/replay owners are separate from memory progression owners. |

## 13. LSU MDB, Exclusive, and Atomic Flows

Detailed LinxCore chapters: [10-linxcore-lsu-structures-instructions.md](10-linxcore-lsu-structures-instructions.md), [11-linxcore-lsu-pipelines.md](11-linxcore-lsu-pipelines.md), [12-linxcore-ordering-resources.md](12-linxcore-ordering-resources.md).

| ARM section | LinxCore microarchitecture spec |
| --- | --- |
| LFB | Linx refill/miss request path must preserve request identity, refill data, response ordering, and LIQ relaunch. |
| Load request merge | Merge policy belongs to refill/miss owner and must not violate LSID precision or Device/MMIO ordering. |
| SCB request | SCB egress selects valid rows, prefers full lines, uses deterministic not-full fallback, and issues DCache/CHI lookup/ownership requests. |
| LIQ FSM | LIQ states include Wait, working/forwarding, store-data-not-ready, miss/refill wait, replay-ready, and precise flush. |
| STQ FSM | STQ states include address/data partials, `ST_ALL`, `STQ_WAIT`, `STQ_COMMIT`, free/pruned. |
| Forward | Byte-granular nearest older store selection by `(BID, LSID)`; data-not-ready causes wait/replay. |
| TLB interface | Platform translation and permission faults produce precise trap state. |
| MDB target core | MDB stays in LSU/IEX/BCTRL interaction: SSIT records conflicts, BMDB reports intent, ROB nuke retirement owns visible recovery. |
| Exclusive | ARM exclusive monitor machinery is not inherited. Linx exclusive-like behavior must be catalog/profile-defined. |
| Atomic | Linx atomics participate in LSID ordering and TSO with `.aq/.rl/.aqrl`; far/near ARM atomic structures are not normative. |
| AlwaysNear/CAS SelfRu | Not a LinxCore requirement unless a Linx profile defines a matching optimization. Keep as open performance question, not spec. |

## Coverage Status

The ARM spec has been migrated at section-cluster granularity into LinxCore terms. The sibling LinxCore chapters provide the detailed contracts. Remaining gaps are intentionally tracked as open questions where existing LinxISA/LinxCore sources do not yet freeze policy.
