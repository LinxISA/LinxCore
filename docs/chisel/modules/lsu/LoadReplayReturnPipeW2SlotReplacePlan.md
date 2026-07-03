# LoadReplayReturnPipeW2SlotReplacePlan

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2SlotReplacePlan.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/LoadReplayReturnPipeW2SlotReplacePlanSpec.scala`
- Integrated user: `rtl/LinxCore/chisel/src/main/scala/linxcore/top/LinxCoreFrontendFetchRfAluTraceTop.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/iex/pipe/lda_pipe.cpp`
    - `LDAPipe::Work`, `LDAPipe::runW2`, `LDAPipe::move`
  - `model/LinxCoreModel/model/iex/pipe/agu_pipe.cpp`
    - `AGUPipe::Work`, `AGUPipe::runW2`, `AGUPipe::move`
- Related Chisel contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2Slot.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2ClearIntent.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2RefillReady.scala`
- Contract IDs: `LC-CHISEL-LSU-REPLAY-PIPE-W2-SLOT-REPLACE-PLAN-001`

## Purpose

`LoadReplayReturnPipeW2SlotReplacePlan` names the storage-side plan needed
after R351 clear intent and R352 refill readiness. LinxCoreModel `LDAPipe` and
`AGUPipe` call `runW2()` before `move()`, and `move()` assigns
`w2_inst = w1_inst`. That means the old W2 entry can publish side effects and
the next W1 entry can become W2 in the same model cycle.

The current Chisel `LoadReplayReturnPipeW2Slot` still gives `clear` priority
over same-cycle writes and accepts writes only when W2 was already empty. This
module does not change that storage. It observes the current slot acceptance
and reports where a future model-compatible storage owner would accept a write
from W1 because W2 is either empty or being live-cleared in the same cycle.

## Interface

| Direction | Signal | Description |
|---|---|---|
| input | `enable` | Replay-LIQ wrapper is active. |
| input | `flush` | Suppresses replacement planning during replay flush. |
| input | `slotOccupied` | R333 W2 slot currently contains a resident entry. |
| input | `writeValid` | W1-to-W2 write candidate from `LoadReplayReturnPipeW1AdvanceCandidate`. |
| input | `writeTargetIsAgu` / `writeTargetIsLda` | Mutually exclusive target bits for the incoming W1 payload. |
| input | `clearIntent` | R351 future clear intent for the resident W2 entry. |
| input | `liveClear` | R351 live clear pulse. Current top keeps this low. |
| input | `futureAdvanceReady` | R352 future W1-to-W2 readiness, `empty || sameCycleLiveClear`. |
| input | `currentSlotAccepted` | Current `LoadReplayReturnPipeW2Slot.accepted` output. |
| input | `currentSlotBlockedByClear` | Current W2 slot clear-priority blocker. |
| output | `active` | Enabled and not flushed. |
| output | `writeTargetValid` | Incoming write names exactly one AGU or LDA target. |
| output | `emptyWriteEligible` | Current empty-slot write case. |
| output | `sameCycleReplaceEligible` | W2 is occupied, a valid write is present, and clear intent exists. |
| output | `sameCycleReplaceReady` | Same-cycle replacement is eligible and live clear plus R352 future readiness agree. |
| output | `futureWriteAccept` | Future storage acceptance: empty write or same-cycle replacement. |
| output | `currentMatchesFutureWrite` | Current W2 slot acceptance matches `futureWriteAccept`. |
| output | blocker signals | Disabled, flush, no-write, invalid-target, occupied-without-clear, live-clear-disabled, current-storage, clear-priority, and invalid-evidence diagnostics. |

## Logic Design

```text
active = enable && !flush
writeTargetValid = writeTargetIsAgu XOR writeTargetIsLda
writeCandidate = active && writeValid

emptyWriteEligible = writeCandidate && !slotOccupied && writeTargetValid
sameCycleReplaceEligible =
  writeCandidate && slotOccupied && writeTargetValid && clearIntent
sameCycleReplaceReady =
  sameCycleReplaceEligible && liveClear && futureAdvanceReady

futureWriteAccept = emptyWriteEligible || sameCycleReplaceReady
currentMatchesFutureWrite = currentSlotAccepted == futureWriteAccept
```

When live clear remains disabled, the future acceptance predicate matches the
current slot behavior for all legal top-level states. Once a later packet
enables live clear, `blockedByCurrentStorage` and
`blockedByCurrentClearPriority` identify the exact cycle where the existing
storage priority would still reject the model-compatible same-cycle
replacement.

## Integration

`LinxCoreFrontendFetchRfAluTraceTop` wires this module after R352:

- write inputs come from the W1 advance candidate that currently feeds the W2
  slot;
- clear inputs come from `LoadReplayReturnPipeW2ClearIntent`;
- future readiness comes from `LoadReplayReturnPipeW2RefillReady`;
- current storage evidence comes from `LoadReplayReturnPipeW2Slot`;
- compact diagnostics are exposed under
  `reducedLoadReplayLiqLretPipeW2SlotReplacePlan*`.

The integration remains observational. It does not feed
`LoadReplayReturnPipeW1AdvanceCandidate.advanceEnable`, W2 slot `writeValid`,
W2 slot `clear`, W2 registered storage, W2 side effects, replay-row lifecycle,
or ROB/RF/ready-table mutation.

## Deferred Owners

- Change `LoadReplayReturnPipeW2Slot` storage semantics so live clear and a
  valid write can replace W2 in one cycle.
- Promote R352 `futureAdvanceReady` into the W1-to-W2 advance gate only after
  the storage owner can accept same-cycle replacement.
- Tie replay-row lifecycle retirement to the consumed W2 entry.

## Verification

Focused gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2SlotReplacePlan
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2RefillReady
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2ClearIntent
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2Slot
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW1AdvanceCandidate
bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop
FETCH_REDUCED_STORE_REPLAY_LIQ=1 BUILD_DIR=generated/r353-replay-pipe-w2-slot-replace-plan-xcheck bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh
```

Reference tests cover empty-slot write matching, occupied-slot blocking,
dormant same-cycle replacement eligibility, future live-clear replacement,
invalid targets, stray future readiness, disabled/flush suppression, current
acceptance mismatches, and Chisel elaboration.
