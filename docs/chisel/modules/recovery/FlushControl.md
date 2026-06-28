# FlushControl

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/recovery/FlushControl.scala`
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

## Timing

`FlushOlderSelector` is purely combinational. Stateful recovery selection must
register requests at the future owner boundary and must not create same-cycle
loops from backend flush fanout back into report queues.

## Flush/Recovery

This module does not perform a flush. It only tells selection logic whether one
request should cancel or dominate another request. Actual queue cleanup,
rename/BROB recovery, PE fanout, vector replay, and MTC replay are deferred.

## Trace/Observability

No trace rows are emitted by this packet. Future recovery events should expose
typed block, RID, PE, thread, and request-type fields for the QEMU and
LinxCoreModel cross-check adapters.

## Verification

- `chisel/src/test/scala/linxcore/recovery/FlushControlSpec.scala` mirrors the
  LinxCoreModel `getSignal` and `CheckOlder` decision table.
- `bash tools/chisel/run_chisel_tests.sh --only FlushControl`
- `bash tools/chisel/build_chisel.sh`
- `bash tools/chisel/run_chisel_verilator_lint.sh`
