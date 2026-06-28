# ScalarTURenameBridge

## Source Mapping

- Chisel: `chisel/src/main/scala/linxcore/rename/ScalarTURenameBridge.scala`
- Tests: `chisel/src/test/scala/linxcore/rename/ScalarTURenameBridgeSpec.scala`
- LinxCoreModel:
  - `model/LinxCoreModel/model/bctrl/spe/SPERename.cpp`
  - `model/LinxCoreModel/model/bctrl/spe/GPRRename.cpp`
- Related Chisel:
  - `chisel/src/main/scala/linxcore/rename/ScalarDecodeRenameBridge.scala`
  - `chisel/src/main/scala/linxcore/rename/TULinkRecoveryCleanupPath.scala`
  - `chisel/src/main/scala/linxcore/rename/TULinkRetireCommandPath.scala`
  - `chisel/src/main/scala/linxcore/rename/TULinkRename.scala`
  - `chisel/src/main/scala/linxcore/backend/DecodeRenameROBPath.scala`

## Purpose

`ScalarTURenameBridge` is the first live scalar-plus-T/U rename composition
owner. It keeps `ScalarDecodeRenameBridge` responsible only for scalar GPR
rename, composes `TULinkRecoveryCleanupPath` for T/U link rename and cleanup,
and overlays accepted T/U source and destination physical tags back onto the
emitted `RenamedUop`.

The module exists so later backend owners can consume model-equivalent
`SPERename::Rename()` sidecars without broadening the scalar GPR bridge. It
also makes cleanup diagnostics and live T/U rename state come from one owner,
rather than a separate diagnostic-only cleanup path.
R69 wires the scalar local block-commit event from the retire path into the
T/U cleanup composition, so the reduced path now consumes
`SPERename::ReportSGPRBlockCommit` through the same live local-register owner.

## Interface

Inputs:

- `in`: decoded uop presented for one-uop rename.
- `outReady`: downstream renamed-uop consumer readiness.
- `robAllocReady`: ROB allocation readiness.
- `checkpointValid/checkpointBid`, `commitValid/commitBid`, `cleanup`: scalar
  GPR and T/U maintenance controls.
- `robSource`, `lsuSource`: ROB and LSU selected-row T/U cleanup source
  candidates.
- `tuRetireValid/Kind/Seq/Dealloc`: live T/U relation-cmap
  mark/deallocation command from `TULinkRetireCommandPath`.
- `tuLocalBlockCommitValid/tuLocalBlockCommitBid`: post-clean scalar local
  block-commit event from `TULinkRetireCommandPath`.

Outputs:

- `inReady`, `accepted`, `outValid/out`: atomic one-uop rename handshake and
  renamed payload.
- `robAllocAttemptValid`, `robAllocValid`, `robAllocRow`: scalar allocation
  row request forwarded from `ScalarDecodeRenameBridge`.
- `needsGprRename`, `needsTAlloc`, `needsUAlloc`, `unsupported*`,
  `blockedBy*`: scalar and T/U pressure, unsupported, and stall diagnostics.
- `srcPhysTags`, `dstPhysTag`, `dstOldPhysTag`: scalar/T/U physical tag
  observations.
- `tuReady`, `tuAccepted`, `tuSrc`, `tuDst`, `tuTSeq`, `tuUSeq`,
  `tuDstValid`, `tuDstKind`: T/U rename results and pre-allocation sequence
  snapshots.
- `tuRetireAccepted`, `tuRetireMiss`, `tuRetireReleaseMismatch`,
  `tuRetireUnsupported`: actual retire-command outcome from `TULinkRename`.
- `tuLocalBlockCommitReady`, `tuLocalBlockCommitAccepted`: downstream
  handshake for the local block-commit event consumed by the T/U cleanup
  composition.
- `tuCleanup*`: forwarded source-selection and flush-publisher diagnostics
  from `TULinkRecoveryCleanupPath`.

## Logic Design

The wrapper sanitizes the input before presenting it to
`ScalarDecodeRenameBridge`:

- valid `OperandClass.P` sources remain visible to scalar GPR rename;
- valid `OperandClass.T` and `OperandClass.U` sources are hidden from scalar
  GPR rename and resolved by `TULinkRecoveryCleanupPath`;
- unsupported valid source classes, such as `CArg`, block acceptance and raise
  unsupported diagnostics;
- valid T/U destinations are hidden from scalar GPR rename and allocated by
  the T/U rename owner.

Acceptance is atomic. The scalar bridge can accept only when downstream output
is ready, ROB allocation is ready, scalar GPR rename can proceed, and
`TULinkRecoveryCleanupPath.ready` is true. `TULinkRecoveryCleanupPath.renameValid`
is driven by the accepted scalar event, so scalar and T/U state mutate for the
same decoded uop in the same cycle.

The output starts from the scalar bridge's `RenamedUop`. Accepted T/U sources
are overlaid with the original decoded operand class, architectural tag,
relative tag, and the T/U physical tag resolved by `TULinkRename`. Accepted
T/U destinations are overlaid with the original decoded destination kind and
the allocated T/U physical tag. Scalar GPR sources and destinations remain
owned by `ScalarDecodeRenameBridge`.

`tuTSeq` and `tuUSeq` are the live T/U allocation sequence snapshots before
destination rename. These are the sidecars that `SPERename::Rename()` records
on the instruction before calling destination rename. `tuDstValid` and
`tuDstKind` identify whether the accepted row allocated a T or U destination,
so ROB and STQ cleanup source publishers can apply the same destination-owned
sequence rule used by the model.

Cleanup source selection stays inside the composed
`TULinkRecoveryCleanupPath`. Non-base backend cleanup can block rename when no
valid ROB/LSU source exists or when both sources conflict, preserving the
recovery barrier before new T/U state mutation.

Retire commands also stay inside `TULinkRecoveryCleanupPath` and
`TULinkRename`. The bridge exposes `tuRetireAccepted` so the upstream
serializer can advance on actual mark/release acceptance after flush and
commit priority have been applied.

Local block commit also stays inside the T/U cleanup path. The bridge forwards
`tuLocalBlockCommit*` into `TULinkRecoveryCleanupPath` and exposes its
ready/accepted handshake. This lets `TULinkRetireCommandPath` keep the R68
event pending while recovery flush or an external commit occupies the local
maintenance slot.

## Model Alignment

`SPERename::Rename()` performs the relevant order:

1. rename source operands, including T/U source lookup through `LocalRegMgr`;
2. snapshot `inst->tSeq` and `inst->uSeq` from the T and U local register
   managers;
3. rename destination operands, including T/U destination allocation;
4. dispatch the renamed instruction.

`ScalarTURenameBridge` matches that order for the reduced one-uop Chisel path.
The T/U sequence outputs are captured before allocation, while the overlaid
destination physical tag comes from the accepted T/U destination allocation.
For block commit, the model calls `SPERename::ReportSGPRBlockCommit` after
scalar `CleanCMAP`; the R69 bridge forwards that event to the composed
`TULinkRecoveryCleanupPath`, which applies `LocalRegMgr::ReportBlockCommit`
through `TULinkRename.commit*`.

## Deferred Owners

- Width-wide scalar plus T/U rename.
- Old T/U physical tag release accounting for destination overwrite.
- Ready-table initialization and wakeup ownership for T/U sources.
- Commit-trace representation of non-GPR destination ownership.
- Multi-PE/multi-STID SGPR fanout beyond the reduced single live bank.
- Tile, vector, and `CArg` operand classification beyond the current P/T/U
  subset.

## Verification

Focused gate:

```bash
bash tools/chisel/run_chisel_tests.sh --only ScalarTURenameBridge
```

Affected gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only ScalarDecodeRenameBridge
bash tools/chisel/run_chisel_tests.sh --only TULinkRecoveryCleanupPath
bash tools/chisel/run_chisel_tests.sh --only TULinkRetireCommandPath
bash tools/chisel/run_chisel_tests.sh --only TULinkRename
bash tools/chisel/run_chisel_tests.sh --only DecodeRenameROBPath
```

The current tests cover the atomic scalar/T/U accept reference rule, scalar
input sanitization for T/U operands, local block-commit maintenance
backpressure, IO shape, and elaboration through `ScalarDecodeRenameBridge`,
`TULinkRecoveryCleanupPath`, and `TULinkRename`.
