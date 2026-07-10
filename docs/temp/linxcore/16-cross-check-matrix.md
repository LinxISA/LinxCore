# LinxCore OOO and LSU Cross-Check Matrix

This document cross-checks the upgraded LinxCore OOO and LSU spec against three authorities:

- LinxISA architectural contract.
- LinxCoreModel executable-reference structure.
- Chisel RTL documentation and current module owners.

The matrix separates evidence from specification. A model file proves how the current executable reference behaves; it does not automatically freeze the final RTL contract unless the LinxCore spec row says so.

## 1 Authority Order

Use this order when rows disagree:

1. LinxISA manual and machine-readable ISA contracts define architectural behavior.
2. LinxCore architecture docs define microarchitectural intent for this RTL lane.
3. LinxCoreModel defines executable-reference behavior and sizing evidence.
4. Chisel docs define current RTL owner boundaries and implemented/dormant status.
5. Migrated neutral upgrade material supplies reusable structures only where it does not conflict with the above.

## 2 OOO Matrix

| Topic | Linx Spec Contract | LinxCoreModel Evidence | Chisel Owner | Resolution |
| --- | --- | --- | --- | --- |
| Variable-length fetch/decode | Linx encodings are 16/32/48/64 bit; block markers are first-class. | `pe_ifu.*`, `Decoder.*`, `GenCoder.*` consume model instruction stream. | `F4DecodeWindow`, `F4DenseSlotQueue`, `FrontendDecodeStage`. | Preserve neutral F4/IB pipeline, bind to Linx length and marker metadata. |
| PC metadata | PC is needed for trace, PC-relative ops, target validation, MDB, and traps. | `SimInstInfo`, `FlushBus.fetchBpc/fetchTPC`. | Frontend modules, `ReducedScalarAluExecute`, `CommitTrace`. | Preserve PC-buffer idea; exact banking remains implementation detail. |
| BSTART/BSTOP boundaries | Boundary-authoritative redirect; `BSTART` opens/terminates blocks; `BSTOP` commits block state. | `BlockCommand.*`, `BROB.*`, `DCTop.*`. | `BlockMarkerLifecycle`, `BlockScalarDoneSequencer`, `BROB`. | Replace ARM branch-group semantics with Linx BID/BROB lifecycle. |
| Dynamic target legality | Recovery target must resolve to legal `BSTART`; invalid target traps precisely. | QEMU parity notes in `linxisa_block_control_flow.md`; model flush sidecars in `FlushBus`. | frontend BFU body-cut modules, `FullBidRecoveryBridge`. | BRU computes correction, boundary commit chooses architectural redirect. |
| Scalar rename | `P` registers use map-based rename; T/U/CARG do not. | `GPRRename` owns `smap`, `cmap`, `freeList`, `checkPoint`, `mapQ`. | `GPRRenameCheckpoint`, `ScalarDecodeRenameBridge`. | Preserve SMAP/CMAP/MapQ/free-list and bind only to scalar `P`. |
| MapQ recovery | Instruction-precise scalar recovery with block-aware sidecars. | `GPRRename::Flush`, `SetCheckPoint`, `RetireBlock`. | `GPRRenameCheckpoint`. | Recovery uses `bid/rid` and correct block-stop cleanup BID. |
| T/U local state | Block-private T/U lifetime and cleanup through block boundaries. | `SPEROB` relation CMAPs, `LocalRegMgr`. | `TULink*`, `ScalarTURenameBridge`. | Do not merge T/U into scalar MapQ. |
| CARG | Block condition/argument state is keyed by active BID. | `BlockCommand` and BCTRL state. | `BlockMarkerLifecycle`, `ReducedScalarAluExecute`. | CARG is materialized block state, not a renamed register. |
| ROB precision | Allocate in order, complete out of order, commit in order, deallocate after cleanup. | `SPEROB::commit/dealloc/flush`, `PROBCommon`. | `ROBEntryBank`, `ROBFlushPrune`, `ReducedCommitROB`. | Preserve neutral ROB behavior with Linx block/local sidecars. |
| BID/BROB identity | BROB owns the `BID_W=ceil(log2(BROB_ENTRIES))` slot BID, with separate STID and ring-age/epoch state. | `BROB.*`, `ROBID.*`. | `BROB`, `FullBidRecoveryBridge`. | Use `(STID,BID)` plus BROB ring-age/kill context where correctness depends on block order. |
| Issue queues | IQ entries carry valid/source-ready/inflight/replay state; oldest-ready pick. | `SPEROB` issue/resolve side effects; model IQ configs. | `ReducedScalarIssueQueue`, `ReducedScalarIssuePick`. | Preserve neutral IQ machinery with Linx queue-kind names and P/T/U wakeup domains. |
| Load speculative wakeup | Speculative load readiness must not corrupt global ready table. | Model load return and replay queues. | `ReducedScalarIssueQueue`, `LoadReplayWakeup`. | Store speculative readiness in IQ entry, not global ready table. |
| Trap entry | `SERVICE_REQUEST`, ACR/SSR, EBARG/EBSTATE/BSTATE, and `ECSTATE.BI` define architectural trap state. | `FlushBus` carries recovery sidecars; `SPEROB` and `BROB` flush paths. | `FlushControl`, `RecoveryCleanupControl`. | Replace ARM exception-level behavior with Linx ACR/SSR trap envelope. |
| Thread/STID | Owner domains must be explicit where recovery and resource ownership require them. | `FlushBus.tid/stid`, model arrays indexed by STID. | Shared bundles and reduced top sidecars. | Single-STID RTL may tie fields constant, not remove them from contracts. |

## 3 LSU Matrix

| Topic | Linx Spec Contract | LinxCoreModel Evidence | Chisel Owner | Resolution |
| --- | --- | --- | --- | --- |
| LSID allocation | Dispatch allocates monotonic memory-order identity; flush rebases dropped IDs. | `DCTop::SetLoadStoreID`, `GenCoder` LSID assignment, `FlushBus.lsID/sid/ldid`. | `DecodeLoadStoreIdAssign`, `DecodeRenameROBPath`, `ROBEntryBank`. | Preserve neutral load/store identity, bind to Linx LSID. |
| Strict LSU issue | Strict profile issues memory when row LSID reaches issue pointer. | `lsid_memory_ordering.md`; model memory sidecars. | `LoadInflightQueue`, reduced LSU wrappers. | Ordered profile is baseline; speculation requires explicit promotion. |
| Store split | STA/STD share identity and merge in STQ. | `MtcSTQ` row carries `bid`, `rid`, `addr`, `data`, readiness, and FSM. | `StoreDispatchQueues`, `StoreDispatchToSTQ`, `STQEntryBank`. | Preserve neutral store split; bind operands to Linx source roles. |
| STQ speculation | STQ rows are flushable until commit/drain. | `MtcSTQ::flush`, `retire`, `commit`. | `STQFlushPrune`, `STQEntryBank`. | STQ is speculative; SCB is non-flushable. |
| Store forwarding | Nearest older byte source wins; partial coverage waits or merges. | `MtcSTQ::lookupForLoad`, `getData`; memory docs list STQ > SCB > DCache priority. | `LoadStoreForwarding`, `LoadForwardPipeline`. | Preserve byte-mask forwarding with BROB ring age across blocks and LSID within a block. |
| LIQ/LHQ | In-flight and resolved loads remain visible for replay and conflict checks. | `MtcLDQInfo`, `MtcResolveQ`. | `LoadInflightQueue`, `LoadResolveQueue`. | Map neutral LIQ/LHQ to LIQ plus ResolveQ/LHQ records. |
| E2/E3/E4 load pipe | Lookup, source return, merge, writeback, resolve. | `MtcLDQInfo::lookup`, `receiveData`, `handleMerge`, `returnData`. | `LoadForwardPipeline`, replay-return W1/W2 modules. | Preserve staged load pipe and identity checks before side effects. |
| Cache/TLB | Translation, memory type, cache hit/refill, precise faults. | `MtcLDQInfo` interfaces with L1/L2/SCB/prefetch. | `SCBLookupControl`, `LoadRefillWakeup`; full arrays future. | Freeze ordering/fault/data priority now; capacity and banking remain implementation parameters. |
| SCB | Committed, non-flushable store coalescing by physical cacheline. | Store-unit and SCB stats; committed store drain path. | `SCBCommitIngress`, `SCBRowBank`, `STQSCBCommitPath`. | Request issue is not completion; WriteResp or equivalent completion is needed. |
| Response retry | Memory responses decode, buffer, and retry by row identity. | MTC load/store response queues and stats. | `SCBResponseDecode`, `SCBResponseBuffer`, `SCBResponseRetryQueue`. | A response must match a live row before mutation. |
| MDB | Conflict predictor records load/store pairs and wakes or stalls future loads. | `MtcMDB` SSIT, `MtcSTQ::mdbCheck`. | `MDBConflictDetect`, `MDBSSIT`, `MDBQueueFanout`. | Predictor cannot relax architecture; conflicts use overlap plus BROB ring age followed by LSID. |
| Nuke recovery | Cross-BID conflicts become load-attributed nukes; recovery is precise. | `FlushBus::match`, `MtcResolveQ::detect`. | `LoadResolveQueue`, `FlushControl`, `FullBidRecoveryBridge`. | Nuke timing belongs to ROB/flush owner, not arbitrary LSU immediate flush. |
| Atomics and fences | Linx `.aq/.rl/.aqrl`, `FENCE.D`, `FENCE.I`, and MMIO rules replace ARM-exclusive behavior. | ISA manual plus MTC atomic queues. | Dormant/guarded atomic replay modules; full owner future. | Preserve serialized precise flow, not ARM monitor details. |
| Tile memory | TLOAD/TSTORE are block-fabric commands with BID/command-tag completion. | `BlockCommand`, `BROB`, `MtcLoadStoreUnit`. | BCTRL/CMD owners; full tile LSU future. | Tile memory is command-driven, not hidden scalar LSU traffic. |

## 4 Chisel Owner Status

Current promoted or unit-green owner categories from `docs/chisel/README.md` and `docs/chisel/module-index.md`:

| Area | Current owner status |
| --- | --- |
| Frontend/F4/IB/D1 | Unit-green through legacy D1-ingress window helpers, dense slot queue, decode ingress, generated opcode decode, and operand classification. |
| Scalar rename | `GPRRenameCheckpoint` and scalar bridge are promoted for scalar P rename; full T/U and multi-thread details remain split across TULink owners. |
| ROB/BROB | ROBID, ROB entry/status/flush-prune, reduced commit ROB, BROB metadata, and block marker lifecycle are unit-green or integrated in reduced tops. |
| Scalar execute/IQ | Reduced scalar IQ, RF, ALU execute, and writeback arbiter are unit-green for the supported scalar subset. |
| STQ/SCB | STQ insert/state/flush, store commit queue/drain, SCB ingress/egress/lookup/state/response, and STQ-to-SCB path are unit-green or top-green in reduced form. |
| Load replay | LIQ/LHQ row owner, forwarding, refill wakeup, replay return, ResolveQ, and replay-LIQ modules exist with many live-disabled or diagnostic guards. |
| MDB | Conflict detect, SSIT, queue fanout, store probe replay, and lookup wait-plan exist, with natural-workload proof still an evidence gap in the current Chisel handoff. |
| Full cache/TLB/fence/atomic/tile memory | Not fully promoted as final RTL owners; current docs preserve contracts and identify future owner packets. |

## 5 Gate Mapping

Documentation changes do not execute RTL gates by themselves, but the spec rows map to existing gates:

| Contract | Gate family |
| --- | --- |
| Frontend/F4/IB/D1 | `bash tools/chisel/run_chisel_tests.sh --only F4DecodeWindow`, `FrontendDecodeIngress`, `FrontendDecodeStage` (the first name is a legacy D1-ingress helper) |
| Scalar rename | `bash tools/chisel/run_chisel_tests.sh --only ScalarDecodeRenameBridge`, `GPRRenameCheckpoint` |
| Decode/ROB/BROB path | `bash tools/chisel/run_chisel_tests.sh --only DecodeRenameROBPath`, `DispatchROBAllocator`, `BROB` |
| ROB commit/flush | `bash tools/chisel/run_chisel_tests.sh --only ROBEntryBank`, `ROBFlushPrune`, `ReducedCommitROB` |
| STQ and store drain | `bash tools/chisel/run_chisel_tests.sh --only STQEntryBank`, `STQCommitQueue`, `STQCommitDrain`, `StoreDispatchSTQPath` |
| SCB | `bash tools/chisel/run_chisel_tests.sh --only SCBCommitIngress`, `SCBCommitBridge`, `SCBRowBank`, `SCBResponseRetryQueue` |
| Forwarding and load replay | `bash tools/chisel/run_chisel_tests.sh --only LoadForwardPipeline`, `LoadInflightQueue`, `LoadResolveQueue` |
| MDB | `bash tools/chisel/run_chisel_tests.sh --only MDBConflictDetect`, `MDBSSIT`, `MDBQueueFanout` |
| Integrated reduced top | Generated-RTL/QEMU xcheck scripts under `tools/chisel`, selected by the feature under test. |
| Model comparison | Build `gfsim` and compare only after QEMU architectural preflight passes for the workload. |

## 6 Alignment Decisions

| Neutral feature | LinxCore decision |
| --- | --- |
| ARM architectural register classes | Replaced by `P`, `T`, `U`, `CARG`, tile/vector/block-fabric state. |
| SMAP/CMAP/MapQ | Preserved for scalar `P` only. |
| ARM PC buffer | Preserved as compact PC metadata; extended for block markers, target validation, trace, and MDB. |
| ARM branch queue/BID | Replaced by BROB `(STID,BID)` slot identity, separate ring age/epoch state, and boundary-authoritative commit. |
| ARM DMB/DSB/acquire/release | Replaced by Linx TSO, `FENCE.D`, `FENCE.I`, and atomic qualifiers. |
| ARM exclusives | Replaced by Linx atomic/load-reserved/store-conditional and serialized precise memory side effects. |
| SVE/vector LSU | Replaced by Linx block-fabric TMA/vector/tile memory command paths. |
| SMT partitioning | Preserved as an owner-domain pattern; first scalar targets may tie STID constant. |

## 7 Review Points Before Freezing RTL

- Freeze whether the first public LinxCore spec names PC-buffer bank count and ports or only the behavioral contract.
- Decide whether scalar atomics use the ordinary load/store path with serialization or a dedicated atomic micro-pipeline.
- Promote full L1D/TLB array ownership when cache capacity and MMU profile are stable.
- Promote TMA/TLOAD/TSTORE memory-order details once the block-fabric engine completion contract is fully tied to MTC memory.
- Decide which replay-LIQ counters become mandatory generated-RTL proof for natural workloads rather than focused fixtures.
