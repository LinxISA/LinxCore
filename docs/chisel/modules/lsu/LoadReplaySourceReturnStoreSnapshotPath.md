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
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplaySourceReturnStoreSnapshotRequestControl.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplaySourceReturnStoreSnapshotRequestPayload.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplaySourceReturnStoreSnapshotRequestQueue.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplaySourceReturnStoreSnapshotRequestSink.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplaySourceReturnStoreSnapshotLookup.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplaySourceReturnStoreSnapshotResponsePayload.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplaySourceReturnStoreSnapshotRawResponseSource.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplaySourceReturnStoreSnapshotQueryIssue.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplaySourceReturnStoreSnapshotSelectedIdentity.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplaySourceReturnStoreSnapshotAcceptedToken.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplaySourceReturnStoreSnapshotResponseQueue.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplaySourceReturnStoreSnapshotIdentityMatch.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplaySourceReturnStoreSnapshotResponseMatch.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplaySourceReturnStoreSnapshotResponseHeadState.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplaySourceReturnStoreSnapshotResponseDrain.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplaySourceReturnStoreSnapshotResponseApply.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplaySourceReturnStoreSnapshotRowStatePlan.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplaySourceReturnStoreSnapshotRowMutationRequest.scala`
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

R400 adds `LoadReplaySourceReturnStoreSnapshotResponseHeadState` before the
R399 drain. In the current reduced single-cluster topology it proves a visible
raw response head stale only when `clusterId=0`, `entryId` is in range, and
`selectedRepickMask(entryId)` is clear. Unsupported cluster or entry IDs do
not become stale by default.

R401 adds `LoadReplaySourceReturnStoreSnapshotRequestControl` before the R392
query owner. It keeps future raw STQ lookup sink readiness separate from
accepted-query token capacity and forwards only gated sink readiness to query
issue.

R402 adds `LoadReplaySourceReturnStoreSnapshotRequestPayload` after query
issue. The top already had selected row identity, PC, address, size, and byte
mask diagnostics from `ReducedLoadReplayLiqAllocPath`; the composite now
copies that selected row shape into a typed request payload for the future
local STQ lookup queue. The current top still keeps live request issue
disabled.

R403 adds `LoadReplaySourceReturnStoreSnapshotRequestQueue` after the R402
payload. Query issue now sees request-queue enqueue capacity, while the
path-level `sinkReady` drains the queue head for the future raw store-unit
sink. The current top still keeps `requestEnable=false`, so the queue remains
dormant in generated-RTL fixtures.

R404 adds `LoadReplaySourceReturnStoreSnapshotRequestSink` after the R403
request queue. The sink consumes the visible request head only when the raw
store-unit sink and the existing response queue are both ready, then emits a
typed `lookup_su_lu_q` response identity into the R398 response queue. Raw
external responses keep priority over sink-generated responses. The current top
still keeps `requestEnable=false` and `sinkReady=false`, so the sink remains
dormant in generated-RTL fixtures. Resident STQ byte lookup and response
sideband generation are added by R405; replay-row mutation remains deferred.

R405 adds `LoadReplaySourceReturnStoreSnapshotLookup` beside the R404 sink.
The lookup consumes the visible request head and the current `STQEntryBankRow`
image, reuses `ResidentStoreForwardStoreSnapshot` plus `LoadStoreForwarding`,
and supplies wait-store and response-data sidebands to the sink. The current
top passes the existing reduced STQ rows into the composite but still keeps
`requestEnable=false` and `sinkReady=false`, so no live request is consumed in
generated-RTL fixtures.

R406 widens the sink-to-response-queue boundary with
`LoadReplaySourceReturnStoreSnapshotResponsePayloadBundle`. The composite now
preserves wait-store BID/RID/LSID/PC plus raw store-data mask/data from the
resident lookup through the ordered response FIFO. Response matching and
evidence still consume only `cID/eID`, `waitStore`, and response-visible
`dataValid`; LIQ/LDQ row mutation remains deferred.

R407 adds `LoadReplaySourceReturnStoreSnapshotResponseApply` after the R399
drain. It observes only ordered-consumed response heads that still target a
reduced repick row, then emits the model-equivalent apply intent:
`stqRnt`, wait-store rewait, data merge, or no-data return. The current path
exports these as diagnostics only; it does not write `LoadInflightQueue` row
state yet.

R408 widens the R397 accepted-query token with the selected row line image,
valid byte mask, and request byte mask. `ResponseApply` now computes merge
intent from that accepted-token context, not from a zero placeholder or the
current launch selector.

R409 adds `LoadReplaySourceReturnStoreSnapshotRowStatePlan` after
`ResponseApply`. The plan converts the ordered response apply event into the
final future row-state branch: wait-store rewait clears return/data state and
records wait-store identity, while data/no-data responses keep the row repick
and produce split `scbRnt/stqRnt` diagnostics. This is still a diagnostic
plan; no registered LIQ row mutation is enabled.

R410 adds `LoadReplaySourceReturnStoreSnapshotRowMutationRequest` after the
row-state plan. The request owner joins the plan with the R407 target mask,
requires exactly one LIQ row target, and shapes the status, return-state,
line-data, and wait-store write enables for the future registered row writer.
R417 promotes the request owner's `liveEnable` to an explicit path input named
`rowMutationLiveEnable`. The current reduced top still ties that input false,
but it wires the source-shaped request payload onward to
`ReducedLoadReplayLiqAllocPath` so the downstream bridge and native LIQ
mutation boundary are now structurally connected.

R419 extends the R400 response-head proof with reduced row-valid and
row-SCB-returned masks from `ReducedLoadReplayLiqAllocPath`. The path still
drops stale heads from the model-equivalent not-repick proof, but response
matching can now use the targeted LIQ row's `scbReturned` bit as the
SCB-before-STQ ordering proof when the response head targets a valid reduced
row. The top also instantiates this composite with the same LIQ depth as the
reduced allocator so row masks are not truncated at the response-head boundary.

R420 widens the raw external response input boundary to the full
`LoadReplaySourceReturnStoreSnapshotResponsePayloadBundle` shape: raw data
validity, wait-store identity, data mask, and data now enter the same response
queue payload used by the request-sink generated response. The current top
still ties all raw response inputs inactive, so this is a structural promotion
for future `lookup_su_lu_q` wiring rather than a live behavior change.

R421 inserts `LoadReplaySourceReturnStoreSnapshotRawResponseSource` between
those raw response inputs and the response queue. The new owner copies the full
payload only when `rawResponseLiveEnable` is asserted; otherwise it reports the
candidate as live-disabled and leaves the response enqueue port available to
the local request-sink generated response. The current top ties
`rawResponseLiveEnable=false`.

R422 adds load request identity fields to raw and sink-generated response
payloads. This follows LinxCoreModel's `lookup_su_lu_q` lifetime: the store
unit returns the same load `MemReqBus` after STQ lookup, and
`MtcLDQInfo::flush` removes queued responses by `FlushBus::match` on that load
identity. The packet deliberately does not implement selective pruning yet,
because the response path still needed the remaining request context sidecars
before a `FlushBus` matcher owner could apply the model predicate.

R423 adds the missing returned-load context fields (`peId/stid/tid`) to the
selected request payload, raw response boundary, sink-generated response, and
response FIFO. The reduced top drives selected context from its single
`io.peId/io.threadId` inputs and keeps dormant raw response context tied to
zero while `rawResponseLiveEnable=false`. Selective request/response pruning
still remains deferred to a separate `FlushBus` matcher owner.

R424 wires a precise `FlushBus` into the request and response queues. Each
queue applies the model-shaped `FlushBus::match(MemReqBus)` predicate to its
resident records, suppresses same-cycle enqueue/dequeue during the recovery
cycle, removes matches, and compacts survivors.

R425 wires the reduced top's scalar backend cleanup into this path with the
same MemReq-shaped LSID override used by ResolveQ. Frontend flush,
start/restart, feature-disable, and marker-only cleanup still drive the path's
hard `flush`, while scalar redirects with a valid reduced load LSID drive
`preciseFlush` so resident request/response records can be pruned selectively.

R426 exposes the request and response FIFO precise-prune masks, prune counts,
and enqueue-blocked diagnostics through the path and reduced-top diagnostic
bundles. This does not change queue behavior; it makes the R425 live cleanup
observable before request or raw-response live arms are promoted.

R427 routes the same reduced row SCB-return proof used by `ResponseMatch` into
`LoadReplaySourceReturnStoreSnapshotRowStatePlan`. This keeps the model
`ASSERT(entry.scbRnt)` prerequisite visible at the future row-mutation plan
boundary instead of reporting a false missing-SCB diagnostic when the reduced
row mask already proves `scbRnt`.

The current top keeps the path response side live-disabled. It ties
`requestEnable`, `rowMutationLiveEnable`, `rawResponseLiveEnable`, `sinkReady`,
raw STQ response, SCB return, wait-store, data-valid, raw-data, wait-store
identity, response request identity/context, and data-payload inputs false or
zero, and it ties external stale-head evidence false, so `storeSnapshotReady`
still forwards the legacy resident-store snapshot readiness. The reduced
`selectedRepickMask`, `selectedRowValidMask`, and `selectedRowScbReturnedMask`
now feed head-state proof inside the path, but no raw response is visible in
the top. Those raw inputs live at the path boundary instead of inside the
module so the identity and response child owners remain a real composite
boundary and can later be promoted without another direct top child instance.

## Interface

### Inputs

| Signal | Description |
|---|---|
| `enable` | Replay-LIQ wrapper is active. |
| `flush` | Hard source-return path clear for frontend flush, start/restart, feature-disable, and marker-only backend cleanup. |
| `preciseFlush` | Model-shaped `FlushBus` forwarded to the request and response queues for selective resident pruning. The reduced top drives scalar redirect cleanup here only when a valid reduced load LSID is available. |
| `requestEnable` | Future live arm for issuing and consuming selected-row STQ snapshot evidence. Current top ties this false. |
| `rowMutationLiveEnable` | Future live arm for allowing a row-state plan to become a LIQ row-mutation request. R417 exposes this path input; the current top ties it false. |
| `rawResponseLiveEnable` | Future live arm for allowing raw external STQ response candidates to enter the response queue. R421 exposes this path input; the current top ties it false. |
| `launchValid` | A selected replay row would need local STQ source-return qualification. |
| `sinkReady` | Future raw store-unit request sink readiness for the R404 request sink. Current top ties this false. |
| `selectedIdentityEnable` | Selects the reduced LIQ launch-index projection instead of the raw selected-row identity inputs. |
| `selectedLaunchIndex` | Reduced LIQ selected launch slot used by `LoadReplaySourceReturnStoreSnapshotSelectedIdentity`. |
| `selectedRepickMask` | Reduced LIQ rows already resident in `Repick`; used to reject pre-repick or stale selected rows. |
| `selectedRowValidMask` | Reduced LIQ row-valid mask used by R419 response-head proof. |
| `selectedRowScbReturnedMask` | Reduced LIQ row SCB-returned mask used by R419 response-head proof and response ordering. |
| `selectedValid` | Future raw selected replay-row identity is valid when `selectedIdentityEnable=false`. |
| `selectedRepick` | Future raw selected row is still in the model-equivalent `LDQ_REPICK` state. |
| `responseValidIn` | Future raw STQ response is visible to the R398 queue. |
| `selectedClusterId` | Future selected replay-row cluster ID. |
| `selectedEntryId` | Future selected replay-row entry ID. |
| `selectedLoadId` | Reduced LIQ slot identity for the selected replay row. |
| `selectedBid` / `selectedGid` / `selectedRid` | Selected row ROB identity sidecars. |
| `selectedLoadLsId` | Selected row load/store ordering ID for future STQ older-store filtering. |
| `selectedPeId` / `selectedStid` / `selectedTid` | Selected row `MemReqBus` PE, scalar-thread, and thread context copied into the request payload for later `FlushBus` matching. |
| `selectedPc` | Selected row PC for diagnostics and wait-store identity. |
| `selectedAddr` / `selectedSize` | Selected row load address window. |
| `selectedRequestByteMask` | Selected row 64-byte request mask from `LoadInflightLaunchSelect`. |
| `selectedLineData` | Selected row line image captured into the accepted-token context. |
| `selectedValidMask` | Selected row valid byte mask captured into the accepted-token context. |
| `responseClusterId` | Future STQ response cluster ID from `MemReqBus.cID`. |
| `responseEntryId` | Future STQ response entry ID from `MemReqBus.eID`. |
| `responseHeadStale` | Future external row-state evidence proving the raw queue head targets a row that is no longer repick. Current top ties this false while the R400 reduced proof uses `selectedRepickMask`. |
| `scbReturned` | Future SCB source-return evidence arrived before STQ response acceptance. |
| `responseRequestBid` / `responseRequestGid` / `responseRequestRid` / `responseRequestLoadLsId` | Future raw response's original load request identity; this is separate from wait-store identity and is the basis for later precise response flush pruning. |
| `responseRequestPeId` / `responseRequestStid` / `responseRequestTid` | Future raw response's original load request context for later `FlushBus::match(MemReqBus)` pruning. |
| `waitStoreIn` | Future raw response asks the row to wait on a store. |
| `dataValidIn` | Future raw response carries mergeable store data. |
| `rawDataValidIn` | Future raw response contains resident-store data bytes, even if wait-store suppresses merge. |
| `dataSuppressedByWaitIn` | Future raw response found data but must ignore it because wait-store control wins. |
| `responseWaitStoreIndex` | Future raw response STQ row index for the selected not-ready store. |
| `responseWaitStoreBid` / `responseWaitStoreRid` | Future raw response wait-store BID/RID identity. |
| `responseWaitStoreLsId` | Future raw response wait-store LSID for wakeup matching. |
| `responseWaitStorePc` | Future raw response wait-store PC. |
| `responseDataMask` | Future raw response 64-byte data-valid mask. |
| `responseData` | Future raw response 64-byte line-positioned store data. |
| `legacySnapshotReady` | Current reduced-top readiness bit preserved from the resident-store snapshot path. |
| `stqRows` | Current resident STQ row image consumed by the R405 lookup owner. The top wires this from the existing reduced store-dispatch path rows. |

### Outputs

| Signal | Description |
|---|---|
| `storeSnapshotReady` | Final readiness bit feeding `LoadReplaySourceReturnReadiness.storeSnapshotReady`. |
| `control*` | Ready-control active/request/evidence/legacy/live/blocker diagnostics. |
| `evidence*` | Selected-row local STQ evidence diagnostics from the evidence classifier. |
| `rawResponseSource*` | R421 raw-response source diagnostics: active/candidate/live-valid, disabled/flush/live-disabled blockers, and malformed payload checks. |
| `queryIssue*` | Selected-row query issue diagnostics from the query owner. |
| `requestPayload*` | R402 selected-row request payload and diagnostics for the future local STQ lookup queue. |
| `requestQueue*` | R403 local STQ snapshot request-queue head, occupancy, blocker diagnostics, and R426 precise-prune mask/count visibility. |
| `responseQueue*` | R426 response FIFO precise-prune mask/count and enqueue-blocked-by-precise-flush diagnostics. |
| `lookup*` | R405 resident-STQ lookup diagnostics: query validity, row masks, eligible store mask, forward/wait masks, wait-store, raw data evidence, and response-visible data evidence. |
| `responseApply*` | R407/R408 ordered-response apply intent: STQ-returned, wait-store identity, accepted-context data merge mask/data, completion diagnostic, and malformed-payload blockers. |
| `rowStatePlan*` | R409 future row-state write plan: wait-store rewait clearing, data/no-data repick preservation, next line image, next split SCB/STQ bits, and invalid response-class diagnostics. |
| `rowMutation*` | R410 future LIQ row mutation request: candidate one-hot target diagnostics, live-disabled request payload, future row write enables, next row image, and invalid target/payload diagnostics. |

The top consumes the existing `control*`, `evidence*`, and `queryIssue*`
diagnostics through unchanged top IO names. Response-match and identity-match
diagnostics remain module-local until an external wrapper consumes them or the
top is split further.

## State

The module is mostly combinational, but R397 adds one accepted-query token
register inside `LoadReplaySourceReturnStoreSnapshotAcceptedToken`; R408
widens that token to carry row line data, valid mask, and request mask. R398
adds one raw-response FIFO inside
`LoadReplaySourceReturnStoreSnapshotResponseQueue`, and R403 adds one
selected-request FIFO inside
`LoadReplaySourceReturnStoreSnapshotRequestQueue`. R399 adds a combinational
response-drain owner, R400 adds a combinational reduced row-state proof owner,
R401 adds a combinational request/capacity gate, R402 adds a combinational
request-payload shaper, R404 adds a combinational request sink, and R405 adds
a combinational resident-STQ lookup owner. R407 adds a combinational
response-apply intent owner. R409 adds a combinational row-state plan owner
after response apply. R410 adds a combinational row-mutation request owner with
its live request arm forced off. R419 widens the response-head proof inputs
with row-valid and row-SCB-returned masks; it adds no state. R420 widens raw
external response inputs to the already registered response-queue payload
shape; it adds no state. R421 adds a combinational raw-response source gate in
front of that response queue and adds no state. R422 widens the response
payload and raw boundary with load request identity; it adds no state. The path
still owns no LDQ/LIQ mutation, full multi-cluster row fsm source, wait-store
state mutation, or data merge state.

## Logic Design

The path preserves the model ordering as a chain of small owners:

```text
RequestControl.querySinkReady
  -> QueryIssue.queryIssued
  -> SelectedIdentity.selectedValid/selectedRepick/cID/eID
  -> RequestPayload.requestValid/request
  -> RequestQueue.headValid/head request
  -> Lookup.waitStoreValid/responseDataValid
  -> RequestSink.responseValid/response payload
  -> RawResponseSource.responseValid/response payload
  -> AcceptedToken.tokenValid/tokenRepick/cID/eID
  -> ResponseQueue.headValid/head response payload
  -> IdentityMatch.responseMatchesSelected
  -> ResponseHeadState.headStale/reducedHeadScbReturned
  -> ResponseMatch.responseValid/waitStore/dataValid
  -> ResponseDrain.orderedConsumed/staleDropped/dequeueReady
  -> ResponseApply.stqReturned/waitStoreApply/dataMergeApply
  -> RowStatePlan.rewaitApply/dataMergePlan/nextStqReturned
  -> RowMutationRequest.candidateTargetMask/requestValid
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
token.

The R400 response-head state owner drives that stale input in the reduced
single-cluster path from `selectedRepickMask` and the queue-head `cID/eID`.
This mirrors the model's `entry.fsm != MTC_LDQ_REPICK` check without treating
unsupported identities as stale. The external `responseHeadStale` input remains
available for the later full multi-cluster row-state owner.

R419 adds `selectedRowValidMask` and `selectedRowScbReturnedMask` to the same
head-state owner. The not-repick proof still drives stale drain, while
`reducedHeadScbReturned` can satisfy `ResponseMatch.scbReturned` for the
targeted row. This mirrors `ASSERT(entry.scbRnt)` in
`MtcLDQInfo::handleSTQReceive` without enabling raw response inputs in the
current top. R427 also feeds that proof into `RowStatePlan.priorScbReturned`,
so response consumption and row-state planning share the same SCB-before-STQ
evidence.

R420 maps raw external response inputs directly into the R398 response queue
payload. The request-sink generated response and a future raw `lookup_su_lu_q`
response now share the same wait-store/data sideband shape before identity
matching, response application, row-state planning, and row-mutation request
generation.

R421 makes that raw mapping live-gated. `RawResponseSource.responseValid`
arbitrates the single response-queue enqueue port against
`RequestSink.responseValid`; a raw candidate with `rawResponseLiveEnable=false`
is diagnosed but does not block the request-sink generated response. This keeps
the structural payload boundary in place while preventing accidental raw
response admission before stale-row, SCB-return, and row-mutation promotion
policy is complete.

R422 carries the response load request `bid/gid/rid/loadLsId` through both
sources into the same FIFO payload, and R423 adds the `peId/stid/tid` request
context beside that identity. Future `lookup_su_lu_q` pruning must use those
request fields, not the wait-store fields, when comparing to
`FlushBus::match`.

R424 forwards `preciseFlush` to both local FIFOs. A precise recovery cycle does
not issue a new STQ lookup request, consume a queued request head, expose a
queued response head, or accept a new response. It only prunes matched resident
records and compacts survivors. This keeps queue-local recovery deterministic
until the backend cleanup path drives the bus live.

R426 keeps that behavior unchanged and only forwards the queue-local precise
prune diagnostics to the composite boundary and reduced top. R427 keeps
request/response behavior unchanged and only corrects the downstream row-state
plan's SCB-return prerequisite source.

The R401 request-control owner gates future STQ lookup request acceptance with
`requestEnable`, path activity, R397 accepted-token capacity, and R403 request
queue enqueue capacity. It does not store payload or identity; it only prevents
query issue from accepting a new selected request when the token slot or queue
slot is not available.

The R402 request-payload owner publishes the selected row's reduced LIQ slot,
accepted local `cID/eID`, BID/GID/RID identity, load LSID, PC, address, size,
and request byte mask only when query issue fires for a selected repick row.
It mirrors the model `handleL1Lookup` handoff into `lookup_lu_su_q` without
performing store-unit data lookup.

The R403 request queue stores those R402 payloads in FIFO order, supports
empty-queue same-cycle bypass to the future raw sink, and accepts a new request
into a full queue when the resident head drains in the same cycle. Head
visibility is independent of `sinkReady`; drain readiness only updates queue
storage and count.

The R404 request sink turns the visible request head into an ordered response
source for the existing response queue. It gates request consumption by
`sinkReady` and response-queue storage space so a consumed request cannot lose
its returned identity. It does not use response-queue `enqueueReady` because
that signal can include same-cycle drain through response matching and would
form a request-acceptance to response-bypass combinational loop. Raw external
response inputs have priority over the sink's generated response because the
response queue has one enqueue port.

The R405 lookup owner converts the visible request head and current STQ rows
into the same resident-store forwarding shape used by execute-load forwarding.
It reports wait-store from the nearest not-ready selected store, keeps raw
ready-byte evidence as `rawDataValid`, and sends response-visible data only
when wait-store is clear. This matches the model control flow where
`handleSTQReceive` rewaits before any returned-data merge.

The R406 response payload carries the data and wait-store identity that a later
mutation owner will need after response matching:

- `waitStoreBid`, `waitStoreRid`, and `waitStorePc` mirror model
  `wait_bid`, `wait_rid`, and `wait_tpc`.
- `waitStoreLsId` is retained for existing store-wakeup matching.
- `rawDataValid`, `dataMask`, and `data` preserve returned store bytes even if
  `waitStore` suppresses immediate merge.
- `dataValid` remains the response-visible merge gate used by evidence.

The R407 apply owner is the first LIQ-side interpretation of that payload. In
the path composition it is connected to the ordered-consumed queue head and the
R400 reduced repick proof. R408 feeds its row image inputs from the accepted
query token, so the merge diagnostic uses the line data, valid mask, and
request mask captured with the query that produced the response.

The R409 row-state plan converts the apply event into the final write intent
that a future LIQ mutation owner should use. Wait-store responses clear final
SCB/STQ return bits and the row data image because the model immediately calls
`rewait`; data/no-data responses keep the row in repick state and set final
`stqRnt`.

The R410 row-mutation request owner is the first named boundary for applying
that plan to a concrete LIQ row. It consumes `ResponseApply.targetMask` and
requires exactly one target before a request can be ready. The composite wires
`liveEnable=false`, so the request payload remains zero and
`blockedByLiveDisabled` identifies the intentional promotion gate.

The current top tie-offs keep `queryIssued=false`, `responseValid=false`,
`requestAccepted=false`, `requestEnable=false`, and row-mutation
`liveEnable=false`; they also keep `rawResponseLiveEnable=false`, so raw
external response candidates cannot affect replay launch or LIQ row state. The
same signals remain visible inside the path for future promotion.

## Timing

The path is a same-cycle diagnostic boundary in front of
`LoadReplaySourceReturnReadiness`. Live query valid/ready, raw response
sourcing behind `rawResponseLiveEnable`, selected-row identity storage, and row
mutation must be added before `requestEnable`, `rawResponseLiveEnable`, or
row-mutation `liveEnable` can be asserted.

## Flush/Recovery

Hard flush clears active query, identity, response, and evidence predicates.
Legacy mode still forwards `legacySnapshotReady`, matching the pre-R395
disabled-live behavior.

`preciseFlush` selectively prunes resident request/response queue records but
does not clear unrelated child state. The reduced top builds this bus from the
scalar cleanup source, overrides `req.lsId` with the redirecting load LSID, and
keeps LSID-less marker cleanup on the hard-clear path.

## Deferred Owners

- Live external `lookup_su_lu_q` producer wiring into the R421 raw-response source.
- Full multi-cluster row-state evidence into `responseHeadStale`.
- Full selected replay-row identity storage from replay-LIQ residency beyond
  the reduced launch-index projection.
- Live SCB return evidence source.
- Stateful wait-store replay mutation and returned-data merge from the R410
  row-mutation request.
- Live row-mutation promotion control after row-carried `scbRnt/stqRnt` and
  stale-response policy are explicit.
- Live promotion of `requestEnable` after query, response, and row-state owners
  are stable.

## Verification

Focused gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadReplaySourceReturnStoreSnapshotPath
bash tools/chisel/run_chisel_tests.sh --only LoadReplaySourceReturnStoreSnapshotRequestControl
bash tools/chisel/run_chisel_tests.sh --only LoadReplaySourceReturnStoreSnapshotRequestPayload
bash tools/chisel/run_chisel_tests.sh --only LoadReplaySourceReturnStoreSnapshotRequestQueue
bash tools/chisel/run_chisel_tests.sh --only LoadReplaySourceReturnStoreSnapshotRequestSink
bash tools/chisel/run_chisel_tests.sh --only LoadReplaySourceReturnStoreSnapshotLookup
bash tools/chisel/run_chisel_tests.sh --only LoadReplaySourceReturnStoreSnapshotResponseQueue
bash tools/chisel/run_chisel_tests.sh --only LoadReplaySourceReturnStoreSnapshotResponseHeadState
bash tools/chisel/run_chisel_tests.sh --only LoadReplaySourceReturnStoreSnapshotResponseDrain
bash tools/chisel/run_chisel_tests.sh --only LoadReplaySourceReturnStoreSnapshotResponseApply
bash tools/chisel/run_chisel_tests.sh --only LoadReplaySourceReturnStoreSnapshotRowStatePlan
bash tools/chisel/run_chisel_tests.sh --only LoadReplaySourceReturnStoreSnapshotRowMutationRequest
bash tools/chisel/run_chisel_tests.sh --only LoadReplaySourceReturnStoreSnapshotAcceptedToken
bash tools/chisel/run_chisel_tests.sh --only LoadReplaySourceReturnStoreSnapshotSelectedIdentity
bash tools/chisel/run_chisel_tests.sh --only LoadReplaySourceReturnStoreSnapshotIdentityMatch
bash tools/chisel/run_chisel_tests.sh --only LoadReplaySourceReturnStoreSnapshotResponseMatch
bash tools/chisel/run_chisel_tests.sh --only LoadReplaySourceReturnStoreSnapshotQueryIssue
bash tools/chisel/run_chisel_tests.sh --only LoadReplaySourceReturnStoreSnapshotEvidence
bash tools/chisel/run_chisel_tests.sh --only LoadReplaySourceReturnStoreSnapshotReadyControl
bash tools/chisel/run_chisel_tests.sh --only ResidentStoreForwardStoreSnapshot
bash tools/chisel/run_chisel_tests.sh --only LoadStoreForwarding
bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop
FETCH_REDUCED_STORE_REPLAY_LIQ=1 BUILD_DIR=generated/r410x bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh
```

Reference tests cover legacy readiness preservation, dormant query/response
behavior, future live accepted-token response completion, flush handling,
disabled behavior, request-queue storage while the future raw sink is stalled,
raw-response priority over sink-generated response, and Chisel elaboration with
the selected-identity, request-control, request-payload, request-queue,
request-sink, accepted-token, payload-bearing response-queue, response-head-state,
response-drain, response-apply, row-state-plan, row-mutation-request, lookup,
identity, response, and evidence child owners present in the composite module.
