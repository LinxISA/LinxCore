# LSU/STQ Model Notes

## Source Mapping

- LinxCoreModel: `model/LinxCoreModel/model/ModelCommon/bus/FlushBus.h`
- LinxCoreModel: `model/LinxCoreModel/model/ModelCommon/bus/MemReqBus.h`
- LinxCoreModel: `model/LinxCoreModel/model/lsu/store_unit/store_unit.cpp`
- LinxCoreModel: `model/LinxCoreModel/model/lsu/store_unit/stq.h`
- LinxCoreModel: `model/LinxCoreModel/model/lsu/store_unit/stq.cpp`
- LinxCoreModel: `model/LinxCoreModel/model/lsu/load_unit/ldq.h`
- LinxCoreModel: `model/LinxCoreModel/model/lsu/load_unit/ldq.cpp`
- LinxCoreModel: `model/LinxCoreModel/model/lsu/mdb/MDB.h`
- LinxCoreModel: `model/LinxCoreModel/model/lsu/mdb/MDB.cpp`
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
- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/MDBConflictDetect.scala`
- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/MDBSSIT.scala`
- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/MDBQueueFanout.scala`
- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadStoreForwarding.scala`
- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadForwardPipeline.scala`
- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadInflightQueue.scala`
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
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/MDBConflictDetectSpec.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/MDBSSITSpec.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/MDBQueueFanoutSpec.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/LoadStoreForwardingSpec.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/LoadForwardPipelineSpec.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/LoadInflightQueueSpec.scala`

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

`StoreUnit::insertStq` pushes inserted store requests into `detect_su_lu_q`.
`LDQInfo::conflictDetect` consumes those probes and `handleDetect` compares the
store against active LDQ cluster rows plus `ResolveQ`. A scalar load/store
conflict requires address overlap and
`LessEqual(store.bid, store.lsID, load.bid, load.lsID)`. Tile load/store
conflicts are currently skipped by model TODOs. Resolved active loads and
resolved-queue loads are flush candidates; unresolved active loads are only
marked `wait_store` for `ST_ADDR` probes. The selected flush candidate is the
oldest conflicting load by `(bid, lsID)`. Same-BID conflicts become inner
flushes; cross-BID conflicts become load-attributed nuke flushes. The same
selected load/store pair is also recorded to `record_lu_mdb_q`, BCTRL `bmdb`,
and IEX-local MDB.

`MDB::Work` processes MDB queues in lookup, delete, then record order. Lookup
uses load PC as the SSIT key. A table hit fills `stInfo.tpc` from the learned
store PC, computes `stInfo.bid = load.bid - bid_off`, clears `nukeVld`, and
returns `hit` only when the lookup is not the first lookup after a matching
nuke, confidence is positive, and weight is above the stall threshold. Record
sets `nukeVld/nukeBID`, allocates missing load PCs, replaces a different store
PC when confidence is low or the new store is closer by `bid_off` then
`lsID_off`, decrements confidence for farther different-store records, and
reinforces same-store records by saturating confidence and weight. Delete
matches load PC plus wait-store PC, releases a row only when weight is already
zero, otherwise decrements weight and reports when the row no longer stalls.
The default model weights are `mdb_release_weight=25`, `mdb_max_weight=3`, and
`mdb_inc_step=1`.

`MDB::handleMDBLookup` always pushes the lookup result to both
`lookup_mdb_lu_q` and `lookup_mdb_su_q` after filling `hit`, `stInfo.tpc`, and
`stInfo.bid`. `LDQInfo.updateMDBInfo` ignores misses; on a hit it finds the
working load row by `(bid, lsID)`, marks it wait-store, copies the predicted
store BID and PC into the load request, and sets `loadPending`. The store side
consumes `lookup_mdb_su_q` through `StoreUnit::mdbCheck`. `STQ::mdbCheck`
scans STQ rows in order, matches a non-tile row by predicted store `(bid,
tpc)`, returns no wakeup if the first matching row lacks address or data, and
otherwise returns the store request for `wakeup_su_lu_q`.

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
stops before response queue ordering, DCache RAM mutation, MDB, forwarding, or
full memory-event trace.

`MDBConflictDetect` is the first Chisel owner for the store-arrival conflict
classifier behind `detect_su_lu_q`. It consumes a store probe, active LDQ row
snapshots, and resolved-queue row snapshots; emits resolved conflict candidate
masks; marks unresolved active rows with `waitStoreMask` for `ST_ADDR`; exposes
tile-suppressed masks; selects the oldest resolved scalar conflict; and
classifies that conflict as same-BID inner flush or cross-BID nuke flush. The
module stops before the MDB SSIT table, `lookup_lu_mdb_q`,
`lookup_mdb_lu_q`, `lookup_mdb_su_q`, store wakeup, BCTRL `bmdb`, IEX-local
MDB, ROB nuke retirement, and final `FlushReq` publication.

`MDBSSIT` is the first Chisel state owner for that MDB table. It preserves the
model lookup/delete/record order, lookup first-after-nuke suppression, positive
confidence and weight-threshold stall rule, same-store reinforcement,
different-store replacement/decrement, and delete decay/release behavior. The
model uses an unbounded `unordered_map`; the Chisel owner is a finite
fully-associative table and reports overflow instead of inventing a replacement
policy. The Chisel owner also initializes `lsIdOff` on first insertion for
deterministic equal-`bidOff` replacement comparison; the C++ miss path leaves
that field implicit until a later replacement path needs it.

`MDBQueueFanout` is the first Chisel owner for the MDB queue boundary around
`MDBSSIT`. It owns finite lookup/delete/record command queues, finite LU and
SU lookup-result queues, and the store-side wakeup check. A lookup command can
leave the input queue only when the result can be enqueued to both LU and SU
outputs in the same cycle. If a pending lookup cannot fan out, delete and
record do not fire, preserving `MDB::Work` phase visibility under finite
hardware backpressure. Accepted record commands publish BMDB report intent but
do not mutate the BCTRL table in this packet. The SU side scans an abstract STQ
row view in row order and emits a wakeup only for the first matching non-tile
row whose address and data are both ready.

`LoadStoreForwarding` is the first Chisel owner for scalar store-to-load byte
selection from `STQ::lookupForLoad`. The model sorts conflicting older stores
from old to young, applies ready store bytes into `ReqData`, and tracks
not-ready bytes with `waitPosionVld`; a younger ready store clears the wait
bits it covers. The Chisel owner encodes that effective rule directly by
selecting the nearest older eligible store per requested load byte. Ready
selected bytes produce `forwardMask` and are merged over `cacheData`;
not-ready selected bytes produce `waitMask` and a newest blocking-store replay
diagnostic. Tile rows are surfaced through suppression masks because model tile
forwarding remains a TODO. The module is combinational and deliberately stops
before STQ row mutation, data-array banking, LDQ wait/store state updates,
SCB/L1 hit qualification, MDB learning, and recovery publication.

`LoadForwardPipeline` is the first registered Chisel E2/E3/E4 wrapper around
that selector. E2 runs `LoadStoreForwarding` over the load query, STQ candidate
view, baseline 64-byte line, and baseline byte-valid mask. E3 registers the
selected load bytes, ready forward bytes, wait-store bytes, merged data, source
return flags, and return-port readiness. E4 forms final byte validity as
baseline-valid bytes OR ready forwarded bytes, checks all requested bytes,
classifies `StoreDataNotReady`, `DataNotComplete`, `AwaitingSources`, or
`ReturnPortBlocked`, and asserts wakeup only when data is complete, load-data
and SCB sources have returned, no wait-store byte remains, and the return slot
is available. This packet still does not mutate LIQ/LHQ/LDQ rows, STQ rows,
SCB rows, MDB state, ready tables, issue wakeup fabric, or trace state.

`LoadInflightQueue` is the first Chisel owner for the active LIQ row image and
the resolved-load LHQ publication surface. It maps model `LUEntryInfo::insert`
to ring allocation of a `Wait` row with a slot-plus-wrap `LID` and stored
`youngestStoreId` snapshot; maps `loadRepick` to a launch that changes a row
from `Wait` to `Repick`; applies `LoadForwardPipeline` E4 results back into
the row; and maps successful E4 wakeup to `Resolved` plus an LHQ-style
line-address and byte-mask record. `StoreDataNotReady` returns the row to
`Wait` with wait-store diagnostics, `DataNotComplete` moves it to `L1DcMiss`
and asserts `missPending`, and source/return-port stalls return the row to
`Wait` without publishing an LHQ record. This owner still does not implement a
separate `ResolveQ`/LHQ queue, precise `FlushBus` pruning, store/SCB wakeup
replay, L2 miss/refill queues, ready-table updates, consumer bypass routing,
or memory trace emission.

This is still not the complete model STQ/SCB path. TTrans/tile behavior, load
replay/refill integration, deadlock checks, data-array banking, LDQ MDB-update
row mutation, BCTRL/IEX MDB table mutation, CHI completion, and BSB
window-slide side effects remain future LSU owner work.

## Open Questions

- The full scalar LSU needs separate owners for load-queue flush, raw L2/CHI
  response queue ordering, MDB interaction, LIQ replay/refill integration, and
  queue backpressure.
  `SCBResponseDecode` now owns raw transaction-id decode, so a later response
  queue packet should preserve its illegal/stale-target reporting while adding
  ordering and backpressure.
- `STQFlushPrune` uses the model's current `baseOnGroup` ordering, including
  its BID fast path. If the model changes this behavior, update both
  `FlushControl` notes and the STQ tests in the same packet.
- QEMU and LinxCoreModel cross-checks cannot observe this module directly
  until the Chisel top emits live memory or recovery trace rows.
