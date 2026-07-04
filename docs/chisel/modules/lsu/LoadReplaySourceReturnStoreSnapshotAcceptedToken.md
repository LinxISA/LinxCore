# LoadReplaySourceReturnStoreSnapshotAcceptedToken

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplaySourceReturnStoreSnapshotAcceptedToken.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/LoadReplaySourceReturnStoreSnapshotAcceptedTokenSpec.scala`
- Integrated user: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplaySourceReturnStoreSnapshotPath.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/lsu/load_unit/ldq.cpp`
    - `LDQInfo::pickL1`
    - `LDQInfo::loadRepick`
    - `LDQInfo::handleSTQReceive`
    - `LDQInfo::waitStore`
    - `LDQInfo::handleMerge`
  - `model/LinxCoreModel/model/ModelCommon/bus/MemReqBus.h`
    - `MemReqBus::cID`
    - `MemReqBus::eID`
    - `MemReqBus::bid`
    - `MemReqBus::gid`
    - `MemReqBus::lsID`
    - `MemReqBus::peID`
    - `MemReqBus::stid`
    - `MemReqBus::tid`
  - `model/LinxCoreModel/model/ModelCommon/bus/FlushBus.h`
    - `FlushBus::match(MemReqBus)`
- Contract IDs: `LC-CHISEL-LSU-REPLAY-STQ-SNAPSHOT-ACCEPTED-TOKEN-001`

## Purpose

`LoadReplaySourceReturnStoreSnapshotAcceptedToken` stores the selected
replay-row identity after a local STQ snapshot query is accepted.

The model sequence is stateful. `LDQInfo::pickL1` chooses a waiting row,
`LDQInfo::loadRepick` marks it `LDQ_REPICK`, and `LDQInfo::handleSTQReceive`
later uses `MemReqBus.cID/eID` to find the same row. A response is accepted
only if that row is still `LDQ_REPICK`; otherwise the response is stale and is
ignored.

R397 inserts this owner inside
`LoadReplaySourceReturnStoreSnapshotPath`. The current reduced top still ties
`requestEnable=false` and `sinkReady=false`, so no live token is captured in
the generated-RTL gate. The module is present so the composite can progress
from same-cycle launch-index projection toward an accepted-query boundary
without adding another direct child to `LinxCoreFrontendFetchRfAluTraceTop`.
R399 clears the token from `LoadReplaySourceReturnStoreSnapshotResponseDrain`
`orderedConsumed`, not from the raw queue pop signal. A future stale response
drop can therefore drain the raw queue without destroying the accepted query
identity.

R408 extends the token from identity-only storage to accepted-row context
storage. The token now captures the selected row line image, valid byte mask,
and request byte mask at query issue so the later ordered response apply owner
uses delayed row context instead of the current launch selector.

R430 exposes the token's capacity, visible/resident state, capture/clear
pulses, outstanding-token blocker, and visible `cID/eID` through the composite
path boundary. This remains path-local visibility; the reduced top still does
not grow another diagnostic port pack for these signals.

R431 widens the token with the accepted load request identity/context needed
by the same precise `FlushBus` predicate used by the request and response
queues. A matching precise flush clears the resident token and suppresses
same-cycle capture so recovery cannot prune the queued LU/SU record while
leaving a stale accepted-query owner behind.

## Interface

### Inputs

| Signal | Description |
|---|---|
| `enable` | Replay-LIQ wrapper is active. |
| `flush` | Clears any resident accepted-query token. |
| `preciseFlush` | Model-shaped recovery bus used to prune a resident token whose accepted load request identity/context matches. |
| `queryIssued` | Selected-row local STQ snapshot query was accepted. |
| `selectedValid` | Selected replay-row identity is valid at query issue. |
| `selectedRepick` | Selected row is still in model-equivalent `LDQ_REPICK` state. |
| `selectedClusterId` | Selected replay-row cluster ID. |
| `selectedEntryId` | Selected replay-row entry ID. |
| `selectedBid` / `selectedGid` / `selectedLoadLsId` | Accepted load request identity copied into the token for precise prune matching. |
| `selectedPeId` / `selectedStid` / `selectedTid` | Accepted load request context copied into the token for precise prune matching. |
| `selectedLineData` | Selected row's current 64-byte line image at query issue. |
| `selectedValidMask` | Selected row's byte-valid mask at query issue. |
| `selectedRequestByteMask` | Selected row's original load request byte mask. |
| `responseConsumed` | Downstream drain owner accepted the matching ordered response. |

### Outputs

| Signal | Description |
|---|---|
| `active` | `enable && !flush`. |
| `tokenCanAccept` | No resident token is outstanding, so a new query may be accepted. |
| `tokenValid` | Stored or same-cycle bypass token is visible to identity and response matching. |
| `tokenRepick` | Token row is still in the accepted repick state. |
| `tokenClusterId` | Token cluster ID. |
| `tokenEntryId` | Token entry ID. |
| `tokenBid` / `tokenGid` / `tokenLoadLsId` | Visible token load request identity. |
| `tokenPeId` / `tokenStid` / `tokenTid` | Visible token load request context. |
| `tokenLineData` | Stored or same-cycle bypass row line image. |
| `tokenValidMask` | Stored or same-cycle bypass row valid byte mask. |
| `tokenRequestByteMask` | Stored or same-cycle bypass row request byte mask. |
| `residentTokenValid` | A token was resident before this cycle. |
| `captureCandidate` | Active accepted-query pulse is visible. |
| `captureAccepted` | Query pulse captured a selected, repick token into the empty token slot. |
| `captureBypass` | Empty-token same-cycle capture is visible immediately to response matching. |
| `clearAccepted` | Current token is consumed by an ordered response. |
| `precisePruned` | Resident token matched `preciseFlush` and will be cleared. |
| `blockedByDisabled` | Query pulse arrived while disabled. |
| `blockedByFlush` | Query pulse arrived during flush. |
| `blockedByPreciseFlush` | Query pulse arrived during a precise-prune cycle. |
| `blockedByNoSelected` | Query pulse arrived without selected identity. |
| `blockedByStaleRow` | Query pulse selected a row that was not in repick state. |
| `blockedByOutstandingToken` | Query pulse attempted to replace an unconsumed token. |

## State

The module owns one token register:

```text
valid
repick
clusterId
entryId
bid
gid
loadLsId
peId
stid
tid
lineData
validMask
requestByteMask
```

It does not own raw STQ response queueing, SCB-order evidence, wait-store row
mutation, or returned-data merge.

## Logic Design

The owner captures only an accepted query whose selected row is valid and still
repick:

```text
captureAccepted = enable && !flush &&
                  queryIssued && selectedValid && selectedRepick &&
                  !residentTokenValid
```

When the token slot is empty, a captured token also bypasses to the current
cycle so a same-cycle response can match it. If an older token is resident,
the older token remains the response-match authority and a new query is
diagnosed as `blockedByOutstandingToken`.

R408 applies the same resident-or-bypass rule to the captured row context:
`tokenLineData`, `tokenValidMask`, and `tokenRequestByteMask` come from the
resident token when one exists, otherwise from the selected row context. These
outputs are meaningful only when `tokenValid` is true.

`responseConsumed` clears the currently visible token only for ordered response
consumption. Flush has priority over capture and clear.

When `preciseFlush.req.valid` is asserted, the module converts the resident
token identity/context into the same `STQFlushPruneEntry` shape used by the
local request/response queues. A match clears the token. The precise-prune
cycle also closes token capacity and suppresses capture, even when the token
does not match, matching the queue policy that hides heads and suppresses
enqueue/dequeue during recovery.

## Timing

`tokenCanAccept` is based only on registered token occupancy, so it can gate
the upstream query sink without feeding response matching back into query
validity. The current composite uses it to prevent multiple outstanding local
STQ snapshot tokens.

## Flush/Recovery

Flush clears the resident token and suppresses token visibility. A query pulse
visible during flush reports `blockedByFlush`.

Precise recovery selectively clears a resident token with matching
`stid`, optional PE/thread scope, and BID/GID/LSID ordering. It does not clear
unmatched tokens, but it does block same-cycle capture while the recovery
cycle is active.

## Deferred Owners

- Live raw STQ response source into the R398 queue.
- Live stale-row proof into the R399 response drain.
- Multi-cluster LDQ identity storage beyond the reduced launch-index
  projection.
- Registered wait-store replay mutation and returned-data merge from the R407
  apply intent using this delayed row context.
- Replacement or queueing policy for multiple outstanding local snapshot
  queries.
- Live promotion of `requestEnable` once response and row-mutation owners are
  stable.

## Verification

Focused gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadReplaySourceReturnStoreSnapshotAcceptedToken
bash tools/chisel/run_chisel_tests.sh --only LoadReplaySourceReturnStoreSnapshotPath
bash tools/chisel/run_chisel_tests.sh --only LoadReplaySourceReturnStoreSnapshotIdentityMatch
bash tools/chisel/run_chisel_tests.sh --only LoadReplaySourceReturnStoreSnapshotResponseMatch
FETCH_REDUCED_STORE_REPLAY_LIQ=1 BUILD_DIR=generated/r408x bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh
```

Reference tests cover accepted-token capture, same-cycle bypass plus consume,
resident-token preservation, response consumption, disabled/flush/stale
diagnostics, outstanding-token blocking, row-context capture/clear, precise
flush pruning, and Chisel elaboration.
