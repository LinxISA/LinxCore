# FullBidRecoveryBridge

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/recovery/FlushControl.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/recovery/FullBidRecoveryBridgeSpec.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/bctrl/BROB.cpp`
  - `model/LinxCoreModel/model/bctrl/spe/SPEROB.cpp`
  - `model/LinxCoreModel/model/ModelCommon/ROBID.h`
  - `model/LinxCoreModel/model/core/FlushControl.cpp`
- Related Chisel contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/bctrl/BID.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/recovery/FlushControl.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/rob/ROBFlushPrune.scala`
- Contract IDs: `LC-CHISEL-RECOVERY-BID-001`

## Purpose

`FullBidRecoveryBridge` is the first explicit recovery handoff between the
hardware block BID contract and the current ROB row pruning contract. BROB and
block metadata consume the full hardware `blockBid`; `ROBEntryBank` and
`ROBFlushPrune` consume the ring `ROBID` carried in `FlushBus.req.bid`.

The bridge is intentionally narrow. It does not clean rename state, LSU/STQ
state, frontend restart state, or PE replay queues.

## Interface

### `FullBidFlushReq`

| Field | Type | Description |
|---|---|---|
| `valid` | `Bool` | Recovery request valid bit. |
| `typ` | `FlushType` | Flush or replay type classified by `FlushControl`. |
| `peId` | `UInt` | PE owner for PE-scoped requests. |
| `tid` | `UInt` | Thread owner. |
| `stid` | `UInt` | Scheduler/thread domain. |
| `blockBid` | `UInt(bidWidth.W)` | Full hardware BID used by BROB/block cleanup. |
| `gid` | `ROBID` | Ring group identity sidecar. |
| `rid` | `ROBID` | Ring row identity sidecar. |
| `lsId` | `ROBID` | Ring load/store sub-identity sidecar. |
| `execEngine` | `ExecEngineType` | Scalar, SIMT, MEM, or IEX_NUM-or-higher source. |
| `fetchTpcValid` | `Bool` | PE replay target-valid bit. |
| `immediateFlush` | `Bool` | Preserved for future cleanup owners. |

### `FullBidRecoveryBridgeIO`

| Direction | Signal | Type | Valid/ready | Description |
|---|---|---|---|---|
| input | `req` | `FullBidFlushReq` | `req.valid` | Full-BID recovery request. |
| output | `robFlush` | `FlushBus` | `robFlush.req.valid` | Annotated ring-ROBID request for ROB pruning. |
| output | `blockFlushValid` | `Bool` | valid | Full-BID block flush valid bit for BROB-style owners. |
| output | `blockFlushBid` | `UInt(bidWidth.W)` | with `blockFlushValid` | Full hardware BID preserved for block cleanup. |
| output | `robBid` | `ROBID` | diagnostic | Converted ring block ID. |
| output | `baseOnBid` | `Bool` | diagnostic | `FlushControl` BID-classification result. |

## State

The bridge has no registers. It is a combinational translation and annotation
owner.

## Logic Design

`FullBidRecoveryBridge.fullBidToRobId` maps a full hardware BID to the ring
`ROBID` representation:

- `ROBID.value` is `BID.slot(blockBid, entries)`.
- `ROBID.wrap` is the low bit of `BID.uniq(blockBid, entries, bidWidth)`.
- `ROBID.valid` is supplied by the caller.

The module copies the remaining request sidecars into a `FlushReq`, annotates
it with `FlushControl.annotate`, and passes the full `blockBid` through
unchanged on the block flush surface.

`DispatchROBAllocator` and `RecoveryCleanupControl` also use this bridge path
so allocation and registered cleanup intent agree on the same
full-BID-to-ring-ROBID split.

## Timing

The module is purely combinational. A future registered recovery owner should
place state at the request collection or cleanup boundary, not inside this
bridge.

## Flush/Recovery

This packet defines only the identity handoff:

- BROB-style block cleanup uses `blockFlushValid/blockFlushBid`.
- ROB row pruning uses `robFlush.req.bid`.
- `gid`, `rid`, and `lsId` remain separate ring sidecars for non-BID and
  group-scoped requests.

`RecoveryCleanupControl` is the next owner after this bridge. It registers the
selected request and exposes cleanup-intent bits for rename, LSU/STQ, frontend,
backend, PE, and tile consumers. Those consumers remain future work.

## Trace/Observability

The diagnostic `robBid`, `baseOnBid`, and full `blockFlushBid` outputs make
the split visible to generated RTL tests and future trace adapters. No commit
trace row is emitted by this module.

## Verification

- `bash tools/chisel/run_chisel_tests.sh --only FullBidRecoveryBridge`
- `bash tools/chisel/run_chisel_tests.sh --only RecoveryCleanupControl`
- `bash tools/chisel/run_chisel_tests.sh --only FlushControl`
- `bash tools/chisel/run_chisel_tests.sh --only DispatchROBAllocator`
- `bash tools/chisel/run_chisel_tests.sh --only ROBFlushPrune`
- `bash tools/chisel/run_chisel_tests.sh --only ROBEntryBank`

Focused tests cover the slot/wrap mapping, full-BID preservation, non-BID RID
separation, and Chisel elaboration of both block and ROB flush surfaces.
