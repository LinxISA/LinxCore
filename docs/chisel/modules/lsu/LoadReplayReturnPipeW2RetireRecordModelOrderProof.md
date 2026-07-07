# LoadReplayReturnPipeW2RetireRecordModelOrderProof

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2RetireRecordModelOrderProof.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/LoadReplayReturnPipeW2RetireRecordModelOrderProofSpec.scala`
- Integrated user: `rtl/LinxCore/chisel/src/main/scala/linxcore/top/LinxCoreFrontendFetchRfAluTraceTop.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/lsu/load_unit/ldq.cpp`: `LDQInfo::returnData`, `LDQInfo::retire`
  - `model/LinxCoreModel/model/iex/iex.cpp`: `IEX::setMemWakeup`, `IEX::setMemData`
- Related contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2RetireRecordFallbackOwnerPolicy.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2RetireRecordLifecycleEvidenceLatch.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2RetireRecordRobCompleteFallbackGuard.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2RetireRecordRfWritebackFallbackGuard.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2RetireRecordWakeupFallbackGuard.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2RetireRecordLifecycleClearFallbackGuard.scala`
- Contract IDs: `LC-CHISEL-LSU-REPLAY-PIPE-W2-RETIRE-RECORD-MODEL-ORDER-PROOF-001`

## Purpose

`LoadReplayReturnPipeW2RetireRecordModelOrderProof` is the R595 diagnostic
proof owner for retained replay-load fallback ordering. It joins the retained
record's returned-load side-effect evidence with the retained lifecycle clear
evidence, then reports whether the owner policy has also enabled the retained
fallback path.

The module mirrors the LinxCoreModel ordering split: `LDQInfo::returnData`
returns data, updates ROB/RF-visible state through `IEX::setMemData`, and wakes
dependents through `IEX::setMemWakeup`; `LDQInfo::retire` later clears resolved
LDQ rows by commit order. R595 keeps that proof diagnostic. It does not mutate
ROB, RF, wakeup, or replay-LIQ state and does not raise the default retained
owner arm.

## Interface

| Direction | Signal | Description |
|---|---|---|
| input | `enable` / `flush` | Reduced replay-LIQ arm and flush suppression. |
| input | `recordValid` | Pending retained retire record is visible. |
| input | `robReturnSideEffectValid` | Retained ROB completion fallback evidence is valid. |
| input | `rfReturnSideEffectValid` | Retained scalar RF writeback fallback evidence is valid. |
| input | `wakeupReturnSideEffectValid` | Retained wakeup fallback evidence is valid. |
| input | `lifecycleEvidenceProviderValid` | Captured retained lifecycle evidence is present. |
| input | `lifecycleClearFallbackValid` | Retained lifecycle clear fallback evidence is valid. |
| input | `sideEffectOwnerEnable` | The fallback owner policy has enabled retained side effects. |
| output | `active` | Module is enabled and not flushed. |
| output | `recordCandidate` | Active retained record candidate. |
| output | `returnSideEffectsReady` | ROB, RF, and wakeup return-side effects are all present. |
| output | `retireClearEvidenceReady` | Retained lifecycle evidence and clear fallback are both present. |
| output | `returnThenRetireReady` | Return-side effects and retire-clear evidence are both present. |
| output | `fallbackOwnerModelOrderEligible` | Model-order proof is ready and the owner policy is enabled. |
| output | blocker signals | Missing record, missing return side effect, missing retire-clear evidence, and owner-disabled diagnostics. |

## Logic Design

```text
recordCandidate =
  enable && !flush && recordValid

returnSideEffectsReady =
  recordCandidate &&
  robReturnSideEffectValid &&
  rfReturnSideEffectValid &&
  wakeupReturnSideEffectValid

retireClearEvidenceReady =
  recordCandidate &&
  lifecycleEvidenceProviderValid &&
  lifecycleClearFallbackValid

returnThenRetireReady =
  returnSideEffectsReady && retireClearEvidenceReady

fallbackOwnerModelOrderEligible =
  returnThenRetireReady && sideEffectOwnerEnable
```

The output split deliberately keeps return-side-effect readiness separate from
retire-clear evidence. That makes missing model-order prerequisites visible in
sideband statistics before any future default-path promotion decision.

## Integration Status

R595 wires the module into `LinxCoreFrontendFetchRfAluTraceTop` beside the
retained fallback owner policy. Its inputs come from the four retained fallback
guards, the retained lifecycle evidence latch, and the policy's
`sideEffectOwnerEnable`. The top exposes eight v45 sideband counters:

- `w2_retire_record_model_order_record_candidate`
- `w2_retire_record_model_order_return_side_effects_ready`
- `w2_retire_record_model_order_retire_clear_evidence_ready`
- `w2_retire_record_model_order_return_then_retire_ready`
- `w2_retire_record_model_order_fallback_owner_eligible`
- `w2_retire_record_model_order_blocked_by_missing_return_side_effect`
- `w2_retire_record_model_order_blocked_by_missing_retire_clear_evidence`
- `w2_retire_record_model_order_blocked_by_owner_disabled`

Generated RTL/QEMU evidence:

```text
generated/r595-replay-retire-record-model-order-proof-xcheck/report/crosscheck_manifest.json
status=pass compared_rows=18 mismatch_count=0 qemu_cbstop=0 dut_cbstop=0

frontend_fetch_rf_alu_sideband_stats.json schema=v45
w2_retire_record_model_order_record_candidate=5
w2_retire_record_model_order_return_side_effects_ready=5
w2_retire_record_model_order_retire_clear_evidence_ready=5
w2_retire_record_model_order_return_then_retire_ready=5
w2_retire_record_model_order_fallback_owner_eligible=5
w2_retire_record_model_order_blocked_by_missing_return_side_effect=0
w2_retire_record_model_order_blocked_by_missing_retire_clear_evidence=0
w2_retire_record_model_order_blocked_by_owner_disabled=0
```

This proves the retained fallback payloads selected by the explicit live probe
have the same return-before-retire evidence shape as the model ordering. It
does not by itself promote the retained fallback path into default behavior.

## Verification

Focused gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2RetireRecordModelOrderProof
bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop
LINXCORE_REPLAY_LIQ_EARLY_STA_ADDRESS=1 \
LINXCORE_REPLAY_LIQ_W2_COMPLETION_DELAY_CYCLES=12 \
LINXCORE_REPLAY_LIQ_RETAINED_OWNER_FALLBACK_LIVE_PROBE=1 \
FETCH_REPLAY_LIQ_REQUIRE_NONZERO=wait_replay_capture_accepted,replay_queue_out_fire,liq_alloc_accepted,lret_w2_slot_accepted,w2_promotion_live \
bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh \
  --fixture replay-ldi-sdi-ldi-sdi-ldi-ldi-loop \
  --build-dir generated/r595-replay-retire-record-model-order-proof-xcheck \
  --expected-rows 18 --capture-rows 32 --max-seconds 10 \
  --reduced-store-replay-liq --disable-store-memory-mutation \
  --allow-residual-replay-liq-wait
```

Reference tests cover full eligibility, missing return side-effect evidence,
missing retire-clear evidence, missing record, owner-disabled, flush, and
Chisel elaboration.
