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

`RecoveryCleanupControl` adds the first registered cleanup-intent boundary.
After a request is selected, global flushes map to BCTRL/rename flush plus
frontend restart; global replay and PE-scoped replay map to BCTRL/rename
replay; all accepted cleanup intents carry backend PE/ROB, report-queue,
LSU/STQ, and tile cleanup hooks. This mirrors `flush`, `replay`,
`FlushBaseOnPE`, and `flushBackend` fanout without mutating those consumers in
the recovery control packet.

`STQFlushPrune` is the first LSU consumer of that selected `FlushBus`. It uses
the same `stid`, optional PE/thread, BID, group, and LSID matching policy as
`FlushBus::match(MemReqBus)` and only frees rows whose model STQ FSM is
`STQ_WAIT`.

## Open Questions

- The model typo `selectPESigal` is preserved only in source citations; Chisel
  uses normal spelling.
- Full `SIMT_INNER_FLUSH` group-order behavior now has a first STQ consumer,
  but vector and MTC consumers still need owner-specific tests before promotion
  beyond arbitration and STQ mask generation.
- Recovery cleanup consumers are still open: rename checkpoint restore,
  application of STQ free masks to full STQ state, frontend restart token
  payloads, PE replay fanout, and BROB pointer restoration are signaled by
  `RecoveryCleanupControl` but not implemented by the current Chisel packets.
