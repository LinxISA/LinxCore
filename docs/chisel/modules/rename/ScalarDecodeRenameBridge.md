# ScalarDecodeRenameBridge

## Source Mapping

- Chisel: `chisel/src/main/scala/linxcore/rename/ScalarDecodeRenameBridge.scala`
- Tests: `chisel/src/test/scala/linxcore/rename/ScalarDecodeRenameBridgeSpec.scala`
- LinxCoreModel: `model/LinxCoreModel/model/bctrl/spe/Decoder.cpp`
- LinxCoreModel: `model/LinxCoreModel/model/bctrl/spe/DCTop.cpp`
- LinxCoreModel: `model/LinxCoreModel/model/bctrl/spe/SPERename.cpp`
- LinxCoreModel: `model/LinxCoreModel/model/bctrl/spe/GPRRename.cpp`
- Related Chisel:
  - `chisel/src/main/scala/linxcore/frontend/FrontendDecodeStage.scala`
  - `chisel/src/main/scala/linxcore/rename/GPRRenameCheckpoint.scala`
  - `chisel/src/main/scala/linxcore/backend/DispatchROBAllocator.scala`

## Purpose

`ScalarDecodeRenameBridge` is the first D2 decode-to-rename staging owner. It
consumes one `DecodedUop`, maps scalar architectural GPR sources through
`GPRRenameCheckpoint`, optionally allocates one scalar destination physical tag,
and emits both:

- a `RenamedUop` for later dispatch/issue owners, and
- a `CommitTraceRow` allocation request for the ROB/BROB allocation bridge.

This packet deliberately compresses the current bring-up path into one atomic
owner so the next top-level packet can connect frontend decode to real ROB
allocation without inventing more fixture rows. It does not claim the full C++
model timing of `DCTop -> dec_ren_q -> SPERename`; a later queue packet should
split this into registered D2/D3 stages once live frontend/backend flow exists.
`ScalarTURenameBridge` composes this scalar-only owner with the T/U rename
path; this module still rejects non-GPR operand classes by design.

## Interface

Inputs:

- `in`: decoded architectural uop from `FrontendDecodeStage`.
- `outReady`: downstream renamed-uop consumer can accept this cycle.
- `robAllocReady`: downstream ROB/BROB allocation bridge can accept this cycle.
- `checkpointValid` / `checkpointBid`: explicit checkpoint command for the
  composed `GPRRenameCheckpoint`.
- `commitValid` / `commitBid`: explicit block-commit command for the composed
  scalar GPR map owner.
- `cleanup`: `RecoveryCleanupIntent` from `RecoveryCleanupControl`.

Outputs:

- `inReady`: the input uop can be accepted.
- `accepted`: the input fired this cycle.
- `outValid` / `out`: accepted renamed uop.
- `robAllocAttemptValid`: ROB allocation request is well-formed before
  applying allocator ready.
- `robAllocValid` / `robAllocRow`: accepted ROB allocation row request.
- `needsGprRename`: destination GPR allocation is required for the input.
- `unsupportedSrcMask`, `unsupportedDst`, `unsupportedOperandClass`,
  `unsupported`: scalar subset rejection diagnostics.
- `blockedByMaintenance`, `blockedByRename`, `blockedByRob`,
  `blockedByOutput`: first-blocking-reason diagnostics.
- `srcPhysTags`, `dstPhysTag`, `dstOldPhysTag`: scalar GPR map observations.
- `renameAccepted`, `checkpointAccepted`, `commitAccepted`,
  `cleanupFlushApplied`, `cleanupReplayObserved`: composed rename-owner events.
- `gprFreeCount`, `gprMapQFreeCount`: free-list and mapQ pressure.

## Logic Design

The bridge accepts at most one uop per cycle. Acceptance is atomic across three
surfaces:

1. scalar GPR destination rename can allocate, if the uop has a scalar GPR
   destination;
2. the downstream ROB/BROB allocator reports ready;
3. the downstream renamed-uop consumer reports ready.

Recovery cleanup, block commit, and explicit checkpoint commands have priority
over new uop acceptance. This mirrors the state-owner priority already used by
`GPRRenameCheckpoint` and prevents same-cycle cleanup/rename ambiguity.

`robAllocAttemptValid` is the pre-ready allocation request qualifier used by
composition owners. It depends on input validity, maintenance state, scalar
support, rename availability, and output readiness, but it does not depend on
`robAllocReady`. `robAllocValid` remains the accepted allocation event. This
split lets `DispatchROBAllocator` compute duplicate-identity readiness from a
stable request row without feeding allocator ready back into allocator valid.

Accepted source operands with `OperandClass.P` and architectural tags `0..23`
read `GPRRenameCheckpoint.smap`. Accepted GPR destinations in the same
architectural range allocate the first free physical tag and record a mapQ row
with `(bid, rid, gid, archTag, physTag)`.

The output `RenamedUop` preserves the decoded uop identity, `peId/threadId`,
immediate, reduced memory class/split metadata, block sidebands, boundary
sidebands, raw instruction, and UID fields. The first packet only fills
physical tags. It keeps `ready=false` for valid renamed sources because
ready-table
initialization, bypass state, and speculative load-ready policy belong to
issue/ready-table owner packets.

The output `CommitTraceRow` carries the decoded PC, raw instruction, length,
`nextPc = pc + len`, model identity from `(bid,gid,rid).value`, block BID
sideband, and source/destination register numbers. Execution side effects,
writeback data, memory events, trap fields, and ROB slot metadata remain owned
by execution and ROB allocation/commit modules.

## Scalar Subset Boundary

The LinxCoreModel scalar GPR owner has 24 architectural GPRs. Current decoded
payloads use a wider 6-bit reg namespace that can carry aliases such as fixed
compressed tags `24` and `31`, invalid tags, and later T/U/SGPR/tile/vector
classes. This bridge therefore rejects, rather than silently renames:

- any scalar source or destination GPR tag `>= 24`;
- any valid source whose class is not `OperandClass.P`;
- any valid destination whose kind is not `DestinationKind.Gpr`.

Those aliases need a later operand-classification owner before they can be
made backend-visible.

## Model Alignment

The C++ model establishes this order:

1. `Decoder::DecodeInst()` converts architectural operands, assigns decode
   sidebands, and forwards instructions.
2. `DCTop::Work()` allocates a ROB row and writes to `dec_ren_q`.
3. `SPERename::Rename()` maps source operands, allocates destination operands,
   captures block checkpoints for `isLastInBlock`, and forwards to dispatch.
4. `GPRRename` owns scalar `smap`, `cmap`, checkpoints, free tags, and mapQ.

The bridge is a bring-up composition of steps 2 and 3 for scalar GPR operands.
For the reduced in-order marker-row top, every accepted row refreshes the
checkpoint for its block BID through `GPRRenameCheckpoint`'s post-rename
checkpoint input. This approximates the model's `isLastInBlock` capture until
the exact decoded block-last owner is wired, and it preserves return-label
materialization rows such as `C.SETRET` across block-stop redirect cleanup.

## Deferred Owners

- Registered `dec_ren_q` / D2-D3 staging.
- Width-wide rename with multiple destinations per cycle.
- Exact automatic checkpoint capture from validated `isLastInBlock` metadata;
  the reduced top currently uses accepted-row post-rename refresh as a
  conservative in-order approximation.
- LSID allocation and load/store same-cycle ordering.
- Integrated handoff from `StoreSplitPayload` into STA/STD dispatch queues.
- Direct T/U/SGPR/tile/vector rename. T/U is composed by
  `ScalarTURenameBridge` without changing this scalar owner.
- Ready-table initialization, speculative load-ready, and bypass ownership.
- ROB/BROB allocation composition with live block headers.
- Commit/writeback/memory/trap side-effect completion.

## Verification

Focused gate:

```bash
bash tools/chisel/run_chisel_tests.sh --only ScalarDecodeRenameBridge
```

Affected gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only GPRRenameCheckpoint
bash tools/chisel/run_chisel_tests.sh --only FrontendDecodeStage
bash tools/chisel/run_chisel_tests.sh --only DispatchROBAllocator
bash tools/chisel/run_chisel_rob_bookkeeping.sh --reduced-rob
```

The current tests cover:

- scalar valid/ready acceptance conditions,
- maintenance, rename, ROB, and output blocking diagnostics,
- explicit rejection of reg6 aliases outside the scalar GPR owner,
- initial scalar GPR map and first free physical tag reference behavior,
- IO width checks for ROB allocation and rename observability,
- pre-ready ROB allocation attempt visibility,
- Chisel elaboration through the composed `GPRRenameCheckpoint`.
