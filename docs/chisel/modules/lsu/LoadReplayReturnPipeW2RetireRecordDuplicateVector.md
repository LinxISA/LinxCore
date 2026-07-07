# LoadReplayReturnPipeW2RetireRecordDuplicateVector

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2RetireRecordDuplicateVector.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/LoadReplayReturnPipeW2RetireRecordDuplicateVectorSpec.scala`
- Integrated user: `rtl/LinxCore/chisel/src/main/scala/linxcore/top/LinxCoreFrontendFetchRfAluTraceTop.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/lsu/load_unit/ldq.cpp`: `LDQInfo::returnData`, `LDQInfo::retire`
  - `model/LinxCoreModel/model/iex/iex.cpp`: `IEX::setMemWakeup`, `IEX::setMemData`
- Related contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2RetireRecordDefaultPromotionReadiness.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2RetireRecordFallbackOwnerPolicy.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2RetireRecordModelOrderProof.scala`
- Contract IDs: `LC-CHISEL-LSU-REPLAY-PIPE-W2-RETIRE-RECORD-DUPLICATE-VECTOR-001`

## Purpose

`LoadReplayReturnPipeW2RetireRecordDuplicateVector` is the R597 classifier for
raw physical duplicate ownership at the retained replay-load retire-record
boundary. R596 proved the default retained-owner path has model-order pre-arm
evidence, but it is blocked by physical W2 duplicate ownership. This module
answers the next question: whether those duplicates are independent aggregate
counters or a same-record bundle covering the model return and retire effects.

The classifier is diagnostic only. It does not drive ROB completion, scalar RF
writeback, returned-load wakeup, replay-LIQ lifecycle clear, or the fallback
owner arm.

## Interface

| Direction | Signal | Description |
|---|---|---|
| input | `enable` / `flush` | Reduced replay-LIQ arm and flush suppression. |
| input | `recordValid` | Pending retained retire record is visible. |
| input | `preArmModelOrderReady` | Default-promotion pre-arm evidence from R596 is ready. |
| input | duplicate signals | Raw unmasked physical duplicate observations for ROB completion, RF writeback, wakeup, and lifecycle clear. |
| output | `recordCandidate` | Active retained record is visible. |
| output | `duplicateVectorValid` | Record, pre-arm evidence, and at least one duplicate are all present. |
| output | `returnSideEffectDuplicateBundle` | ROB, RF, and wakeup duplicates are present together, matching the model `returnData` side-effect bundle. |
| output | `modelOrderDuplicateBundle` | Return-side-effect duplicates plus lifecycle-clear duplicate are all present. |
| output | `partialDuplicateVector` | Some duplicate exists, but the full model-order duplicate bundle is not present. |
| output | `singleDuplicate` / `multiDuplicate` | Duplicate-vector cardinality classification. |
| output | `duplicateCount` | Number of duplicate classes, zero when the vector is not valid. |
| output | `nextOwnerRequiresBundleTransfer` | Alias for `modelOrderDuplicateBundle`; next no-physical source must transfer the whole bundle. |
| output | blocker signals | No-record, pre-arm-not-ready, and no-duplicate diagnostics. |

## Logic Design

```text
recordCandidate =
  enable && !flush && recordValid

duplicateCount =
  popcount(robDuplicate,
           rfDuplicate,
           wakeupDuplicate,
           lifecycleClearDuplicate)

duplicateVectorValid =
  recordCandidate && preArmModelOrderReady && duplicateCount != 0

returnSideEffectDuplicateBundle =
  duplicateVectorValid &&
  robDuplicate &&
  rfDuplicate &&
  wakeupDuplicate

modelOrderDuplicateBundle =
  returnSideEffectDuplicateBundle && lifecycleClearDuplicate
```

`blockedByNoRecord` is intentionally an active-cycle diagnostic. In a bounded
harness it can be nonzero on idle cycles even when every valid retained record
is a full model-order duplicate bundle.

## Integration Status

R597 wires the classifier beside the default-promotion readiness gate in
`LinxCoreFrontendFetchRfAluTraceTop`. The top feeds it the R596
`preArmModelOrderReady` result and the raw unmasked duplicate outputs from the
ROB, RF, wakeup, and lifecycle fallback guards.

Generated RTL/QEMU default-path evidence:

```text
generated/r597-replay-duplicate-vector-xcheck/report/crosscheck_manifest.json
status=pass compared_rows=18 mismatch_count=0 qemu_cbstop=0 dut_cbstop=0

frontend_fetch_rf_alu_sideband_stats.json schema=v47
w2_retire_record_default_promotion_record_candidate=5
w2_retire_record_default_promotion_pre_arm_model_order_ready=5
w2_retire_record_default_promotion_any_physical_duplicate=5
w2_retire_record_default_promotion_ready=0
w2_retire_record_default_promotion_blocked_by_physical_duplicate=5
w2_retire_record_duplicate_vector_valid=5
w2_retire_record_duplicate_vector_return_side_effect_bundle=5
w2_retire_record_duplicate_vector_model_order_bundle=5
w2_retire_record_duplicate_vector_partial=0
w2_retire_record_duplicate_vector_single=0
w2_retire_record_duplicate_vector_multi=5
w2_retire_record_duplicate_vector_count_sum=20
w2_retire_record_duplicate_vector_next_requires_bundle_transfer=5
w2_retire_record_duplicate_vector_blocked_by_no_record=189
w2_retire_record_duplicate_vector_blocked_by_pre_arm_not_ready=0
w2_retire_record_duplicate_vector_blocked_by_no_duplicate=0
```

The current fixture therefore blocks default promotion because the same
retained records already have all four physical W2 duplicate owners. The next
owner is not a single-side-effect fallback; it must either create a real
no-physical source for the whole ROB/RF/wakeup/lifecycle bundle or deliberately
redesign the default ownership transfer.

R598 adds `LoadReplayReturnPipeW2RetireRecordBundleTransferPlan` as that
deliberate redesign planning surface. It consumes this module's
`modelOrderDuplicateBundle` and only emits a diagnostic transfer candidate when
the R596 pre-arm evidence is present, the R596 default-ready output is still
false, and no retained-owner probe is active. The v48 sideband evidence records
`w2_retire_record_bundle_transfer_plan_default_transfer_candidate=5`,
`w2_retire_record_bundle_transfer_plan_requires_physical_bundle_suppression=5`,
`w2_retire_record_bundle_transfer_plan_default_promotion_already_ready=0`, and
zero partial/probe/no-vector blockers.

## Verification

Focused gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2RetireRecordDuplicateVector
bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop
LINXCORE_REPLAY_LIQ_EARLY_STA_ADDRESS=1 \
LINXCORE_REPLAY_LIQ_W2_COMPLETION_DELAY_CYCLES=12 \
FETCH_REPLAY_LIQ_REQUIRE_NONZERO=wait_replay_capture_accepted,replay_queue_out_fire,liq_alloc_accepted,lret_w2_slot_accepted,w2_promotion_live \
bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh \
  --fixture replay-ldi-sdi-ldi-sdi-ldi-ldi-loop \
  --build-dir generated/r597-replay-duplicate-vector-xcheck \
  --expected-rows 18 --capture-rows 32 --max-seconds 10 \
  --reduced-store-replay-liq --disable-store-memory-mutation \
  --allow-residual-replay-liq-wait
```

Reference tests cover full model-order duplicate bundles, partial vectors,
single duplicates, missing-record, missing-pre-arm, no-duplicate, flush, and
Chisel elaboration.
