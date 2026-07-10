# ISA-Neutral Microarchitecture Alignment

This document defines the migration method for ARM-oriented microarchitecture sections that are mostly ISA-neutral. The goal is to preserve proven core structures first, then bind those structures to LinxISA `v0.56` semantics.

Use this file before reading the section-parity matrix. It prevents the migration from replacing a reusable OOO mechanism merely because the source document was written for ARM.

## 1 Alignment Method

Every migrated section uses a two-pass rule:

1. Preserve the ISA-neutral structure.
   Keep tables, queues, maps, arbiters, pipeline stages, state machines, ready tracking, commit ordering, recovery boundaries, cache/TLB structures, and bypass paths when the mechanism is not ARM-specific.

2. Rebind the architectural fields to LinxISA.
   Replace ARM architectural register classes, exception levels, branch rules, barriers, PAC/BTI/SVE features, and system-register semantics with LinxISA operand classes, block boundaries, ACR/SSR trap state, TSO memory rules, and block-fabric engine semantics.

The result should look like an upgraded OOO core whose architectural contract is LinxISA, not a fresh design that discards useful neutral implementation structure.

## 2 Frontend and PC Buffer

### ISA-Neutral Structure to Preserve

- Multi-stage fetch path with PC select, I-cache lookup, data/ECC staging, instruction assembly, instruction buffer, and decode window.
- PC buffering to avoid carrying full PC values through every downstream pipe.
- PC base plus offset representation for compact uop payloads.
- PC-buffer allocation and release tied to decode/ROB lifetime.
- Hashed or compact PC metadata for load/store ordering, MDB, trace, and debug.
- Redirect/restart interface from recovery to fetch.

### LinxISA Binding

- Instruction assembly must support Linx variable-length 16/32/48/64-bit forms.
- F4/IB is the final fetch/instruction-buffer boundary. D1 reads a
  non-compacting `decodeWidth` group from its ordered bytes and may form a
  continuous decode view only within that stream; it must not combine
  unrelated entries to manufacture an instruction.
- PC metadata must carry block-boundary context, `BSTART`/`BSTOP` identity, template markers, `B.TEXT` body entry metadata, and checkpoint identity.
- Target-bearing `BSTART` forms compute legal block targets from PC-relative immediates.
- Dynamic target consumers (`RET`, `IND`, `ICALL`, `FRET.*`) consume live target-owner state from `SETC.TGT`, call-return materialization, or template return state.
- Recovery restart is an explicit `FLS -> F0` handoff with target PC, checkpoint, boundary kind, and trap metadata. It is not an implicit fetch-loop side effect.

## 3 Decode, Uop Formation, and Grouping

### ISA-Neutral Structure to Preserve

- D1/D2/D3 stage split.
- Predecode or early decode sidebands for downstream resource checks.
- All-or-nothing decode-group resource admission.
- Uop break/fuse machinery when an instruction maps to multiple execution actions.
- ROB-visible group/row identity and instruction raw/length preservation.
- Decode-side stall reporting for downstream resource pressure.

### LinxISA Binding

- Opcode classification is generated from Linx opcode metadata, with most-specific mask match inside the instruction-length domain.
- Operand classification maps source tags `0..23` to scalar `P`, `24..27` to `T`, `28..31` to `U`; destination tags `0..23` to scalar GPR, `31` to `T`, and `30` to `U`.
- BBD marker rows (`BSTART`, `BSTOP`, compressed and extended forms, template markers) are first-class decode products.
- Store split preserves Linx source ordering: STA/STD share identity; PCR stores, indexed stores, store-immediate forms, and compressed stores keep their catalog-defined source roles.
- Decode stamps row identity, block identity, all-row LSID snapshot, instruction length, raw bits, PC, checkpoint, and boundary metadata.

## 4 SMAP, CMAP, Free List, and MapQ

### ISA-Neutral Structure to Preserve

The speculative-map, committed-map, free-list, and mapping-queue structure is reusable.

| Structure | Neutral responsibility |
| --- | --- |
| SMAP | Latest speculative architectural-to-physical mapping visible to rename. |
| CMAP | Committed architectural-to-physical mapping visible after retirement. |
| Free list | Tracks physical registers available for future destination allocation. |
| MapQ | Ordered log of speculative remaps used for commit release and flush recovery. |

The neutral MapQ entry shape should include:

- valid bit;
- thread or STID/PE owner where multithreaded;
- architectural destination tag;
- newly allocated physical tag;
- previous physical tag to release on commit or restore on flush;
- `(stid, bid)` identity and `rid` ordering sidecars, with BROB ring-age state
  supplied wherever cross-block order is needed;
- decode slot or insertion-order sidecar;
- checkpoint/block context where recovery uses snapshots;
- committed/retired state if commit and deallocation are split.

Neutral behavior:

- Rename reads SMAP for sources and writes SMAP for destinations.
- Destination allocation consumes free-list entries in decode-slot order.
- MapQ append order is program order within the rename group.
- Commit walks MapQ in architectural retirement order, updates CMAP, and releases overwritten committed tags.
- Flush restores SMAP from a checkpoint or CMAP, then reapplies surviving MapQ rows in order.
- Same-architecture multiple writes must release intermediate physical tags correctly.

### LinxISA Binding

- The scalar `P` class uses SMAP/CMAP/MapQ.
- Current scalar model evidence: 24 architectural scalar GPR tags in the scalar owner, `ggpr_count=128`, `ggpr_mapq_depth=256`.
- `P` MapQ recovery is instruction-precise by `rid` and block-aware by `bid`/checkpoint.
- `T` and `U` do not use scalar SMAP/CMAP/MapQ. They use local ClockHands rename, local sequence sidecars, relation-cmap retire paths, and block-commit cleanup.
- `CARG` is not renamed. It resolves by active `BID` into block condition/argument state.
- Block-stop cleanup must present the correct cleanup BID. Passing the current finished block when the next-block BID is required can restore the pre-block checkpoint and lose valid post-block mappings.

## 5 ROB and Commit

### ISA-Neutral Structure to Preserve

- ROB allocation in program order.
- Row status lifecycle: free, allocated, renamed, issued, completed, retired, fault/flush.
- Commit walks contiguous completed rows from the head.
- Commit and deallocation are separate when retired rows must remain resident for cleanup or duplicate-identity protection.
- Flush pruning scans ordered rows and rebases allocation/commit pointers.
- Commit trace is emitted in commit slot order.

### LinxISA Binding

- ROB rows carry the `BID_W`-bit hardware `blockBid` together with separate
  STID and any required ring-age/epoch sidecars; this remains distinct from
  model-shaped `bid/gid/rid` sidebands.
- `BSTART` carries the new block BID. On BSTART retire, scalar_done applies to the old active BID. On BSTOP retire, scalar_done applies to the current active BID.
- BROB owns dynamic block identity, block completion, engine completion, and
  ring-qualified flush.
- A BROB flush derives the wrapped live kill interval from `(STID, BID)`, ring
  head/tail/occupancy, and internal wrap state. Consumers use that kill context
  rather than numeric BID comparisons.
- Marker-row mode must split decode-time active block context from retire-time marker effects.

## 6 Issue Queues

### ISA-Neutral Structure to Preserve

- Physical IQs with valid entries, source-valid/source-ready bits, age ordering, inflight locks, replay/cancel, and deallocation at a non-cancellable point.
- Ready-table initialization at enqueue.
- Oldest-ready pick.
- RF read arbitration after pick.
- Wakeup path separate from same-cycle pick.
- Bypass network for producer-to-consumer data.
- Queue merge/split freedom behind stable external uop-kind names.

### LinxISA Binding

- External uop-kind names remain `issq_alu`, `issq_bru`, `issq_agu`, `issq_std`, `issq_fsu`, `issq_sys`, `issq_cmd`, and related classes.
- Promoted physical layout: `alu_iq0`, `shared_iq1`, `bru_iq`, `agu_iq0`, `agu_iq1`, `std_iq0`, `std_iq1`, `cmd_iq`.
- `P` wakeup is global by ptag.
- `T/U` wakeup is point-to-point with `qtag = (phys_issq_id, entry_id)`.
- BBD marker-only rows do not enter ordinary IQs.
- CMD rows connect to BISQ/BCTRL/block fabric and must preserve `BID`, command tag, and epoch sidecars.

## 7 IEX, Register Files, and Bypass

### ISA-Neutral Structure to Preserve

- ALU, BRU, AGU/STA, STD, shared SYS/FSU, and command execution classes.
- P0/P1/I1/I2 plus E1/E2/E3... execution coordinates and W1/W2/W3
  producer-result overlays.
- RF read-port arbitration at I1.
- Dedicated writeback resources for producer pipes.
- Store data pipe with read ports but no destination writeback.
- Multi-cycle latency-to-wakeup table.
- Load-to-use bypass path.

### LinxISA Binding

- BRU computes `SETC.*`, branch condition, dynamic target setup, and deferred correction metadata; it does not directly redirect architectural PC.
- Boundary rows consume BRU correction and target-owner state at block commit.
- ALU semantics follow Linx scalar opcode rules, including W-form shifts, `ADDTPC`, `HL.LUI/LIS/LIU`, compressed arithmetic, and local `T/U` source suppression in QEMU-shaped trace.
- RF writes are gated by destination kind. Scalar GPR writes update scalar `P` RF; `T/U` writes update local bank owners.
- `FENTRY`, `FRET.*`, and reduced macro/template paths may use hidden owner state such as SP shadow only until full macro/LSU ownership is promoted.

## 8 LSU, Store Queue, and Load Queues

### ISA-Neutral Structure to Preserve

- AGU issue path for address generation.
- Store split into address and data halves.
- STQ for speculative stores.
- LIQ/LDQ for in-flight loads and replay/miss state.
- LHQ or equivalent hit queue for resolved load metadata.
- Store-to-load forwarding CAM with byte masks.
- Load miss/refill/replay wakeup.
- Store drain path after commit.
- Precise MMU/device fault reporting.

### LinxISA Binding

- Decode allocates all-row LSID snapshots in slot order; only memory rows increment the LSID counter.
- Scalar LSU issue is ordered by `lsid_issue_ptr` in the strict profile.
- Store split STA/STD share SID/LSID identity.
- Loads record youngest store snapshot; forwarding considers stores no newer than that snapshot.
- Forwarding selects the byte-granular nearest older store by BROB ring age
  across blocks and LSID within a block.
- Store-arrival conflict detection may mark wait-store, same-BID inner flush, or cross-BID nuke.
- Nuke recovery is triggered only when the nuke-marked load reaches ROB head
  and flushes younger blocks through the selected STID's BROB ring-age context.

## 9 Cache, SCB, TLB, and Coherence

### ISA-Neutral Structure to Preserve

- L1D tag/state/data arrays.
- TLB/translation pipeline with fault reporting.
- Line-fill or refill request/response path.
- Store coalescing buffer for committed stores.
- Store response buffering and retry.
- DCache lookup/update path.
- Coherence-response matching by transaction ID.

### LinxISA Binding

- Linx memory model is TSO.
- STQ may contain flushable stores; SCB may contain only stores guaranteed not to be flushed.
- SCB coalesces by physical cacheline and drains in store order.
- Do not merge into an SCB row that has issued a request and is waiting for response.
- Completion of a store-drain/fence-visible write is WriteResp or equivalent response, not request acceptance.
- Current SCB contract uses transaction IDs encoded as `(entryIndex << 2) | 2`.
- Device/MMIO accesses are side-effecting and must remain non-speculative.
- Linx fences and atomics use Linx `.aq/.rl/.aqrl`, `FENCE.D`, `FENCE.I`, and profile-defined memory types. ARM DMB/DSB/exclusive monitor behavior is not assumed.

## 10 MDB and Memory Disambiguation

### ISA-Neutral Structure to Preserve

- Store/load conflict detector.
- Predictor table for repeating conflicts.
- Lookup, record, delete, and wakeup queues.
- Conflict confidence/weight or equivalent throttling.
- Recovery publication path to ROB/flush owner.

### LinxISA Binding

- Scalar conflicts require address overlap and `LessEqual(store.bid, store.lsID, load.bid, load.lsID)`.
- Resolved active LIQ rows and ResolveQ rows can be flush candidates.
- Unresolved active rows are marked wait-store for store-address probes.
- Same-BID conflicts are inner flushes.
- Cross-BID conflicts are load-attributed nukes.
- Tile load/store conflicts stay suppressed until a tile-specific owner defines their ordering.

## 11 Engine and Block Fabric

### ISA-Neutral Structure to Preserve

- Command queue.
- Command tag allocation.
- Response matching.
- Engine completion scoreboard or reorder buffer.
- Command/response trace.
- Flush cancellation of younger commands.

### LinxISA Binding

- BROB generates a `BID_W = ceil(log2(BROB_ENTRIES))` slot BID. Command tag
  equals BID and command STID remains separate; an 8-bit command lane is the
  default-256-entry representation, not a widened BID rule.
- BISQ/BCTRL issue commands to VEC, TMA, CUBE, TAU, TEPL, and related engines.
- Engine completion participates in `scalar_done && engine_done`.
- Engine traps report through ROB/BROB and architectural trap state.
- Engine memory effects must obey BCC/MTC ordering and block completion.

## 12 Open Questions

- Which ISA-neutral ARM structures should be promoted as explicit module diagrams in the LinxCore draft rather than captured textually?
- Should the first LinxCore spec freeze a banked SMAP/MapQ organization, or only the behavior and sizing contracts?
- Which cache/TLB details are architectural enough to document now versus leaving to platform profile and implementation owner packets?
