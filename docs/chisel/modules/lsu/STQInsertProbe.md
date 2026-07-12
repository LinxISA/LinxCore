# STQInsertProbe

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/STQInsertProbe.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/STQInsertProbeSpec.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/lsu/store_unit/stq.cpp`
    - `STQ::insert`
    - `STQ::mergeStore`
    - `STQueueEntryInfo::init`
- `model/LinxCoreModel/model/lsu/store_unit/store_unit.cpp`
  - `StoreUnit::insertStq`
- `model/LinxCoreModel/model/ModelCommon/bus/MemReqBus.h`
  - `MemReqBus::tSeq/uSeq`
- Related Chisel:
  - `chisel/src/main/scala/linxcore/lsu/STQEntryBank.scala`
  - `chisel/src/main/scala/linxcore/lsu/StoreDispatchSTQPath.scala`

## Purpose

`STQInsertProbe` is the read-only owner for the `STQEntryBank` insert
readiness predicate. It derives whether one `STQStoreRequest` can allocate a
new STQ row, merge into an existing complementary split-store row, or conflict
with an incompatible same-store split half.

The probe exists so upstream store-dispatch owners can compute readiness for
STA and STD candidates independently from the same live row image. A full STQ
may reject a new STA allocation while still accepting a mergeable STD data half
for an existing partial row.

## Interface

Inputs:

- `requestValid`: request image is meaningful for conflict and merge checks.
- `request`: typed `STQStoreRequest` candidate.
- `rows`: live `STQEntryBankRow` image.
- `flushApplied`: bank recovery mutation owns this cycle and suppresses insert.

`request` and `rows` include T/U cleanup source sidecars, but this probe does
not interpret them. It only decides insert readiness from store type, row
state, `(stid, bid, lsId)`, scalar/SIMT lane scope, and free-row availability.

Outputs:

- `ready`: the bank-style insert-ready predicate for the request image.
- `requestReady`: `requestValid && ready`.
- `canMerge`: valid request can merge into a complementary partial row.
- `canAllocate`: valid request can allocate a free row if no merge wins.
- `conflict`: valid split request matched an incompatible same-store row.
- `mergeMask`, `conflictMask`, `freeMask`: row diagnostics.
- `mergeIndex`, `allocateIndex`, `insertIndex`: selected row indices.

## Logic Design

The probe scans `Wait` rows for a matching `(stid, bid, lsId)` tuple. It never
merges split halves across independent STID domains. Scalar requests ignore
SIMT lane; non-scalar requests require the same lane. A split request
can merge only with the complementary existing half:

| Incoming request | Existing row |
|---|---|
| `Addr` | `Data` |
| `Data` | `Addr` |

An incompatible same-ID split half is reported as a conflict and blocks
allocation, even when a free row exists. `All` stores never merge and require a
free row. Split stores allocate a free row only when no complementary merge
target is present.

`flushApplied` suppresses `ready` but leaves masks and candidate indices
observable for debug. This matches `STQEntryBank`, where recovery has priority
over insert, mark-commit, and committed-row free commands.

## Model Alignment

`StoreUnit::insertStq` accepts `ST_ALL` only when `STQ::full()` is false. For
split requests, it first calls `STQ::mergeStore`; only if merge fails does it
fall back to new-row allocation. `STQueueEntryInfo::init` is the mutation point
for both new rows and complementary half merges. `STQInsertProbe` implements
the corresponding combinational readiness decision without mutating row state.

## Verification

Focused gate:

```bash
bash tools/chisel/run_chisel_tests.sh --only STQInsertProbe
```

Affected gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only STQEntryBank
bash tools/chisel/run_chisel_tests.sh --only StoreDispatchSTQPath
bash tools/chisel/run_chisel_tests.sh --only StoreDispatchToSTQ
```

Focused tests cover full-STQ complementary merge acceptance, cross-STID
non-merge, incompatible split-half conflict, flush-applied suppression, IO
widths, and standalone CIRCT elaboration.

R670 changes split-half identity matching from projected LSID equality to the
exact `(STID, BID, lsIdFull)` identity. BID remains ROB-sized; physical row
selection remains STQ-sized.
