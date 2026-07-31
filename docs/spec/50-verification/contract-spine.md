# Contract-spine verification

## Single-chain structural check {#VER-ARC-001}
<!-- ndf: kind=verif level=must layer=L3 status=stable since=0.1 verifies=ARC-TOP-001,ARC-TOP-010 -->

A structural test MUST inspect the elaborated TOP and reject a second stateful
core chain or policy state owned by TOP.

## Linx semantic review gate {#VER-ARC-002}
<!-- ndf: kind=verif level=must layer=L3 status=stable since=0.1 verifies=ARC-TOP-002 -->

Each imported mechanism MUST name its Linx contract and rejected foreign
semantics in a reviewable reference projection.

## Replacement and deletion gate {#VER-ARC-003}
<!-- ndf: kind=verif level=must layer=L3 status=stable since=0.1 verifies=ARC-TOP-003 -->

The deletion task MUST require passing replacement evidence and MUST reject old
state-owning identifiers in live source, tests, emitters, harnesses, and
current architecture pages.

## Interface and parameter checks {#VER-ARC-004}
<!-- ndf: kind=verif level=must layer=L3 status=stable since=0.1 verifies=ARC-TOP-011,ARC-TOP-012 -->

Executable tests MUST compare elaborated interface shapes with the generated
manifest and elaborate all width profiles 2, 4, 6, and 8, including invalid
cross-parameter combinations.

## Ownership checks {#VER-ARC-005}
<!-- ndf: kind=verif level=must layer=L3 status=stable since=0.1 verifies=ARC-TOP-020,ARC-TOP-021,ARC-TOP-022 -->

Unit and integration tests MUST prove single-owner ROB/commit/recovery,
atomic rename-map transitions, and non-blocking DTU observation.
Task-9 owner closure additionally requires `OOORobCommitSpec` and
`OOORecoverySpec` to prove exact ROB-member ordering, same-group branch suffix
recovery, semantic completion rejection without producer deadlock, BROB
BID/generation release validation, whole-prefix release rejection, final-member
BROB release, side-effect-free owner readiness plus one common commit fire,
typed block start/stop propagation, retained recovery source arbitration, ROB
request/response, one-prepare-per-target barriers, mismatched acknowledgement
rejection, and a multi-target recovery prepare/apply barrier.

## Front-end and OOO pipeline checks {#VER-ARC-006}
<!-- ndf: kind=verif level=must layer=L3 status=stable since=0.1 verifies=ARC-TOP-030,ARC-TOP-031 -->

Coverage MUST include 16-, 32-, 48-, and 64-bit instruction forms, template
expansion, complete identities, D1/D2 backpressure, ROB reservation, rename,
early completion, and suffix retry.

## Dispatch and recovery checks {#VER-ARC-007}
<!-- ndf: kind=verif level=must layer=L3 status=stable since=0.1 verifies=ARC-TOP-032,ARC-TOP-033 -->

Coverage MUST exercise every typed queue and default execution/LSU path, plus
recovery coincident with dispatch, completion, memory side effects, trap, and
interrupt.
