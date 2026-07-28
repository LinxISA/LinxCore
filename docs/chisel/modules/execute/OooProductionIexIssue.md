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
- `chisel/src/test/scala/linxcore/ooo/OooProductionIexRecoverySpec.scala`
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

## Scheduling row and execution sidecar

Each physical slot has two storage domains joined as `OooIexIssueRow`:

- `OooIexScheduleRow` is resettable, frequently scanned state used by wakeup,
  pick, exact release, and recovery;
- `OooIexPayloadSidecar` is a memory-backed execution payload addressed by the
  stable class/bank/slot reservation and read only for the selected query.

The scheduling row contains exact PE/STID/epoch/transaction and
`RobMemberKey`, the class/bank/write-port/entry/reservation generation, and
generation-qualified P/T/U source/destination tags. The payload sidecar keeps
the canonical uop key, opcode, generated recipe, split-child index, primary
prediction, PC-buffer tokens, and immediate/boundary/template/trap/close
controls.

The joined query view preserves the existing execution contract. Release or
recovery invalidates only the scheduling row; stale payload memory is
unreachable until a later exact S2 bind overwrites that slot. Rename-owner
structures are not copied into the IQ. SMAP, CMAP, P/T/U MapQ, and retirement
relations remain owned by RENU/commit. The wide sidecar therefore does not
participate in every wakeup/recovery comparison, and IEX does not become a
second rename or recovery authority.

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

## Recovery

The owner consumes a compact `OooResidencyRecoveryPlan` projected from the
complete grouped-ROB plan. Prepare freezes only the target STID, validates
every retained S1/S2/S3 member against the exact wrapped ROB window, and
reports killed residency without mutation. Apply prunes killed lanes from the
retained S1 claim and pending S3 mask, frees exact killed `BoundS2` and
`ResidentS3` rows, and clears matching generation-qualified P/T/U readiness
records. A surviving partial pivot remains resident and pickable; unrelated
STIDs continue. Because wakeup is a non-backpressured `Valid` input, target
STID wakeup during prepare is an assertion failure rather than a silently
dropped readiness event; global R0-R4 must quiesce those producers before
prepare. The port is deliberately tied off above this module until that
coordinator can fire all recovery owners atomically.

## Remaining production gaps

- P1/I1/I2 retained execution-pipe stages and cross-pipe arbitration.
- Age-matrix or equivalent oldest-ready pick with same-STID exact ROB order
  and fair cross-STID selection.
- Speculative issue inflight state, cancel/retry, and the rule that only a
  non-cancellable I2 terminal event releases the physical row.
- RF read-port arbitration, operand bypass, and result/wakeup buses.
- O8 hierarchical/FIFO free selection, bank/port cost steering, safe-mode
  thresholds, a retained banked/timed recovery scan over scheduling rows, and
  default-geometry timing/area closure.
- Per-class multi-pick liveness counters and coverage closure.

The legacy `ReducedScalarIssue*` modules remain compatibility evidence until a
later IEX packet replaces their P1/I1/I2 and top-level consumers. They are not
used as the semantic authority for this module.

## Verification

```bash
bash tools/chisel/run_chisel_tests.sh --only OooProductionIexIssue
bash tools/chisel/run_chisel_tests.sh --only OooProductionIexRecovery
bash tools/chisel/run_chisel_tests.sh --only OooO3IexIntegration
bash tools/chisel/run_chisel_tests.sh --only OooO3RenameCoordinator
```

The focused UT covers retained-stage timing, fair STID arbitration, target
claim collisions, split atomicity, compact row payload, registered wakeup,
same-edge wakeup plus S2 bind, exact release/backpressure, malformed requests,
and widths 2/4/6. The compact recovery UT covers partial-pivot S1/S2/S3 pruning,
survivor residency, complete cancellation, and cross-STID isolation. The IT
connects the real O3/RENU/dispatch coordinator and proves publication,
residency, and dispatch-slot return share the exact transactions.

O8.1 elaboration evidence uses the first 2-bank x 4-entry stage test. Splitting
the wide payload into inferred memory reduces the generated main module from
477,275 to 426,274 SystemVerilog lines. The remaining size is dominated by the
one-cycle all-entry recovery scan, so this packet is a storage-boundary
prerequisite rather than timing closure.
