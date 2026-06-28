# LSU/STQ Model Notes

## Source Mapping

- LinxCoreModel: `model/LinxCoreModel/model/ModelCommon/bus/FlushBus.h`
- LinxCoreModel: `model/LinxCoreModel/model/ModelCommon/bus/MemReqBus.h`
- LinxCoreModel: `model/LinxCoreModel/model/lsu/store_unit/store_unit.cpp`
- LinxCoreModel: `model/LinxCoreModel/model/lsu/store_unit/stq.h`
- LinxCoreModel: `model/LinxCoreModel/model/lsu/store_unit/stq.cpp`
- LinxCoreModel: `model/LinxCoreModel/model/l1/SCB.h`
- LinxCoreModel: `model/LinxCoreModel/model/l1/SCB.cpp`
- LinxCoreModel: `model/LinxCoreModel/model/l1/cluster.cpp`
- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/STQFlushPrune.scala`
- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/STQEntryBank.scala`
- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/STQCommitQueue.scala`
- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/STQCommitDrain.scala`
- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/SCBEntryState.scala`
- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/SCBCommitIngress.scala`
- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/SCBCommitBridge.scala`
- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/SCBEgressSelect.scala`
- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/SCBLookupControl.scala`
- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/SCBStateUpdate.scala`
- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/SCBRowBank.scala`
- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/STQSCBCommitPath.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/STQFlushPruneSpec.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/STQEntryBankSpec.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/STQCommitQueueSpec.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/STQCommitDrainSpec.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/SCBCommitIngressSpec.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/SCBCommitBridgeSpec.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/SCBEgressSelectSpec.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/SCBLookupControlSpec.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/SCBStateUpdateSpec.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/SCBRowBankSpec.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/STQSCBCommitPathSpec.scala`

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

`L1Clusters::commitStore` consumes `commit_su_scb_q` entries while the SCB is
not full, inserts store fragments into `SCBuffer`, and asserts the upstream
queue stall when SCB is full or committed-store backlog remains. In the model,
`SCBuffer::full()` is conservative: it returns true when free SCB entries are
fewer than `n_store_in` even if an incoming fragment would hit an existing SCB
line. `SCBuffer` coalesces by 64-byte line address, updates byte data and valid
bits, sends a line-valid wakeup after each insert, marks lines full when all 64
bytes are valid, and later evicts full or selected valid lines through
DCache/L2 lookup paths.

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

`STQCommitDrain` is the first Chisel owner for the model `STQ::commit`
memory-side boundary. It composes `STQCommitQueue`, checks committed rows
against downstream single- or split-segment availability, emits one or two
scalar memory request descriptors, and exposes a standalone issue/free mask for
early memory-side bring-up. In the full STQ-to-SCB composition, that standalone
mask is not wired to the STQ bank; `SCBRowBank.commitFreeMask` is the final
free source. The drain still preserves the model rule that split stores require
both segment queues to be non-stalled before issue, while a stalled older row
can remain queued as a younger ready row drains.

`SCBCommitIngress` is the first Chisel owner for scalar SCB insert/coalescing.
It consumes `STQCommitDrain` descriptors, allocates 64-byte line entries,
merges same-line store bytes, reports blocked fragments when no matching line
or free entry exists, and emits post-merge byte-valid wakeups. It deliberately
stops before DCache lookup/update, random/not-full eviction, L2/CHI write
requests, write responses, MDB conflict prediction, and load-side forwarding
selection.

`SCBCommitBridge` is the first Chisel owner for the capacity feedback boundary
between `STQCommitDrain` and `SCBCommitIngress`. It gates every descriptor with
the model `SCBuffer::full()` rule, mapping `n_store_in` to the bridge request
width: `modelBatchReady = scbFreeCount >= requestCount`. When this gate is
closed, even same-line hits stall and no committed STQ row is freed. When the
gate is open, accepted descriptors flow into `SCBCommitIngress`, and only
accepted descriptors with `last=1` produce `STQEntryBank.commitFreeMask` bits.

`SCBEntryState` and `SCBEgressSelect` are the first Chisel owners for the model
SCB entry lifecycle at the egress boundary. Ingress initializes rows to
`Valid`, and egress selection only considers valid rows. Under explicit
eviction pressure, full valid rows have priority. If no full row exists, the
selector chooses the first valid not-full row as the deterministic replacement
for the model's random not-full eviction path. The selector emits a lookup
descriptor and candidate masks but deliberately stops before DCache lookup,
L2/CHI write or upgrade request generation, response matching, and SCB row
freeing.

`SCBLookupControl` is the first Chisel owner for the abstract DCache/L2 outcome
after egress selection. It mirrors model `SCBuffer::handleLookup`: a writable
DCache hit emits byte-update and SCB-free intent, while a non-writable lookup
emits an L2 ownership request. A DCache tag hit without write permission
becomes an upgrade request; a tag miss becomes a write request. The request
descriptor carries the model transaction tag `(entryIndex << 2) | 2`. WriteResp
matching and response queue ordering remain future owner work.

`SCBStateUpdate` is the first Chisel owner for the row-state transitions around
that lookup boundary. It consumes the `acceptedMask`, `freeMask`, and
`missMask` from `SCBLookupControl`, plus one decoded memory response row
id. It computes the next SCB row image for selected `Valid -> Lookup`, writable
hit free, non-writable lookup `Miss`, and response-driven `Miss -> Lookup`.
The same-cycle `acceptedMask` plus `freeMask` or `missMask` case is legal when
the selected row is currently `Valid`; the final state is the hit or miss
result. Responses to non-`Miss` rows and miss/free requests to non-lookup rows
are exposed as illegal masks for the registered SCB composition owner.
L2/CHI response queues, MDB, forwarding, and DCache RAM mutation remain later
owner work.

`SCBResponseDecode` is the first Chisel owner for the raw response tag
boundary. It accepts WriteResp or UpgradeResp only when the raw transaction id
matches the model `(entryIndex << 2) | 2` namespace, the decoded entry index is
implemented, and the target row is valid `Miss`. Legal responses feed
`SCBStateUpdate.memRespEntryIndex`; wrong type, wrong tag, out-of-range index,
and stale non-`Miss` targets are reported locally and suppressed from the state
update.

`SCBRowBank` is the first registered SCB composition owner. It owns one row
image, keeps the model batch gate based on pre-cycle free count, applies
committed-store fragments in lane order, and only merges into rows still in
`Valid` state. `Lookup` and `Miss` rows are not same-line merge targets; a
new same-line store allocates a separate row when free space exists. The bank
then runs the egress selector, lookup control, and state update over the staged
row image and registers the result. This creates the full-LSU handoff surface
for final `STQEntryBank` free authorization.

`STQSCBCommitPath` is the first Chisel full STQ-to-SCB composition owner. It
wires `STQEntryBank`, `STQCommitDrain`, and `SCBRowBank` so accepted
`SCBRowBank` descriptors with `last=1` are the only committed-row free source
for the STQ bank. It gates the drain with the registered SCB pre-cycle
model-batch condition and suppresses drain issue when `STQEntryBank` reports an
active flush-prune cycle, preserving the bank-owned rule that flush cycles
ignore commit free commands. Older committed rows may drain while a younger row
is marked commit and enqueued for a later visible row image. This owner still
stops before raw CHI response decode, DCache RAM mutation, MDB, forwarding, or
full memory-event trace.

This is still not the complete model STQ/SCB path. TTrans/tile behavior, load
forwarding, deadlock checks, data-array banking, MDB conflict learning, CHI
completion, and BSB window-slide side effects remain future LSU owner work.

## Open Questions

- The full scalar LSU needs separate owners for load-queue flush, raw L2/CHI
  response queue ordering, MDB interaction, load forwarding, and queue
  backpressure.
  `SCBResponseDecode` now owns raw transaction-id decode, so a later response
  queue packet should preserve its illegal/stale-target reporting while adding
  ordering and backpressure.
- `STQFlushPrune` uses the model's current `baseOnGroup` ordering, including
  its BID fast path. If the model changes this behavior, update both
  `FlushControl` notes and the STQ tests in the same packet.
- QEMU and LinxCoreModel cross-checks cannot observe this module directly
  until the Chisel top emits live memory or recovery trace rows.
