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
  - `chisel/src/main/scala/linxcore/lsu/StoreDispatchQueues.scala`
  - `chisel/src/main/scala/linxcore/backend/DispatchROBAllocator.scala`

## Purpose

`DecodeRenameROBPath` is the first reduced frontend/backend composition point.
It connects `FrontendDecodeStage`, `DecodeRenameQueue`,
`ScalarDecodeRenameBridge`, and `DispatchROBAllocator` so one decoded scalar
uop can pass through a registered D2/D3 queue, produce a renamed payload, and
allocate a real BROB/ROB row in the accepted rename cycle.

The module exists to remove the next layer of fixture wiring. It is still a
bring-up path, not the final dispatch pipe. It selects one decoded slot per
cycle, stamps reduced memory-order identity for scalar load/store rows at the
queue acceptance boundary, enqueues the decoded row into a registered
`dec_ren_q` owner, stamps temporary ROB identity from allocator cursors when
the queue head is presented to rename, and leaves enqueue-time ROB
reservation, full SID/LID carry, STA/STD execution, STQ mutation, width-wide
rename, and full top-level fetch/commit flow to later owners. It now owns a
finite queue-backed store-dispatch boundary through `StoreDispatchQueues`, but
STA/STD execution and STQ insertion remain later LSU owners.

## Interface

Inputs:

- `d1`, `slots`, `validMask`, `flushValid`: D1/F4 decode inputs consumed by
  `FrontendDecodeStage`.
- `renamedOutReady`: downstream renamed-uop consumer readiness.
- `storeStaDequeueReady`, `storeStdDequeueReady`: execution-side readiness for
  the current STA and STD queue heads.
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
  accepted renamed row.
- `storeStaQueueValid`, `storeStdQueueValid`, `storeStaQueue`,
  `storeStdQueue`, `storeStaEnqueueFire`, `storeStdEnqueueFire`,
  `storeStaDequeueFire`, `storeStdDequeueFire`,
  `storeDispatchInputProtocolError`, `storeStaQueueCount`,
  `storeStdQueueCount`, `storeStaQueueFull`, `storeStdQueueFull`: finite
  STA/STD dispatch queue observability from `StoreDispatchQueues`.
- `robAllocAttemptValid`, `robAllocReady`, `robAllocFire`,
  `robAllocBlockedBy*`, `robAllocDuplicateIdentity`: allocation handoff
  observability.
- `blockedBy*`, `unsupported*`: scalar rename bridge diagnostics.
- `allocBlockBid`, `allocRobValue`, `commit*`, `dealloc*`, `flushApplied`,
  `size`, `outstandingCount`, and occupancy masks: `DispatchROBAllocator` and
  `ROBEntryBank` lifecycle observability.

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

`ScalarDecodeRenameBridge.robAllocAttemptValid` drives
`DispatchROBAllocator.allocValid`. This signal is intentionally independent of
allocator ready. The bridge only reports `accepted`, pops `DecodeRenameQueue`,
and mutates GPR rename state when `allocReady` returns true, but the allocator
duplicate-identity check sees a stable request row before it computes ready.
This avoids a ready/valid combinational feedback path through ROB duplicate
detection.

For store rows at the queue head, the path computes reduced store-dispatch
readiness before rename accepts the row. Unsplit stores require only STA
readiness; split stores require both STA and STD readiness so the model STA/STD
pair cannot partially fire. This readiness is computed from the queued decoded
row, not from `StoreSplitPayload.inReady`, avoiding a combinational loop
through the accepted renamed output. `StoreSplitPayload` then consumes the
accepted renamed row and emits the observed STA, STD, or ST_ALL payloads.

`StoreDispatchQueues` consumes those payloads and owns finite STA/STD FIFO
admission. Its readiness is capacity-only and flush-qualified; it does not
depend on the payload valid bits coming back from `StoreSplitPayload`. Split
stores enqueue both STA and STD payloads atomically, unsplit stores enqueue
only a STA-side `ST_ALL` payload, and protocol-shape errors are exposed as
diagnostics that block enqueue. Queue heads are surfaced for the future
STA/STD execution owners.

The allocator still owns BID generation, BROB metadata allocation, ROB row
allocation, completion, deallocation, commit monitoring, and ROB flush pruning.
The composition ties block scalar/engine completion and block retire inputs
inactive because full block-control retirement is not part of this owner.

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
5. `SPERename::Rename()` consumes `dec_ren_q`, maps scalar sources, allocates
   scalar destinations, captures checkpoints for `isLastInBlock`, and forwards
   the renamed uop toward dispatch.
6. `GPRRename` owns scalar `smap`, `cmap`, checkpoints, free tags, and mapQ.
7. `SPERename::InsertToStoreIEX()` emits either an atomic STA/STD pair or a
   single ST_ALL store payload behind rename, with PCR-source and pair/cache
   suppression semantics.

`DecodeRenameROBPath` now preserves the registered `dec_ren_q` timing point,
and it assigns reduced memory-order identity before queue enqueue. It still
reduces the model in one important way: the allocator cursor identity is
stamped at the queue head. This avoids duplicate cursor reservations while the
path lacks an enqueue-time ROB reservation owner. Full model timing requires
moving ROB reservation before enqueue and carrying full `load_id`/`sid`
payloads into LIQ/STQ owners. Full store timing still requires STA/STD
execution, executed store request construction, and STQ residency behind the
new dispatch queues.

## Deferred Owners

- Width-wide selection and rename for multiple decoded uops per cycle.
- Enqueue-time ROB reservation from full block/decode context rather than
  reduced queue-head allocator cursor stamping.
- BID-scoped `dec_ren_q` flush pruning rather than coarse queue clear.
- Top-level frontend backpressure wiring that consumes `decodeReady`.
- Width-wide slot-order LSID/SID allocation and same-cycle memory ordering.
- Full `load_id`/`sid` payload carry into LIQ/STQ owners.
- STA/STD execution, address/data readiness, and STQ mutation behind the
  current `StoreDispatchQueues` heads.
- Automatic checkpoint capture from validated `isLastInBlock`.
- T/U/SGPR/tile/vector operand classification and rename.
- Ready-table initialization, issue enqueue, execution completion, and full
  commit side effects.
- Full QEMU-vs-DUT compare with live architectural commit rows.

## Verification

Focused gate:

```bash
bash tools/chisel/run_chisel_tests.sh --only DecodeRenameROBPath
bash tools/chisel/run_chisel_tests.sh --only DecodeLoadStoreIdAssign
bash tools/chisel/run_chisel_tests.sh --only DecodeRenameQueue
bash tools/chisel/run_chisel_tests.sh --only StoreSplitPayload
bash tools/chisel/run_chisel_tests.sh --only StoreDispatchQueues
```

Affected gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only ScalarDecodeRenameBridge
bash tools/chisel/run_chisel_tests.sh --only FrontendDecodeStage
bash tools/chisel/run_chisel_tests.sh --only DispatchROBAllocator
bash tools/chisel/run_chisel_tests.sh --only GPRRenameCheckpoint
bash tools/chisel/run_chisel_rob_bookkeeping.sh --reduced-rob
bash tools/chisel/run_chisel_top_xcheck.sh
bash tools/chisel/run_chisel_qemu_crosscheck.sh --dry-run
```

The current tests cover first-valid slot selection, queue admission
backpressure, the reduced memory-order ID observability, the store dispatch
STA/STD readiness rule, the allocation attempt contract, IO shape, and CIRCT
elaboration through frontend decode, `DecodeLoadStoreIdAssign`,
`DecodeRenameQueue`, scalar rename, `StoreSplitPayload`, and backend
allocation.
