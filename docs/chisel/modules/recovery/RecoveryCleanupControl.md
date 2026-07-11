# RecoveryCleanupControl

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/recovery/RecoveryCleanupControl.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/recovery/RecoveryCleanupControlSpec.scala`
- Integrated ROB probe:
  `chisel/src/main/scala/linxcore/recovery/RecoveryCleanupROBProbe.scala`,
  `tools/chisel/recovery_cleanup_rob_probe_tb.cpp`
- Eligibility owner:
  `chisel/src/main/scala/linxcore/recovery/RecoveryEligibilityControl.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/core/FlushControl.cpp`
  - `model/LinxCoreModel/model/bctrl/BCtrl.cpp`
  - `model/LinxCoreModel/model/bctrl/BIFU.cpp`
  - `model/LinxCoreModel/model/bctrl/BROB.cpp`
  - `model/LinxCoreModel/model/bctrl/spe/SPE.cpp`
  - `model/LinxCoreModel/model/bctrl/spe/SPEROB.cpp`
  - `model/LinxCoreModel/model/lsu/store_unit/store_unit.cpp`
  - `model/LinxCoreModel/model/lsu/store_unit/stq.cpp`
- Related Chisel contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/recovery/FlushControl.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/recovery/RecoveryCleanupControl.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/STQFlushPrune.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/STQEntryBank.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/rob/ROBEntryBank.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/rob/ROBFlushPrune.scala`
- Contract IDs: `LC-CHISEL-RECOVERY-CLEANUP-001`

## Purpose

`RecoveryCleanupControl` is the registered recovery cleanup-intent owner. It
accepts either a selected full-BID request or a pre-annotated ring-identity
`FlushBus`, registers the selected source for one cleanup boundary, and exposes
explicit intent bits for BCTRL, rename, backend, frontend, LSU/STQ, tile, and
ROB cleanup consumers. Full-BID input has fixed priority when both sources are
valid.

The module does not mutate rename maps, LSU/STQ entries, frontend queues, BROB
pointers, or ROB rows. It prevents those side effects from being smuggled into
`ROBFlushPrune` or generic top-level glue.

## Interface

### Request Boundary

| Direction | Signal | Type | Valid/ready | Description |
|---|---|---|---|---|
| input | `req` | `FullBidFlushReq` | `req.valid && reqReady` | Selected recovery request with full block BID and ring sub-ID sidecars. |
| output | `reqReady` | `Bool` | ready | High when the one-entry intent register can accept a request. |
| input | `ringReq` | `FlushBus` | `ringReq.req.valid && ringReqReady` | Selected source with wrap-qualified ROB identity but no full block BID. |
| output | `ringReqReady` | `Bool` | ready | High when ring input may enter and no full-BID request claims the cycle. |
| input | `intentReady` | `Bool` | ready | Consumer acknowledgement for the registered intent. |
| output | `pending` | `Bool` | diagnostic | The intent register currently holds a valid request. |
| output | `accepted` | `Bool` | diagnostic | Either request source accepted this cycle. |
| output | `fullAccepted/ringAccepted` | `Bool` | diagnostic | Accepted source classification. |
| output | `consumed` | `Bool` | diagnostic | Existing pending intent consumed this cycle. |

### `RecoveryCleanupIntent`

| Field | Description |
|---|---|
| `valid` | Registered cleanup intent is valid. |
| `flush` | Annotated `FlushBus` for ROB and backend consumers. |
| `blockFlushValid/blockFlushBid` | Full-BID block cleanup for true global flushes. Ring-only input keeps valid low. |
| `robPruneValid` | ROB/PE-ROB prune path should consume `flush`. |
| `bctrlFlushValid` | BCTRL should take the model `flush` path. |
| `bctrlReplayValid` | BCTRL should take the model `replay` path. |
| `bctrlSimtRecoveredValid` | PE-scoped SIMT/MTC replay should run the BROB SIMT recovery sweep, except `SIMT_INNER_FLUSH`. |
| `renameFlushValid` | Scalar rename should take the model `flush` path. |
| `renameReplayValid` | Scalar rename should take the model `replay` path. |
| `backendFlushValid` | Backend PE/IEX/LSU/tile cleanup should consume `flush`. |
| `reportQueueFlushValid` | Younger report queues should be filtered by the selected signal. |
| `frontendRestartValid` | BCTRL/front-end restart is expected from a global flush. |
| `peFanoutAll` | Fan out to all PEs. |
| `peFanoutSingle/peFanoutId` | Fan out to one PE selected by `peId`. |
| `vectorReplayValid/vectorFlushValid` | Vector backend replay or flush intent. |
| `mtcReplayValid/mtcFlushValid` | MTC backend replay or flush intent. |
| `lsuFlushValid/stqFlushValid` | LSU and STQ cleanup should consume the selected `FlushBus`. |
| `tileFlushValid` | Tile register/bridge cleanup should consume the selected `FlushBus`. |
| `globalFlush/globalReplay/peScopedReplay` | Selected model lane classification. |

## State

- `pendingReq`: registered full-BID request storage.
- `pendingRingReq`: registered annotated ring request storage.
- `pendingIsRing`: source tag that prevents ring input from fabricating
  full-BID BCTRL/BROB cleanup authority.
- `pendingValid`: valid bit for the registered cleanup intent.
- `FullBidRecoveryBridge`: combinational child used to preserve the full BID
  and produce the ring `FlushBus` from the registered request.

The boundary accepts a new request when empty or when the current intent is
being consumed. A simultaneous consume and accept replaces the pending request.
Full-BID input wins a same-cycle source conflict; ring input observes
`ringReqReady=false` and must remain valid.

## Logic Design

The lane classification follows `FlushControl::select` and the model fanout:

- global flush: request is a model flush type and is not PE/thread-scoped;
- global replay: request is not a model flush type and is not PE/thread-scoped;
- PE-scoped replay: request is PE- or thread-scoped, including vector/MTC
  requests and `PE_REPLAY`.

For global flushes, the model runs `bctrl.flush`, `sRename.flush`,
`flushBackend`, report-queue filtering, and BIFU/frontend restart through
`BCtrl::flush`/`BlockIFU::flush`.

Ring-qualified requests drive typed ROB, rename, backend, frontend, and LSU
cleanup, but suppress `blockFlushValid` and `bctrlFlushValid`: a ROBID slot and
wrap bit are not a full Linx block BID. A future allocator/BROB lookup must add
that authority; truncation or zero-extension is forbidden.

`RecoveryEligibilityControl` precedes ring acceptance in `ScalarLSU`. A
non-immediate BID-based request waits until `request.bid <= oldestBid`; an
RID-based request uses the canonical `(BID,RID)` comparison. Missing or younger
oldest state keeps the source queued. Immediate requests bypass only this age
gate, not cleanup backpressure or source arbitration.

For global replay and PE-scoped replay, the model runs `bctrl.replay`,
`sRename.replay`, backend cleanup, and PE/ROB cleanup. PE-scoped SIMT/MTC
replay also enables the BROB `SimtRecoverd` sweep, except for
`SIMT_INNER_FLUSH`.

Backend fanout follows `FlushControl::flushBackend`: SIMT replay, MTC replay,
and base-on-PE requests target one PE; scalar/global cleanup targets all PEs.
LSU, STQ, tile-register, and tile-bridge cleanup consume the same selected
`FlushBus`. `STQFlushPrune` is the first concrete STQ consumer of this intent:
it emits free masks for valid `STQ_WAIT` entries. `STQEntryBank` now consumes
those masks for row-state cleanup, while store-commit queue, SCB/MDB,
forwarding, and data-array side effects remain owned by future LSU modules.

## Timing

This is a one-entry registered boundary. The selected request becomes visible
as `intent.valid` on the cycle after acceptance and remains visible until
`intentReady` consumes it. Downstream cleanup consumers must observe this
registered boundary; do not create combinational loops from side-effect
completion back into flush selection.

Consumers apply state changes only on `intent.valid && intentReady`. Merely
observing a blocked valid intent must not repeatedly prune or clear state.

## Flush/Recovery

This module defines the cleanup-intent fanout only. The following remain future
owner work:

- BROB pointer restoration and replay-state mutation,
- scalar rename checkpoint restore,
- PE ROB row mutation beyond existing `ROBEntryBank`/`ROBFlushPrune`,
- LSU/STQ store-commit queue, SCB/MDB, data-array, forwarding, and LSID
  rebasing,
- frontend restart token payloads,
- precise trap and redirect ownership.

## Verification

- `bash tools/chisel/run_chisel_tests.sh --only RecoveryCleanupControlSpec`
- `bash tools/chisel/run_chisel_tests.sh --only RecoveryEligibilityControlSpec`
- `bash tools/chisel/run_chisel_tests.sh --only RecoveryCleanupROBProbeSpec`
- `bash tools/chisel/run_chisel_recovery_cleanup_rob_probe.sh`

The generated-RTL probe allocates three real ROB rows across two STIDs, retains a non-oldest
ring-qualified nuke before cleanup, then holds the eligible cleanup intent while
its consumer is blocked. It prunes the target row only on the accepted intent
and preserves a younger row from another STID. The same run proves full-over-ring source priority,
consume-plus-ring-replace, and that ring input cannot assert full-BID block
cleanup.

## Trace/Observability

`RecoveryCleanupIntent` exposes explicit lane and side-effect bits so the
future trace adapter can record recovery events without inferring them from ROB
row mutation. No architectural commit trace row is emitted by this module.

## Verification

- `bash tools/chisel/run_chisel_tests.sh --only RecoveryCleanupControl`
- `bash tools/chisel/run_chisel_tests.sh --only FullBidRecoveryBridge`
- `bash tools/chisel/run_chisel_tests.sh --only FlushControl`
- `bash tools/chisel/run_chisel_tests.sh --only STQFlushPrune`
- `bash tools/chisel/run_chisel_tests.sh --only STQEntryBank`
- `bash tools/chisel/run_chisel_tests.sh --only ROBFlushPrune`
- `bash tools/chisel/run_chisel_tests.sh --only ROBEntryBank`
- `bash tools/chisel/build_chisel.sh`

Focused tests cover global flush classification, scalar replay, PE replay,
SIMT inner flush, MTC replay, and Chisel elaboration of the registered cleanup
intent interface.
