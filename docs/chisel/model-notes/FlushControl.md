# FlushControl Model Notes

## Source Mapping

- LinxCoreModel: `model/LinxCoreModel/model/core/FlushControl.h`
- LinxCoreModel: `model/LinxCoreModel/model/core/FlushControl.cpp`
- LinxCoreModel: `model/LinxCoreModel/model/ModelCommon/bus/FlushBus.h`
- LinxCoreModel: `model/LinxCoreModel/model/ModelCommon/ModelEnumDefines.h`
- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/recovery/FlushControl.scala`

## Model Contract

`FlushControl` owns three recovery lanes:

- `flushSignal`: global flush.
- `replaySignal`: global replay.
- `peSignal[]`: PE-scoped replay or flush.

Report queues from BCC, IEX, PE, and LSU feed `report(FlushReq)`. The model
wraps each request with `getSignal`, annotating whether it is based on BID, PE,
thread, group, SIMT replay, or MTC replay.

## Request Classification

The model treats `MISS_PRED_FLUSH`, `NUKE_FLUSH`, `INNER_FLUSH`, and
`FAST_FLUSH` as flush types. `FAST_REPLAY` and `PE_REPLAY` are replay-side
requests unless their annotations route them into PE handling.

`baseOnBid` is true for miss-predict, nuke, fast-replay, fast-flush, and
PE-replay requests without a fetch TPC. Non-scalar execution engines are based
on PE and thread. `SIMT_INNER_FLUSH` is also based on group.

## Older-Signal Arbitration

`CheckOlder(src, dst)` first rejects different `stid` values. It then applies:

- Same BID, BID-based conflict: miss-predict wins; nuke wins over inner/PE;
  fast-replay wins over PE.
- Same BID and RID, non-BID conflict: inner flush and PE replay cancel each
  other; inner flush cancels inner flush.
- PE replay has special handling after same-BID conflicts are resolved:
  miss-predict beats it, fast-replay is treated as older than it, and PE-vs-PE
  age only compares within the same PE.
- Otherwise age is ROBID order. For BID-based arbitration, the current oldest
  block BID is also treated as older even when wrap comparison alone would not
  make it older.

## Chisel Scope

The first Chisel packet implements the request/bus types, classification
helpers, `needFlush` helpers, and the combinational `FlushOlderSelector`.
Stateful report queues, `CheckFlush`, merge, backend fanout, and block-ROB
side effects are intentionally deferred until BROB and PE recovery ownership
are present.

`FullBidRecoveryBridge` adds the first explicit identity bridge for recovery:
the full hardware `blockBid` passes through for BROB/block cleanup, while
`FlushBus.req.bid` carries the ring `ROBID` sidecar used by ROB row pruning.
The conversion uses the full BID low slot bits as `ROBID.value` and the low
uniqueness bit as `ROBID.wrap`, matching the sidecar produced by
`DispatchROBAllocator` during allocation.

`RecoveryCleanupControl` adds the registered cleanup-intent boundary. It
accepts full-BID requests or pre-annotated ring-qualified requests, with
full-BID input taking fixed local compatibility priority. Central composition
preselects full-BID requests through `RecoverySourceArbiter` and leaves the raw
ring input unused. After a request is selected,
global flushes map to BCTRL/rename flush plus
frontend restart; global replay and PE-scoped replay map to BCTRL/rename
replay; all accepted cleanup intents carry backend PE/ROB, report-queue,
LSU/STQ, and tile cleanup hooks. This mirrors `flush`, `replay`,
`FlushBaseOnPE`, and `flushBackend` fanout without mutating those consumers in
the recovery control packet.

Ring-qualified input can drive typed ROB and backend cleanup but cannot name a
full BROB block. The owner therefore suppresses BCTRL/block-flush validity for
that source. State consumers fire only with `intent.valid && intentReady`.

R638 adds the exact promotion path used by scalar MDB. `ROBFullBidLookup`
indexes the resident row by RID and checks native BID/GID plus PE/STID/TID,
valid full sideband, and ring projection. `RingFullBidRecoveryBridge` rejects
non-echoed or inconsistent results before constructing `FullBidFlushReq`.
Lookup failure retains the report; successful promotion may authorize
BCTRL/BROB cleanup after the normal registered intent handshake.

R639 adds the retained multi-source selection boundary. The model's
`CheckOlder` returns false for different STIDs and its implementation still
notes single-thread support, so Chisel does not compare their BIDs. It selects
the model-oldest report independently within each instantiated STID and uses
round robin only to serialize STID winners. Each source has a retained slot;
cleanup backpressure or another source winning cannot drop a report.
This is the post-promotion source boundary, not yet the complete stateful
`selectFlushSigal`/`selectReplaySigal`/`selectPESigal` merge. Those class lanes
and their cross-cancellation remain canonical composition work.

`ScalarLSU` places `RecoveryEligibilityControl` before ring input. This is the
Chisel `CheckFlush` age boundary for MDB reports: non-immediate requests wait
for wrap-qualified oldest BID/RID state; immediate requests bypass age only.

R640 extracts that gate and exact lookup into `ScalarLSURecoverySource`. The
LSU now publishes one full-BID source and accepts per-source readiness from
`RecoverySourceArbiter`; it no longer instantiates `RecoveryCleanupControl` or
selects an external full request over MDB.

R641 adds `RecoveryClassMerge` and `RecoveryFabric`. The class merge preserves
one global-flush lane, one global-replay lane, and one PE-scoped lane per
instantiated `(STID, PE)`. Accepted reports use `CheckOlder` against only their
own STID's oldest BID, perform model cancellation and `mergeSignal`
transformation, reject global replay when the oldest block is complete, and
stage the selected request in an irrevocable downstream slot. `RecoveryFabric`
places this owner between `RecoverySourceArbiter` and
`RecoveryCleanupControl`; the raw ring cleanup input stays inactive in that
composition. Cross-STID serialization remains round-robin and never compares
unrelated BIDs.

`STQFlushPrune` is the first LSU consumer of that selected `FlushBus`. It uses
the same `stid`, optional PE/thread, BID, group, and LSID matching policy as
`FlushBus::match(MemReqBus)` and only frees rows whose model STQ FSM is
`STQ_WAIT`.

`GPRRenameCheckpoint` is the first scalar rename cleanup consumer of the
registered intent. It applies `renameFlushValid` to model `GPRRename::Flush`:
compute `restoreBid = flush.bid - 1`, restore `smap` from a valid checkpoint or
`cmap`, prune matching mapQ rows, and re-apply surviving same-BID mapQ rows for
non-BID flushes. `renameReplayValid` is observed but does not mutate scalar GPR
maps in this first owner because ClockHands/T/U and SGPR replay remain separate
rename packets.

## Open Questions

- The model typo `selectPESigal` is preserved only in source citations; Chisel
  uses normal spelling.
- Full `SIMT_INNER_FLUSH` group-order behavior now has a first STQ consumer,
  but vector and MTC consumers still need owner-specific tests before promotion
  beyond arbitration and STQ mask generation.
- Recovery cleanup consumers are still open beyond first scalar GPR cleanup:
  ClockHands/T/U and multi-thread rename replay, frontend restart token
  payloads, PE replay fanout, and BROB pointer restoration are signaled by
  `RecoveryCleanupControl` but not implemented by the current Chisel packets.
- `RecoveryFabric` is proven ahead of the real-ROB cleanup harness. Canonical
  BCC/IEX/PE source modules and production backend-top wiring remain open.
