# OooIexIssue

## Purpose

`OooIexIssue` is the residency boundary between the OOO
coordinator's terminal S1 publication and later IEX pick/execute logic. It
owns physical IQ row installation and readiness, but it does not yet implement
execution pipes.

Source and test owners:

- `chisel/src/main/scala/linxcore/ooo/OooIexIssue.scala`
- `chisel/src/main/scala/linxcore/ooo/OooBundles.scala`
- `chisel/src/test/scala/linxcore/ooo/OooIexIssueSpec.scala`
- `chisel/src/test/scala/linxcore/ooo/OooIexRecoverySpec.scala`
- `chisel/src/test/scala/linxcore/ooo/OooO3IexIntegrationSpec.scala`

The first downstream read-stage packet is documented separately in
[`OooIexP1I2Lane`](OooIexP1I2Lane.md). It consumes a selected joined row but
does not take ownership of the physical IQ entry.

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
complete grouped-ROB plan. Prepare captures the immutable request, validates
retained S1 immediately, then scans one parameterized entry slice from every
physical class/bank per cycle. The default
`iexRecoveryScanEntriesPerBankPerCycle=1` takes
`iqEntriesPerBank` scan cycles; larger power-of-two divisors trade read/CAM
width for latency. Capture plus scan exposes prepared-ready after
`iexRecoveryScanCycles + 1` cycles.

The scan is side-effect free. It overwrites every stored row-kill bit during a
complete pass and accumulates exact `BoundS2`/`ResidentS3` counts, pending-S3
lane membership, and generation-qualified P/T/U ready-scoreboard kill masks.
Changing the offered plan while it is retained or finding a malformed live
row rejects the request. Deasserting prepare before `Prepared` aborts only the
scan metadata. Neither case mutates S1/S2/S3 or readiness state.

P-ready is a global scoreboard, so a numerical PTag can be recycled and
rewritten by a peer while recovery is retained. Capture snapshots the complete
`{valid,generation,stid,epoch}` owner identity used by the scan. Common apply
clears a retained P-ready mask bit only when the live identity still equals
that snapshot; a new generation/STID/epoch survives unchanged.

Prepare freezes target-STID admission, transition, release, and wakeup while
unrelated STIDs continue. After every slice validates, the owner holds the
prepared result and its exact masks until the global common apply. Apply
prunes killed lanes from retained S1 and pending S3, frees exact killed
`BoundS2` and `ResidentS3` rows, and clears matching P/T/U readiness records.
A surviving partial pivot remains resident and pickable. Because wakeup is a
non-backpressured `Valid` input, target-STID wakeup during prepare is an
assertion failure rather than a silently dropped readiness event; global R0-R4
must quiesce those producers before prepare.

## Remaining gaps

- Age-matrix or equivalent oldest-ready pick with same-STID exact ROB order
  and fair cross-STID selection.
- Multi-lane P1 arbitration and speculative in-flight bookkeeping around the
  implemented single-lane P1/I1/I2 transaction.
- Canonical P/T/U RF owners, shared read-port arbitration, operand bypass, and
  result/wakeup buses.
- The terminal handoff rule that releases the physical row only after a
  non-cancellable I2 consumer accepts it.
- O8 bank/port occupancy plus retained-inflight cost steering, PTag coupling,
  safe-mode thresholds, and default-geometry timing/area closure. The
  unbounded dispatch slot encoder is closed by O8.2's bounded hierarchy.
- Per-class multi-pick liveness counters and coverage closure.

The legacy `ReducedScalarIssue*` modules remain compatibility evidence until a
later IEX packet replaces their top-level consumers. `OooIexP1I2Lane` now
replaces their read-stage semantics for the canonical path. They are not used
as the semantic authority for this module.

## Verification

```bash
bash tools/chisel/run_chisel_tests.sh --only OooIexIssue
bash tools/chisel/run_chisel_tests.sh --only OooIexRecovery
bash tools/chisel/run_chisel_tests.sh --only OooO3IexIntegration
bash tools/chisel/run_chisel_tests.sh --only OooO3RenameCoordinator
```

The focused UT covers retained-stage timing, fair STID arbitration, target
claim collisions, split atomicity, compact row payload, registered wakeup,
same-edge wakeup plus S2 bind, exact release/backpressure, malformed requests,
and widths 2/4/6. The compact recovery UT covers exact scan latency,
side-effect-free partial-scan abort, partial-pivot S1/S2/S3 pruning, survivor
residency, complete cancellation, plan-drift/malformed-row rejection,
cross-STID progress while the target scans, configurable two-entry bank
slices, and PTag recycle/reuse across retained common apply. The IT connects
the real O3/RENU/dispatch
coordinator and proves publication, residency, recovery preparation, common
apply, and dispatch-slot return share the exact transactions.

O8.1/O8.1b elaboration evidence uses the same first 2-bank x 4-entry stage
test. Splitting the wide payload into inferred memory first reduced the main
module from 477,275 to 426,274 SystemVerilog lines. Retaining and timing the
compact-row recovery scan reduces it again to 173,709 lines: 63.6% below the
pre-split baseline and 59.2% below O8.1. The directly comparable recovery
scenario falls from about three minutes to 47.046 seconds; the expanded
three-test recovery suite passes in 232.166 seconds. This closes the unbounded
recovery CAM. O8.2 separately closes the unbounded dispatch slot encoder; O8
remains open for cost steering, physical PC metadata/write macro realization,
and the grouped-ROB commit prefix path. PC recovery compare/read depth is
bounded by O8.3h; ROB, MapQ, and PC address banking remain established
boundaries rather than final timing claims.
