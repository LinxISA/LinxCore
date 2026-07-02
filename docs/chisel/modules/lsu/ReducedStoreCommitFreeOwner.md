# ReducedStoreCommitFreeOwner

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/ReducedStoreCommitFreeOwner.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/ReducedStoreCommitFreeOwnerSpec.scala`
- Integrated users:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/top/LinxCoreFrontendFetchRfAluTraceTop.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/backend/DecodeRenameROBPath.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/StoreDispatchSTQPath.scala`
- LinxCoreModel evidence:
  - `tools/LinxCoreModel/model/lsu/store_unit/store_unit.cpp`
    - `StoreUnit::GetCommitID`
    - `StoreUnit::checkCommit`
  - `tools/LinxCoreModel/model/lsu/store_unit/stq.cpp`
    - `STQ::retire`
    - `STQ::commit`
    - `STQ::free`

## Purpose

`ReducedStoreCommitFreeOwner` is the reduced-top lifecycle owner that connects
ROB commit rows to the optional queue-backed STQ path. It observes committed
store rows from `DecodeRenameROBPath`, matches them to resident STQ rows by
native ROB RID wrap/value plus STID, and marks the matched STQ row committed.
It can also run in a legacy direct-free mode that issues a later single-index
committed-row free, but `LinxCoreFrontendFetchRfAluTraceTop` disables that mode
after R241 and frees rows from the SCB accepted-`last` mask instead.

This module is intentionally narrower than the full LSU commit path. It lets
`LinxCoreFrontendFetchRfAluTraceTop(useReducedStoreDispatchStq=true)` prove
store-dispatch residency and ROB-to-STQ mark ownership, but it does not own
cache update, memory mutation, MDB conflict detection, or load-store forwarding.

## Interface

Inputs:

- `enable`: activates the reduced owner. When low, the owner clears pending
  state and drives no mark/free commands.
- `directFreeEnable`: enables the legacy direct-free queue. The reduced top
  drives this low so accepted marks feed `STQCommitDrain` and `SCBRowBank`
  rather than this owner.
- `flushValid`: clears pending mark/free state. The reduced top asserts this
  on backend flush, start, restart, and while the optional STQ path is disabled.
- `activeStid`: STID used to match committed rows against resident STQ rows.
- `commit`, `commitValidMask`: monitored ROB commit window from
  `DecodeRenameROBPath`.
- `stqRows`: current STQ row image forwarded from `StoreDispatchSTQPath`.
- `markCommitAccepted`, `markCommitIgnored`: result of the mark command after
  `STQEntryBank` checks row state.
- `commitFreeAccepted`, `commitFreeIgnored`,
  `commitFreeAcceptedMask`, `commitFreeIgnoredMask`: result feedback for the
  legacy single-row committed free command. With `directFreeEnable=0`, these
  inputs are diagnostic only.

Outputs:

- `markCommitValid`, `markCommitIndex`: one pending STQ row to move from
  `Wait` to `Commit`.
- `commitFreeValid`, `commitFreeIndex`: one previously marked row to free when
  `directFreeEnable=1`; tied inactive by the reduced top after R241.
- `commitStoreSeen`, `commitStoreMatched`, `commitStoreUnmatched`: current
  commit-window diagnostics.
- `matchMask`: STQ rows matched by current committed store rows.
- `pendingMarkMask`, `pendingFreeMask`,
  `pendingMarkCount`, `pendingFreeCount`: registered owner state.
- `markBlocked`, `freeBlocked`: command was issued but the STQ bank did not
  accept it.
- `idle`: no pending mark or free work remains.

## Logic Design

The owner does not maintain a parallel insertion map. `StoreDispatchToSTQ`
already stamps each STQ row with the renamed uop's `rid`, `bid`, `lsId`, and
STID sidecars. The ROB commit row already exposes the native `rob` RID with
wrap/value. A committed store row matches an STQ row when:

1. the commit slot is valid,
2. the commit row is a store memory row,
3. the commit row has a valid native ROB RID,
4. the STQ row is valid, `Wait`, `ST_ALL`, address-ready, and data-ready,
5. the STQ row RID equals the commit row RID using wrap/value comparison,
6. the STQ row STID equals `activeStid`.

Matched rows set bits in `pendingMarkMask`. The owner presents the lowest
pending mark bit as a single `markCommitValid` command. When `STQEntryBank`
accepts that command, the bit leaves `pendingMarkMask`. If `directFreeEnable`
is asserted, the bit also enters `pendingFreeMask`; on a later cycle the owner
presents the lowest pending free bit as a single `commitFreeValid` command and
accepted free clears the bit. If `directFreeEnable` is deasserted,
`pendingFreeMask` is held empty and another owner must free the committed row.

The register boundary matters. Commit-window observation only sets pending mark
bits for a later cycle. In direct-free mode, accepted marks create pending free
bits for a later cycle. In the reduced top's R241 mode, accepted marks enqueue
`STQCommitDrain` and SCB accepted `last` fragments become the free source.

## Model Alignment

The C++ model separates store lifecycle into two phases:

- `STQ::retire` marks ready stores as `STQ_COMMIT` when block/ROB order makes
  them non-flushable, then pushes their indices into `storeCommitQ`.
- `STQ::commit` drains `storeCommitQ` in order, sends one or two memory
  requests to SCB, and calls `free(i)` only after the downstream request is
  accepted.

This reduced owner preserves the ROB-to-STQ `Wait -> Commit` transition. The
R240 direct-free mode preserved `Commit -> Free` with a top-only debug
assumption. R241 disables that mode in the reduced top and wires the accepted
mark into `STQCommitDrain`, with `SCBRowBank.commitFreeMask` as the only STQ
free source. Program-order memory effects, external memory mutation, and
load/store interaction are still outside this owner.

## Deferred Owners

- Add store memory mutation and load lookup/store-forwarding interaction.
- Add MDB conflict publication and precise recovery cleanup for committed STQ
  rows if the full exception model requires it.

## Verification

Focused gate:

```bash
sbt "testOnly linxcore.lsu.ReducedStoreCommitFreeOwnerSpec"
```

Integrated gate used for R241:

```bash
sbt "testOnly linxcore.lsu.STQCommitQueueSpec linxcore.lsu.STQCommitDrainSpec linxcore.lsu.STQSCBCommitPathSpec linxcore.lsu.ReducedStoreCommitFreeOwnerSpec linxcore.lsu.ReducedStoreExecResultBridgeSpec linxcore.backend.DecodeRenameROBPathSpec linxcore.top.LinxCoreFrontendFetchRfAluTraceTopSpec"
```

The tests cover commit-row-to-STQ matching, pending mark/free progression,
direct-free disable behavior, unmatched store diagnostics, non-ready row
rejection, drain flush clearing, and top/backend elaboration of the SCB-backed
free diagnostics.
