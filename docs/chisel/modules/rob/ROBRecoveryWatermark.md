# ROBRecoveryWatermark

## Source Mapping

- Chisel: `chisel/src/main/scala/linxcore/rob/ROBRecoveryWatermark.scala`
- ROB owner: `chisel/src/main/scala/linxcore/rob/ROBEntryBank.scala`
- Block owner: `chisel/src/main/scala/linxcore/bctrl/BROB.scala`
- Backend integration: `chisel/src/main/scala/linxcore/backend/DispatchROBAllocator.scala`
- Model evidence: `model/LinxCoreModel/model/core/FlushControl.cpp`, especially
  `isRecoveryPoint`, plus `model/LinxCoreModel/model/bctrl/BROB.cpp`
- Contract ID: `LC-CHISEL-ROB-RECOVERY-WATERMARK-001`

## Purpose

`ROBRecoveryWatermark` owns the oldest resident scalar RID in each instantiated
Linx STID. Recovery eligibility needs this RID for same-block ordering; it must
not use a zero tie-off, a global cross-STID head, or a BID reconstructed from a
ROB slot.

The companion oldest block BID and completed-oldest state are owned by
`BrobMetaTracker`. `DispatchROBAllocator` joins the BROB and ROB observations
and publishes one coherent per-STID recovery watermark to
`DecodeRenameROBPath`.

## Parameters

| Parameter | Meaning |
|---|---|
| `entries` | Resident scalar ROB rows. Must be a power of two greater than one. |
| `stidCount` | Number of instantiated scalar STID lanes. |
| `stidWidth` | Encoded STID width. `stidCount` must fit. |

## Interface

| Direction | Signal | Description |
|---|---|---|
| input | `commitHead` | Current scalar ROB commit pointer. |
| input | `rowValid[entries]` | Resident row-valid image. |
| input | `rowStatus[entries]` | Current row status image. |
| input | `rowStid[entries]` | Row-owned STID. |
| input | `rowRid[entries]` | Exact wrap-qualified row RID. |
| input | `rowBlockBid[entries]` | Allocator-stamped full block BID stored with each row. |
| output | `oldestValid[stidCount]` | At least one non-retired resident row exists in the STID. |
| output | `oldestRid[stidCount]` | First eligible row encountered in circular commit order. |
| output | `oldestBlockBid[stidCount]` | Full block BID belonging to that exact selected row. |

## Selection Rules

For each STID, scan every physical row exactly once beginning at `commitHead`.
The first row with matching STID, valid storage, and a status other than
`Free` or `Retired` owns `oldestRid`. Retired rows are excluded because commit
has already advanced architectural state even when delayed deallocation keeps
the physical row resident.

The scan is observation-only. It does not modify commit, deallocation,
allocation, completion, or flush order. Different STIDs are selected
independently and no BID/RID comparison is performed across STIDs.

## Integration Contract

`RecoveryWatermarkJoin` is the sole combinational owner of the BROB/ROB join.
For each STID it accepts `brobValid/blockBid/complete` and
`robValid/rid/blockBid`, then publishes the five `recoveryOldest*` fields below.
Its sizing is parameterized by `entries`, `stidCount`, and `bidWidth`.

`BrobMetaTracker.oldestBid` is the lowest live full BID in the corresponding
STID lane. `BrobMetaTracker.oldestComplete` describes that exact entry.
`DispatchROBAllocator` first requires the ROB-selected full block BID to equal
the BROB-selected oldest full BID. This prevents a marker-only older block from
being paired with a younger scalar RID. It then publishes:

- `recoveryOldestValid = brob.oldestValid && rob.recoveryOldestValid && fullBidMatch`;
- `recoveryOldestBlockBid = brob.oldestBid`;
- `recoveryOldestBid = fullBidToRobId(brob.oldestBid)`;
- `recoveryOldestRid = rob.recoveryOldestRid`;
- `recoveryOldestBlockComplete = brob.oldestComplete`.

Consumers must qualify all fields with `recoveryOldestValid`. Full BID remains
BROB-owned; the ring BID is only the eligibility projection. The qualifier is
threaded through source arbitration and class merge. An invalid watermark must
not activate the model's oldest-BID special case or completed-oldest replay
drop, even though the diagnostic BID/RID payload remains visible.

## Exclusions

This module does not define exception levels, condition flags, exclusive
monitors, power state, or any ARM architectural behavior. It does not create a
global order between Linx STIDs.

## Verification

- `bash tools/chisel/run_chisel_tests.sh --only ROBRecoveryWatermarkSpec`
- `bash tools/chisel/run_chisel_tests.sh --only ROBEntryBankSpec`
- `bash tools/chisel/run_chisel_tests.sh --only BROBSpec`
- `bash tools/chisel/run_chisel_tests.sh --only DispatchROBAllocatorSpec`
- `bash tools/chisel/run_chisel_tests.sh --only DecodeRenameROBPathSpec`
- `bash tools/chisel/run_chisel_recovery_cleanup_rob_probe.sh`
