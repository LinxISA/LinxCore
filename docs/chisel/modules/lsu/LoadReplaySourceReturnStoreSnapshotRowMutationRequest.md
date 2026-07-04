# LoadReplaySourceReturnStoreSnapshotRowMutationRequest

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplaySourceReturnStoreSnapshotRowMutationRequest.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/LoadReplaySourceReturnStoreSnapshotRowMutationRequestSpec.scala`
- Integrated user: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplaySourceReturnStoreSnapshotPath.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/mtccore/lsu/load_unit/ldq_cluster.cpp`
    - `MTCLUEntryInfo::rewait`
  - `model/LinxCoreModel/model/mtccore/lsu/load_unit/ldq.cpp`
    - `MtcLDQInfo::handleSTQReceive`
    - `MtcLDQInfo::waitStore`
    - `MtcLDQInfo::handleMerge`
- Contract IDs: `LC-CHISEL-LSU-REPLAY-STQ-SNAPSHOT-ROW-MUTATION-REQUEST-001`

## Purpose

`LoadReplaySourceReturnStoreSnapshotRowMutationRequest` names the boundary
between the R409 row-state plan and the future registered `LoadInflightQueue`
row writer.

The C++ model mutates exactly one target LDQ row when an ordered STQ response
still points at a row in `MTC_LDQ_REPICK`: it sets `stqRnt`, either records
wait-store state and calls `rewait`, or merges/no-ops returned data into that
same row. This module keeps that single-row mutation shape explicit before the
live row writer exists.

R410 is still dormant in the composite path. The path wires `liveEnable=false`,
so `requestValid` and the request payload remain zero in generated-top
fixtures. Candidate diagnostics still prove whether the R409 plan has exactly
one LIQ target and whether the future live enable is the only remaining
request blocker.

## Interface

| Signal | Description |
|---|---|
| `enable` / `flush` | Gate all candidate and request outputs. |
| `liveEnable` | Future arm for allowing a candidate to become a row mutation request. The R410 path integration ties this false. |
| `planValid` | R409 row-state plan is valid for an ordered STQ response. |
| `targetMask` | One-hot LIQ row target from `LoadReplaySourceReturnStoreSnapshotResponseApply`. |
| `setWaitStatus` / `keepRepickStatus` | Future status-write intent for wait-store rewait or continued repick. |
| `clearReturnState` | Future split return-state write clears SCB/STQ state for rewait. |
| `lineWrite` / `waitStoreWrite` | Future row-data and wait-store-state write enables. |
| `nextWaitStore*` | Wait-store state copied into the future row write. |
| `nextLineData` / `nextValidMask` / `nextDataComplete` | Final row data image from the plan. |
| `nextScbReturned` / `nextStqReturned` / `nextStoreSourceReturned` | Final split source-return state from the plan. |
| `candidate*` | Plan target diagnostics before the live request arm. |
| `targetReady` | Candidate has a nonzero, exactly one-hot LIQ target. |
| `request*` | Live mutation request and target index/mask. These are zero while `liveEnable=false`. |
| `statusWrite` / `returnStateWrite` / `lineWriteOut` / `waitStoreWriteOut` | Future row-writer enables, gated by `requestValid`. |
| `blockedBy*` | Disabled, flush, missing plan, missing target, and live-disabled diagnostics. |
| `invalid*` | Multi-target, write-without-plan, wait-store-without-wait-status, inconsistent split-source, and conflicting-status diagnostics. |

## Logic Design

The module is combinational:

```text
candidateValid = enable && !flush && planValid
targetReady    = candidateValid && PopCount(targetMask) == 1
requestValid   = targetReady && liveEnable
```

Candidate outputs expose the target mask, target count, and encoded target
index when the plan is active. Request outputs are separately gated by
`liveEnable`; this keeps future row-write payloads unambiguous and prevents a
dormant diagnostic candidate from looking like an active LIQ mutation.

The invalid diagnostics guard the shape expected from `RowStatePlan`:

- no row write should be requested without `planValid`;
- the target mask must be exactly one-hot;
- `nextWaitStore` must pair with `setWaitStatus`;
- `nextStoreSourceReturned` must imply both split return bits;
- wait-store and keep-repick status writes must not be asserted together.

## Deferred Owners

- Registered `LoadInflightQueue` row mutation.
- Real row-carried `scbRnt`/`stqRnt` fields in `LoadInflightRow`.
- A live promotion control that can set `liveEnable` only after stale-response
  and split source-return policy are row-owned.

## Verification

Focused gate:

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadReplaySourceReturnStoreSnapshotRowMutationRequest
```

Reference tests cover live one-hot wait-store requests, live-disabled
candidates, no-target and multi-target blockers, disabled/flush/no-plan
blockers, invalid payload combinations, and Chisel elaboration.
