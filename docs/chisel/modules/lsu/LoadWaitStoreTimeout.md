# LoadWaitStoreTimeout

## Source Mapping

- Chisel: `chisel/src/main/scala/linxcore/lsu/LoadWaitStoreTimeout.scala`
- Tests: `chisel/src/test/scala/linxcore/lsu/LoadWaitStoreTimeoutSpec.scala`
- Parent: `chisel/src/main/scala/linxcore/lsu/ScalarLSUMDBPath.scala`
- Model: `model/LinxCoreModel/model/lsu/load_unit/ldq.cpp`,
  `LDQInfo::updateMDBInfo`, `LDQInfo::waitStore`, and
  `LDQInfo::checkLoadPending`
- Contract IDs: `LC-MA-MEM-001`, `LC-MA-HAZ-001`

## Purpose

`LoadWaitStoreTimeout` detects an MDB prediction that leaves a scalar load
waiting without a matching store wakeup. It produces one retained release
candidate for `ScalarLSUMDBPath`; the parent clears the LIQ wait and enqueues
MDB delete feedback atomically.

The model records `stallCycle` when a wait begins and uses
`delete_lu_mdb_q` to decay the failed store-set prediction. Its nominal shared
300-cycle fallback never decrements because `oldestPending` is not asserted in
the current `checkLoadPending` implementation. Chisel preserves the useful
feedback mechanism with deterministic per-row age instead of reproducing the
dead shared-counter heuristic.

## State

Each LIQ slot stores:

- an observed-valid bit,
- the load slot-plus-wrap generation,
- predicted store BID, LSID, and PC,
- a saturating age of `log2Ceil(mdbFailedWaitTimeoutCycles + 1)` bits.

Expiry becomes visible after `mdbFailedWaitTimeoutCycles` complete resident
cycles. The internal age saturates at `mdbFailedWaitTimeoutCycles - 1`; the
extra representable value is not used as a second timeout state.

The active predicate requires a valid non-tile scalar row in `Wait` or
`Repick` with valid wait-store metadata. A changed load generation or store
identity installs a new key at age zero. Wakeup, row removal, and recovery
clear the observation.

## Release Contract

All expired slots remain asserted while downstream work is blocked. Priority
encoding selects the lowest slot, and other saturated slots remain eligible
for later cycles. `releaseAccepted` clears only the selected observation.

`ScalarLSUMDBPath` asserts the LIQ mutation only when the bounded MDB delete
queue is ready. It asserts `deleteInValid` only when that mutation accepts.
Therefore one cycle performs both effects or neither effect:

1. return the load to `Wait`, clear accumulated return data, and clear
   `waitStore`;
2. enqueue load PC plus predicted store PC for `MDBSSIT` decay/delete.

This timer is microarchitectural. It defines no ISA-visible timeout and imports
no ARM exception, barrier, or exclusive-monitor behavior.

## Verification

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadWaitStoreTimeoutSpec
bash tools/chisel/run_chisel_scalar_lsu_mdb_path_probe.sh
```

Reference tests cover exact threshold behavior, restart on load/store identity
change, cancellation, simultaneous expiry serialization, and held expiry under
backpressure. The generated-RTL probe trains one SSIT row, then performs three
timeout releases: two below-stall decays and final zero-weight removal. Its
integrated lane applies the timeout mutation to a real `LoadInflightQueue` row
and observes the wait-store mask clear after atomic delete acceptance. The
probe also instantiates a one-cycle timer to verify the minimum threshold and
immediate flush masking.
