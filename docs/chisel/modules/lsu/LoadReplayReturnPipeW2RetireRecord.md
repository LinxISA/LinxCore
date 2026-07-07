# LoadReplayReturnPipeW2RetireRecord

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2RetireRecord.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/LoadReplayReturnPipeW2RetireRecordSpec.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/iex/pipe/lda_pipe.cpp`
    - `LDAPipe::runW2`, `LDAPipe::move`
  - `model/LinxCoreModel/model/iex/pipe/agu_pipe.cpp`
    - `AGUPipe::runW2`, `AGUPipe::move`
  - `model/LinxCoreModel/model/iex/iex.cpp`
    - `IEX::receiveFromLSU`, `IEX::setMemData`
- Related Chisel contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2Slot.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2CompletionCandidate.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2ClearIntent.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnLretSink.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2PostLretEnqueueHold.scala`
- Contract IDs: `LC-CHISEL-LSU-REPLAY-PIPE-W2-RETIRE-RECORD-001`

## Purpose

`LoadReplayReturnPipeW2RetireRecord` is the model-aligned alternative to the
R575 diagnostic W2 hold. Instead of keeping the physical W2 slot resident after
side effects fire, it captures a one-entry retire record when live W2 clear is
accepted. That gives downstream replay-row lifecycle or registered LRET-drain
logic a stable consumed-row identity even if `LoadReplayReturnPipeW2Slot` clears
promptly.

In LinxCoreModel, `LDAPipe::runW2` and `AGUPipe::runW2` perform W2 side effects
before `move()` overwrites W2 from W1. The architectural consumed-row evidence
therefore needs to survive the stage transition, but it does not require the
physical W2 slot itself to remain occupied. R575 proved a hold is comparator
safe; this module starts the cleaner design path where W2 retire identity is a
separate record.

## Interface

| Direction | Signal | Description |
|---|---|---|
| input | `enable` | Replay-LIQ wrapper is active. |
| input | `flush` | Clears pending retire record and suppresses capture/fire pulses. |
| input | `slotOccupied` | W2 slot is resident. |
| input | `completionClearSlot` | W2 completion candidate requests slot clear. |
| input | `clearIntent` | W2 clear-intent owner has matching pre-clear, permit, and post-fire evidence. |
| input | `liveClear` | Live W2 clear is armed. |
| input | `lretEnqueueAccepted` | Same-cycle accepted LRET enqueue marker, retained as provenance. |
| input | `retirePayload` | Full returned-load payload to retain for downstream retire or drain matching. |
| input | `recordReady` | Downstream consumer can take the pending retire record. |
| output | `captureCandidate` | Live W2 clear has the structural predicates needed to capture a retire record. |
| output | `payloadValid` | Capture candidate has a valid payload. |
| output | `captureValid` | Candidate and payload are both valid. |
| output | `captureReady` | One-entry record is empty or is being consumed in the same cycle. |
| output | `captureAccepted` | Retire record captures this cycle. |
| output | `captureDropped` | Valid capture was blocked by a full record. |
| output | `recordValid` / `record` | Pending retained payload. |
| output | `recordFire` | Pending record was consumed. |
| output | `pending` / `count` | One-entry occupancy diagnostics. |
| output | `capturedWithLretEnqueue` | Capture accepted in the same cycle as LRET enqueue acceptance. |
| output | `recordFromLretEnqueue` | Pending record was captured with the same-cycle LRET enqueue marker. |
| output | blocker signals | Disabled, flush, no-slot, missing clear evidence, invalid payload, and full-record blockers. |

## Logic Design

```text
captureCandidate =
  enable && !flush &&
  slotOccupied &&
  completionClearSlot &&
  clearIntent &&
  liveClear

captureValid = captureCandidate && retirePayload.valid
recordFire = recordValid && recordReady && !flush
captureReady = !recordValid || recordFire
captureAccepted = captureValid && captureReady
```

On capture, the module stores `retirePayload` and whether capture overlapped an
accepted LRET enqueue. A same-cycle consume/recapture is allowed so a future
consumer can stream one record per cycle when it is ready. Flush clears the
record without emitting `recordFire`.

## Integration Status

R576 introduced this owner as a standalone unit-tested module. R577 wires it
diagnostically next to `LoadReplayReturnPipeW2ClearIntent` in
`LinxCoreFrontendFetchRfAluTraceTop` and feeds `retirePayload` from the same
returned-load payload used for LRET enqueue formation. The diagnostic top ties
`recordReady` high, exposes capture/full/provenance counters, and deliberately
does not change W2 clear, LRET drain, or replay-row lifecycle mutation.

The R577 generated RTL/QEMU replay-LIQ gate proves the record captures the same
three accepted LRET enqueue overlaps that R574/R575 classified, while the
physical W2 slot still clears promptly without the R575 hold knob:

```text
generated/r577-replay-w2-retire-record-xcheck/report/crosscheck_manifest.json
status=pass compared_rows=18 mismatch_count=0 qemu_cbstop=0 dut_cbstop=0

frontend_fetch_rf_alu_sideband_stats.json schema=v28
lret_sink_enqueue_accepted_w2_occupied=3
lret_sink_enqueue_accepted_w2_live_clear=3
lret_sink_followup_w2_cleared=3
lret_sink_followup_w2_still_occupied=0
w2_retire_record_capture_accepted=3
w2_retire_record_capture_accepted_w2_occupied=3
w2_retire_record_record_fire=3
w2_retire_record_captured_with_lret_enqueue=3
w2_retire_record_record_from_lret_enqueue=3
w2_retire_record_capture_dropped=0
w2_retire_record_blocked_by_full=0
```

R578 adds the first downstream diagnostic consumer by feeding
`retireRecord.io.record` into a second `LoadReplayReturnPipeW2ReplayRowLifecycleReady`
instance. The consumer keeps lifecycle clear disabled and only exposes whether
the retained record would match exactly one resolved LIQ row. The generated
RTL/QEMU gate at
`generated/r578-replay-w2-retire-record-lifecycle-xcheck` passes with 18
compared rows and zero mismatches. The v29 sideband report records
`w2_retire_record_lifecycle_resolved_row_match=3`,
`w2_retire_record_lifecycle_row_clear_ready=3`,
`w2_retire_record_lifecycle_blocked_by_no_resolved_row=0`, and
`w2_retire_record_lifecycle_blocked_by_multiple_resolved_rows=0`.

R579 makes that lifecycle matcher the retire-record consume owner by driving
`recordReady` from the diagnostic instance's `rowClearReady` instead of tying
it high. This keeps LIQ mutation disabled, but it proves the retained record is
only consumed when exactly one resolved replay row matches the record identity.
The generated RTL/QEMU gate at
`generated/r579-replay-w2-retire-record-ready-xcheck` passes with 18 compared
rows and zero mismatches. The v30 sideband report records
`w2_retire_record_record_ready=3`, `w2_retire_record_record_fire=3`,
`w2_retire_record_lifecycle_resolved_row_match=3`,
`w2_retire_record_lifecycle_row_clear_ready=3`,
`w2_retire_record_lifecycle_blocked_by_no_resolved_row=0`, and
`w2_retire_record_lifecycle_blocked_by_multiple_resolved_rows=0`.

R581 wires the retained-record lifecycle request probe into the generated top
after splitting W2 module construction out of the large top constructor. The
probe observes the consumed retained record, its unique lifecycle row, and the
existing physical-W2 atomic request, commit-row candidate, and row-fill enable.
It remains diagnostic only. The generated RTL/QEMU gate at
`generated/r581-replay-w2-retire-record-request-probe-xcheck` passes with 18
compared rows and zero mismatches. Sideband schema v31 records three retained
request candidates, zero missing lifecycle-row blockers, zero row-fill
candidate blockers, zero row-fill-enable blockers, zero live-promotion
candidates, and three `blocked_by_no_atomic_request` events. The next owner is
therefore atomic-request alignment for the retained record, not lifecycle-row
matching.

R582 splits that next owner again: if the retained record itself is treated as
request evidence, the existing physical-W2 row-fill candidate still does not
align with the retained record. The v32 sideband records
`w2_retire_record_atomic_request_evidence_valid=3`,
`w2_retire_record_atomic_request_blocked_by_no_lifecycle_row=0`, and
`w2_retire_record_atomic_request_blocked_by_no_row_fill_candidate=3`. The next
owner is a retained-record commit-row or row-fill candidate source.

R585 changes the integrated retire payload source from the live LRET enqueue
row to the resident W2 slot fields at the same live-clear boundary that accepts
the retire record. This keeps the retained identity aligned with the W2
metadata provider under delayed W2 completion while leaving the LRET FIFO
payload path untouched. The generated RTL/QEMU gate
`generated/r585-replay-retire-record-payload-source-latch-hold-xcheck` passes
with 18 compared rows and zero mismatches. Sideband schema v34 records
`w2_retire_record_commit_row_fill_candidate=145`,
`w2_retire_record_commit_row_candidate_blocked_by_no_metadata=0`, and
`w2_retire_record_commit_row_candidate_blocked_by_row_fill_disabled=145`.

R586 replaces the retained-record lifecycle re-query with
`LoadReplayReturnPipeW2RetireRecordLifecycleEvidenceLatch`. The latch captures
the physical W2 lifecycle row-clear evidence at the same boundary as
`captureAccepted`, then provides that evidence as the retained record's
`recordReady` source. This preserves prompt physical W2 lifecycle clear while
letting the retained record carry the row identity it consumed. The generated
RTL/QEMU gate `generated/r586-replay-retire-record-lifecycle-evidence-xcheck`
passes with 18 compared rows and zero mismatches. Sideband schema v36 records
`w2_retire_record_lifecycle_evidence_capture_from_lifecycle=5`,
`w2_retire_record_lifecycle_evidence_provider_valid=5`,
`w2_retire_record_lifecycle_evidence_capture_blocked_by_no_lifecycle=0`,
`w2_retire_record_row_fill_enable=5`,
`w2_retire_record_commit_row_complete_candidate=5`, and
`w2_retire_record_lifecycle_request_live_promotion_candidate=5`.

R587 adds `LoadReplayReturnPipeW2RetireRecordRobCompleteFallbackGuard` as the
next retained-record boundary. The guard observes the retained complete-row
candidate and the existing physical W2 ROB completion source, then classifies
whether the retained row would duplicate a physical ROB completion for the same
captured RID. The generated RTL/QEMU gate
`generated/r587-replay-retire-record-rob-fallback-guard-xcheck` passes with 18
compared rows and zero mismatches. Sideband schema v37 records
`w2_retire_record_rob_fallback_capture_physical_complete=5`,
`w2_retire_record_rob_fallback_candidate=5`,
`w2_retire_record_rob_fallback_duplicate_physical_complete=5`, and
`w2_retire_record_rob_fallback_complete_valid=0`. This proves the current
fixture already completes those retained RIDs through the physical W2 path, so
the next owner is a no-physical-complete fallback stimulus or RF/LIQ clear
alignment, not direct retained ROB completion.

R588 adds `LoadReplayReturnPipeW2RetireRecordRfWritebackFallbackGuard` for the
same retained record. The guard compares the retained scalar GPR destination
and data against the physical W2 writeback fire payload captured for the same
RID. The generated RTL/QEMU gate
`generated/r588-replay-retire-record-rf-fallback-guard-xcheck` passes with 18
compared rows and zero mismatches. Sideband schema v38 records
`w2_retire_record_rf_fallback_capture_physical_writeback=5`,
`w2_retire_record_rf_fallback_candidate=5`,
`w2_retire_record_rf_fallback_duplicate_physical_writeback=5`, and
`w2_retire_record_rf_fallback_writeback_valid=0`. Retained RF writeback is
therefore also a duplicate in the current fixture; the next owner is retained
LIQ clear or wakeup duplicate classification before any retained side effect is
enabled.

R589 adds `LoadReplayReturnPipeW2RetireRecordWakeupFallbackGuard` for the
remaining returned-load wakeup side effect. The guard mirrors the model
`LDQInfo::returnData` to `IEX::setMemWakeup` condition by considering retained
wakeup valid only when the retained record is non-speculative, non-stack, and
has a real destination. It then compares retained RID, reduced-GPR wakeup
class, and destination physical tag against the physical W2 wakeup fire payload.
The generated RTL/QEMU gate
`generated/r589-replay-retire-record-wakeup-fallback-guard-xcheck` passes with
18 compared rows and zero mismatches. Sideband schema v39 records
`w2_retire_record_wakeup_fallback_capture_physical_wakeup=5`,
`w2_retire_record_wakeup_fallback_candidate=5`,
`w2_retire_record_wakeup_fallback_duplicate_physical_wakeup=5`, and
`w2_retire_record_wakeup_fallback_wakeup_valid=0`. Retained wakeup and LIQ
lifecycle clear must remain disabled until a no-physical-wakeup stimulus or a
model-derived retained side-effect ordering proof exists.

R590 adds `LoadReplayReturnPipeW2RetireRecordLifecycleClearFallbackGuard` for
the retained replay-LIQ clear mutation. The guard mirrors the model split where
`LDQInfo::returnData` resolves and returns data, while `LDQInfo::retire` clears
resolved load rows later by commit order. It latches whether the physical W2
lifecycle clear accepted the captured row-clear index and suppresses retained
fallback clear for the same retained index. The generated RTL/QEMU gate
`generated/r590-replay-retire-record-lifecycle-clear-fallback-guard-xcheck`
passes with 18 compared rows and zero mismatches. Sideband schema v40 records
`w2_retire_record_lifecycle_clear_fallback_capture_physical_clear=5`,
`w2_retire_record_lifecycle_clear_fallback_candidate=5`,
`w2_retire_record_lifecycle_clear_fallback_duplicate_physical_clear=5`, and
`w2_retire_record_lifecycle_clear_fallback_clear_valid=0`. Retained lifecycle
clear must remain disabled until a no-physical-clear stimulus exists.

R591 adds `LoadReplayReturnPipeW2RetireRecordFallbackOwnerPolicy` as the single
retained side-effect enable owner. The policy consumes the retained ROB, RF,
wakeup, and lifecycle-clear guard candidates plus their duplicate-physical
classifications, then enables the four fallback guards only when all candidates
are present and no physical W2 side-effect already owns the same returned load.
The top ties the global policy arm low, so R591 preserves R590 behavior while
making later no-physical-side-effect work a single ownership decision instead of
four independent fallback enables.

R592 adds a default-off retained-owner no-physical probe for the reduced replay
LIQ top. `LINXCORE_REPLAY_LIQ_RETAINED_OWNER_NO_PHYSICAL_PROBE=1` masks the
duplicate-physical classifications only into the owner policy. The physical W2
side-effect paths and the individual duplicate guards remain active, so the
generated RTL/QEMU comparator still passes with 18 compared rows and zero
mismatches. Sideband schema v42 records the policy transition:
`w2_retire_record_fallback_owner_policy_blocked_by_physical_duplicate=0`,
`w2_retire_record_fallback_owner_policy_retained_sole_owner_eligible=5`,
`w2_retire_record_fallback_owner_policy_blocked_by_global_fallback_disabled=5`,
and `w2_retire_record_fallback_owner_policy_side_effect_enable=0`, while the
four individual physical duplicate guards still record `5`.

R593 adds `LINXCORE_REPLAY_LIQ_RETAINED_OWNER_FALLBACK_EMIT_PROBE=1` as a
stronger diagnostic-only probe. It masks physical observations at the individual
guard inputs, raises the policy global arm for the probe, and proves that all
four retained fallback outputs can emit without changing the architectural
QEMU/DUT comparison. The generated RTL/QEMU comparator at
`generated/r593-replay-retained-owner-fallback-emit-probe-xcheck` passes with
18 compared rows, zero mismatches, and zero QEMU/DUT CBSTOP rows. Sideband
schema v43 records `w2_retire_record_fallback_owner_policy_side_effect_enable=5`,
`w2_retire_record_rob_fallback_complete_valid=5`,
`w2_retire_record_rf_fallback_writeback_valid=5`,
`w2_retire_record_wakeup_fallback_wakeup_valid=5`, and
`w2_retire_record_lifecycle_clear_fallback_clear_valid=5`, while the four
duplicate-physical guard outputs are zero because the probe masked those inputs.
These fallback outputs remain sideband-only in the top.

R594 adds `LINXCORE_REPLAY_LIQ_RETAINED_OWNER_FALLBACK_LIVE_PROBE=1` as a
default-off reduced-top live-probe for the same retained fallback payloads. It
routes the retained ROB, RF, wakeup, and lifecycle-clear fallback outputs into
the existing reduced live sink boundaries while preserving the default physical
W2 path when the knob is off. The generated RTL/QEMU comparator at
`generated/r594-replay-retained-owner-fallback-live-probe-xcheck` passes with
18 compared rows, zero mismatches, and zero QEMU/DUT CBSTOP rows. Sideband
schema v44 records `w2_retire_record_fallback_owner_policy_side_effect_enable=5`,
`w2_retire_record_rob_fallback_live_complete_selected=5`,
`w2_retire_record_rf_fallback_live_writeback_selected=5`,
`w2_retire_record_wakeup_fallback_live_wakeup_selected=5`, and
`w2_retire_record_lifecycle_clear_fallback_live_clear_selected=5`. The live
clear-selected counter is tied to the retained lifecycle evidence latch's
`clearAccepted` proof. This is live-mutation evidence for the reduced diagnostic
probe, not a default-path retained-owner promotion.

## Deferred Owners

- Promotion from diagnostic lifecycle match to a gated live clear/consume path
  that preserves atomic side-effect, row-fill, ROB resolve, and LIQ clear
  ordering.
- Retained-record atomic request source alignment before the record may drive a
  live row-fill/clear path.
- Live retained-record row-fill mutation ordering after R586 proved retained
  lifecycle evidence, row-fill enable, atomic request alignment, and complete
  row candidates with zero diagnostic blockers.
- Retained side-effect fallback promotion after R591 proves a no-physical
  stimulus. R592 proves the owner-policy no-duplicate branch with a boundary
  probe, R593 proves sideband-only fallback output emission, and R594 proves
  reduced-top live-probe selection under an explicit diagnostic knob. The
  default retained-owner arm must still stay off until a real no-physical
  side-effect path or equivalent model-derived ordering proof exists.

## Verification

Focused gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2RetireRecord
```

Reference tests cover empty capture, live-clear blocking, invalid payload,
full-record drop, same-cycle consume/recapture, and Chisel elaboration.

R577 integration gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop
LINXCORE_REPLAY_LIQ_EARLY_STA_ADDRESS=1 \
LINXCORE_REPLAY_LIQ_W2_COMPLETION_DELAY_CYCLES=12 \
FETCH_REPLAY_LIQ_REQUIRE_NONZERO=wait_replay_capture_accepted,replay_queue_out_fire,liq_alloc_accepted,lret_w2_slot_accepted,w2_promotion_live \
bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh \
  --fixture replay-ldi-sdi-ldi-sdi-ldi-ldi-loop \
  --build-dir generated/r577-replay-w2-retire-record-xcheck \
  --expected-rows 18 --capture-rows 32 --max-seconds 10 \
  --reduced-store-replay-liq --disable-store-memory-mutation \
  --allow-residual-replay-liq-wait
```

R578 lifecycle-match diagnostic gate:

```bash
LINXCORE_REPLAY_LIQ_EARLY_STA_ADDRESS=1 \
LINXCORE_REPLAY_LIQ_W2_COMPLETION_DELAY_CYCLES=12 \
FETCH_REPLAY_LIQ_REQUIRE_NONZERO=wait_replay_capture_accepted,replay_queue_out_fire,liq_alloc_accepted,lret_w2_slot_accepted,w2_promotion_live \
bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh \
  --fixture replay-ldi-sdi-ldi-sdi-ldi-ldi-loop \
  --build-dir generated/r578-replay-w2-retire-record-lifecycle-xcheck \
  --expected-rows 18 --capture-rows 32 --max-seconds 10 \
  --reduced-store-replay-liq --disable-store-memory-mutation \
  --allow-residual-replay-liq-wait
```

R579 lifecycle-ready consume diagnostic gate:

```bash
LINXCORE_REPLAY_LIQ_EARLY_STA_ADDRESS=1 \
LINXCORE_REPLAY_LIQ_W2_COMPLETION_DELAY_CYCLES=12 \
FETCH_REPLAY_LIQ_REQUIRE_NONZERO=wait_replay_capture_accepted,replay_queue_out_fire,liq_alloc_accepted,lret_w2_slot_accepted,w2_promotion_live \
bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh \
  --fixture replay-ldi-sdi-ldi-sdi-ldi-ldi-loop \
  --build-dir generated/r579-replay-w2-retire-record-ready-xcheck \
  --expected-rows 18 --capture-rows 32 --max-seconds 10 \
  --reduced-store-replay-liq --disable-store-memory-mutation \
  --allow-residual-replay-liq-wait
```

R581 retained-record lifecycle request probe gate uses the same fixture and
builds `generated/r581-replay-w2-retire-record-request-probe-xcheck`.
