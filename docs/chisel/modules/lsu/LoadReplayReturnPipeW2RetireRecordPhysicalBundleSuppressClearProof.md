# LoadReplayReturnPipeW2RetireRecordPhysicalBundleSuppressClearProof

## Purpose

`LoadReplayReturnPipeW2RetireRecordPhysicalBundleSuppressClearProof` is the
R605 diagnostic owner after the R604 physical-bundle live mask. R604 suppresses
duplicate replay-return ROB completion, RF writeback, wakeup, and replay-row
lifecycle clear as one atomic physical bundle. R605 proves that the selected
live-mask candidates still correspond to real W2 fire/clear timing while the
duplicate lifecycle clear is suppressed.

The module is diagnostic-only. It does not drive ROB, RF, wakeup, LIQ, or clear
control.

## Source Mapping

- Chisel:
  `chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2RetireRecordPhysicalBundleSuppressClearProof.scala`
- Unit test:
  `chisel/src/test/scala/linxcore/lsu/LoadReplayReturnPipeW2RetireRecordPhysicalBundleSuppressClearProofSpec.scala`
- Top integration:
  `chisel/src/main/scala/linxcore/top/LinxCoreFrontendFetchRfAluTraceTop.scala`
- Upstream live mask:
  `chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2RetireRecordPhysicalBundleSuppressLiveMask.scala`
- Harness sideband schema:
  `tools/chisel/frontend_fetch_rf_alu_trace_top_tb.cpp`
- Validator:
  `tools/chisel/validate_frontend_fetch_rf_alu_sideband_stats.py`

## Model Evidence

The relevant model path is the same replay-return ownership chain cited by
R603/R604:

- `LDQInfo::returnData` in
  `/Users/zhoubot/linx-isa/model/LinxCoreModel/model/lsu/load_unit/ldq.cpp`
  publishes the load-return data and wakeup side effects for one load identity.
- `LDQInfo::retire` in the same file clears the resolved LDQ row by commit and
  load identity.
- `IEX::setMemWakeup` and `IEX::setMemData` in
  `/Users/zhoubot/linx-isa/model/LinxCoreModel/model/iex/iex.cpp` consume the
  returned memory side effects.

R605 does not reinterpret model behavior. It checks that the Chisel live-mask
candidate selected by R604 remains aligned with the W2 fire-complete,
clear-intent, live-clear, row-fill, and suppressed lifecycle-clear evidence.

## Interface

Inputs:

- `enable`, `flush`: local active window.
- `liveMaskCandidate`, `suppressMask`: selected R604 full physical mask.
- `fireComplete`: W2 side-effect fire-complete evidence.
- `clearIntent`, `liveClear`: W2 clear decision evidence.
- `rowFillEnable`: W2 live row-fill evidence.
- `lifecycleClearEnable`, `lifecycleClearAccepted`: replay-row lifecycle clear
  request state after R604 masking.

Outputs:

- `candidate`: active R604 live-mask candidate.
- `fullMask`: candidate with `suppressMask == 0xf`.
- `fireCompleteAligned`, `clearIntentAligned`, `liveClearAligned`,
  `rowFillAligned`: candidate aligned with retained W2 clear evidence.
- `lifecycleClearSuppressed`: candidate with lifecycle clear disabled and not
  accepted.
- `allClearAligned`: all positive proof bits true.
- `blockedByPartialMask`, `blockedByNoFireComplete`,
  `blockedByNoClearIntent`, `blockedByNoLiveClear`, `blockedByNoRowFill`,
  `blockedByLifecycleClearStillEnabled`: ordered blockers.

## Logic Design

The R604 live-mask candidate is registered after W2 fire/clear evidence. R605
therefore holds the most recent W2 clear evidence until a candidate consumes it:

```text
active = enable && !flush
candidate = active && liveMaskCandidate
anyClearEvidence = active && (fireComplete || clearIntent || liveClear || rowFillEnable)

if !enable || flush:
  clear retained evidence
else if candidate:
  clear retained evidence after the current proof cycle
else if anyClearEvidence:
  retain fireComplete/clearIntent/liveClear/rowFillEnable

fullMask = suppressMask == 0xf
lifecycleClearSuppressed = candidate && !lifecycleClearEnable && !lifecycleClearAccepted
allClearAligned =
  candidate && fullMask &&
  retained_or_current_fire_complete &&
  retained_or_current_clear_intent &&
  retained_or_current_live_clear &&
  retained_or_current_row_fill &&
  lifecycleClearSuppressed
```

The retained evidence is local to the diagnostic module and feeds only sideband
IO.

## Verification

Focused module gate:

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2RetireRecordPhysicalBundleSuppressClearProof
```

Affected top gate:

```bash
bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop
```

Generated-RTL/QEMU R605 gate:

```bash
LINXCORE_REPLAY_LIQ_EARLY_STA_ADDRESS=1 \
LINXCORE_REPLAY_LIQ_W2_COMPLETION_DELAY_CYCLES=12 \
LINXCORE_REPLAY_LIQ_RETAINED_OWNER_PHYSICAL_SUPPRESS_PROBE=1 \
LINXCORE_REPLAY_LIQ_RETAINED_OWNER_PHYSICAL_SUPPRESS_LIVE_MASK=1 \
FETCH_REPLAY_LIQ_REQUIRE_NONZERO=wait_replay_capture_accepted,replay_queue_out_fire,liq_alloc_accepted,lret_w2_slot_accepted,w2_promotion_live,w2_retire_record_physical_bundle_suppress_live_mask_enabled_candidate,w2_retire_record_physical_bundle_suppress_clear_proof_candidate,w2_retire_record_physical_bundle_suppress_clear_proof_full_mask,w2_retire_record_physical_bundle_suppress_clear_proof_fire_complete_aligned,w2_retire_record_physical_bundle_suppress_clear_proof_clear_intent_aligned,w2_retire_record_physical_bundle_suppress_clear_proof_live_clear_aligned,w2_retire_record_physical_bundle_suppress_clear_proof_row_fill_aligned,w2_retire_record_physical_bundle_suppress_clear_proof_lifecycle_clear_suppressed,w2_retire_record_physical_bundle_suppress_clear_proof_all_clear_aligned \
bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh \
  --fixture replay-ldi-sdi-ldi-sdi-ldi-ldi-loop \
  --build-dir generated/r605-replay-physical-suppress-clear-proof-xcheck \
  --expected-rows 18 --capture-rows 32 --max-seconds 10 \
  --reduced-store-replay-liq --disable-store-memory-mutation \
  --allow-residual-replay-liq-wait
```

Evidence:

```text
generated/r605-replay-physical-suppress-clear-proof-xcheck/report/crosscheck_manifest.json
status=pass compared=18 mismatches=0
cbstop_qemu=0 cbstop_dut=0

frontend_fetch_rf_alu_sideband_stats.json schema=v55
w2_retire_record_physical_bundle_suppress_live_mask_enabled_candidate=5
w2_retire_record_physical_bundle_suppress_clear_proof_candidate=5
w2_retire_record_physical_bundle_suppress_clear_proof_full_mask=5
w2_retire_record_physical_bundle_suppress_clear_proof_fire_complete_aligned=5
w2_retire_record_physical_bundle_suppress_clear_proof_clear_intent_aligned=5
w2_retire_record_physical_bundle_suppress_clear_proof_live_clear_aligned=5
w2_retire_record_physical_bundle_suppress_clear_proof_row_fill_aligned=5
w2_retire_record_physical_bundle_suppress_clear_proof_lifecycle_clear_suppressed=5
w2_retire_record_physical_bundle_suppress_clear_proof_all_clear_aligned=5
w2_retire_record_physical_bundle_suppress_clear_proof_blocked_by_partial_mask=0
w2_retire_record_physical_bundle_suppress_clear_proof_blocked_by_no_fire_complete=0
w2_retire_record_physical_bundle_suppress_clear_proof_blocked_by_no_clear_intent=0
w2_retire_record_physical_bundle_suppress_clear_proof_blocked_by_no_live_clear=0
w2_retire_record_physical_bundle_suppress_clear_proof_blocked_by_no_row_fill=0
w2_retire_record_physical_bundle_suppress_clear_proof_blocked_by_lifecycle_clear_still_enabled=0
```

The standalone v55 validator also passed with `--expect-reduced-store-replay-liq`
and nonzero requirements for every positive clear-proof counter.

## Next Owner

The next owner should scale the same physical-bundle suppression proof to a
larger replay-LIQ or CoreMark window before treating the path as replacement
evidence for full LSU retirement. Keep R605 diagnostic-only unless a future
packet needs the retained clear-evidence boundary for another proof.
