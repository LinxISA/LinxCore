# DispatchROBAllocator

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/backend/DispatchROBAllocator.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/backend/DispatchROBAllocatorSpec.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/bctrl/BROB.cpp`
  - `model/LinxCoreModel/model/bctrl/BROB.h`
  - `model/LinxCoreModel/model/bctrl/spe/SPEROB.cpp`
  - `model/LinxCoreModel/model/ModelCommon/ROBID.*`
- Related Chisel contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/bctrl/BID.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/bctrl/BROB.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/rob/ROBEntryBank.scala`
- Contract IDs: `LC-IF-CHISEL-DISPATCH-ROB-ALLOC-001`

## Purpose

`DispatchROBAllocator` is the first backend integration owner that ties block
allocation to PE ROB row allocation. It composes the Chisel `BrobMetaTracker`
and `ROBEntryBank`, generates the next full hardware BID, stores that BID in
BROB metadata, and drives the ROB row's native `allocBid` sidecar from the same
allocation event.

This is still a bring-up bridge, not full dispatch, rename, or CMT. It exists
to remove unit-test-only `ROBEntryBank.allocBid` fixtures and to make later
dispatch agents consume a real block owner.

## Interface

| Direction | Signal | Type | Valid/ready | Description |
|---|---|---|---|---|
| input | `flush` | `FlushBus` | `flush.req.valid` | ROB row flush request forwarded to `ROBEntryBank` |
| input | `allocValid` | `Bool` | `allocReady` | Requests one block/ROB row allocation |
| output | `allocReady` | `Bool` | ready | High only when both BROB slot and ROB row allocation can accept |
| output | `allocFire` | `Bool` | diagnostic | `allocValid && allocReady` |
| output | `allocBlockedByBrob` | `Bool` | diagnostic | Current BID slot is not free in BROB |
| output | `allocBlockedByRob` | `Bool` | diagnostic | BROB is ready but the ROB bank rejects the row |
| output | `allocDuplicateIdentity` | `Bool` | diagnostic | ROB duplicate `(bid,gid,rid)` rejection |
| input | `allocRow` | `CommitTraceRow` | with `allocValid` | ROB row payload; block BID sideband is overwritten by the generated BID |
| input | `allocTid` | `UInt` | with `allocValid` | BROB thread/STID metadata |
| input | `allocPeId` | `UInt` | with `allocValid` | BROB PE owner metadata |
| input | `allocBlockType` | `UInt` | with `allocValid` | Reduced block type metadata |
| input | `allocNeedsEngine` | `Bool` | with `allocValid` | BROB completion predicate metadata |
| output | `allocBlockBid` | `UInt(64.W)` by default | diagnostic | Full generated hardware BID for the next accepted allocation |
| output | `allocRobValue` | `UInt(log2(entries).W)` | diagnostic | ROB slot assigned by `ROBEntryBank` on an accepted allocation |
| input | `completeValid` / `completeRobValue` | mixed | valid | ROB completion path forwarded to `ROBEntryBank` |
| input | `deallocReady` | `Bool` | ready | ROB deallocation-ready path forwarded to `ROBEntryBank` |
| input | `block*Done*`, `blockRetire*`, `blockFlush*`, `blockQueryBid` | mixed | valid/query | Pass-through control and query surface for `BrobMetaTracker` |
| output | `blockQuery*`, `block*Mask` | mixed | diagnostic | BROB query and occupancy/completion masks |
| output | `commit*`, `dealloc*`, `flush*`, `size`, `outstandingCount`, `*Mask` | mixed | diagnostic | `ROBEntryBank` commit, recovery, and lifecycle outputs |

## State

- `blockSlot`: low BID slot cursor, reset to zero.
- `blockUniq`: high BID uniqueness cursor, reset to zero.
- `BrobMetaTracker`: block metadata state owner.
- `ROBEntryBank`: PE ROB row state owner.

## Logic Design

The allocator computes the next full BID as
`BID.fromParts(blockUniq, blockSlot)`. This mirrors the hardware BID contract:
low bits select the BROB slot, and high bits provide uniqueness and ordering.

Allocation is atomic across BROB and ROB:

- `BrobMetaTracker.allocValid` fires only when the ROB bank is ready.
- `ROBEntryBank.allocValid` fires only when the BROB slot is ready.
- `allocReady` is the conjunction of both ready signals.
- The BID cursor advances only on `allocFire`.

On allocation, the module writes the generated full BID into BROB metadata and
also overwrites `allocRow.blockBidValid/blockBid` before forwarding the row to
`ROBEntryBank`. It converts the generated BID to the ROB bank's native
`ROBID` sidecar by taking the low slot bits as `value` and the low uniqueness
bit as `wrap`. That sidecar feeds `ROBEntryBank.allocBid`; RID remains
allocated locally by `ROBEntryBank` from its allocation pointer.

`CommitTraceRow.identity` is not synthesized here. It remains the model commit
trace and duplicate-detection identity supplied by the eventual decode/dispatch
row builder.

## Timing

The allocation ready path is combinational across the two child owners, but no
child state changes unless both sides can accept. The BID cursor advances in
the same clock edge as the accepted BROB and ROB allocation.

## Flush/Recovery

ROB row flushes are forwarded to `ROBEntryBank`. BROB flush remains an explicit
full-BID input (`blockFlushValid/blockFlushBid`) because the current Chisel
recovery bus still uses ring `ROBID` metadata while the hardware block contract
uses full 64-bit BIDs. A later recovery owner must carry both surfaces or
replace the temporary bridge with a single typed full-BID recovery contract.

## Trace/Observability

The commit output is the `ROBEntryBank` monitored commit port. The allocation
bridge makes block identity visible by setting `blockBidValid` and `blockBid`
on allocated rows. Invalid fixed-width commit slots still remain the trace
adapter's responsibility.

## Verification

- `bash tools/chisel/run_chisel_tests.sh --only DispatchROBAllocator`
- `bash tools/chisel/run_chisel_tests.sh --only BROB`
- `bash tools/chisel/run_chisel_tests.sh --only ROBEntryBank`
- `bash tools/chisel/run_chisel_tests.sh --only ROBFlushPrune`
- `bash tools/chisel/run_chisel_tests.sh --only FlushControl`
- `bash tools/chisel/run_chisel_rob_bookkeeping.sh --reduced-rob`
- `python3 tools/chisel/trace_schema_adapter.py --self-test`
- `bash tools/chisel/run_chisel_qemu_crosscheck.sh --dry-run`
- `bash tools/chisel/run_chisel_reduced_rob_xcheck.sh`
- `bash tools/chisel/build_chisel.sh`

Focused tests cover atomic BROB/ROB allocation, BID cursor wrap through
uniqueness bits, blocked-allocation hold behavior for BROB fullness and ROB
duplicate identity, and Chisel elaboration of the composed module.
