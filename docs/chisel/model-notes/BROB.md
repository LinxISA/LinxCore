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
entry. At R103, legal `BSTART`/`BSTOP` marker rows were still skip-only in the
reduced live-fetch RF/ALU gate, so marker-owned old/current-active BID
retirement remained a future packet.

R104 adds the reduced marker-owned lifecycle needed by the live fetch RF/ALU
gate. Model evidence from `BCtrlUnit::RunFetchStage5` shows that a block-start
marker allocates the new block identity, following scalar rows inherit the
current block BID, and `BSTOP` is stamped with the current active block rather
than allocating a new BROB entry. The Chisel reduced path now allocates a
BROB-only entry on a consumed `BSTART`, records that full BID as the active
block, stamps following scalar ROB rows with the active BID without allocating
another BROB entry, and pulses scalar done for the old/current active BID when
a later `BSTART` or `BSTOP` marker is consumed. This still uses marker consume
as the reduced retire point; full marker ROB rows, per-STID active block state,
and recovery-exact marker retirement remain future work.

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

R651 promotes the allocation-only state into `BrobOrderState`. Each STID owns
an independent allocation tail, commit head, and bounded live count. Recovery
validates the first-killed identity against that live window, truncates tail
and count, and never moves the commit head. `MISS_PRED_FLUSH` uses the reported
BID as the first killed block; retained-target nuke/inner/fast flush uses the
target successor. The same applied decision controls `BrobMetaTracker`
pruning. Metadata resolves the exact owner-provided head instead of selecting
an unsigned minimum full BID.

Completed heads arbitrate fairly across STIDs and cross a one-entry
flow-through irrevocable slot before retirement. Completion is persistent
metadata, so consecutive completion events are not the retirement authority
and can wait behind downstream backpressure. Metadata free, commit-head
advance, live-count decrement, rename commit enqueue, and public retire fire
share one handshake. The current Chisel block interface retires one block per
cycle; LinxCoreModel's configurable `bctrl_bandwidth`, non-flush/store-barrier
frontiers, SIMT/MTC-specific replay, and same-cycle resolve/flush priority
remain future work.

## Non-Flush Oldest

`BROBState::CheckNonFlushBid` starts from `commitPtr` and advances through live
entries while `CheckNonFlushOldestBid` says they cannot be flushed. R652 keeps
that ISA-neutral consecutive-prefix mechanism but makes the boundary explicit:
`BrobNonFlushFrontier` publishes `valid`, exact head, bounded prefix count,
youngest safe BID, and first blocked BID independently per STID. It rejects the
model routine's ambiguous first-unsafe return convention; an unsafe head
produces no valid frontier.

The promoted Chisel predicate is intentionally conservative: only completed,
exception-free metadata is safe. `ReducedStoreCommitFreeOwner` retains a ROB
store commit with its full block BID until it lies inside the selected STID's
prefix, then authorizes the STQ `Wait -> Commit` transition. Recovery clears
that retained event with the store/BROB state. Branch-resolved scalar early
release and issued TLOAD/TSTORE release remain future producer-backed
extensions.

## Store-Range Delivery

`BlockROB::deliveryStoreID` walks from each STID's `sbarPtr`, assigns the
current `sbarID` to the exact resident row, and advances only through rows with
a certain `storeCount`. R653 promotes that mechanism as
`BrobStoreRangeState`, using the owner head/live-count window for bounded
modular age. Scalar decode stores increment the exact block count; scalar-done
freezes it. The explicit-count input preserves the model's template/tile path
without treating a decode hint as authority.

Accepted recovery clears the killed suffix and restores the first killed
row's saved start ID when range assignment had already passed it. This range
state is independent from `BrobNonFlushFrontier`: assigning a store range does
not authorize STQ commitment or SCB admission.

## Open Questions

- The architecture docs still list BROB same-cycle resolve-vs-flush and
  commit-vs-flush priority as open issues.
- Chisel now models per-STID allocation-tail, commit-head, live-count,
  conservative strong non-flush prefix, and contiguous store-range arrays.
  Early-safe predicates and full BCTRL/BISQ interaction remain open. The
  model's BROB-local dispatch/rename fields are
  declarations without active pointer-update behavior and are not promoted as
  target owners.
- Recovery still has two BID surfaces in Chisel: BROB flush consumes full
  hardware BID, while ROB row pruning consumes ring `ROBID`. The first handoff
  is explicit in `FullBidRecoveryBridge`; R651 restores the live order window,
  while early non-flush predicates, store-barrier allocation, rename rollback,
  and complete LSU/STQ cleanup remain future work.
- TileRename release and GPR MAPQ-to-CMAP commit remain tied to future
  integrated BROB/rename work.
