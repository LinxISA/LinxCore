# ScalarTURenameBridge

## Source Mapping

- Chisel: `chisel/src/main/scala/linxcore/rename/ScalarTURenameBridge.scala`
- Tests: `chisel/src/test/scala/linxcore/rename/ScalarTURenameBridgeSpec.scala`
- LinxCoreModel:
  - `model/LinxCoreModel/model/bctrl/spe/SPERename.cpp`
  - `model/LinxCoreModel/model/bctrl/spe/GPRRename.cpp`
- Related Chisel:
  - `chisel/src/main/scala/linxcore/rename/ScalarDecodeRenameBridge.scala`
  - `chisel/src/main/scala/linxcore/rename/TULinkLocalBankArray.scala`
  - `chisel/src/main/scala/linxcore/rename/TULinkRecoveryCleanupPath.scala`
  - `chisel/src/main/scala/linxcore/rename/TULinkLocalBlockCommitFanout.scala`
  - `chisel/src/main/scala/linxcore/rename/TULinkRetireCommandPath.scala`
  - `chisel/src/main/scala/linxcore/rename/TULinkRename.scala`
  - `chisel/src/main/scala/linxcore/backend/DecodeRenameROBPath.scala`

## Purpose

`ScalarTURenameBridge` is the first live scalar-plus-T/U rename composition
owner. It keeps `ScalarDecodeRenameBridge` responsible only for scalar GPR
rename, composes `TULinkLocalBankArray` for T/U link rename and cleanup, and
overlays accepted T/U source and destination physical tags back onto the
emitted `RenamedUop`.

The module exists so later backend owners can consume model-equivalent
`SPERename::Rename()` sidecars without broadening the scalar GPR bridge. It
also makes cleanup diagnostics and live T/U rename state come from one owner,
rather than a separate diagnostic-only cleanup path.
R69 wires the scalar local block-commit event from the retire path into the
T/U cleanup composition, so the reduced path now consumes
`SPERename::ReportSGPRBlockCommit` through the same live local-register owner.
R70 forwards the event STID and exposes whether the reduced local-register
owner matched it. R72 replaces the direct single-bank child with
`TULinkLocalBankArray`. The live reduced lane still selects PE0/STID0, but the
bridge now contains the explicit `[scalar PE][STID]` SGPR bank-array boundary
and exposes bridge-owned local block-commit fanout diagnostics.
R73 makes the active-bank selector an explicit bridge input. The reduced
backend still drives PE0, but the active STID now comes from the queued decoded
row's thread/STID sidecar instead of a bridge-local `localStid` constant.
R74 adds the matching retire-bank selector path. `tuRetirePeId/Stid` come
from the serialized retired-row command and are forwarded to
`TULinkLocalBankArray` separately from the active rename selector.
R75 starts driving that active PE input from the queued decoded row's `peId`
sidecar in the reduced backend. The bridge has no PE0 assumption internally;
it reports range and one-hot diagnostics from `TULinkLocalBankArray`.

## Interface

Inputs:

- `in`: decoded uop presented for one-uop rename.
- `activePeId`, `activeStid`: selected SGPR bank group for current reduced
  rename and external local commit traffic.
- `outReady`: downstream renamed-uop consumer readiness.
- `robAllocReady`: ROB allocation readiness.
- `checkpointValid/checkpointBid`, `commitValid/commitBid`, `cleanup`: scalar
  GPR and T/U maintenance controls.
- `robSource`, `lsuSource`: ROB and LSU selected-row T/U cleanup source
  candidates.
- `tuRetireValid/Kind/Seq/Dealloc`: live T/U relation-cmap
  mark/deallocation command from `TULinkRetireCommandPath`.
- `tuRetirePeId`, `tuRetireStid`: retired-row bank selector carried by that
  command.
- `tuLocalBlockCommitValid/tuLocalBlockCommitBid/tuLocalBlockCommitStid`:
  post-clean scalar local block-commit event from `TULinkRetireCommandPath`.

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
- `tuActivePeInRange`, `tuActiveStidInRange`, `tuActiveBankValid`,
  `tuActivePeOH`, `tuActiveStidOH`: active bank selector diagnostics forwarded
  from `TULinkLocalBankArray`.
- `tuRetireAccepted`, `tuRetireMiss`, `tuRetireReleaseMismatch`,
  `tuRetireUnsupported`: actual retire-command outcome from `TULinkRename`.
- `tuRetirePeInRange`, `tuRetireStidInRange`, `tuRetireBankValid`,
  `tuRetirePeOH`, `tuRetireStidOH`: retire-command bank selector diagnostics
  forwarded from `TULinkLocalBankArray`.
- `tuLocalBlockCommitReady`, `tuLocalBlockCommitAccepted`: downstream
  handshake for the local block-commit event consumed by the T/U cleanup
  composition.
- `tuLocalBlockCommitStidMatch`, `tuLocalBlockCommitBlockedByStid`: reduced
  local-owner STID match diagnostics.
- `tuLocalBlockCommitFanout*`: bridge-owned selected-STID fanout diagnostics
  from `TULinkLocalBankArray`, including selected-bank range and readiness.
- `tuCleanup*`: forwarded source-selection and flush-publisher diagnostics
  from the selected `TULinkRecoveryCleanupPath` bank group.

## Logic Design

The wrapper sanitizes the input before presenting it to
`ScalarDecodeRenameBridge`:

- valid `OperandClass.P` sources remain visible to scalar GPR rename;
- valid `OperandClass.T` and `OperandClass.U` sources are hidden from scalar
  GPR rename and resolved by `TULinkLocalBankArray`;
- unsupported valid source classes, such as `CArg`, block acceptance and raise
  unsupported diagnostics;
- valid T/U destinations are hidden from scalar GPR rename and allocated by
  the T/U rename owner.

For split-store rows entering the reduced store path, the bridge preserves
local T/U source sidecars on the emitted `RenamedUop` but suppresses those
local sources only at the live `TULinkLocalBankArray` lookup input. This keeps
`SPERename::InsertToStoreIEX`-style store payload/source ordering visible to
later store split and STQ owners while avoiding a false local sequence
underflow when an STA/STD split carries a T/U payload or base that the reduced
store path is not yet ready to consume through the normal scalar issue path.

Acceptance is atomic. The scalar bridge can accept only when downstream output
is ready, ROB allocation is ready, scalar GPR rename can proceed, and
`TULinkLocalBankArray.ready` is true for the selected bank group.
`TULinkLocalBankArray.renameValid` is driven by the accepted scalar event, so
scalar and T/U state mutate for the same decoded uop in the same cycle.
The selected bank group is no longer hardwired inside the bridge: callers drive
`activePeId/activeStid`, and the bridge forwards the bank-array in-range and
one-hot diagnostics. In the current reduced backend, those inputs come from
`DecodedUop.peId` and `DecodedUop.threadId`; default frontend packets still
produce PE0/STID0 until an upstream owner drives nonzero sidecars.

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
`TULinkLocalBankArray` and its selected `TULinkRecoveryCleanupPath` bank
group. Non-base backend cleanup can block rename when no valid ROB/LSU source
exists or when both sources conflict, preserving the recovery barrier before
new T/U state mutation.

Retire commands are routed through the bank array by the command's
`tuRetirePeId/tuRetireStid`, then into that bank group's
`TULinkRecoveryCleanupPath` and `TULinkRename`. This selector is intentionally
independent from the active rename selector. The bridge exposes
`tuRetireAccepted` so the upstream serializer can advance on actual
mark/release acceptance after flush and commit priority have been applied.

Local block commit is owned by the bank array. The bridge forwards
`tuLocalBlockCommit*` into `TULinkLocalBankArray`, which uses
`TULinkLocalBlockCommitFanout` to select the event STID and wait for every
selected scalar PE bank group to be ready before pulsing child banks. This lets
`TULinkRetireCommandPath` keep the R68 event pending while recovery flush or
an external commit occupies a local maintenance slot. The current reduced
bridge selects PE0 plus the queued row STID for rename traffic, while retire
traffic uses the command PE/STID sidecar. The local block-commit path is
already expressed as the selected-STID fanout shape.

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
`TULinkLocalBankArray`, which applies `LocalRegMgr::ReportBlockCommit` through
each selected bank group's `TULinkRecoveryCleanupPath` and
`TULinkRename.commit*`. R70 preserves the model-selected STID on that event,
and R72 adds the explicit bank-array boundary that matches
`sgprRenameUnit[pe][stid][hand]` structurally. R73 starts consuming the
existing Chisel row-owned STID sidecar for that selector, matching the model's
`SPERename::Rename()` lookup by `inst->stid` for the reduced single-PE lane.
R74 matches `SPERename::RepLocalRetired(type, peid, ..., tid)` by forwarding
the retired-row command PE/STID to the bank array, so local mark/release is no
longer tied to the current rename-head bank.
R75 completes the active selector side of that same ownership contract by
driving active PE from the row's `peId` sidecar, matching
`sgprRenameUnit[inst->peID][inst->stid]`.

## Deferred Owners

- Width-wide scalar plus T/U rename.
- Old T/U physical tag release accounting for destination overwrite.
- Ready-table initialization and wakeup ownership for T/U sources.
- Commit-trace representation of non-GPR destination ownership.
- Multi-PE backend/top integration. The bridge already consumes active
  `peId/stid` sidecars, but the default reduced frontend/top still drives PE0
  unless a later owner produces nonzero PE packets and instantiates matching
  banks.
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
bash tools/chisel/run_chisel_tests.sh --only TULinkLocalBankArray
bash tools/chisel/run_chisel_tests.sh --only TULinkRecoveryCleanupPath
bash tools/chisel/run_chisel_tests.sh --only TULinkRetireCommandPath
bash tools/chisel/run_chisel_tests.sh --only TULinkRename
bash tools/chisel/run_chisel_tests.sh --only DecodeRenameROBPath
```

The current tests cover the atomic scalar/T/U accept reference rule, scalar
input sanitization for T/U operands, local block-commit maintenance
backpressure including STID mismatch, explicit active-bank selector validity,
split-store T/U source lookup bypass, retire-command PE/STID selector
validity, IO shape, and elaboration through
`ScalarDecodeRenameBridge`, `TULinkLocalBankArray`,
`TULinkRecoveryCleanupPath`, `TULinkLocalBlockCommitFanout`, and
`TULinkRename`.
