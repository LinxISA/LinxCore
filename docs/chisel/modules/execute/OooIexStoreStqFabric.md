# OooIexStoreStqFabric

## Purpose

`OooIexStoreStqFabric` is the canonical static composition for retained store
execution and Store Queue residency. It replaces the single-lease integration
harness with a production-shaped owner that can keep multiple logical stores
live at once, accept two independent STA lanes and two independent STD lanes,
and converge both halves only in `STQEntryBank`.

Source and test owners:

- `chisel/src/main/scala/linxcore/ooo/OooIexStoreStqFabric.scala`
- `chisel/src/main/scala/linxcore/ooo/OooIexExecutionStorePipeline.scala`
- `chisel/src/test/scala/linxcore/ooo/OooIexStoreStqFabricSpec.scala`
- `chisel/src/test/scala/linxcore/ooo/OooIexExecutionStoreIntegrationSpec.scala`
- `chisel/src/main/scala/linxcore/ooo/OooIexIssue.scala`
- `chisel/src/test/scala/linxcore/ooo/OooIexIssueSpec.scala`

## Ownership contract

Every split store reserves its exact one- or two-row STQ lease while its
transaction is retained in issue S1. S2 publication remains blocked until all
logical stores in that S1 transaction have fired their reservation request.
Reservation is fair across STIDs, and each logical store is represented by its
address child together with full memory-order allocation.

STA and STD do not carry a queue-index-derived lease. On arrival, the fabric
reconstructs the generation-qualified lease by scanning canonical STQ rows
and matching all of:

- PE/STID, native BID and BROB generation;
- RID slot and generation;
- logical first member and resident generation;
- full LSID, full store ID, request count, and beat number;
- `WAIT` status and lease generation.

A missing, duplicate, partial, stale, or non-`WAIT` match keeps the execute
transaction upstream and reports `leaseLookupRejected`. It cannot allocate a
row by CAM and there is no address/data join buffer before the STQ.

## Fill, recovery, and commit boundary

Two `OooIexStorePipeline` instances retain independent STA/STD halves. Address
fills use fair arbitration into the canonical STQ metadata port. Data fills
use two independent `STQDataBank` write ports and therefore no longer pass
through the old single-fill bottleneck. Each STD write is retained for a
byte-mask phase followed by a data phase; only the returned exact lease can set
the metadata row's `dataReady`. Commit drain and forwarding consumers observe
the same joined row projection, so the physical data array has one owner.

Before an address fill mutates STQ, `lateStaCandidate` exposes its complete MDB
probe and remains stable while `lateStaPermit` is false. `lateStaProbe` is the
accepted side-effect pulse, not the capacity request. This permits a composed
MDB owner to reserve record/wait/recovery capacity before STQ acceptance and
prevents a store address from becoming visible without its conflict side
effect.

The same held `OooResidencyRecoveryPlan` is prepared by both retained store
pipelines and the STQ. Prepare is side-effect free and fences reserve, STA/STD
acceptance, fill, commit-mark, and free mutation. Recovery can fire only when
every killed STQ row is an exact `WAIT` row; one common fire then cancels the
retained halves and frees the projected rows on the same edge. Generations
never rewind. Rows that have progressed beyond `WAIT` block the operation and
must be handled by the later commit/recovery owner.

Issue recovery also rejects a pivot that would retain only one physical child
of a logical split store. STA/STD recovery is therefore all-or-none while the
store is retained before S2 and after its physical rows reach IQ residency.

The fabric's commit-mark and mask-free ports are private connections in
`OooIexExecutionStorePipeline` and `OooO3IexStorePipeline`. Semantic ROB store
tokens are matched to exact STQ leases by `STQSCBCommitBackend`; accepted SCB
last fragments are the terminal free authority. The remaining store path gap
is memory-system behavior, not raw physical-index commit ownership.

## Verification

```bash
bash tools/chisel/run_chisel_tests.sh --only OooIexStoreStqFabric
bash tools/chisel/run_chisel_tests.sh --only STQDataBank
bash tools/chisel/run_chisel_tests.sh --only OooIexExecutionStoreIntegration
bash tools/chisel/run_chisel_tests.sh --only OooIexIssue
bash tools/chisel/run_chisel_tests.sh --only OooIexStorePipeline
bash tools/chisel/run_chisel_tests.sh --only OooStqReservationProjection
bash tools/chisel/run_chisel_tests.sh --only OooStqRecoveryProjection
bash tools/chisel/run_chisel_tests.sh --only STQEntryBank
```

The focused fabric UT covers two simultaneously resident logical stores with
crossed STA/STD lanes, retained late-STA capacity backpressure, two
simultaneous independent STD data-bank writes,
refusal of unreserved execution, side-effect-free
recovery prepare, mutation fencing, common-fire application, and rejection of
a split-store partial cut after the rows have transferred from IQ. The adjacent
execution/store IT drives the formal STA and STD lanes and observes address and
data in one pre-reserved canonical row. The issue test proves malformed
reservation recipes fail before S1, S1 residency and S2 blocking until
canonical reservation fires, and rejection of a recovery cut between STA and
STD before reservation, after reservation, and in resident S3.

## Remaining gaps

- Extend the physical data bank beyond the current scalar payload contract to
  vector/FSU widths, cross-bank ECC/parity, and explicit power-gating policy.
- Connect translation, PMP/PMA, MMIO classification, L1D/coherence, and
  externally visible fault publication.
- Prove sustained two-STA pressure alongside the now-directed two-STD path,
  pair-store all-or-none behavior,
  default-width synthesis/timing, and O9 workload promotion.
