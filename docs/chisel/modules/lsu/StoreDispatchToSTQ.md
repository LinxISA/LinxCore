# StoreDispatchToSTQ

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/StoreDispatchToSTQ.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/StoreDispatchToSTQSpec.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/lsu/store_unit/store_unit.cpp`
    - `StoreUnit::store`
    - `StoreUnit::insertStq`
  - `model/LinxCoreModel/model/lsu/store_unit/stq.cpp`
    - `STQ::insert`
    - `STQ::mergeStore`
    - `STQueueEntryInfo::init`
  - `model/LinxCoreModel/model/ModelCommon/bus/MemReqBus.h`
    - `MemReqBus::tSeq/uSeq`
  - `model/LinxCoreModel/model/ModelCommon/SimInstInfo.cpp`
    - `SimInstInfo::GenMemReq`
  - `model/LinxCoreModel/model/lsu/FakeLSU.cpp`
    - `FakeLSU::mergeStore`
- Related Chisel:
  - `chisel/src/main/scala/linxcore/lsu/StoreDispatchQueues.scala`
  - `chisel/src/main/scala/linxcore/lsu/STQInsertProbe.scala`
  - `chisel/src/main/scala/linxcore/lsu/StoreDispatchSTQPath.scala`
  - `chisel/src/main/scala/linxcore/lsu/STQEntryBank.scala`

## Purpose

`StoreDispatchToSTQ` is the first Chisel bridge from queue-backed store
dispatch heads to typed STQ insert requests. It consumes visible STA and STD
payloads from `StoreDispatchQueues`, waits for explicit execution results, and
forms one `STQStoreRequest` per cycle for `STQEntryBank`-style insertion.

The module deliberately keeps address generation and data selection outside
this owner. STA/STD execution results are inputs. This keeps the boundary
honest while the real AGU, store-data path, ready-table, and bypass machinery
are still absent.

## Interface

Inputs:

- `flushValid`: suppresses all candidates and dequeue requests.
- `staValid`, `stdValid`: queue-head validity from `StoreDispatchQueues`.
- `sta`, `std`: queue-head `StoreSplitIssuePayload` rows.
- `staExec`, `stdExec`: explicit execution results containing `addr`, `data`,
  `size`, PE/thread scope, stack marker, scalar/SIMT merge scope, and SIMT
  lane.
- `staInsertReady`, `stdInsertReady`: downstream per-candidate insert
  readiness. `StoreDispatchSTQPath` now derives these with independent
  `STQInsertProbe` instances over the live `STQEntryBank` row image. They
  remain separate because a full STQ can reject a new STA allocation while
  still accepting a mergeable STD half.

Outputs:

- `staDequeueReady`, `stdDequeueReady`: dequeue commands for the accepted queue
  head.
- `insertValid`, `insert`: selected `STQStoreRequest`.
- `staRequest`, `stdRequest`: per-candidate request images for readiness
  probing and observability. Their `tSeq/uSeq` and T/U destination sidecars
  are copied from the queue-head `StoreSplitIssuePayload`.
- `staCandidate`, `stdCandidate`: payload plus execution-result readiness.
- `selectedSta`, `selectedStd`: one-cycle insertion choice.
- `blockedByStaExec`, `blockedByStdExec`: queue head waits for execution
  result.
- `blockedByStaInsert`, `blockedByStdInsert`: executed request waits for STQ
  insertion.
- `stdBypassStaBlocked`: STD was allowed to issue because the STA head was
  present and executed but could not insert.

## Logic Design

Candidates require queue-head validity, payload validity, execution-result
validity, and no flush:

```text
staCandidate = !flush && staValid && sta.valid && staExec.valid
stdCandidate = !flush && stdValid && std.valid && stdExec.valid
```

When both candidates can insert, STA wins. This matches the scalar model store
unit, which processes STA queues before STD queues. If the STA candidate is
present but cannot insert, a ready STD candidate may bypass it. This preserves
the model's important progress property: STQ fullness may block a new address
allocation, but a complementary data half can still merge into an existing
partial row and complete the store.

The selected queue head is dequeued in the same cycle that its request is
selected for insertion. No queue head is dequeued on flush or without the
corresponding execution result and insert readiness.

Request formation maps `StoreSplitStoreType` to `STQStoreType` with the same
numeric order:

| Store split payload | STQ request |
|---|---|
| `All` | `STQStoreType.All` |
| `Addr` | `STQStoreType.Addr` |
| `Data` | `STQStoreType.Data` |

The request copies `bid/gid/rid` from the renamed uop, converts the 32-bit
reduced `lsid` into the STQ ring-ID sidecar, and fills address/data/size plus
scope fields from the execution result.

The request also carries `tSeq/uSeq` and T/U destination ownership fields that
match the model `MemReqBus` sidecars. R61 moves those fields into
`StoreSplitIssuePayload` and this bridge now copies them into every
per-candidate request. If `tuDstValid` is false, the emitted destination kind
is forced to `DestinationKind.None`; otherwise the payload destination kind is
preserved. In the reduced `DecodeRenameROBPath` integration, the producer now
comes from `ScalarTURenameBridge`.

## Model Alignment

`StoreUnit::store` drains STA-side queues first, ordered by `(bid, lsID)`,
then drains STD-side queues. `StoreUnit::insertStq` accepts `ST_ALL` only when
the STQ can allocate, accepts a split half if it can merge with an existing
complementary row, and otherwise allocates a new partial row when space
exists.

`STQ::mergeStore` and `STQueueEntryInfo::init` allow split halves to arrive in
either order. This Chisel bridge therefore does not force the R48 atomic
rename-time split pair to enter STQ atomically. The queue pair is atomic at
dispatch admission, but the STQ boundary is partial-merge based.

`FakeLSU::mergeStore` follows the same conceptual split-half merge rule in the
fake memory path: a first half is held by `(bid, lsID)` and a complementary
second half completes the store.

`SPERename::Rename` snapshots `inst->tSeq` and `inst->uSeq` before T/U
destination rename, and `MemReqBus` carries those sequence snapshots through
LSU/STQ paths. `StoreDispatchToSTQ` is therefore a preservation owner for
those sidecars, not their producer.

## Deferred Owners

- Real STA address generation and STD data selection.
- Live `tSeq/uSeq` and T/U destination sidecars from the T/U rename owner into
  `StoreSplitPayload` in the reduced backend composition.
- Load-conflict probe publication after accepted STQ insert.
- Store-data wakeup, ready-table, memory trace, and exception side effects.

## Verification

Focused gate:

```bash
bash tools/chisel/run_chisel_tests.sh --only StoreDispatchToSTQ
```

Affected gates:

```bash
sbt --client --error 'Test / compile'
bash tools/chisel/run_chisel_tests.sh --only STQInsertProbe
bash tools/chisel/run_chisel_tests.sh --only StoreDispatchSTQPath
bash tools/chisel/run_chisel_tests.sh --only StoreDispatchQueues
bash tools/chisel/run_chisel_tests.sh --only STQEntryBank
bash tools/chisel/run_chisel_tests.sh --only StoreSplitPayload
bash tools/chisel/run_chisel_tests.sh --only DecodeRenameROBPath
bash tools/chisel/run_chisel_tests.sh --only InterfaceBundles
bash tools/chisel/run_chisel_rob_bookkeeping.sh --reduced-rob
bash tools/chisel/run_chisel_top_xcheck.sh
bash tools/chisel/run_chisel_qemu_crosscheck.sh --dry-run
bash tools/chisel/build_chisel.sh
bash tools/chisel/run_chisel_verilator_lint.sh
```

Focused tests cover STA priority, STD bypass when STA cannot insert,
execution-result versus insert backpressure diagnostics, flush suppression, IO
widths, enum ordering, and CIRCT elaboration. R61 extends the checks to the
sidecar-carry ports on `sta`, `std`, and `insert`.
