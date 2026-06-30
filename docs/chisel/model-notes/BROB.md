# BROB Model Notes

## Source Mapping

- LinxCoreModel: `model/LinxCoreModel/model/bctrl/BROB.h`
- LinxCoreModel: `model/LinxCoreModel/model/bctrl/BROB.cpp`
- LinxCoreModel: `model/LinxCoreModel/model/ModelCommon/ModelEnumDefines.h`
- Current pyCircuit owner: `rtl/LinxCore/src/bcc/bctrl/brob.py`
- Current reduced block-structure model: `rtl/LinxCore/src/bcc/block_struct/brob.py`
- Current reduced block-structure RTL: `rtl/LinxCore/src/bcc/block_struct/brob_rtl.py`
- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/bctrl/BID.scala`
- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/bctrl/BROB.scala`
- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/backend/DispatchROBAllocator.scala`
- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/recovery/FlushControl.scala`

## Model Contract

`BlockROB` is the block-order retirement structure for BCTRL. It stores one
entry per block and carries the block identity, header, block command pointer,
PE owner, status, exception metadata, ACC rename metadata, and performance
state.

The model has per-STID state:

- `allocPtr`: next allocation point from block decode.
- `dispatchPtr`: block dispatch point.
- `renameStartPtr` and `renamePtr`: block rename progress.
- `commitPtr`: oldest block retirement point.
- `nonFlushBid`: oldest block that cannot be flushed.
- `size`: live block count.
- `sbarPtr` and `sbarID`: block-level store-barrier ordering state.

## Status Values

The C++ model defines these `BlockStatus` values in order:

```text
FREE, ALLOCATED, DISPATCHED, RENAMING, RENAMED, ISSUED, PAR_RUNNING,
RUNNING, COMPLETED, RETIRED, NEEDFLUSH, NEEDREPLAY, FLUSHED, MISPRED,
EXCEPTION, TERMINATE
```

The Chisel `BrobStatus` enum preserves this ordering.

## Allocation

`allocBlock` increments `next[stid].allocPtr`, increments `size`, writes the
current allocation position into `header->bSeq`, `header->bid`, `cmd->bid`, and
`entry[id].id`, then marks the entry `ALLOCATED` unless an existing running
status must be preserved. The C++ model still uses `ROBID` ring identity for
these pointers.

The Chisel packet adds hardware-facing BID helpers for the current RTL contract:

- `BID` is 64 bits.
- For default 128 entries, `slot_id = bid[6:0]`.
- `uniq = bid[63:7]`.
- `cmd_tag = bid[7:0]`.
- Flush-by-BID uses full unsigned BID ordering: keep `bid <= flush_bid`, kill
  `bid > flush_bid`.

`DispatchROBAllocator` is the first Chisel bridge that makes this BID a live
source for ROB allocation. It keeps a BID cursor, writes the generated full BID
into `BrobMetaTracker`, stamps the same full BID into the commit row
`blockBid` sideband, and converts it into the ring `ROBID` sidecar passed to
`ROBEntryBank.allocBid`. `FullBidRecoveryBridge` owns the same conversion on
the recovery path so BROB cleanup can keep using full BIDs while ROB pruning
uses ring `ROBID`.

## Completion And Commit

The C++ model's `completeBlock` marks a live block `COMPLETED` and wakes block
issue dependents. `commitBlock` walks from `commitPtr` up to
`bctrl_bandwidth`, retiring only oldest `COMPLETED` entries. `BlockCOMPLETED`
advances `commitPtr`, decrements `size`, frees the entry, retires related
rename resources, and publishes block retire events.

R103 model-maintenance audit keeps the next Chisel packet focused on the split
between marker consumption and real block lifecycle. The reduced live-fetch
RF/ALU gate currently preserves legal `BSTART`/`BSTOP` rows as DUT-only skip
slots. That is useful frontend evidence, but it is not `scalarDone` semantics.
The model path to preserve before wider QEMU prefixes is:

- `BSTART` belongs to the new block, while retiring it marks scalar done for
  the old active block.
- `BSTOP` retiring marks scalar done for the current active block.
- `SPEROB::commit` moves completed uops to `INST_RETIRED`.
- `SPEROB::dealloc` releases retired rows in order, stops at a block-last row,
  and calls `CommitLast`.
- `SPEROB::CommitBlock` deallocates rows for the completed block, calls
  `SetBlockComplete`, runs `CleanCMAP`, and only then publishes
  `ReportLocalRegBlockCommit`.
- `BlockROB::commitBlock` can retire only oldest `COMPLETED` block entries.

R103 implements the first reduced Chisel sideband for this path. The ROB bank
now exposes the full 64-bit `blockBid` for a deallocated block-last row, and
`DecodeRenameROBPath` drives BROB scalar completion from that full BID before
issuing a one-cycle-later retire pulse. `BrobMetaTracker` also rejects stale
same-slot scalar, engine, or retire events unless the full BID matches the live
entry. Legal `BSTART`/`BSTOP` marker rows are still skip-only in the reduced
live-fetch RF/ALU gate, so marker-owned old/current-active BID retirement is a
future packet.

The reduced pyCircuit block-structure model expresses the Chisel-facing
metadata contract as:

- scalar blocks complete when scalar completion is observed;
- engine blocks complete only after scalar and engine completion;
- exception payloads are carried until oldest-block retire;
- retire frees only a completed entry.

## Flush And Recovery

The C++ model has two important recovery paths:

- `recoverBlock(MISS_PRED_FLUSH)` rolls `allocPtr` back to the reported BID and
  marks the reported BID and younger entries `FLUSHED`, while preserving the
  resolving block metadata needed by the fetch unit.
- `setFlushed` handles nuke/inner/fast flushes, resets affected block metadata,
  adjusts allocation/rename pointers, and keeps or restores the oldest/current
  block depending on `baseOnBid` and immediate-flush rules.

The first Chisel packet implements only the reusable BID mask rule for metadata
state: entries with full BID greater than `flushBid` are marked `Flushed`;
entries with `bid <= flushBid` survive. Full `recoverBlock`, `setFlushed`,
SIMT/MTC-specific replay, and same-cycle resolve/flush priority remain future
work.

## Non-Flush Oldest

`BROBState::CheckNonFlushBid` starts from `commitPtr` and advances through live
entries while `CheckNonFlushOldestBid` says they cannot be flushed. The current
Chisel metadata tracker does not implement this pointer yet.

## Open Questions

- The architecture docs still list BROB same-cycle resolve-vs-flush and
  commit-vs-flush priority as open issues.
- The Chisel packet does not yet model per-STID pointer arrays or full
  BCTRL/BISQ dispatch interaction.
- Recovery still has two BID surfaces in Chisel: BROB flush consumes full
  hardware BID, while ROB row pruning consumes ring `ROBID`. The first handoff
  is explicit in `FullBidRecoveryBridge`; full BROB pointer restoration,
  rename rollback, and LSU/STQ cleanup remain future work.
- TileRename release and GPR MAPQ-to-CMAP commit remain tied to future
  integrated BROB/rename work.
