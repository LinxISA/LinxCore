# FlushControl

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/recovery/FlushControl.scala`
- Tests:
  - `rtl/LinxCore/chisel/src/test/scala/linxcore/recovery/FlushControlSpec.scala`
  - `rtl/LinxCore/chisel/src/test/scala/linxcore/recovery/FullBidRecoveryBridgeSpec.scala`
  - `rtl/LinxCore/chisel/src/test/scala/linxcore/recovery/RecoveryCleanupControlSpec.scala`
- Previous pyCircuit owner: deferred; recovery ownership is currently derived
  from model and architecture documents.
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/core/FlushControl.h`
  - `model/LinxCoreModel/model/core/FlushControl.cpp`
  - `model/LinxCoreModel/model/ModelCommon/bus/FlushBus.h`
  - `model/LinxCoreModel/model/ModelCommon/ModelEnumDefines.h`
- Contract IDs: `LC-CHISEL-FLUSH-001`

## Purpose

`FlushControl` defines the recovery request type system and the oldest-signal
arbitration used by flush and replay selection. The first Chisel module is a
combinational `FlushOlderSelector`; it lets ROB/BROB and future PE recovery
logic share the same C++ model-derived age and priority rules.

`FullBidRecoveryBridge` shares this file and defines the first explicit
handoff from full hardware block BID to the ring `ROBID` sidecar consumed by
ROB row pruning.

`RecoveryCleanupControl` is the first registered cleanup-intent owner after
selection. It classifies selected flush/replay requests into BCTRL, rename,
backend, ROB, LSU/STQ, tile, PE fanout, and frontend restart intent bits.

## Interface

### `FlushReq`

| Field | Type | Description |
|---|---|---|
| `valid` | `Bool` | Request valid bit from the producing queue. |
| `typ` | `FlushType` | Miss-predict, PE replay, nuke, inner, fast replay, fast flush, or SIMT inner flush. |
| `peId` | `UInt` | PE owner for PE/thread-scoped recovery. |
| `tid` | `UInt` | Thread owner. |
| `stid` | `UInt` | Scheduler/thread domain. Different `stid` values do not arbitrate. |
| `bid` | `ROBID` | Block ROB identity. |
| `gid` | `ROBID` | Group identity for vector/MTC paths. |
| `rid` | `ROBID` | Micro-instruction ROB identity. |
| `lsId` | `ROBID` | Load/store sub-identity used by non-BID flush checks. |
| `execEngine` | `ExecEngineType` | Scalar, SIMT, MEM, or IEX_NUM-or-higher. |
| `fetchTpcValid` | `Bool` | PE replay without this bit becomes BID-based. |
| `fetchTpc` | `UInt(64.W)` | Exact non-block-based recovery restart target. |
| `immediateFlush` | `Bool` | Preserved for future `CheckFlush`; unused by `FlushOlderSelector`. |

### `FlushBus`

| Field | Type | Description |
|---|---|---|
| `req` | `FlushReq` | Original request. |
| `baseOnBid` | `Bool` | Recovery compares BID only. |
| `baseOnGroup` | `Bool` | Recovery is group-scoped. |
| `baseOnPE` | `Bool` | Recovery is PE-scoped. |
| `baseOnThread` | `Bool` | Recovery is thread-scoped. |
| `simtReplay` | `Bool` | Backend should replay SIMT/vector paths. |
| `mtcReplay` | `Bool` | Backend should replay MTC paths. |

### `FlushOlderSelector`

| Direction | Signal | Type | Valid/ready | Description |
|---|---|---|---|---|
| Input | `src` | `FlushBus` | combinational | Candidate older request. |
| Input | `dst` | `FlushBus` | combinational | Candidate request to cancel or merge. |
| Input | `oldestBid` | `ROBID` | combinational | Current oldest block for the source `stid`. |
| Output | `srcOlder` | `Bool` | combinational | True when `src` is older than or higher priority than `dst`. |

### `FullBidRecoveryBridge`

| Direction | Signal | Type | Valid/ready | Description |
|---|---|---|---|---|
| Input | `req` | `FullBidFlushReq` | `req.valid` | Recovery request with full hardware `blockBid` and ring sub-ID sidecars. |
| Output | `robFlush` | `FlushBus` | `robFlush.req.valid` | Annotated flush request for ROB row pruning. |
| Output | `blockFlushValid` | `Bool` | valid | Full-BID flush valid for BROB/block cleanup owners. |
| Output | `blockFlushBid` | `UInt` | with valid | Full hardware BID, passed through unchanged. |
| Output | `robBid` | `ROBID` | diagnostic | `blockBid` converted to ring ROBID. |
| Output | `baseOnBid` | `Bool` | diagnostic | BID-classification result from `FlushControl.annotate`. |

### `RecoveryCleanupControl`

| Direction | Signal | Type | Valid/ready | Description |
|---|---|---|---|---|
| Input | `req` | `FullBidFlushReq` | `req.valid && reqReady` | Selected full-BID recovery request. |
| Output | `reqReady` | `Bool` | ready | One-entry cleanup-intent register can accept a request. |
| Input | `intentReady` | `Bool` | ready | Downstream cleanup consumer accepted the registered intent. |
| Output | `intent` | `RecoveryCleanupIntent` | `intent.valid` | Registered cleanup fanout intent. |

## State

This packet has no internal registers. Future stateful recovery work will add
`flushSignal`, `replaySignal`, `peSignal[]`, enable bits, and report-queue
drain behavior from the C++ model.

## Logic Design

`FlushControl.annotate` maps a `FlushReq` into a `FlushBus` using the model's
`getSignal` helpers. `FlushControl.checkOlder` implements the C++ model's
priority tree:

- reject different `stid`;
- resolve same-BID priority conflicts;
- resolve same-BID/RID non-BID conflicts;
- apply PE replay special cases after same-BID conflicts are resolved;
- otherwise use ROBID age, with the oldest block special-case for BID-based
  arbitration.

`FullBidRecoveryBridge.fullBidToRobId` maps a full hardware BID to the ring
`ROBID` representation by taking the low slot bits as `value` and the low
uniqueness bit as `wrap`. This helper is also used by
`DispatchROBAllocator`, so allocation and recovery share the same BID split.

`RecoveryCleanupControl` follows the model `select` fanout after a request has
won arbitration: global flush drives BCTRL/rename flush plus frontend restart;
global replay and PE-scoped replay drive BCTRL/rename replay; every accepted
intent drives backend, PE/ROB, report-queue, LSU/STQ, and tile cleanup hooks.

## Timing

`FlushOlderSelector` is purely combinational. Stateful recovery selection must
register requests at the future owner boundary and must not create same-cycle
loops from backend flush fanout back into report queues.

## Flush/Recovery

This module family does not mutate queue or rename state. It tells selection
logic whether one request should cancel or dominate another request, exposes
the full-BID handoff for block and ROB cleanup owners, and now provides a
registered cleanup-intent boundary. Actual queue cleanup, rename/BROB recovery,
PE fanout, vector replay, and MTC replay consumers are deferred.

## Trace/Observability

No trace rows are emitted by this packet. Future recovery events should expose
typed block, RID, PE, thread, and request-type fields for the QEMU and
LinxCoreModel cross-check adapters.

## Verification

- `chisel/src/test/scala/linxcore/recovery/FlushControlSpec.scala` mirrors the
  LinxCoreModel `getSignal` and `CheckOlder` decision table.
- `chisel/src/test/scala/linxcore/recovery/FullBidRecoveryBridgeSpec.scala`
  covers full-BID-to-ring-ROBID mapping and bridge elaboration.
- `chisel/src/test/scala/linxcore/recovery/RecoveryCleanupControlSpec.scala`
  covers model lane classification and cleanup-intent elaboration.
- `bash tools/chisel/run_chisel_tests.sh --only RecoveryCleanupControl`
- `bash tools/chisel/run_chisel_tests.sh --only FullBidRecoveryBridge`
- `bash tools/chisel/run_chisel_tests.sh --only FlushControl`
- `bash tools/chisel/build_chisel.sh`
- `bash tools/chisel/run_chisel_verilator_lint.sh`
