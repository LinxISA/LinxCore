# TULinkRename

## Source Mapping

- Chisel: `chisel/src/main/scala/linxcore/rename/TULinkRename.scala`
- Tests: `chisel/src/test/scala/linxcore/rename/TULinkRenameSpec.scala`
- LinxCoreModel:
  - `model/LinxCoreModel/model/bctrl/spe/SPERename.cpp`
  - `model/LinxCoreModel/model/bctrl/LocalRegMgr.cpp`
  - `model/LinxCoreModel/model/bctrl/LocalRegMgr.h`
  - `model/LinxCoreModel/model/bctrl/spe/SPEROB.cpp`
  - `model/LinxCoreModel/model/ModelCommon/bus/FlushBus.h`
  - `model/LinxCoreModel/model/ModelCommon/LSUUtils.cpp`
- Related Chisel:
  - `chisel/src/main/scala/linxcore/frontend/FrontendRegAliasClassify.scala`
  - `chisel/src/main/scala/linxcore/frontend/FrontendOperandDecode.scala`
  - `chisel/src/main/scala/linxcore/rename/ScalarDecodeRenameBridge.scala`

## Purpose

`TULinkRename` is the first Chisel owner for scalar T/U link rename state. It
consumes the `OperandClass.T/U` sources and `DestinationKind.T/U` destinations
already emitted by frontend operand classification and resolves them through
two independent scalar local-register queues.

This module deliberately does not broaden `ScalarDecodeRenameBridge`.
Scalar GPR rename remains a 24-register `OperandClass.P` owner. T and U link
state is local-register queue state with model-specific sequence and pressure
rules, so it needs a separate owner before the decode/rename path can compose
GPR and T/U rename into one accepted `RenamedUop`.

## Interface

Inputs:

- `in`: one decoded uop with up to three source operands and one destination.
- `renameValid`: accepts the decoded uop when `ready` is true.

Outputs:

- `ready` / `accepted`: one-uop handshake for the T/U sidecar owner.
- `src`: per-source T/U resolution with operand class, relative tag, mapQ
  sequence, physical tag, hit, and underflow diagnostics.
- `dst`: T/U destination allocation sideband with kind, relative tag, current
  sequence, current physical tag, accepted allocation, and allocation block.
- `tSeq` / `uSeq`: current T and U mapQ allocation sequence before any
  same-cycle destination allocation.
- `needsTAlloc` / `needsUAlloc`: decoded destination is a T or U link
  destination.
- `blockedByTAlloc` / `blockedByUAlloc`: destination allocation would exceed
  that bank's model `CheckStall` pressure rule.
- `sourceUnderflowMask`: T/U source offsets that do not resolve to a live
  mapQ row.
- `tAllocPhysTag` / `uAllocPhysTag`: current scalar local physical pointer.
- `tMapQValidMask` / `uMapQValidMask`: live mapQ rows for observability.
- `tUsedEntries` / `uUsedEntries`: scalar `usedEntrySize[0]` analogs.
- `tUsedPhys` / `uUsedPhys`: scalar `usedPSize[0]` analogs.

## Logic Design

The module instantiates two identical banks, one for `OperandClass.T` and one
for `OperandClass.U`. Each bank owns:

- a mapQ with `mapQDepth` entries,
- a current allocation sequence `mapQAllocPtr[0]`,
- a circular physical pointer `allocPtr[0]`,
- used entry and used physical counts.

On reset, both banks are empty, both allocation sequences are zero, and both
physical pointers are zero.

For a valid T/U source, the relative tag is the model source offset. Source
resolution computes:

```text
seq = mapQAllocPtr[0] - (offset + 1)
```

and returns the physical tag from `mapQ[seq.value]`. The source does not see a
same-cycle destination allocation because `seq` is computed from the
pre-allocation pointer, matching `SPERename::Rename()` setting `inst->tSeq`
and `inst->uSeq` before destination rename. If the offset reaches deeper than
the current used-entry count or the selected mapQ row is not valid, the module
raises source underflow instead of silently wrapping.

For a T or U destination, an accepted uop writes the current allocation entry
with `(bid, rid, gid, seq, physTag)`, then increments the sequence and advances
the physical pointer modulo that bank's local register count. This follows the
scalar `LocalRegMgr::Alloc(ROBID bid, ROBID rid, uint32_t size)` behavior:
the physical tag is the current circular `allocPtr[0]`, not the result of a
first-free search. The bank stalls when either:

```text
usedEntrySize[0] + 1 > mapSize
usedPSize[0] + 1 > pSize
```

matching `LocalRegMgr::CheckStall` for non-vector scalar local registers.

## Model Alignment

`SPERename::Build()` creates independent `LocalRegMgr` instances for
`OPD_TLINK` and `OPD_ULINK` with `local_reg_t`, `local_reg_u`, and
`speROBDepth`. `SPERename::Rename()` captures both current sequences before
renaming sources and destinations. `RenameSrcPOperand()` dispatches T/U
sources by relative offset, and `RenameDstPOperand()` allocates one scalar
local register for each T/U destination.

The current frontend maps source reg6 tags `24..27` to T offsets `0..3` and
`28..31` to U offsets `0..3`. It maps destination tag `31` to T and tag `30`
to U. `TULinkRename` consumes those decoded classes directly and does not
reinterpret the reg6 namespace.

## Deferred Owners

- Composition with `ScalarDecodeRenameBridge` into a unified accepted renamed
  payload.
- Ready-table initialization and wakeup state for T/U physical tags.
- SPEROB release path: `ReportRetired`, relation cmap, long-latency release,
  and block commit.
- Flush recovery using `FlushBus.req.tSeq` and `FlushBus.req.uSeq`, including
  LSU adjustment through `GetPrevRegSeq` when the flushed instruction itself
  owns a T/U destination.
- Multi-PE and multi-thread bank replication.
- Vector, SIMT, predicate, tile, and reuse-link variants.

## Verification

Focused gate:

```bash
bash tools/chisel/run_chisel_tests.sh --only TULinkRename
```

Affected gates:

```bash
sbt --client --error 'Test / compile'
bash tools/chisel/run_chisel_tests.sh --only FrontendDecodeStage
bash tools/chisel/run_chisel_tests.sh --only ScalarDecodeRenameBridge
bash tools/chisel/run_chisel_tests.sh --only DecodeRenameROBPath
bash tools/chisel/run_chisel_rob_bookkeeping.sh --reduced-rob
```

The current tests cover:

- pre-allocation sequence capture,
- source lookup by `offset + 1` behind the current allocation pointer,
- independent T and U banks,
- source underflow diagnostics,
- mapQ and local physical-count pressure stalls,
- IO width and sequence observability,
- elaboration without instantiating `GPRRenameCheckpoint`,
- stable T/U enum values.
