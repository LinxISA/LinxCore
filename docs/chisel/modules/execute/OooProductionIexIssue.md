# OooProductionIexIssue

## Purpose

`OooProductionIexIssue` is the production residency boundary between the OOO
coordinator's terminal S1 publication and later IEX pick/execute logic. It
owns physical IQ row installation and readiness, but it does not yet implement
execution pipes.

Source and test owners:

- `chisel/src/main/scala/linxcore/ooo/OooProductionIexIssue.scala`
- `chisel/src/main/scala/linxcore/ooo/OooBundles.scala`
- `chisel/src/test/scala/linxcore/ooo/OooProductionIexIssueSpec.scala`
- `chisel/src/test/scala/linxcore/ooo/OooO3IexIntegrationSpec.scala`

## Stage ownership

| Stage | Retained owner | Terminal condition |
|---|---|---|
| OOO S1 | one row per STID plus exact target claims | selected by fair shared S2 writer |
| IEX S2 | exact physical IQ rows in `BoundS2` | one complete registered cycle |
| IEX S3 | exact rows in `ResidentS3` | future exact I2 release or O7 recovery |

S1 admission revalidates O3, P, T/U, and dispatch identities. Every child of a
split transaction is accepted or rejected together. Pending S1 claims exclude
same-target admission from other STIDs without pretending the physical row has
already been written.

S2 binds at most one STID transaction per cycle. It consumes the earlier
dispatch reservation rather than selecting a new entry. Newly written rows are
not pickable until the following S3 transition.

## Execution row

Each physical row contains one unified execution uop:

- exact PE/STID/epoch/transaction and `RobMemberKey`;
- original class/bank/write-port/entry/reservation generation;
- canonical uop key, opcode, generated recipe, and split-child index;
- primary branch prediction and PC-buffer tokens;
- immediate, boundary, template, trap, and close controls;
- generation-qualified P/T/U source and destination tags.

Rename-owner structures are not copied into the IQ. SMAP, CMAP, P/T/U MapQ,
and retirement relations remain owned by RENU/commit. This keeps the physical
row synthesizable and prevents IEX from becoming a second recovery authority.

## Wakeup and release

Wakeups compare full STID/epoch and physical generation. They update only
registered source-ready state, so wakeup N can affect pick eligibility no
earlier than N+1. A generation-qualified P scoreboard and per-STID T/U
sequence scoreboards retain completed-producer state for consumers that arrive
after the wakeup pulse. Installing a new physical destination clears the
matching ready entry. A `ResidentS3` row is pickable only when all valid
sources are registered ready.

Release is fail-closed. It requires the exact member and complete dispatch
reservation, including class-local entry, write port, and reservation epoch.
The IQ row and dispatch allocation return on one Decoupled fire.

## Remaining production gaps

- P1/I1/I2 retained execution-pipe stages and cross-pipe arbitration.
- Age-matrix or equivalent oldest-ready pick with same-STID exact ROB order
  and fair cross-STID selection.
- Speculative issue inflight state, cancel/retry, and the rule that only a
  non-cancellable I2 terminal event releases the physical row.
- RF read-port arbitration, operand bypass, and result/wakeup buses.
- O7 global recovery joining IQ claims/rows with ROB, BROB, PC, and rename.
- O8 hierarchical/FIFO free selection, bank/port cost steering, safe-mode
  thresholds, and default-geometry timing/area closure.
- Per-class multi-pick liveness counters and coverage closure.

The legacy `ReducedScalarIssue*` modules remain compatibility evidence until a
later IEX packet replaces their P1/I1/I2 and top-level consumers. They are not
used as the semantic authority for this module.

## Verification

```bash
bash tools/chisel/run_chisel_tests.sh --only OooProductionIexIssue
bash tools/chisel/run_chisel_tests.sh --only OooO3IexIntegration
bash tools/chisel/run_chisel_tests.sh --only OooO3RenameCoordinator
```

The focused UT covers retained-stage timing, fair STID arbitration, target
claim collisions, split atomicity, compact row payload, registered wakeup,
same-edge wakeup plus S2 bind, exact release/backpressure, malformed requests,
and widths 2/4/6. The IT
connects the real O3/RENU/dispatch coordinator and proves publication,
residency, and dispatch-slot return share the exact transactions.
