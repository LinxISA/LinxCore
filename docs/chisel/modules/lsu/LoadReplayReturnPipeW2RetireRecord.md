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

## Deferred Owners

- Promotion from diagnostic lifecycle match to a gated live clear/consume path
  that preserves atomic side-effect, row-fill, ROB resolve, and LIQ clear
  ordering.
- Retained-record atomic request source alignment before the record may drive a
  live row-fill/clear path.
- Retained-record row-fill enable and mutation ordering after the retained
  commit-row candidate is proven with resident W2-slot payload identity.

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
