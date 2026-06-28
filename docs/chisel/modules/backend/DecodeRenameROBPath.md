# DecodeRenameROBPath

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/backend/DecodeRenameROBPath.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/backend/DecodeRenameROBPathSpec.scala`
- LinxCoreModel:
  - `model/LinxCoreModel/model/bctrl/spe/DCTop.cpp`
  - `model/LinxCoreModel/model/bctrl/spe/SPERename.cpp`
  - `model/LinxCoreModel/model/bctrl/spe/GPRRename.cpp`
  - `model/LinxCoreModel/model/bctrl/spe/SPEROB.cpp`
- Related Chisel:
  - `chisel/src/main/scala/linxcore/frontend/FrontendDecodeStage.scala`
  - `chisel/src/main/scala/linxcore/backend/DecodeLoadStoreIdAssign.scala`
  - `chisel/src/main/scala/linxcore/backend/DecodeRenameQueue.scala`
  - `chisel/src/main/scala/linxcore/rename/ScalarDecodeRenameBridge.scala`
  - `chisel/src/main/scala/linxcore/rename/ScalarTURenameBridge.scala`
  - `chisel/src/main/scala/linxcore/rename/TULinkRecoveryCleanupPath.scala`
  - `chisel/src/main/scala/linxcore/lsu/StoreDispatchQueues.scala`
  - `chisel/src/main/scala/linxcore/lsu/StoreDispatchSTQPath.scala`
  - `chisel/src/main/scala/linxcore/backend/DispatchROBAllocator.scala`

## Purpose

`DecodeRenameROBPath` is the first reduced frontend/backend composition point.
It connects `FrontendDecodeStage`, `DecodeRenameQueue`,
`ScalarTURenameBridge`, and `DispatchROBAllocator` so one decoded scalar/T/U
uop can pass through a registered D2/D3 queue, produce a renamed payload, and
allocate a real BROB/ROB row in the accepted rename cycle.

The module exists to remove the next layer of fixture wiring. It is still a
bring-up path, not the final dispatch pipe. It selects one decoded slot per
cycle, stamps reduced memory-order identity for scalar load/store rows at the
queue acceptance boundary, enqueues the decoded row into a registered
`dec_ren_q` owner, stamps temporary ROB identity from allocator cursors when
the queue head is presented to rename, and leaves enqueue-time ROB
reservation, full SID/LID carry, STA/STD execution, width-wide
rename, T/U relation-cmap retire/release, and full top-level fetch/commit
flow to later owners. It now owns the queue-backed store-dispatch to STQ
row-owner boundary through `StoreDispatchSTQPath`; STA address generation and
STD data selection remain explicit inputs until the real execution owners
exist. It also composes live T/U rename and recovery cleanup so the allocator's
ROB-side source candidate and the live STQ-bank LSU source candidate are
selected, cross-checked, and published by the same state owner that produces
`tSeq/uSeq` and T/U destination sidecars.

## Interface

Inputs:

- `d1`, `slots`, `validMask`, `flushValid`: D1/F4 decode inputs consumed by
  `FrontendDecodeStage`.
- `renamedOutReady`: downstream renamed-uop consumer readiness.
- `storeStaExec`, `storeStdExec`: explicit STA address and STD data execution
  results for the current store-dispatch queue heads.
- `storeMarkCommit*`, `storeCommitFree*`, `storeCommitFreeMask*`: reduced STQ
  commit-mark and committed-row free hooks.
- `checkpointValid/checkpointBid`, `commitValid/commitBid`, `cleanup`:
  pass-through control for the scalar GPR rename owner and ROB flush path.
- `completeValid/completeRobValue`: reduced ROB completion hook.
- `deallocReady`: reduced ROB deallocation hook.

Outputs:

- `decodedValidMask`, `invalidOpcodeMask`, `blockBoundaryMask`,
  `blockStopMask`, `loadMask`, `storeMask`: decode-stage sidebands.
- `selectedValid`, `selectedSlot`, `selectedRobValue`,
  `selectedBlockBid`: first-valid decoded slot and allocator cursor
  observability.
- `decodeReady`, `decRenPushReady`, `decRenPushFire`, `decRenPopFire`,
  `decRenValid`, `decRenHead`, `decRenTail`, `decRenCount`, `decRenEmpty`,
  `decRenFull`: registered decode-to-rename queue backpressure and occupancy
  observability.
- `selectedIsLoad`, `selectedIsStore`, `selectedMemoryValid`,
  `lsidAssignFire`, `selectedLsId`, `selectedLoadId`, `selectedStoreId`,
  `nextLsId`, `nextLoadId`, `nextStoreId`, `storeSplitIntent`: reduced
  memory-order ID and store-split-intent observability from
  `DecodeLoadStoreIdAssign`.
- `renamedOutValid/renamedOut`, `accepted`: renamed-uop output and atomic
  path accept event.
- `storeDispatchReady`, `storeDispatchFire`, `storeDispatchSplit`,
  `storeDispatchBlockedBySta`, `storeDispatchBlockedByStd`, `storeSta`,
  `storeStd`, `storeUnsplit`: reduced store-dispatch observability from the
  accepted renamed row. The payloads include live `tSeq/uSeq` and T/U
  destination sidecars from `ScalarTURenameBridge`.
- `storeStaQueueValid`, `storeStdQueueValid`, `storeStaQueue`,
  `storeStdQueue`, `storeStaEnqueueFire`, `storeStdEnqueueFire`,
  `storeStaDequeueFire`, `storeStdDequeueFire`,
  `storeDispatchInputProtocolError`, `storeStaQueueCount`,
  `storeStdQueueCount`, `storeStaQueueFull`, `storeStdQueueFull`: finite
  STA/STD dispatch queue observability from `StoreDispatchSTQPath`.
- `storeStaInsert*`, `storeStdInsert*`, `storeSelected*`,
  `storeBlockedBy*`, `storeStdBypassStaBlocked`, `storeStqInsert*`,
  `storeStqFlush*`, `storeStq*Mask`, `storeStq*Count`, `storeStqEmpty`,
  `storeStqFull`, and `storeStqStall`: live STQ path diagnostics from the
  integrated `StoreDispatchSTQPath`.
- `storeLsuTULinkSource*`: live STQ-bank LSU T/U cleanup source candidate and
  match diagnostics forwarded from `STQEntryBank`.
- `robAllocAttemptValid`, `robAllocReady`, `robAllocFire`,
  `robAllocBlockedBy*`, `robAllocDuplicateIdentity`: allocation handoff
  observability.
- `blockedBy*`, `blockedByTURename`, `unsupported*`: scalar/T/U rename bridge
  diagnostics.
- `tuRename*`: T/U rename readiness, accepted event, pre-allocation
  `tSeq/uSeq`, destination ownership, pressure, and source-underflow
  observability.
- `allocBlockBid`, `allocRobValue`, `commit*`, `dealloc*`, `flushApplied`,
  `robTULinkSource*`, `size`, `outstandingCount`, and occupancy masks:
  `DispatchROBAllocator` and `ROBEntryBank` lifecycle observability.
- `tuCleanupPublisherFlush*`, `tuCleanupSelectedFlushSource`,
  `tuCleanup*Source*`, `tuCleanupSourceConflict`,
  `tuCleanupSelectedFrom*`, and `tuCleanupFlush*PrevApplied`:
  live T/U cleanup diagnostics for ROB/LSU source selection, missing-source
  blocking, duplicate-source conflict, and selected flush sequence
  publication.

## Logic Design

The path decodes all F4 slots, then selects the lowest-index valid decoded slot
using `PriorityEncoder`. Later slots are not compacted or retried in this
module; a later width owner must provide full decode enqueue.

The selected decoded row first passes through `DecodeLoadStoreIdAssign`.
Memory-order counters advance only on `decRenPushFire`, so a stalled
decode/rename queue does not consume LSIDs or SID/LID serials. The annotated
row is written into `DecodeRenameQueue` when `decRenPushFire` is true. The
queue and the memory-order counters are flushed by direct frontend flush input
or by backend cleanup intent. `decodeReady` mirrors the queue push-ready signal
so a future frontend integration can advance D1/F4 only when the selected row
is accepted. The generated opcode metadata drives the reduced path's
load/store-pair, PCR-store, and cache-maintain split sidebands. DCZVA remains a
deferred explicit classification input.

When a row is visible at the queue head, the module stamps the missing backend
identity that `DCTop::Work()` and `SPEROB::allocROB()` supply in the C++ model:

- `bid` comes from the current generated block BID converted to ring `ROBID`;
- `rid.value` comes from the ROB allocation pointer exposed as
  `allocRobValue`;
- `gid` is held at zero in the reduced scalar path;
- `blockBidValid/blockBid` mirrors the generated full hardware BID.

`ScalarTURenameBridge.robAllocAttemptValid` drives
`DispatchROBAllocator.allocValid`. This signal is intentionally independent of
allocator ready. The bridge only reports `accepted`, pops `DecodeRenameQueue`,
and mutates GPR plus T/U rename state when `allocReady` returns true, but the
allocator duplicate-identity check sees a stable request row before it
computes ready. This avoids a ready/valid combinational feedback path through
ROB duplicate detection.

For store rows at the queue head, the path computes reduced store-dispatch
readiness before rename accepts the row. Unsplit stores require only STA
readiness; split stores require both STA and STD readiness so the model STA/STD
pair cannot partially fire. This readiness is computed from the queued decoded
row, not from `StoreSplitPayload.inReady`, avoiding a combinational loop
through the accepted renamed output. `StoreSplitPayload` then consumes the
accepted renamed row and emits the observed STA, STD, or ST_ALL payloads.
R62 drives the explicit T/U local-register sidecar inputs on
`StoreSplitPayload` from `ScalarTURenameBridge`. `tSeq/uSeq` are the
pre-allocation snapshots captured before T/U destination rename, and
`tuDst*` identifies whether the accepted row owns a T or U destination.

`StoreDispatchSTQPath` consumes those payloads and owns finite STA/STD FIFO
admission plus the first live STQ row mutation boundary in the reduced backend.
Its queue readiness is capacity-only and flush-qualified; it does not depend on
the payload valid bits coming back from `StoreSplitPayload`. Split stores
enqueue both STA and STD payloads atomically, unsplit stores enqueue only a
STA-side `ST_ALL` payload, and protocol-shape errors are exposed as diagnostics
that block enqueue. Queue heads leave the queue only when the corresponding
explicit execution result is valid and the live STQ insert probe accepts that
candidate. A backend recovery flush is passed to the STQ bank; direct frontend
or decode maintenance flush uses `StoreDispatchSTQPath.queueFlushValid` so it
clears only the dispatch queues and does not over-prune STQ rows.

The allocator still owns BID generation, BROB metadata allocation, ROB row
allocation, completion, deallocation, commit monitoring, and ROB flush pruning.
The composition ties block scalar/engine completion and block retire inputs
inactive because full block-control retirement is not part of this owner.

The composition also drives the allocator's R56 T/U sidecar inputs. `allocStid`
still comes from the queued decoded row's thread ID in the reduced path, while
`allocTSeq`, `allocUSeq`, and T/U destination ownership now come from
`ScalarTURenameBridge`. This gives the ROB row the same sequence snapshot that
the store-dispatch payload carries.

The composition forwards `DispatchROBAllocator.robTULinkSource*` to the module
IO and feeds the same source into `ScalarTURenameBridge.robSource`. The bridge
feeds `StoreDispatchSTQPath.lsuTULinkSource` into its LSU source input. For
non-base cleanup intents, the emitted diagnostics prove whether the ROB and
LSU candidates agree for the same `(bid,rid,stid)`, whether exactly one owner
supplied a matching source, or whether cleanup was blocked by a missing or
conflicting source. Unlike the earlier diagnostic-only path, the selected
publisher output now drives the live T/U rename cleanup path inside the same
composition owner.

## Model Alignment

The C++ model order being preserved is:

1. `DCTop::Work()` selects one instruction, assigns `rid` from the PE ROB
   allocation pointer, calls `SPEROB::allocROB()`, assigns LSID/SID where
   applicable, then writes the instruction to `dec_ren_q`.
2. `SPEROB::allocROB()` stores an allocated row, copies `bid/gid/rid`, and
   advances `allocPtr`, `size`, and `osdSize`.
3. `Decoder::DecodeInst()` and `DCTop::SetLoadStoreID()` assign memory-order
   IDs before incrementing their counters. The Chisel reduced path now matches
   that pre-increment rule for one accepted STID0 decoded memory row.
4. `SPE::Xfer()` calls `dec_ren_q.Work()` to move ready written rows into the
   rename-visible queue image.
5. `SPERename::Rename()` consumes `dec_ren_q`, maps scalar and T/U sources,
   snapshots `inst->tSeq/uSeq` before T/U destination rename, allocates
   scalar and T/U destinations, captures checkpoints for `isLastInBlock`, and
   forwards the renamed uop toward dispatch.
6. `GPRRename` owns scalar `smap`, `cmap`, checkpoints, free tags, and mapQ;
   T/U `LocalRegMgr` instances own local link sequence, offset lookup,
   allocation pressure, and cleanup pruning.
7. Store-unit deadlock cleanup builders preserve row-owned T/U sequence and
   destination sidecars and must agree with ROB-owned source evidence for the
   same selected `(bid,rid,stid)`.
8. `SPERename::InsertToStoreIEX()` emits either an atomic STA/STD pair or a
   single ST_ALL store payload behind rename, with PCR-source and pair/cache
   suppression semantics.

`DecodeRenameROBPath` now preserves the registered `dec_ren_q` timing point,
and it assigns reduced memory-order identity before queue enqueue. It still
reduces the model in one important way: the allocator cursor identity is
stamped at the queue head. This avoids duplicate cursor reservations while the
path lacks an enqueue-time ROB reservation owner. Full model timing requires
moving ROB reservation before enqueue and carrying full `load_id`/`sid`
payloads into LIQ/STQ owners. It exposes the ROB T/U source candidate through
the allocator boundary and composes the live STQ-bank LSU candidate through
`ScalarTURenameBridge` and its `TULinkRecoveryCleanupPath`. R62 now wires live
`SPERename::Rename()`-equivalent `tSeq/uSeq` snapshots and T/U destination
ownership into both `StoreSplitPayload` and `DispatchROBAllocator`.
Full store timing still requires real STA/STD execution, load-conflict
publication, SCB/MDB handoff, and memory trace side effects.

## Deferred Owners

- Width-wide selection and rename for multiple decoded uops per cycle.
- Enqueue-time ROB reservation from full block/decode context rather than
  reduced queue-head allocator cursor stamping.
- BID-scoped `dec_ren_q` flush pruning rather than coarse queue clear.
- Top-level frontend backpressure wiring that consumes `decodeReady`.
- Width-wide slot-order LSID/SID allocation and same-cycle memory ordering.
- Full `load_id`/`sid` payload carry into LIQ/STQ owners.
- Real STA/STD execution owners that drive `storeStaExec` and `storeStdExec`.
- Automatic checkpoint capture from validated `isLastInBlock`.
- T/U relation-cmap retire/deallocation producer for the live `tuRetire*`
  bridge inputs.
- SGPR/tile/vector operand classification and rename.
- Old T/U physical tag release accounting for destination overwrite.
- SCB/MDB integration, committed-store memory drain, and load-conflict
  publication behind accepted STQ inserts.
- Ready-table initialization, issue enqueue, execution completion, and full
  commit side effects.
- Full QEMU-vs-DUT compare with live architectural commit rows.

## Verification

Focused gate:

```bash
bash tools/chisel/run_chisel_tests.sh --only DecodeRenameROBPath
bash tools/chisel/run_chisel_tests.sh --only DecodeLoadStoreIdAssign
bash tools/chisel/run_chisel_tests.sh --only DecodeRenameQueue
bash tools/chisel/run_chisel_tests.sh --only ScalarTURenameBridge
bash tools/chisel/run_chisel_tests.sh --only StoreSplitPayload
bash tools/chisel/run_chisel_tests.sh --only StoreDispatchSTQPath
bash tools/chisel/run_chisel_tests.sh --only TULinkRecoveryCleanupPath
```

Affected gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only ScalarDecodeRenameBridge
bash tools/chisel/run_chisel_tests.sh --only FrontendDecodeStage
bash tools/chisel/run_chisel_tests.sh --only DispatchROBAllocator
bash tools/chisel/run_chisel_tests.sh --only STQEntryBank
bash tools/chisel/run_chisel_tests.sh --only StoreDispatchToSTQ
bash tools/chisel/run_chisel_tests.sh --only GPRRenameCheckpoint
bash tools/chisel/run_chisel_rob_bookkeeping.sh --reduced-rob
bash tools/chisel/run_chisel_top_xcheck.sh
bash tools/chisel/run_chisel_qemu_crosscheck.sh --dry-run
```

The current tests cover first-valid slot selection, queue admission
backpressure, the reduced memory-order ID observability, the store dispatch
STA/STD readiness rule, the allocation attempt contract, ROB/LSU T/U cleanup
source agreement and conflict reference behavior, IO shape, and CIRCT
elaboration through frontend decode, `DecodeLoadStoreIdAssign`,
`DecodeRenameQueue`, scalar/T/U rename, `StoreSplitPayload`,
`StoreDispatchSTQPath` with `STQEntryBank`, backend allocation, and
`TULinkRecoveryCleanupPath`.
