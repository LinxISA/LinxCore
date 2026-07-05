# MDBQueueFanout

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/MDBQueueFanout.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/MDBQueueFanoutSpec.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/lsu/mdb/MDB.cpp`
  - `model/LinxCoreModel/model/lsu/mdb/MDB.h`
  - `model/LinxCoreModel/model/lsu/load_unit/ldq.cpp`
  - `model/LinxCoreModel/model/lsu/load_unit/ldq.h`
  - `model/LinxCoreModel/model/lsu/store_unit/store_unit.cpp`
  - `model/LinxCoreModel/model/lsu/store_unit/stq.cpp`
  - `model/LinxCoreModel/model/lsu/lsu_interface.h`
  - `model/LinxCoreModel/model/ModelCommon/bus/MemReqBus.h`
- Related Chisel contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/MDBSSIT.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/MDBConflictDetect.scala`
- Contract IDs: `LC-CHISEL-LSU-MDB-FANOUT-001`

## Purpose

`MDBQueueFanout` wraps the `MDBSSIT` table owner with the first Chisel MDB
queue and fanout boundary. It models the queue topology around `MDB::Work`:

- `lookup_lu_mdb_q` into the SSIT lookup phase,
- `delete_lu_mdb_q` into the SSIT delete phase,
- `record_lu_mdb_q` into the SSIT record phase,
- `lookup_mdb_lu_q` output for `LDQInfo.updateMDBInfo`,
- `lookup_mdb_su_q` output consumed by the store-side `mdbCheck` path.

The module also exposes the store-side wakeup decision from the model
`StoreUnit::mdbCheck` / `STQ::mdbCheck` path: a predicted store must name a
non-tile STQ row with matching `(bid, pc)`, and that row must have both address
and data ready before a wakeup is published.

R291 wires this owner into the opt-in replay-LIQ reduced top as a diagnostic
record path. `MDBConflictDetect.record` becomes a `recordIn` command with
model confidence `1`, and resident `StoreDispatchSTQPath` rows become the
store-wakeup scan view. R292 adds the load-side lookup producer boundary from
the replay-LIQ launch-accepted row; because the reduced top still ties
`launchEnable` low, this path is structurally wired but dormant in the current
fixture. R293 surfaces the delete queue/decay and phase-stall diagnostics at
the top boundary while keeping `deleteInValid` tied low. Delete producers remain
tied off until failed-wait delete owners exist, so the top observes SSIT
record/BMDB/lookup/delete command state without changing load wakeup or
recovery behavior. R458 proves the record path in the executable replay
fixture: `ReducedStoreWaitReplayChiselPathProbe` converts the selected
`MDBConflictDetect.record` into `MDBQueueBus`, drives `recordIn`, and observes
record queue acceptance, record processing, BMDB report intent, and one SSIT
valid row in generated RTL. Lookup/delete producers and store-wakeup fanout
are still tied off in that fixture. R459 enables the fixture lookup path:
after reinforcing the same record, the harness drives two lookups for the
resolved load. The first lookup is suppressed by the model first-after-nuke
rule, and the second lookup hits, fans out to LU/SU, matches the resident STQ
row, and emits the store-side wakeup. R460 enables the fixture delete path:
after the lookup/wakeup proof, the harness drives repeated failed-wait delete
commands through `deleteIn`, observes below-stall decay, and releases the
learned SSIT row only after the zero-weight delete.

It does not yet own LDQ row mutation, STQ row storage, byte forwarding, BCTRL
`BMDB` table mutation, IEX-local MDB, ROB nuke retirement, or final recovery
publication.

## Interface

### Command Inputs

| Signal | Description |
|---|---|
| `lookupInValid/lookupIn/lookupInReady` | Load-side MDB lookup enqueue equivalent to `lookup_lu_mdb_q`. |
| `deleteInValid/deleteIn/deleteInReady` | Failed wait-store delete enqueue equivalent to `delete_lu_mdb_q`; `ldInfo.waitStorePc` is the model `wait_tpc`. |
| `recordInValid/recordIn/recordInReady` | Conflict learning enqueue equivalent to `record_lu_mdb_q`. |

`MDBQueueBus` carries the model `MDBBus` shape at the Chisel boundary:
`ldInfo`, `stInfo`, `conf`, `hit`, and `valid`. `MDBMemInfo.pc` corresponds to
model `MemReqBus::tpc`.

### Fanout Outputs

| Signal | Description |
|---|---|
| `luOutValid/luOut/luDequeueReady` | Lookup result queue for the future LDQ update owner. |
| `suOutValid/suOut/suCheckReady` | Lookup result queue consumed by this module's SU wakeup check. |
| `lookupProcessed` | A lookup command left the input queue and was pushed to both output queues. |
| `phaseStalledByFanout` | A pending lookup could not be atomically enqueued to both output queues; delete and record phases are frozen. |

### Store-Side Wakeup

| Signal | Description |
|---|---|
| `storeRows` | Abstract STQ row view used by `STQ::mdbCheck`: valid, non-tile, `(bid, pc)`, ready bits, and wakeup payload. |
| `suMatchedStore` | The SU side consumed an MDB hit and found the first matching non-tile store row. |
| `suStorePending` | The first matching row exists but is not both address-ready and data-ready. |
| `suWakeup` | Store-side wakeup payload equivalent to `wakeup_su_lu_q->push_back(hitReq)`. |

### Learning/Diagnostics

| Signal | Description |
|---|---|
| `bmdbReportValid` / `bmdbLoadBid` / `bmdbStoreBid` / `bmdbStoreStid` | Intent corresponding to `core->bctrl->bmdb.reportConfilict(...)` when a record command is accepted by `MDBSSIT`. |
| `deleteMatched/deleteReleased/deleteDroppedBelowStall` | Delete/decay diagnostics from `MDBSSIT`. |
| `recordOverflow/recordOrderIllegal` | Finite-table or model-order errors from `MDBSSIT`. |
| `ssitValidMask/ssitTable` | SSIT observability for later integration and trace work. |

## State

The module owns finite Chisel queues around `MDBSSIT`:

- one lookup command queue,
- one delete command queue,
- one record command queue,
- one LU lookup-result queue,
- one SU lookup-result queue.

The C++ model uses unbounded `std::deque` queues and processes up to
`lsu_width` entries from each command queue. This Chisel owner is a bounded
one-command-per-phase boundary. Backpressure is explicit through enqueue-ready
and output-ready signals.

## Logic Design

`MDB::Work` processes lookup, delete, then record. `MDBQueueFanout` preserves
that phase order around a single `MDBSSIT` table:

1. A lookup may fire only when both LU and SU output queues can accept the same
   lookup result.
2. The lookup result copies the incoming `MDBBus`, sets `hit`, and fills
   `stInfo.pc` plus `stInfo.bid` from the SSIT lookup result.
3. The same result is enqueued atomically to both output queues, matching
   `handleMDBLookup` pushing to `lookup_mdb_lu_q` and `lookup_mdb_su_q`.
4. If a lookup is pending but cannot fan out, delete and record do not fire in
   that cycle.
5. If lookup is absent or successfully fans out, delete consumes
   `ldInfo.waitStorePc` and record consumes `ldInfo`/`stInfo` into `MDBSSIT`.
6. Accepted records publish `bmdbReportValid` so the later BCTRL owner can
   update `BMDB` without mixing that table into this packet.

The SU side consumes the SU output queue under `suCheckReady`. On a consumed MDB
hit, it scans `storeRows` in row order and uses the first non-tile row matching
the predicted store `(bid, pc)`. A wakeup is emitted only when that row is both
address-ready and data-ready. A matching but not-ready row reports
`suStorePending` and emits no wakeup, matching `STQ::mdbCheck` returning an
invalid request for incomplete stores.

## Timing

The command queues and output queues are registered Chisel `Queue` instances.
`MDBSSIT` still computes lookup, delete, and record combinationally over its
current table image and registers the next table. Later integration may split
the boundary into multiple lanes, but it must preserve atomic lookup fanout and
lookup-before-delete-before-record visibility.

## Flush/Recovery

`MDBQueueFanout` does not consume `FlushBus`. Recovery enters through record
commands, which set SSIT nuke state, and through future ROB/LDQ recovery owners
that will prune queued wrong-path MDB work. This packet deliberately stops
before ROB nuke retirement and final `FlushReq` publication.

## Trace/Observability

The module exposes command-processing pulses, fanout stalls, SU match/pending
state, BMDB report intent, and SSIT table state. QEMU and LinxCoreModel
cross-checks cannot observe this packet directly until the Chisel top emits
live LSU memory/recovery trace events. R291 exposes the same record/BMDB/SSIT
diagnostics at `LinxCoreFrontendFetchRfAluTraceTop` for generated-RTL
visibility, and R292 exposes lookup enqueue, fanout, and LU/SU hit identity
diagnostics from the dormant replay-LIQ launch boundary. R293 adds generated
top visibility for delete ready/accept/process, delete matched/released/decayed,
and lookup-fanout phase stall outputs before a real failed-wait delete producer
exists. R458 adds fixture-level generated-RTL evidence for the record queue and
SSIT/BMDB-report path, but it does not make those diagnostics architectural
replacement evidence. R459 adds fixture-level evidence for lookup fanout and
SU wakeup after SSIT reinforcement, but the lookup timing and resolved-load
source are still harness-owned. R460 adds fixture-level evidence for delete
decay/release after that lookup proof; the failed-wait producer and timer
remain harness-owned.

## Verification

- `bash tools/chisel/run_chisel_tests.sh --only MDBQueueFanout`
- `bash tools/chisel/run_chisel_tests.sh --only MDBSSIT`
- `bash tools/chisel/run_chisel_tests.sh --only MDBConflictDetect`
- `bash tools/chisel/run_chisel_tests.sh --only ReducedStoreWaitReplayChiselPath`
- `bash tools/chisel/run_chisel_reduced_store_wait_replay_chisel_path.sh`
- `bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop`
- `FETCH_REDUCED_STORE_REPLAY_LIQ=1 BUILD_DIR=generated/r291-replay-liq-mdb-fanout-xcheck bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh`
- `FETCH_REDUCED_STORE_REPLAY_LIQ=1 BUILD_DIR=generated/r292-replay-liq-mdb-lookup-xcheck bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh`
- `FETCH_REDUCED_STORE_REPLAY_LIQ=1 BUILD_DIR=generated/r293-replay-liq-mdb-delete-boundary-xcheck bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh`
- `bash tools/chisel/run_chisel_tests.sh --only STQCommitQueue`
- `bash tools/chisel/run_chisel_rob_bookkeeping.sh --reduced-rob`
- `python3 tools/chisel/trace_schema_adapter.py --self-test`
- `bash tools/chisel/run_chisel_qemu_crosscheck.sh --dry-run`

Focused reference tests cover atomic lookup fanout, output-backpressure phase
freezing, SU wakeup on a ready matching store, pending/no-wakeup for incomplete
stores, tile-row suppression, BMDB report intent only on accepted records, and
Chisel elaboration with queue and wakeup IO.
The R458 generated-RTL fixture report additionally records
`mdb_fanout_record_accepted=true`, `mdb_fanout_record_processed=true`,
`mdb_bmdb_report=true`, and `mdb_fanout_ssit_valid_mask=1` for a ResolveQ
conflict record sourced from `MDBConflictDetect`.
The R459 generated-RTL fixture report records
`mdb_fanout_record_reinforced=true`, `mdb_lookup_first_suppressed=true`,
`mdb_lookup_hit=true`, `mdb_su_wakeup=true`, and
`mdb_su_wakeup_store_index=0`, proving lookup fanout and store-side wakeup
through the same fixture-local fanout owner.
The R460 generated-RTL fixture report records `mdb_delete_accepted=true`,
`mdb_delete_dropped_below_stall=true`, and `mdb_delete_released=true`, proving
the same owner can process failed-wait delete decay and release of the learned
SSIT row.
