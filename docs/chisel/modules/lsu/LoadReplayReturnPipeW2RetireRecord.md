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

## Deferred Owners

- A downstream replay-row lifecycle consumer that uses the retire record instead
  of sampling the physical W2 slot after clear.
- Promotion from diagnostic lifecycle match to a gated live clear/consume path
  that preserves atomic side-effect, row-fill, ROB resolve, and LIQ clear
  ordering.

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
