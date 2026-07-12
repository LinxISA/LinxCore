# LoadMissQueue

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadMissQueue.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/LoadMissQueueSpec.scala`
- Integrated owner: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/ScalarLSULoadPath.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/lsu/load_unit/ldq.cpp`
    - `LDQInfo::pickL2`
    - `LDQInfo::handleL2Lookup`
    - `LDQInfo::handleL1Wakeup`
  - `model/LinxCoreModel/model/lsu/load_unit/ldq.h`
    - `missQ`
    - `ldqPkts`
  - `model/LinxCoreModel/model/l1/cluster.cpp`
    - `L1Clusters::refillCache`
    - `L1Clusters::handleRefill`
- Contract IDs: `LC-MA-MEM-001`, `LC-MA-HAZ-001`, `LC-MA-RES-001`

## Purpose

`LoadMissQueue` is the canonical cacheable scalar owner between an E4
`DataNotComplete` result and a lower-memory cache-line request. It converts an
otherwise transient miss into retained state, coalesces later misses to the
same line, issues one ordered request per unique line, and turns an exact
response into the existing LIQ refill packet.

The owner adapts the model's line-keyed `missQ` to synthesizable hardware. A
line alone is not sufficient response identity after flush or slot reuse, so
each hardware entry adds a miss slot plus generation. This is an ISA-neutral
OOO/cache mechanism. Linx PE/STID/TID/BID/GID/RID/full-LSID identity controls
recovery; ARM barriers, exclusives, acquire/release, exception levels, and
architectural cache-maintenance behavior are not part of this module.

## Parameters

| Parameter | Meaning |
|---|---|
| `missEntries` | Physical unique-line miss entries and request-order capacity. |
| `liqEntries` | Number of dependent LIQ slots represented per entry. |
| `idEntries` | ROB slot-plus-wrap domain for BID/GID/RID compatibility fields. |
| `lsidWidth` | Independent full Linx memory-order identity width. |
| `lineBytes` | Cache-line bytes; current scalar integration requires 64. |

No parameter derives from another. Tests must include unequal miss, LIQ, ROB,
and LSID dimensions.

## Miss Admission

An E4 miss candidate carries its LIQ index and complete resident row identity.
Admission follows these rules:

1. Treat `missValid` as upstream cacheable admission, then reject disabled,
   flushed, invalid, tile, or zero-size candidates locally. The current scalar
   composition is a normal-memory profile; a future memory-attribute owner must
   withhold `missValid` for Device/MMIO/cache-maintenance requests.
2. If an entry already owns the aligned line, add or confirm the exact LIQ
   dependent without allocating another request token.
3. Otherwise allocate a free physical entry and enqueue its index into the
   request-order FIFO atomically.
4. Refuse admission when neither coalescing nor atomic entry-plus-token
   allocation is possible. Canonical composition prevents this case by
   reserving worst-case capacity at E2 launch and reports any violation.

Each dependent stores LIQ slot/generation, PE, STID, TID, BID, GID, RID,
projected load LSID, and full-LSID validity/value. Slot index alone is never
recovery or response authority.

## Request Issue

The request FIFO preserves first-miss allocation order. Its head behaves as an
irrevocable ready/valid source:

- a live entry with surviving dependents emits one read request containing
  miss slot/generation and aligned line address;
- backpressure keeps every request field stable;
- acceptance marks the entry issued and removes only its FIFO token;
- an unissued entry whose dependents were all pruned is dropped and freed
  without lower-memory traffic.

Same-line coalescing remains legal before or after request issue.

## Response and Refill

A response is accepted independently of whether it matches. A response matches
only when miss slot, generation, and line address all agree with one issued
entry. A matching read response:

1. emits one `LoadRefillWakeupRequest` with full line data and L2-miss metadata;
2. frees the physical entry;
3. toggles its generation before reuse.

A stale, duplicate, wrong-line, non-read, or otherwise malformed response emits
no LIQ refill and raises diagnostics. It may not mutate a current entry by line
address alone. An invalid miss ID or non-read type does not free the entry even
when slot, generation, and line otherwise agree; only a valid read response
retires the retained transaction.

## Flush and Recovery

Hard flush and typed precise recovery remove matching dependent identities.
The precise predicate is exactly `LoadQueueFlushMatch`; missing full-LSID
authority retains same-BID state rather than falling back to a projection.

- An unissued entry with no survivors is canceled before request issue.
- An issued entry with no survivors remains as an orphan until its exact
  response drains.
- A later same-line cacheable load may coalesce onto that issued entry.
- A response for an older generation cannot target a reused entry.

## Canonical Composition

`ScalarLSULoadPath` reserves one potential miss entry for every accepted LIQ
launch and releases that reservation at E4 on every hit, wait, replay, or miss.
Launch is blocked when physical free entries are not greater than outstanding
reservations. Therefore an E4 miss must always be accepted by the queue; a
dropped E4 candidate is a protocol error.

The miss queue participates in scalar-LSU `empty`/`pending` state. Existing
external refill injection remains a verification compatibility path and must
not collide with a queue-owned response refill.

## Verification

- `bash tools/chisel/run_chisel_tests.sh --only LoadMissQueue`
- `bash tools/chisel/run_chisel_tests.sh --only ScalarLSULoadPath`
- `bash tools/chisel/run_chisel_tests.sh --only ScalarLSU`
- `bash tools/chisel/run_chisel_load_miss_queue_probe.sh`

Reference and elaboration tests cover unique-line FIFO issue, same-line
coalescing, request backpressure, exact refill, stale generation rejection,
precise dependent pruning, unissued cancellation, issued-orphan retention,
hard-flush behavior, and independent sizing.
