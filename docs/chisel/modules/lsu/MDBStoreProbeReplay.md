# MDBStoreProbeReplay

## Source Mapping

- Chisel: `chisel/src/main/scala/linxcore/lsu/MDBStoreProbeReplay.scala`
- Tests: `chisel/src/test/scala/linxcore/lsu/MDBStoreProbeReplaySpec.scala`
- Admission owner: `chisel/src/main/scala/linxcore/lsu/StoreDispatchSTQPath.scala`
- Canonical MDB owner: `chisel/src/main/scala/linxcore/lsu/ScalarLSUMDBPath.scala`
- Model: `model/LinxCoreModel/model/lsu/store_unit/store_unit.cpp`,
  `model/LinxCoreModel/model/lsu/load_unit/ldq.cpp`
- Contract ID: `LC-CHISEL-LSU-MDB-STORE-REPLAY-001`

## Purpose

`MDBStoreProbeReplay` is a finite, lossless timing queue between reduced store
dispatch and canonical `ScalarLSUMDBPath`. It closes the reduced pipeline gap
where a store probe can precede publication of a related resolved load into
`LoadResolveQueue`.

The module does not own conflict detection, predictor state, recovery policy,
or Linx architectural behavior. Those remain in `ScalarLSUMDBPath` and the
central recovery fabric.

## Admission Contract

Store dispatch exports an insertion intent before external MDB permit affects
the actual STQ selection. The reduced top builds the canonical store probe from
that stable intent and joins two credits into address-bearing store admission:

1. `ScalarLSUMDBPath.storeProbeReady` proves every required record, wait-plan,
   and recovery sink can accept atomically.
2. `MDBStoreProbeReplay.liveReady` proves any required delayed replay can be
   retained without overwriting an older accepted probe.

Data-only STD fragments bypass this address-side permit. An assertion requires
every accepted address-bearing STQ insertion to be the selected and consumed
canonical MDB live probe in the same cycle.

The pre-permit intent and accepted request are distinct contracts. When a
blocked STA intent allows a data-only STD to bypass, the accepted request's
store type qualifies `liveCommit`; the stalled STA intent cannot commit MDB
side effects on the STD acceptance pulse.

## Queue Contract

- Queue depth is parameterized by `entries` and is independent of MDB SSIT and
  command/output queue sizes.
- `live` is the pre-permit insertion intent; `liveCommit` identifies actual STQ
  acceptance.
- `retainForReplay` is asserted only while an unresolved resident LIQ row can
  later publish to ResolveQ.
- A committed live probe is enqueued only when delayed replay is required and
  ResolveQ is not already visible.
- Live intent has output priority. Retained probes replay in FIFO order when
  `replayEnable` is asserted.
- A retained head dequeues only on `replayConsume`, which is canonical MDB
  transaction acceptance. Backpressure holds payload and order.
- `flush` clears the complete queue.

`retainedValid`, `retainedCount`, `retainedNeedsRetry`, `replaySelected`, and
`replayAccepted` expose queue state and progress. `retainedReplayed` is the
accepted-dequeue pulse retained for compatibility with existing diagnostics.

## Linx Adaptation

Probe payloads preserve PE/STID/TID and BID/GID/RID/LSID identity from the
store insertion intent. No ARM barriers, exclusives, acquire/release state,
exception levels, or other foreign architectural behavior are introduced.

## Verification

- `bash tools/chisel/run_chisel_tests.sh --only MDBStoreProbeReplay`
- `bash tools/chisel/run_chisel_tests.sh --only StoreDispatchSTQPath`
- `bash tools/chisel/run_chisel_tests.sh --only DecodeRenameROBPath`
- `bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop`
- `bash tests/test_lsu_mdb_transaction_cross_rtl.sh`

The reference tests retain two consecutive accepted probes without overwrite,
backpressure a third probe when a two-entry queue is full, hold a replay under
downstream stall, drain in FIFO order, avoid duplicate replay when ResolveQ is
already visible, reject uncommitted intent from storage, and clear on flush.
