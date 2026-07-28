# LinxCore Chisel OOO Upgrade Plan

## 1. Document status and accepted direction

This document is the review-ready implementation plan for replacing the current
bring-up OOO path with a canonical `LinxCoreOoo`. It incorporates the useful
physical-closure techniques from the LinxCore830/930 ARM OOO reference notes,
but keeps Linx block-ISA identity, recovery, and register semantics normative.

The ARM reference is an implementation notebook across more than one product
revision. Its port counts, queue depths, and pipeline techniques are design
inputs, not a second architectural specification.

The following decisions are accepted for this plan:

- OOO is one independently testable module covering `D1 -> D2 -> D3 -> S1`.
- The default product has four hardware threads (`STID_COUNT = 4`). Each thread
  owns D2/D3/S1 staging state; the expensive combinational decode, grouping,
  rename, and steering logic is shared. Each stage selects at most one STID per
  cycle, while different stages may select different STIDs in the same cycle.
- A Linx instruction may expand to multiple uops. Ordinary expansion follows
  an ARM-style uop-break plan; template expansion remains owned by the external
  CTU between IFU and OOO.
- BSTART fuses forward and BSTOP fuses backward when legal. The surviving uop
  carries two boundary bits, `start` and `stop`; a one-instruction block may
  carry both.
- D2 computes virtual RID/group placement and all resource demand. D3/S1
  reserve and publish the physical ROB group and its member uops.
- RID identifies a ROB group, not an individual uop. BID remains the separate
  native block identity allocated by the per-STID BROB.
- P rename covers 24 absolute architectural registers. T and U use separate
  relative-index sequential rename. There is no FP or CC rename in OOO.
- Tile registers are intentionally outside OOO rename. Tile state is handled
  after IEX dispatch by the tile execution subsystem.
- D2 refills banked PTag staging FIFOs from the free list. D3 consumes only
  staging-FIFO tags; it never runs a wide free-list priority encoder directly.
- SMAP payload includes PTag, IQID/producer token, and ready state. Same-cycle
  RAW and WAW relations are resolved by oldest-to-youngest inlining.
- Dispatch dynamically balances physical IQ banks using exact entry and write
  port credits. It is older-first and may accept only a contiguous prefix.
- S1 writes an IEX speculative issue slot. IEX S2 binds the reserved physical
  IQ entry; the row is first pick-eligible at S3.
- OOO owns a 64-entry base-plus-byte-offset PC buffer.
- Recovery restores CMAP to SMAP and replays surviving MapQ rows. Rename is
  blocked for the affected STID until rebuild completes.
- Every STID has a ROB non-flush frontier that may advance ahead of commit and
  authorize explicitly nonspeculative execution without performing commit.

This plan deliberately supersedes the following older planning assumptions:

- ROB/BROB allocation is no longer a D1-to-D2 physical mutation.
- BSTART/BSTOP do not always require standalone ROB rows.
- one instruction does not imply one uop or one RID.
- S1 is not the final physical IQ residency stage.
- two-STID verification is not sufficient for the product target.

The corresponding normative architecture documents must be updated in Phase
O0 before public Chisel bundles change.

### 1.1 Repository evidence used by this plan

- [microarchitecture contract](../architecture/microarchitecture.md) for
  STID, BID/BROB, P/T/U, completion, memory-order, and recovery ownership;
- [block-control-flow contract](../architecture/linxisa_block_control_flow.md)
  for prediction validation, in-body BSTART reentry, and boundary commit;
- [opcode catalog](../../src/common/opcode_catalog.yaml) and generated
  `FrontendOpcodeDecodeTable` for current decode metadata;
- [CodeTemplateUnit contract](../architecture/code_template_unit.md) and
  [template reservation oracle](interfaces/TemplateD3ReservationFill.md) for
  template child order and count;
- [OOO improvement analysis](linxcore-chisel-ooo-improvement-design.md) for the
  current Chisel migration surface;
- the local LinxCore830/930 ARM OOO reference notes for physical banking,
  preallocation, grouping, dispatch, PC compression, SMT, and non-flush ideas.

Where these sources disagree with an accepted decision above, this document
records the target and Phase O0 must repair the normative source before RTL
promotion.

### 1.2 Implementation ledger

| Packet | Status | Evidence | Remaining exit work |
|---|---|---|---|
| O0 normative contracts | In progress | microarchitecture, block-control, pipeline-stage, CTU, common/OOO bundle pages, generated 689-record opcode recipe audit, exact S1/S2/S3 residency contract, and canonical P1/I1/I2 read-lane contract updated | global-cancel integration contract |
| O1 packet family | Implemented | `OooParams`, exact identity/stage bundles, 2/4/6 width elaboration | conservation monitors beyond stage occupancy |
| O1 four-thread shell | Implemented | private per-STID D2/D3/S1 rows, stable shared grants, 1/2/4 STID tests | WFI/inactive inputs and bounded starvation counters |
| O2 decode/expand/fuse | Implemented | schema-v2 generated recipes; fixed-four-wide IFU to per-STID 2/4/6 raw reservoir; parameterized canonical D1; exact P/T/U and pair operands; precise traps; exact CTU/complex diverted-parent sidebands; same/cross-cycle three-parent boundary fusion; focused UT/IT | the catalog has zero dispatch-owned complex forms, so unresolved macro/atomic forms remain fail-closed; the external CTU recipe producer is connected at O9 |
| O3 grouped ROB/BROB/PC | Implemented | D2 virtual grouping and retention; D3 provisional claims; atomic S1 grouped ROB; exact member completion/commit; native BID/generation BROB; fixed-partition 64-entry byte-offset PC buffer; one shared reserve/publish/commit/recovery coordinator; O4 RENU and O5/O6 integration; O8.3d ROB bank/subbank address partition; O8.3e retained two-pass bounded recovery scan; O8.3f registered head token followed by retained payload read; O8.3g four-bank PC address partition with six-read preservation; O8.3h retained bounded PC recovery scan and row-repair masks; O8.3i fair retained bounded non-flush scan with atomic publication; O8.3j three explicit two-read base-payload replicas with common write/free broadcast | PC metadata/write macro realization remains |
| O4 P/T/U RENU | Implemented | generation-qualified banked PTag staging/free-list owner; per-STID provisional leases; P SMAP prepare/publication; bundle-wide RAW/WAW inlining; ordered exact P MapQ rows in parameterized low-index subbanks; registered commit/recovery row selection before PTag return and pointer update; serialized CMAP/old-PTag commit walk; independent per-STID T/U sequential reserve, same-bundle relative bypass, wrap-qualified local tags, exact local MapQ publication; every-logical-uop retire sidecar; ordered T/U relation-CMAP mark/deallocation; post-clean exact block release; atomic P/T/U commit-owner start; exact recovery suffix authority; killed-current-PTag return and survivor replay; exact T/U suffix/cursor rollback; three-owner atomic coordinator; four-STID randomized sequential reference; exact producer IQ class/bank/entry binding and real IEX S1 transfer | close default-width synthesis timing |
| O5.1 dispatch reservations | Implemented | generated demand compaction; exact class/bank/write-port/slot reservation leases; free/provisional/published conservation; full-owner publication/release validation; O3/O4 common-fire integration; O8.2 bounded hierarchical first-free selection; focused UT/IT | add occupancy/in-flight/PTag bank cost steering and safe-mode policy |
| O5.2 IEX residency | Implemented | exact Decoupled O3-to-S1 transfer; per-STID retained S1; pending-target exclusion; fair atomic S2 bind; registered S3 pick enable; compact scheduling row plus memory-backed execution sidecar; generation-qualified P/T/U ready scoreboards; wakeup N to pick N+1; exact dispatch-coupled release; focused UT/IT | multi-pick policy remains later IEX scope |
| I0.1 P1/I1/I2 read lane | Implemented | exact selected-row validation; retained atomic P/T/U plus PC read attempt; explicit grant/deny; denial-to-repick; partial-response rejection; retained I2 data under backpressure; exact recovery cancellation; real PC-buffer IT | canonical RF/bypass and E1 terminal release remain |
| I0.2 oldest-ready pick | Implemented | reusable class/bank-domain selection; modular RID-generation/slot/member age; per-STID oldest plus cross-STID work-conserving RR; retained token; canonical IQ-row in-flight claim; exact retry-to-repick; recovery block/cancel; in-flight terminal-release guard; UT and generated-RTL structure evidence | freeze default pipe map, class-specific blockers, and liveness thresholds |
| I0.3 picker-to-P1 join | Implemented | catalog-generated PC-read policy for 28 exact opcode forms; primary-parent PC index derived at S2 bind; exact token/sidecar join; typed malformed join; picker-to-P1/I1/I2 composition; denial/partial-response/P1 rejection feedback to canonical `inFlight`; end-to-end retry/repick IT | shared RF/PC arbitration, bypass, and E1 terminal release |
| I0.4 multi-domain issue fabric | Implemented | parameterized `iexIssueDomainCount`; one canonical IQ with N picker/query/retry ports; N private bridge/P1-I2 lanes; enforced class/bank projection disjointness; domain-qualified retry; aggregate S1/IQ/lane/recovery quiescence; two-domain grant/deny/retry/release IT | freeze default class/bank map, class-specific blockers, shared RF/PC arbitration, bypass, and E1 terminal release |
| I0.5 atomic I1 read arbiter | Implemented | bounded feasible-subset selection across issue domains; same-STID wrap-qualified age plus cross-STID RR; independent 6P/4T/4U/PC port parameters; complete-group grant/deny; exact source/PC port mapping and readyless response crossbar; malformed-shape denial; focused UT | connect canonical P/T/U data owners and PC buffer, bypass/load generations, and direct issue-fabric composition |
| O6.1 typed fast resolve | Implemented | generated whitelist; retained per-STID typed entries; exact boundary/writeback/wakeup/trace/completion fork; O3/ROB integration and exact global cancellation; focused UT/IT | O9 consumer/top activation remains |
| O6.2 non-flush | Implemented | grouped ROB-owned per-STID window; exact typed proof intake/rejection; interrupt freeze; recovery recomputation; direct ROB UT and coordinator IT | O9 final consumer activation remains |
| O7 recovery and CTU | Implemented | O7.1 grouped ROB exact suffix truncation; O7.2 retained all-owner recovery through CTU prepare, one common destructive apply, P/T/U rebuild, and exact IFU restart acknowledgement; O7.3 adds per-STID CTU claim/plan/lease state, ordered canonical-child reinsertion, multi-RID parent semantics, stale-generation rejection, and prepare/apply/abort recovery IT | unresolved complex parents remain fail-closed; the external CTU recipe engine and core-top wiring are O9 integration work |
| O8 physical closure | In progress | O8.1 separates frequently scanned IEX scheduling state from a stable-slot memory-backed execution sidecar; O8.1b retains and scans exact recovery state by parameterized slices across all class/banks before one common apply; O8.2 replaces each bank-wide free-entry encoder with a bounded two-level selector; O8.3a closes independent transaction/tail/epoch recovery reference state and exact wrapped-tail reuse; O8.3b physically partitions each per-STID P MapQ by low logical-index bits while preserving one exact ordered ring; O8.3c registers commit/recovery row selection before PTag return and pointer mutation; O8.3d maps every grouped-ROB access through parameterized low-bit banks and following-bit even/odd subbanks while rejecting publication/retirement widths that would collide within one ordered prefix; O8.3e replaces the full-window ROB recovery view with two retained bounded passes and one common apply; O8.3f retains the exact selected head token before banked payload capture and permits pointer mutation only from the retained row; O8.3g maps every PC-base access through parameterized low-bit banks while retaining all six logical reads; O8.3h bounds PC recovery compare/read depth and retains apply masks; O8.3i replaces the all-STID full-window non-flush network with one retained bank-width scanner and atomic final publication; O8.3j separates canonical PC metadata from three fixed two-read base-payload replicas | physical PC metadata/write macro realization, commit-selection timing, occupancy/in-flight/PTag steering, default-width synthesis timing, and 2/4/6 timing closure |
| O9 integration/promotion | Not started | current compatibility owners remain migration evidence | canonical top integration, legacy removal, and benchmark promotion follow |

“Implemented” in this ledger is packet-scoped; it does not promote the current
benchmark hierarchy to canonical OOO.

## 2. Product outcome

The canonical graph is:

```text
IFU fixed-64b Instruction Buffer --> D1 decode/fuse/divert --+
                                                             +--> OOO ingress join
external CTU <-- claim/plan lease --> canonical child rows ---+       |
                                                                     v
                                             D2 virtual RID/group plan
                                             resource and PC preview
                                                          |
                                                          v
                                             D3 physical ROB + RENU
                                             IQ bank reservation
                                                          |
                                                          v
                                             S1 publish / fast resolve
                                                          |
                                                          v
                                             IEX S1 speculative slots
                                             IEX S2 physical IQ bind
                                             IEX S3 pick eligible
```

`LinxCoreOoo` must elaborate and pass at instruction decode widths 2, 4, and 6.
Width 4 is the first product configuration. Width 2 is the bring-up/reference
configuration. Width 6 is the scale/timing configuration. Instruction width,
expanded-uop width, rename destination width, dispatch width, and retirement
width are distinct parameters.

The module owns:

- full 16/32/48/64-bit decode after IFU has expanded the instruction container
  to 64 bits;
- ordinary multi-uop break and block-boundary fusion;
- D2 virtual ROB grouping and D3/S1 physical ROB publication;
- per-STID BROB admission and native BID assignment;
- P, T, and U rename, commit, and recovery;
- PC compression and PC-buffer release tracking;
- dispatch steering and IEX issue-slot reservations;
- fast-resolve, exact completion intake, ordered retirement, and non-flush;
- four-thread stage arbitration, quotas, starvation prevention, and recovery;
- the OOO side of the external CTU ingress and cancellation contract.

It does not own:

- IFU prediction, ITLB, L1I, or the IFU Instruction Buffer;
- CTU template state or expansion sequencing;
- physical IQ rows, pickers, RF arbitration, execution pipes, or W2;
- Tile register rename or Tile execution state;
- LIQ, STQ, MDB, SCB, MissQ, refill, cache, or coherence state;
- architectural effects that belong to commit, IEX, LSU, BCTRL, or CTU child
  execution.

## 3. Non-negotiable Linx invariants

ARM physical techniques may be reused only while these invariants hold.

### 3.1 Native block identity

- Every ROB group belongs to exactly one `(PE, STID, BID)`.
- Native BID is exactly `BID_W = log2Ceil(BROB_ENTRIES)`; the default BROB has
  256 entries per STID and therefore an 8-bit BID.
- BROB wrap/generation and age are separate internal state. They are never
  packed above BID on architectural or shared interfaces.
- BID values are not compared by unsigned magnitude. Age and kill membership
  come from the selected STID's BROB ring state.
- A group cannot cross a BID boundary. Boundary fusion changes row shape, not
  this ownership rule.

### 3.2 Thread qualification

- RID order is meaningful only within one STID's ROB order domain.
- BID order is meaningful only within one STID's BROB ring.
- Every shared SMAP payload, IQID, ready bit, completion, wakeup, dispatch
  reservation, PC-buffer reference, and recovery request is STID-qualified.
- A recovery for one STID produces zero mutation in every other STID.

### 3.3 Single state owner

- ROB, BROB, each rename map, each free list, each MapQ, PC-buffer allocation,
  and every IQ entry have one canonical owner.
- Preview, credit, reservation, and trace structures may cache information but
  cannot become a second allocator or a second architectural state machine.
- D2 virtual IDs do not become valid physical ROB rows until the D3/S1 publish
  transaction succeeds.

### 3.4 Exact terminal transactions

- Completion is keyed by the exact ROB group and member token; slot-only
  completion is forbidden.
- Writeback, wakeup, redirect, completion, and release fire from one retained
  terminal transaction, not from loosely related valid pulses.
- Fast resolve marks a group/member complete but never commits it out of order.

## 4. Parameter model

The first canonical defaults are planning targets, not hard-coded constants.

| Parameter | Default | Contract |
|---|---:|---|
| `stidCount` | 4 | Product value; tests also elaborate 1 and 2 |
| `instructionDecodeWidth` | 4 | Supported values 2, 4, 6 |
| `decodedUopWidth` | 8 | Ordinary D1/D2 uop rows per selected STID/cycle |
| `renameWidth` | 8 | D3 uop rows; independent of instruction width |
| `dispatchWidth` | 8 | S1 speculative issue slots per selected STID/cycle |
| `retireGroupWidth` | 4 | Bring up at 2; scale/timing target 6 |
| `maxInstPerRobGroup` | 4 | ARM-style grouping cap |
| `maxOrdinaryUopsPerGroup` | 12 | Larger templates span multiple groups |
| `robGroupsPerStid` | 64 | 256 total logical groups at four STIDs |
| `robBankCount` | 8 | Low RID-slot bits select the physical bank; must cover publication and retirement widths |
| `robSubbankCount` | 2 | Following RID-slot bits select even/odd timing subbanks inside each bank |
| `brobEntriesPerStid` | 256 | Native BID remains 8 bits by default |
| `pArchRegs` | 24 | Absolute architectural P namespace |
| `pPhysRegs` | 128 | Minimum: 96 committed mappings plus eight speculative tags guaranteed per STID; 192/256 are scale points |
| `pTagBanks` | 2 | Bank-aware allocation, RF, and IQ steering |
| `pMapQDepthPerStid` | 256 | Independent of ROB and physical IQ depth |
| `pMapQSubbankCount` | 2 | Power-of-two physical partition of each ordered P MapQ; low logical-index bits select the subbank |
| `pcBufferEntries` | 64 | Shared storage with per-STID quotas |
| `pcBankCount` | 4 | Low per-STID local-index bits select the address bank; must cover allocation and retirement prefix widths |
| `pcRecoveryScanGroupsPerCycle` | 4 | Power-of-two divisor of the ROB window; bounds PC recovery token/metadata comparisons per cycle |
| `pcOffsetWidth` | 7 | Byte offset, required by 2/4/6/8-byte instructions |
| `pcWritePorts` | 3 | Physical closure target |
| `pcReadPorts` | 6 | Commit, branch validation, trace, and execution |
| `pcReadReplicaCount` | 3 | Compile-time fixed consumer mapping; at most two readyless reads per replica |
| `iqClassCount` | 8 | Generated canonical dispatch classes |
| `iqBankCount` | 8 | Class-local physical bank count |
| `iqEntriesPerBank` | 32 | 2048 total class/bank rows before O8 sizing closure |
| `iqWritePortsPerBank` | 3 | Exact S2 reservation/write-port identity |
| `iexRecoveryScanEntriesPerBankPerCycle` | 1 | Power-of-two divisor of bank depth; bounds recovery CAM width independently of IQ capacity |
| `iexWakeupPorts` | 8 | Generation-qualified P/T/U ready-table update ports |

Resource widths must be represented as a vector, not inferred from one decode
width:

```text
InstructionDemand {
  instructionRows
  decodedUops
  robGroups
  brobSlots
  pcBaseWrites
  pDestinations
  tAllocations
  uAllocations
  mapQRows
  dispatchWrites[UopClass]
  dispatchWrites[IqBank]
  loadIds
  storeIds
}
```

Required elaboration points include unequal capacities. At minimum, tests must
cover a small ROB with a deeper MapQ, asymmetric IQ banks, and independent
32/40-bit full LSID while BID/GID/RID remain ROB/BROB-sized.

## 5. Canonical instruction, uop, group, and block identity

### 5.1 Architectural parent and expanded children

Every decoded instruction receives an `instructionParentId` before expansion.
Expansion may map one parent to several children; fusion may map several
architectural parents to one surviving child. Each canonical child therefore
carries:

```text
primaryInstructionParentId
architecturalParentRefs[0..2] // optional fused BSTART, carrier, fused BSTOP
architecturalParentCount
uopOrdinal
uopCount
firstUop
lastUop
architecturalTraceOwnerMask
templateGroupId?       // only for CTU children
```

Every `architecturalParentRef` retains its own PC, raw encoding, length,
instruction identity, and precise-exception attribution. Exactly one member
owns each parent's architectural trace and completion record. One fused member
may own two or three ordered parent trace rows; internal expansion children do
not emit duplicate parent rows. Fusion reduces execution-uop count, never the
architectural instruction count used by retirement, trace, PMU, or single-step.
Interrupts are taken only at ROB-group boundaries. Debug single-step and any
mode requiring an instruction-by-instruction interrupt boundary force
one-parent groups and disable boundary fusion for the affected window.

### 5.2 RID/group model

RID identifies one physical ROB group. The exact group key is:

```text
RobGroupKey = { PE, STID, ridSlot, ridGeneration }
```

The exact completion key is:

```text
RobMemberKey = { RobGroupKey, BID, brobGeneration, memberIndex,
                 residentGeneration }
```

During migration, existing GID fields remain an adapter-visible group ordinal.
They must not become a competing age domain. The canonical specification must
either alias GID to the wrap-qualified ROB group key or remove it from internal
interfaces after every consumer is migrated.

A ROB group contains up to four adjacent pre-fusion architectural instructions and up to
`maxOrdinaryUopsPerGroup` member uops. A template instruction may span multiple
RID groups while retaining one `instructionParentId`, `templateGroupId`, and
BID. The final template member gates parent architectural retirement.

The ROB entry stores member-valid, expected-resolve, completed, exception, and
trace-owner summaries. Completion may be implemented as a bit vector or typed
resolve counters, but every decrement/set is member-qualified and idempotent.

### 5.3 Group start/end rules

Grouping happens after illegal detection, uop break planning, and boundary
fusion. A group never crosses any of these boundaries:

- STID or PE change;
- BID change, fused `stop`, or fused `start` that opens a new block;
- precise exception/trap boundary;
- branch or operation that may request a redirect/flush;
- system, fence, cache/TLB maintenance, or nonspeculative instruction;
- CTU parent/child-chain boundary that requires atomic parent retirement;
- PC-buffer base allocation, because one ROB group records at most one PC-base
  release obligation;
- group instruction/uop/member-count limit.

Loads, stores, and other potentially faulting instructions start a new group.
The final spec may allow following non-faulting instructions in that group only
when all members share the same commit/flush condition and exception PC remains
unambiguous. Conservative one-faulting-instruction groups are the bring-up
configuration.

## 6. Multi-uop decode and expansion

### 6.1 Generated expansion metadata

The opcode catalog must gain canonical metadata generated into pyCircuit,
Chisel, model, and audit artifacts:

```text
uopRecipe
uopCountMin / uopCountMax
complexBreak
lateSplitKind
fusionHeadClass / fusionTailClass
fastResolveClass
implicitSourceMask / implicitDestination
sideEffectOwner
requiresTargetValidation
mayTrapLate
sourceCount / destinationCount[P,T,U]
dispatchDemand[UopClass]
mayTrap / mayRedirect / nonspeculative
```

The generated table, not hand-written switch statements in multiple modules,
is the classification authority.

The checked-in schema-v2 catalog currently contains 689 encoded records and
658 unique opcode IDs.  The Chisel decode table has 687 encoded rules because
it excludes internal-only rows and adds the architectural 16-bit `C.SETRET`
alias.  A deterministic generator gate compares both the Scala table and the
human-readable audit.  Every generated rule is exercised through the hardware
priority decoder, not only inspected as Scala data.

### 6.2 Break locations

Expansion follows the ARM-style division of labor:

- D1 handles fixed one-to-five-uop recipes with replicate-and-shift-down lane
  mapping while preserving instruction and uop order.
- D2 may finish recipes whose source/destination fields or grouping depend on
  full decode results.
- A retained internal `ComplexUopBreakEngine` handles ordinary instructions
  that exceed the single-cycle D1 expansion limit. It emits into the selected
  STID's D2 expansion FIFO and does not globally block other STIDs.
- D3/S1 may perform a late split only when one logical operation must enter
  different queue families, for example a store becoming STA plus STD. All
  late children reserve atomically; no child is published alone.
- A late merge is permitted only for a documented paired-memory recipe and
  must preserve all parent/member completion counts.
- CTU exclusively handles `FENTRY`, `FEXIT`, `FRET_RA`, and `FRET_STK`; the
  ordinary complex-break engine must not also claim them.

Undefined/illegal detection precedes uop break and fusion. An illegal parent
emits one precise trap member and no speculative child side effect.

### 6.3 Expansion atomicity

For every parent, the plan records exact child count and resource demand before
the first child becomes visible to D3. A parent may use one of two mechanisms:

- reserve the complete child set; or
- retain an explicit expansion lease with enough D2 buffering to guarantee
  progress while children are emitted.

Partial publication without a retained lease is forbidden.

### 6.4 Initial recipe families

The O0 catalog audit must turn these policy rows into exact per-opcode recipes:

| Instruction family | Planned child shape | Break owner |
|---|---|---|
| scalar ALU/shift/multiply/divide | one execution uop unless the operation needs a documented helper | D1 |
| scalar load | address/load uop; writeback remains the load terminal path | D1 |
| scalar store | `STA + STD`, sharing parent, RID group, BID, and full LSID | D3/S1 atomic late split |
| pair/dual load such as `HL.LDIP` | address/request children plus two destinations as required by the final LSU contract | D1/D2; exact recipe frozen in O0 |
| pair/dual store such as `HL.SDIP` | one or more address/data children with atomic publication and one parent trace | D1/D2 plus S1 class split |
| atomic/CAS family | address, data/compare, and result children according to LSU/AMO ports | D1/D2; never inferred as a scalar load |
| pure BSTART/BSTOP | zero standalone children when fused; one `BoundaryMetadata` member otherwise | D1 fusion |
| `START_CALL_32/48` | one combined control/value child with start metadata and RA result | D1 decode, typed S1 resolve or BRU fallback |
| `FENTRY/FEXIT/FRET.*` | ordered ALU/LOAD/STORE/SETC_TGT child stream | external CTU |
| `ERCOV/ESAVE/MCOPY/MSET` | unspecified | fail closed until a normative expansion recipe exists |

For pair-memory encodings, QEMU decodetree field positions are normative for
D1 operand extraction.  Pair loads carry `RegDst0[27:23]`, `RegDst1[15:11]`,
and either one immediate-form base or base-plus-index sources.  Immediate
pair stores (`*IP`) carry three register sources (`SrcD`, `SrcD1`, base) plus
an immediate; register-indexed `*P` forms carry four register sources
(`SrcD[47:43]`, `SrcD1`, base, index).  Pair-load destinations must both be P
GPRs or D1 emits one precise trap member.

The exact ARM-like recipes are selected because Linx opcode shapes are close to
their ARM counterparts, but Linx source/destination classes, BID effects, full
LSID, and parent commit semantics remain authoritative.

## 7. Block-boundary fusion

### 7.1 Boundary bits

Every canonical uop carries:

```text
boundaryBits[1:0] = { stop, start }
```

- BSTART normally fuses forward into the first legal following uop and sets
  `start=1`.
- BSTOP normally fuses backward into the youngest legal preceding uop and sets
  `stop=1`.
- A one-instruction block may carry `start=1, stop=1`.
- If fusion is impossible, a standalone boundary uop is emitted and is
  fast-resolved after its ROB/BROB/PC/checkpoint state is published.

The surviving uop carries a boundary sidecar containing original marker PC,
raw encoding, length, boundary kind, static target, final prediction record,
checkpoint, closing BID, opening BID, and whether the boundary is explicit or
implicit. An in-body BSTART needs both old-block close and new-block open roles;
one unqualified BID field is insufficient.

The fused boundary remains an architectural parent in the surviving member's
parent vector. For example, `BSTART + body + BSTOP` may use one execution uop
with both bits set, but retirement still observes three ordered architectural
instructions and their original PCs/encodings.

Fusion itself never mints, increments, or rewrites BID. D2/BROB preview and the
D3 BROB allocation owner supply the identities. A backward-fused BSTOP sidecar
names the carrier's closing BID. A forward-fused BSTART carrier executes in the
new/opening BID while its sidecar may also name the implicitly closed old BID.
For a single-instruction block, start and stop refer to the same opening/current
BID after the prior-block close obligation has been represented separately.

### 7.2 Legal fusion

Fusion requires:

- same PE/STID and contiguous architectural order;
- the same prediction/fetch epoch and no illegal, fetch-fault, or precise-trap
  row between the pair;
- enough retained history for cross-cycle fusion;
- no CTU/complex-break ownership conflict;
- no group or PC-buffer condition that would make the boundary side effect
  ambiguous;
- exact preservation of the BSTART split/reentry rule.

D1 keeps one retained tail candidate per STID for backward BSTOP fusion and one
retained boundary candidate per STID for forward BSTART fusion. These histories
are generation-qualified and cleared by matching recovery. Fusion never patches
an already published ROB group: a candidate remains in the D1 fusion hold until
the adjacent instruction or an explicit end-of-stream/serialization condition
forces fusion or standalone fallback.

### 7.3 Linx split semantics

A BSTART encountered in a block body closes the previous block and opens a new
one at the same architectural PC. Fusion may represent this as `stop` on the
previous surviving row and `start` on the next/new-block row, but it must reuse
the allocated same-PC reentry token and must not allocate or retire a duplicate
marker.

BSTART.CALL does not implicitly write `ra`. The ISA `START_CALL_32/48` forms
are different: they combine call transfer, boundary start, and an RA result and
therefore cannot be treated as pure boundary metadata. Conditional direction
and indirect/return target validation remain BRU work. Direct/call static-target
validation is performed at S1 by the boundary fast-resolve path.

## 8. Four-thread pipeline and arbitration

### 8.1 Physical staging

The baseline structure is:

```text
D1 shared combinational decode/fuse/break
D2Hold[STID_COUNT]
D3Hold[STID_COUNT]
S1Hold[STID_COUNT]
```

Each stage grants at most one STID per cycle. D1, D2, D3, and S1 have independent
arbiters, so the pipeline may simultaneously process different STIDs at
different stages. A stage selection remains stable while its retained output
is blocked. There is no configuration in which one D1, D2, D3, or S1 stage
advances two STIDs in the same cycle. Baseline commit and non-flush advancement
also select at most one STID per cycle.

### 8.2 Arbitration policy

The default grant priority is:

1. retained transaction that already owns a downstream reservation;
2. recovery/flush drain obligation;
3. ready STID with the oldest starvation age;
4. ready STID with more private capacity and no downstream class stall;
5. round-robin tie break.

Yield/WFI/inactive state removes or deprioritizes a thread without flushing
other threads. Configurable starvation counters force service after a bounded
number of grants to peers.

Commit, non-flush advancement, MapQ rebuild, PC-buffer maintenance, and shared
PTag refill have separate fair STID arbiters. A faulting or blocked head in one
STID cannot prevent eligible commit from another, except when a truly singleton
architectural resource is explicitly serialized.

### 8.3 Shared-resource quotas

ROB groups, PTags, PC-buffer rows, and shared IQ entries expose minimum per-STID
reservations plus borrowable surplus. Threshold stalls are admission controls;
they do not confiscate already-owned rows. Both hard capacity and soft quota
stall reasons are observable.

## 9. D1 stage: decode, fusion, and uop planning

D1 accepts one dense same-STID instruction prefix from OOO ingress. For every
candidate it:

1. validates fixed-64-bit container metadata and original 2/4/6/8-byte length;
2. runs most-specific-mask opcode decode;
3. detects fetch fault, illegal encoding, and early precise trap;
4. extracts P/T/U architectural operands, immediates, prediction, and boundary
   sidecars;
5. forms `InstructionExpansionPlan` and expansion-resource demand;
6. applies same-cycle and retained-history boundary fusion;
7. emits dense ordered canonical uops or diverts the parent to CTU/complex
   break ownership;
8. computes group-start/end hints without allocating RID, BID, PC rows, PTags,
   or IQ state.

The IFU remains limited to instruction length and BSTART/BSTOP predecode. ARM's
static IFU resource checks are instead implemented at OOO ingress/D1 so full
Linx decode ownership does not leak back into I-F4.

D1 backpressure includes D2 per-STID staging, expansion FIFO/lease capacity,
and static maximums such as decoded-uop lanes, destinations, boundary count,
late-split count, and per-class dispatch writes. It accepts only a contiguous
instruction prefix; a suffix is retained unchanged.

Canonical D1 is split into a combinational canonical decoder and a retained
per-STID fusion-history owner.  The history holds the last fusion-eligible
canonical uop until its architectural successor or an explicit end-of-stream
is known.  It never patches a published member.  Matching recovery cancels
only the selected STID.  A terminal full-width packet that cannot represent
the retained parent plus all new parents drains the retained marker/carrier as
a legal standalone fallback before retrying the unchanged packet.

Demand counter widths encode the maximum input demand, not the corresponding
physical capacity.  In particular a six-instruction window can represent 12
pair destinations, 12 dispatch writes, and 12 memory requests so D2 can detect
and stall over-capacity prefixes without truncated counters.

## 10. D2 stage: virtual RID/group and resource preview

D2 is the main precomputation stage. It performs no public physical ROB write.
For the selected STID it:

- groups expanded members and computes virtual RID/group offsets from that
  STID's reserved tail;
- computes member indices, expected resolve counts, trace owner, and group
  completion summary;
- previews BID/BROB allocation for every fused or standalone new-block start;
- assigns PC-buffer base/index/byte-offset demand;
- calculates exact PTag, MapQ, T/U, LSID, S1 slot, IQ bank/port, and late-split
  demand including retained D3/S1 consumption;
- selects PTag banks and checks D3 staging-FIFO availability;
- precomputes physical ROB bank/write ports and group-full conditions;
- records all stall reasons before allowing D3 selection.

Virtual RID assignment is a reservation-token computation, not a physical
valid bit. The token includes a tail epoch. D3 rejects and recomputes a stale
token after commit, recovery, or another accepted writer changes that STID's
tail.

### 10.1 PTag prefetch

Each PTag bank owns a small staging FIFO. A D2-side background refill engine:

1. observes FIFO vacancies and per-STID demand;
2. finds free PTags in the banked free list;
3. atomically removes selected tags from the free list and writes the FIFO;
4. records owner bank, allocation epoch, and optional STID quota attribution.

D3 can allocate only from these FIFOs. Unconsumed tags remain in the FIFO;
recovery does not blindly return them. Refill and commit/recovery return paths
use one arbiter and conservation checker. Transaction ID rotates the preferred
bank, while an availability-aware oldest-to-youngest selector falls forward to
another staged bank when that preference is empty. The selector accounts for
earlier destinations in the same bundle before assigning younger ones, so it
cannot overbook staging credit and cannot deadlock on skewed physical-bank
returns while another bank remains allocatable.

### 10.2 Maximum-prefix rule

D2 may accept the largest older contiguous prefix for which every group and
late-split child has all required resources. If member `k` cannot proceed,
members younger than `k` do not proceed. An instruction's children and a fused
boundary pair are indivisible.

## 11. D3 stage: physical ROB publication and rename

D3 consumes one selected STID's valid D2 plan. The transaction succeeds only
when physical ROB banks, BROB slots, rename resources, PC-buffer writes, and S1
issue-slot reservations can all be retained through publication.

At D3 prepare:

- virtual RID offsets are translated into exact physical `RobGroupKey`s;
- BROB prepares native BID and separate generation for each new block;
- the PC buffer prepares required base-row reservations;
- ROB banks form provisional group headers and member sidecars;
- P rename reads SMAP and selects PTags only from staging FIFOs;
- T and U owners prepare independent relative-index sequential allocation;
- dispatch selects physical IQ class/bank and prepares an IEX reservation token;
- fast-resolve class and required terminal sinks are finalized.

The physical update is deliberately split into two retained events:

1. `d2D3ReserveFire` claims the exact RID/BID/PC/IQ reservation set, consumes
   selected PTags from staging FIFOs, writes recovery-visible provisional ROB
   rows, and advances only reservation pointers. D3 then holds the complete
   renamed transaction.
2. `d3S1PublishFire` writes SMAP/MapQ and remaining ROB sidecars, publishes the
   S1 speculative slots or fast-resolve requests, and sets `groupComplete`.

Every owner uses the same transaction ID across both events. A matching
recovery can cancel provisional state before publication. Ordinary S1
backpressure cannot lose or retarget the D3-held reservation, and S1 never asks
for a resource that was not reserved by `d2D3ReserveFire`. No owner mutates on
D2 preview alone. A provisional ROB row is recovery-visible but not retirement-
visible until S1 publication completes.

## 12. RENU

### 12.1 P absolute-index rename

For each STID, P rename owns:

- 24-entry speculative SMAP;
- 24-entry committed CMAP;
- banked 256-entry MapQ;
- shared/global PTag free-list interface and banked staging FIFOs;
- checkpoint/recovery metadata;
- PTag alias/ownership state if move elimination is enabled later.

SMAP payload is:

```text
PMapPayload {
  valid
  ptag
  producerIqid
  producerStid
  ready
  valueSize
  producerEpoch
}
```

Source lookup reads the selected STID's SMAP. A regular oldest-to-youngest
inlining network then applies every older destination in the current D3 bundle:

- RAW: a younger source receives the youngest matching older destination's
  PTag/IQID/ready payload;
- WAW: the youngest destination becomes the final speculative mapping;
- multiple dependent children of one instruction follow uop ordinal;
- zero/no-register aliases are removed before the network.

Pending S1 SMAP writes participate in bypass, so physical S1 write timing does
not create a one-cycle dependency hole.

Each accepted destination appends `{RID group, member, atag, oldPtag,
newPtag, producerIqid}` to MapQ. Commit updates CMAP in program order and
releases superseded mappings only through the PTag return arbiter.

The logical MapQ remains one per-STID ring with one head, tail, count, and
exact `mapQIndex`. Its physical rows are arranged as
`[stid][subbank][row]`: the low `log2(pMapQSubbankCount)` index bits select the
subbank and the remaining high bits select the row. The default two subbanks
are the even/odd layout described by the implementation reference. All
publication, retained commit, killed-tail drain, and survivor-replay accesses
pass through the same logical-index decoder. Banking never changes program
order, commit/recovery exclusivity, PTag return order, or exact member identity.
O8.3b establishes this storage boundary. O8.3c limits each commit read slice to
one row per configured low-index subbank, retains the complete slice before
PTag return, and mutates CMAP/head/count only after that registered return
fires. Killed-tail recovery likewise retains one selected row before return and
tail update. Thus entry read and pointer mutation no longer form a one-cycle
loop; post-elaboration synthesis timing remains a separate closure gate.

Move elimination is not required for the first functional milestone. If later
enabled, it aliases the source PTag and must add explicit reference/lock state;
the destination cannot be freed merely because one alias commits or flushes.

### 12.2 T/U relative-index rename

T and U are separate owners. They do not use the P SMAP, CMAP, MapQ, free list,
or PTag staging FIFO.

Each STID has independent T and U state:

- relative architectural index/base;
- next sequential `ttag` or `utag`;
- block-local allocation sequence and wrap generation;
- local commit map/queue appropriate to relative lifetime;
- BID-qualified checkpoint and recovery state.

The renamed uop preserves `atag`/relative index and adds `ttag` or `utag`.
Same-cycle source relations use oldest-to-youngest sequential bypass. Block
completion/recovery, not P CMAP commit, releases T/U lifetime.

Architectural ROB commit is nevertheless the ordered trigger for local
retirement. `OooTURetire` retains one exact sidecar for every
published logical uop, including rows with no local destination. It matches a
retained grouped-ROB commit by STID, RID generation, native BID plus BROB
generation, resident generation, transaction, logical-uop mask, and member
range. It then serializes the model order:

1. drain prior T relations, then prior U relations, on group/block transition
   or block-last;
2. mark each exact T/U MapQ destination retired;
3. pressure-release the oldest relation only after the new mark;
4. remove relations for the exact block;
5. issue the post-clean local block commit to release the retired MapQ head
   prefix.

An implicit BROB close is represented as a `closeBefore` event on the first
logical uop of the close-owner group. It therefore drains and commits the old
block before processing that uop's real block. P and T/U commit owners perform
side-effect-free exact probes and start atomically; ROB/BROB/PC deallocation is
not visible until both owners report ready on the same retained batch.

### 12.3 No FP/CC/Tile rename

OOO contains no FP SMAP, CC map, predicate map, or their PTag banks. Opcodes
that superficially resemble ARM FP are classified according to Linx semantics.
Tile operands are carried as architectural tile descriptors through OOO and
renamed/allocated only by the post-IEX Tile subsystem.

## 13. PC compression

The PC buffer contains 64 base entries. A uop carries `pcBaseIndex` plus a
byte-granular offset; full PC is reconstructed as:

```text
pc = pcBuffer[STID, pcBaseIndex].base + zeroExtend(pcOffsetBytes)
```

Byte offsets are required because Linx instructions are 2, 4, 6, or 8 bytes.
The default seven-bit offset covers 128 bytes. A new base is allocated when:

- the next instruction would overflow the offset range;
- a predicted-taken/direct control discontinuity changes the sequential base;
- a template/fusion recovery needs an independently restorable base;
- a group rule requires a unique exception/trace base.

The current owner uses four fixed 16-entry per-STID partitions. Inside each
partition, O8.3g maps the low local-index bits to one of four default banks and
the high bits to one of four rows. Three-write allocation and four-group
retirement prefixes therefore cannot collide in a bank. O8.3j keeps canonical
allocation/commit/recovery metadata in that owner while broadcasting the
minimal `{valid, STID, index, allocationEpoch, base}` consumer payload into
three explicit replicas. Compile-time mapping gives ports 0/3, 1/4, and 2/5 to
replicas 0, 1, and 2, so every replica has exactly two readyless reads and no
runtime arbitration. Allocation, ordered free, and recovery free update all
replicas on the same owner fire. A later configuration may lend unused entries
while preserving per-STID head/tail and recovery. Port pressure participates
in D2 maximum-prefix admission.

A ROB group records only the ordered PC-base release obligation
(`releasePcBase` or a one-entry release count); it does not store the PC base or
base index as general ROB payload. `pcBaseIndex + pcOffsetBytes` travels with
the uop/PC packet to consumers that need PC. A precise exception consumer must
retain its reconstructed exception PC before the execution member is released.
Commit advances the selected STID's PC-buffer release pointer from the ROB
release indication. Debug builds may retain a checked full-PC shadow that must
equal reconstructed PC and is forbidden from becoming an execution owner.
O8.3h separately bounds recovery compare/read depth with a retained scan and
retained apply masks. O8.3j fixes the six logical reads onto three explicit
replicas. The remaining PC physical tasks are multirow metadata/read-payload
write fanout, optional foundry-macro wrapping and its I1/I2 latency contract,
and synthesis timing evidence.

## 14. Dispatch and IEX S1/S2/S3 boundary

### 14.1 Classification and steering

Dispatch classifies uops into generated logical classes such as:

```text
ALU, MUL, DIV, BRU, LDA, STA, STD, AMO, CMD, SYS, TILE_DESC
```

The exact set is generated from opcode metadata. Tile execution remains
outside OOO even when a descriptor uses an OOO dispatch class.

For each class, D3 receives from IEX:

```text
IqCredit {
  STID
  iqClass
  bank
  freeEntries
  freeSpecSlots
  freeWritePorts
  creditEpoch
}
```

Steering considers destination PTag bank, compatible execution pipes, current
occupancy, write-port availability, and per-STID quota. Round-robin/LFSR may
break equal-cost ties, but older uops always win resource conflicts.

O5.1 implements the exact functional reservation lifecycle and common
publication boundary. O8.2 retains the complete free bitmap as state
observability but removes it from any bank-wide first-free encoder: a
parameterized two-level selector chooses the lowest nonempty leaf and then the
lowest entry inside that leaf. The default 32-entry bank uses eight 4-entry
leaves. This closes the unbounded slot-index chain without changing ownership
or conservation. Occupancy plus retained-in-flight cost, explicit bank budgets,
one-cycle-ahead arbitration, configurable safe-mode thresholds, and
PTag-bank-aware steering remain O8 inputs; transaction/uop rotation is still
only a deterministic tie breaker.

The selected `{class, bank, port, iqid, reservationEpoch}` is retained in the
renamed uop. It cannot be changed while S1 is stalled.

### 14.2 Prefix and split rules

- If an older uop stalls, every younger uop in the selected STID bundle stalls.
- Uops targeting independent classes may publish together only within the
  accepted older prefix.
- All children of a late split reserve and publish atomically.
- A class cannot overbook either physical entries or write ports.
- Credit includes D3 and retained S1 consumption, not only currently valid IQ
  entries.

### 14.3 S1, S2, and S3

- OOO S1 publishes the retained payload into an IEX speculative issue slot.
- IEX S2 consumes the earlier reservation, binds a physical IQ entry, writes
  uncritical payload fields, and acknowledges the `iqid` mapping.
- IEX S3 marks the entry valid for ready/age-matrix pick.

O5.2 implements this residency contract in `OooIexIssue`. One
retained S1 row per STID is selected by a shared fair S2 writer. A pending-S1
claim excludes duplicate cross-STID targets before physical write. S2 installs
all split children atomically and holds `BoundS2` for one full cycle; only the
registered transition to `ResidentS3` enables a readiness query. Wakeups write
registered generation-qualified P/T/U source state, enforcing wakeup N to
pick-enable N+1.

Generation-qualified P and per-STID T/U ready scoreboards retain completed
producer state for consumers that reach S2 after the wakeup pulse. Installing
a new physical destination invalidates the matching scoreboard entry, so tag
reuse cannot inherit readiness from an older generation or local sequence.

The installed row is physically split. `OooIexScheduleRow` retains only the
state scanned by wakeup, pick, release, and recovery: exact member/reservation
identity, lifecycle identity, and physical source/destination readiness.
`OooIexPayloadSidecar` stores opcode/recipe, primary prediction, PC tokens, and
control/template/trap fields in a stable-slot memory. `OooIexIssueRow` remains
the joined query/execution view, so no downstream semantic contract changes.
The real `OooO3RenameCoordinator` transfers this payload over Decoupled and
shares that fire with ROB/BROB/PC/RENU/dispatch publication. Exact terminal
release removes the scheduling row and returns the dispatch slot on one fire;
stale sidecar bits are unreachable while the slot is free.

Recovery capture validates retained S1, then O8.1b scans one configurable
entry slice from every physical class/bank per cycle. The request, exact
row-kill mask, S3 lane mask, killed-state counts, and P/T/U ready-scoreboard
kill masks are retained. Global P-ready identity
`{valid,generation,stid,epoch}` is snapshotted at capture and must still match
at apply, so a peer may recycle and reuse the same numerical PTag without a
stale target mask clearing its ready state. A complete scan takes
`iexRecoveryScanCycles = iqEntriesPerBank /
iexRecoveryScanEntriesPerBankPerCycle`; prepared-ready appears after one
capture cycle plus those scan cycles. Plan drift, malformed live-row identity,
or incomplete S3 membership rejects without mutation. Prepare deassertion
aborts only private scan metadata. The target STID remains fenced throughout,
peers continue, and only the global common apply consumes the stored masks.

The S1 reservation guarantees eventual S2 capacity unless matching recovery
cancels it. Therefore rename may safely publish `producerIqid` at D3/S1 without
depending on an unreserved future allocation.

Physical IQ valid/readiness and speculative in-flight state now belong to IEX.
I0.2 selects exact oldest-ready members and retains only a query token; the
canonical scheduling row owns `inFlight`, retry, recovery, and terminal-release
qualification. I0.3 joins that token to the canonical sidecar and connects one
picker/bridge/lane retry loop. I0.4 vectorizes those ports around the same IQ
owner and adds one private bridge/lane per disjoint domain. The default
class/bank map, RF arbitration, and execution remain later IEX work. I0.1 implements one reusable P1/I1/I2
transaction lane without copying IQ residency into it. OOO consumes
reservations and acknowledgments but never mirrors IQ residency.

### 14.4 Oldest-ready pick and in-flight

I0.2 defines one reusable issue-domain picker. An instance covers one uop class
and a mask of physical banks. It filters only canonical `ResidentS3`,
registered-ready, non-in-flight rows. For each STID it selects the oldest
member by modular `{ridGeneration,ridSlot,memberIndex}` order, then performs
work-conserving round-robin selection across STIDs. This remains exact across
RID wrap because the maximum live population is constrained below half of the
age namespace.

The selected token is retained under downstream backpressure. On fire, the IQ
row becomes canonically `inFlight`; the picker owns no issued bitmap. Exact
read denial or rejection clears that row for repick. Recovery prepare drops an
unclaimed target token and blocks new target selection while peers continue;
common apply cancels any remaining killed token. Terminal release requires the
exact row still be in flight.

This differs from the ARM reference's queue-specific ALU next-retire and
AGU/STD age-matrix rules. Linx uses one member-age baseline; latency,
memory-order, nonspeculative, load-generation, and safe-mode rules will become
class-specific eligibility filters. The number of disjoint picker domains and
their bank-to-pipe mapping remains an explicit topology decision.

### 14.4.1 Exact selected-payload join and PC policy

I0.3 makes PC use an opcode-catalog contract instead of a top-level guess.
Every catalog record has `pc_read_parent = NONE|PRIMARY` and a
`pc_read_class`; the generated recipe exports `pcReadRequired` and
`pcReadClass`. The current explicit `PRIMARY` set contains 28
forms: conditional branches and jumps, `addtpc`, and scalar/HL `*_pcr`
loads/stores. Ordinary compares, ALU operations, and base-register memory
operations do not consume a PC read port.

PC use is child-specific after late split: branch/`addtpc` rows use BRU,
scalar PCR loads and the address child of scalar PCR stores use AGU, and HL
PCR single-child forms use ALU. The STD data child of a PCR store does not read
PC.

At S2 bind the IQ matches the canonical `primaryParent` key against the
architectural-parent array once and stores `{pcParentIndexValid,
pcParentIndex}` beside the PC tokens. `OooIexPickP1Bridge` addresses the joined
row with the retained pick token and proves resident state, full member and
reservation identity, valid dispatch recipe, opcode agreement, and required
PC-token shape. It forwards the full row plus generated PC controls to P1 but
retains no scheduling row, payload row, age state, or PC value.

`OooIexIssueP1Lane` composes the canonical IQ, join, and P1/I1/I2 lane. A
read denial, incomplete readyless return, or P1 rejection returns the exact
member/reservation to the IQ. Denial cannot accept a replacement P1 on the
same edge, and malformed joins wait for lane capacity, so the one-lane retry
port never needs to return two different members in one cycle. Recovery apply
is forwarded from the IQ's retained exact plan to both I1 and I2.

For a malformed join or P1 shape, claim and exact retry may occur on the same
edge. The IQ recognizes only the identical current pick token as an effective
in-flight claim; the retry write has final priority and leaves the row
resident/not-in-flight. A different same-edge retry remains rejected.

### 14.4.2 Parameterized multi-domain fabric

I0.4 adds `iexIssueDomainCount` without multiplying IQ ownership.
`OooIexIssue` exposes N picker/query/retry ports over the same scheduling rows,
ready scoreboards, and memory-backed sidecars. `OooIexIssueP1Fabric` attaches
one bridge and one P1/I1/I2 lane to each port. The original one-lane
composition remains a domain-zero compatibility wrapper and explicitly
requires a one-domain parameter.

Each domain is configured by one uop class and one bank mask. Same-bank domains
are legal when their classes differ, and same-class domains are legal when
their bank masks do not overlap. Overlap in both dimensions is a hardware
assertion failure. Retry is also domain-qualified, so a stale or misrouted
response cannot clear another domain's canonical in-flight row. The two-domain
IT demonstrates concurrent ALU/BRU claims and an isolated BRU deny/repick while
ALU advances to I2.

Fabric `empty` includes private lanes, retained S1 rows, BoundS2/ResidentS3 IQ
rows, and retained recovery scan state. It does not confuse lane-empty with
backend quiescence. The default physical ALU/BRU/AGU/STD/FSU/SYS/CMD mapping is
not frozen by this packet; that decision must be made together with RF read
ports, writeback ports, execution pipe multiplicity, and class-specific
admission blockers.

### 14.5 P1, I1, and I2

I0.1 establishes the first canonical post-IQ stage contract:

- P1 accepts one exact selected `OooIexIssueRow`. It validates member, BID,
  reservation, primary-parent, ready-source, and optional PC-token shape. A
  malformed producer is consumed as a typed reject rather than wedging the
  interface.
- I1 retains that row and presents one atomic read attempt covering every
  valid P/T/U source plus the optional parent PC token. One explicit arbiter
  decision grants the entire attempt or denies it for exact repick. A grant
  with any missing readyless response is rejected; partial operand state never
  reaches I2.
- I2 retains the full row, source values, and reconstructed PC under
  backpressure. Recovery applies exact grouped-ROB membership independently to
  I1 and I2. The lane never releases the physical IQ row; only a later
  non-cancellable execution handoff may issue the exact release.

The current PC buffer returns `base + byteOffset` at its readyless I1 read
port, and I2 registers that full PC. The ARM reference notes instead place the
base read in I1 and the add in I2. Both preserve a data-bearing I2 boundary;
the Linx implementation keeps reconstruction with the canonical PC owner.
Replacing the readyless arrays with synchronous macros requires an explicit
registered response phase and must not turn the request path into hidden
state.

### 14.5.1 Atomic multi-domain read allocation

I0.5 implements `OooIexAtomicReadArbiter` between the domain-local I1 attempts
and physical data owners. P, T, U, and PC capacity are independent resources;
the default formal geometry is 6P/4T/4U plus the existing six PC ports. A
selected subset is legal only when every source from every selected uop fits.
No denied group emits even one physical request.

The bounded domain count is at most eight, allowing the arbiter to enumerate
all subsets and compare only feasible complete groups. Priority is
lexicographic: modular RID-generation/slot/member age within the same STID,
then an advancing STID round-robin base across STIDs. This preserves the oldest
request while packing lower-priority groups whose complete demands still fit;
it does not use fixed domain priority.

Port requests carry domain/source coordinates, STID/epoch, and the complete
generation-qualified P or local-sequence source token. The readyless response
crossbar returns data to the originating source position. A missing response
does not retroactively alter port allocation: I1 sees `grant=1` with an
incomplete data-valid mask and uses the existing exact reject/repick path.
Malformed source masks, operand classes, identities, tags, reservations, or PC
tokens are decided as whole-group denial with no physical request.

This packet does not create another RF state owner. I0.6 must connect these
requests to the canonical P data/ready owner, new exact T/U local data owners,
and `OooPcBuffer`, then drive the multi-domain lane decisions directly.

## 15. Fast-resolve plan

Fast resolve is a typed terminal path, not a blanket "no operands" shortcut.

| Class | Initial members | Required behavior |
|---|---|---|
| `BoundaryMetadata` | pure legal BSTART/BSTOP forms and fused start/stop bits | Publish ROB/BROB/checkpoint/PC sidecars; validate direct/call target at S1; no IQ entry |
| `ImmediateProducer` | `SETRET`, `C.SETRET`, `HL.SETRET` forms whose value is `PC + immediate` | Consume normal destination PTag, use retained dispatch-result RF/writeback+wakeup port, then complete |
| `ControlValueProducer` | architecturally fused `START_CALL_32/48`, and any later proven SETRET+BSTART.CALL fusion | Preserve start metadata, call transfer, RA destination/value, both source PCs/PC-base obligations, redirect validation, PRF writeback and wakeup before completion |
| `PreciseTrapRecord` | illegal/undefined and architecturally defined immediate trap forms such as EBREAK after exception classification | Record exception/PC in ROB; trap only at ordered head |
| `NoEffect` | only generated opcodes explicitly proven architecturally inert | Complete after ROB publication; no hidden BCTRL/CMD/system effect |

Boundary fusion normally removes the standalone marker member. Its start/stop
effect remains a ROB group sidecar and fires only from ordered commit/BROB
retirement. A non-fused marker becomes a standalone fast-resolved member.

The initial fast-resolve blacklist includes:

- SETC/CMP/conditional operations that need source values or BRU validation;
- loads, stores, atomics, address generation, and store STA/STD children;
- fences, cache/TLB maintenance, ACRC/ACRE, and system synchronization;
- block arguments, dimensions, descriptors, hints, and command-pipe updates;
- `FENTRY/FEXIT/FRET.*` parents and all CTU children;
- unresolved macro-template catalog forms `ERCOV`, `ESAVE`, `MCOPY`, and
  `MSET`; these fail closed until an expansion owner and recipe are specified;
- Tile/Vector engine commands;
- any instruction with an unresolved exception, memory, RF, wakeup, redirect,
  or external-service side effect.

Fast-resolve completion fires only when every required terminal sink accepts:
ROB member completion, optional PRF result, ready-table/wakeup, boundary
validation/recovery request, and trace sidecar. Backpressure retains the entire
transaction.

Non-scalar BSTART forms that launch a block engine may complete their boundary
member only after the engine-command handoff is independently and atomically
retained. They are not pure no-effect markers.

The opcode catalog must generate the whitelist. Boundary recognition uses
`blockKind` plus generated boundary metadata rather than major category: the
current catalog does not classify all 16/32/48/64-bit BSTOP/BSTART forms in the
same major category. Operand-less is also not a fast-resolve predicate; ACRC is
an explicit counterexample because it reads implicit ABI state.

Unit tests must prove every
catalog opcode is classified as fast-resolve, dispatched, CTU-owned, or illegal;
an unclassified opcode is an elaboration/test failure.

### 15.1 Initial opcode decision matrix

| Current catalog family | Initial canonical decision |
|---|---|
| scalar/compressed/HL BSTART with only boundary semantics | fuse to `start`; otherwise `BoundaryMetadata` fast resolve |
| `BSTOP` and `C.BSTOP` | fuse to `stop`; otherwise `BoundaryMetadata` fast resolve |
| TMA/CUBE/VPAR/VSEQ/engine BSTART | boundary part may fast resolve only after the engine-command sink retains its independent obligation |
| `START_CALL_32/48` | `ControlValueProducer`; never pure-boundary fast resolve |
| `SETRET/C.SETRET/HL.SETRET` | `ImmediateProducer` after exact opcode metadata and destination semantics are fixed |
| SETC/CMP/B.Z/B.NZ/SETC_TGT | BRU, not fast resolve |
| block argument/dimension/descriptor/hint | CMD/BCTRL, not fast resolve even without explicit registers |
| ACRC/ACRE, fence, cache/TLB/system | SYS/service path; implicit state and late traps prohibit operand-count inference |
| illegal/undefined/immediate architectural trap | `PreciseTrapRecord` |
| CTU and unresolved macro templates | CTU or fail closed |

The O0 audit must explicitly repair the current catalog gaps for 32-bit BSTOP,
HL BSTART categories, implicit ACRC sources, and `START_CALL_32/48` boundary/RA
metadata before this matrix drives RTL.

## 16. Physical ROB, commit, and deallocation

### 16.1 Banked storage

Each STID owns an ordered ROB partition with independent allocate, commit,
non-flush, and recovery pointers. Physical storage is banked. The first target
uses eight banks, with optional even/odd subbanks to break the allocation-to-
retirement pointer timing loop.

The group lifecycle is:

```text
Free
 -> ProvisionalD3
 -> PublishedS1
 -> PartiallyResolved
 -> Completed
 -> Retired
 -> Released
 -> Free
```

`ProvisionalD3` rows are visible to recovery but not commit. S1 publishes
`groupComplete` only after all members, MapQ rows, boundary/PC sidecars, and
dispatch/fast-resolve ownership are installed.

### 16.2 Commit

A group can retire only when:

- every expected member resolution is complete;
- no member has a pending execution, LSU, CTU, or external-service obligation;
- the group is the selected STID's ROB head;
- exception and interrupt priority permits retirement;
- MapQ/CMAP, BROB, PC buffer, LSU, trace, and other release sinks can retain the
  commit transaction.

Commit chooses the largest completed contiguous group prefix up to
`retireGroupWidth`, then applies per-sink port limits. Only one STID commits in
one cycle in the baseline physical implementation; ready STIDs arbitrate fairly.

Commit and deallocation are separate retained walks. Commit makes architectural
effects ordered. Deallocation waits until every release obligation, including
serialized marker/BROB and MapQ work, has acknowledged.

### 16.3 Completion counters

Typed member counters separate ALU, BRU, LSU address, LSU data, CTU child,
command/system, and fast-resolve obligations where useful for timing. Counter
underflow, duplicate completion, stale generation, wrong STID/BID, and member
out-of-range all produce zero mutation plus diagnostics.

## 17. Non-flush frontier

Each STID ROB owns a `nonFlushHead` and a contiguous `nonFlushPrefixCount`.
This frontier may advance ahead of commit when every older group is proven not
to be removed by branch correction, synchronous exception, pending interrupt,
or self-flushing system behavior.

The ROB/commit-control side is the only non-flush owner. IEX and LSU provide
typed evidence and may consume a granted window, but they cannot advance the
frontier or treat it as a second completion/writeback path.

A group's non-flush-ready condition is initialized by class and updated by
typed evidence:

- ordinary non-trapping ALU may be ready at publication;
- branch/boundary waits for required direction/target validation;
- load/store waits for the defined address/permission exception point;
- system/command waits for its class-specific non-flush resolve;
- a group containing an unresolved precise exception never advances the
  frontier past itself.

Consumers receive an exact per-STID window, not a numeric RID/BID threshold:

```text
NonFlushWindow { STID, headRobGroupKey, prefixCount, epoch }
```

Allowed uses include waking instructions that are architecturally
nonspeculative, releasing selected prediction/branch resources, and removing
unnecessary serialization. It does not:

- update CMAP or free speculative PTags;
- emit architectural commit trace;
- retire ROB/BROB state;
- authorize a committed store to SCB without the existing BROB-qualified
  strong non-flush proof and full store identity.

Pending interrupt freezes the affected STID's frontier at the required boundary.
Recovery recomputes it from surviving ROB groups.

## 18. Recovery

Recovery is one exact, retained five-stage transaction:

```text
R0 Capture
R1 Resolve selected STID/BID/RID/member and kill set
R2 Prepare every affected owner and freeze affected rename/dispatch
R3 Commit pointer/map/queue/PC/BROB changes atomically
R4 Acknowledge, rebuild completion, and frontend restart
```

The affected STID's rename remains blocked from R2 until SMAP rebuild is done.
Other STIDs continue when shared recovery ports and quotas permit.

P recovery performs:

1. select CMAP or a validated checkpoint as the baseline;
2. copy the baseline into SMAP;
3. replay surviving MapQ rows in program order;
4. return killed PTags through the single PTag return arbiter;
5. rebuild IQID/ready payload or invalidate it when the surviving producer is
   already complete.

T/U recovery uses their separate sequential/checkpoint owners and never reuses
P MapQ rules.

The same accepted transaction prunes or restores:

- D1 fusion history and complex-break state;
- D2/D3/S1 per-STID staging;
- provisional and published ROB groups;
- BROB tail and native BID reservations;
- PC-buffer write/base pointers;
- P MapQ/SMAP, T/U state, and PTag reservations;
- IEX speculative-slot reservations and S2 binds;
- pending completion, fast-resolve, and non-flush state;
- CTU queued/active expansion by exact parent/template identity.

Missing, stale, cross-STID, ambiguous, or generation-mismatched authority
causes zero mutation.

## 19. External CTU integration

CTU remains outside IFU and OOO. The canonical OOO-side
`OooCtuIngressBridge`, composed by `OooIfuD1Ingress`, retains decoded D1
packets per STID, claims diverted template parents, and arbitrates ordinary
prefixes with CTU-produced canonical children before D2. D1 performs only the
decode needed to identify a template parent; the external CTU still owns the
recipe algorithm and expansion sequencing.

When a template parent is claimed:

1. the raw parent is consumed exactly once;
2. CTU computes its exact ordered child recipe and resource envelope;
3. the bridge assigns a retained
   `{PE, STID, parent, templateGroupId, generation}` ingress lease;
4. CTU emits canonical children carrying parent/template/ordinal identity;
5. children pass exact bridge validation, then enter normal D2 grouping, D3
   rename/ROB, S1 dispatch, execute, precise trap, and commit paths;
6. nonfinal children carry zero trace-parent demand and the final child gates
   the one architectural parent commit record.

The exact plan fixes child count before emission. Stale leases, wrong ordinals,
count drift, illegal/recursive children, and generation mismatches are consumed
as typed rejects without advancing the lease. Each accepted child is one
canonical D1 packet, so a long expansion uses the ordinary D2 grouping rules
and may span several RIDs. Parentless ROB rows are legal only for exact
nonfinal template continuations; the final child must own the parent.

CTU never allocates RID/BID/PTag/IQ state, writes PRF or memory directly,
completes ROB state directly, or globally blocks unrelated STIDs. Recovery
prepares CTU before O3 admission, fences without mutation, and cancels retained
D1/lease state on the same common apply as ROB/D3/BROB/PC/rename/dispatch/IEX.
Abort only releases the fence. Because same-STID D1 and active expansion are
in order behind the ROB trigger, the apply discards the complete retained
target-STID ingress/lease continuation; unrelated STIDs remain live.

The current Template D3 reservation/fill machinery is a migration oracle for
row recipes and cancellation tests only. It cannot coexist as a canonical
allocator after the bridge is enabled.

## 20. Stall and observability model

Every stopped prefix records its oldest blocking lane and typed reason:

```text
D1_STATIC_UOPS
D1_FUSION_HISTORY
D1_EXPANSION_LEASE
D2_ROB_GROUPS
D2_BROB_SLOTS
D2_PC_BUFFER
D2_PTAG_FIFO_BANK
D2_MAPQ
D2_T_RENAME / D2_U_RENAME
D2_IQ_ENTRY / D2_IQ_WRITE_PORT
D3_STALE_PLAN
D3_PHYSICAL_BANK_CONFLICT
D3_RECOVERY
S1_SPEC_SLOT
S1_FAST_RESULT_PORT
STID_QUOTA
STID_STARVATION_OVERRIDE
```

Counters are per STID, stage, class, IQ bank, and lane. They distinguish hard
capacity, soft quota, physical port conflict, recovery, and retained-output
stall. Performance review must be able to explain lost decode/dispatch cycles
without inspecting waveforms.

## 21. Unit-test plan

| Unit | Required proof |
|---|---|
| `D1Decode` | all 16/32/48/64 rules, illegal-before-break, prediction/PC carry, 2/4/6 instruction widths |
| `InstructionExpansionPlan` | one-to-five recipes, ordinary complex recipe, exact child order/count/demand, no partial publication |
| `BoundaryFusion` | forward BSTART, backward BSTOP, `start+stop`, 2/3-parent trace preservation, cross-cycle/per-STID history, in-body reentry, recovery cancellation, standalone fallback |
| `D2RobGroupPlanner` | max four instructions/group, max member cap, group-force rules, virtual RID wrap, stale tail epoch, no cross-BID group |
| `RobPhysicalPublisher` | D3 provisional/S1 publish, bank conflicts, prefix publication, recovery between D3 and S1 |
| `ROBPartition` | member resolve counts, grouped commit, exact stale rejection, non-flush, commit/deallocation separation, even/odd bank timing state |
| `BROB` | 256-entry rollover for all four STIDs, fused/standalone starts, in-body reentry reuse, no unsigned BID age |
| `PTagStagingFifo` | refill/consume/hold, preferred-bank exhaustion with availability fallback, skewed identity returns, simultaneous return/refill, no D3 direct free-list select |
| `PRename` | 24-to-128 mapping, RAW/WAW inlining, pending-S1 bypass, IQID/ready payload, banked MapQ, commit and recovery |
| `TuRename` | independent T/U sequential allocation, relative lookup, wrap-qualified exact mark/deallocation, post-clean block release, four-STID isolation |
| `TuRetire` | every-uop source retention, exact grouped-commit match, T-before-U pre-release, pressure release, no-destination block-last, implicit close, relation cleanup |
| `PcBuffer` | variable 2/4/6/8-byte offsets, overflow base allocation, 3W/6R conflicts, four partitions, commit/recovery release |
| `DispatchSteering` | bank/port/entry credits, oldest prefix, split atomicity, stable target, LFSR/RR tie break, quota |
| `IexSpecSlotBoundary` | S1 retained slot, S2 reserved bind, S3 pick enable, recovery at every stage, IQID stability |
| `FastResolve` | complete whitelist/blacklist, `START_CALL` RA result, implicit-operand rejection, result-port backpressure, boundary validation, no early commit |
| `NonFlushFrontier` | per-class ready, holes stop prefix, interrupt freeze, recovery rebuild, no commit/PTag release side effect |
| `RecoveryCoordinator` | R0-R4 protocol, CMAP copy + MapQ survivor replay, all-owner ack, unrelated-STID progress |
| `CtuBridge` | exact claim, lease, multi-group template, stall, final parent trace, cancel/reuse, no direct state effects |
| `FourThreadArbiters` | independent stage selections, fairness, starvation bound, retained selection stability, WFI/inactive behavior |

Required conservation assertions include:

- physical ROB free + provisional + published + retired-not-released = depth;
- each RID group is owned by exactly one STID/BID/generation;
- expected member resolves = completed + outstanding for every live group;
- each PTag is in exactly one legal lifecycle location, with explicit alias
  references if move elimination is enabled;
- MapQ committed + live + killed/returning = accepted rename rows;
- PC-buffer free + live + releasing = 64;
- S1 reservations = speculative slots + S2-bound entries + canceled/returned;
- CTU parents = queued + expanding + children-live + completed + canceled;
- accepted architectural parents = committed + live + precisely killed.

## 22. Integration-test plan

`LinxCoreOooTestTop` contains real D1/D2/D3/S1, ROB/BROB, PC buffer, RENU,
recovery, non-flush, and CTU bridge, plus abstract IEX S1/S2/S3 sinks and exact
completion producers.

Directed scenarios include:

1. Mixed 16/32/48/64-bit instructions expand to ordered uops at widths 2/4/6.
2. A single instruction expands to several uops and shares one parent trace.
3. A template spans several RID groups but retires one architectural parent.
4. BSTART fuses forward, BSTOP backward, and one instruction carries both bits.
   The fused member still retires the original 2/3 architectural parent rows.
5. In-body BSTART reentry reuses BID and creates no duplicate marker/trace.
6. Four STIDs carry equal RID/BID values without alias and occupy different
   pipeline stages concurrently.
7. A lane-3 resource failure accepts only the older complete prefix; a split
   store never publishes one child alone.
8. D2 virtual plan becomes stale before D3 and is recomputed without a ROB hole.
9. Skewed PTag identity returns exhaust the rotating preferred bank while
   another bank remains live; D3 falls forward without direct free-list select.
   True all-bank exhaustion still stalls atomically until a commit return.
10. Same-cycle P RAW/WAW plus pending-S1 bypass produces exact tags/IQIDs.
11. PC offsets cross 2/4/6/8-byte instructions and allocate a new base exactly
    at overflow.
12. S1 stalls 100 cycles, then S2/S3 bind/pick without payload or target change.
13. Fast BSTART/BSTOP and SETRET complete without IQ; SETRET waits for its PRF
    result/wakeup sink.
14. `START_CALL_32/48` cannot take the pure-boundary path and completes only
    after RA result, wakeup, call validation, and start metadata are retained.
15. A non-flush frontier passes non-trapping ALU, stops at unresolved load or
    branch, then advances without committing.
16. Recovery strikes D1 fusion history, D2 virtual plan, D3 provisional ROB,
    S1 speculative slot, S2 physical bind, CTU expansion, and MapQ rebuild.
17. Affected-STID rename stalls while the other three STIDs continue.
18. RID, BID, PTag, PC index, and IQ reservation generations wrap and reject
    stale responses.

Cross-stack proof includes generated opcode/recipe parity, randomized
decode/group/rename/ROB differential checking against LinxCoreModel, generated
RTL stage traces, and bounded QEMU/DUT architectural commit comparison after
real IEX terminal completion is connected.

## 23. Coverage plan

Functional coverage is the release authority.

| Domain | Required crosses |
|---|---|
| Width | instruction 2/4/6 x uop 1..max x accepted prefix |
| Thread | STID 0..3 x D1/D2/D3/S1 selection x stall/recovery |
| Expansion | recipe x child count x simple/complex/CTU x split/merge |
| Fusion | start/stop/dual/standalone x same/cross-cycle x STID x recovery |
| Group | 1..4 instructions x 1..max members x group-end reason x RID wrap |
| Identity | equal RID/BID across STIDs x generation reuse x stale terminal event |
| Rename | P/T/U combinations x RAW/WAW x PTag bank x FIFO/MapQ pressure |
| PC | instruction length x offset edge x base write/read-port pressure x STID partition |
| Dispatch | class x IQ bank x write-port/entry pressure x split x retained S1 |
| Fast resolve | every whitelist/blacklist class x result sink x exception/redirect |
| Non-flush | producer class x frontier hole x interrupt x recovery x STID |
| Recovery | cause x stage residency x owner prepare refusal x survivor replay |
| CTU | form x child count x multi-RID x stall/cancel/final-parent completion |

Release targets:

- 100% planned functional bins hit or reviewed/justified waiver;
- every opcode has decode, uop recipe, dispatch/fast/CTU owner, and test status;
- 100% safety assertions exercised with zero failures;
- at least 90% line and 85% branch coverage for new OOO modules after reviewed
  exclusions;
- randomized seeds and parameter values recorded with repository revisions;
- four-thread and wraparound configurations included, not inferred from
  single-thread tests.

## 24. Migration from the current Chisel tree

| Current component/assumption | Canonical action |
|---|---|
| `D1InstructionDecodeStage` | Generalize to multi-instruction/multi-uop D1 and generated recipe metadata |
| `D1DecodedLaneQueue` | Replace one-row drain with per-STID dense ingress and expansion buffering |
| `D1DecodeRenameROBIngress` | Remove physical D1/D2 allocation; connect D1 plan to D2 virtual grouping |
| `DecodeRenameROBPath` | Decompose into D1, D2 planner, D3 publisher/RENU, and S1 retained output |
| `DispatchROBAllocator` | Replace cursor allocation with virtual-tail token plus D3/S1 banked publisher |
| `ROBEntryBank` | Promote to grouped/member-count ROB with provisional/published states and non-flush |
| `ReducedCommitROB` | Legacy oracle only, then remove from canonical elaboration |
| `GPRRenameCheckpoint` | Promote after staging FIFO, IQID/ready SMAP, banked MapQ, and four-STID API |
| `ScalarTURenameBridge` | Move state into independent per-STID T/U canonical owners |
| `StoreSplitPayload` | Retain pure transform; move atomic S1/S2 reservations to dispatch/IEX owners |
| `BlockMarker*` standalone row path | Replace normal case with boundary-bit fusion; retain fallback and differential tests |
| `ScalarIssueFabric` / reduced IQs | Replace OOO boundary with IEX S1 speculative-slot credits and S2/S3 protocol |
| Template D3/shadow modules | Keep row-plan oracle temporarily; canonical templates use external CTU bridge |
| full-PC uop payload | Replace with checked PC base index + byte offset after PC-buffer parity closes |
| two-STID assumptions | Generalize public parameters and tests to four STIDs |

The current architecture contract still describes D3 atomic one-row allocation
and ROB-visible standalone BSTART/BSTOP. Phase O0 must update those normative
sections before implementation relies on this plan.

## 25. Delivery phases

### O0: specification freeze

Deliver:

- update normative microarchitecture, block-control, CTU, pipeline-stage, and
  common-bundle documents for grouped RID, fused boundaries, D2/D3/S1 timing,
  four threads, PC buffer, and non-flush;
- freeze GID migration/alias policy;
- generate the opcode uop-recipe/fusion/fast-resolve audit table;
- correct missing/inconsistent boundary and implicit-operand metadata,
  including `BSTOP`, `HL.BSTART*`, and `START_CALL_32/48`;
- define exact S1/S2/S3 credit/reservation bundles.

Exit: no normative document still requires physical D1/D2 ROB allocation or
unconditional standalone marker rows; every public field has one owner.

### O1: four-thread shell and verification skeleton

Deliver per-STID D2/D3/S1 staging, shared combinational stage arbiters,
`LinxCoreOooTestTop`, conservation monitors, and the parameter matrix.

Exit: 1/2/4-STID and instruction-width 2/4/6 elaboration passes; arbitration
fairness and retained-output tests pass.

### O2: canonical decode, expansion, and fusion

Deliver generated recipe metadata, ordinary one-to-five-uop break, retained
complex-break engine, boundary fusion, standalone fallback, and dense D2 input.

Exit: opcode audit is complete; expansion/fusion differential tests and all
four-thread recovery-history tests pass.

### O3: grouped RID ROB/BROB and PC buffer

Deliver D2 virtual group planner, D3 provisional/S1 publisher, member resolve
tracking, native BID/BROB integration, 64-entry PC buffer, and grouped commit.

Implementation status: packet complete. The virtual planner, retained D2 row,
D3 provisional allocator, S1 atomic grouped-ROB publication, exact member
completion, retained grouped commit, canonical native BID/generation BROB,
canonical 64-entry PC-base owner, and terminal coordinator are implemented.
BROB publication and retirement share the grouped ROB terminal handshakes and
preserve an exact cross-BID close-owner for in-body BSTART. The S1 request also
makes PC bindings explicit. The PC owner provides fixed four-way STID
partitioning, byte offsets for 2/4/6/8-byte instructions, three-write admission,
six checked reads, allocation epochs, exact group cursors, and explicit/implicit
close ownership. `OooRobBrobPcCoordinator` binds resident generations and all
BROB/PC tokens into one immutable prepared view, then permits no mutation until
the common publication fire. At commit it exposes a retained ROB batch only
after D3, BROB, and PC validate that exact batch, and asserts every internal
valid only on the terminal external handshake. Same-STID ring updates are
serialized while different STIDs may publish and commit concurrently.

Exit: no ROB hole on stale plans; group/BID/PC wrap and exact-completion suites
pass; no group crosses a BID or PC-release boundary.

### O4: P/T/U canonical rename

Deliver banked PTag free list and staging FIFOs, SMAP IQID/ready payload,
RAW/WAW inlining, banked MapQ/CMAP recovery, and independent T/U owners.

Implementation status: O4.1 implements the shared banked PTag free list and
staging owner. D3 claims only staged tags into one exact provisional lease per
STID; publication, cancel, and return are generation-qualified terminal events.
The default namespace starts with 96 committed identity tags plus 32 free tags;
all 128 tags are checked cycle by cycle for exactly-one-location conservation,
and a replaced identity tag becomes normally allocatable. D3 rotates a preferred
bank by transaction ID, then falls forward to a bank with staged credit while
accounting older destinations first; adversarial skew therefore cannot strand
live tags in another bank. O4.2 implements the
per-STID 24-entry P SMAP, reset CMAP, ordered MapQ publication, exact ROB-member
keys, and oldest-to-youngest RAW/WAW forwarding across every expanded uop and
destination in the transaction. `OooO3RenameCoordinator` makes PTag claim
atomic with D3 reserve and joins PTag/SMAP/MapQ publication to the existing
ROB/BROB/PC terminal fire. O4.3 carries exact per-group MapQ-row obligations
through the ROB and locks one retained commit batch while walking the ordered
MapQ head at `pTagReturnWidth`. Each step advances CMAP and returns the row's
generation-qualified previous PTag, including reset identity tags after their
CMAP mappings are replaced. Only after the walk completes may the common
ROB/BROB/PC/D3 deallocation fire. A younger same-STID provisional row that has
not exposed a complete prepared valid never blocks the older walk; it is held
immutable until the walk finishes. An already exposed prepared valid must first
publish under the retained ready/valid contract. While active, D3 eligibility
prevents newly selecting that STID and can select another STID when no earlier
retained selection is already exposed. O5 must complete fully downstream-
readiness-aware per-STID arbitration.
IQ binding is explicitly invalid until O5. O4.4.1 adds independent T and U
sequential owners per STID. They preview every active local source and
destination oldest-to-youngest, resolve same-bundle relative dependencies,
capture pre-destination T/U sequences per uop, and retain one exact provisional
lease per STID. Publication uses a compact O3-derived sidecar containing only
transaction identity, local operand shapes, and exact `RobMemberKey` bindings.
The shared coordinator admits D3 only when PTag and T/U resources are both
available, and the S1 terminal fire publishes ROB/BROB/PC, P SMAP/MapQ, PTags,
and T/U MapQ rows together. No published T/U row is released by P commit.
Stale D2 plans bypass rename resource gating only to reach D3's zero-mutation
reject path. Same-cycle same-STID publish/reserve is supported by previewing the
outgoing local lease in capacity, sequence, physical-tag, and source lookup.
O4.4.2 adds an independent `OooTURetire` owner rather than mixing
retire-source and relation state into sequential allocation. Every logical uop
is published into a per-STID source ring, including no-destination block-last
rows. Retained ROB batches are matched exactly before ordered T pre-release, U
pre-release, destination mark, pressure release, exact-block relation cleanup,
and local block commit. `OooTURename` alone continues to own T/U
MapQ rows and physical tags; it accepts only exact wrap-qualified mark/dealloc
commands and releases only a retired native-BID/BROB-generation head prefix.
Explicit boundary-stop and implicit BROB close both drive the same exact block
protocol. P and T/U owners begin only after both side-effect-free probes pass,
and common ROB/BROB/PC deallocation waits for both walks. O4.4.3a adds exact
recovery-anchor validation over the per-STID logical-uop source ring. The owner
scans without mutation, requires one exact member/native-BID/BROB-generation/
resident-generation/transaction/epoch match, and then emits only the killed
suffix youngest-to-oldest through a retained Decoupled stream. `killTrigger`
distinguishes branch-style survivor recovery from exception/nuke-style trigger
removal. Same-STID publication and commit wait for the retained recovery while
other STIDs remain live; simultaneous commit has capture priority. Transaction
ID zero is legal, and stale or missing authority leaves the source ring
unchanged. A retained authorization handshake prevents P/T/U owners from
starting until that read-only scan proves a unique anchor, including the
zero-killed-source case. O4.4.3b makes P recovery consume the authorized stream:
each source proves its exact P MapQ tail-row count, returns killed `current`
PTags through the generation-qualified return path, copies CMAP to SMAP, and
replays the surviving MapQ head-to-tail. Same-STID P prepare/commit remains
blocked through the rebuild, while another STID can publish. UT also proves the
surviving prefix can subsequently commit. O4.4.3c makes the T/U owner consume
the same authorized source stream. Each logical source must match both
pre-uop sequence snapshots, exact wrap-qualified T/U MapQ tail rows, full
member identity, and circular physical-tag order before it atomically removes
its destinations and restores the sequence and next-physical cursors. A
zero-destination row still proves both tails, and retired rows cannot be
rolled back. Focused UT covers mixed T/U rollback, generation wrap, cursor
reuse, transaction zero, unrelated-STID progress, malformed authorization, and
retire priority. O4.4.3d opens the coordinator's rename-local recovery request
only after joining scanner, P, and T/U authorization and killed-source
handshakes atomically. Request capture fences D3 reserve/publication for the
selected STID from the beginning of the read-only scan, so an already-retained
target row must publish before capture and no new row can invalidate the
suffix before owner authorization. Unrelated STIDs remain eligible.

The scanner removes a source only when both MapQ owners accept the identical
youngest-to-oldest row. P returns killed current PTags and replays its survivor
prefix while T/U restores exact sequence and physical cursors; common finish
waits for all three retained completions. Four-STID integration testing covers
a transaction-zero survivor, a younger mixed P/T/U suffix, concurrent STID 2
publication, unchanged STID 0/3 state, and stale-key zero mutation. This closes
rename-local recovery integration, not O7 global ROB/BROB/PC/IQ cancellation.
O4.4.4 closes the sequential-reference obligation with deterministic seed
`0x4f344d`. One ChiselSim run interleaves 36 randomized publish/recovery
operations after seeding every namespace on all four STIDs. The independent
scoreboard recomputes each STID's P SMAP from identity CMAP plus the surviving
ordered P rows and compares all 24 architectural mappings after every event.
It also compares P/T/U MapQ counts, logical source-ring occupancy, unchanged
CMAP, ROB occupancy, relation emptiness, provisional ownership, and the global
published-PTag count. Shapes include P, T, U, P+T, P+U, T+U, and
zero-destination rows; exact anchors include zero-kill and trigger-kill cases.
Per-STID transaction numbering is deliberately local, and the regression
retains the D3 stale-plan contract rather than inventing globally unique IDs.

O4 is packet-complete. O5 owns IQ binding and dispatch readiness; O7 owns the
larger ROB/BROB/PC/IQ/global recovery transaction.

Exit: tag/map conservation and randomized sequential-reference comparisons pass
for four STIDs; D3 has no direct free-list priority selection.

### O5: dispatch and IEX S1/S2/S3

O5.1 delivers generated-class compaction, exact bank/port/entry reservation,
all-child atomicity, retained per-STID leases, P-map producer binding, and
common O3/O4 publication. O5.2 delivers the exact Decoupled IEX S1 sink,
per-STID retained rows and target claims, fair S2 physical bind, registered S3
pick enable, registered wakeup readiness, compact execution rows, and exact
dispatch-coupled release. It removes the coordinator's temporary external
Boolean permit.

Exit: focused class/bank/port, split, target-collision, retained-stage, wakeup,
release, and real O3-to-IEX integration crosses pass; no ready loop; target and
payload remain stable through arbitrary S1 backpressure. Multi-pick policy is
later IEX scope, not an O5.2 residency claim. I0.1 separately closes the
reusable one-lane P1/I1/I2 transaction, including read denial, partial-response
rejection, retained output, and recovery cancellation; multi-lane arbitration
remains open. I0.2 separately closes reusable oldest-ready selection,
canonical in-flight claim, exact retry-to-repick, and release qualification.
I0.3 closes the selected sidecar join, generated PC-read metadata, and one-lane
retry/repick composition. I0.4 closes the parameterized N-domain ownership
mechanism, overlap assertion, isolated retry, and aggregate quiescence; the
default class/bank map and shared RF/PC arbitration remain open.

### O6: fast resolve and non-flush

O6.1 delivers typed retained fast-resolve sinks, boundary/direct-call
validation, SETRET/START_CALL result paths, precise-trap completion, per-STID
fairness, and exact O3/grouped-ROB integration. Every fast member has already
allocated ROB/BROB/PC/rename state. It may omit IQ residency, but it cannot
retire, release rename state, or become non-flush merely because the result is
known.

O6.2 delivers the per-STID ROB-owned exact non-flush frontier and counters.
The grouped ROB derives class-specific proof obligations at publication,
accumulates only exact member-qualified evidence, and advances a retained
contiguous safe prefix. Ordinary non-trapping ALU groups require no later
proof; exception, memory, control, and serialization obligations are
independent bits. Precise-trap or malformed groups fail closed. Generic member
completion is deliberately not a safety proof.

The public authority is `{STID, head RobGroupKey, prefixCount, epoch}`; its
count width covers the full per-STID ROB partition. Consumers must prove
membership in that retained prefix rather than compare RID or BID numerically.
Pending interrupt freezes only expansion for the affected STID while exact
proof state is retained. Commit moves the window head and subtracts only the
retired prefix; non-flush itself never commits, deallocates, updates CMAP, or
releases rename state. O7 must recompute this state after its global kill-set
transaction and connect the permitted consumers.

Exit: full opcode whitelist/blacklist tests pass; non-flush never commits or
frees rename state; affected STID can advance independently.

### O7: recovery and external CTU

Deliver R0-R4 coordinator, CMAP-to-SMAP/replay, PC/ROB/BROB/IQ cancellation,
external CTU lease/children/final-parent protocol, and template multi-RID groups.

Implementation status: O7.1 completes the grouped-ROB owner slice. Every
physical row now retains publication epoch plus per-logical-uop member
base/count, P MapQ rows, architectural parents, boundary/PC/trap summaries, and
non-flush obligations. An exact request can therefore preserve or kill the
complete trigger logical uop even when several logical uops share one physical
RID group. The side-effect-free plan describes the partial pivot and complete
younger suffix across RID wrap; apply truncates only the selected STID and
restarts its non-flush proof window. Stale identity or malformed logical shape
is rejected without mutation.

The grouped-ROB owner-local fire is never exposed independently. O7.2a carries
the complete ordered physical
killed-group vector and exact BROB/PC allocation/implicit-close evidence in the
ROB plan, and `OooD3ReservationAllocator` independently prepares and applies
exact published/used/tail/provisional rollback. Its composed input stayed
closed until the lower physical owners could apply together. O7.2b1 adds BROB
prepare/apply: it releases only an exact
allocated tail-block suffix, decrements per-block live groups, restores current
from the surviving tail, and reopens an older block whose close owner was
killed. O7.2b2 now adds the analogous PC-base prepare/apply, including exact
partition token/allocation epoch, current base-value restoration, close-owner
undo, and immediate invalidation of freed reads. O7.2c projects the complete
ROB plan into one compact `OooResidencyRecoveryPlan`; this retains exact
wrapped-RID pivot/member authority without fanning the full killed-group and
BROB/PC repair vectors into every queue owner. Dispatch records exact ROB
membership at publication, cancels all target-STID provisional leases, and
removes only killed published rows. IEX prunes retained S1 and pending S3
masks, frees killed `BoundS2`/`ResidentS3` rows, and invalidates matching
generation-qualified P/T/U ready records. Fast resolve freezes only the target
STID and removes its exact killed pending entries while unrelated STIDs may
complete. Each direct owner independently prepares and rejects malformed or
out-of-window state without mutation.

O7.2d1 composes the lower physical subtransaction in
`OooRobBrobPcCoordinator`. It retains one exact request through
Idle/Preparing/Prepared, waits for any exposed same-STID D3 publication or
retained commit to drain, captures the ROB's sole exact plan only after D3,
BROB, and PC independently validate it, and drives all four owner fires plus
`recoveryApplied` on one externally authorized apply. The full plan remains
stable through arbitrary apply backpressure; malformed or stale owner state
rejects before mutation, and unrelated STIDs remain live. The enclosing
O7.2d2 lifts that lower subtransaction into a retained O3 core-physical
transaction. `CaptureOwners` independently handshakes the lower coordinator
and the exact T/U retire-source suffix scanner. `PrepareOwners` waits for the
ROB's retained plan, projects one `OooResidencyRecoveryPlan`, and prepares
dispatch, fast resolve, external IEX, and both rename state owners. One apply
cycle fires ROB/D3/BROB/PC, dispatch, fast, IEX, and P/T/U authorization.
The coordinator independently requires the scanner authorization to retain the
active STID plus valid group/native-BID identity before apply; P/T/U reject
after that prevalidated authorization is asserted unreachable.
`Rebuild` remains busy until the youngest-to-oldest source stream has removed
killed P/T/U rows, P SMAP has copied CMAP and replayed the survivor prefix, and
T/U cursors have been restored. A typed reject enters `AbortOwners`; abort is
held until both retained lower and scanner transactions are idle, closing the
late-accept/reject race without mutation.

Every PTag return now forks atomically to the freelist and external IEX ready
scoreboard. The exact `{ptag,generation}` ready record is invalidated before
the token can be recycled, including commit returns and killed fast producers
which have no resident IEX row. The obsolete public external PTag-return input
is removed.

O7.2e encloses the open O3 transaction with `OooFrontendRecoveryBridge`.
Capture immediately raises a non-mutating fence for the exact STID across IFU
raw ingress and D2/S1 retained staging. A reject therefore releases the fence
without clearing a row or sending a redirect. Only the typed exact
`recoveryApplied` event emits one `stageCancel` pulse and releases the retained
`IfuInnerFlush`. The bridge does not mistake redirect enqueue for frontend
completion: R4 waits for both the exact typed O3 rebuild completion and the
real `LinxCoreIfu.canonicalFlush` echo, including the IFU-allocated new epoch,
in either order. `OooO3RenameCoordinator` also publishes the exact aborted
request after every retained lower/scanner owner has drained. The canonical
IFU composition gives this applied-recovery redirect priority over the
compatibility BRU-feedback queue and consumes an identical duplicate once.

O7.3 is implemented by `OooCtuIngressBridge`. It preserves mixed
ordinary/template program order, emits the dense ordinary prefix before a
diverted parent, and retains one exact per-STID lease through claim, plan, and
ordered child emission. Canonical children re-enter before D2 one at a time,
so expansions larger than one RID group need no CTU-owned ROB capacity.
Template ordinal/count widths cover `maxRecipeUops`; only the final child owns
the architectural parent, while `OooS1GroupedRob` accepts zero-parent rows only
for exact nonfinal template continuations.

`OooFrontendRecoveryBridge` prepares CTU before offering the request to O3.
CTU snapshot/fence is side-effect free; exact O3 apply drives CTU cancellation
and frontend stage cancellation on the same cycle, and exact O3 abort releases
the CTU transaction without mutation. The bridge rejects stale plans,
out-of-order/count-drifting children, malformed children, and malformed
recovery requests without lease advance. Unresolved complex parents remain an
explicit fail-closed boundary, not a CTU bypass.

The OOO-side O7 contract is therefore closed. O9 must still instantiate the
external recipe producer, connect it to the claim/plan/child ports in the
canonical top, and retire the legacy direct-effect CTU path.

Exit: recovery at every stage and template phase closes with zero cross-STID
mutation; CTU has no direct RF/ROB/LSU architectural-effect port.

### O8: width and physical closure

Bring up instruction width 2, then product width 4, then scale width 6;
independently tune uop/rename/dispatch/retire widths, ROB banking, even/odd
subbanks, PTag FIFO depth, PC ports, and IQ steering.

O8.1 first splits the IEX row into `OooIexScheduleRow` and
`OooIexPayloadSidecar`. The schedule row is the only state scanned by wakeup,
release, and recovery; the wide payload is an inferred memory addressed by the
stable class/bank/slot reservation and is read only for the selected query.
The public `OooIexIssueRow` remains their joined view. On the same focused
2-bank x 4-entry test, the main generated module falls from 477,275 to 426,274
SystemVerilog lines, but the Verilator frontend remains long-running because
the one-cycle all-entry recovery CAM is still unrolled. This is a prerequisite,
not O8 exit.

O8.1b now retains the immutable recovery request and scans
`iexRecoveryScanEntriesPerBankPerCycle` rows from every class/bank per cycle.
It stores exact row/S3/P/T/U kill masks and killed-state counts, rejects drift
or malformed membership without mutation, supports read-only partial-scan
abort, identity-qualifies P-ready capture/apply across peer PTag reuse, and
consumes the completed masks only on common apply. On the same
2-bank x 4-entry stage test the main generated module is 173,709 lines, 63.6%
below the 477,275-line pre-O8.1 baseline and 59.2% below the 426,274-line O8.1
result. The directly comparable recovery scenario falls from about three
minutes to 47.046 seconds; the expanded three-test recovery suite is 232.166
seconds.

O8.2 replaces the bank-wide slot encoder with a reusable bounded hierarchical
selector. `iqFreeSelectLeafEntries` defaults to four; canonical's 32-entry
bank therefore selects across eight leaf-valid bits and then four local entry
bits. Exact first-free order, older-prefix atomicity, bank write-port budgets,
and the retained reservation identity remain unchanged. The 32-entry selector
emits 44 lines of SystemVerilog with no 32-entry priority chain; the focused
2-bank x 4-entry dispatch module changes from 30,753 to 30,859 lines (+0.34%)
because the reusable module boundary is explicit.

O8.3a closes the absolute recovery-tail/reference-model blocker exposed after
O8.2. D3 transaction identity, wrap-qualified head/tail, and tail epoch are
independent state domains: reserve advances transaction identity and tail;
release advances head; recovery restores only the exact tail, preserves the
next transaction ID, and advances the epoch to stale pre-recovery D2 plans.
A directed nonzero-head wrap test restores `{slot=0,generation=1}` and reuses
it with the unchanged next transaction ID. The four-STID randomized reference
now compares live ROB/D3 occupancy and every tail/epoch/transaction domain
after each publication or recovery instead of deriving all of them from a
cumulative issued counter.

O8.3b and O8.3c close the P MapQ storage and retained read-to-pointer pipeline.
O8.3d maps grouped ROB storage as `[stid][bank][subbank][row]`: low RID-slot
bits select one of eight default banks, the following bit selects the default
even/odd subbank, and the high bits select a row. Publication and retirement
widths must fit within the effective bank count, so one ordered prefix cannot
hit a bank twice. One decoder serves publication, completion, evidence,
commit, and recovery paths. This establishes the physical address boundary;
it does not yet claim the reference design's two-cycle retirement loop.

O8.3e replaces the one-cycle full-partition recovery scan with a retained
two-pass walk. The first pass reads at most
`robRecoveryScanGroupsPerCycle` consecutive groups, one per physical bank at
the default width, validates the immutable old window, and finds exactly one
full-identity pivot. The second pass rereads the same bounded slices to retain
the ordered kill records and surviving tail. Request drift and malformed rows
reject without mutation; prepare withdrawal discards only private metadata;
the common O3 apply remains the sole row/occupancy mutation. Default eight-bank
recovery therefore takes eight find cycles plus eight build cycles for a
64-group window. Generated eight- and sixteen-group test modules remain roughly
linear at 107,178 and 217,545 lines, but grow versus O8.3d because the complete
plan is retained; this packet claims bounded compare/read depth, not area
reduction.

O8.3f registers ROB head selection before entry payload and pointer mutation.
The selection stage retains the exact STID/head/epoch/count token; the next
stage validates that token and captures the banked payload into the stable
commit row. Same-STID recovery is fenced throughout both stages, peer STIDs
continue, and only the retained commit fire clears rows or advances the head.
This implements the two-cycle entry-read-to-pointer split described in
`Documents/a.txt` section 6.4.1.2.1 without importing its ARM-specific state
classes or age shortcuts.
The generated four-wide modules are 107,407 lines at eight groups and 217,635
lines at sixteen groups, remaining approximately linear with depth; these
figures track generated structure and are not an area or timing-closure claim.

O8.3g maps PC-base storage as `[stid][bank][row]`. Low local-index bits select
one of four default banks, high bits select one of four rows, and one shared
decoder serves allocation, publication, commit, recovery, and all consumer
reads. The parameters reject an allocation or retirement prefix wider than the
bank count. Directed UT crosses banks and rows and simultaneously drives all
six read ports, including two different rows of one bank. The external token,
byte offset, allocation epoch, per-STID ring order, and common O3 transaction
remain unchanged. This is the address boundary motivated by `Documents/a.txt`
sections 6.6.3–6.6.8; it is not a claim that a six-read SRAM macro or the PC
recovery path has closed timing.

O8.3h captures the exact grouped-ROB recovery plan and walks
`pcRecoveryScanGroupsPerCycle` killed-group records per cycle. The default
four-group slice needs 16 scan cycles for a 64-group window. Each slice checks
PC tokens and implicit-close ownership while accumulating per-row reference
counts, prior ROB keys, close-owner repair, and allocated-row clears. Plan
drift rejects, valid withdrawal aborts private state, the target STID is
fenced, and peer STIDs continue. A successful final slice retains both the
public prepared payload and private repair masks until the common apply; apply
does not recreate the full killed-window CAM.

O8.3i replaces the previous per-cycle `stidCount * robGroupsPerStid`
non-flush prefix network with one retained scanner. A dirty-vector plus
advancing RR start selects one non-interrupted, non-recovering STID. Capture
freezes the exact head, occupancy, PE, head epoch, current authorized prefix,
and window epoch; each later cycle validates no more than
`robNonFlushScanGroupsPerCycle` consecutive rows through the shared banked
address transform. The scanner always begins at offset zero so it revalidates
the already-public prefix, stops at the first unsafe/hole/malformed identity,
and publishes the final count only when the complete snapshot still matches.
Publication, accepted evidence, commit, and recovery apply force a private
restart for the target STID. Interrupt and recovery fencing preserve the
public value and peer progress. Commit remains the immediate prefix-rebase
owner and recovery remains the target-window reset owner. Generated-test
modules are 107,286 SystemVerilog lines at eight groups and 216,393 lines at
sixteen groups; the old `safePrefixByStid` network is absent. These counts are
structure evidence, not area or synthesis timing claims.

O8.3j separates the canonical PC lifecycle table from the replicated consumer
payload. The metadata owner still stores ROB-group cursors, live counts, close
ownership, and recovery state once. Three explicit
`[stid][bank][row]` arrays replicate only
`{valid, STID, index, allocationEpoch, base}`; compile-
time port mapping assigns the six readyless reads as 0/3, 1/4, and 2/5. Common
S1 allocation, ordered commit free, and exact recovery free broadcast to every
replica on the same owner mutation. Parameter checks require an even mapping
with no more than two ports per replica. Directed UT reads every replica after
allocation, after recovery, and after ordered free. The default standalone
generated module contains 64 rows in each of `readReplicas_0/1/2` and is
211,765 SystemVerilog lines. This proves explicit retained structure, not
foundry-macro inference, area, or timing closure.
Final packet gates pass with `OooParamsSpec` 3/3, `OooPcBufferSpec` 15/15,
`OooRobBrobPcCoordinatorSpec` 6/6, `OooO3RenameCoordinatorSpec` 7/7,
`OooO3IexIntegrationSpec` 1/1, real-IFU
`OooFrontendIfuRecoveryIntegrationSpec` 1/1, and `Test/compile`.

The remaining O8.3 work realizes multirow metadata/read-payload write fanout
through a selected array or macro boundary and closes width timing. A
synchronous macro may require a registered response phase inside the now
explicit IEX I1/I2 contract rather than the current readyless read boundary.
The bounded
retirement-width commit eligibility path still needs timing evidence rather
than being hidden behind the registered payload boundary.
Occupancy/in-flight cost steering, PTag-bank coupling, one-cycle-ahead policy,
and safe thresholds remain physical-policy gaps. Copying the reference
design's ARM register classes or RID/BID age shortcuts remains forbidden.

Exit: timing reports contain no unbounded free-list encoder, group prefix, or
ready-loop path; all functional coverage remains closed after banking changes.

### O9: canonical integration and promotion

Connect canonical IFU, CTU, IEX, BCTRL, ROB/BROB, and commit/recovery; remove
reduced/shadow owners from the canonical hierarchy; run generated RTL,
QEMU/DUT, CoreMark, and Dhrystone promotion evidence.

Exit: commit/stage traces match, four-thread stress passes, no forbidden owner
is instantiated, and every release artifact records revisions/parameters/seeds.

## 26. Canonical forbidden-instance and forbidden-contract gate

Canonical elaboration/CI fails if it finds:

- a `Reduced*` architectural state owner;
- physical ROB allocation at D1-to-D2 fire;
- one-RID-per-uop assumptions in a grouped interface;
- a group spanning two STIDs or BIDs;
- BID widened with hidden age/generation bits or compared unsigned;
- D3 selecting PTags directly from the free list;
- FP/CC rename state inside OOO;
- Tile physical rename inside OOO;
- standalone BSTART/BSTOP required when a legal fusion exists;
- physical IQ residency mirrored in OOO;
- S1 publish without a retained S2 entry reservation;
- slot-only completion or recovery identity;
- full-PC execution dependence after PC-buffer promotion;
- CTU direct ROB/RF/LSU/architectural mutation;
- recovery of one STID changing another STID's maps, ROB, BROB, PC, IQ, or CTU
  state.

## 27. Review checklist before Chisel expansion starts

The review should explicitly approve:

1. RID is a grouped ROB identity; exact member completion adds member index and
   generation, while BID remains separate.
2. `maxInstPerRobGroup=4` and the initial ordinary member cap are acceptable.
3. the migration meaning of existing GID fields.
4. BSTART-forward/BSTOP-backward fusion and standalone fallback semantics.
5. CTU children may span multiple RID groups but emit one parent commit row.
6. D2 virtual planning versus D3 provisional/S1 publish ownership.
7. four-thread independent per-stage arbitration and shared-resource quotas.
8. PTag bank count, FIFO refill ownership, and SMAP IQID/ready payload.
9. byte-granular 64-entry PC-buffer format and port targets.
10. S1 speculative slot, S2 physical bind, and S3 pick-ready contract.
11. initial fast-resolve whitelist and strict blacklist.
12. `START_CALL_32/48` as a control-plus-RA producer, not a pure boundary.
13. exact non-flush window and permitted consumers.
14. CMAP-to-SMAP plus surviving-MapQ replay recovery behavior.
15. the O0 normative-document changes that must land before RTL work.

Once these decisions are approved, O0 and O1 are the correct starting point
for the Chisel extension. Implementing width expansion before grouped identity,
stage ownership, and the S1/S2 reservation contract are frozen would create a
second migration rather than a canonical path.
