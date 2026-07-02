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
native ROB RID wrap/value plus STID, marks the matched STQ row committed, and
then issues a later single-index committed-row free.

This module is intentionally narrower than the full LSU commit path. It lets
`LinxCoreFrontendFetchRfAluTraceTop(useReducedStoreDispatchStq=true)` prove
store-dispatch residency without permanently filling the STQ, but it does not
replace `STQCommitDrain`, `STQSCBCommitPath`, SCB admission, cache update,
memory mutation, MDB conflict detection, or load-store forwarding.

## Interface

Inputs:

- `enable`: activates the reduced owner. When low, the owner clears pending
  state and drives no mark/free commands.
- `flushValid`: clears pending mark/free state. The reduced top asserts this
  on backend flush, start, restart, and while the optional STQ path is disabled.
- `activeStid`: STID used to match committed rows against resident STQ rows.
- `commit`, `commitValidMask`: monitored ROB commit window from
  `DecodeRenameROBPath`.
- `stqRows`: current STQ row image forwarded from `StoreDispatchSTQPath`.
- `markCommitAccepted`, `markCommitIgnored`: result of the mark command after
  `STQEntryBank` checks row state.
- `commitFreeAccepted`, `commitFreeIgnored`,
  `commitFreeAcceptedMask`, `commitFreeIgnoredMask`: result of the single-row
  committed free command.

Outputs:

- `markCommitValid`, `markCommitIndex`: one pending STQ row to move from
  `Wait` to `Commit`.
- `commitFreeValid`, `commitFreeIndex`: one previously marked row to free.
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
accepts that command, the bit leaves `pendingMarkMask` and enters
`pendingFreeMask`. On a later cycle, the owner presents the lowest pending free
bit as a single `commitFreeValid` command. Accepted free clears the bit.

The register boundary matters. Commit-window observation only sets pending mark
bits for a later cycle, and accepted marks only create pending free bits for a
later cycle. This preserves the model's visible `Wait -> Commit -> Free`
sequence instead of trying to free a row in the same cycle it is marked.

## Model Alignment

The C++ model separates store lifecycle into two phases:

- `STQ::retire` marks ready stores as `STQ_COMMIT` when block/ROB order makes
  them non-flushable, then pushes their indices into `storeCommitQ`.
- `STQ::commit` drains `storeCommitQ` in order, sends one or two memory
  requests to SCB, and calls `free(i)` only after the downstream request is
  accepted.

This reduced owner preserves the two observable row-state transitions but uses
a top-only debug free assumption: once `STQEntryBank` accepts the commit mark,
the owner later frees the row directly. That is sufficient to keep the optional
reduced STQ dispatch path finite during bring-up. It is not sufficient for
program-order memory effects or for load/store interaction.

## Deferred Owners

- Replace the reduced direct-free assumption with `STQCommitDrain` or
  `STQSCBCommitPath` in the live reduced top.
- Carry SCB capacity feedback and accepted `last` fragments back to the STQ
  bank as the only full-composition free source.
- Add store memory mutation and load lookup/store-forwarding interaction.
- Add MDB conflict publication and precise recovery cleanup for committed STQ
  rows if the full exception model requires it.

## Verification

Focused gate:

```bash
sbt "testOnly linxcore.lsu.ReducedStoreCommitFreeOwnerSpec"
```

Integrated gate used for R240:

```bash
sbt "testOnly linxcore.lsu.ReducedStoreCommitFreeOwnerSpec linxcore.lsu.ReducedStoreExecResultBridgeSpec linxcore.backend.DecodeRenameROBPathSpec linxcore.top.LinxCoreFrontendFetchRfAluTraceTopSpec linxcore.lsu.STQCommitDrainSpec linxcore.lsu.STQSCBCommitPathSpec"
```

The tests cover commit-row-to-STQ matching, pending mark/free progression,
unmatched store diagnostics, non-ready row rejection, and top/backend
elaboration of the new diagnostics.
