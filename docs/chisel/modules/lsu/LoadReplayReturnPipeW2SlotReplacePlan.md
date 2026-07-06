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
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2PromotionControl.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2AdvanceControl.scala`
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
R355 consumes the acyclic future acceptance outputs from this plan in
`LoadReplayReturnPipeW2AdvanceControl`; R356 owns the shared live-promotion
enable. R556 proves promotion, live clear, refill readiness, and advance
selection fire in the reduced replay-loop fixture. R557 then adds overlap
sideband counters and proves the current fixture does not present any W1 write
candidate while W2 is occupied and live clear fires, so same-cycle replacement
eligibility remains zero for stimulus reasons. R558 adds a denser
`replay-ldi-sdi-ldi-ldi-loop` fixture with a second younger load and proves it
as a generated-RTL/QEMU regression, but the added load still does not overlap
W1 with W2 live clear. R559 adds the repeated store/load dependency fixture
`replay-ldi-sdi-ldi-sdi-ldi-loop`; it doubles replay-return pressure and still
reports zero W1-candidate/W2-live-clear overlap, so the next stimulus candidate
must create true returned-load phasing or multiple outstanding returned loads,
not only denser same-address dependencies. R560 adds
`replay-ldi-sdi-ldi-ldi-ldi-ldi-loop`, a burst of consecutive younger loads
after one store dependency; it increases W2 occupancy but still records zero
overlap, so merely adding younger loads after one learned store dependency is
also insufficient. R561 adds sideband phase-distance counters and proves the
same fixture also has no one-cycle-near miss between W1 candidate and W2
clear/live-clear. R562 widens that phase sideband and shows W2 live clear trails
the W1 candidate by two cycles twice and by five-or-more cycles once in the
burst fixture; same-cycle, one-cycle, gap3/gap4, and reverse-gap evidence remain
zero. R563 adds W1/W2 slot identity sideband and proves those delayed W2 clears
all match the same load-LSID as the earlier W1 candidate, so the phase gap is
resident-row lifetime rather than hidden replacement stimulus. R564 splits
different-LSID near-misses by gap2/gap3/gap4/gap5+ in both directions and
records all of those buckets as zero on the same burst fixture, so there is no
different-row near-miss in the current sequence to rescue with storage tuning.
R565 applies the same buckets to the repeated dependency-chain fixture; it sees
six same-LSID phase gaps (`gap2=5`, `gap4=1`) and zero different-LSID buckets,
so both current dense fixtures are resident-row lifetime observations rather
than replacement candidates.
R566 adds a clustered second-dependency fixture with one extra younger load and
still records only same-LSID phase gaps (`gap2=4`, `gap4=2`) plus zero
same-cycle replacement, so more same-address pressure does not change the owner
boundary.
R567 extends the harness sideband report, not the slot-replace plan logic, with
same-cycle overlap identity buckets. A future fixture or phasing hook must make
`w2_slot_replace_overlap_live_clear_different_lsid` nonzero before the overlap
can be treated as replacement stimulus.

## Interface

| Direction | Signal | Description |
|---|---|---|
| input | `enable` | Replay-LIQ wrapper is active. |
| input | `flush` | Suppresses replacement planning during replay flush. |
| input | `slotOccupied` | R333 W2 slot currently contains a resident entry. |
| input | `writeValid` | W1-to-W2 write candidate from `LoadReplayReturnPipeW1AdvanceCandidate`. |
| input | `writeTargetIsAgu` / `writeTargetIsLda` | Mutually exclusive target bits for the incoming W1 payload. |
| input | `clearIntent` | R351 future clear intent for the resident W2 entry. |
| input | `liveClear` | R351 live clear pulse gated by `LoadReplayReturnPipeW2PromotionControl`; R556 observes this pulse in the replay-loop fixture. |
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
or ROB/RF/ready-table mutation directly. R355 is the sole owner that now
routes future readiness toward W1 advance and `replaceOnClear`; its
`livePromotionEnable` input comes from
`LoadReplayReturnPipeW2PromotionControl`. R556 observes the promotion path
live, but not a same-cycle replacement candidate, so the next packet must
separate fixture coverage from storage ownership before changing broad gates.
R557 records `live_clear_without_w1_candidate=3` and zero W1-candidate/W2-live
clear overlap in that same fixture. R558 records the same zero-overlap result
for the denser extra-load fixture while increasing replay traffic to a 12-row
QEMU/DUT pass. R559 records the same zero-overlap result for the repeated
store/load dependency fixture while increasing the pass to 15 compared rows and
six accepted W1/W2 return-pipe slots. R560 records the same zero-overlap result
for a burst-after-dependency fixture while passing 18 compared rows and
increasing resident W2 occupancy to 10 cycles. R561 adds phase-distance
counters and records zero W1-candidate-before-clear/live-clear and zero
clear/live-clear-before-W1-candidate events for that same burst fixture. R562
records nonzero W1-candidate to W2-live-clear gaps at 2 cycles and 5+ cycles,
with no reverse-gap or overlap evidence. R563 records
`same_lsid=3`, `different_lsid=0`, and `unknown_lsid=0` for those phase-gap
clears, proving they are not different-candidate replacement opportunities.

## Deferred Owners

- Create or select a replay-return sequence where W1 has a valid write
  candidate while W2 is occupied and live clear fires; R557 proves the current
  loop fixture lacks this overlap, and R558 proves that simply appending one
  more younger load is still insufficient. R559 proves repeating the
  same-address store/load dependency chain is also insufficient. R560 proves a
  burst of younger loads after one learned store dependency is still
  insufficient. R561 proves this fixture family is not merely missing the
  overlap by one adjacent cycle. R562 proves the useful phasing distance in the
  burst fixture is two cycles for two returns and five-or-more cycles for one
  return; next stimulus should shorten or retain across that gap before changing
  W2 storage only if it creates a different load-LSID candidate. R563 proves
  retaining the same LSID only observes normal W2 lifetime, not replacement.
  R564 proves the current burst fixture also has no different-LSID near-miss in
  either direction across the measured gap buckets, so the next packet should
  create a new different-LSID overlap stimulus rather than adjust W2 storage.
  R565 proves the older repeated dependency-chain fixture has the same
  same-LSID-only limitation under those buckets. R566 proves adding one extra
  younger load after the second store dependency also stays same-LSID-only.
  R567 adds same-cycle overlap identity counters so a future overlap cannot be
  confused with same-resident-row lifetime evidence.
- Verify `LoadReplayReturnPipeW2AdvanceControl` selects R352/R353 future
  readiness and drives `LoadReplayReturnPipeW2Slot.replaceOnClear` in that
  same-cycle replacement case.
- Tie replay-row lifecycle retirement to the consumed W2 entry.

## Verification

Focused gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2SlotReplacePlan
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2RefillReady
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2ClearIntent
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2PromotionControl
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2Slot
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW1AdvanceCandidate
bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop
FETCH_REDUCED_STORE_REPLAY_LIQ=1 BUILD_DIR=generated/r353-replay-pipe-w2-slot-replace-plan-xcheck bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh
```

Reference tests cover empty-slot write matching, occupied-slot blocking,
dormant same-cycle replacement eligibility, future live-clear replacement,
invalid targets, stray future readiness, disabled/flush suppression, current
acceptance mismatches, and Chisel elaboration.
