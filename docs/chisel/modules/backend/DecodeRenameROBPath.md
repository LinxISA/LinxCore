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
  - `chisel/src/main/scala/linxcore/rename/ScalarDecodeRenameBridge.scala`
  - `chisel/src/main/scala/linxcore/backend/DispatchROBAllocator.scala`

## Purpose

`DecodeRenameROBPath` is the first reduced frontend/backend composition point.
It connects `FrontendDecodeStage`, `ScalarDecodeRenameBridge`, and
`DispatchROBAllocator` so one decoded scalar uop can produce a renamed payload
and allocate a real BROB/ROB row in the same accepted cycle.

The module exists to remove the next layer of fixture wiring. It is still a
bring-up path, not the final dispatch pipe. It selects one decoded slot per
cycle, stamps temporary ROB identity from the allocator cursors, and leaves the
registered `dec_ren_q`, LSID allocation, store split, width-wide rename, and
full top-level fetch/commit flow to later owners.

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
module; a later D2 queue owner must provide full width and backpressure.

Before feeding the selected row into rename, the module stamps the missing
backend identity that `DCTop::Work()` supplies in the C++ model:

- `bid` comes from the current generated block BID converted to ring `ROBID`;
- `rid.value` comes from the ROB allocation pointer exposed as
  `allocRobValue`;
- `gid` is held at zero in the reduced scalar path;
- `blockBidValid/blockBid` mirrors the generated full hardware BID.

`ScalarDecodeRenameBridge.robAllocAttemptValid` drives
`DispatchROBAllocator.allocValid`. This signal is intentionally independent of
allocator ready. The bridge only reports `accepted` and mutates GPR rename
state when `allocReady` returns true, but the allocator duplicate-identity
check sees a stable request row before it computes ready. This avoids a
ready/valid combinational feedback path through ROB duplicate detection.

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
3. `SPERename::Rename()` consumes `dec_ren_q`, maps scalar sources, allocates
   scalar destinations, captures checkpoints for `isLastInBlock`, and forwards
   the renamed uop toward dispatch.
4. `GPRRename` owns scalar `smap`, `cmap`, checkpoints, free tags, and mapQ.

`DecodeRenameROBPath` deliberately fuses these into one reduced accept
boundary so cross-check infrastructure can start seeing real allocation
state. It does not claim final cycle timing until the registered D2/D3 queue
and width-wide owners are implemented.

## Deferred Owners

- Registered `dec_ren_q` / D2-to-D3 queueing and backpressure.
- Width-wide selection and rename for multiple decoded uops per cycle.
- ROB identity assignment from full block/decode context rather than reduced
  allocator cursor stamping.
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

The current tests cover first-valid slot selection, the allocation attempt
contract, IO shape, and CIRCT elaboration through frontend decode, scalar
rename, and backend allocation.
