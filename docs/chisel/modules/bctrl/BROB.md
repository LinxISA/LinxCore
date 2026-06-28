# BROB

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/bctrl/BID.scala`
- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/bctrl/BROB.scala`
- Chisel integration: `rtl/LinxCore/chisel/src/main/scala/linxcore/backend/DispatchROBAllocator.scala`
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

The first Chisel packet implements the metadata substrate only:

- `BID` helpers for 64-bit BID encoding, command tags, and flush masking.
- `BrobStatus`, preserving the LinxCoreModel status order.
- `BrobEntryMeta`, a typed entry payload.
- `BrobMetaTracker`, a reduced metadata tracker for allocate, scalar/engine
  completion, retire/free, query, and BID-based flush.

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
| Input | `allocTid` | `UInt` | with `allocValid` | Thread/STID owner placeholder. |
| Input | `allocPeId` | `UInt` | with `allocValid` | PE owner. |
| Input | `allocBlockType` | `UInt` | with `allocValid` | Encoded block type placeholder. |
| Input | `allocNeedsEngine` | `Bool` | with `allocValid` | True for blocks requiring engine completion. |
| Output | `allocReady` | `Bool` | ready | Selected slot is free. |
| Input | `scalarDoneValid` | `Bool` | valid | Scalar side completion. |
| Input | `scalarDoneBid` | `UInt(64.W)` | with valid | BID to update. |
| Input | `scalarTrapValid` | `Bool` | with valid | Scalar completion carries exception. |
| Input | `scalarTrapCause` | `UInt` | with valid | Exception cause. |
| Input | `engineDoneValid` | `Bool` | valid | Engine side completion. |
| Input | `engineDoneBid` | `UInt(64.W)` | with valid | BID to update. |
| Input | `engineTrapValid` | `Bool` | with valid | Engine completion carries exception. |
| Input | `engineTrapCause` | `UInt` | with valid | Exception cause. |
| Input | `retireValid` | `Bool` | valid | Free a completed BID. |
| Input | `retireBid` | `UInt(64.W)` | with valid | BID to free if completed. |
| Input | `flushValid` | `Bool` | valid | Apply BID-based flush mask. |
| Input | `flushBid` | `UInt(64.W)` | with valid | Current block BID; younger BIDs are killed. |
| Input | `queryBid` | `UInt(64.W)` | combinational | BID to inspect. |
| Output | `query` | `BrobEntryMeta` | combinational | Current slot metadata. |
| Output | `queryAllocated` | `Bool` | combinational | Query slot is live. |
| Output | `queryComplete` | `Bool` | combinational | Completion predicate for query slot. |
| Output | `allocatedMask` | `UInt(entries.W)` | combinational | Live slots. |
| Output | `completeMask` | `UInt(entries.W)` | combinational | Complete slots. |
| Output | `pendingMask` | `UInt(entries.W)` | combinational | Live but incomplete slots. |

## State

`BrobMetaTracker` stores one `BrobEntryMeta` per slot. Reset state is
`BrobStatus.Free` with zeroed metadata.

This packet does not yet implement C++ model pointers such as `allocPtr`,
`dispatchPtr`, `renamePtr`, `commitPtr`, or `nonFlushBid`; those become real
state when integrated BROB allocation and commit are promoted. The
`DispatchROBAllocator` bridge adds the first allocation cursor outside the
metadata tracker so a generated full BID can allocate BROB and ROB state
atomically.

## Logic Design

- Allocation writes BID, owner metadata, `needsEngine`, and status
  `Allocated` when the selected slot is free.
- `DispatchROBAllocator` generates a full BID with `BID.fromParts`, passes it
  to `BrobMetaTracker.allocBid`, writes the same BID into the ROB row
  `blockBid` sideband, and drives `ROBEntryBank.allocBid` from the equivalent
  ring sidecar.
- Scalar completion sets `scalarDone` and captures the first scalar exception.
  Scalar-only blocks become `Completed`.
- Engine completion sets `engineDone` and captures the first engine exception.
  Engine blocks become `Completed` only after scalar completion is also set.
- Retire frees only a `Completed` entry.
- Flush is applied after the packet's local updates and marks entries with
  full BID greater than `flushBid` as `Flushed`.

## Timing

The metadata tracker is a single-cycle state owner. Query outputs are
combinational from the registered table. Later integrated BROB work must add
explicit arbitration if multiple completion sources target the same slot in one
cycle.

## Flush/Recovery

The implemented flush rule is the strict BID mask from the LinxCore contract:
keep `bid <= flushBid`, kill `bid > flushBid`. This is intentionally full
64-bit BID order, not low-slot order.

Full LinxCoreModel `recoverBlock`, `setFlushed`, PE replay, SIMT/MTC replay,
rename rollback, TileRename release, and GPR CMAP commit effects are not in
this packet.

## Trace/Observability

This packet exposes masks and per-BID query metadata only. Future trace rows
must connect BROB allocation, completion, flush, and retire events to the
neutral QEMU/LinxCore commit adapter.

## Verification

- `chisel/src/test/scala/linxcore/bctrl/BROBSpec.scala`
- `bash tools/chisel/run_chisel_tests.sh --only BROB`
- `bash tools/chisel/run_chisel_tests.sh --only DispatchROBAllocator`
- `bash tools/chisel/build_chisel.sh`
