# MDBSSIT

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/MDBSSIT.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/MDBSSITSpec.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/lsu/mdb/MDB.h`
  - `model/LinxCoreModel/model/lsu/mdb/MDB.cpp`
  - `model/LinxCoreModel/model/lsu/load_unit/ldq.cpp`
  - `model/LinxCoreModel/model/lsu/store_unit/store_unit.cpp`
  - `model/LinxCoreModel/model/ModelCommon/bus/MemReqBus.h`
  - `model/LinxCoreModel/configs/lsu_config.h`
  - `model/LinxCoreModel/configs/lsu.toml`
- Related Chisel contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/MDBConflictDetect.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/rob/ROBID.scala`
- Contract IDs: `LC-CHISEL-LSU-MDB-SSIT-001`

## Purpose

`MDBSSIT` is the first Chisel state owner for the LinxCoreModel MDB Store Set
ID Table. It sits after `MDBConflictDetect` and before future queue wrappers
that will provide `lookup_lu_mdb_q`, `lookup_mdb_lu_q`, `lookup_mdb_su_q`,
`record_lu_mdb_q`, and `delete_lu_mdb_q` storage.

The module owns:

- CAM lookup by load PC,
- first-after-nuke suppression and one-shot clear,
- model confidence and weight-based stall qualification,
- store PC plus predicted store BID generation from `bid_off`,
- record allocation, replacement, reinforcement, and confidence decrement,
- delete/decay behavior for failed waits,
- finite-table overflow reporting.

It does not own the queue storage around MDB, `LDQInfo.updateMDBInfo`,
`StoreUnit.mdbCheck`, STQ store wakeup, BCTRL `bmdb`, IEX-local MDB, byte
forwarding, ROB nuke retirement, or final recovery publication.

## Interface

### Inputs

| Signal | Type | Description |
|---|---|---|
| `lookup.valid/loadPc/loadBid` | `MDBSSITLookupRequest` | Load-side MDB lookup equivalent to one consumed `lookup_lu_mdb_q` entry. |
| `delete.valid/loadPc/storePc` | `MDBSSITDeleteRequest` | Failed wait-store feedback equivalent to `delete_lu_mdb_q`; `storePc` is model `wait_tpc`. |
| `record.valid/*` | `MDBSSITRecordRequest` | Load/store conflict record equivalent to `record_lu_mdb_q`. |

### Outputs

| Signal | Description |
|---|---|
| `lookupResponseValid` | Mirrors `lookup.valid`; future queue fanout sends the response to both LU and SU. |
| `lookupTableHit` | Load PC matched an SSIT row, regardless of whether the row currently stalls. |
| `lookupHit` | Model stall prediction: table hit, not first-after-nuke, `conf > 0`, and `weight >= stallThreshold`. |
| `lookupFirstAfterNuke` | The row's `nukeVld/nukeBID` suppressed this lookup. The row clears `nukeValid` after lookup. |
| `lookupConfBlocked` / `lookupWeightBlocked` | Why a table hit did not become a stall prediction. |
| `lookupStorePc` / `lookupStoreBid` | Predicted store identity copied into future `MDBBus.stInfo`. |
| `deleteMatched` | Delete command matched load PC and store PC. |
| `deleteReleased` | Delete found weight already zero and invalidated the row. |
| `deleteDroppedBelowStall` | Delete decremented weight and the row no longer qualifies for `lookupHit`. |
| `recordAccepted` | Record command mutated or allocated a row. |
| `recordAllocated` | Record inserted a new load-PC row. |
| `recordReplaced` | Different-store record replaced an existing row due to lower confidence or closer store age. |
| `recordReinforced` | Same-store record incremented confidence and weight. |
| `recordDecremented` | Different-store record was farther and decremented confidence. |
| `recordOverflow` | New load PC arrived while the finite Chisel table was full. |
| `recordOrderIllegal` | Store BID was younger than load BID; the model asserts this case. |
| `validMask` / `entryCount` / `table` | Observability for integration and tests. |

## State

Each SSIT row stores:

- valid bit,
- load PC key,
- predicted store PC,
- `bidOff = DeltaBID(load.bid, store.bid)`,
- `lsIdOff = DeltaBID(load.lsID, store.lsID)`,
- 2-bit confidence,
- saturating weight,
- `nukeValid` and `nukeBid`.

The C++ model uses an unbounded `unordered_map`. Chisel uses a parameterized
fully associative finite table and reports overflow instead of silently
choosing a replacement policy. New rows are allocated into the first free slot.
The C++ miss insertion path initializes `st_pc`, `bid_off`, `conf`, `nukeVld`,
`nukeBID`, and `weight`; the Chisel row also initializes `lsIdOff` from the
same conflict record so later equal-`bidOff` replacement comparisons are
deterministic.

## Logic Design

`MDB::Work` runs lookup, delete, and record in that order. `MDBSSIT` preserves
that visible ordering in one cycle:

1. Lookup reads the pre-cycle table, computes the response, and clears
   `nukeValid` on any load-PC table hit.
2. Delete observes the post-lookup table. If the matching row weight is zero,
   it releases the row. Otherwise it decrements weight and reports when the
   new weight is below the stall threshold.
3. Record observes the post-delete table. It allocates, replaces, reinforces,
   or decrements confidence according to `MDB::insert`.

The model formulas are preserved:

- `initWeight = (mdb_max_weight + 1) * mdb_release_weight / 100`
- `stallThreshold = initWeight + 1`
- `lookupHit = tableHit && !firstAfterNuke && conf > 0 && weight >= stallThreshold`
- same-store records saturate confidence at 3 and weight at `mdb_max_weight`
- delete releases only when weight is already zero before decrement

Default parameters match LinxCoreModel LSU config values:

- `mdb_release_weight = 25`
- `mdb_max_weight = 3`
- `mdb_inc_step = 1`

## Timing

The owner is a registered table with combinational lookup, delete, record, and
next-state computation over the current row image. Future queue wrappers may
pipeline or lane-split around this owner, but must preserve lookup before
delete before record ordering for same-cycle commands.

## Flush/Recovery

`MDBSSIT` does not consume `FlushBus`. Recovery effects enter through
`record`, which sets `nukeValid` for the conflicting load BID, and through
`lookup`, which suppresses the first lookup from that same BID. ROB nuke
retirement remains owned by the later ROB/recovery packet.

## Trace/Observability

The table, masks, and per-command diagnostics are exposed for later memory
trace rows. The `table` output reflects the post-lookup/post-delete/post-record
next-state image for the current command cycle. QEMU and LinxCoreModel
cross-checks cannot observe this packet directly until the Chisel top emits
live memory/recovery events.

## Verification

- `bash tools/chisel/run_chisel_tests.sh --only MDBSSIT`
- `bash tools/chisel/run_chisel_tests.sh --only MDBConflictDetect`
- `bash tools/chisel/run_chisel_tests.sh --only STQCommitQueue`
- `bash tools/chisel/run_chisel_rob_bookkeeping.sh --reduced-rob`
- `python3 tools/chisel/trace_schema_adapter.py --self-test`
- `bash tools/chisel/run_chisel_qemu_crosscheck.sh --dry-run`

Focused reference tests cover new-row first-after-nuke suppression, weight
threshold stall qualification, same-store reinforcement, closer-store
replacement, farther-store confidence decrement, delete decay and release,
different-BID lookup clearing nuke state, illegal order rejection, finite-table
overflow, and Chisel elaboration.
