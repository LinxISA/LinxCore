# LoadReplayReturnPipeW2RetireRecordBundleTransferPlan

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2RetireRecordBundleTransferPlan.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/LoadReplayReturnPipeW2RetireRecordBundleTransferPlanSpec.scala`
- Integrated user: `rtl/LinxCore/chisel/src/main/scala/linxcore/top/LinxCoreFrontendFetchRfAluTraceTop.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/lsu/load_unit/ldq.cpp`: `LDQInfo::returnData`, `LDQInfo::retire`
  - `model/LinxCoreModel/model/iex/iex.cpp`: `IEX::setMemWakeup`, `IEX::setMemData`
- Related contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2RetireRecordDefaultPromotionReadiness.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2RetireRecordDuplicateVector.scala`
- Contract IDs: `LC-CHISEL-LSU-REPLAY-PIPE-W2-RETIRE-RECORD-BUNDLE-TRANSFER-PLAN-001`

## Purpose

`LoadReplayReturnPipeW2RetireRecordBundleTransferPlan` is the R598 diagnostic
owner that turns R597's full physical duplicate bundle classification into an
explicit default-ownership transfer plan. It does not perform the transfer. It
only reports when a later owner may consider replacing the physical W2 bundle
with the retained retire-record bundle.

The plan is intentionally strict: it requires a visible retained record, R596
default-promotion pre-arm evidence, R597 full model-order duplicate evidence,
no existing default-promotion readiness, and no active retained-owner probe.
Partial duplicate vectors are a blocker, not a transfer source.

## Interface

| Direction | Signal | Description |
|---|---|---|
| input | `enable` / `flush` | Reduced replay-LIQ arm and flush suppression. |
| input | `recordValid` | Pending retained retire record is visible. |
| input | `preArmModelOrderReady` | R596 pre-arm evidence is ready. |
| input | `defaultPromotionReady` | R596 says the retained default path already has no physical duplicate. |
| input | `duplicateVectorValid` | R597 has at least one raw physical duplicate for the pre-armed record. |
| input | `modelOrderDuplicateBundle` | R597 proves the duplicate vector covers ROB/RF/wakeup/lifecycle together. |
| input | `partialDuplicateVector` | R597 saw a duplicate vector that is not the full model-order bundle. |
| input | `probeActive` | No-physical, emit, or live retained-owner probe is active. |
| output | `recordCandidate` | Active retained record is visible. |
| output | `preArmReady` | Record plus R596 pre-arm evidence are present. |
| output | `modelOrderBundle` | Full R597 duplicate bundle is present for the pre-armed record. |
| output | `defaultTransferCandidate` | Diagnostic candidate for future default bundle transfer. |
| output | `requiresPhysicalBundleSuppression` | Alias for `defaultTransferCandidate`; future live use must suppress the physical bundle atomically. |
| output | `defaultPromotionAlreadyReady` | No transfer is needed because R596 already reports default readiness. |
| output | blocker signals | No-record, pre-arm-not-ready, no-duplicate-vector, partial-duplicate, and probe-active diagnostics. |

## Logic Design

```text
recordCandidate =
  enable && !flush && recordValid

preArmReady =
  recordCandidate && preArmModelOrderReady

modelOrderBundle =
  preArmReady && duplicateVectorValid && modelOrderDuplicateBundle

defaultTransferCandidate =
  modelOrderBundle &&
  !defaultPromotionReady &&
  !probeActive
```

`requiresPhysicalBundleSuppression` is equal to `defaultTransferCandidate`
because a future live promotion must suppress or replace the physical ROB
completion, RF writeback, wakeup, and lifecycle clear together. The module is a
planning surface only and does not drive those sinks.

## Integration Status

R598 wires the plan beside the R596 readiness gate and R597 duplicate vector in
`LinxCoreFrontendFetchRfAluTraceTop`.

Generated RTL/QEMU default-path evidence:

```text
generated/r598-replay-bundle-transfer-plan-xcheck/report/crosscheck_manifest.json
status=pass compared_rows=18 mismatch_count=0 qemu_cbstop=0 dut_cbstop=0

frontend_fetch_rf_alu_sideband_stats.json schema=v48
w2_retire_record_default_promotion_record_candidate=5
w2_retire_record_default_promotion_pre_arm_model_order_ready=5
w2_retire_record_default_promotion_any_physical_duplicate=5
w2_retire_record_default_promotion_ready=0
w2_retire_record_duplicate_vector_model_order_bundle=5
w2_retire_record_bundle_transfer_plan_candidate=5
w2_retire_record_bundle_transfer_plan_pre_arm_ready=5
w2_retire_record_bundle_transfer_plan_model_order_bundle=5
w2_retire_record_bundle_transfer_plan_default_transfer_candidate=5
w2_retire_record_bundle_transfer_plan_requires_physical_bundle_suppression=5
w2_retire_record_bundle_transfer_plan_default_promotion_already_ready=0
w2_retire_record_bundle_transfer_plan_blocked_by_no_record=189
w2_retire_record_bundle_transfer_plan_blocked_by_pre_arm_not_ready=0
w2_retire_record_bundle_transfer_plan_blocked_by_no_duplicate_vector=0
w2_retire_record_bundle_transfer_plan_blocked_by_partial_duplicate=0
w2_retire_record_bundle_transfer_plan_blocked_by_probe_active=0
```

The next owner is an explicit atomic bundle-transfer or physical-bundle
suppression design. It must not enable one retained side effect independently.

## Verification

Focused gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2RetireRecordBundleTransferPlan
bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop
LINXCORE_REPLAY_LIQ_EARLY_STA_ADDRESS=1 \
LINXCORE_REPLAY_LIQ_W2_COMPLETION_DELAY_CYCLES=12 \
FETCH_REPLAY_LIQ_REQUIRE_NONZERO=wait_replay_capture_accepted,replay_queue_out_fire,liq_alloc_accepted,lret_w2_slot_accepted,w2_promotion_live \
bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh \
  --fixture replay-ldi-sdi-ldi-sdi-ldi-ldi-loop \
  --build-dir generated/r598-replay-bundle-transfer-plan-xcheck \
  --expected-rows 18 --capture-rows 32 --max-seconds 10 \
  --reduced-store-replay-liq --disable-store-memory-mutation \
  --allow-residual-replay-liq-wait
```

Reference tests cover full-bundle transfer planning, already-ready default
promotion, partial duplicates, missing record, missing pre-arm evidence,
missing duplicate vector, probe-active blocking, flush, and Chisel elaboration.
