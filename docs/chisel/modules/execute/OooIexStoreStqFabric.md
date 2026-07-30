# OooIexStoreStqFabric

## Purpose

`OooIexStoreStqFabric` is the canonical static composition for retained store
execution and Store Queue residency. It replaces the single-lease integration
harness with a production-shaped owner that can keep multiple logical stores
live at once, accept two independent STA lanes and two independent STD lanes,
and converge both halves only in `STQEntryBank`.

Source and test owners:

- `chisel/src/main/scala/linxcore/ooo/OooIexStoreStqFabric.scala`
- `chisel/src/test/scala/linxcore/ooo/OooIexStoreStqFabricSpec.scala`
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

Two `OooIexStorePipeline` instances retain independent STA/STD halves. Their
fill results use fair arbitration into the single canonical STQ fill port.
Each store pipeline preserves the full execute transaction and its exact lease
until the corresponding address/data fills have been accepted.

The same `OooResidencyRecoveryPlan` is projected into both retained store
pipelines and the STQ. Recovery can fire only when every killed STQ row is an
exact `WAIT` row; generations never rewind. Rows that have progressed beyond
`WAIT` block the operation and must be handled by the later commit/recovery
owner.

Issue recovery also rejects a pivot that would retain only one physical child
of a logical split store. STA/STD recovery is therefore all-or-none while the
store is retained before S2 and after its physical rows reach IQ residency.

The fabric exposes the existing raw STQ commit-mark and mask-free controls so
the future ROB/SCB commit adapter can be added without changing store
execution ownership. These controls are not yet a complete architectural
store-commit protocol.

## Verification

```bash
bash tools/chisel/run_chisel_tests.sh --only OooIexStoreStqFabric
sbt --server --batch --no-colors --mem 4096 \
  'testOnly linxcore.ooo.OooIexIssueSpec -- -z canonical'
bash tools/chisel/run_chisel_tests.sh --only OooIexStorePipeline
bash tools/chisel/run_chisel_tests.sh --only OooStqReservationProjection
bash tools/chisel/run_chisel_tests.sh --only OooStqRecoveryProjection
bash tools/chisel/run_chisel_tests.sh --only STQEntryBank
```

The focused fabric UT covers two simultaneously resident logical stores with
crossed STA/STD lanes, refusal of unreserved execution, and one recovery plan
canceling retained STD state while freeing an exact two-row lease. The issue
test proves malformed reservation recipes fail before S1, S1 residency and S2
blocking until canonical reservation fires, and rejection of a recovery cut
between STA and STD before reservation, after reservation, and in resident S3.

## Remaining gaps

- Add a production wrapper that wires `OooIexExecutionPipeline` reservation,
  STA, STD, load-cancel, and recovery ports directly to this fabric.
- Translate exact ROB commit tokens into STQ commit-mark/free operations and
  close SCB ordering, exceptions, and committed-store drain semantics.
- Add the physical two-cycle store-data bank, byte-mask generation, split and
  unaligned handling, and ECC/parity policy.
- Compose store-to-load forwarding, partial-overlap detection, violation
  replay, and LIQ/STQ memory-order checks.
- Connect translation, PMP/PMA, MMIO classification, L1D/coherence, and
  externally visible fault publication.
- Prove sustained two-STA/two-STD pressure, pair-store all-or-none behavior,
  default-width synthesis/timing, and O9 workload promotion.
