# ScalarLSULoadPath

## Source Mapping

- Chisel: `chisel/src/main/scala/linxcore/lsu/ScalarLSULoadPath.scala`
- Active rows: `chisel/src/main/scala/linxcore/lsu/LoadInflightQueue.scala`
- Resolved rows: `chisel/src/main/scala/linxcore/lsu/LoadResolveQueue.scala`
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
available through the load path. Native row mutation is intentionally tied
inactive at this canonical boundary until MDB and ordered STQ/SCB response
owners move beneath `ScalarLSU`. Cache/miss queues and final IEX LRET,
writeback, and wakeup publication are also future integration work.

## Verification

- `bash tools/chisel/run_chisel_tests.sh --only LoadInflightQueueSpec`
- `bash tools/chisel/run_chisel_tests.sh --only LoadResolveQueueSpec`
- `bash tools/chisel/run_chisel_tests.sh --only ScalarLSULoadPathSpec`
- `bash tools/chisel/run_chisel_tests.sh --only ScalarLSUSpec`
- `bash tools/chisel/run_chisel_tests.sh --only LinxCoreTopSpec`
- Full Chisel suite: 245 suites, 1,454 tests, zero failures.
- Canonical top xcheck: 3 compared rows, zero mismatches.
- Reduced CoreMark regression:
  `generated/r634-post-load-owner-coremark/report/crosscheck_manifest.json`,
  665 compared rows, zero mismatches.
