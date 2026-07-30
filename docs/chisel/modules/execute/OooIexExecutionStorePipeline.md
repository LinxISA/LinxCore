# OooIexExecutionStorePipeline

## Purpose

`OooIexExecutionStorePipeline` is the canonical production boundary that
composes the formal fourteen-lane scalar/control execution pipeline with the
generation-qualified STQ/store fabric. Store reservation, two STA lanes, two
STD lanes, load cancellation, and store recovery are private connections.
There is no pre-STQ address/data join owner.

The lower-level `OooIexExecutionPipeline` and `OooIexStoreStqFabric` remain
independently testable. A production top should instantiate this wrapper rather
than reconnect their store ports externally.

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

## Common recovery

The wrapper sends one held recovery plan to both execution/IQ residency and
the STQ/store fabric. It publishes `recoveryPrepareReady` and
`recoveryPrepared.valid` only when both owners have accepted an exact,
side-effect-free projection. While prepare is held, STQ reservation, execute
acceptance, fill, commit-mark, and free mutation are fenced.

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

The structural gate elaborates the full fourteen-lane wrapper. A focused
recovery-join UT proves that a single prepared owner cannot fire, both owners
must rendezvous, and execution rejection has priority over the synthesized
store rejection. The adjacent dynamic IT pre-reserves a scalar store, drives
its STA through `agu0-sta` and its STD through `alu0` in the same cycle, and
proves that the expected address and data appear in the same canonical STQ
row. Focused fabric UT additionally proves prepare fencing, no pre-fire
mutation, common-fire application, and split-store partial-cut rejection.

## Remaining gaps

- Convert exact grouped-ROB commit output into retained CommitQ tokens instead
  of exposing raw STQ row indices and free masks.
- Connect the committed fragment owner to the canonical SCB and prove
  last-fragment-only physical-row release.
- Add the physical store-data bank, forwarding/overlap checks, load violation
  replay, and serial-wrap quiescence.
- Add translation, PMP/PMA, MMIO serialization, L1D/coherence, and precise
  fault publication.
- Close default-width generated-graph cost, synthesis timing, sustained
  two-STA/two-STD pressure, O9 top promotion, CoreMark, and Dhrystone.
