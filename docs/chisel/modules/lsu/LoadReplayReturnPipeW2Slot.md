# LoadReplayReturnPipeW2Slot

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2Slot.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/LoadReplayReturnPipeW2SlotSpec.scala`
- Integrated user: `rtl/LinxCore/chisel/src/main/scala/linxcore/top/LinxCoreFrontendFetchRfAluTraceTop.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/iex/pipe/lda_pipe.cpp`
    - `LDAPipe::move`
    - `LDAPipe::runW1`
    - `LDAPipe::runW2`
    - `LDAPipe::flush`
  - `model/LinxCoreModel/model/iex/pipe/agu_pipe.cpp`
    - `AGUPipe::move`
    - `AGUPipe::runW1`
    - `AGUPipe::runW2`
    - `AGUPipe::flush`
- Related Chisel contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW1Slot.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW1AdvanceCandidate.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2CompletionCandidate.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2SideEffectReady.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2ResolveSinkReady.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2WritebackSinkReady.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2PromotionControl.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayDestination.scala`
- Contract IDs: `LC-CHISEL-LSU-REPLAY-PIPE-W2-SLOT-001`

## Purpose

`LoadReplayReturnPipeW2Slot` is the dormant registered W2 stage owner for a
returned-load pipe payload. In LinxCoreModel LDA and AGU pipes, `move()`
assigns `w2_inst = w1_inst`; `runW1()` only stamps the W1 cycle, while
`runW2()` stamps the W2 cycle and owns RF writeback, resolve publication, and
local scalar wakeup.

R333 adds the W2 payload state boundary without enabling downstream W2 side
effects. R334 wires clear from `LoadReplayReturnPipeW2CompletionCandidate`,
and R335 feeds that completion from `LoadReplayReturnPipeW2SideEffectReady`
while R336 names the resolve sink, R337 names the writeback sink, and R338
names the wakeup sink; all stay live-disabled. R354 adds an explicit
`replaceOnClear` storage mode for the later model-compatible cycle where W2
side effects clear the old entry and W1 refills W2 in the same cycle. The
reduced top now drives that mode through R355
`LoadReplayReturnPipeW2AdvanceControl`, whose live-promotion input comes from
the R356 promotion-control owner. R356 still ties the external promotion
request false, so W1 advance readiness comes from W2 emptiness and
fixture-visible replay behavior stays unchanged.

R376 carries the registered W1 source-trace sideband into the W2 slot image.
This gives the future replay-load commit-row fill path a resident source-data
payload to consume without reconstructing operands from ROB allocation-time
metadata. The top still leaves the W2 commit-row trace-source provider disabled.

## Interface

| Direction | Signal | Description |
|---|---|---|
| input | `enable` | Replay-LIQ wrapper is active. |
| input | `flush` | Clears the W2 slot and suppresses same-cycle writes. |
| input | `clear` | Explicit lifecycle clear for a consumed W2 entry. Current top drives this from R334 W2 completion through the R335 W2 side-effect readiness join; R336 keeps resolve live-disabled, R337 keeps writeback live-disabled, and R338 keeps wakeup live-disabled. |
| input | `replaceOnClear` | Enables the future same-cycle clear/refill storage mode. Current top drives this from R355 `LoadReplayReturnPipeW2AdvanceControl`, whose R356 live-promotion input keeps it false. |
| input | `writeValid` | W1-to-W2 advance pulse from `LoadReplayReturnPipeW1AdvanceCandidate`. |
| input | `writeTargetIsAgu` / `writeTargetIsLda` | Mutually exclusive W2 pipe-family target. |
| input | `writePipeIndex` | Selected return-pipe index carried from the W1 slot. |
| input | `writeLoadToUsePipeIndex` | Original MemReqBus load-to-use pipe sideband. |
| input | `writeBid` / `writeGid` / `writeRid` / `writeLoadLsId` | Returned-load identity copied from the W1 slot. |
| input | `writePc` / `writeAddr` / `writeSize` / `writeData` | Returned-load scalar request/data fields. |
| input | `writeDst` | Reduced destination sideband. |
| input | `writeSourceTraceValid` / `writeSource0` / `writeSource1` | R376 source operand trace sideband from the W1 slot. |
| input | `writeWakeupRequired` | Future issue-wakeup sideband. |
| output | `accepted` | W2 slot accepted the incoming write, either as an empty-slot write or as a gated clear/refill replacement. |
| output | `occupied` / `entryValid` | Registered W2 entry is resident. |
| output | `entry*` | Registered target, pipe index, identity, request, destination, source trace, data, and wakeup sidebands. |
| output | `acceptedEmpty` | Write accepted into an empty slot with no same-cycle clear. |
| output | `replacedOnClear` | Write accepted while `clear` consumes the previous resident slot. |
| output | `blockedBy*` | Disabled, flush, clear, no-write, invalid-target, occupied-slot, and replace-disabled blockers. |

## Logic Design

The W2 slot is a one-entry registered stage:

```text
active = enable && !flush
targetValid = writeTargetIsAgu XOR writeTargetIsLda
writeCandidate = active && writeValid && targetValid

acceptedEmpty = writeCandidate && !clear && !occupied
replacedOnClear = writeCandidate && clear && replaceOnClear && occupied
accepted = acceptedEmpty || replacedOnClear

if flush:
  occupied = false
  entry = disabled payload
else if accepted:
  occupied = true
  entry = write payload
else if clear:
  occupied = false
  entry = disabled payload
else:
  entry holds state
```

Flush has priority over same-cycle writes. Clear also has priority while
`replaceOnClear=false`; when it is true, a clear plus valid resident slot and
valid exclusive write target replaces the old W2 payload with the incoming W1
payload. A write with neither or both target bits asserted is rejected,
preserving the invariant that each returned load belongs to exactly one LDA or
AGU W2 pipe target.

## Integration

`LinxCoreFrontendFetchRfAluTraceTop` wires this slot after R332:

- `writeValid` comes from W1 advance `advanceValid`;
- target-domain and selected pipe index come from the W1 advance candidate;
- payload sidebands come from the R331 W1 slot entry outputs;
- W1 advance `advanceEnable` is driven by R355
  `LoadReplayReturnPipeW2AdvanceControl`, which currently selects
  `!W2Slot.occupied`;
- `replaceOnClear` is driven by R355
  `LoadReplayReturnPipeW2AdvanceControl`, whose live-promotion input comes
  from R356 `LoadReplayReturnPipeW2PromotionControl` with the R363 request gate
  false;
- `clear` comes from `LoadReplayReturnPipeW2CompletionCandidate.clearSlot`,
  which remains false while the R335/R336/R337/R338 W2 side-effect readiness path
  keeps at least one required sink not-ready;
- top-level diagnostics expose accepted, occupied, target, pipe-index, PC,
  load-LSID identity, and blocker signals.

Because R328/R330 still keep upstream E4 residency and E4-to-W1 advance
disabled, this module does not change fixture-visible replay behavior. It
names the W2 payload owner required before real RF, ROB, ready-table,
issue-wakeup, or replay-row lifecycle side effects can consume returned loads.

R538 keeps the W2 slot policy unchanged and extends only the generated-RTL
sideband stats report. Schema v13 samples the existing top-level
`LretPipeResidency*`, `LretPipeW1*`, and `LretPipeW2Slot*` diagnostics as
`lret_residency_*`, `lret_w1_*`, and `lret_w2_slot_*` counters. These counters
are intended to classify the live replay-return blockage before promoting W2
side effects or clear/refill replacement.
The R538 replay loop still records upstream replay-LIQ progress, but the new
stage counters stay empty (`lret_residency_candidate_valid=0`,
`lret_w1_slot_accepted=0`, `lret_w2_slot_accepted=0`) while the W2 slot
reports `lret_w2_slot_blocked_by_no_write=108`. This keeps the next owner
upstream of W2 storage: returned-load/IEX pipe insertion and LRET residency
payload production.

R563 exposes the W2 slot PC and load-LSID identity through the reduced top.
The replay burst fixture then proves the R562 W1-to-W2-live-clear phase gaps
are the same resident load being cleared (`same_lsid=3`) rather than a different
returned-load candidate (`different_lsid=0`). W2 storage replacement remains
blocked until a different W1 candidate overlaps the resident W2 live clear.
R564 adds gap-bucketed different-LSID near-miss counters and records all of them
as zero on that fixture, so the current sequence cannot justify W2 storage
replacement changes.
R565 applies the same counters to `replay-ldi-sdi-ldi-sdi-ldi-loop` and again
records only same-LSID gaps, so the next owner must create a new different-LSID
returned-load overlap stimulus.
R566 adds one more younger load after the second store dependency and still
records only same-LSID gap2/gap4 clears with zero replacement eligibility.
R567 adds harness-only same-cycle overlap identity counters. The storage owner
must not treat a future `w2_slot_replace_overlap_candidate_live_clear` pulse as
replacement evidence unless the new different-LSID bucket is also nonzero.
R568 adds a top-level, default-off W2 completion-delay diagnostic hook and
proves that holding W2 resident for longer is still insufficient on the current
clustered dependency fixture: `lret_w2_slot_occupied=30`, but
`w2_slot_replace_overlap_candidate_live_clear=0`,
`w2_slot_replace_same_cycle_eligible=0`, and `w2_advance_replace_on_clear=0`.
This keeps `replaceOnClear` storage disabled until a separate stimulus proves a
different-LSID W1 candidate overlaps W2 live clear.
R569 extends that negative proof with delay-4 and delay-12 generated-RTL/QEMU
runs. Both manifests pass with `compared_rows=18`, `mismatch_count=0`, and zero
QEMU/DUT CBSTOP rows. Delay 12 raises W2 residency to
`lret_w2_slot_occupied=78`, but W1 still never blocks behind occupied W2
(`lret_w1_advance_blocked_by_advance_disabled=0`) and all same-cycle
replacement counters remain zero. The next owner should create denser upstream
return admission or E4 residency stimulus; changing `replaceOnClear` or W2
storage policy remains blocked.

## Deferred Owners

- W2 pipe-cycle timestamp storage.
- W2 writeback, resolve publication, ready-table update, issue wakeup, and
  replay-row retirement.
- Live W2 clear after all side effects are accepted.
- Enable the R356 promotion request so R355 can drive `replaceOnClear` from
  the R351/R352/R353 live clear/refill predicate.
- Live enable for upstream E4-to-W1 advance.
- Per-pipe first-free/multi-pipe W-stage occupancy.
- Precise pipe-stage flush by ROB/LSID identity rather than top-level replay
  flush only.

## Verification

Focused gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2Slot
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2CompletionCandidate
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2PromotionControl
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW1AdvanceCandidate
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW1Slot
bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop
FETCH_REDUCED_STORE_REPLAY_LIQ=1 BUILD_DIR=generated/r354-replay-pipe-w2-slot-replace-mode-xcheck bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh
```

Reference tests cover scalar LDA capture, vector AGU capture, occupied-slot
blocking, flush and clear priority, disabled/no-write/invalid-target blockers,
payload preservation, disabled replacement, same-cycle replacement when
`replaceOnClear` is enabled, flush priority over replacement, and Chisel
elaboration.
