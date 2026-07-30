# OOO Load Attempt / Canonical LIQ Binding Plan

## 1. Status and scope

This document freezes the production ownership seam between the OOO load
producer and the scalar LSU. I0.15b implements the first bounded packet:

- exact OOO-compatible producer and attempt-generation sidecar,
- atomic co-allocation with one canonical LIQ row,
- generation-qualified rebind of that same resident row,
- exact retention through LIQ, LHQ/ResolveQ, LRET, W1, and W2,
- flush, stale-token, lifecycle-race, and row-reuse rejection.

I0.15b does **not** claim that the OOO load path is fully integrated. I0.15c-a
now adds the production-width three-AGU allocation bridge, parent-PC capture,
and exact youngest-older-store LSID tail required by canonical forwarding. The
existing `OooIexLoadUnit` still remains a duplicate request/retry/result owner
until I0.15c-b routes the accepted bridge transaction into
`ScalarLSULoadPath` and returns its terminal result.

## 2. Ownership

| State or decision | Sole owner | Contract |
|---|---|---|
| ROB-member producer identity and speculative attempt generation | OOO | `RobMemberKey + load generation`; a generation alone is never authoritative. |
| Physical load residency and `loadId` slot/wrap lease | `LoadInflightQueue` | Allocation, Wait/Repick/L1DcMiss/L2Wait/Resolved, replay, refill, and row release. |
| Memory ordering | Scalar LSU | Full LSID, store snapshot, wait-store, forwarding, miss, and replay rules never use the OOO attempt token as an ordering key. |
| Terminal return residency | LRET queue and W1/W2 pipeline | Retains the exact attempt until the terminal atomic side effects fire. |
| ROB acceptance of a returned load | Future OOO/LSU adapter | Must validate both canonical LSU row identity and the exact producer/attempt token. |

The OOO token is a sidecar. It cannot mutate LIQ status, select forwarding,
compare load/store age, or replace full LSID authority.

## 3. Exact attempt ABI

`LoadAttemptIdentity` contains:

- attempt `valid`,
- producer `valid`, PE ID, STID,
- native BID valid/value and BROB generation,
- RID slot and RID generation,
- ROB member index and resident generation,
- load-attempt generation.

The LSU bundle is deliberately independent of `OooParams`; the adapter maps
`RobMemberKey` field by field. Protocol fields reserve 16 bits for scope/index
values and 32 bits for generations. A bridge must use elaboration-time
`require` checks proving every native width fits and must zero-extend.
`LoadAttemptIdentity.requireBridgeFits` is the executable proof point: the
production `OooParams` profile passes, while any over-wide native field rejects
during elaboration. Silent truncation, reconstruction from legacy
`bid/gid/rid`, and opaque hash matching are forbidden.

An invalid attempt is legal for legacy/non-OOO allocation, but it cannot use
the rebind port. A token marked valid without valid producer and native-BID
identity is malformed and allocation rejects it. Production OOO allocation
must provide a valid exact token.

## 4. Handshakes

### 4.1 Initial allocation

`allocValid && allocReady` creates the canonical LIQ row and captures the
attempt in the same state transition. There is no later best-effort sideband
join. The returned `allocLoadId` is the physical LIQ lease used by subsequent
rebind requests.

### 4.2 Rebind

`attemptRebind` carries `{loadId, current, next}`. Acceptance requires:

1. no hard or precise flush,
2. an exact valid slot-plus-wrap `loadId` match,
3. a resident row in `Wait`, `L1DcMiss`, or `L2Wait`,
4. no same-cycle launch/pick of that row,
5. exact equality between the row token and `current`,
6. identical producer identity in `current` and `next`,
7. `next.generation == current.generation + 1` modulo the protocol width.

Rebind changes only `row.attempt`. It preserves address, destination, full
LSID, miss state, wait-store state, forwarded bytes, source-return evidence,
and allocation lease. Wrong producer, stale/skipped generation, terminal
residency, same-cycle launch, flush, and row reuse all reject without mutation.

### 4.3 Terminal return

The exact token follows this carrier chain:

```text
LoadInflightAlloc
  -> LoadInflightRow
  -> LoadHitRecord / ResolveQ
  -> LoadReplayReturnLretPayload
  -> LRET queue
  -> ScalarLSULoadReturnPipeline W1/W2
  -> completion.payload.attempt
```

`robLookupAttempt` exposes the same token during the pre-insert ROB validation
cycle. Invalid/data-blocked formatter output emits an invalid zero token.
Queue compaction, W1/W2 backpressure, and precise survivor movement copy the
whole retained entry and therefore cannot substitute a newer row's identity.

## 5. Recovery and race precedence

Hard and precise flush outrank allocation, rebind, launch, replay/refill
mutation, and terminal publication. A request visible during a flush reports
`blockedByFlush` and cannot fire. A precise flush may preserve an older row,
but rebind is still suppressed for the flush cycle; the producer retries only
after the surviving row and recovery epoch are stable.

Rebind is rejected once the row is `Repick` or `Resolved`. This prevents an E4
result or already-retained terminal return from changing generation under the
consumer. Same-cycle launch/pick is also rejected so the launched attempt and
resident row token cannot disagree.

## 6. Verification matrix

| Level | Required evidence |
|---|---|
| LIQ UT | co-allocation; exact next-generation rebind; same generation/different producer; skipped/stale generation; launch race; hard/precise flush; natural same-slot/opposite-wrap reuse; generation wrap. |
| LRET UT | formatter invalidation on missing data; exact token under queue backpressure and compaction. |
| W1/W2 UT | exact `robLookupAttempt`; W1/W2 retention under blocked terminal sinks; precise survivor retention. |
| OOO/LSU IT | alloc -> miss/cancel -> rebind -> final return; recovery with pending response; stale response after row reuse; exact ROB-member acceptance. |
| Parameter gate | unequal `LIQ=4`, `ROB=8`, `STQ=4`, `LSID=40`; 2/4/6 OOO widths once the adapter is present. |

I0.15b contains dynamic LIQ and W1/W2 tests at unequal capacities and a
40-bit LSID. It also executes the production-width fit proof and rejects
truncating bridge configurations. The OOO/LSU IT rows remain I0.15c exit
criteria.

## 7. Remaining gaps

### I0.15c-a: typed allocation seam (implemented)

- `OooIexLoadLiqAllocAdapter` fairly arbitrates the three physical AGU lanes
  into one canonical LIQ allocation port without retaining a second request.
- The adapter calls `LoadAttemptIdentity.requireBridgeFits` and maps every
  `RobMemberKey` field, attempt generation, 40-bit full LSID, GPR destination,
  address, access size/sign, PC, and physical return-pipe lane explicitly.
- Every scalar load now obtains its architectural parent PC at P1/I1 even when
  the address is base/index relative. MDB/store-set/replay indexing is not
  allowed to depend on a zero placeholder PC.
- `OooMemoryIdState` now retains
  `{youngestStoreLsidValid, youngestStoreLsid}`. A type-local store counter is
  insufficient to reconstruct the unified LSID when loads and stores
  interleave; the allocator updates this tail on store allocation and existing
  ROB snapshots/recovery carry it as part of the exact state.
- Dynamic UT proves three-lane fairness, output backpressure, no generation
  consumption on recovery, destructive draining of hard-flushed and exact
  killed requests, fail-closed missing PC, exact producer plus displaced-PTag
  mapping, narrow projection checks, and unequal `LIQ=4`, `ROB=8`, `STQ=4`,
  `LSID=40` geometry.
- Same-BID replay wake ordering now uses the 40-bit wake/snapshot LSIDs rather
  than their ROBID projections. Missing full authority and half-range serial
  ambiguity fail closed and are exported as LIQ diagnostic masks.
- Allocation keeps speculative wakeup disabled until I0.15c-b supplies the
  exact wake/cancel owner in the same live composition.

### I0.15c-b: make canonical residency live

- Route all three production load lanes from AGU issue into canonical LIQ
  allocation/launch and route terminal W2 results back to exact OOO completion.
- Replace `OooIexLoadUnit`'s duplicated request/miss/retry/result residency;
  OOO retains only speculative dependency/cancel policy.
- Make one common recovery plan fence OOO attempt state, LIQ state, LRET, and
  W1/W2 before apply.
- Add exact fault/exception terminal payload and prove data/fault are mutually
  exclusive.

#### Cleanup and cutover sequence

The duplicate `OooIexLoadUnit` tracker is removed by replacement, not by
copying its state into another OOO queue:

1. Add one non-resident production ownership bridge.  It must make canonical
   LIQ allocation and OOO metadata-sidecar allocation one atomic ready/valid
   transaction, retain no address/miss/replay/data state, and join only the
   exact canonical `{loadId,attempt}` completion back to the OOO terminal.
2. Bind canonical LIQ launch and attempt rebind events to speculative wakeup
   and cancel policy.  Launch/rebind must be qualified by the same row lease
   and attempt token; allocation is too early to publish a speculative wakeup.
3. Cut the three AGU lanes in `OooIexExecutionCluster` over to that bridge,
   join its recovery readiness into the existing common recovery fire, replace
   the three abstract memory request/response ports with canonical LSU ports,
   and then delete `OooIexLoadUnit` plus its migration-only tests/docs.

Regression order is fixed: preserve the old load-unit and execution-cluster
tests until the replacement IT covers hit, miss/rebind, fault, terminal
backpressure, stale return, exact recovery kill, and three-lane fairness.  The
old owner may be deleted only in the same packet that makes those replacement
gates green; a second dormant load tracker is forbidden.

### I0.15d and later: close memory-response identity

- E3/E4 launch tracking currently retains a LIQ index, while cache/refill and
  source-return packets are not all attempt-tagged. Every asynchronous return
  must revalidate the canonical row lease and relevant attempt generation.
- Compose the three canonical STQ forwarding response pipes directly with
  LIQ launch/result mutation; remove the remaining abstract compatibility CAM.
- Close physical DTLB, PMP/PMA, L1D/coherence, device/MMIO, cross-line, and
  exception flows under the same owner contract.
- Add timeout/liveness counters, safe-mode behavior, DFX observability, formal
  stale-response properties, synthesis timing, and natural workloads.

## 8. Relationship to `Documents/a.txt`

The reference notebook motivates speculative load issue, load-generation
cancel/replay, LIQ repick, and retained return mechanisms. LinxCore reuses
those physical techniques but does not copy ARM-specific queue IDs or recovery
semantics. Linx native ROB/BROB member generations, full LSID ordering, and
canonical LIQ ownership remain authoritative; a queue index or numerical load
generation alone is insufficient.
