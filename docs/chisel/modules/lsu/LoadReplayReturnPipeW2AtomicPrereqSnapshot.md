# LoadReplayReturnPipeW2AtomicPrereqSnapshot

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2AtomicPrereqSnapshot.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/LoadReplayReturnPipeW2AtomicPrereqSnapshotSpec.scala`
- Future integrated user: `rtl/LinxCore/chisel/src/main/scala/linxcore/top/LinxCoreFrontendFetchRfAluTraceTop.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/iex/pipe/lda_pipe.cpp`: `LDAPipe::runW2`, `LDAPipe::move`
  - `model/LinxCoreModel/model/iex/pipe/agu_pipe.cpp`: `AGUPipe::runW2`, `AGUPipe::move`
  - `model/LinxCoreModel/model/lsu/load_unit/ldq.cpp`: `LDQInfo::returnData`
  - `model/LinxCoreModel/model/iex/iex.cpp`: `IEX::setMemData`
  - `model/LinxCoreModel/model/pe/PECommon/PROBCommon.cpp`: `ROBState::resolveData`
- Related Chisel contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2AtomicRequestEnablePolicy.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2AtomicRequestGate.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2SideEffectReady.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2ClearCommitGuard.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2CommitRowCandidate.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2ReplayRowLifecycleReady.scala`
- Contract IDs: `LC-CHISEL-LSU-REPLAY-PIPE-W2-ATOMIC-PREREQ-SNAPSHOT-001`

## Purpose

`LoadReplayReturnPipeW2AtomicPrereqSnapshot` is the register boundary for the
future live replay-return W2 atomic request policy. R530 showed that feeding
current top clear, side-effect readiness, row-fill, and replay-row lifecycle
signals directly into `LoadReplayReturnPipeW2AtomicRequestEnablePolicy` creates
combinational cycles. This module gives later top integration a place to sample
those observations before request issue, then feed only the previous-cycle
snapshot into the policy.

The C++ model treats W2 as a resident-instruction stage: `LDAPipe::runW2` and
`AGUPipe::runW2` publish RF writeback, resolve, and scalar wakeup while
`move()` advances W1 into W2 afterward. The Chisel policy must preserve that
single resident boundary without letting same-cycle side-effect enables become
their own prerequisites.

## Interface

| Direction | Signal | Description |
|---|---|---|
| input | `enable` / `flush` | Replay-LIQ integration arm and flush clear. |
| input | `captureEnable` | Allows the top to sample a candidate pre-request observation. |
| input | `slotOccupied` | Resident W2 slot is present. |
| input | `slotBid` / `slotGid` / `slotRid` / `slotLoadLsId` | Resident W2 identity used to validate the snapshot on later cycles. |
| input | `sideEffectSinksReadyIn` | Observed readiness for required side-effect sinks. |
| input | `clearCommitReadyIn` | Observed clear/resolve/ROB identity readiness. |
| input | `rowFillCandidateValidIn` | Observed commit-row replacement candidate readiness. |
| input | `lifecycleRowClearReadyIn` | Observed unique replay-LIQ row-clear readiness. |
| output | `captureCandidate` / `captureAccepted` | Snapshot capture handshake. Capture accepts only active occupied slots with valid identity. |
| output | `snapshotValid` / `snapshotMatchesSlot` / `prereqsUsable` | Registered snapshot state and same-resident-slot validation. |
| output | prerequisite outputs | Captured prerequisite values, masked false unless the snapshot is usable for the current resident slot. |
| output | `residentPrereqsReady` | All captured resident prerequisites are true for the same resident slot. |
| output | `snapshot*` identity | Captured BID/GID/RID/load-LSID diagnostics. |
| output | blocker signals | Disabled, flush, capture disabled, no slot, invalid identity, no snapshot, identity mismatch, and ordered missing-prerequisite diagnostics. |

## Logic Design

```text
active = enable && !flush
slotIdentityValid = slotBid.valid && slotGid.valid && slotRid.valid && slotLoadLsId.valid
captureCandidate = active && captureEnable && slotOccupied
captureAccepted = captureCandidate && slotIdentityValid

on !active:
  snapshotValid = false
on captureAccepted:
  snapshotValid = true
  snapshot identity = current slot identity
  snapshot prereqs = current prerequisite observations

snapshotMatchesSlot =
  snapshotValid &&
  slotOccupied &&
  current identity valid &&
  captured identity == current identity

prereqsUsable = active && snapshotMatchesSlot
residentPrereqsReady =
  prereqsUsable &&
  capturedSideEffectSinksReady &&
  capturedClearCommitReady &&
  capturedRowFillCandidateValid &&
  capturedLifecycleRowClearReady
```

The snapshot deliberately does not decide which side effects are required. That
remains owned by `LoadReplayReturnPipeW2AtomicRequestEnablePolicy`, which gates
side-effect sink readiness only when side-effect evidence is present. This
module only prevents a later top packet from using current-cycle side-effect,
clear, row-fill, or lifecycle effects as request prerequisites.

## Integration

R532 adds this module as a standalone owner. It is not wired into
`LinxCoreFrontendFetchRfAluTraceTop` yet. A future top packet should connect
the current-cycle prerequisite owners into this snapshot and feed the snapshot
outputs into `LoadReplayReturnPipeW2AtomicRequestGate` only after the focused
top spec proves there is no FIRRTL cycle. `liveModeEnable` must remain false
until generated-RTL evidence proves the whole W2 side-effect, clear, row-fill,
and lifecycle chain can commit atomically.

## Deferred Owners

- Top-level snapshot wiring with `liveModeEnable=false`.
- Generated-RTL proof that snapshot-fed policy prerequisites preserve the
  dormant reduced path.
- Later live-mode proof with request issue, side effects, clear, row fill, and
  replay-row lifecycle mutation enabled together.

## Verification

Focused gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2AtomicPrereqSnapshot
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2AtomicRequestGate
git diff --check
```

Reference tests cover same-identity snapshot reuse, identity mismatch, flush
clear, invalid resident identity suppression, ordered missing-prerequisite
blockers, and Chisel elaboration of the registered snapshot state.
