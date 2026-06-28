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
  - `chisel/src/main/scala/linxcore/backend/DecodeRenameQueue.scala`
  - `chisel/src/main/scala/linxcore/rename/ScalarDecodeRenameBridge.scala`
  - `chisel/src/main/scala/linxcore/backend/DispatchROBAllocator.scala`

## Purpose

`DecodeRenameROBPath` is the first reduced frontend/backend composition point.
It connects `FrontendDecodeStage`, `DecodeRenameQueue`,
`ScalarDecodeRenameBridge`, and `DispatchROBAllocator` so one decoded scalar
uop can pass through a registered D2/D3 queue, produce a renamed payload, and
allocate a real BROB/ROB row in the accepted rename cycle.

The module exists to remove the next layer of fixture wiring. It is still a
bring-up path, not the final dispatch pipe. It selects one decoded slot per
cycle, enqueues that raw decoded row into a registered `dec_ren_q` owner,
stamps temporary ROB identity from allocator cursors when the queue head is
presented to rename, and leaves enqueue-time ROB reservation, LSID allocation,
store split, width-wide rename, and full top-level fetch/commit flow to later
owners.

## Interface

Inputs:

- `d1`, `slots`, `validMask`, `flushValid`: D1/F4 decode inputs consumed by
  `FrontendDecodeStage`.
- `renamedOutReady`: downstream renamed-uop consumer readiness.
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
- `renamedOutValid/renamedOut`, `accepted`: renamed-uop output and atomic
  path accept event.
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

The selected raw decoded row is written into `DecodeRenameQueue` when
`decRenPushFire` is true. The queue is flushed by direct frontend flush input
or by backend cleanup intent. `decodeReady` mirrors the queue push-ready signal
so a future frontend integration can advance D1/F4 only when the selected row
is accepted.

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
3. `SPE::Xfer()` calls `dec_ren_q.Work()` to move ready written rows into the
   rename-visible queue image.
4. `SPERename::Rename()` consumes `dec_ren_q`, maps scalar sources, allocates
   scalar destinations, captures checkpoints for `isLastInBlock`, and forwards
   the renamed uop toward dispatch.
5. `GPRRename` owns scalar `smap`, `cmap`, checkpoints, free tags, and mapQ.

`DecodeRenameROBPath` now preserves the registered `dec_ren_q` timing point,
but it still reduces the model in one important way: the queue stores raw
decoded rows and the allocator cursor identity is stamped at the queue head.
This avoids duplicate cursor reservations while the path lacks an
enqueue-time ROB reservation owner. Full model timing requires moving ROB
reservation and LSID/SID assignment before enqueue.

## Deferred Owners

- Width-wide selection and rename for multiple decoded uops per cycle.
- Enqueue-time ROB reservation from full block/decode context rather than
  reduced queue-head allocator cursor stamping.
- BID-scoped `dec_ren_q` flush pruning rather than coarse queue clear.
- Top-level frontend backpressure wiring that consumes `decodeReady`.
- LSID/SID allocation and same-cycle memory ordering.
- Store split rewrite into STA/STD.
- Automatic checkpoint capture from validated `isLastInBlock`.
- T/U/SGPR/tile/vector operand classification and rename.
- Ready-table initialization, issue enqueue, execution completion, and full
  commit side effects.
- Full QEMU-vs-DUT compare with live architectural commit rows.

## Verification

Focused gate:

```bash
bash tools/chisel/run_chisel_tests.sh --only DecodeRenameROBPath
bash tools/chisel/run_chisel_tests.sh --only DecodeRenameQueue
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
backpressure, the allocation attempt contract, IO shape, and CIRCT elaboration
through frontend decode, `DecodeRenameQueue`, scalar rename, and backend
allocation.
