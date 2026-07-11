# DispatchROBAllocator

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/backend/DispatchROBAllocator.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/backend/DispatchROBAllocatorSpec.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/bctrl/BROB.cpp`
  - `model/LinxCoreModel/model/bctrl/BROB.h`
  - `model/LinxCoreModel/model/bctrl/spe/SPEROB.cpp`
  - `model/LinxCoreModel/model/bctrl/spe/SPERename.cpp`
  - `model/LinxCoreModel/model/ModelCommon/ROBID.*`
- Related Chisel contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/common/BlockMarkerBundles.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/common/TULinkBundles.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/bctrl/BID.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/bctrl/BROB.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/recovery/FlushControl.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/rob/ROBEntryBank.scala`
- Contract IDs: `LC-IF-CHISEL-DISPATCH-ROB-ALLOC-001`

## Purpose

`DispatchROBAllocator` is the first backend integration owner that ties block
allocation to PE ROB row allocation. It composes the Chisel `BrobMetaTracker`
and `ROBEntryBank`, generates the next full hardware BID, stores that BID in
BROB metadata, and drives the ROB row's native `allocBid` sidecar from the same
allocation event.

It also forwards the allocation-time T/U cleanup source sidecars into
`ROBEntryBank`: native `gid`, `peId/stid`, row-owned `tSeq/uSeq`, the T/U
destination class, and `isLast`. This keeps ROB source and retire-source
publication in the row owner while
`ScalarTURenameBridge` supplies the live T/U rename snapshots in the reduced
decode/rename/ROB path.
R66 also forwards the ROB bank's deallocated block-last `(bid,gid)` candidate
so later relation-clean scheduling can wait for serialized retire commands
instead of firing block cleanup at commit time.
R76 splits allocation from rename-produced sidecar visibility. Decode enqueue
can reserve BROB and ROB rows atomically, while a later `renameUpdate*`
handshake patches the reserved ROB row after `ScalarTURenameBridge` accepts the
queued instruction.
R103 forwards the ROB bank's full `deallocBlockLastBlockBid` sideband so
reduced block lifecycle owners can drive BROB scalar completion with the same
64-bit BID that allocation wrote into the row.
R104 separates two block-allocation cases needed by marker lifecycle: scalar
rows can reuse an already-active full block BID without allocating a BROB
entry, while a marker-only `BSTART` can allocate a BROB entry without reserving
a scalar ROB row.
R169 forwards allocation-time marker row sidecars into `ROBEntryBank` and
returns the ROB bank's width-wide `deallocBlockMarkerRetireSource` vector. The
allocator does not consume those marker retire sources; it preserves the
boundary between row storage and future BCTRL marker-row retirement policy.
R287 also forwards the decode-time memory-order sidecar into `ROBEntryBank`:
the row's pre-increment `lsID` snapshot plus load/store classification. The
allocator returns the bank's `commitMemoryOrder` vector unchanged so LSU
owners can build `SPEROB::getRetireID`-style retire watermarks without changing
the commit trace schema.
R321 forwards the read-only ROB row status lookup request and result between
the backend path and `ROBEntryBank`. The allocator does not interpret the
result; it only preserves the current ROB row image boundary for downstream
LSU/IEX admission logic.
R547 forwards `deallocHoldMask` to `ROBEntryBank` so top-level replay-return
owners can keep retired load rows resident while a delayed LRET return still
needs the current ROB row image for `IEX::setMemData`. The allocator does not
generate the hold policy; it only preserves the ROB-bank boundary.
R643 replaces the global block cursor with one parameterized cursor per STID,
passes STID on every BROB lifecycle event, and returns the deallocated
block-last STID beside its full BID. This matches LinxCoreModel lane-local BROB
allocation and explicitly rejects cross-STID BID ordering.
The allocator can elaborate multiple lanes independently, but the current
`DecodeRenameROBPath` composition is guarded to one STID until GPR mapQ block
commit also keys entries by STID.

This is still a bring-up bridge, not full dispatch, rename, or CMT. It exists
to remove unit-test-only `ROBEntryBank.allocBid` fixtures and to make later
dispatch agents consume a real block owner.

## Interface

| Direction | Signal | Type | Valid/ready | Description |
|---|---|---|---|---|
| input | `flush` | `FlushBus` | `flush.req.valid` | ROB row flush request forwarded to `ROBEntryBank` |
| input | `allocValid` | `Bool` | `allocReady` | Requests one block/ROB row allocation |
| input | `allocUsesExistingBlock` | `Bool` | with `allocValid` | Scalar row uses `allocExistingBlockBid` and does not allocate a new BROB entry |
| input | `allocExistingBlockBid` | `UInt(64.W)` by default | with `allocValid` | Active full block BID reused by a scalar ROB row |
| output | `allocReady` | `Bool` | ready | High when the ROB row can accept and, if a new scalar block is needed, the BROB slot can accept |
| output | `allocFire` | `Bool` | diagnostic | `allocValid && allocReady` |
| output | `allocBlockedByBrob` | `Bool` | diagnostic | Current BID slot is not free in BROB |
| output | `allocBlockedByRob` | `Bool` | diagnostic | BROB is ready but the ROB bank rejects the row |
| output | `allocDuplicateIdentity` | `Bool` | diagnostic | ROB duplicate `(bid,gid,rid)` rejection |
| input | `allocRow` | `CommitTraceRow` | with `allocValid` | ROB row payload; block BID sideband is overwritten by the generated BID |
| input | `allocGid` | `ROBID(entries)` | with `allocValid` | Native group ID sidecar forwarded to `ROBEntryBank` for relation-cmap grouping |
| input | `allocTid` | `UInt` | with `allocValid` | BROB thread/STID metadata |
| input | `fullBidLookupRequest` | `ROBFullBidLookupRequest` | valid | Exact resident-row recovery key forwarded to the real ROB. |
| output | `fullBidLookup` | `ROBFullBidLookupResult` | diagnostic/source | Allocator-owned full generation sideband and identity blockers, widened to `bidWidth`. |
| input | `allocStid` | `UInt` | with `allocValid` | ROB T/U cleanup source STID sidecar |
| input | `allocLsId` | `UInt(lsidWidth.W)` | with `allocValid` | Decode-time pre-increment `lsID` snapshot forwarded to `ROBEntryBank` |
| input | `allocIsLoad`, `allocIsStore` | `Bool` | with `allocValid` | Load/store classification forwarded to the ROB memory-order sidecar |
| input | `allocTSeq` / `allocUSeq` | `ROBID(mapQDepth)` | with `allocValid` | ROB row T/U cleanup source sequence sidecars |
| input | `allocTUDstValid` / `allocTUDstKind` | mixed | with `allocValid` | ROB row T/U destination ownership sidecar |
| input | `allocIsLast` | `Bool` | with `allocValid` | Native block-last sidecar forwarded to relation-cmap retire-source publication |
| input | `allocMarkerBoundary`, `allocMarkerStop` | `Bool` | with `allocValid` | Marker-row classification sidecars forwarded to `ROBEntryBank` |
| input | `allocMarkerBoundaryKind` | `BoundaryKind.Type` | with `allocValid` | Decoded marker boundary kind forwarded to `ROBEntryBank` |
| input | `allocMarkerBoundaryTarget` | `UInt(pcWidth.W)` | with `allocValid` | Decoded marker target forwarded to `ROBEntryBank` |
| input | `allocPeId` | `UInt` | with `allocValid` | BROB PE owner metadata and ROB retired-row bank sidecar |
| input | `allocBlockType` | `UInt` | with `allocValid` | Reduced block type metadata |
| input | `allocNeedsEngine` | `Bool` | with `allocValid` | BROB completion predicate metadata |
| output | `allocBlockBid` | `UInt(64.W)` by default | diagnostic | Full generated hardware BID for the next accepted allocation |
| output | `allocRobValue` | `UInt(log2(entries).W)` | diagnostic | ROB slot assigned by `ROBEntryBank` on an accepted allocation |
| output | `allocRobWrap` | `Bool` | diagnostic | ROB allocation epoch bit paired with `allocRobValue` for native RID stamping |
| input | `blockAllocOnlyValid` | `Bool` | `blockAllocOnlyReady` | Requests a BROB-only allocation for a marker-owned block start |
| input | `blockAllocOnlyStid` | `UInt` | with `blockAllocOnlyValid` | Selects the marker block's independent STID allocation cursor. |
| output | `blockAllocOnlyReady` | `Bool` | ready | High when no scalar allocation is using the allocator and BROB can accept |
| output | `blockAllocOnlyFire` | `Bool` | diagnostic | `blockAllocOnlyValid && blockAllocOnlyReady` |
| output | `blockAllocOnlyBid` | `UInt(64.W)` by default | diagnostic | Full generated hardware BID assigned to the accepted BROB-only allocation |
| input | `renameUpdateValid` | `Bool` | `renameUpdateReady` | Post-rename update request for a previously allocated ROB row |
| output | `renameUpdateReady` | `Bool` | ready | Forwarded `ROBEntryBank` update readiness; independent of BROB state |
| output | `renameUpdateAccepted` | `Bool` | diagnostic | ROB row update accepted this cycle |
| output | `renameUpdateIgnored` | `Bool` | diagnostic | ROB row update request could not apply |
| input | `renameUpdateRid` | `ROBID(entries)` | with `renameUpdateValid` | Native RID of the reserved row to update |
| input | `renameUpdateRow` | `CommitTraceRow` | with `renameUpdateValid` | Post-rename commit row payload |
| input | `renameUpdateTSeq` / `renameUpdateUSeq` | `ROBID(mapQDepth)` | with `renameUpdateValid` | Post-rename T/U sequence sidecars |
| input | `renameUpdateTUDstValid` / `renameUpdateTUDstKind` | mixed | with `renameUpdateValid` | Post-rename T/U destination ownership |
| input | `completeValid` / `completeRobValue` | mixed | valid | ROB completion path forwarded to `ROBEntryBank` |
| input | `completeRowValid` / `completeRow` | mixed | with accepted completion | Optional execute/LSU completion payload forwarded to `ROBEntryBank`; when invalid, completion preserves the row stored by allocation/rename update |
| input | `deallocReady` | `Bool` | ready | ROB deallocation-ready path forwarded to `ROBEntryBank` |
| input | `deallocHoldMask` | `UInt(entries.W)` | policy | Per-ROB-slot deallocation hold mask forwarded unchanged to `ROBEntryBank` |
| input | `statusLookupValid`, `statusLookupRid` | mixed | valid | Read-only native RID status query forwarded to `ROBEntryBank`. |
| output | `statusLookup` | `ROBRowStatusLookupResult` | diagnostic/source | Current-row status lookup result forwarded without interpretation. |
| input | `commitTraceLookupValid`, `commitTraceLookupRid`, `commitTraceLookupSourceTraceEnable` | mixed | valid/policy | Read-only native RID row-payload query forwarded to `ROBEntryBank`. |
| output | `commitTraceLookup` | `ROBRowCommitTraceLookupResult` | diagnostic/source | Current-row commit-trace provider result forwarded without interpretation. |
| input | `block*Done*`, `blockRetire*`, `blockFlush*`, `blockQuery*` | mixed | valid/query | Exact full BID plus STID pass-through surface for `BrobMetaTracker`. |
| output | `blockQuery*`, `block*Mask` | mixed | diagnostic | BROB query and occupancy/completion masks |
| output | `commit*`, `dealloc*`, `flush*`, `size`, `outstandingCount`, `*Mask` | mixed | diagnostic | `ROBEntryBank` commit, recovery, and lifecycle outputs |
| output | `commitMemoryOrder` | `Vec(commitWidth, ROBMemoryOrderCommit)` | diagnostic/source | ROB commit-window native ID plus LSID sidecars forwarded from `ROBEntryBank` |
| output | `robTULinkSource*` | mixed | diagnostic/source | ROB row candidate for `TULinkFlushSourceSelector.robSource` |
| output | `deallocTURetireSource` | `Vec(commitWidth, TULinkRetireSource)` | diagnostic/source | ROB deallocation-row source vector for `TULinkRetireCommandPath` |
| output | `deallocBlockMarkerRetireSource` | `Vec(commitWidth, BlockMarkerRetireSource)` | diagnostic/source | ROB deallocation-row marker source vector for future marker-row retirement |
| output | `deallocBlockLast*` | mixed | diagnostic/source | First block-last row freed by the ROB deallocation walk, including native `(bid,gid)` and full `blockBid` |

## State

- `blockSlot(stid)`: per-STID low BID slot cursor, reset to zero.
- `blockUniq(stid)`: per-STID high BID uniqueness cursor, reset to zero.
- `BrobMetaTracker`: block metadata state owner.
- `ROBEntryBank`: PE ROB row state owner.

## Logic Design

The allocator computes each lane's next full BID as
`BID.fromParts(blockUniq(stid), blockSlot(stid))`. This mirrors the hardware BID contract:
low bits select the BROB slot, and high bits provide uniqueness and ordering.

Allocation has three reduced modes:

- A scalar row without an active block allocates BROB and ROB together.
- A scalar row with `allocUsesExistingBlock` allocates only the ROB row and
  stamps it with `allocExistingBlockBid`.
- A marker-only block start uses `blockAllocOnlyValid` to allocate only BROB
  metadata and returns the generated BID through `blockAllocOnlyBid`.

For the scalar-new-block case, `BrobMetaTracker.allocValid` fires only when the
ROB bank is ready, and `ROBEntryBank.allocValid` fires only when the BROB slot
is ready. For scalar existing-block allocation, ROB readiness alone controls
`allocReady`. Scalar allocation has priority over marker-only allocation when
both are presented in the same cycle. The BID cursor advances only when a new
BROB entry is accepted, either by scalar allocation or marker-only allocation.

On allocation, the module writes the generated full BID into BROB metadata and
overwrites `allocRow.blockBidValid/blockBid` before forwarding the row to
`ROBEntryBank`. When `allocUsesExistingBlock` is set, the row sideband and
`ROBEntryBank.allocBid` use `allocExistingBlockBid` instead. The conversion to
the ROB bank's native `ROBID` sidecar takes the low slot bits as `value` and
the low uniqueness bit as `wrap` through
`FullBidRecoveryBridge.fullBidToRobId`; RID remains allocated locally by
`ROBEntryBank` from its allocation pointer. R111 exposes both `allocRobValue`
and `allocRobWrap` so `DecodeRenameROBPath` can stamp the exact native RID
into the queued row; the slot value alone is insufficient after the reduced
8-entry ROB wraps. BROB identity is `(STID, full BID)`; equal full BID values
in different STIDs are valid and are not globally ordered.

The T/U cleanup and retire-source sidecars are forwarded unmodified to
`ROBEntryBank` at allocation time, but the reduced R76 path intentionally
drives the rename-produced sidecars as zero/no-destination during reservation.
`renameUpdate*` then forwards the accepted `ScalarTURenameBridge` row and
`SPERename`-equivalent `tSeq/uSeq` snapshot to the same ROB slot. This matches
the C++ model's two-phase effect: `SPEROB::allocROB` stores the instruction
before `dec_ren_q`, and later `SPERename::Rename` mutates the shared
instruction object. The Chisel row stores values, so the later mutation is an
explicit update handshake.

Marker classification and boundary metadata are forwarded with the same
allocation handshake. The allocator treats them as row image sidecars, not as
control decisions; full marker-row retirement remains a downstream BCTRL owner.

`DecodeRenameROBPath` drives `allocGid`, `allocPeId`, and `allocIsLast` from
the decoded row/reduced owner metadata at reservation time so ROB deallocation
can publish relation-cmap retire sources without depending on commit-trace
identity fields. In the current reduced path, `allocPeId` remains PE0 for most
packets, but it is still stored as row-owned metadata for later non-zero PE
routing.

`CommitTraceRow.identity` is not synthesized here. It remains the model commit
trace and duplicate-detection identity supplied by the eventual decode/dispatch
row builder.

## Timing

The allocation ready path is combinational across the two child owners, but no
child state changes unless both sides can accept. The BID cursor advances in
the same clock edge as the accepted BROB and ROB allocation.

## Flush/Recovery

ROB row flushes are forwarded to `ROBEntryBank`; the resulting
`robTULinkSource*` outputs feed the live T/U cleanup selector composition.
`deallocTURetireSource` is forwarded from `ROBEntryBank` into
`DecodeRenameROBPath`, where `TULinkRetireCommandPath` serializes it into the
live T/U rename retire port while preserving the row's PE/STID bank identity.
`deallocBlockLast*` is also forwarded as the
future `CleanCMAP` scheduling source, but this allocator does not issue the
cleanup command because relation-cmap retire serialization must finish first.
The full `deallocBlockLastBlockBid` is forwarded separately from the ring BID
so BROB scalar-completion and retire paths can keep using full 64-bit hardware
BID ordering.
R169 also forwards `deallocBlockMarkerRetireSource` unchanged from the ROB bank.
The vector stays width-wide across the allocator boundary so a future serializer
or lifecycle consumer can process every retired marker row in the deallocation
window.
BROB flush remains an explicit full-BID input
(`blockFlushValid/blockFlushBid`) because the current Chisel recovery bus
still uses ring `ROBID` metadata while the hardware block contract uses full
64-bit BIDs. `FullBidRecoveryBridge` now owns the shared conversion used by
both allocation and recovery. A later cleanup owner must still connect rename,
LSU/STQ, frontend redirect, and BROB pointer restoration side effects.

## Trace/Observability

The commit output is the `ROBEntryBank` monitored commit port. The allocation
bridge makes block identity visible by setting `blockBidValid` and `blockBid`
on allocated rows. Invalid fixed-width commit slots still remain the trace
adapter's responsibility.

## Verification

- `bash tools/chisel/run_chisel_tests.sh --only DispatchROBAllocator`
- `bash tools/chisel/run_chisel_tests.sh --only FullBidRecoveryBridge`
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
duplicate identity, separation of decode-time allocation from rename-time row
update, ROB T/U source IO elaboration through the composed module,
ROB deallocation retire-source and block-last-candidate IO elaboration through
the composed module, full block-BID propagation from ROB block-last deallocation,
marker-only BROB allocation plus scalar active-BID reuse, ROB RID wrap
publication, marker-retire source IO forwarding, and Chisel elaboration of the
composed module.
