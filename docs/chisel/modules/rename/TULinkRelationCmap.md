# TULinkRelationCmap

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/rename/TULinkRelationCmap.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/rename/TULinkRelationCmapSpec.scala`
- Shared bundles: `rtl/LinxCore/chisel/src/main/scala/linxcore/common/TULinkBundles.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/bctrl/spe/SPEROB.cpp`
  - `model/LinxCoreModel/model/bctrl/spe/SPEROB.h`
  - `model/LinxCoreModel/model/bctrl/spe/SPERename.cpp`
  - `model/LinxCoreModel/model/pe/PECommon/PROBCommon.h`
  - `model/LinxCoreModel/model/pe/PECommon/PROBCommon.cpp`
  - `model/LinxCoreModel/model/vectorcore/vpe/VecPEROB.h`
- Related Chisel:
  - `chisel/src/main/scala/linxcore/rename/TULinkRetireCommandPath.scala`
  - `chisel/src/main/scala/linxcore/rename/TULinkRename.scala`
  - `chisel/src/main/scala/linxcore/rob/ROBEntryBank.scala`
  - `chisel/src/main/scala/linxcore/backend/DispatchROBAllocator.scala`
  - `chisel/src/main/scala/linxcore/backend/DecodeRenameROBPath.scala`

## Purpose

`TULinkRelationCmap` is the first Chisel owner for the SPEROB relation-cmap
retire and release policy for scalar T/U local registers. It consumes
serialized ROB-deallocation row sidecars, stores per-kind relation entries for
T and U destinations, and emits serialized `TULinkRename.retire*` commands.

This module does not replace `TULinkRename`. `TULinkRename` owns local mapQ
allocation, mark-retired state, block-commit freeing, and flush pruning.
`TULinkRelationCmap` owns the SPEROB decision about when a row's T/U
destination should be marked retired and when the oldest relation entry should
be deallocated.

## Interface

Inputs:

- `in`: one `TULinkRetireSource` from the source serializer. It carries
  `valid`, `isLast`, native `bid/gid/rid`, `stid`, row-owned `tSeq/uSeq`, and
  T/U destination ownership.
- `clear`: synchronous reset of relation queues and pending command state.
- `commandReady`: downstream readiness for accepting one retire command.

Outputs:

- `inReady`, `inAccepted`: row input handshake. Rows are held off while older
  relation entries must be released first or while a pending mark/release
  command is still outstanding.
- `command`: one `TULinkRetireCommand` per cycle. `dealloc=false` marks a T/U
  mapQ row retired; `dealloc=true` releases the current dealloc-head sequence.
- `commandFire`: `command.valid && commandReady`.
- `unsupportedDst`: input row names a destination class other than T or U.
- `preReleaseT/preReleaseU`: older relations must be released before the
  current row because the current row is block-last or belongs to a new
  `(bid,gid)` group.
- `pressureReleaseT/pressureReleaseU`: accepting the current row will exceed
  the model pressure threshold and require an oldest-entry release after the
  mark command.
- `pendingMark`, `pendingPostReleaseT`, `pendingPostReleaseU`: serialized
  command backlog diagnostics.
- `tCount/uCount`: resident relation entries per kind.

## State

- T relation FIFO: stores `(bid,gid,seq)` for T destinations.
- U relation FIFO: stores `(bid,gid,seq)` for U destinations.
- Pending mark register: stores the accepted row's T/U mark-retired command.
- Pending post-release bits: remember that the just-marked row also triggered
  an oldest-entry release due to block-last or pressure.

The relation FIFO depth is parameterized and must be a power of two. The
default release threshold is `4`, matching the model's
`LOGIC_UT_COUNT_4` threshold used by `ReleaseFunc`.

## Logic Design

The module serializes the C++ `SPEROB::ReleaseRelative()` behavior onto a
single retire-command port:

- Before accepting a row, it checks both T and U relation queues. If the row is
  block-last or if the newest resident relation belongs to a different
  `(bid,gid)`, existing entries are released first.
- Pre-release order is T queue first, then U queue, matching
  `CheckRelativeReg()` calling `CheckReg(tcmap)` before `CheckReg(ucmap)`.
- After pre-release drains, an accepted T or U destination is enqueued and a
  mark-retired command is emitted for that row's `tSeq` or `uSeq`.
- If the accepted row is block-last, or if the queue had at least four entries
  before enqueue, the module emits a deallocation command for the oldest entry
  after the mark command fires.
- A full relation queue also forces oldest-entry release before accepting a new
  same-kind destination, preventing structural deadlock if the downstream
  serializer stalls.

The command stream deliberately emits the mark command before any
post-accept release for the same row. This preserves the `ReportRetired(seq,
false)` then optional `ReportRetired(seq, true)` ordering in
`SPEROB::ReleaseFunc()`.

## Model Alignment

The C++ model separates three effects:

- `ReleaseRelative()` is called from `SPEROB::dealloc()` for retired ROB rows.
- `ReleaseFunc()` writes the relation entry and calls
  `RepLocalRetired(..., isDealloc=false)` to mark the T/U local mapQ entry
  retired.
- Block-last or relation-pressure release reads the oldest relation entry and
  calls `ReportRetired(seq, true)` on the matching local register manager.

`TULinkRelationCmap` implements those ordering decisions for scalar T/U
destinations. Ready-table updates, predicate links, vector local links,
multi-PE bank selection, and full block-commit freeing remain outside this
module.

## Deferred Owners

- Relation flush pruning equivalent to `FlushRelativeReg`,
  `CleanCMAP`, and `CleanGroupCMAP`.
- Predicate, vector, SIMT, and tile relation maps.
- Ready-table mutation and physical tag wakeup/release side effects.
- Per-PE, per-STID bank replication.

## Verification

Focused gate:

```bash
bash tools/chisel/run_chisel_tests.sh --only TULinkRelationCmap
```

Affected gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only InterfaceBundles
bash tools/chisel/run_chisel_tests.sh --only TULinkRetireCommandPath
bash tools/chisel/run_chisel_tests.sh --only ROBEntryBank
bash tools/chisel/run_chisel_tests.sh --only DispatchROBAllocator
bash tools/chisel/run_chisel_tests.sh --only DecodeRenameROBPath
```

The tests cover pressure release after the fifth same-kind relation,
group-change pre-release order, block-last drain plus current-row mark/release,
and Chisel elaboration of the serialized command interface.
