# ScalarLSULoadPath

## Source Mapping

- Chisel: `chisel/src/main/scala/linxcore/lsu/ScalarLSULoadPath.scala`
- Active rows: `chisel/src/main/scala/linxcore/lsu/LoadInflightQueue.scala`
- Resolved rows: `chisel/src/main/scala/linxcore/lsu/LoadResolveQueue.scala`
- MDB: `chisel/src/main/scala/linxcore/lsu/ScalarLSUMDBPath.scala`
- Tests: `chisel/src/test/scala/linxcore/lsu/ScalarLSULoadPathSpec.scala`
- Model: `model/LinxCoreModel/model/lsu/load_unit/ldq.cpp`,
  `LDQInfo::flush` and `LDQInfo::CheckMovRslvQ`
- Contract IDs: `LC-MA-MEM-001`, `LC-MA-HAZ-001`, `LC-MA-FWD-001`

## Purpose

`ScalarLSULoadPath` is the canonical active-to-resolved scalar load owner. It
places `LoadInflightQueue` and `LoadResolveQueue` under one recovery and LSID
lifecycle instead of relying on reduced-top pending bits and sideband wiring.

## Lifecycle

1. Allocation captures PE, STID, TID, BID, GID, RID, load LSID, address,
   destination, source traces, and the youngest eligible store snapshot.
2. Launch is accepted only when the LIQ row is eligible and ResolveQ has three
   free slots: two for already registered E3/E4 arrivals and one for the newly
   accepted load.
3. An E4 hit publishes one `LoadHitRecord`; the owner transfers it to ResolveQ
   with the source row's thread identity.
4. ResolveQ acceptance arms an exact LIQ-slot clear. The owner permits a new
   transfer in the same cycle that the prior clear is accepted.
5. Commit `(BID,LSID)` watermarks retire resolved records. Hard and precise
   flush are shared by LIQ and ResolveQ.
6. Accepted scalar allocation enqueues MDB lookup. Accepted address-bearing
   store traffic scans active and resolved rows, trains SSIT on a violation,
   and drains retained wait masks through the native LIQ mutation port.
7. A stable predicted-store wait ages independently in its LIQ slot. Timeout
   release clears wait-store state only in the cycle that MDB delete enqueue
   also accepts.
8. An accepted conflict retains one typed recovery report until the outer
   recovery owner accepts it through the dedicated `recovery` port. Report retention participates in load-path
   quiescence and can backpressure address-bearing store insertion.

`transferProtocolError` reports a hit without reserved ResolveQ capacity or a
new accepted transfer while an older source clear is blocked. It must remain
false in promoted top-level evidence.

`liqEntries` must not exceed the ROB identity domain used by replay
diagnostics. Reduced tops with a smaller ROB therefore select a matching LIQ
size explicitly; the production defaults remain independently configurable.

## Recovery

Typed precise flush applies PE/STID/TID scope and BID/group/LSID ordering to
both active and resolved rows. LIQ cancels E3/E4 sidebands on a precise flush,
returns surviving pipeline rows to `Wait`, removes matching rows, and rebases
the allocation cursor to the first removed slot with a changed generation.
This follows the model's `FlushBus::match` behavior without importing ARM
exception levels, exclusive monitors, barriers, or acquire/release opcodes.

## Current Boundary

Replay/store wakeup, refill, forwarding, and source-return readiness remain
available through the load path. MDB lookup/conflict wait mutation is live at
this canonical boundary. Same-cycle writer arbitration holds lookup output
until mutation applies, and MDB transient state contributes to `empty`.
Cache/miss queues, multi-source top arbitration, full-BID BROB cleanup, and final IEX
LRET/writeback/wakeup publication remain future integration work.

## Verification

- `bash tools/chisel/run_chisel_tests.sh --only LoadInflightQueueSpec`
- `bash tools/chisel/run_chisel_tests.sh --only LoadResolveQueueSpec`
- `bash tools/chisel/run_chisel_tests.sh --only ScalarLSULoadPathSpec`
- `bash tools/chisel/run_chisel_tests.sh --only ScalarLSUMDBPathSpec`
- `bash tools/chisel/run_chisel_tests.sh --only LoadWaitStoreTimeoutSpec`
- `bash tools/chisel/run_chisel_tests.sh --only ScalarLSUSpec`
- `bash tools/chisel/run_chisel_tests.sh --only LinxCoreTopSpec`
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
