# LoadReplaySourceReturnStoreSnapshotPath

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplaySourceReturnStoreSnapshotPath.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/LoadReplaySourceReturnStoreSnapshotPathSpec.scala`
- Integrated user: `rtl/LinxCore/chisel/src/main/scala/linxcore/top/LinxCoreFrontendFetchRfAluTraceTop.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/lsu/load_unit/ldq.cpp`
    - `LDQInfo::loadRepick`
    - `LDQInfo::handleL1Lookup`
    - `LDQInfo::handleSTQReceive`
    - `LDQInfo::waitStore`
    - `LDQInfo::handleMerge`
    - `LDQInfo::pickL1`
  - `model/LinxCoreModel/model/lsu/store_unit/store_unit.cpp`
    - `StoreUnit::handleLoadReq`
  - `model/LinxCoreModel/model/lsu/store_unit/stq.cpp`
    - `STQ::lookupForLoad`
  - `model/LinxCoreModel/model/ModelCommon/bus/MemReqBus.h`
    - `MemReqBus::cID`
    - `MemReqBus::eID`
- Child Chisel contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplaySourceReturnStoreSnapshotQueryIssue.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplaySourceReturnStoreSnapshotSelectedIdentity.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplaySourceReturnStoreSnapshotAcceptedToken.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplaySourceReturnStoreSnapshotResponseQueue.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplaySourceReturnStoreSnapshotIdentityMatch.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplaySourceReturnStoreSnapshotResponseMatch.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplaySourceReturnStoreSnapshotResponseDrain.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplaySourceReturnStoreSnapshotEvidence.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplaySourceReturnStoreSnapshotReadyControl.scala`
- Contract IDs: `LC-CHISEL-LSU-REPLAY-STQ-SNAPSHOT-PATH-001`

## Purpose

`LoadReplaySourceReturnStoreSnapshotPath` composes the replay-LIQ local STQ
snapshot source-return owners into one LSU boundary.

R395 replaces four separate `LinxCoreFrontendFetchRfAluTraceTop` child
instances with this single path instance. The path contains the R392 query
owner, the R394 `cID/eID` identity matcher, the R393 response-order owner, the
R391 evidence classifier, and the R390 ready-control owner. This relieves the
oversized top constructor while preserving every existing top diagnostic.

R396 adds `LoadReplaySourceReturnStoreSnapshotSelectedIdentity` inside the
composite. The top now passes the existing reduced LIQ launch index and
`repickMask` into the path, and the composite can use that projection as a
single-cluster selected-row identity. Raw selected-row identity inputs still
live at the path boundary for the later full LDQ identity owner.

R397 adds `LoadReplaySourceReturnStoreSnapshotAcceptedToken` inside the same
composite. Query issue is now capacity-qualified by the token slot, and the
identity/response/evidence chain uses the accepted token rather than the
floating same-cycle selected identity. The current top still keeps live request
and sink readiness disabled, so this remains an internal future-promotable
boundary.

R398 adds `LoadReplaySourceReturnStoreSnapshotResponseQueue` between the raw
STQ response inputs and identity/response matching. The queue preserves model
`lookup_su_lu_q` FIFO order, supports empty-slot same-cycle bypass so the R397
same-cycle path remains valid, and only drains when the downstream ordered
response is consumed. The current top still ties raw response inputs false.

R399 adds `LoadReplaySourceReturnStoreSnapshotResponseDrain` after response
matching. The drain is now the single owner that tells the queue to pop a head:
ordered responses clear the accepted token, while a future explicit stale-head
signal may drop a model-stale response without clearing that token. The current
top ties stale-head evidence false until replay row-state ownership is
available.

The current top keeps the path response side live-disabled. It ties
`requestEnable`, `sinkReady`, raw STQ response, SCB return, wait-store, and
data-valid inputs false, and it ties stale-head evidence false, so
`storeSnapshotReady` still forwards the legacy resident-store snapshot
readiness. Those raw inputs live at the path boundary instead of inside the
module so the identity and response child owners remain a real composite
boundary and can later be promoted without another direct top child instance.

## Interface

### Inputs

| Signal | Description |
|---|---|
| `enable` | Replay-LIQ wrapper is active. |
| `flush` | Store/replay flush suppresses live source-return evidence. |
| `requestEnable` | Future live arm for issuing and consuming selected-row STQ snapshot evidence. Current top ties this false. |
| `launchValid` | A selected replay row would need local STQ source-return qualification. |
| `sinkReady` | Future STQ lookup sink readiness for selected-row query issue. Current top ties this false. |
| `selectedIdentityEnable` | Selects the reduced LIQ launch-index projection instead of the raw selected-row identity inputs. |
| `selectedLaunchIndex` | Reduced LIQ selected launch slot used by `LoadReplaySourceReturnStoreSnapshotSelectedIdentity`. |
| `selectedRepickMask` | Reduced LIQ rows already resident in `Repick`; used to reject pre-repick or stale selected rows. |
| `selectedValid` | Future raw selected replay-row identity is valid when `selectedIdentityEnable=false`. |
| `selectedRepick` | Future raw selected row is still in the model-equivalent `LDQ_REPICK` state. |
| `responseValidIn` | Future raw STQ response is visible to the R398 queue. |
| `selectedClusterId` | Future selected replay-row cluster ID. |
| `selectedEntryId` | Future selected replay-row entry ID. |
| `responseClusterId` | Future STQ response cluster ID from `MemReqBus.cID`. |
| `responseEntryId` | Future STQ response entry ID from `MemReqBus.eID`. |
| `responseHeadStale` | Future row-state evidence proving the raw queue head targets a row that is no longer repick. Current top ties this false. |
| `scbReturned` | Future SCB source-return evidence arrived before STQ response acceptance. |
| `waitStoreIn` | Future raw response asks the row to wait on a store. |
| `dataValidIn` | Future raw response carries mergeable store data. |
| `legacySnapshotReady` | Current reduced-top readiness bit preserved from the resident-store snapshot path. |

### Outputs

| Signal | Description |
|---|---|
| `storeSnapshotReady` | Final readiness bit feeding `LoadReplaySourceReturnReadiness.storeSnapshotReady`. |
| `control*` | Ready-control active/request/evidence/legacy/live/blocker diagnostics. |
| `evidence*` | Selected-row local STQ evidence diagnostics from the evidence classifier. |
| `queryIssue*` | Selected-row query issue diagnostics from the query owner. |

The top consumes the existing `control*`, `evidence*`, and `queryIssue*`
diagnostics through unchanged top IO names. Response-match and identity-match
diagnostics remain module-local until an external wrapper consumes them or the
top is split further.

## State

The module is mostly combinational, but R397 adds one accepted-query token
register inside `LoadReplaySourceReturnStoreSnapshotAcceptedToken` and R398
adds one raw-response FIFO inside
`LoadReplaySourceReturnStoreSnapshotResponseQueue`. R399 adds a combinational
response-drain owner. The path still owns no LDQ/LIQ mutation, live row-state
stale source, wait-store state, or data merge state.

## Logic Design

The path preserves the model ordering as a chain of small owners:

```text
QueryIssue.queryIssued
  -> SelectedIdentity.selectedValid/selectedRepick/cID/eID
  -> AcceptedToken.tokenValid/tokenRepick/cID/eID
  -> ResponseQueue.headValid/head cID/eID/waitStore/dataValid
  -> IdentityMatch.responseMatchesSelected
  -> ResponseMatch.responseValid/waitStore/dataValid
  -> ResponseDrain.orderedConsumed/staleDropped/dequeueReady
  -> Evidence.snapshotRequired/snapshotValid
  -> ReadyControl.storeSnapshotReady
```

The model sends a selected `LDQ_REPICK` row to STQ, matches the returned
`MemReqBus.cID/eID` back to the row, ignores stale non-repick rows, requires
SCB return before STQ return, and then either rewaits on `wait_store` or merges
returned data before `pickL1` can call `returnData`.

The R396 selected-identity projection maps the reduced selected LIQ slot to
`clusterId=0` and `entryId=launchIndex`, then qualifies it with the current
`repickMask`. This is only a reduced single-cluster surrogate. The real
accepted-query selected-row token and full `MemReqBus.cID/eID` storage remain
deferred.

The R397 accepted token stores the projected or raw selected identity only
after `QueryIssue.queryIssued` fires and no older token is resident. It also
bypasses an empty-slot capture for same-cycle response matching, then clears
when `ResponseMatch.responseValid` accepts the ordered response. This preserves
one outstanding local STQ snapshot query without feeding response acceptance
back into query validity.

The R398 response queue stores raw STQ responses in FIFO order before identity
matching. An empty queue can bypass an accepted response to the matcher in the
same cycle, but resident queue occupancy drains only after
`ResponseMatch.responseValid`. Head visibility is independent of downstream
ready so the path does not form a response-valid/dequeue-ready combinational
cycle.

The R399 response drain consumes queue heads for ordered responses and reserves
an explicit stale-head drop path. It does not infer stale from `blockedByNoMatch`;
future multi-token ownership may make a nonmatching head valid for another
token. A future row-state owner must drive `responseHeadStale` from the
model-equivalent `entry.fsm != MTC_LDQ_REPICK` predicate before stale drops are
enabled in the top.

The current top tie-offs keep `queryIssued=false`, `responseValid=false`, and
`requestEnable=false`, so the live chain does not affect replay launch. The
same signals remain visible inside the path for future promotion.

## Timing

The path is a same-cycle diagnostic boundary in front of
`LoadReplaySourceReturnReadiness`. Live query valid/ready, raw response
sourcing, selected-row identity storage, and row mutation must be added before
`requestEnable` can be asserted.

## Flush/Recovery

Flush clears active query, identity, response, and evidence predicates. Legacy
mode still forwards `legacySnapshotReady`, matching the pre-R395 disabled-live
behavior.

## Deferred Owners

- Live raw STQ response source and precise queued-response flush pruning.
- Live stale-head row-state evidence into `responseHeadStale`.
- Full selected replay-row identity storage from replay-LIQ residency beyond
  the reduced launch-index projection.
- Live SCB return evidence source.
- Stateful wait-store replay mutation and returned-data merge.
- Live promotion of `requestEnable` after query, response, and row-state owners
  are stable.

## Verification

Focused gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadReplaySourceReturnStoreSnapshotPath
bash tools/chisel/run_chisel_tests.sh --only LoadReplaySourceReturnStoreSnapshotResponseDrain
bash tools/chisel/run_chisel_tests.sh --only LoadReplaySourceReturnStoreSnapshotResponseQueue
bash tools/chisel/run_chisel_tests.sh --only LoadReplaySourceReturnStoreSnapshotAcceptedToken
bash tools/chisel/run_chisel_tests.sh --only LoadReplaySourceReturnStoreSnapshotSelectedIdentity
bash tools/chisel/run_chisel_tests.sh --only LoadReplaySourceReturnStoreSnapshotIdentityMatch
bash tools/chisel/run_chisel_tests.sh --only LoadReplaySourceReturnStoreSnapshotResponseMatch
bash tools/chisel/run_chisel_tests.sh --only LoadReplaySourceReturnStoreSnapshotQueryIssue
bash tools/chisel/run_chisel_tests.sh --only LoadReplaySourceReturnStoreSnapshotEvidence
bash tools/chisel/run_chisel_tests.sh --only LoadReplaySourceReturnStoreSnapshotReadyControl
bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop
FETCH_REDUCED_STORE_REPLAY_LIQ=1 BUILD_DIR=generated/r398x bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh
```

Reference tests cover legacy readiness preservation, dormant query/response
behavior, future live accepted-token response completion, flush handling,
disabled behavior, and Chisel elaboration with the selected-identity,
accepted-token, response-queue, response-drain, identity, and response child
owners present in the composite module.
