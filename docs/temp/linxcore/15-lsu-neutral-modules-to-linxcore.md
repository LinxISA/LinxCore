# LSU Neutral Modules Applied to LinxCore

This document applies the ISA-neutral LSU microarchitecture structures from the migrated upgrade corpus to LinxCore module by module. The reusable structures are AGU issue, STQ, LIQ/LHQ, load-forwarding, TLB/cache lookup, refill, SCB, MDB, and response queues. LinxCore-specific binding adds LinxISA memory semantics, LSID ordering, BCC/MTC channels, block-aware recovery, and Linx tile-memory engines.

## 1 Source Coverage

Primary neutral source sections:

- `docs/temp/upgrade/09-lsu-overview-features.md`
- `docs/temp/upgrade/10-lsu-structures-instructions.md`
- `docs/temp/upgrade/11-lsu-pipelines.md`
- `docs/temp/upgrade/12-lsu-control-ordering-resources.md`
- `docs/temp/upgrade/13-lsu-mdb-exclusive-atomic.md`

Cross-check sources:

- LinxISA manual: `09_memory_operations.adoc`, `11_agu.adoc`, `14_amo.adoc`, `15_bbd.adoc`, and `17_cmd.adoc`
- LinxCore architecture: `docs/architecture/lsid_memory_ordering.md`, `microarchitecture.md`, `block_fabric_contract.md`, and `verification-matrix.md`
- LinxCoreModel: `model/mtccore/lsu/load_unit/MtcLDQInfo.h`, `store_unit/MtcSTQ.h`, `store_unit/MtcStoreUnit.h`, `mdb/MtcMDB.h`, `ModelCommon/bus/MemReqBus.h`, and `ModelCommon/bus/FlushBus.h`
- Chisel docs: `docs/chisel/README.md`, `docs/chisel/module-index.md`, and module pages under `docs/chisel/modules/lsu`

## 2 LSID Allocator and Memory-Order Identity

### Neutral Structure

Keep per-memory-operation identity:

- allocate a load/store sequence number in dispatch order;
- attach it to ROB and LSU rows;
- use it for ordering, forwarding, replay, and flush;
- rebase or clean it during recovery.

The neutral source has separate load and store IDs in several paths. LinxCore keeps the identity concept, but binds it to LSID.

### LinxCore Binding

LinxCore strict profile:

- Dispatch allocates monotonic LSID snapshots for every row.
- Only memory-class rows consume an LSID.
- STA and STD halves of a store share the same store identity.
- Loads record their youngest-store snapshot at allocation or issue.
- LSU issue is gated by `lsid_issue_ptr` in the strict profile.
- Flush rebases issue and complete pointers to avoid deadlock on killed speculative rows.

### Model and Chisel Check

Model evidence:

- `DCTop::SetLoadStoreID`
- `GenCoder` assignment of `inst->lsID`
- `FlushBus` carries `lsID`, `sid`, and `ldid`

Chisel owners:

- `DecodeLoadStoreIdAssign`
- `DecodeRenameROBPath`
- `ROBEntryBank` memory-order sidecars
- `LoadResolveQueue`
- `LoadInflightQueue`

Acceptance contract:

- All LSU rows must carry `(stid, bid, gid/rid where needed, lsID)` plus any
  BROB ring-age/epoch sidecars needed after their producer leaves BROB.
- Any flush comparison must state whether it uses an exact `(STID,BID)` match,
  a BROB-provided younger-block kill set, or an LSID-qualified predicate. BID
  by itself is not an order relation across wrap.

## 3 AGU, LDA, and STA Issue

### Neutral Structure

Keep address-generation issue classes:

- load address generation;
- store address generation;
- PC-relative address formation;
- TLB lookup request formation;
- memory-type classification;
- fault capture.

### LinxCore Binding

Linx scalar memory instructions use AGU semantics from LinxISA:

- base plus immediate and base plus index forms;
- PCR-relative loads/stores where decoded;
- bridged memory forms with explicit `ri*` base namespace;
- atomics and fences as memory-ordering participants;
- device/MMIO accesses treated as side-effecting and non-speculative.

### Model and Chisel Check

Model evidence:

- `MtcLDQInfo` load pipeline entry points include `lookup`, `handleL1Lookup`, `handleL2Lookup`, and `returnData`.
- `MtcSTQ` stores address, size, data, readiness, and state per row.

Chisel owners:

- `ReducedScalarAluExecute` for reduced address sidebands and store sidebands.
- `ReducedStoreStaAddressExecBridge`
- `StoreDispatchToSTQ`
- `StoreDispatchSTQPath`

Acceptance contract:

- AGU may execute before data is ready for stores, but store visibility waits for commit and SCB admission.
- Device/MMIO AGU must not permit speculative side effects.

## 4 Store Split: STA, STD, and Store Queue

### Neutral Structure

Keep store split:

- STA computes address and memory attributes.
- STD supplies data and byte enables.
- STQ merges halves by store identity.
- Store rows remain speculative until ROB commit.
- Store rows forward to younger loads when legal.

### LinxCore Binding

LinxCore store split rules:

- Decode creates STA/STD components for scalar stores when needed.
- STA/STD share `BID`, `RID`, `LSID`, PC, and store identity.
- Store data source order follows Linx decode metadata, not ARM operand order.
- PCR stores and compressed stores keep their catalog-defined source roles.
- Store entries are flushable in STQ and non-flushable only after SCB admission.

### Model and Chisel Check

Model evidence:

- `MtcSTQueueEntryInfo` stores `vld`, `MemReqBus`, `bid`, `rid`, `size`, `addr`, `data`, `addrRdy`, `dataRdy`, and FSM.
- `MtcSTQ::insert`, `mergeStore`, `lookupForLoad`, `retire`, `commit`, and `flush`.

Chisel owners:

- `StoreDispatchQueues`
- `StoreDispatchToSTQ`
- `StoreSplitPayload`
- `STQInsertProbe`
- `STQEntryBank`
- `StoreDispatchSTQPath`
- `STQFlushPrune`

Acceptance contract:

- STQ is speculative and flush-pruned.
- A store may not leave the speculative domain until ROB commit proves it cannot be cancelled.
- Merge-bypass is legal only when STA/STD identity is preserved.

## 5 Store-to-Load Forwarding

### Neutral Structure

Keep byte-granular store forwarding:

- compare load address against older stores;
- select nearest older matching store per byte;
- merge store data over base load data;
- wait or replay when a matching older store lacks data or address;
- report conflict evidence to replay/MDB paths.

### LinxCore Binding

LinxCore forwarding rule:

- Consider only stores older than or equal to the load snapshot.
- Use the selected STID's BROB ring age for cross-block order and LSID for
  order within a block; an exact `(STID,BID,LSID)` tuple identifies a row but
  does not itself define wrapped age.
- Select nearest older store per byte.
- `STQ` data overrides `SCB`, and `SCB` overrides L1D/refill data.
- If a required byte comes from a data-not-ready store, the load becomes wait-store/replay eligible.

### Model and Chisel Check

Model evidence:

- `MtcSTQ::lookupForLoad`
- `MtcSTQ::getData`
- `MtcLDQInfo::handleSTQReceive`
- memory FU docs under `/Users/zhoubot/linx-isa/docs/memory`

Chisel owners:

- `LoadStoreForwarding`
- `ResidentStoreForwardStoreSnapshot`
- `ReducedStoreResidentForward`
- `LoadForwardPipeline`
- `LoadReplaySourceReturnStoreSnapshot*`

Acceptance contract:

- Forwarding must be 64-byte cacheline/byte-mask aware.
- Partial coverage must merge correctly or wait; it must not consume stale L1D bytes silently.

## 6 Load Inflight Queue, Load Hit Queue, and Resolve Queue

### Neutral Structure

Keep load-residency structures:

- LIQ or LDQ for in-flight, cancelled, missed, and replaying loads.
- LHQ or ResolveQ for loads that returned data and must remain visible for ordering checks.
- Oldest-load selection for replay/repick.
- Wakeup from store data, SCB response, refill, and memory response.

### LinxCore Binding

LinxCore maps the neutral LIQ/LHQ shape as follows:

- `LoadInflightQueue` holds scalar replay rows and LHQ-style hit records.
- `LoadResolveQueue` retains resolved load records until older commit watermarks retire them.
- Resolve records participate in MDB conflict checks.
- Flush pruning uses precise `(STID,BID,LSID)` identity together with the
  BROB ring context that defines which younger block slots are killed.
- Load rows retain return signedness, destination, source trace sidecars, and memory request metadata.

### Model and Chisel Check

Model evidence:

- `MtcLDQInfo` owns clusters, pick entries, cross buffers, `MtcResolveQ`, missQ, and load-pending state.
- `MtcResolveQ::insert`, `retired`, `detect`, and `flush`.

Chisel owners:

- `LoadInflightQueue`
- `LoadInflightLaunchSelect`
- `LoadInflightRowMutationPath`
- `LoadResolveQueue`
- `ReducedLoadReplayLiqAllocPath`
- `ReducedLoadReplayRelaunchQueue`

Acceptance contract:

- Returned loads remain visible until no older store can invalidate them.
- Replay selection must use BROB ring age followed by LSID unless a future
  performance policy explicitly proves an equivalent order relation.

## 7 Load Forward Pipeline E2/E3/E4

### Neutral Structure

Keep the staged load pipe:

- E2: issue lookup, store/SCB candidate query, and cache/TLB-side request.
- E3: data-source return staging.
- E4: final merge, writeback, resolve, and wakeup.

### LinxCore Binding

LinxCore load pipeline:

- carries STID, BID, RID, LSID, and any required BROB ring-age/epoch sidecars
  through each stage;
- tracks source-return bits for load data, SCB, and STQ/store source;
- handles wait-store and replay publication;
- keeps speculative wakeup separate from final writeback;
- reports ROB/PE resolve only when payload identity is proven.

### Model and Chisel Check

Model evidence:

- `MtcLDQInfo::handleL1Lookup`
- `handleSCBReceive`
- `handleSTQReceive`
- `handleBypass`
- `handleMerge`
- `returnData`

Chisel owners:

- `LoadForwardPipeline`
- `LoadReplayReturnPipeW1Slot`
- `LoadReplayReturnPipeW2Slot`
- `LoadReplayReturnDataExtract`
- `LoadReplayReturnWritebackCandidate`
- `LoadReplayReturnWakeupCandidate`
- `LoadReplayReturnIexPipeInsertCandidate`

Acceptance contract:

- E4 is the earliest point where final merged data can be architectural for scalar load completion.
- Any replay-return path must prove ROB row identity before mutating RF, wakeup, ROB, or LIQ lifecycle state.

## 8 TLB, Memory Type, L1D, and Refill

### Neutral Structure

Keep the translation and cache hierarchy:

- load/store TLB lookup;
- memory-type classification;
- L1D tag/state/data lookup;
- miss request formation;
- refill response;
- DCache update path;
- fault reporting.

The neutral source describes concrete cache capacities and bank counts. LinxCore should preserve the structure, but the final capacity is an implementation parameter unless frozen elsewhere.

### LinxCore Binding

LinxCore requirements:

- TSO is the baseline memory model for scalar memory.
- Device/MMIO accesses are non-speculative and side-effect ordered.
- Instruction synchronization uses `FENCE.I`.
- Data/device synchronization uses `FENCE.D` and atomic `.aq/.rl/.aqrl` semantics.
- Bridged shader memory forms use explicit address domains and must not imply hidden lane indexing.

### Model and Chisel Check

Model evidence:

- `MtcLDQInfo` interfaces with L1/L2, SCB, prefetcher, and miss queues.
- `MemReqBus` carries memory request identity.

Chisel owners:

- `SCBLookupControl` abstracts DCache/L2 split for store drain.
- `LoadRefillWakeup` handles refill wakeup for unresolved scalar LIQ rows.
- Full L1D/TLB array owners remain future promotion points.

Acceptance contract:

- Capacity and banking can remain implementation-defined.
- Ordering, side effects, fault precision, and data-source merge priority are normative.

## 9 Store Coalescing Buffer

### Neutral Structure

Keep SCB as committed-store coalescing:

- accepts stores after commit eligibility;
- coalesces by cacheline;
- drains to DCache or lower memory;
- tracks outstanding request and response state;
- handles retry/response ordering;
- does not accept wrong-path stores.

### LinxCore Binding

LinxCore SCB rules:

- SCB rows are non-flushable.
- STQ rows are flushable until SCB acceptance.
- Coalescing key is physical cacheline.
- Do not merge into a row after it has issued a memory request and waits for response.
- Store is complete for fence-visible ordering only after WriteResp or equivalent completion.
- Response tag encoding uses `(entryIndex << 2) | 2` in the current contract.

### Model and Chisel Check

Model evidence:

- MTC store path and stats expose SCB lookup, miss, and stall behavior.
- `MtcSTQ::retire` and `commit` supply committed stores toward memory-side owners.

Chisel owners:

- `STQCommitQueue`
- `STQCommitDrain`
- `SCBCommitIngress`
- `SCBCommitBridge`
- `SCBEgressSelect`
- `SCBLookupControl`
- `SCBStateUpdate`
- `SCBRowBank`
- `STQSCBCommitPath`

Acceptance contract:

- SCB accepted-last-fragment is the only committed-row free source back to STQ in the promoted STQ-to-SCB composition.
- Request acceptance alone must not be reported as global store completion.

## 10 Response, Retry, and Refill Queues

### Neutral Structure

Keep explicit response handling:

- decode raw memory response tags;
- buffer responses that cannot update state immediately;
- retry responses in deterministic order;
- wake loads on refill;
- update store rows on WriteResp or UpgradeResp.

### LinxCore Binding

LinxCore response handling:

- Response row identity must match the original SCB or load request row.
- Retry order should match model `resp_list` priority where Chisel claims model parity.
- Load refill wakeup must identify unresolved LIQ rows by line, LSID, and valid row identity.

### Model and Chisel Check

Model evidence:

- `MtcLDQInfo` receives L1/L2/SCB wakeups and update paths.
- MTC LSU stats distinguish SCB lookup/miss/full and load miss counts.

Chisel owners:

- `SCBResponseDecode`
- `SCBResponseBuffer`
- `SCBResponseRetrySelect`
- `SCBResponseRetryQueue`
- `LoadRefillWakeup`
- `ResidentStoreReplayWakeup`

Acceptance contract:

- A response without a matching live row must be retained, retried, or diagnosed; it must not mutate an arbitrary row.

## 11 MDB and Store-Set Prediction

### Neutral Structure

Keep MDB:

- detect load/store ordering violations;
- record recurring conflict pairs;
- look up future load PCs;
- stall or wait loads predicted to conflict;
- delete or decay stale entries;
- publish recovery to ROB/flush owner.

### LinxCore Binding

LinxCore MDB rule:

- Conflict requires address overlap and `LessEqual(store.bid, store.lsID, load.bid, load.lsID)`.
- Same-BID conflict is an inner flush.
- Cross-BID conflict is a load-attributed nuke.
- Active LIQ and ResolveQ rows can both be conflict candidates.
- Tile load/store MDB participation is suppressed until a tile-specific owner defines its memory-order contract.

### Model and Chisel Check

Model evidence:

- `MtcMDB` owns `SSITEntry` with confidence, weight, store PC, bid offset, LSID offset, and nuke state.
- `MtcSTQ::mdbCheck` detects store-side wakeups and conflicts.

Chisel owners:

- `MDBConflictDetect`
- `MDBSSIT`
- `MDBQueueFanout`
- `MDBStoreProbeReplay`
- `LoadReplayMdbLookupWaitPlan`

Acceptance contract:

- MDB is a predictor and recovery aid; it cannot relax architectural memory ordering.
- A learned wait must be released by the matching store readiness, store deallocation, or flush cleanup.

## 12 Snoop, Nuke, and Recovery

### Neutral Structure

Keep load invalidation and nuke concepts:

- snoops can invalidate previously resolved load data;
- ordering violations are attributed to a specific load;
- recovery flushes younger speculative work;
- oldest offending load controls precise recovery.

### LinxCore Binding

LinxCore nuke behavior:

- A cross-BID load/store conflict marks the load for nuke.
- Recovery is taken when the nuke-marked load reaches the ROB head or equivalent precise point.
- Flush combines LSID sidecars with the selected BROB's `(STID, BID)` ring-age
  context; it must not compare wrapped BID values numerically.
- Block recovery must also clean BCTRL, BROB, rename, STQ, LIQ, ResolveQ, and local T/U owners.

### Model and Chisel Check

Model evidence:

- `FlushBus::match` supports block, group, PE, thread, and exact
  `(STID,BID,LSID)` matching; range pruning additionally requires BROB ring
  context rather than an unsigned BID range.
- `MtcResolveQ::detect` and `MtcLDQInfo::handleDetect` model resolved-load conflict handling.

Chisel owners:

- `LoadResolveQueue`
- `LoadInflightQueue`
- `MDBConflictDetect`
- `FlushControl`
- `FullBidRecoveryBridge`
- `STQFlushPrune`

Acceptance contract:

- Nuke is not a generic immediate flush for any conflict. The ROB/flush owner controls precise timing.

## 13 Atomics, Fences, and MMIO

### Neutral Structure

Keep serialized side-effect microflows:

- acquire/release ordering;
- atomic read-modify-write;
- non-speculative device accesses;
- fence request and acknowledgment;
- precise fault/trap reporting.

The ARM exclusive-monitor flows from the source are not directly portable. Preserve the general concept of a serialized, precisely completed atomic/fence path, not ARM-specific monitor behavior.

### LinxCore Binding

LinxISA binding:

- Atomics use Linx forms such as `swap*`, `cas*`, `lr.*`, `sc.*`, reductions, and `.aq/.rl/.aqrl` qualifiers where applicable.
- `FENCE.D` orders device/data visibility according to the profile.
- `FENCE.I` synchronizes instruction-side visibility.
- MMIO/device requests cannot be speculatively issued or replayed in a way that duplicates side effects.

### Model and Chisel Check

Model evidence:

- LinxISA manual chapters `09_memory_operations.adoc`, `14_amo.adoc`, and `10_system_and_privilege.adoc`.
- Current MTC LSU model exposes atomic queues between load and store units.

Chisel owners:

- Atomic replay-return W2 modules exist as dormant or guarded owners.
- Full scalar atomic/fence pipe remains a promotion point.

Acceptance contract:

- Atomic completion must prove both memory-side completion and ROB identity before architectural writeback.
- Fences cannot retire as globally complete on request issue alone.

## 14 TMA, Tile Memory, and Bridged Memory

### Neutral Structure

The neutral LSU contains vector/SVE and store-data width concepts. Preserve the broader principle: multi-lane memory engines need queues, descriptors, command tags, data return, and ordering handoff.

### LinxCore Binding

LinxCore replaces ARM vector memory with Linx block-fabric memory:

- TLOAD/TSTORE are command-driven by BSTART and descriptor rows.
- TMA consumes `B.DIM`, `B.ARG`, `B.IOR`, and `B.IOT` metadata.
- Tile memory effects participate in `engine_done`.
- BCC/MTC memory must preserve block completion, trap propagation, and memory ordering.
- Bridged shader memory uses explicit `l.*.brg` and `v.*.brg` forms.

### Model and Chisel Check

Model evidence:

- `BlockCommand.*`
- `BROB.*`
- `MtcLoadStoreUnit.*`
- `MtcLDQInfo.*`
- `MtcSTQ.*`

Chisel owners:

- `cmd_iq` and BCTRL/BISQ owners for command issue.
- LSU scalar owners do not yet define full tile memory execution.

Acceptance contract:

- Tile memory is not hidden scalar LSU traffic.
- Every tile-memory command must be attributable to a BID and command tag.
- Completion must feed BROB `engine_done` and trap paths.

## 15 Prefetch and Cache Maintenance

### Neutral Structure

Keep hints as optional performance side effects:

- prefetch may be dropped when legal;
- cache maintenance needs precise ordering and privilege checks;
- maintenance operations must not corrupt speculative state.

### LinxCore Binding

LinxISA forms:

- `PRF`/prefetch-like forms are hints unless a profile says otherwise.
- `DC.*`, `BC.*`, `TLB.*`, and `FENCE.*` forms are system or memory maintenance operations with SSR/ACR checks.
- Cache and TLB maintenance traps are precise.

### Model and Chisel Check

Model evidence:

- LinxISA manual generated encodings include `prf`, `dc_*`, `bc_*`, and `tlb_*` forms.
- `MtcPrefetcher`, `MtcStream`, and `MtcStride` model prefetch-related behavior.

Chisel owners:

- Full cache/TLB maintenance owners are future promotion points.
- Current scalar reduced path should treat unimplemented hints as NOP only when ISA/profile permits.

Acceptance contract:

- Hints may be performance-only.
- Maintenance and fences are architectural and must pass privilege, ordering, and trap checks.
