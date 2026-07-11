# BROB

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/bctrl/BID.scala`
- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/bctrl/BROB.scala`
- Chisel lifecycle: `rtl/LinxCore/chisel/src/main/scala/linxcore/bctrl/BlockMarkerLifecycle.scala`
- Chisel sequencing: `rtl/LinxCore/chisel/src/main/scala/linxcore/bctrl/BlockScalarDoneSequencer.scala`
- Chisel integration: `rtl/LinxCore/chisel/src/main/scala/linxcore/backend/DispatchROBAllocator.scala`
- Chisel recovery bridge: `rtl/LinxCore/chisel/src/main/scala/linxcore/recovery/FlushControl.scala`
- Previous pyCircuit owner: `rtl/LinxCore/src/bcc/bctrl/brob.py`
- Reduced block-structure owner: `rtl/LinxCore/src/bcc/block_struct/brob.py`
- Reduced block-structure RTL: `rtl/LinxCore/src/bcc/block_struct/brob_rtl.py`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/bctrl/BROB.h`
  - `model/LinxCoreModel/model/bctrl/BROB.cpp`
  - `model/LinxCoreModel/model/ModelCommon/ModelEnumDefines.h`
- Contract IDs: `LC-CHISEL-BROB-001`, `LC-CHISEL-BID-001`

## Purpose

BROB is the block-level reorder structure. It allocates BID ownership at block
start, tracks scalar and engine completion, gates block commit in BID order,
and supplies the block identity used by recovery, BISQ, TileRename, and trace.

R643 promotes the metadata substrate to a parameterized per-STID owner:

- `BID` helpers for 64-bit BID encoding, command tags, and flush masking.
- `BrobStatus`, preserving the LinxCoreModel status order.
- `BrobEntryMeta`, a typed entry payload.
- `BrobMetaTracker`, a reduced metadata tracker for allocate, scalar/engine
  completion, retire/free, query, and STID-scoped BID flush.

## Interface

### `BID`

| Function | Description |
|---|---|
| `slot(id, entries)` | Low `log2(entries)` bits used to index BROB storage. |
| `uniq(id, entries)` | High uniqueness/age bits above the slot. |
| `fromParts(uniq, slot, entries)` | Packs uniqueness and slot into a full BID. |
| `cmdTag(id)` | Returns `bid[7:0]` for backend-to-BCTRL response routing. |
| `keepOnFlush(id, flushBid)` | True when `id <= flushBid`. |
| `killOnFlush(id, flushBid)` | True when `id > flushBid`. |

### `BrobMetaTracker`

| Direction | Signal | Type | Valid/ready | Description |
|---|---|---|---|---|
| Input | `allocValid` | `Bool` | valid | Allocate metadata for `allocBid`. |
| Input | `allocBid` | `UInt(64.W)` | with `allocValid` | Full BID. Low bits select slot. |
| Input | `allocStid` | `UInt` | with `allocValid` | Selects the independent BROB lane; out-of-range STIDs are rejected. |
| Input | `allocTid` | `UInt` | with `allocValid` | Architectural thread metadata retained in the selected entry. |
| Input | `allocPeId` | `UInt` | with `allocValid` | PE owner. |
| Input | `allocBlockType` | `UInt` | with `allocValid` | Encoded block type placeholder. |
| Input | `allocNeedsEngine` | `Bool` | with `allocValid` | True for blocks requiring engine completion. |
| Output | `allocReady` | `Bool` | ready | Selected slot is reusable: `Free` or `Flushed`. |
| Input | `scalarDoneValid` | `Bool` | valid | Scalar side completion. |
| Input | `scalarDoneBid/scalarDoneStid` | mixed | with valid | Exact `(STID, full BID)` to update. |
| Input | `scalarTrapValid` | `Bool` | with valid | Scalar completion carries exception. |
| Input | `scalarTrapCause` | `UInt` | with valid | Exception cause. |
| Input | `engineDoneValid` | `Bool` | valid | Engine side completion. |
| Input | `engineDoneBid/engineDoneStid` | mixed | with valid | Exact `(STID, full BID)` to update. |
| Input | `engineTrapValid` | `Bool` | with valid | Engine completion carries exception. |
| Input | `engineTrapCause` | `UInt` | with valid | Exception cause. |
| Input | `retireValid` | `Bool` | valid | Free a completed BID. |
| Input | `retireBid/retireStid` | mixed | with valid | Exact completed block identity to free. |
| Input | `flushValid` | `Bool` | valid | Apply BID-based flush mask. |
| Input | `flushBid/flushStid/flushInclusive` | mixed | with valid | Kill a selected STID suffix; inclusive recovery also kills the pivot. |
| Input | `queryBid/queryStid` | mixed | combinational | Exact lane and BID to inspect. |
| Output | `query` | `BrobEntryMeta` | combinational | Current slot metadata. |
| Output | `queryAllocated` | `Bool` | combinational | Query slot is live. |
| Output | `queryComplete` | `Bool` | combinational | Completion predicate for query slot. |
| Output | `allocatedMask` | `UInt(entries.W)` | combinational | Live slots. |
| Output | `completeMask` | `UInt(entries.W)` | combinational | Complete slots. |
| Output | `pendingMask` | `UInt(entries.W)` | combinational | Live but incomplete slots. |
| Output | `oldestValid[stidCount]` | `Vec[Bool]` | combinational | Selected STID lane has a live block. |
| Output | `oldestBid[stidCount]` | `Vec[UInt(bidWidth.W)]` | with `oldestValid` | Lowest live full BID independently selected in each STID. |
| Output | `oldestComplete[stidCount]` | `Vec[Bool]` | with `oldestValid` | Completion predicate of that exact selected block. |

## State

`BrobMetaTracker` stores one `BrobEntryMeta` per `(STID, slot)`. Reset state is
`BrobStatus.Free` with zeroed metadata.

`BrobAllocationRecovery`, integrated under `DispatchROBAllocator`, now owns the
C++ model's per-STID allocation-tail restore. Dispatch, rename, commit,
`nonFlushBid`, and store-barrier pointers remain unimplemented.

## Logic Design

- Identity is the pair `(STID, full BID)`. Equal BID values in different STID
  lanes are legal and must never alias for completion, retire, query, or flush.
- Allocation writes BID, STID, owner metadata, `needsEngine`, and status
  `Allocated` when the selected slot is reusable. Reusable means `Free` or
  `Flushed`; a flushed slot is not live, so it must not strand later full-BID
  allocation when the allocator wraps to that physical entry.
- `DispatchROBAllocator` generates a full BID with `BID.fromParts`, passes it
  to `BrobMetaTracker.allocBid`, writes the same BID into the ROB row
  `blockBid` sideband, and drives `ROBEntryBank.allocBid` from the equivalent
  ring sidecar through `FullBidRecoveryBridge.fullBidToRobId`.
- Scalar completion sets `scalarDone` and captures the first scalar exception.
  Scalar-only blocks become `Completed`.
- Engine completion sets `engineDone` and captures the first engine exception.
  Engine blocks become `Completed` only after scalar completion is also set.
- `BlockScalarDoneSequencer` is the reduced integration owner for scalar-only
  completion followed by retire/free. It forwards scalar done in the source
  cycle and emits the matching retire one cycle later, so this metadata tracker
  observes `Completed` before the free request arrives.
- `BlockMarkerLifecycle` is the reduced source owner for marker-consume,
  scalar-redirect, scalar-created, and ROB block-last scalar-done events before
  they enter `BlockScalarDoneSequencer`.
- Retire frees only a `Completed` entry.
- Flush is applied only to `flushStid`. Ordinary accepted global flush marks
  entries with full BID greater than `flushBid` as `Flushed`; miss-predict
  recovery also marks the pivot because it names the first killed block. No BID
  comparison is made across STIDs. `Flushed` entries remain
  excluded from allocated/pending/complete masks but are accepted by
  `allocReady` so reduced block-control cleanup can reuse killed slots.
- Recovery watermark selection scans live entries independently in every STID
  lane and returns the lowest full BID plus completion state of that exact
  entry. It never compares BID age across STIDs. The downstream allocator must
  match this full BID against the full block BID stored with its selected ROB
  row before publishing a coherent BID/RID pair.

## Timing

The metadata tracker is a single-cycle state owner. Query outputs are
combinational from the registered table. Later integrated BROB work must add
explicit arbitration if multiple completion sources target the same slot in one
cycle.

## Flush/Recovery

The implemented flush rule is lane-local. `MISS_PRED_FLUSH` follows model
`recoverBlock` and kills `bid >= pivot`; accepted scalar nuke/inner/fast flush
follows the retained-target case of `setFlushed` and kills `bid > pivot`.
`BrobAllocationRecovery` restores the allocation cursor to the exact first
killed BID in the same accepted cleanup cycle.

`Flushed` is a non-live state, not a terminal allocation blocker. R127 proved
this on the CoreMark live path: scalar-return cleanup can kill younger block
slots, and the next marker allocation must be able to reuse the same physical
slot after wrap/flush bookkeeping.

`FullBidRecoveryBridge` is the first recovery handoff owner between this full
BID block surface and the ring `ROBID` consumed by `ROBEntryBank` pruning.

Full commit/dispatch/rename pointer recovery, `nonFlushBid`, PE replay,
SIMT/MTC replay, rename rollback, TileRename release, and GPR CMAP commit
effects remain outside this packet.

## Trace/Observability

This packet exposes masks and per-BID query metadata only. Future trace rows
must connect BROB allocation, completion, flush, and retire events to the
neutral QEMU/LinxCore commit adapter.

## Verification

- `chisel/src/test/scala/linxcore/bctrl/BROBSpec.scala`
- `chisel/src/test/scala/linxcore/bctrl/BlockMarkerLifecycleSpec.scala`
- `bash tools/chisel/run_chisel_tests.sh --only BlockMarkerLifecycle`
- `chisel/src/test/scala/linxcore/bctrl/BlockScalarDoneSequencerSpec.scala`
- `bash tools/chisel/run_chisel_tests.sh --only BlockScalarDoneSequencer`
- `bash tools/chisel/run_chisel_tests.sh --only BROB`
- `bash tools/chisel/run_chisel_tests.sh --only DispatchROBAllocator`
- `bash tools/chisel/run_chisel_tests.sh --only FullBidRecoveryBridge`
- `bash tools/chisel/build_chisel.sh`
