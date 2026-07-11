# ROBFullBidLookup

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/rob/ROBFullBidLookup.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/rob/ROBFullBidLookupSpec.scala`
- Integration owners:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/rob/ROBEntryBank.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/backend/DispatchROBAllocator.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/backend/DecodeRenameROBPath.scala`
- Recovery consumer:
  `rtl/LinxCore/chisel/src/main/scala/linxcore/recovery/RingFullBidRecoveryBridge.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/ModelCommon/bus/FlushBus.h::FlushBus::match`
  - `model/LinxCoreModel/model/lsu/load_unit/ldq.cpp::LDQInfo::handleFlush`
- Contract IDs: `LC-MA-ROB-001`, `LC-MA-MEM-001`, `LC-MA-BLK-001`

## Purpose

`ROBFullBidLookup` recovers the allocator-stamped full block-generation
sideband for a retained ring-qualified recovery request. It is not a BID
reconstruction function. Ring ROBID contains only slot and wrap context, so
guessing higher uniqueness bits could target the wrong BROB generation.

The lookup is direct-indexed by native RID. It succeeds only when the resident
row exactly matches BID, GID, RID, PE, STID, and TID, carries a valid full
sideband, and that sideband projects back to the request BID. The full sideband
remains implementation state during migration to canonical per-STID `BID_W`;
it is not ISA-visible state.

## Interface

| Direction | Signal | Type | Description |
|---|---|---|---|
| input | `request` | `ROBFullBidLookupRequest` | Valid plus exact BID/GID/RID and PE/STID/TID key. |
| input | `occupiedMask` | `UInt(entries.W)` | Pre-cycle resident-row mask. |
| input | `rowBid/rowGid/rowRid` | `Vec[ROBID]` | Native row identity sidecars. |
| input | `rowPeId/rowStid/rowTid` | `Vec[UInt]` | Exact Linx ownership sidecars. |
| input | `rowBlockBidValid/rowBlockBid` | `Vec` | Allocator-stamped full generation sideband. |
| output | `result.request` | request echo | Allows a remote consumer to reject stale combinational/registered results. |
| output | `result.matched` | `Bool` | Exact resident identity, sideband validity, and ring projection all passed. |
| output | `result.blockBid` | `UInt` | Full generation sideband, zero unless matched. |
| output | `result.blockedBy*` | `Bool` | Invalid identity, free, stale RID, BID, GID, scope, missing-sideband, and projection blockers. |

## Logic Design

`request.rid.value` selects one physical ROB slot. This avoids an associative
full-ROB scan and follows existing row-status and commit-trace lookup ownership.
The selected slot then passes ordered checks:

1. BID, GID, and RID are valid.
2. The RID-indexed slot is occupied and has the same RID epoch.
3. Native BID and GID match.
4. PE, STID, and TID all match. These are identity checks, not optional flush
   scope checks.
5. The resident commit row has `blockBidValid`.
6. `FullBidRecoveryBridge.fullBidToRobId(row.blockBid)` equals the request BID.

Any failure produces one ordered blocker and no full BID. The module has no
state and no cleanup side effect.

## Recovery Contract

The MDB report remains queued until this lookup succeeds, oldest eligibility
passes, the selected-source arbiter grants it, and cleanup accepts the promoted
request. A miss must never fall back to ring-only BROB/BCTRL cleanup.

## Verification

- `bash tools/chisel/run_chisel_tests.sh --only ROBFullBidLookupSpec`
- `bash tools/chisel/run_chisel_tests.sh --only RingFullBidRecoveryBridgeSpec`
- `bash tools/chisel/run_chisel_tests.sh --only RecoveryCleanupROBProbeSpec`
- `bash tools/chisel/run_chisel_recovery_cleanup_rob_probe.sh`

Focused tests cover non-reconstructible full uniqueness, stale RID, cross-scope
identity, missing sideband, ring-projection mismatch, and elaboration. The
generated probe proves a wrong-RID request remains retained before exact lookup
promotes the report and enables accepted block cleanup.
