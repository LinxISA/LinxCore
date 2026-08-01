# OooIexExecutionStorePipeline

## Purpose

`OooIexExecutionStorePipeline` is the canonical production boundary that
composes the formal fourteen-lane scalar/control execution pipeline with the
live scalar-load attachment, generation-qualified STQ/store fabric, and
committed-store backend. Store
reservation, two STA lanes, two STD lanes, load cancellation, exact ROB commit
resolution, CommitQ insertion, SCB/serialized drain, terminal free, and store
recovery are private connections. There is no pre-STQ address/data join owner
and no external physical-index commit authority.

The lower-level `OooIexExecutionPipeline` and `OooIexStoreStqFabric` remain
independently testable. Until Task 13 creates public `IEX`, this surviving
mechanism wrapper is the integration boundary for execution/store tests; it is
not yet a public-box owner. Public `OOO` now ends at typed dispatch,
completion, and recovery interfaces rather than a private O3/IEX wrapper.

## Store transaction ownership

Every split store remains resident in issue S1 until its complete one- or
two-row STQ reservation fires. S2 cannot publish the physical children without
the lease. Formal execution lanes then transfer STA and STD independently into
retained store owners. Each owner looks up the existing STQ lease using full
ROB/BROB/member, LSID/store-ID, beat, and lease-generation identity; execution
cannot allocate a row by CAM.

The canonical STQ row is the only convergence owner. A fill conflict, stale
lease, duplicate match, or identity mismatch keeps the transaction retained
and reports a typed diagnostic.

Address fills expose `lateStaCandidate` before STQ mutation and accept only
with `lateStaPermit`. `lateStaProbe` remains the accepted pulse. The installed
`OooIexScalarLoadStorePath` closes this private permit to live MDB capacity;
the old public forwarding and late-STA seams no longer escape this wrapper.

After translation/PMA classifies the exact physical lease, the grouped ROB
supplies a semantic `STQRobCommitToken` without an STQ index. The backend
rediscovers one converged row, promotes it to COMMIT, and inserts its CommitQ
token atomically. Cacheable accepted last fragments and the final exact
serialized response are the only physical-row free sources.

## Common recovery

The wrapper sends one held recovery plan to execution/IQ and terminal
metadata, the STQ/store fabric, and scalar LIQ/MDB/LRET residency. It publishes
`recoveryPrepareReady` and `recoveryPrepared.valid` only when all three owners
have accepted exact, side-effect-free projections. While prepare is held, STQ
reservation, execute acceptance, scalar-load ingress, fill, commit-mark, and
free mutation are fenced.

`recoveryFire` is converted into one private common fire. On that edge:

- IQ/read/E1 and internal execution owners apply their killed set;
- retained STA/STD owners cancel matching work;
- only exact killed `WAIT` STQ rows are freed;
- no physical lease generation is rewound.

A committed row, malformed owner, or pivot that retains only one physical
child of a split store rejects the entire common transaction. No owner mutates
independently.

## Verification

```bash
bash tools/chisel/build_chisel.sh
bash tools/chisel/run_chisel_tests.sh --only OooIexExecutionPipeline
bash tools/chisel/run_chisel_tests.sh --only OooIexExecutionStoreIntegration
bash tools/chisel/run_chisel_tests.sh --only OooIexStoreStqFabric
bash tools/chisel/run_chisel_tests.sh --only OooIexStorePipeline
bash tools/chisel/run_chisel_tests.sh --only OooStqReservationProjection
bash tools/chisel/run_chisel_tests.sh --only OooStqRecoveryProjection
bash tools/chisel/run_chisel_tests.sh --only STQEntryBank
```

The structural gate elaborates the full fourteen-lane wrapper and proves one
scalar-load attachment, one canonical metadata owner, and one STQ owner. A
focused recovery-join UT proves that no subset of the three prepared owners
can fire, and that execution then store rejection has priority over scalar
load rejection. The adjacent dynamic IT pre-reserves a scalar store, drives
its STA through `agu0-sta` and its STD through `alu0` in the same cycle, and
proves that the expected address and data appear in the same canonical STQ
row. The extended IT classifies that lease, commits it without a physical-index
sideband, observes CommitQ and SCB admission, and proves terminal row release.
Focused fabric UT additionally proves prepare fencing, no pre-fire mutation,
common-fire application, split-store partial-cut rejection, and retained
serialized terminal free across recovery prepare.

## Remaining gaps

- Replace the typed test PMA producer with the physical translation/PMP/PMA
  result path and connect the uncached/device response fabric.
- Add the physical store-data bank, forwarding/overlap checks, load violation
  replay, and serial-wrap quiescence.
- Add Device loads, atomics/fences, L1D/coherence, and precise fault
  publication.
- Close default-width generated-graph cost, synthesis timing, sustained
  two-STA/two-STD pressure, O9 top promotion, CoreMark, and Dhrystone.
