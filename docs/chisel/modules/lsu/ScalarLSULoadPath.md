# ScalarLSULoadPath

## Source Mapping

- Chisel: `chisel/src/main/scala/linxcore/lsu/ScalarLSULoadPath.scala`
- Active rows: `chisel/src/main/scala/linxcore/lsu/LoadInflightQueue.scala`
- Resolved rows: `chisel/src/main/scala/linxcore/lsu/LoadResolveQueue.scala`
- MDB: `chisel/src/main/scala/linxcore/lsu/ScalarLSUMDBPath.scala`
- Load miss queue: `chisel/src/main/scala/linxcore/lsu/LoadMissQueue.scala`
- Refill transport: `chisel/src/main/scala/linxcore/lsu/LoadRefillTransport.scala`
- Load return: `chisel/src/main/scala/linxcore/lsu/ScalarLSULoadReturnQueue.scala`
- Return W1/W2: `chisel/src/main/scala/linxcore/lsu/ScalarLSULoadReturnPipeline.scala`
- Tests: `chisel/src/test/scala/linxcore/lsu/ScalarLSULoadPathSpec.scala`
- Generated proof: `tools/chisel/run_chisel_scalar_lsu_load_path_return_probe.sh`
- Model: `model/LinxCoreModel/model/lsu/load_unit/ldq.cpp`,
  `LDQInfo::returnData`, `LDQInfo::flush`, and `LDQInfo::CheckMovRslvQ`;
  `model/LinxCoreModel/model/iex/iex.cpp`, `IEX::receiveFromLSU`
- Contract IDs: `LC-MA-MEM-001`, `LC-MA-HAZ-001`, `LC-MA-FWD-001`

## Purpose

`ScalarLSULoadPath` is the canonical active-to-resolved scalar load owner. It
places `LoadInflightQueue` and `LoadResolveQueue` under one recovery and LSID
lifecycle instead of relying on reduced-top pending bits and sideband wiring.

## Lifecycle

1. Allocation captures PE, STID, TID, BID, GID, RID, load LSID, address,
   destination, source traces, and the youngest eligible store snapshot.
2. Launch is accepted only when the LIQ row is eligible, ResolveQ has three
   free slots, and the row's exact `(STID, return pipe)` lane has unreserved
   capacity, and physical miss entries exceed outstanding miss reservations.
   Launch acceptance increments both return-lane and miss reservations.
3. E4 releases both launch reservations on hit, miss, or replay. A data miss
   atomically enters `LoadMissQueue`; a hit extracts
   final scalar data and enters ResolveQ plus the selected LRET queue atomically;
   neither sink may observe only half of the transaction.
4. Atomic acceptance arms an exact LIQ-slot clear. The owner permits a new
   transfer in the same cycle that the prior clear is accepted.
5. The retained queue drains only after exact ROB-row validation. A normal
   return enters one fairly selected W1 lane, advances to its paired W2 lane,
   and completes resolve plus required GPR writeback/wakeup atomically. A
   missing ROB row holds; a present `NeedFlush` row is consumed and dropped.
6. Commit `(BID,LSID)` watermarks retire resolved records. Hard and precise
   flush are shared by LIQ and ResolveQ.
7. Accepted scalar allocation enqueues MDB lookup. Accepted address-bearing
   store traffic scans active and resolved rows, trains SSIT on a violation,
   and drains retained wait masks through the native LIQ mutation port.
8. A stable predicted-store wait ages independently in its LIQ slot. Timeout
   release clears wait-store state only in the cycle that MDB delete enqueue
   also accepts.
9. An accepted conflict retains one typed recovery report until the outer
   recovery owner accepts it through the dedicated `recovery` port. Report retention participates in load-path
   quiescence and can backpressure address-bearing store insertion.
10. The first miss to a line allocates one slot-plus-generation lower-memory
    transaction. Same-line misses coalesce as exact LIQ dependents. Typed
    recovery prunes dependents; issued orphans retain transaction identity
    until response. An exact read response broadcasts a line refill and frees
    the miss entry.
11. Exact miss refills and the external refill port enqueue into one bounded
    dual-ingress FIFO. Miss refill is older within a simultaneous cycle, both
    packets are retained, and LIQ consumes one ordered packet per cycle. Exact
    read response retirement is backpressured until transport enqueue fires.

`transferProtocolError` reports missing ResolveQ/LRET capacity, a partial sink
acceptance, or a new accepted transfer while an older source clear is blocked.
The load-return port also reports reservation underflow. Both must remain false
in promoted evidence.

Resident LRET entries plus reservations may not exceed one lane's configured
depth. Queue drain can provide same-cycle launch credit for that exact lane;
other lanes remain independent. Hard or typed precise flush cancels LIQ
pipeline work and clears its launch reservations while the queue bank applies
the corresponding hard clear or typed entry compaction.

W1 and W2 are vectors sized by `loadReturnPipeCount`. A W2 slot remains stable
until resolve and every required writeback/wakeup sink can fire together.
Same-cycle W2 completion, W1 advance, and new W1 insertion are supported.
Typed precise recovery freezes survivor movement and prunes only matching
stage entries; W1/W2 residency participates in load-path quiescence.

`liqEntries` must not exceed the ROB identity domain used by replay
diagnostics. Reduced tops with a smaller ROB therefore select a matching LIQ
size explicitly; the production defaults remain independently configurable.

## Recovery

Typed precise flush applies PE/STID/TID scope and BID/group/LSID ordering to
active, resolved, and resident load-return state. LIQ cancels E3/E4 sidebands
and all associated reservations on a precise flush, returns surviving pipeline
rows to `Wait`, removes matching rows, and rebases the allocation cursor to the
first removed slot with a changed generation. LRET compacts only matching
resident entries.
This follows the model's `FlushBus::match` behavior without importing ARM
exception levels, exclusive monitors, barriers, or acquire/release opcodes.

## Current Boundary

Replay/store wakeup, refill, forwarding, and source-return readiness remain
available through the load path. MDB lookup/conflict wait mutation is live at
this canonical boundary. Same-cycle writer arbitration holds lookup output
until mutation applies, and MDB transient state contributes to `empty`.
Canonical scalar miss coalescing, exact refill identity, LRET data extraction,
lane reservation, retained publication,
ROB validation, and parameterized W1/W2 atomic side-effect ownership are live
beneath `ScalarLSU`. The reduced top connects the shared W2 candidate to exact
ROB completion and the canonical physical GPR/P-ready sink. The exposed
readiness inputs remain outer allow gates and cannot bypass those resident
owners. T/U local-link completion is an explicit unsupported contract error
until its bank/qtag sink is connected. Bounded refill transport and scalar
cross-line execution are live; L1D arrays/replacement/coherence,
memory-attribute classification, and natural recovery activation remain future
integration work.

R675 executes a crossing 1/2/4/8-byte scalar load as two phase-local launches
under one LIQ identity. The first phase cannot publish ResolveQ or LRET state.
The second phase may publish only when both line masks are complete, producing
one little-endian extended value. The generated return-path probe covers both
hit/hit and hit/miss/refill/hit sequences and verifies the second miss uses the
next aligned line.

## Verification

- `bash tools/chisel/run_chisel_tests.sh --only LoadInflightQueueSpec`
- `bash tools/chisel/run_chisel_tests.sh --only LoadMissQueueSpec`
- `bash tools/chisel/run_chisel_tests.sh --only LoadRefillTransportSpec`
- `bash tools/chisel/run_chisel_tests.sh --only LoadResolveQueueSpec`
- `bash tools/chisel/run_chisel_tests.sh --only ScalarLSULoadPathSpec`
- `bash tools/chisel/run_chisel_tests.sh --only ScalarLSULoadReturnPipelineSpec`
- `bash tools/chisel/run_chisel_tests.sh --only ScalarLSUMDBPathSpec`
- `bash tools/chisel/run_chisel_tests.sh --only LoadWaitStoreTimeoutSpec`
- `bash tools/chisel/run_chisel_tests.sh --only ScalarLSUSpec`
- `bash tools/chisel/run_chisel_tests.sh --only LinxCoreTopSpec`
- `bash tools/chisel/run_chisel_scalar_lsu_load_path_return_probe.sh`
- `bash tools/chisel/run_chisel_load_miss_queue_probe.sh`
- `bash tools/chisel/run_chisel_load_refill_transport_probe.sh`
- R675 final: 268 suites and 1,626 tests; expanded LSU promotion gate;
  generated hit/hit, hit/miss/refill/hit, and precise-recovery cross-line
  proof; 1,467-row CoreMark no-regression with zero mismatches and zero CBSTOP.
- R674 final: 268 suites and 1,622 tests; expanded LSU promotion gate; both
  generated miss/refill probes; 1,467-row CoreMark no-regression with zero
  mismatches and zero CBSTOP.
- Full Chisel suite: 245 suites, 1,454 tests, zero failures.
- Canonical top xcheck: 3 compared rows, zero mismatches.
- Reduced CoreMark regression:
  `generated/r634-post-load-owner-coremark/report/crosscheck_manifest.json`,
  665 compared rows, zero mismatches.

R635 adds the canonical generated-RTL MDB probe, a full-suite pass of 246
suites and 1,462 tests, a 3-row zero-mismatch top xcheck, and the reduced
CoreMark no-regression manifest at
`generated/r635-final-mdb-owner-coremark/report/crosscheck_manifest.json` with
665 compared rows and zero mismatches. CoreMark does not naturally activate
the canonical MDB owner in this bounded window; the positive MDB sequence is
owned by `run_chisel_scalar_lsu_mdb_path_probe.sh`.

R636 extends that probe with live per-row timeout/delete feedback and passes
247 suites with 1,466 tests. Canonical top xcheck remains 3 rows with zero
mismatches. The reduced CoreMark no-regression manifest at
`generated/r636-final-failed-wait-coremark/report/crosscheck_manifest.json`
passes 665 rows with zero mismatches; it is not natural timeout activation.

R637 adds retained MDB recovery publication and positive generated-RTL proof
that a blocked report remains stable before registered cleanup acceptance and
real ROB pruning, including preservation of a younger different-STID row. The
full suite passes 249 suites and 1,474 tests; reduced
CoreMark passes 426 rows with zero mismatches at
`generated/r637-final-mdb-recovery-coremark/report/crosscheck_manifest.json`.
