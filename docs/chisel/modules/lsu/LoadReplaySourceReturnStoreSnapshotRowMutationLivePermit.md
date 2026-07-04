# LoadReplaySourceReturnStoreSnapshotRowMutationLivePermit

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplaySourceReturnStoreSnapshotRowMutationLivePermit.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/LoadReplaySourceReturnStoreSnapshotRowMutationLivePermitSpec.scala`
- Integrated user: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplaySourceReturnStoreSnapshotPath.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/lsu/load_unit/ldq.cpp`
    - `LDQInfo::handleSTQReceive`
    - `LDQInfo::waitStore`
    - `LDQInfo::handleMerge`

## Purpose

`LoadReplaySourceReturnStoreSnapshotRowMutationLivePermit` is the source-path
admission guard between a replay-STQ row-state plan and a live LIQ row mutation
request.

The C++ model applies a returned STQ response only to the still-repick target
row after SCB has returned. This owner keeps that invariant explicit for the
Chisel path: a row-state plan may exist for diagnostics, but the request owner
does not receive a live permit until the reduced response-head proof says the
same target row is valid, repick, SCB-returned, and apply-eligible.

## Interface

Inputs:

- `enable`, `flush`: path lifecycle controls.
- `liveEnable`: outer live row-mutation arm. R445 drives this true in the
  reduced top; this owner still withholds `livePermit` until the same-row head
  proof passes.
- `targetReady`: source request owner has a valid one-row target candidate.
- `targetMask`: target mask from `ResponseApply`.
- `headTargetsRow`, `headRepick`, `headApplyEligible`,
  `headProofTargetMask`: reduced response-head proof from
  `LoadReplaySourceReturnStoreSnapshotResponseHeadState`.
- `headBlockedByInvalidRow`, `headBlockedByScbNotReturned`: reason-specific
  head-proof blockers forwarded for diagnostics.

Outputs:

- `headProofReady`: target mask is nonzero, matches the reduced head one-hot,
  and the head is apply-eligible.
- `headProofTargetMaskOut`: forwarded reduced head one-hot mask.
- `livePermit`: `liveEnable && headProofReady`; this drives
  `RowMutationRequest.liveEnable` in the composite path.
- `blockedByDisabled`, `blockedByFlush`, `blockedByHeadProof`,
  `blockedByHeadInvalidRow`, `blockedByHeadScbNotReturned`,
  `blockedByHeadNotRepick`, `blockedByHeadTargetMismatch`: diagnostics for the
  source-path live gate.

## Logic Design

The owner is combinational. It first forms `active = enable && !flush` and a
target candidate from `active && liveEnable && targetReady`.

`headProofReady` is true only when:

- the path is active,
- the reduced head proof is apply-eligible,
- `targetMask` is nonzero,
- `targetMask == headProofTargetMask`.

The live permit deliberately does not depend on `targetReady`; the downstream
request owner still owns request validity. This preserves the R442 behavior:
the permit only expresses whether the head proof allows liveness, while
`RowMutationRequest` decides whether the current plan and target are ready.

The blocker outputs are reason-specific views of the same target candidate.
They let a later top-promotion packet distinguish invalid reduced row proof,
missing SCB return, not-repick/stale target, and target-mask mismatch without
changing the row-mutation request payload.

## Verification

Focused gate:

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadReplaySourceReturnStoreSnapshotRowMutationLivePermit
```

The integrated path gate must also pass after wiring changes:

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadReplaySourceReturnStoreSnapshotPath
```
