# GPRRenameCheckpoint

## Source Mapping

- LinxCoreModel: `model/LinxCoreModel/model/bctrl/spe/GPRRename.cpp`
- LinxCoreModel: `model/LinxCoreModel/model/bctrl/spe/GPRRename.h`
- LinxCoreModel: `model/LinxCoreModel/model/bctrl/spe/SPERename.cpp`
- LinxCoreModel: `model/LinxCoreModel/model/ModelCommon/bus/RenameBus.h`
- LinxCoreModel: `model/LinxCoreModel/model/ModelCommon/bus/FlushBus.h`
- LinxCoreModel: `model/LinxCoreModel/isa/ISACommon/GPR.h`
- pyCircuit: `src/bcc/backend/state.py`
- pyCircuit: `src/bcc/backend/rename.py`
- Chisel: `chisel/src/main/scala/linxcore/rename/GPRRenameCheckpoint.scala`
- Tests: `chisel/src/test/scala/linxcore/rename/GPRRenameCheckpointSpec.scala`
- Generated RTL probe: `tools/chisel/run_chisel_gpr_rename_stid_probe.sh`
- Integrated reservation owner:
  `chisel/src/main/scala/linxcore/backend/GPRReservationTracker.scala`

## Purpose

`GPRRenameCheckpoint` is the first scalar rename cleanup consumer behind
`RecoveryCleanupControl`. It owns the model `GPRRename` state shape for the
current Chisel lane:

- speculative map `smap`,
- committed map `cmap`,
- per-BID checkpointed `smap`,
- `renamePtr`,
- free physical-tag mask,
- finite `mapQ` rows for speculative destination mappings.

This packet is deliberately scoped to scalar general registers. The model
`GPR::GPR_COUNT` is 24, while current Chisel and pyCircuit interfaces still use
6-bit architectural tags so they can carry invalid and later non-GPR aliases.
ClockHands/T/U, local registers, tile rename, and dispatch bundle-width
allocation remain separate owner packets. R644 promotes the model's
multi-STID scalar GPR contract: speculative and committed maps, checkpoints,
rename pointers, and MapQ rows are lane-local, while physical-register
allocation is shared across lanes.

## Interface

| Port | Direction | Type | Purpose |
|---|---:|---|---|
| `srcArchTags` | input | `Vec(3, UInt)` | Source GPR architectural tags to read from `smap`. |
| `renameStid` | input | `UInt` | STID lane that owns source lookup and destination rename. |
| `renameValid` | input | `Bool` | Requests one destination GPR allocation. |
| `renameArchTag` | input | `UInt` | Destination architectural GPR index. |
| `renameBid` / `renameRid` / `renameGid` | input | `ROBID` | Model identity stored in the mapQ row. |
| `checkpointValid` | input | `Bool` | Captures the current `smap` into `checkpointBid.value`. |
| `checkpointBid` | input | `ROBID` | Checkpoint slot and new `renamePtr`. |
| `checkpointStid` | input | `UInt` | STID lane that owns the explicit checkpoint. |
| `postRenameCheckpointValid` | input | `Bool` | Captures the post-accepted-rename `smap` into `postRenameCheckpointBid.value`; if no rename fires, captures current `smap`. |
| `postRenameCheckpointBid` | input | `ROBID` | Checkpoint slot used by the reduced post-rename checkpoint refresh path. |
| `postRenameCheckpointStid` | input | `UInt` | STID lane for the post-rename checkpoint; it must match an accepted rename lane. |
| `commitValid` | input | `Bool` | Retires all mapQ rows matching `commitBid`. |
| `commitBid` | input | `ROBID` | Block identity for `GPRRename::RetireBlock`. |
| `commitStid` | input | `UInt` | STID half of the exact block-commit identity. |
| `queryStid` | input | `UInt` | Selects the lane exported through map, checkpoint, MapQ, and pointer observability. |
| `cleanup` | input | `RecoveryCleanupIntent` | Registered recovery intent from `RecoveryCleanupControl`. |
| `srcPhysTags` | output | `Vec(3, UInt)` | Source physical tags read from current `smap`. |
| `renameReady` / `renameAccepted` | output | `Bool` | One-destination allocation handshake. |
| `renamePhysTag` | output | `UInt` | First free physical tag selected for accepted rename. |
| `renameOldPhysTag` | output | `UInt` | Previous destination mapping read from `renameStid`, independent of `queryStid`. |
| `checkpointAccepted` | output | `Bool` | Checkpoint capture occurred this cycle. |
| `commitAccepted` | output | `Bool` | Commit walk occurred this cycle. |
| `cleanupReady` | output | `Bool` | First owner is always ready for the registered cleanup intent. |
| `cleanupFlushApplied` | output | `Bool` | `renameFlushValid` consumed and state restored/pruned. |
| `cleanupReplayObserved` | output | `Bool` | `renameReplayValid` observed; GPR maps are unchanged. |
| `restoreFromCheckpoint` | output | `Bool` | Flush restored `smap` from a valid checkpoint. |
| `restoreFromCommitMap` | output | `Bool` | Flush fell back to `cmap` because the checkpoint was invalid. |
| `freeMask` / `freeCount` | output | mask/count | Current physical-tag free state. |
| `mapQValidMask` / `mapQFreeCount` | output | mask/count | Current speculative mapQ occupancy. |
| `checkpointValidMask` | output | mask | Valid checkpoint slots by ring BID value. |
| `renamePtr` | output | `ROBID` | Current model rename pointer. |
| `smap` / `cmap` | output | `Vec(24, UInt)` | Observability for future dispatch/commit integration. |
| `committedMapQMask` | output | `UInt` | MapQ rows retired in the commit walk. |
| `prunedMapQMask` | output | `UInt` | MapQ rows pruned by the recovery walk. |
| `releasedPhysMask` | output | `UInt` | Physical tags released by commit or flush. |
| `*StidInRange` | output | `Bool` | Range result for rename, checkpoint, commit, and query selectors. |
| `cleanupThreadMismatch` / `stateError` | output | `Bool` | Invalid cleanup STID and aggregate selector/state diagnostics. |

## Logic Design

Reset mirrors `GPRRename::Build` for every configured scalar STID:

- lane `s` maps architectural register `i` to identity tag
  `s * archRegs + i` in both `smap` and `cmap`.
- all `stidCount * archRegs` identity tags are permanently excluded from
  allocation; remaining physical tags form one shared free pool.
- checkpoints are invalid and initialized to identity maps.
- every lane's MapQ rows are invalid.

The parameter contract requires `stidCount > 0`, the configured STID width to
represent every lane, and `physRegs > stidCount * archRegs`. `mapQDepth`,
`physRegs`, `archRegs`, and `stidCount` remain independent sizing controls.

The model exposes direct methods rather than a single `Work()` arbitration
point. The Chisel owner uses a deterministic hardware priority for this first
registered state owner:

1. recovery cleanup,
2. block commit,
3. checkpoint capture,
4. one destination rename allocation.

Recovery therefore owns the cycle and cannot race a new speculative rename.
Maintenance is globally serialized even when it targets different STIDs; lane
locality is a state-ownership guarantee, not a claim of simultaneous commit or
flush bandwidth.

Global block cleanup requires two separate identities in
`RecoveryCleanupIntent`: canonical `blockFlushBid` for the Linx architectural
block slot and a valid owner-resolved `blockFlushPointer` for implementation
age against MapQ/checkpoint rows. This module never widens canonical BID; it
uses only the resolved pointer for generation-aware pruning and asserts if a
global block cleanup arrives without that sidecar.

## STID Ownership

Source lookup, destination update, checkpoint capture, block commit, cleanup,
and observability select exactly one configured STID. A MapQ row records its
STID and can affect only that lane. Equal full BID values in two STIDs are
therefore independent: commit or recovery matches `(stid, fullBid)` rather
than BID alone. Cleanup restores and prunes only the selected lane; surviving
rows in other lanes continue to own their physical tags.

Free-list recomputation is global because the pool is shared. A physical tag
is free only when no lane's SMAP, CMAP, or valid MapQ row references it. This
prevents one lane's commit or flush from releasing a tag still live in another
lane.

The rename datapath and capacity-observability query are deliberately
independent. Source tags and `renameOldPhysTag` always read `renameStid`; map,
MapQ, and checkpoint observability read `queryStid`. A selected decode row may
therefore query its own MapQ capacity while an older queued row renames in a
different STID without corrupting the queued row's old destination tag.

Decode reservations follow the same ownership split. `GPRReservationTracker`
counts pending physical allocations globally and pending MapQ rows per STID.
Admission compares the selected row against global physical credit and its own
lane's MapQ credit. Pressure in one MapQ lane cannot block or admit another
lane, while exhaustion of the shared physical pool blocks every lane.

## Rename And Checkpoint

An accepted rename chooses the lowest-numbered free physical tag, clears it
from `freeMask`, updates `smap(renameArchTag)`, and inserts the first free mapQ
row with `(bid,rid,gid,archTag,physTag)`.

Checkpoint capture copies the current `smap` into
`checkpointMap(checkpointBid.value)`, sets that checkpoint valid, and updates
`renamePtr`. In the current model call path this is invoked from
`SPERename` when the renamed instruction is marked `isLastInBlock`; later
decode/dispatch owners may choose the exact boundary handoff, but the Chisel
state owner only requires an explicit checkpoint command.

R202 adds the reduced post-rename checkpoint refresh used by the marker-row
top. When a rename allocation is accepted and `postRenameCheckpointValid` is
set, the checkpoint slot receives the `smap` after the new destination mapping
is installed. When no destination rename fires, the same input captures the
current `smap`. This is a reduced in-order approximation of the model's
`isLastInBlock` checkpoint rule: by the time a block-stop redirect asks
recovery to restore the just-finished block, the checkpoint for that block BID
contains the latest scalar map, including adjacent `C.SETRET` materialization
rows.

## Commit

`GPRRename::RetireBlock` walks mapQ rows in allocation order. For each row with
matching BID, it releases the previously committed tag for that architectural
register, updates `cmap(archTag)` to the row physical tag, and clears the mapQ
row.

The Chisel implementation preserves the effective result of that ordered walk
within the selected STID:
the last committed row for an architectural tag becomes the new `cmap` value,
the pre-commit committed tag is released, and earlier same-arch committed
physical tags are also released.

R141 hardens the release path for the reduced CoreMark gate. Identity tags
`0..23` are never returned to the free pool, and after cleanup/commit/rename
state is computed, any physical tag still referenced by next `smap`, next
`cmap`, or a valid next `mapQ` row is forced allocated. This mirrors the model
ownership rule that a tag is reusable only after all speculative and committed
references to it are gone. The same packet removes the previous 64-physical-tag
bring-up cap; the implementation now elaborates at LinxCoreModel's
`ggpr_count = 128` capacity.

R196 aligns the reduced live top with LinxCoreModel's independent
`ggpr_mapq_depth = 256` setting. This scalar GPR mapQ capacity is separate from
the reduced local T/U `mapQDepth` used for T/U ROBID sequence plumbing; do not
increase the local T/U depth just to model GGPR rename pressure. The top-level
marker-row emitters pass `gprMapQDepth = 256` into the scalar rename owner while
leaving local T/U sequences compact.

R197 reduces generated release/live-mask fanout for the model-sized mapQ. The
commit path now builds release sets from one-hot physical-tag masks, the
"later same architectural register" check uses a reverse architectural-tag mask
scan, and the final live-reference protection uses one combined live physical
mask. This preserves the ordered model commit result while cutting Chisel emit
time and Verilator RSS for the 256-depth top.

R198 keeps the model-sized capacity but splits the largest per-architecture
mapQ scans into helper modules. `GPRRenameReplaySurvivorSelect` owns the
survivor replay selection for one architectural register, and
`GPRRenameCommitArchSelect` owns the latest committed mapQ row selection for
one architectural register. This keeps the parent checkpoint behavior aligned
with the model's ordered commit, flush, and replay results while making the
generated 256-depth marker-row top Verilator-practical. The smoke gate built
46 generated modules into 73 C++ files in roughly 262 seconds and the
1024-row marker-row gate now reaches DUT comparison instead of stalling before
`obj_dir`.

## Flush And Replay

For `renameFlushValid`, the model computes `restoreBid = flush.bid - 1`. If
`restoreBid <= renamePtr`, `renamePtr` is moved back and `smap` is restored
from `checkpointMap(restoreBid.value)` when that checkpoint is valid; otherwise
it falls back to `cmap`.

After the optional restore, mapQ rows matching the flush request are pruned:

- `baseOnBid`: prune rows with `flush.bid <= row.bid`.
- non-BID: prune rows with `(flush.bid, flush.rid) <= (row.bid, row.rid)`.

After pruning, surviving mapQ rows are re-applied into `smap` in model age
order. This includes older surviving rows from wrapped BIDs when no valid
checkpoint is available, not only rows in the same BID as the cleanup point.
The fold starts from the checkpoint or `cmap` restore image and replays each
valid survivor whose `(bid,rid)` is older than the flush point, so speculative
mappings that remain architecturally live are not lost on redirect cleanup.

`renameReplayValid` is observed but does not mutate scalar GPR maps in this
packet. SGPR/ClockHands replay and queue cleanup belong to later rename
owners.

## Observability

The module exposes map contents, free state, mapQ occupancy, checkpoint validity,
released physical tags, and restore source. These outputs are intended for the
future dispatch/commit/top integration packet and for cross-check trace hooks.

## Architectural Boundary

The shared free list, per-lane maps, ordered MapQ commit, checkpoint restore,
and survivor replay are ISA-neutral OOO mechanisms. Their architectural keys
are Linx `(STID, BID, RID, GID)` identities plus explicit internal pointer
context where generation-aware storage still requires it.
This owner does not import ARM register banking, condition-code state,
exception levels, barriers, or memory-ordering rules. Any future ISA-neutral
rename optimization must preserve the Linx block identity and recovery
contract defined here.

## Verification

Focused gate:

```bash
bash tools/chisel/run_chisel_tests.sh --only GPRRenameCheckpoint
bash tools/chisel/run_chisel_gpr_rename_stid_probe.sh
```

Affected gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only RecoveryCleanupControl
bash tools/chisel/run_chisel_tests.sh --only FullBidRecoveryBridge
bash tools/chisel/run_chisel_tests.sh --only ROBID
bash tools/chisel/run_chisel_rob_bookkeeping.sh --reduced-rob
```

The current test reference covers:

- identity reset state,
- first-free rename allocation and mapQ insertion,
- ordered block commit including same-arch mapQ rows,
- base-on-BID flush restore and pruning,
- non-BID flush restoration of surviving same-BID mapQ rows,
- non-BID flush restoration of older surviving wrapped-BID mapQ rows when no
  checkpoint is valid,
- no identity-tag release on first architectural commits,
- live-reference protection while `smap`, `cmap`, or `mapQ` still mention a
  physical tag,
- Chisel elaboration of cleanup, map, checkpoint, release outputs, 128 physical
  GPR tags, the model-sized scalar GPR mapQ pressure path, and the helper
  modules used to keep the 256-entry path practical,
- post-rename checkpoint IO for the reduced block-stop restore path.
- two-STID identity-map partitioning and shared free capacity,
- equal-BID lane-local commit independence,
- lane-local flush/prune with the other lane surviving, and
- global physical versus per-STID MapQ reservation pressure,
- queued-lane old-destination lookup while another STID is queried, and
- visible rejection of an out-of-range STID in generated Verilated RTL.
