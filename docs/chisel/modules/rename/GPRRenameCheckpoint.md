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
ClockHands/T/U, local registers, tile rename, multi-thread rename, and dispatch
bundle-width allocation remain later owner packets.

## Interface

| Port | Direction | Type | Purpose |
|---|---:|---|---|
| `srcArchTags` | input | `Vec(3, UInt)` | Source GPR architectural tags to read from `smap`. |
| `renameValid` | input | `Bool` | Requests one destination GPR allocation. |
| `renameArchTag` | input | `UInt` | Destination architectural GPR index. |
| `renameBid` / `renameRid` / `renameGid` | input | `ROBID` | Model identity stored in the mapQ row. |
| `checkpointValid` | input | `Bool` | Captures the current `smap` into `checkpointBid.value`. |
| `checkpointBid` | input | `ROBID` | Checkpoint slot and new `renamePtr`. |
| `commitValid` | input | `Bool` | Retires all mapQ rows matching `commitBid`. |
| `commitBid` | input | `ROBID` | Block identity for `GPRRename::RetireBlock`. |
| `cleanup` | input | `RecoveryCleanupIntent` | Registered recovery intent from `RecoveryCleanupControl`. |
| `srcPhysTags` | output | `Vec(3, UInt)` | Source physical tags read from current `smap`. |
| `renameReady` / `renameAccepted` | output | `Bool` | One-destination allocation handshake. |
| `renamePhysTag` | output | `UInt` | First free physical tag selected for accepted rename. |
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
| `cleanupThreadMismatch` / `stateError` | output | `Bool` | The first owner supports only STID0. |

## Logic Design

Reset mirrors `GPRRename::Build` for a single scalar thread:

- `smap[i] = i` and `cmap[i] = i` for the 24 model GPRs.
- physical tags `0..23` are allocated; tags `24..physRegs-1` are free.
- checkpoints are invalid and initialized to identity maps.
- mapQ rows are invalid.

The model exposes direct methods rather than a single `Work()` arbitration
point. The Chisel owner uses a deterministic hardware priority for this first
registered state owner:

1. recovery cleanup,
2. block commit,
3. checkpoint capture,
4. one destination rename allocation.

Recovery therefore owns the cycle and cannot race a new speculative rename.

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

## Commit

`GPRRename::RetireBlock` walks mapQ rows in allocation order. For each row with
matching BID, it releases the previously committed tag for that architectural
register, updates `cmap(archTag)` to the row physical tag, and clears the mapQ
row.

The Chisel implementation preserves the effective result of that ordered walk:
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

## Flush And Replay

For `renameFlushValid`, the model computes `restoreBid = flush.bid - 1`. If
`restoreBid <= renamePtr`, `renamePtr` is moved back and `smap` is restored
from `checkpointMap(restoreBid.value)` when that checkpoint is valid; otherwise
it falls back to `cmap`.

After the optional restore, mapQ rows matching the flush request are pruned:

- `baseOnBid`: prune rows with `flush.bid <= row.bid`.
- non-BID: prune rows with `(flush.bid, flush.rid) <= (row.bid, row.rid)`.

For non-BID flushes, surviving same-BID mapQ rows are re-applied into `smap`.
This preserves the model loop that restores older same-block speculative
renames after pruning younger rows.

`renameReplayValid` is observed but does not mutate scalar GPR maps in this
packet. SGPR/ClockHands replay and queue cleanup belong to later rename
owners.

## Observability

The module exposes map contents, free state, mapQ occupancy, checkpoint validity,
released physical tags, and restore source. These outputs are intended for the
future dispatch/commit/top integration packet and for cross-check trace hooks.

## Verification

Focused gate:

```bash
bash tools/chisel/run_chisel_tests.sh --only GPRRenameCheckpoint
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
- no identity-tag release on first architectural commits,
- live-reference protection while `smap`, `cmap`, or `mapQ` still mention a
  physical tag,
- Chisel elaboration of cleanup, map, checkpoint, release outputs, and 128
  physical GPR tags.
