# LoadReplayDestination

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayDestination.scala`
- Integrated users:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/ReducedLoadWaitReplaySlot.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/ReducedLoadReplayRelaunchQueue.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/ReducedLoadReplayLiqAllocAdapter.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadInflightQueue.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadInflightLaunchSelect.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnLretPayload.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/iex/iex.cpp`
    - `IEX::setMemWakeup`
    - `IEX::setMemData`
  - `model/LinxCoreModel/model/ModelCommon/SimInstInfo.cpp`
    - `SimInstInfo::GenMemReq`
- Contract IDs: `LC-CHISEL-LSU-REPLAY-DST-001`

## Purpose

`LoadReplayDestination` is the compact one-destination sideband carried by the
reduced replay-return path. LinxCoreModel does not recover load destinations
from the returned `MemReqBus` alone: `IEX::setMemWakeup` and `IEX::setMemData`
look up the ROB instruction by returned RID and use its `pdsts_` vector.

The reduced Chisel path does not yet enqueue a real IEX LRET packet or publish
mem wakeup. This sideband preserves the renamed destination observed on the
held load uop at wait capture time so the later LRET/wakeup owner has a named
payload boundary instead of reconstructing destination tags after replay.

## Interface

| Field | Description |
|---|---|
| `valid` | Destination is present on the captured load. |
| `kind` | Chisel `DestinationKind` (`Gpr`, `T`, `U`, or `None`). |
| `archTag` | Architectural reg6 tag. |
| `relTag` | Local T/U relative tag when applicable. |
| `physTag` | Renamed physical destination tag used by future wakeup/writeback owners. |
| `oldPhysTag` | Previous physical tag sideband from rename. |

## Logic Design

The bundle is pure payload. `LoadReplayDestination.none` produces a disabled
payload with `DestinationKind.None` and zero tags. Replay owners forward the
bundle only under their existing valid/accepted predicates:

1. `ReducedScalarAluExecute` exposes the E-stage load uop destination while
   `loadLookupValid` is true.
2. `ReducedLoadWaitReplaySlot` captures that payload with the wait-store load.
3. `ReducedLoadReplayRelaunchQueue` and `ReducedLoadReplayLiqAllocAdapter`
   preserve it across pending replay and LIQ allocation.
4. `LoadInflightQueue` stores it in the LIQ row.
5. `LoadInflightLaunchSelect` republishes it for the selected return row.
6. `LoadReplayReturnLretPayload` emits it only when scalar return data is valid.

## Deferred Owners

- Multi-destination load-pair/vector/tile payloads.
- Real IEX LRET enqueue payload type.
- Ready-table write, RF load-writeback, and issue wakeup fanout.
- Recovery pruning of replay destination payloads beyond the existing row
  owner flush.

## Verification

Focused gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only ReducedLoadWaitReplaySlot
bash tools/chisel/run_chisel_tests.sh --only ReducedLoadReplayRelaunchQueue
bash tools/chisel/run_chisel_tests.sh --only ReducedLoadReplayLiqAllocAdapter
bash tools/chisel/run_chisel_tests.sh --only LoadInflightQueue
bash tools/chisel/run_chisel_tests.sh --only LoadInflightLaunchSelect
bash tools/chisel/run_chisel_tests.sh --only ReducedLoadReplayLiqAllocPath
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnLretPayload
bash tools/chisel/run_chisel_tests.sh --only ReducedScalarAluExecute
bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop
```
