# MDBStoreProbeReplay

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/MDBStoreProbeReplay.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/MDBStoreProbeReplaySpec.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/lsu/store_unit/store_unit.cpp`
  - `model/LinxCoreModel/model/lsu/load_unit/ldq.cpp`
  - `model/LinxCoreModel/model/lsu/mdb/MDB.cpp`
- Related Chisel contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/MDBConflictDetect.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/MDBQueueFanout.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadResolveQueue.scala`
- Contract IDs: `LC-CHISEL-LSU-MDB-STORE-REPLAY-001`

## Purpose

`MDBStoreProbeReplay` is the reduced replay-LIQ timing owner that keeps a
store-detect probe available until a resolved replay load is visible in
`LoadResolveQueue`.

The model path records MDB conflicts when `LDQInfo::handleDetect` scans a
store probe against already resolved active LDQ rows and `ResolveQ::detect`.
R496 proved that the reduced top had the right aggregate events but the wrong
relative timing: store probes appeared at cycles 22 and 28, while the replay
load reached ResolveQ at cycle 34. R497 retains the latest live store probe and
replays it once when ResolveQ is nonempty, so `MDBConflictDetect` observes the
model-equivalent resolved-load candidate and drives `MDBQueueFanout` record
learning.

This module does not change the conflict predicate, SSIT learning policy,
BMDB side effects, lookup wait-plan publication, or recovery flush behavior.
It only owns the reduced timing bridge between store-probe production and
ResolveQ publication.

## Interface

| Signal | Direction | Description |
|---|---|---|
| `flush` | Input | Clears retained replay state. The reduced top asserts this when replay-LIQ mode is disabled or store/replay state is flushed. |
| `live` | Input | Current-cycle `MDBConflictStoreProbe` from store dispatch/STQ insertion timing. |
| `replayEnable` | Input | Allows one retained-probe replay, driven by nonempty ResolveQ in the reduced top. |
| `replayConsume` | Input | Marks the retained probe as already replayed when the replayed output is accepted by the downstream conflict detector boundary. |
| `out` | Output | Selected live or replayed store probe. Live probes have priority over retained replay probes. |
| `liveSelected` | Output | Diagnostic pulse when `out` is the current live probe. |
| `replaySelected` | Output | Diagnostic pulse when `out` is the retained replay probe. |
| `retainedValid` | Output | Diagnostic state bit showing that a live probe has been captured. |
| `retainedReplayed` | Output | Diagnostic state bit showing that the retained probe has already been replayed. |
| `replayAccepted` | Output | Pulses only when a selected replay is accepted by the downstream atomic transaction boundary. |

## State

The module stores one `MDBConflictStoreProbe` plus two state bits:

- `retainedValid`: a live probe has been captured and may be replayed.
- `retainedReplayed`: the retained probe already had its one replay chance.

Any new live probe replaces the retained probe and clears `retainedReplayed`.
`flush` clears all state.

## Logic Design

1. If `live.valid` is high, drive `out` from `live`, capture it into the
   retained register, and clear the replayed bit.
2. If there is no live probe and `replayEnable` is high while a retained probe
   has not yet replayed, drive `out` from the retained probe.
3. When a replayed probe is accepted by the atomic MDB record/recovery
   transaction boundary, set `retainedReplayed` so the same probe is not
   emitted again. Selection alone does not consume retained state.
4. If neither live nor replay is selected, drive an invalid zero probe.

Live priority keeps the reduced top aligned with the model store-arrival path:
a fresh store probe is always the newest event. Replay only covers the timing
gap where the store probe was earlier than ResolveQ publication.

## Verification

- `bash tools/chisel/run_chisel_tests.sh --only MDBStoreProbeReplay`
- `bash tools/chisel/run_chisel_tests.sh --only MDBConflictDetect`
- `bash tools/chisel/run_chisel_tests.sh --only MDBQueueFanout`
- `bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop`
- `FETCH_REPLAY_LIQ_REQUIRE_NONZERO=wait_replay_capture_accepted,wait_replay_relaunch_valid,replay_queue_enqueue_accepted,replay_queue_out_fire,liq_alloc_accepted,liq_base_lookup_granted,source_return_query_issued,source_return_response_apply_valid,source_row_mutation_request_valid,liq_row_mutation_write_enable,resolve_queue_push_accepted,resolve_queue_valid,mdb_conflict_store_valid,mdb_conflict_store_with_resolve_queue_valid,mdb_conflict_resolve_candidate,mdb_conflict_valid,mdb_fanout_record_valid,mdb_fanout_record_accepted,mdb_fanout_record_processed,mdb_fanout_bmdb_report,mdb_fanout_ssit_nonempty FETCH_REPLAY_LIQ_REQUIRE_ZERO=mdb_lookup_wait_plan_lookup_hit,mdb_lookup_wait_plan_wait_intent_valid,mdb_lookup_wait_plan_request_valid LINXCORE_REPLAY_LIQ_EARLY_STA_ADDRESS=1 bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r497-replay-liq-mdb-store-replay-gate --fixture replay-ldi-sdi-ldi --reduced-store-replay-liq --disable-store-memory-mutation --max-seconds 8`
- `python3 tools/chisel/validate_frontend_fetch_rf_alu_sideband_stats.py generated/r497-replay-liq-mdb-store-replay-gate/report/frontend_fetch_rf_alu_sideband_stats.json --expect-reduced-store-replay-liq --require-nonzero replay_liq.mdb_conflict_store_with_resolve_queue_valid --require-nonzero replay_liq.mdb_conflict_resolve_candidate --require-nonzero replay_liq.mdb_conflict_valid --require-nonzero replay_liq.mdb_fanout_record_valid --require-nonzero replay_liq.mdb_fanout_bmdb_report --require-zero replay_liq.mdb_lookup_wait_plan_lookup_hit`

The R497 live gate passes with 3 normalized QEMU/DUT rows, zero mismatches,
`mdb_conflict_store_with_resolve_queue_valid=1`,
`mdb_conflict_resolve_candidate=1`, `mdb_conflict_valid=1`,
`mdb_fanout_record_valid=1`, `mdb_fanout_record_accepted=1`,
`mdb_fanout_record_processed=1`, `mdb_fanout_bmdb_report=1`, and
`mdb_fanout_ssit_nonempty=1`. MDB lookup wait-plan counters intentionally
remain zero; using the learned record to drive a later lookup/wait mutation is
the next packet.
