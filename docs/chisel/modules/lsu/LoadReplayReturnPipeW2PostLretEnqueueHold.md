# LoadReplayReturnPipeW2PostLretEnqueueHold

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2PostLretEnqueueHold.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/LoadReplayReturnPipeW2PostLretEnqueueHoldSpec.scala`
- Integrated user: `rtl/LinxCore/chisel/src/main/scala/linxcore/top/LinxCoreFrontendFetchRfAluTraceTop.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/iex/pipe/lda_pipe.cpp`
    - `LDAPipe::runW2`, `LDAPipe::move`
  - `model/LinxCoreModel/model/iex/pipe/agu_pipe.cpp`
    - `AGUPipe::runW2`, `AGUPipe::move`
  - `model/LinxCoreModel/model/iex/iex.cpp`
    - `IEX::receiveFromLSU`
- Related Chisel contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/ScalarLSULoadReturnQueue.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2Slot.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2CompletionCandidate.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2ClearIntent.scala`
- Contract IDs: `LC-CHISEL-LSU-REPLAY-PIPE-W2-POST-LRET-HOLD-001`

## Purpose

`LoadReplayReturnPipeW2PostLretEnqueueHold` is a default-off diagnostic owner
for one narrow phasing experiment: when an accepted LRET enqueue overlaps a
live W2 completion clear, optionally suppress that same-cycle W2 slot clear and
release it later through a direct clear pulse.

The R574 evidence showed that every accepted LRET enqueue with W2 occupied
coincided with `completionClearSlot`, `clearIntent`, `sideEffectFireComplete`,
and `liveClear`, and that the next cycle saw W2 already cleared. The R575
experiment proves the opposite phasing is comparator-safe for the replay-loop
fixture when enabled with
`LINXCORE_REPLAY_LIQ_W2_POST_LRET_ENQUEUE_HOLD_CYCLES=1`: the same accepted
enqueue events have next-cycle W2 occupancy while the QEMU/DUT trace still
matches.

This module is not a final architectural retire policy. It is an isolated knob
for testing whether the replay return path can keep the consumed W2 row visible
across the registered LRET FIFO boundary without re-firing W2 side effects.

## Interface

| Direction | Signal | Description |
|---|---|---|
| parameter | `holdCycles` | Number of configured diagnostic hold cycles. `0` is transparent and emits no state. |
| input | `enable` | Replay-LIQ reduced wrapper is active. |
| input | `flush` | Cancels active hold state and suppresses hold start/release pulses. |
| input | `slotOccupied` | W2 slot is resident in the cycle being classified. |
| input | `completionClearSlot` | W2 completion candidate is requesting slot clear. |
| input | `liveClear` | W2 clear intent is live-enabled. |
| input | `lretEnqueueAccepted` | LRET sink accepted a returned-load enqueue in the same cycle. |
| output | `suppressCurrentClear` | Masks the same-cycle W2 slot clear that would otherwise consume the resident W2 row. |
| output | `releaseClear` | Emits the delayed direct W2 slot clear after the configured hold expires. |
| output | `completionReady` | Feeds the W2 completion readiness join; false while a held row is being retained or released. |
| output | `holdActive` | Registered hold state is active. |
| output | `holdStart` | Diagnostic pulse when a qualifying overlap starts a hold. |
| output | `holdRelease` | Diagnostic pulse when the held row is released. |

## Logic Design

```text
holdStart =
  holdCycles > 0 &&
  enable && !flush &&
  !holdActive &&
  slotOccupied &&
  completionClearSlot &&
  liveClear &&
  lretEnqueueAccepted

suppressCurrentClear = holdStart
completionReady = !holdActive
releaseClear = enable && !flush && holdActive && holdCount == 0
```

On `holdStart`, the top masks the current `LoadReplayReturnPipeW2Slot.clear`
input and loads the down counter. While held, `completionReady` is false so the
W2 side-effect completion path cannot re-fire the retained entry. When the
counter reaches zero, `releaseClear` clears the W2 slot directly. Flush or
disable clears the hold state without publishing a release pulse.

## Integration

`LinxCoreFrontendFetchRfAluTraceTop` wires the helper between the W2 completion
candidate and the W2 slot:

- `suppressCurrentClear` masks
  `LoadReplayReturnPipeW2CompletionCandidate.clearSlot`;
- `releaseClear` ORs into `LoadReplayReturnPipeW2Slot.clear`;
- `completionReady` gates the existing W2 completion delay readiness.

The reduced replay-LIQ top exposes the default-off environment knob
`LINXCORE_REPLAY_LIQ_W2_POST_LRET_ENQUEUE_HOLD_CYCLES`. With the default value
`0`, generated RTL is transparent except for constant-folded wiring.

## Deferred Owners

- Replace the diagnostic hold with a model-derived W2 retire policy once real
  replay side effects and replay-row lifecycle mutation are live.
- Decide whether final hardware should retain the W2 row until the registered
  LRET FIFO drain boundary or instead carry the needed row identity through a
  separate retire record.
- Add sideband counters for hold start/release if this knob remains useful
  beyond R575.

## Verification

Focused gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2PostLretEnqueueHold
bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop
LINXCORE_REPLAY_LIQ_EARLY_STA_ADDRESS=1 \
LINXCORE_REPLAY_LIQ_W2_COMPLETION_DELAY_CYCLES=12 \
LINXCORE_REPLAY_LIQ_W2_POST_LRET_ENQUEUE_HOLD_CYCLES=1 \
FETCH_REPLAY_LIQ_REQUIRE_NONZERO=wait_replay_capture_accepted,replay_queue_out_fire,liq_alloc_accepted,lret_w2_slot_accepted,w2_promotion_live \
bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh \
  --fixture replay-ldi-sdi-ldi-sdi-ldi-ldi-loop \
  --build-dir generated/r575-replay-lret-post-enqueue-w2-hold-xcheck \
  --expected-rows 18 --capture-rows 32 --max-seconds 10 \
  --reduced-store-replay-liq --disable-store-memory-mutation \
  --allow-residual-replay-liq-wait
```

R575 generated-RTL/QEMU evidence:

- manifest `status="pass"`, `compared_rows=18`, `mismatch_count=0`;
- `lret_sink_enqueue_accepted_w2_occupied=3`;
- `lret_sink_followup_w2_still_occupied=3`;
- `lret_sink_followup_w2_cleared=0`;
- `lret_sink_pending_w2_occupied=3`;
- `lret_sink_drain_valid_w2_occupied=3`;
- `lret_sink_drain_fire_w2_occupied=3`.
