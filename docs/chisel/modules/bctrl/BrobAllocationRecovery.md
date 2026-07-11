# BrobAllocationRecovery

## Source Mapping

- Chisel: `chisel/src/main/scala/linxcore/bctrl/BrobAllocationRecovery.scala`
- Chisel generated probe:
  `chisel/src/main/scala/linxcore/bctrl/BrobAllocationRecoveryProbe.scala`
- LinxCoreModel:
  - `model/LinxCoreModel/model/bctrl/BROB.cpp`, `BlockROB::recoverBlock`
  - `model/LinxCoreModel/model/bctrl/BROB.cpp`, `BlockROB::setFlushed`
  - `model/LinxCoreModel/model/core/FlushControl.cpp`, `CheckFlush`
- Contract IDs: `LC-MA-BLK-001`, `LC-MA-ROB-001`, `LC-MA-FWD-001`

## Purpose

`BrobAllocationRecovery` owns the parameterized next-allocation full BID for
each STID. It replaces the split `blockSlot/blockUniq` registers formerly
embedded in `DispatchROBAllocator` and restores the selected lane when an
accepted global cleanup discards a speculative BROB suffix.

This is an ISA-neutral circular-resource mechanism specialized by Linx block
identity. It carries no ARM architectural state or behavior.

## Interface

| Direction | Signal | Description |
|---|---|---|
| input | `advanceValid/advanceStid` | Advance one lane after an accepted new BROB allocation. |
| input | `recoveryValid/recoveryStid` | Restore one lane after accepted cleanup. |
| input | `recoveryPivotBid` | Authoritative full Linx BID from the retained recovery payload. |
| input | `recoveryInclusive` | The pivot itself is the first killed block. |
| input | `queryStid` | Select one lane's next allocation BID. |
| output | `nextBid/cursor` | Selected and per-lane full-BID allocation cursors. |
| output | `recoveryFirstKilledBid` | Installed cursor: pivot for inclusive recovery, otherwise pivot plus one. |
| output | `recoveryOldAllocBid` | Pre-recovery cursor snapshot corresponding to model `old_alloc`. |
| output | `*InRange/recoveryApplied` | Explicit STID validity and accepted-state diagnostics. |

## Semantics

- `MISS_PRED_FLUSH` reports the first block after the resolving block. The
  reported BID and every younger BID are killed, so recovery is inclusive and
  the allocation cursor is restored to the pivot.
- An accepted scalar nuke, inner, or fast flush preserves its authoritative
  target block. The first killed block is the target successor, so the cursor
  is restored to `pivot + 1`.
- Recovery dominates allocation when both target the same STID. Independent
  lanes may advance in the same cycle at this owner boundary.
- Invalid STIDs cannot mutate any cursor. Full-BID addition wraps only at the
  configured implementation width.

`RecoveryCleanupControl.blockFlushInclusive` carries the model path
classification. `DispatchROBAllocator` blocks new BROB allocation during the
accepted global cleanup pulse, applies the cursor restore, and gives
`BrobMetaTracker` the same inclusive/exclusive pivot rule. Metadata suffix
pruning and allocation-tail restoration therefore use one decision.

## Scope

This packet restores `BROBState::allocPtr` behavior only. The model's
`commitPtr`, dispatch/rename pointers, `nonFlushBid`, `sbarPtr`, replay-state
mutation, and canonical finite-width `BIDRingOrder` integration remain separate
promotion work. R650 is a Chisel-only implementation packet.

## Verification

- `bash tools/chisel/run_chisel_tests.sh --only BrobAllocationRecovery`
- `bash tools/chisel/run_chisel_tests.sh --only BROB`
- `bash tools/chisel/run_chisel_tests.sh --only DispatchROBAllocator`
- `bash tools/chisel/run_chisel_brob_allocation_recovery_probe.sh`

The generated gate covers per-STID advance, inclusive miss-predict restore,
preserved-pivot successor restore, old-allocation capture, same-lane recovery
priority, invalid-STID rejection, and coherent child admission suppression.
