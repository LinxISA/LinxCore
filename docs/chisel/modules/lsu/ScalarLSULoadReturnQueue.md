# ScalarLSULoadReturnQueueBank

## Status

- Package: `linxcore.lsu`
- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/ScalarLSULoadReturnQueue.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/ScalarLSULoadReturnQueueSpec.scala`
- Architecture contract: `LC-MA-MEM-001`
- Integration: canonical `ScalarLSULoadPath` plus live reduced timing path

## Purpose

`ScalarLSULoadReturnQueueBank` is the retained handoff from scalar LSU return
publication to the IEX E4/W1/W2 pipeline. It replaces the unscoped global
two-entry LRET sink with independent per-STID, per-return-pipe queues.

The bank preserves the model ownership split:

1. LSU resolves and publishes returned memory data.
2. A queue retains the complete return identity and payload.
3. IEX drains only when an E4 slot can accept the entry.
4. W2 performs required ROB resolve, RF writeback, and wakeup atomically.

The queue bank does not implement cache refill policy, W2 side effects, or
architectural recovery. It retains payloads and applies the accepted typed
recovery decision supplied by the backend.

Canonical `ScalarLSULoadPath` reserves exact lane capacity at launch, carries
the selected pipe in the LIQ row, and replaces that reservation with a resident
entry only when ResolveQ accepts the same E4 hit. The reduced timing top uses
the same queue bank behind its detailed replay-return publication controls.

## Parameters

| Parameter | Meaning |
| --- | --- |
| `idEntries` | ROB ring identity capacity used by BID/GID/RID/LSID. |
| `stidCount` | Number of independent scalar STID lanes. |
| `returnPipeCount` | Number of IEX load-return pipe destinations per STID. |
| `queueDepth` | Entries retained in each STID/pipe lane. |
| `addrWidth`, `pcWidth`, `dataWidth`, `sizeWidth` | Return payload geometry. |
| `peIdWidth`, `stidWidth`, `tidWidth` | Linx execution-scope identity widths. |
| `archRegWidth`, `physRegWidth` | Destination metadata widths. |

`loadReturnQueueEntries` and `loadReturnPipeCount` in `ScalarLsuParams` provide
the production sizing values. Queue sizing never changes ROB identity width.

## Entry Identity

Every resident entry contains:

- PE, STID, and TID scope;
- BID, GID, RID, and load LSID ring identities;
- PC, address, size, and scalar return data;
- destination and source-trace metadata;
- selected return-pipe index;
- speculative-wakeup and stack-valid state.

RID selects the ROB row. STID selects the scalar lane. BID/GID/LSID govern
ordering and recovery. The queue must not reconstruct one identity from
another.

## Admission

Admission is split into two phases:

1. `preEnqueueReady` reports whether the selected STID has queue credit before
   return-pipe selection. It does not depend on the selected pipe index.
2. `enqueueReady` validates the final STID/pipe target and that lane's credit.
   `enqueueAccepted` commits the payload to exactly one lane.

This split prevents a combinational cycle through consumer readiness, return
pipe selection, and target queue readiness. The live integration asserts that
every published LRET request is accepted atomically.

An invalid STID or pipe index is rejected and diagnosed. A full lane blocks
only that target; independent lanes retain their available credit. A dequeue
from a full lane may open same-cycle enqueue capacity, but an empty lane does
not bypass directly to IEX. The latter preserves the registered model queue
boundary.

`full` is resident bank state and asserts only when every lane is full;
`blockedByFull` is request-local and asserts only for a valid payload targeting
a full selected lane during an active non-prune cycle. These diagnostics must
not be qualified or distorted by an absent request, hard flush, or precise
prune.

## Drain Arbitration

Each lane preserves FIFO order. `RRArbiter` selects among nonempty lane heads
for the shared IEX receive port. A blocked selected head remains resident.
After a successful drain, arbitration rotates so continuously active STIDs
cannot starve one another.

The drained payload is accompanied by PE/STID/TID, pipe, and lane diagnostics.
IEX still checks E4 residency and ROB row validity before consuming it.

## Recovery

`flush` is the hard reset/restart path and clears all lanes. `preciseFlush`
uses the typed Linx `FlushBus` predicate:

- STID always matches;
- PE and TID match when their scopes are enabled;
- BID-only, group, or BID/LSID ordering follows the selected recovery class;
- matching entries are removed and survivors compact in FIFO order.

Precise pruning blocks concurrent enqueue and dequeue for one cycle. It does
not clear older entries or entries belonging to another STID. Canonical
`ScalarLSULoadPath` shares its hard and typed precise flush with LIQ, ResolveQ,
reservations, and LRET. The live reduced top feeds accepted backend recovery to
the same typed port and reserves hard clear for frontend reset, start, or
restart.

## Model Alignment

LinxCoreModel creates `lsu_iex_lret_array[stid][pipe]` queues in `IEX`, writes
them from `LDQInfo::returnData`, and drains them only when `BackToPipe` reports
a free E4 slot. `LDAPipe::runW2` later publishes RF and resolve side effects.

The Chisel bank preserves the useful ISA-neutral mechanisms: retained queues,
per-lane credit, fair shared drain, registered E4/W1/W2 staging, and scoped
flush. It deliberately rejects ARM exception levels, condition state,
exclusive monitors, barrier encodings, and acquire/release opcode policy.

## Verification

`ScalarLSULoadReturnQueueSpec` covers:

- independent per-STID and per-pipe FIFO order;
- target-local full backpressure;
- resident all-lane fullness and selected-lane blocking diagnostics;
- same-cycle full-lane dequeue/enqueue compaction;
- round-robin drain fairness;
- precise killed-suffix compaction across ROBID wrap with independent-lane
  survival and concurrent-mutation suppression;
- STID pre-admission credit when the final selected pipe is full;
- invalid STID and pipe rejection;
- elaboration of the parameterized queue bank and recovery diagnostics.

`run_chisel_scalar_lsu_load_path_return_probe.sh` proves canonical launch
reservation, same-lane blocking, independent-pipe progress, scalar extraction,
atomic ResolveQ+LRET acceptance, exact drain identity, released-credit relaunch,
and hard-flush cleanup on generated RTL.

The full live-top suite, generated RTL xcheck, and CoreMark replay remain the
integration gates because they exercise the queue together with IEX residency,
W1/W2 completion, RF writeback, ROB completion, and LIQ lifecycle release.
