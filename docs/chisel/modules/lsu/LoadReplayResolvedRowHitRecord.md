# LoadReplayResolvedRowHitRecord

## R672-A LSID Width Contract

Resolved replay publication copies the LIQ row's parameterized
`loadLsIdFullValid/loadLsIdFull` into the `LoadHitRecord`. This record can be
merged with the ordinary LHQ publication without width conversion before
ResolveQ and MDB consume it.

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayResolvedRowHitRecord.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/LoadReplayResolvedRowHitRecordSpec.scala`
- Top integration: `rtl/LinxCore/chisel/src/main/scala/linxcore/top/LinxCoreFrontendFetchRfAluTraceTop.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/lsu/load_unit/ldq.cpp`
    - `LDQInfo::receiveData`
    - `LDQInfo::returnData`
    - `LDQInfo::CheckMovRslvQ`
    - `LDQInfo::conflictDetect`
  - `model/LinxCoreModel/model/lsu/load_unit/ldq.h`

## Purpose

`LoadReplayResolvedRowHitRecord` builds a `LoadHitRecord` from a completed
scalar replay-LIQ row. It covers the live-top path where source-return row
mutation has already produced the complete returned line image, but the normal
E4 LHQ hit-record output did not fire in that cycle.

The model first resolves a load row, moves the resolved load into `ResolveQ`,
and then lets later store conflict detection scan that resolved-load record.
R495 adds this helper so the reduced replay-LIQ top can publish that
model-equivalent resolved-load payload from the source-return row-mutation
image before claiming any MDB record or lookup-hit behavior.

## Interface

| Signal | Direction | Description |
|---|---|---|
| `enable` | Input | Allows this cycle's row to be considered for record publication. |
| `row` | Input | Candidate `LoadInflightRow` from the replay-LIQ row array. |
| `useResolvedImage` | Input | Selects the same-cycle source-return row-mutation image instead of the stored row image. |
| `resolvedLineData` | Input | Returned line data from the row-mutation plan. |
| `resolvedValidMask` | Input | Returned valid-byte mask from the row-mutation plan. |
| `resolvedDataComplete` | Input | Same-cycle completed-data proof. |
| `resolvedSourcesReturned` | Input | Same-cycle source-return proof. |
| `resolvedScbReturned` | Input | Same-cycle SCB-return proof. |
| `resolvedStqReturned` | Input | Same-cycle STQ-return proof. |
| `recordValid` | Output | A scalar replay row is complete and can be pushed into `LoadResolveQueue`. |
| `record` | Output | `LoadHitRecord` carrying load identity, block identity, PC, address, size, byte mask, line data, and forwarded mask. |
| `blockedBy*` | Output | Debug blockers for invalid row, non-`Repick` status, incomplete return, wait-store, or tile row. |

## Logic Design

The helper accepts only rows that match the scalar resolved-replay shape:

1. The row is valid and in `LoadInflightStatus.Repick`.
2. Data, source, SCB, and STQ return proofs are complete.
3. The requested byte mask is nonzero and fully covered by the selected valid
   mask.
4. The row is not waiting on a store and is not a tile load.

When `useResolvedImage` is false, the record uses the stored row line image
and stored `loadByteMask`. When `useResolvedImage` is true, the record uses
the source-return row-mutation line image and valid mask. If the stored
`loadByteMask` has not been written yet in that same-cycle path, the helper
reconstructs the request byte mask from `addr` and `size`.

The output record preserves the row's load ID, BID/GID/RID, load LSID, PC,
address, line-aligned address, size, data line, and forwarded-byte mask. It
does not own row clearing, ResolveQ storage, MDB record publication, wakeup,
or recovery.

## Top Integration

`LinxCoreFrontendFetchRfAluTraceTop` gives normal LHQ records priority when
`ReducedLoadReplayLiqAllocPath.lhqRecordValid` fires. Otherwise, it can push a
record from this helper when the source-return row-mutation path proves a
complete `Repick` row without setting wait-store or clearing return state.

R495 also retires the matching ResolveQ entry when the parent LIQ clear of the
same load row is accepted. That keeps the reduced live fixture drainable while
still proving that a real ResolveQ push/residency occurred before the clear.

## Verification

Focused gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadReplayResolvedRowHitRecord
bash tools/chisel/run_chisel_tests.sh --only LoadResolveQueue
bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop
```

Live QEMU/Verilator fixture gate:

```bash
FETCH_REPLAY_LIQ_REQUIRE_NONZERO=wait_replay_capture_accepted,wait_replay_relaunch_valid,replay_queue_enqueue_accepted,replay_queue_out_fire,liq_alloc_accepted,liq_base_lookup_granted,source_return_query_issued,source_return_response_apply_valid,source_row_mutation_request_valid,liq_row_mutation_write_enable,resolve_queue_push_accepted,resolve_queue_valid,mdb_conflict_store_valid \
FETCH_REPLAY_LIQ_REQUIRE_ZERO=mdb_lookup_wait_plan_lookup_hit,mdb_lookup_wait_plan_wait_intent_valid,mdb_lookup_wait_plan_request_valid \
LINXCORE_REPLAY_LIQ_EARLY_STA_ADDRESS=1 \
bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh \
  --build-dir generated/r495-replay-liq-rowmutation-resolveq-gate \
  --fixture replay-ldi-sdi-ldi \
  --reduced-store-replay-liq \
  --disable-store-memory-mutation \
  --max-seconds 8
```

The R495 run compares 3 normalized QEMU/DUT rows with zero mismatches. The
sideband report shows `resolve_queue_push_accepted=1`,
`resolve_queue_valid=1`, `source_row_mutation_request_valid=1`,
`source_return_response_apply_valid=1`, and `mdb_conflict_store_valid=2`.
MDB conflict publication remains a future packet: `mdb_conflict_valid=0`,
`mdb_fanout_record_valid=0`, and MDB lookup wait-plan counters remain zero.
