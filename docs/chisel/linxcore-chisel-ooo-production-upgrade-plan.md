# LinxCore Chisel OOO Production Upgrade Plan

## 1. Document status and accepted direction

This document is the review-ready implementation plan for replacing the current
bring-up OOO path with a production `LinxCoreOoo`. It incorporates the useful
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
| O0 normative contracts | In progress | microarchitecture, block-control, pipeline-stage, CTU, common/production bundle pages and generated 689-record opcode recipe audit updated | exact S1/S2/S3 integration contract |
| O1 packet family | Implemented | `OooParams`, exact identity/stage bundles, 2/4/6 width elaboration | conservation monitors beyond stage occupancy |
| O1 four-thread shell | Implemented | private per-STID D2/D3/S1 rows, stable shared grants, 1/2/4 STID tests | WFI/inactive inputs and bounded starvation counters |
| O2 decode/expand/fuse | Implemented | schema-v2 generated recipes; fixed-four-wide IFU to per-STID 2/4/6 raw reservoir; parameterized canonical D1; exact P/T/U and pair operands; precise traps; exact CTU/complex diverted-parent sidebands; same/cross-cycle three-parent boundary fusion; focused UT/IT | the catalog has zero dispatch-owned complex forms, so unresolved macro/atomic forms remain fail-closed; CTU child reinsertion remains O7 |
| O3 grouped ROB/BROB/PC | Implemented | D2 virtual grouping and retention; D3 provisional claims; atomic S1 grouped ROB; exact member completion/commit; native BID/generation BROB; fixed-partition 64-entry byte-offset PC buffer; one shared reserve/publish/commit coordinator | integrate O3 prepared publication with O4 RENU and O5 dispatch owners |
| O4 P/T/U RENU | In progress | generation-qualified banked PTag staging/free-list owner; per-STID provisional leases; P SMAP prepare/publication; bundle-wide RAW/WAW inlining; ordered exact P MapQ rows; serialized CMAP/old-PTag commit walk; independent per-STID T/U sequential reserve, same-bundle relative bypass, wrap-qualified local tags, exact local MapQ publication; every-logical-uop retire sidecar; ordered T/U relation-CMAP mark/deallocation; post-clean exact block release; atomic P/T/U commit-owner start; exact read-only recovery authorization and youngest-to-oldest killed-source stream; killed-current-PTag return plus CMAP-to-SMAP survivor replay; exact T/U MapQ suffix and sequence/physical-cursor rollback | atomic four-STID recovery integration; randomized sequential-reference closure |
| O5–O9 | Not started | current compatibility owners remain migration evidence | dispatch through benchmark promotion follow |

“Implemented” in this ledger is packet-scoped; it does not promote the current
benchmark hierarchy to production OOO.

## 2. Product outcome

The production graph is:

```text
IFU fixed-64b Instruction Buffer ----+
                                     +--> OOO ingress --> D1 decode/fuse/break
external CTU canonical child rows ---+                    |
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
  and every IQ entry have one production owner.
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

The first production defaults are planning targets, not hard-coded constants.

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
| `brobEntriesPerStid` | 256 | Native BID remains 8 bits by default |
| `pArchRegs` | 24 | Absolute architectural P namespace |
| `pPhysRegs` | 128 | Minimum: 96 committed mappings plus eight speculative tags guaranteed per STID; 192/256 are scale points |
| `pTagBanks` | 2 | Bank-aware allocation, RF, and IQ steering |
| `pMapQDepthPerStid` | 256 | Independent of ROB and physical IQ depth |
| `pcBufferEntries` | 64 | Shared storage with per-STID quotas |
| `pcOffsetWidth` | 7 | Byte offset, required by 2/4/6/8-byte instructions |
| `pcWritePorts` | 3 | Physical closure target |
| `pcReadPorts` | 6 | Commit, branch validation, trace, and execution |

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
They must not become a competing age domain. The production specification must
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

The opcode catalog must gain production metadata generated into pyCircuit,
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

Production D1 is split into a combinational canonical decoder and a retained
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
retirement. `OooProductionTURetire` retains one exact sidecar for every
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

The first bring-up uses four fixed 16-entry per-STID partitions. A later
configuration may lend unused entries while preserving per-STID head/tail and
recovery. Three write ports and six read ports are the physical target; port
pressure participates in D2 maximum-prefix admission.

A ROB group records only the ordered PC-base release obligation
(`releasePcBase` or a one-entry release count); it does not store the PC base or
base index as general ROB payload. `pcBaseIndex + pcOffsetBytes` travels with
the uop/PC packet to consumers that need PC. A precise exception consumer must
retain its reconstructed exception PC before the execution member is released.
Commit advances the selected STID's PC-buffer release pointer from the ROB
release indication. Debug builds may retain a checked full-PC shadow that must
equal reconstructed PC and is forbidden from becoming an execution owner.

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

The S1 reservation guarantees eventual S2 capacity unless matching recovery
cancels it. Therefore rename may safely publish `producerIqid` at D3/S1 without
depending on an unreserved future allocation.

Physical IQ valid bits, age matrix, wakeup, pick, speculative issue recovery,
RF arbitration, and execution are IEX-owned. OOO consumes credits and
acknowledgments but never mirrors IQ residency.

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

| Current catalog family | Initial production decision |
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

CTU remains outside IFU and OOO. `IfuCtuOooBridge` arbitrates raw fixed-width
IFU entries and CTU-produced canonical children into an OOO ingress/expansion
buffer.

When a template parent is claimed:

1. the raw parent is consumed exactly once;
2. CTU computes its exact ordered child recipe and resource envelope;
3. the bridge obtains a retained group/space lease;
4. CTU emits canonical children carrying parent/template/ordinal identity;
5. children enter normal D1 validation, D2 grouping, D3 rename/ROB, S1
   dispatch, execute, precise trap, and commit paths;
6. the final child gates the one architectural parent commit record.

CTU never allocates RID/BID/PTag/IQ state, writes PRF or memory directly,
completes ROB state directly, or globally blocks unrelated STIDs. Recovery
cancels CTU state with exact `{PE, STID, parent, templateGroup, generation}`.

The current Template D3 reservation/fill machinery is a migration oracle for
row recipes and cancellation tests only. It cannot coexist as a production
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

| Current component/assumption | Production action |
|---|---|
| `D1InstructionDecodeStage` | Generalize to multi-instruction/multi-uop D1 and generated recipe metadata |
| `D1DecodedLaneQueue` | Replace one-row drain with per-STID dense ingress and expansion buffering |
| `D1DecodeRenameROBIngress` | Remove physical D1/D2 allocation; connect D1 plan to D2 virtual grouping |
| `DecodeRenameROBPath` | Decompose into D1, D2 planner, D3 publisher/RENU, and S1 retained output |
| `DispatchROBAllocator` | Replace cursor allocation with virtual-tail token plus D3/S1 banked publisher |
| `ROBEntryBank` | Promote to grouped/member-count ROB with provisional/published states and non-flush |
| `ReducedCommitROB` | Legacy oracle only, then remove from production elaboration |
| `GPRRenameCheckpoint` | Promote after staging FIFO, IQID/ready SMAP, banked MapQ, and four-STID API |
| `ScalarTURenameBridge` | Move state into independent per-STID T/U production owners |
| `StoreSplitPayload` | Retain pure transform; move atomic S1/S2 reservations to dispatch/IEX owners |
| `BlockMarker*` standalone row path | Replace normal case with boundary-bit fusion; retain fallback and differential tests |
| `ScalarIssueFabric` / reduced IQs | Replace OOO boundary with IEX S1 speculative-slot credits and S2/S3 protocol |
| Template D3/shadow modules | Keep row-plan oracle temporarily; production templates use external CTU bridge |
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

### O2: production decode, expansion, and fusion

Deliver generated recipe metadata, ordinary one-to-five-uop break, retained
complex-break engine, boundary fusion, standalone fallback, and dense D2 input.

Exit: opcode audit is complete; expansion/fusion differential tests and all
four-thread recovery-history tests pass.

### O3: grouped RID ROB/BROB and PC buffer

Deliver D2 virtual group planner, D3 provisional/S1 publisher, member resolve
tracking, native BID/BROB integration, 64-entry PC buffer, and grouped commit.

Implementation status: packet complete. The virtual planner, retained D2 row,
D3 provisional allocator, S1 atomic grouped-ROB publication, exact member
completion, retained grouped commit, production native BID/generation BROB,
production 64-entry PC-base owner, and terminal coordinator are implemented.
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

### O4: P/T/U production rename

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
O4.4.2 adds an independent `OooProductionTURetire` owner rather than mixing
retire-source and relation state into sequential allocation. Every logical uop
is published into a per-STID source ring, including no-destination block-last
rows. Retained ROB batches are matched exactly before ordered T pre-release, U
pre-release, destination mark, pressure release, exact-block relation cleanup,
and local block commit. `OooProductionTURename` alone continues to own T/U
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
Randomized sequential-reference comparison remains active O4 work.

Exit: tag/map conservation and randomized sequential-reference comparisons pass
for four STIDs; D3 has no direct free-list priority selection.

### O5: dispatch and IEX S1/S2/S3

Deliver generated classes, bank/port/entry steering, contiguous-prefix and
split atomicity, S1 speculative slots, S2 physical bind, and S3 pick enable.

Exit: every class/bank/port contention cross closes; no ready loop; target and
payload remain stable through arbitrary S1 backpressure.

### O6: fast resolve and non-flush

Deliver typed fast-resolve sinks, boundary/direct-call validation, SETRET result
path, precise trap completion, per-STID non-flush frontier, and counters.

Exit: full opcode whitelist/blacklist tests pass; non-flush never commits or
frees rename state; affected STID can advance independently.

### O7: recovery and external CTU

Deliver R0-R4 coordinator, CMAP-to-SMAP/replay, PC/ROB/BROB/IQ cancellation,
external CTU lease/children/final-parent protocol, and template multi-RID groups.

Exit: recovery at every stage and template phase closes with zero cross-STID
mutation; CTU has no direct RF/ROB/LSU architectural-effect port.

### O8: width and physical closure

Bring up instruction width 2, then product width 4, then scale width 6;
independently tune uop/rename/dispatch/retire widths, ROB banking, even/odd
subbanks, PTag FIFO depth, PC ports, and IQ steering.

Exit: timing reports contain no unbounded free-list encoder, group prefix, or
ready-loop path; all functional coverage remains closed after banking changes.

### O9: production integration and promotion

Connect production IFU, CTU, IEX, BCTRL, ROB/BROB, and commit/recovery; remove
reduced/shadow owners from the production hierarchy; run generated RTL,
QEMU/DUT, CoreMark, and Dhrystone promotion evidence.

Exit: commit/stage traces match, four-thread stress passes, no forbidden owner
is instantiated, and every release artifact records revisions/parameters/seeds.

## 26. Production forbidden-instance and forbidden-contract gate

Production elaboration/CI fails if it finds:

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
second migration rather than a production path.
