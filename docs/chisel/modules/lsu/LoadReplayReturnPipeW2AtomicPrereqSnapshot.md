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

R532 adds this module as a standalone owner. R533 wires it into
`LinxCoreFrontendFetchRfAluTraceTop` with `liveModeEnable=false.B`. R534 uses
the same snapshot-fed prerequisites with `liveModeEnable=true.B`. R550 changes
the side-effect prerequisite producer from the live-gated
`LoadReplayReturnPipeW2SideEffectReady.sideEffectsReady` join to pre-request
sink-capacity evidence built from `LoadReplayReturnPipeW2ResolveSinkReady`
`resolveArmed`, `LoadReplayReturnPipeW2WritebackSinkReady.writebackArmed`, and
`LoadReplayReturnPipeW2WakeupSinkReady.wakeupArmed`. Required sinks must be
armed, optional sinks are ignored, and actual resolve/writeback/wakeup mutation
remains gated later by `LoadReplayReturnPipeW2SideEffectLiveControl`. R551
extends the same pre-request split to clear/ROB capacity: the top captures
clear-commit readiness from side-effect sink capacity, a valid resident slot
RID, and `LoadReplayReturnPipeW2RobCompleteSource.sinkReady`, while the live
`LoadReplayReturnPipeW2ClearCommitGuard` remains a post-fire coherence
diagnostic. The top captures those pre-request prerequisites plus observations
from `LoadReplayReturnPipeW2CommitRowCandidate` and
`LoadReplayReturnPipeW2ReplayRowLifecycleReady`, then feeds only the snapshot
outputs into `LoadReplayReturnPipeW2AtomicRequestGate`.

The reduced top still leaves the policy's direct clear-intent and empty-refill
inputs dormant. Raw clear/refill evidence continues to feed the live-request
child for diagnostics, while policy prerequisites come from this registered
snapshot. R533 generated-RTL evidence proves this dormant snapshot-fed path
preserves the reduced top with `liveModeEnable=false.B`. R534 promotes the
request gate to live mode; live mutation remains gated by the same registered
snapshot, side-effect, clear, row-fill, and lifecycle prerequisites.
R550 generated-RTL/QEMU evidence at
`generated/r550-replay-w2-prereq-sink-capacity/report/crosscheck_manifest.json`
passes with 9 compared rows, zero mismatches, and zero QEMU/DUT CBSTOP rows.
The sideband report shows the intended ordered-blocker movement:
`w2_atomic_blocked_by_no_side_effect_sink` drops to 7 while
`w2_atomic_blocked_by_no_clear_commit` rises to 67, so the next owner is
clear-commit readiness rather than side-effect sink capacity.
R551 generated-RTL/QEMU evidence at
`generated/r551-replay-w2-prereq-clear-commit-capacity/report/crosscheck_manifest.json`
passes with 9 compared rows, zero mismatches, and zero QEMU/DUT CBSTOP rows.
The sideband report moves the ordered policy blocker again:
`w2_atomic_blocked_by_no_clear_commit=0`,
`w2_atomic_blocked_by_no_row_fill_candidate=67`,
`w2_atomic_blocked_by_no_side_effect_sink=7`, and
`w2_atomic_request_active=0`. The next owner is pre-request row-fill candidate
readiness rather than clear-commit capacity.
R552 adds sideband visibility below that row-fill aggregate and proves the
missing prerequisite is ROB instruction metadata for the resident W2 RID:
`lret_w2_slot_source_trace_valid=74`,
`w2_commit_row_trace_source_rob_lookup_instruction_valid=0`,
`w2_commit_row_trace_source_blocked_by_no_metadata=74`, and
`w2_commit_row_candidate_blocked_by_no_source_trace=0`.

## Deferred Owners

- Drive resident W2 RID instruction metadata through the read-only ROB
  commit-trace lookup before expecting this snapshot to observe row-fill
  readiness.
- Broader replay-return workload proof that observes nonzero live W2 side
  effects, row fill, and replay-row lifecycle mutation under QEMU/DUT compare.

## Verification

Focused gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2AtomicPrereqSnapshot
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2AtomicRequestGate
bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop
BUILD_DIR=generated/r533-replay-w2-prereq-snapshot-top-xcheck bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh
BUILD_DIR=generated/r534-replay-w2-atomic-live-mode-xcheck bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh
git diff --check
```

Reference tests cover same-identity snapshot reuse, identity mismatch, flush
clear, invalid resident identity suppression, ordered missing-prerequisite
blockers, and Chisel elaboration of the registered snapshot state.

The R533 generated-RTL manifest at
`generated/r533-replay-w2-prereq-snapshot-top-xcheck/report/crosscheck_manifest.json`
records `status: "pass"`, `comparator_status: 0`, three compared rows, zero
mismatches, and zero QEMU/DUT CBSTOP rows.
