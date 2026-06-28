# LSU/STQ Model Notes

## Source Mapping

- LinxCoreModel: `model/LinxCoreModel/model/ModelCommon/bus/FlushBus.h`
- LinxCoreModel: `model/LinxCoreModel/model/ModelCommon/bus/MemReqBus.h`
- LinxCoreModel: `model/LinxCoreModel/model/lsu/store_unit/store_unit.cpp`
- LinxCoreModel: `model/LinxCoreModel/model/lsu/store_unit/stq.h`
- LinxCoreModel: `model/LinxCoreModel/model/lsu/store_unit/stq.cpp`
- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/STQFlushPrune.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/STQFlushPruneSpec.scala`

## Model Contract

`StoreUnit::flush` fans one `FlushBus` predicate into LSU-side queues and then
calls `STQ::flush`. The shared predicate is `FlushBus::match(MemReqBus)`.

The predicate first rejects a different `stid`. It then applies optional PE and
thread filtering when the selected recovery request is PE- or thread-scoped.
After scope filtering:

- `baseOnBid` matches when the request BID is older than or equal to the store
  request BID.
- `baseOnGroup` first accepts same-or-younger store BIDs, then falls back to
  the `(bid, gid, lsID)` tuple comparison.
- the default path compares `(bid, lsID)`.

`STQ::flush` only frees entries whose STQ slot is valid, whose memory request
matches the `FlushBus`, and whose FSM is `STQ_WAIT`. Valid entries in commit,
miss, L2-wait, idle, or resolved states are not freed by this flush path.

## Chisel Scope

`STQFlushPrune` is the first Chisel LSU cleanup consumer. It mirrors the model
match predicate and emits:

- `matchMask`: all valid STQ rows covered by the selected `FlushBus`.
- `freeMask`: covered rows whose status is `Wait`.
- `statusBlockedMask`: covered rows preserved because their status is not
  `Wait`.
- `freeCount`: number of freed rows.

The module deliberately does not mutate STQ RAMs, `storeCommitQ`, SCB state, or
memory request queues. The future full STQ owner must consume `freeMask` and
own those side effects.

## Open Questions

- The full scalar LSU needs separate owners for load-queue flush, store-commit
  queue cleanup, SCB/MDB interaction, and queue backpressure.
- `STQFlushPrune` uses the model's current `baseOnGroup` ordering, including
  its BID fast path. If the model changes this behavior, update both
  `FlushControl` notes and the STQ tests in the same packet.
- QEMU and LinxCoreModel cross-checks cannot observe this module directly
  until the Chisel top emits live memory or recovery trace rows.
