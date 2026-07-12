# STQFlushPrune

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/STQFlushPrune.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/STQFlushPruneSpec.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/ModelCommon/bus/FlushBus.h`
  - `model/LinxCoreModel/model/ModelCommon/bus/MemReqBus.h`
  - `model/LinxCoreModel/model/lsu/store_unit/store_unit.cpp`
  - `model/LinxCoreModel/model/lsu/store_unit/stq.h`
  - `model/LinxCoreModel/model/lsu/store_unit/stq.cpp`
- Related Chisel contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/recovery/FlushControl.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/recovery/RecoveryCleanupControl.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/STQEntryBank.scala`
- Contract IDs: `LC-CHISEL-LSU-STQ-FLUSH-001`

## Purpose

`STQFlushPrune` is the first concrete LSU/STQ consumer for the registered
recovery cleanup intent. It implements the model-derived STQ flush predicate
and reports which store-queue rows should be freed.

It is not a full STQ. It does not own store insertion, data/address readiness,
commit, memory issue, `storeCommitQ`, load-store forwarding, SCB/MDB state, or
queue RAM mutation. `STQEntryBank` is the first state owner that consumes this
module's `freeMask`.

## Interface

### Inputs

| Signal | Type | Description |
|---|---|---|
| `flush` | `FlushBus` | Annotated recovery request from `RecoveryCleanupControl.intent.flush`. |
| `rows` | `Vec[STQFlushPruneEntry]` | Current STQ row sidecars and FSM state. |

### `STQFlushPruneEntry`

| Field | Description |
|---|---|
| `valid` | STQ row is allocated. |
| `status` | Model STQ FSM state: `Wait`, `Commit`, `Miss`, `L2Wait`, `Idle`, or `Resolved`. |
| `peId` | Memory request PE owner. |
| `stid` | Scheduler/thread domain used by `FlushBus::match`. |
| `tid` | Thread owner used by thread-scoped recovery. |
| `bid` | Store request block identity. |
| `gid` | Store request group identity. |
| `lsId` | Transitional ROBID-shaped lookup projection; never used for R671 store-prune age. |
| `lsIdFull` | Canonical parameterized store memory-order identity. |

### Outputs

| Signal | Type | Description |
|---|---|---|
| `matchMask` | `UInt(entries.W)` | Valid rows covered by `FlushBus::match`. |
| `freeMask` | `UInt(entries.W)` | Matched rows in `Wait` state that the future STQ owner should free. |
| `statusBlockedMask` | `UInt(entries.W)` | Matched rows preserved because their status is not `Wait`. |
| `fullLsIdRequiredMask` | `UInt(entries.W)` | In-scope same-BID rows whose non-BID decision requires full LSID. |
| `fullLsIdMissingMask` | `UInt(entries.W)` | Required rows blocked because the request lacks full-LSID authority. |
| `fullLsIdAmbiguousMask` | `UInt(entries.W)` | Required rows blocked by exactly half-range serial separation. |
| `freeCount` | `UInt(log2Ceil(entries + 1).W)` | Population count of `freeMask`. |

## State

The module is combinational. `STQEntryBank` holds the first row state and
applies `freeMask`; later LSU owners will add store-commit queue, SCB/MDB,
forwarding, and data-array side effects.

## Logic Design

`STQFlushPrune.matchesFlush` mirrors `FlushBus::match(MemReqBus)`:

- invalid flushes and invalid rows never match;
- `stid` must match;
- PE and thread identity must match when the flush is PE- or thread-scoped;
- `baseOnBid` compares only BID age;
- `baseOnGroup` accepts same-or-younger BIDs first, then requires
  `lsIdFullValid` for the `(bid, gid, full LSID)` tuple path;
- the default path requires `lsIdFullValid` and compares
  `(bid, full LSID)`;
- full LSID uses `LSIDOrder` modulo serial arithmetic. Exactly half-range
  separation is ambiguous and does not match;
- missing full-LSID authority never falls back to the ROBID projection.

The free decision then applies the STQ-specific model rule:

```text
free(row) = match(row) && row.status == Wait
```

All other matched statuses are exposed through `statusBlockedMask` so later
integration tests can distinguish "not covered by recovery" from "covered but
not freeable by this STQ rule".

## Timing

All masks are combinational functions of the current row sidecars and the
selected `FlushBus`. A future STQ should register or apply the masks in its
own state-update phase and avoid feeding queue mutation back into recovery
selection combinationally.

## Flush/Recovery

This module consumes the `lsuFlushValid/stqFlushValid` intent from
`RecoveryCleanupControl` by using `intent.flush` as its flush input. It does
not produce frontend restart tokens, rename restore state, ROB pruning, or
BROB pointer updates.

## Trace/Observability

The masks are suitable for a future recovery trace event:

- `matchMask` records the selected recovery coverage.
- `freeMask` records the actual STQ rows to be freed.
- `statusBlockedMask` records matched rows that remain because the model only
  frees `STQ_WAIT` entries.
- the full-LSID diagnostic masks distinguish missing source promotion and
  unreachable half-range contract violations from ordinary age non-matches.

No architectural commit trace row is emitted by this module.

## Verification

- `bash tools/chisel/run_chisel_tests.sh --only STQFlushPrune`
- `bash tools/chisel/run_chisel_tests.sh --only RecoveryCleanupControl`
- `bash tools/chisel/run_chisel_tests.sh --only FlushControl`
- `bash tools/chisel/run_chisel_tests.sh --only ROBEntryBank`
- `bash tools/chisel/run_chisel_rob_bookkeeping.sh --reduced-rob`
- `python3 tools/chisel/trace_schema_adapter.py --self-test`
- `bash tools/chisel/run_chisel_qemu_crosscheck.sh --dry-run`

Focused tests cover base-on-BID freeing, full-width BID+LSID comparison,
40-bit wrap and high-bit distinction, missing/half-range authority blocking,
group comparison with the model BID fast path, STID/PE/thread scoping, invalid
flush suppression, non-`Wait` status preservation, unequal width contracts,
and Chisel elaboration.
