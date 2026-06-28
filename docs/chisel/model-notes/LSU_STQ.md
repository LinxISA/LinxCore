# LSU/STQ Model Notes

## Source Mapping

- LinxCoreModel: `model/LinxCoreModel/model/ModelCommon/bus/FlushBus.h`
- LinxCoreModel: `model/LinxCoreModel/model/ModelCommon/bus/MemReqBus.h`
- LinxCoreModel: `model/LinxCoreModel/model/lsu/store_unit/store_unit.cpp`
- LinxCoreModel: `model/LinxCoreModel/model/lsu/store_unit/stq.h`
- LinxCoreModel: `model/LinxCoreModel/model/lsu/store_unit/stq.cpp`
- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/STQFlushPrune.scala`
- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/STQEntryBank.scala`
- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/STQCommitQueue.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/STQFlushPruneSpec.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/STQEntryBankSpec.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/STQCommitQueueSpec.scala`

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

`STQueueEntryInfo::init` either allocates a new valid `STQ_WAIT` row or merges
the address and data halves of a split store. A new `ST_ALL` row is immediately
address-ready and data-ready. A new `ST_ADDR` row is address-ready only, and a
new `ST_DATA` row is data-ready only. Merging the complementary half changes
the row to `ST_ALL`.

`STQ::free` decrements resident `size` for any freed row and decrements
`osdSize` only when the freed row is still `STQ_WAIT`. `STQ::retire` changes
locally committable, ready `STQ_WAIT` rows to `STQ_COMMIT` and decrements
`osdSize`; `STQ::commit` later sends committed stores to memory-side queues and
then frees the row.

`STQ::retire` appends non-tile committed store row indices to `storeCommitQ`
in `(bid, lsID)` age order. `STQ::commit` scans that queue from old to young,
skips rows whose downstream SCB/cacheline path is stalled, issues up to
`store_commit_count`, erases issued indices, and leaves stalled rows resident.

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

`STQEntryBank` is the first Chisel STQ state owner. It stores row sidecars,
tracks resident `size` and WAIT/outstanding `osdSize`, performs first-free
allocation, supports complementary `ST_ADDR`/`ST_DATA` merge into `ST_ALL`,
marks locally ready `ST_ALL` WAIT rows as `Commit`, frees committed rows on
single-index or multi-row mask commands, and applies `STQFlushPrune.freeMask`
to clear matched WAIT rows. The multi-row free path is the first bank-side
target for `STQCommitQueue` issue lanes; it decrements resident count once per
accepted committed row and leaves `osdSize` unchanged.

`STQCommitQueue` is the first Chisel owner for `storeCommitQ` ordering. It
accepts committed row indices, keeps them sorted by `(bid, lsId)`, selects up
to a parameterized issue width, skips downstream-stalled rows, and compacts the
queue after issue.

This is still not the complete model STQ. SCB/MDB traffic, cacheline splitting,
tile/TTrans behavior, load forwarding, deadlock checks, data-array banking, and
BSB window-slide side effects remain future LSU owner work.

## Open Questions

- The full scalar LSU needs separate owners for load-queue flush,
  SCB/MDB interaction, cacheline splitting, load forwarding, and queue
  backpressure. The bank now has a committed-row free mask, but the future LSU
  owner still needs to drive it from real memory-side issue success.
- `STQFlushPrune` uses the model's current `baseOnGroup` ordering, including
  its BID fast path. If the model changes this behavior, update both
  `FlushControl` notes and the STQ tests in the same packet.
- QEMU and LinxCoreModel cross-checks cannot observe this module directly
  until the Chisel top emits live memory or recovery trace rows.
