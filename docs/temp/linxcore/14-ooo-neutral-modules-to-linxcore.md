# OOO Neutral Modules Applied to LinxCore

This document applies the ISA-neutral out-of-order microarchitecture structures from the migrated upgrade corpus to LinxCore one module at a time. The rule is deliberate: preserve the proven OOO mechanism where it is not ISA-specific, then bind its fields, recovery rules, and execution interfaces to LinxISA `v0.56`.

The result is not an ARM-compatible core. It is a LinxCore microarchitecture that retains reusable OOO structures such as PC buffering, staged decode, rename maps, mapping queues, issue queues, ROB commit, and precise recovery.

## 1 Source Coverage

Primary neutral source sections:

- `docs/temp/upgrade/01-ooo-overview.md`
- `docs/temp/upgrade/02-ooo-decode.md`
- `docs/temp/upgrade/03-ooo-rename-dispatch.md`
- `docs/temp/upgrade/04-ooo-rob-exception-pc-smt-sve.md`
- `docs/temp/upgrade/05-iex-overview.md`
- `docs/temp/upgrade/06-iex-issue-execute.md`
- `docs/temp/upgrade/07-iex-special-flows.md`
- `docs/temp/upgrade/08-iex-control-safety-interfaces.md`

Cross-check sources:

- LinxISA manual: `/Users/zhoubot/linx-isa/docs/architecture/isa-manual/src/chapters/15_bbd.adoc`, `17_cmd.adoc`, and `10_system_and_privilege.adoc`
- LinxCore architecture: `docs/architecture/microarchitecture.md`, `linxisa_block_control_flow.md`, `block_private_rf.md`, `block_fabric_contract.md`, and `verification-matrix.md`
- LinxCoreModel: `model/bctrl/spe/GPRRename.*`, `SPEROB.*`, `DCTop.*`, `GenCoder.*`, `model/bctrl/BROB.*`, `model/pe/PECommon/PROBCommon.*`, and `model/ModelCommon/bus/FlushBus.h`
- Chisel docs: `docs/chisel/README.md`, `docs/chisel/module-index.md`, and module pages under `docs/chisel/modules`

## 2 Frontend Fetch, F4/IB, and D1 Ingress

### Neutral Structure

Keep the multi-stage frontend shape:

- PC select and fetch request generation.
- I-cache or fetch-memory lookup.
- Fetch response staging.
- Instruction assembly.
- Instruction buffer residency.
- Decode-window slicing.
- Restart/redirect input from recovery.

The neutral upgrade spec assumes a wide frontend that can supply several instructions per cycle and may stall when downstream resources are exhausted. That structure remains useful independent of ISA.

### LinxCore Binding

LinxCore binds this structure to variable-length Linx encodings:

- F4/IB retains dense fetch bytes and metadata as the final fetch-stage state.
- Decode must honor 16/32/48/64-bit instruction lengths.
- D1 may build a continuous decode view from ordered F4/IB bytes, but must not
  concatenate unrelated packet entries to manufacture an instruction.
- F4 body cuts must stop at block body boundaries and replay from legal block metadata.
- `BSTART`, `BSTOP`, compressed marker rows, and extended `HL.BSTART.*` are first-class frontend observations.

### Model and Chisel Check

Model evidence:

- `model/pe/ifu/iside/pe_ifu.*` contains legacy F4/F5/instruction-buffer
  implementation names. Canonically F4 and IB are one stage, and F5-like
  decode handoff behavior belongs to D1 ingress.
- `model/bctrl/spe/DCTop.*` consumes decoded block commands and assigns load/store identity sidecars.

Chisel owners:

- `FrontendFetchPacketSource`
- `FrontendInstructionBuffer`
- `F4DecodeWindow`
- `F4DenseSlotQueue`
- `FrontendDecodeIngress`
- `FrontendDecodeStage`
- reduced BFU body-cut modules

Acceptance contract:

- F4/IB and D1 ingress must preserve raw instruction bytes, PC, length, and
  ordered-entry identity.
- Any redirected fetch target must correspond to legal `BSTART` metadata or trap through the precise recovery path.
- Marker rows must not be silently skipped except in explicitly reduced trace-top modes that are documented as comparators, not final behavior.

## 3 PC Buffer and PC Metadata

### Neutral Structure

Keep the PC-buffer concept:

- Store a compact PC base instead of carrying full PCs through every uop.
- Carry per-uop PC offset or compact hash sidecars.
- Allocate PC entries at decode/rename.
- Release PC entries at ROB retire or when no younger consumer can read them.
- Support redirect repair after branch recovery and ROB flush.

The neutral source describes a PC base plus offset scheme and hashed PC metadata for load/store consumers. That is still the right power and area shape.

### LinxCore Binding

LinxCore must extend the PC metadata:

- Full `pc` is required for trace and model comparison.
- Compact PC metadata is allowed inside IQ and execution lanes when the full PC can be reconstructed.
- `BSTART` rows carry target and boundary-kind metadata.
- `SETC.TGT`, `FRET.RA`, `FRET.STK`, call materialization, and indirect targets must preserve target-owner identity.
- Load/store rows require enough PC information for MDB, replay diagnostics, and precise traps.

### Model and Chisel Check

Model evidence:

- `SimInstInfo` carries instruction PC and identity.
- `FlushBus` carries `fetchBpc`, `fetchTPC`, `bid`, `gid`, `rid`, and `lsID` recovery sidecars.

Chisel owners:

- `FrontendDecodeStage`
- `ReducedScalarAluExecute`
- `DecodeRenameROBPath`
- `ROBRowCommitTraceLookup`

Acceptance contract:

- The final spec should freeze behavior, not a fixed port count.
- Any PC-buffer banking is legal only if it preserves branch recovery, PC-relative instructions, load/store PC hashing, and commit trace reconstruction.

## 4 Decode, Uop Formation, and Grouping

### Neutral Structure

Keep the staged decode split:

- D1: early classification, boundary grouping, exception sidebands.
- D2: operand extraction, resource request shaping, split/fuse decisions.
- D3: resource admission, rename, dispatch preparation.
- S1/S2: dispatch-packet formation and IQ enqueue.

The neutral source has an all-or-nothing resource-admission rule for a decode group. Preserve it for deterministic ordering and simpler recovery.

### LinxCore Binding

LinxCore replaces ARM decode with generated Linx metadata:

- Opcode classification uses the Linx generated opcode catalog.
- Most-specific mask wins inside the active instruction-length domain.
- Source aliases map `0..23` to scalar `P`, `24..27` to `T`, and `28..31` to `U`.
- Destination aliases map `0..23` to scalar `P`, `31` to `T`, and `30` to `U`.
- Store split preserves Linx source roles for STA and STD.
- BBD marker rows are decoded as boundary and command metadata, not ordinary arithmetic uops.

### Model and Chisel Check

Model evidence:

- `model/bctrl/spe/Decoder.*`
- `model/bctrl/spe/GenCoder.*`
- `model/bctrl/spe/DCTop.*`

Chisel owners:

- `FrontendOperandDecode`
- `FrontendRegAliasClassify`
- `FrontendDecodeStage`
- `StoreSplitPayload`

Acceptance contract:

- Decode output must carry raw bits, length, PC, `bid`, `rid`, LSID snapshot, execution class, source roles, destination roles, and boundary metadata.
- Decode must not create ARM-only exception or system-register sidebands; traps and SSR/ACR checks follow LinxISA.

## 5 Resource Admission and Decode Stalls

### Neutral Structure

Keep the resource scoreboard:

- ROB entries.
- PC-buffer entries.
- MapQ/free-list capacity.
- IQ capacity.
- LSU IDs.
- Store queue and load replay capacity.
- Recovery busy state.

The neutral source explicitly treats some stalls as whole-group stalls rather than per-lane stalls. Preserve that for D3 rename and MapQ correctness.

### LinxCore Binding

LinxCore adds block-aware resources:

- BROB slot or active-BID allocation.
- BCTRL command/BISQ capacity.
- T/U local FIFO capacity.
- Marker lifecycle queue capacity.
- LSID allocation range.
- Template and engine command capacity.

### Model and Chisel Check

Model evidence:

- `GPRRename::CheckStall`
- `SPEROB::allocROB`
- `BlockROB::CheckRename`
- `DCTop::SetLoadStoreID`

Chisel owners:

- `ScalarDecodeRenameBridge`
- `DecodeRenameQueue`
- `DecodeRenameROBPath`
- `DispatchROBAllocator`
- `BlockMarkerLifecycle`

Acceptance contract:

- A group can advance past D3 only when all resources needed by admitted rows are available.
- Resource admission must be monotonic in program order within the group.
- If a reduced top bypasses a resource, the bypass must be documented as reduced-harness behavior.

## 6 Scalar Rename: SMAP, CMAP, Free List, and MapQ

### Neutral Structure

Keep the classic OOO rename package:

| Structure | Preserved role |
| --- | --- |
| SMAP | Latest speculative architectural-to-physical map. |
| CMAP | Committed architectural-to-physical map. |
| Free list | Pool of reusable physical destination tags. |
| MapQ | Ordered log of remaps for commit release and flush recovery. |

Neutral MapQ row fields:

- valid;
- owner STID/PE where needed;
- architectural destination tag;
- new physical tag;
- previous physical tag;
- `bid`;
- `rid`;
- decode-slot order;
- checkpoint identity;
- committed or retired state when commit and deallocation split.

### LinxCore Binding

Only scalar global `P` registers use this structure:

- `P` SMAP/CMAP cover 24 scalar architectural tags in the current scalar owner.
- Physical GGPR evidence is 128 entries.
- GPR MapQ evidence is 256 entries.
- Flush restore is instruction-precise by `rid` and block-aware by `bid`.
- Same-cycle writes to the same architectural tag must release intermediate physical tags in correct commit order.
- `T`, `U`, and `CARG` are excluded from scalar MapQ.

### Model and Chisel Check

Model evidence:

- `model/bctrl/spe/GPRRename.h` declares `freeList`, `smap`, `cmap`, `checkPoint`, `renamePtr`, `retirePtr`, `mapQ`, and `mapQFreeSize`.
- `GPRRename::SetCheckPoint`, `RetireBlock`, and `Flush` are the key behavior owners.

Chisel owners:

- `GPRRenameCheckpoint`
- `ScalarDecodeRenameBridge`
- `TULinkRelationCmap` for local relation cleanup, not scalar P MapQ.

Acceptance contract:

- Commit updates CMAP and releases overwritten committed tags.
- Flush restores SMAP from checkpoint or committed state, then replays surviving MapQ entries.
- Block-stop redirects must provide the cleanup BID expected by the scalar checkpoint owner.

## 7 T/U Local Rename and Relation Commit

### Neutral Structure

The neutral source has multiple register classes, each with rename and committed maps. Preserve the idea of separate state owners, but not the ARM classes.

### LinxCore Binding

LinxCore splits global and block-local rename:

- `T` and `U` are block-local FIFO/ClockHands-style resources.
- They carry relative architectural identities plus resolved local physical tags.
- They retire through relation-cmap and local block-commit fanout paths.
- Their lifetime is block-scoped and tied to `BSTOP`, template returns, and recovery cleanup.
- `CARG` is resolved by active `BID` and is not renamed.

### Model and Chisel Check

Model evidence:

- `SPEROB` owns `tcmap`, `ucmap`, local-register commit, and cleanup.
- `LocalRegMgr.*` owns block-local register management.

Chisel owners:

- `ScalarTURenameBridge`
- `TULinkRename`
- `TULinkLocalBankArray`
- `TULinkRetireCommandPath`
- `TULinkLocalBlockCommitFanout`
- `TULinkRecoveryCleanupPath`

Acceptance contract:

- T/U wakeup is local and point-to-point by queue tag, not global by scalar ptag.
- T/U state must be recoverable through block cleanup, not scalar MapQ replay.

## 8 Decode-Rename Queue and Dispatch Packet

### Neutral Structure

Keep the dispatch-packet form:

- decoded semantic class;
- renamed physical sources and destinations;
- readiness bits;
- ROB identity;
- PC metadata;
- exception/fault sidebands;
- IQ target;
- LSU identity when applicable.

### LinxCore Binding

LinxCore dispatch packets add:

- `BID_W`-bit `blockBid` plus separate STID and ring-age/epoch sidecars;
- model-shaped `bid/gid/rid`;
- all-row LSID snapshot;
- marker lifecycle sidebands;
- command tag sidebands;
- active block context;
- scalar/T/U destination kind;
- Linx trace row fields.

### Model and Chisel Check

Model evidence:

- `SimInstInfo`, `RenameBus`, `PEResolveBus`, and `MemReqBus`.

Chisel owners:

- `DecodeRenameQueue`
- `ScalarDecodeRenameBridge`
- `DecodeRenameROBPath`
- shared bundles under `docs/chisel/interfaces/CommonBundles.md`

Acceptance contract:

- Dispatch packet fields must remain stable even if physical queues are later merged or split.
- Trace names preserve architectural classes (`issq_alu`, `issq_bru`, `issq_agu`, `issq_std`, `issq_cmd`) while Chisel may use different physical queue names.

## 9 ROB, Commit, and Deallocation

### Neutral Structure

Keep ROB precision:

- allocate in program order;
- complete out of order;
- commit in order;
- deallocate only after all retirement side effects are safe;
- keep precise fault and flush metadata;
- emit commit trace in slot order.

The neutral upgrade source allows commit and deallocation to be separate. Preserve that separation because Linx block and local-register cleanup require it.

### LinxCore Binding

LinxCore ROB rows must carry:

- `BID_W`-bit `blockBid`, separate STID, and required ring-age/epoch sidecars;
- model `bid/gid/rid`;
- instruction PC, raw bits, and length;
- scalar/local destination sidecars;
- LSID and memory-order sidecars;
- marker retire metadata;
- exception/trap state;
- block-last and cleanup markers.

### Model and Chisel Check

Model evidence:

- `SPEROB::commit`, `dealloc`, `flush`, `CommitBlock`, and `CleanCMAP`.
- `PROBCommon.*` and `PROBStatus.h`.

Chisel owners:

- `ROBID`
- `ROBEntryStatus`
- `ROBEntryBank`
- `ROBFlushPrune`
- `ReducedCommitROB`
- `ROBRowStatusLookup`
- `ROBRowCommitTraceLookup`
- `CommitTraceMonitor`

Acceptance contract:

- Commit must be contiguous from the head.
- Need-flush rows remain precise until selected by the recovery owner.
- Deallocation must provide all scalar/T/U cleanup information before freeing the row.

## 10 BROB and Block Identity

### Neutral Structure

The neutral source has branch queue or BID-like branch identity for recovery and frontend communication. Preserve the idea of compact control-flow identity, but rebind it to Linx block identity.

### LinxCore Binding

BROB is a Linx-specific upgrade:

- BROB generates the complete `BID_W = ceil(log2(BROB_ENTRIES))` per-STID slot BID.
- BSTART carries the new BID.
- BSTART retire emits scalar_done for the old active block.
- BSTOP retire emits scalar_done for the current active block.
- Engine completion and scalar completion jointly define block completion.
- Flush uses BROB-derived ring-age/kill context; it never numerically compares
  wrapped BID values.
- Command tag equals BID and carries STID separately; the default 256-entry
  configuration happens to use an 8-bit tag.

### Model and Chisel Check

Model evidence:

- `model/bctrl/BROB.*`
- `model/ModelCommon/BlockCommand.*`
- `model/ModelCommon/ROBID.*`

Chisel owners:

- `BROB`
- `BlockMarkerLifecycle`
- `BlockMarkerRetireSourceSerializer`
- `BlockScalarDoneSequencer`
- `FullBidRecoveryBridge`

Acceptance contract:

- A BID must never be reused while any scalar row, engine command, or memory side effect can still report against it.
- Recovery must use `(STID, BID)` plus BROB ring-age/epoch context where
  architectural correctness depends on block order.

## 11 Issue Queues, Wakeup, RF Read, and Bypass

### Neutral Structure

Keep the standard IQ machinery:

- valid entries;
- source valid and source ready bits;
- enqueue-time ready-table sampling;
- oldest-ready pick;
- inflight lock once picked;
- RF read arbitration after pick;
- wakeup in later cycles, not same-cycle pick;
- bypass from producers to consumers;
- deallocation at a non-cancellable execution point.

### LinxCore Binding

LinxCore physical issue queues:

- `alu_iq0`
- `shared_iq1`
- `bru_iq`
- `agu_iq0`
- `agu_iq1`
- `std_iq0`
- `std_iq1`
- `cmd_iq`

Wakeup domains:

- Scalar `P` wakes by physical ptag.
- `T/U` wakes by point-to-point `qtag = (phys_issq_id, entry_id)`.
- Loads may speculatively wake at LD_E1 but data is confirmed at LD_E4.
- Speculative load readiness lives in IQ entry state, not the global ready table.

### Model and Chisel Check

Model evidence:

- `SPEROB::SetIsqId`, `SetIsqPicker`, `SetIssued`, and `PEResolve`.
- model queue-family configs define current executable-reference capacities.

Chisel owners:

- `ReducedScalarIssueQueue`
- `ReducedScalarIssuePick`
- `ReducedScalarRegisterFile`
- `ReducedScalarWritebackArbiter`
- `ReducedScalarAluExecute`

Acceptance contract:

- Ready table must not be corrupted by speculative load wakeup.
- Same-cycle producer wakeup cannot make a just-enqueued consumer pick in the same cycle unless a future timing spec explicitly permits it.
- Store-data issue reads sources but does not write a destination.

## 12 BRU, Boundary Redirect, and Dynamic Targets

### Neutral Structure

Keep branch execution as an out-of-order execution class with recovery metadata, but do not carry over ARM branch semantics.

### LinxCore Binding

Linx redirect is boundary-authoritative:

- BRU computes `SETC.*`, target values, and deferred correction.
- BRU does not directly redirect architectural PC.
- `BSTART/BSTOP` boundary commit consumes the chosen condition and target.
- Dynamic target forms must resolve to legal `BSTART` metadata.
- Invalid recovery targets take a precise trap.

### Model and Chisel Check

Model evidence:

- `linxisa_block_control_flow.md` records boundary-authoritative commit.
- QEMU mapping names are `linx_block_begin`, `linx_gen_block_end`, `trans_c_bstart_std`, `trans_c_bstop`, and `trans_setc_*`.

Chisel owners:

- `ReducedScalarAluExecute`
- `BlockMarkerLifecycle`
- `DecodeRenameROBPath`
- frontend BFU body-cut modules

Acceptance contract:

- The active block context at decode is separate from retire-time marker effects.
- A block-body `BSTART` must terminate the previous block and restart as a block head when required by split behavior.

## 13 Trap, Exception, and Recovery

### Neutral Structure

Keep precise exception handling:

- detect early faults when possible;
- mark ROB rows;
- take traps at retirement;
- flush younger speculative work;
- redirect fetch through a single recovery owner;
- preserve faulting PC and architectural context.

### LinxCore Binding

Linx traps use ACR/SSR state:

- `SERVICE_REQUEST` is the trap entry mechanism.
- `ECSTATE.BI` distinguishes header versus body trap context.
- `EBARG` stores current block PC, target block PC, and body resume PC.
- `BSTATE` and profile-defined block-local state are saved or restored as specified.
- `EBREAK` is a default breakpoint trap unless semihosting is explicitly enabled by the runtime profile.

### Model and Chisel Check

Model evidence:

- `FlushBus.h` carries `bid`, `gid`, `rid`, `lsID`, `fetchBpc`, `fetchTPC`, `tSeq`, `uSeq`, and `predSeq`.
- `SPEROB::flush` and `BlockROB::recoverBlock` own model recovery paths.

Chisel owners:

- `FlushControl`
- `RecoveryCleanupControl`
- `FullBidRecoveryBridge`
- `ROBFlushPrune`
- `STQFlushPrune`
- `GPRRenameCheckpoint`

Acceptance contract:

- A flush request must name its comparison domain: block, group, PE, thread, or LSID ordering.
- Recovery cleanup must reach frontend, ROB, rename, BCTRL, LSU, and local T/U owners.

## 14 Threading, STID, and Resource Partitioning

### Neutral Structure

The neutral source includes SMT concepts: per-thread commit arbitration, resource partitioning, thread-aware MapQ/ROB/PC buffer, and shared IQs.

### LinxCore Binding

LinxCore has STID/PE identity and can reuse the neutral partitioning idea:

- State rows must carry `stid` where multi-context ownership exists.
- BROB, rename, T/U local banks, LSU rows, and command queues must include the owner domain used by recovery.
- The first scalar RTL target may be single-STID, but the spec should avoid baking in single-thread-only field shapes.

### Model and Chisel Check

Model evidence:

- `FlushBus` includes `tid` and `stid`.
- `GPRRename` arrays are indexed by `stid`.
- `BROBState` is indexed by `stid`.

Chisel owners:

- shared bundles carry STID or owner sidecars where promoted.
- reduced tops often fix a single STID but keep typed sidecars.

Acceptance contract:

- A single-STID implementation may tie owner fields constant.
- It must not remove the field from architectural or model-visible structures where future multi-STID behavior depends on it.

## 15 Trace, Debug, and Verification Hooks

### Neutral Structure

Keep commit trace, issue trace, exception trace, and performance counters as first-class debug outputs. The neutral source’s PMU/debug details are not directly normative, but the observability principle is.

### LinxCore Binding

Required trace identity:

- PC and raw instruction.
- instruction length.
- `bid/gid/rid`.
- `BID_W`-bit `blockBid`, separate STID, and any required ring-age/epoch
  sidecars where relevant.
- LSID for memory rows.
- source and destination kind.
- commit slot and retirement order.
- trap and flush reason.
- marker and block-completion events.

### Model and Chisel Check

Model evidence:

- `CommitInfo.h`
- `PROBCommon.*`
- `BlockCommand.*`

Chisel owners:

- `CommitTrace`
- `CommitTraceMonitor`
- `LinxCoreFrontendFetchRfAluTraceTop`
- QEMU/generated-RTL comparator scripts documented under `docs/chisel/README.md`

Acceptance contract:

- Every new OOO module must state its trace-visible side effects.
- Reduced harness shortcuts must be labelled as reduced and cannot become silent architectural behavior.
