# LoadReplayReturnPipeW2AtomicRequestEnablePolicy

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2AtomicRequestEnablePolicy.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/LoadReplayReturnPipeW2AtomicRequestEnablePolicySpec.scala`
- Integrated user: `rtl/LinxCore/chisel/src/main/scala/linxcore/top/LinxCoreFrontendFetchRfAluTraceTop.scala` through `LoadReplayReturnPipeW2AtomicRequestGate`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/iex/pipe/lda_pipe.cpp`: `LDAPipe::runW2`, `LDAPipe::move`
  - `model/LinxCoreModel/model/iex/pipe/agu_pipe.cpp`: `AGUPipe::runW2`, `AGUPipe::move`
  - `model/LinxCoreModel/model/lsu/load_unit/ldq.cpp`: `LDQInfo::returnData`
  - `model/LinxCoreModel/model/iex/iex.cpp`: `IEX::setMemData`
  - `model/LinxCoreModel/model/pe/PECommon/PROBCommon.cpp`: `ROBState::resolveData`
- Related Chisel contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2AtomicLiveRequestControl.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2SideEffectIssuePermit.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2ClearCommitGuard.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2CommitRowCandidate.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2ReplayRowLifecycleReady.scala`
- Contract IDs: `LC-CHISEL-LSU-REPLAY-PIPE-W2-ATOMIC-REQUEST-ENABLE-POLICY-001`

## Purpose

`LoadReplayReturnPipeW2AtomicRequestEnablePolicy` names the pre-request policy
for the future live `LoadReplayReturnPipeW2AtomicLiveRequestControl.requestEnable`
gate. LinxCoreModel treats returned-load W2 as a single resident-instruction
boundary: returned data can publish RF writeback, PE/ROB resolve, scalar wakeup,
resident clear, and W1-to-W2 refill before the pipe moves. The Chisel reduced
top has these owners, but `requestEnable` remains tied false.

This policy records the evidence that must exist before a future integration
may raise that request:

- resident W2 evidence exists, or W2 is empty and a refill candidate exists;
- required side-effect sinks are ready before live side-effect issue;
- resident clear/commit identity has been proven;
- a complete commit row candidate is available;
- the replay LIQ lifecycle row has been uniquely identified.

The module intentionally uses pre-request inputs. It does not depend on
post-request `sideEffectFireComplete`, `liveClearReady`, `lifecycleReady`, or
`rowFillEnable`, because those signals are consequences of the atomic request
and would form a circular enable condition.

## Interface

| Direction | Signal | Description |
|---|---|---|
| input | `enable` / `flush` | Replay-LIQ integration arm and flush suppression. |
| input | `slotOccupied` | W2 has a resident returned-load instruction. |
| input | `sideEffectCandidateValid` | W2 completion classifier has side-effect evidence. |
| input | `sideEffectRequiredMask` | `{wakeupRequired, writebackRequired, resolveRequired}` mask. |
| input | `sideEffectSinksReady` | All required side-effect sinks are ready for a live issue. |
| input | `clearIntent` | Resident W2 instruction is intended to clear. |
| input | `clearCommitReady` | Clear/resolve/ROB-complete identity evidence is coherent. |
| input | `rowFillCandidateValid` | Replay commit-row candidate is fully shaped. |
| input | `lifecycleRowClearReady` | Exactly one resolved LIQ row matches the resident W2 instruction. |
| input | `writeCandidateValid` | W1-to-W2 refill candidate exists. |
| output | `residentRequestEnableCandidate` | Future request-enable candidate for an occupied W2 slot. |
| output | `emptyRefillRequestEnableCandidate` | Future request-enable candidate for refill into an empty W2 slot. |
| output | `requestEnableCandidate` | Combined policy candidate. R530 wires this only through `LoadReplayReturnPipeW2AtomicRequestGate`; the reduced top still keeps policy prerequisites dormant. |
| output | blocker signals | Disabled, flush, no evidence, missing sink readiness, missing clear commit, missing row fill, and missing lifecycle-row diagnostics. |
| output | invalid-input signals | Side-effect, clear, and row-fill evidence observed without a resident W2 slot, plus occupied-slot side-effect evidence with an empty required-sink mask. |

## Logic Design

```text
active = enable && !flush
sideEffectEvidence =
  sideEffectCandidateValid && sideEffectRequiredMask != 0

residentEvidence =
  slotOccupied &&
  (sideEffectEvidence || clearIntent || rowFillCandidateValid || writeCandidateValid)

emptyRefillEvidence = !slotOccupied && writeCandidateValid

sideEffectPrerequisitesReady =
  !sideEffectEvidence || sideEffectSinksReady

residentCommitPrerequisitesReady =
  clearCommitReady &&
  rowFillCandidateValid &&
  lifecycleRowClearReady

residentRequestEnableCandidate =
  active &&
  residentEvidence &&
  sideEffectPrerequisitesReady &&
  residentCommitPrerequisitesReady

emptyRefillRequestEnableCandidate =
  active && emptyRefillEvidence
```

The resident arm is deliberately stricter than the empty-refill arm. An occupied
W2 slot may only request the atomic live boundary when clear identity, commit-row
replacement, and LIQ lifecycle evidence are all present. An empty W2 slot can
request refill without those resident-commit prerequisites.

R528 adds explicit malformed-resident diagnostics for the future top integration
packet. `sideEffectCandidateValid`, `clearIntent`, and `rowFillCandidateValid`
are resident-slot evidence, so observing them while `slotOccupied=false` is
reported separately from a valid empty W2 refill. An occupied side-effect
candidate with `sideEffectRequiredMask=0` and no other resident evidence is also
reported separately, so top wiring can distinguish a missing side-effect mask
from a true idle slot.

## Integration

R530 integrates this module as the policy child of
`LoadReplayReturnPipeW2AtomicRequestGate` in
`LinxCoreFrontendFetchRfAluTraceTop`. The top still passes
`liveModeEnable=false.B`, and it ties the policy prerequisites false until
their producers are proven pre-request. This preserves the R363 dormant live
path while replacing the direct atomic live-request instance with the composite
request-gate boundary.

The first R530 top wiring attempt fed current top clear, side-effect readiness,
row-fill, and lifecycle signals into this policy and failed elaboration with
FIRRTL combinational cycles. Those signals currently include same-cycle effects
of the atomic request path, so the integrated top now keeps them disconnected
from policy and feeds only raw request evidence into the live-request child.
R533 reconnects the prerequisite path only through
`LoadReplayReturnPipeW2AtomicPrereqSnapshot`, a registered same-identity W2
snapshot. Future top-level packets may connect additional policy prerequisites
only if they can prove the following wiring does not create a combinational
loop:

- side-effect readiness comes from pre-request sink readiness, not fire payloads;
- clear readiness comes from identity/commit evidence, not `liveClearReady`;
- lifecycle readiness comes from `rowClearReady`, not `lifecycleReady`;
- row-fill evidence comes from `rowFillCandidateValid`, not `rowFillEnable`.

## Deferred Owners

- Live-mode replacement of the R363 `requestEnable=false` tie-off.
- Multi-return-pipe arbitration when multiple returned-load W2 pipes exist.
- Verilator replay-return evidence after the live request gate is integrated.

## Verification

Focused gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2AtomicRequestEnablePolicy
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2AtomicLiveRequestControl
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2RowFillEnableControl
git diff --check
```

Reference tests cover the fully armed resident request candidate, empty W2
refill candidate, ordered resident blockers, disabled/flush/no-evidence
diagnostics, malformed resident evidence on an empty W2 slot, occupied
side-effect candidates without a required sink, and Chisel elaboration.
