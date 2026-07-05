# LoadReplayRowMutationSourceMux

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayRowMutationSourceMux.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/LoadReplayRowMutationSourceMuxSpec.scala`
- Live integration: `rtl/LinxCore/chisel/src/main/scala/linxcore/top/LinxCoreFrontendFetchRfAluTraceTop.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/lsu/load_unit/ldq.cpp`
    - `LDQInfo::handleStateQuery`
    - `LDQInfo::updateMDBInfo`
  - `model/LinxCoreModel/model/lsu/mdb/MDB.cpp`
    - `MDB::handleMDBLookup`
    - `MDB::Work`

## Purpose

`LoadReplayRowMutationSourceMux` arbitrates raw replay-LIQ row-mutation
requests before they enter `ReducedLoadReplayLiqAllocPath`'s existing
`LoadInflightRowMutationRequestBridge`.

The reduced live top now has two legal row-mutation producers:

- source-return store-snapshot resolution, which clears or merges returned load data;
- MDB lookup wait-plan requests, which return a repick load to wait-store state
  after a learned MDB hit.

Both producers already emit the same raw request shape. The mux keeps shape
checks and native-store index resizing in the downstream bridge rather than
bypassing that owner.

## Interface

| Signal | Description |
|---|---|
| `sourceReturn` | Raw request from `LoadReplaySourceReturnStoreSnapshotPath`. |
| `mdbWaitPlan` | Raw request from `LoadReplayMdbLookupWaitPlan`. |
| `out` | Selected request passed to `ReducedLoadReplayLiqAllocPath`. |
| `selectedSourceReturn` | Source-return request won arbitration. |
| `selectedMdbWaitPlan` | MDB wait-plan request won arbitration. |
| `conflict` | Both inputs requested mutation in the same cycle. |

## Logic Design

Arbitration is combinational, with source-return priority only when the two
producers target different rows:

```text
sameTarget =
  sourceReturn.valid && mdbWaitPlan.valid &&
  sourceReturn.targetMask == mdbWaitPlan.targetMask &&
  sourceReturn.targetIndex == mdbWaitPlan.targetIndex

selectedSourceReturn = sourceReturn.valid
selectedMdbWaitPlan = mdbWaitPlan.valid && (!sourceReturn.valid || sameTarget)
conflict = sourceReturn.valid && mdbWaitPlan.valid
out = merged sourceReturn/MDB request if sameTarget
      else sourceReturn if sourceReturn.valid
      else mdbWaitPlan if mdbWaitPlan.valid
      else zero
```

Source-return has priority because it represents a concrete source response for
the row and can advance or resolve returned data. MDB wait-plan mutation is a
predictive wait-store rewrite and must not preempt a same-cycle actual
source-return mutation on a different row.

For same-target requests, the mux coalesces the mutations to match the
LinxCoreModel order where `LDQInfo::updateMDBInfo` can publish MDB wait state in
the same state-merge window as source-return data handling. The merged request
keeps source-return line/data fields unless the MDB side is clearing wait state,
ORs line and wait-store write enables, and lets MDB `setWaitStatus` suppress a
source-return `keepRepickStatus`. This prevents a same-row source-return pulse
from dropping an MDB wait-plan request that targets the identical LIQ entry.

## Verification

Focused gate:

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadReplayRowMutationSourceMux
```

R498 also ran:

```bash
bash tools/chisel/run_chisel_tests.sh --only ReducedLoadReplayLiqAllocPath
bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop
```

The R498 live QEMU/DUT gate at
`generated/r498-replay-liq-mdb-lookup-query-gate` passes with three compared
rows and zero mismatches. Its sideband report proves the mux preserves the
existing source-return mutation path (`source_row_mutation_request_valid=1`,
`liq_row_mutation_write_enable=1`) while the new MDB wait-plan side remains
idle because the lookup misses in this fixture.

R507 validates the same-target merge in the live reduced top. The residual-wait
gate at `generated/r507-replay-loop-mdb-wait-store-mutation-gate` compares 9
normalized QEMU/DUT rows with zero mismatches, then observes eight extra cycles
without requiring idle drain. Sideband counters record
`liq_row_mutation_selected_mdb_wait_plan=1`,
`mdb_wait_plan_row_mutation_write_enable=1`, and
`liq_wait_store_mask_nonzero=8` while
`liq_replay_wake_wait_store_clear=0`. This proves the mux can preserve an
MDB-origin wait-store mutation even when same-row source-return activity is
present; it does not prove the later store-unit wake/clear path.
