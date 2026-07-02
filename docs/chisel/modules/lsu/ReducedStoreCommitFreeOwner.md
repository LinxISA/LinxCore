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
store rows from `DecodeRenameROBPath`, matches them to resident STQ rows by the
model `CommitTrace.identity` tuple plus STID, and marks the matched STQ row
committed. It also buffers committed-store identities when ROB commit becomes
visible before the matching reduced STQ row is resident and markable.
It can also run in a legacy direct-free mode that issues a later single-index
committed-row free, but `LinxCoreFrontendFetchRfAluTraceTop` disables that mode
after R241 and frees rows from the SCB accepted-`last` mask instead.

This module is intentionally narrower than the full LSU commit path. It lets
`LinxCoreFrontendFetchRfAluTraceTop(useReducedStoreDispatchStq=true)` prove
store-dispatch residency and ROB-to-STQ mark ownership, but it does not own
cache update, MDB conflict detection, or full load-store forwarding. R258 adds
a separate reduced store-memory overlay for accepted committed fragments; this
owner still only marks and frees STQ rows.

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
- `pendingCommitIdentityMask`: buffered committed-store identities that are
  waiting for a future resident STQ row with the same model identity.
- `pendingMarkMask`, `pendingFreeMask`,
  `pendingMarkCount`, `pendingFreeCount`: registered owner state.
- `markBlocked`, `freeBlocked`: command was issued but the STQ bank did not
  accept it.
- `idle`: no pending mark or free work remains.

## Logic Design

The owner does not maintain a parallel insertion map. `StoreDispatchToSTQ`
already stamps each STQ row with the renamed uop's model `bid/gid/rid`,
`lsId`, and STID sidecars. The ROB commit row exposes the same model identity
through `CommitTraceRow.identity`. A committed store row matches an STQ row
when:

1. the commit slot is valid,
2. the commit row is a store memory row,
3. the commit row has a model `CommitTrace.identity` value,
4. the STQ row is valid, `Wait`, `ST_ALL`, address-ready, and data-ready,
5. the STQ row model `bid/gid/rid` equals the commit identity,
6. the STQ row STID equals `activeStid`.

Matched rows set bits in `pendingMarkMask`. If a committed store identity does
not match a currently markable STQ row, the owner records that identity in a
small pending-commit buffer. Later cycles compare resident STQ rows against
both the current commit window and buffered identities, then clear the buffered
identity once a matching row becomes markable. This covers the reduced top case
where ROB commit observation can race ahead of store queue residency.

The owner presents the lowest pending mark bit as a single `markCommitValid`
command. When `STQEntryBank` accepts that command, the bit leaves
`pendingMarkMask`. If `directFreeEnable` is asserted, the bit also enters
`pendingFreeMask`; on a later cycle the owner presents the lowest pending free
bit as a single `commitFreeValid` command and accepted free clears the bit. If
`directFreeEnable` is deasserted, `pendingFreeMask` is held empty and another
owner must free the committed row.

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

R253 corrects the reduced owner matching contract: commit rows must match STQ
rows through model `bid/gid/rid`, not the physical ROB sideband, and unmatched
committed-store identities must be retained until the split-store path has
inserted a ready `ST_ALL` row. The focused owner gate passes, the 240-row
live-QEMU reduced-store wrapper compares 162 rows with zero mismatches, and a
direct replay over the 726-row R252 expected stream compares 495 architectural
rows with zero mismatches.

R254-R257 leave the owner logic unchanged and scale the same reduced-store
proof. The live wrapper passes at 1024 raw CoreMark/QEMU rows with 665 compared
rows. Reusing the R254 generated testbench against fresh loop-reentry-enabled
captures then passes at 2048, 4096, and 8192 raw rows, comparing 1467, 3369,
and 7172 rows respectively with zero mismatches.

R258 keeps this owner unchanged. It consumes the downstream SCB accepted
fragment stream in `ReducedStoreMemoryOverlay`, so the reduced top can prove
later load visibility without harness-side sparse memory mutation. The
commit/free owner remains responsible only for ROB-to-STQ mark identity and
free-source integration.

## Deferred Owners

- Replace the reduced store-memory overlay with full load lookup,
  store-forwarding, wait/replay, and cache interaction.
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

The tests cover commit-identity-to-STQ matching, pending commit identity
buffering, pending mark/free progression, direct-free disable behavior,
unmatched store diagnostics, non-ready row rejection, drain flush clearing, and
top/backend elaboration of the SCB-backed free diagnostics.
