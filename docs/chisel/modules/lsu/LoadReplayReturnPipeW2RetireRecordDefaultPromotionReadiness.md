# LoadReplayReturnPipeW2RetireRecordDefaultPromotionReadiness

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2RetireRecordDefaultPromotionReadiness.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/LoadReplayReturnPipeW2RetireRecordDefaultPromotionReadinessSpec.scala`
- Integrated user: `rtl/LinxCore/chisel/src/main/scala/linxcore/top/LinxCoreFrontendFetchRfAluTraceTop.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/lsu/load_unit/ldq.cpp`: `LDQInfo::returnData`, `LDQInfo::retire`
  - `model/LinxCoreModel/model/iex/iex.cpp`: `IEX::setMemWakeup`, `IEX::setMemData`
- Related contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2RetireRecordFallbackOwnerPolicy.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2RetireRecordModelOrderProof.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2RetireRecordLifecycleEvidenceLatch.scala`
- Contract IDs: `LC-CHISEL-LSU-REPLAY-PIPE-W2-RETIRE-RECORD-DEFAULT-PROMOTION-READINESS-001`

## Purpose

`LoadReplayReturnPipeW2RetireRecordDefaultPromotionReadiness` is the R596
pre-arm gate for default retained replay-load fallback promotion. It names the
conditions that must be true before any future packet can raise the retained
owner arm outside explicit diagnostic probes.

The module deliberately consumes raw physical duplicate evidence from the four
fallback guards and a separate `probeActive` input. That prevents the R592-R595
probe masks from satisfying the default-promotion path. A ready result means
the retained record has fallback candidates, captured lifecycle evidence, no
real physical duplicate side-effect owner, and no active probe.

## Interface

| Direction | Signal | Description |
|---|---|---|
| input | `enable` / `flush` | Reduced replay-LIQ arm and flush suppression. |
| input | `recordValid` | Pending retained retire record is visible. |
| input | `fallbackCandidatesReady` | Owner-policy candidate join is ready. |
| input | `lifecycleEvidenceProviderValid` | Captured lifecycle evidence is present. |
| input | duplicate signals | Raw physical duplicate observations from ROB, RF, wakeup, and lifecycle fallback guards. |
| input | `probeActive` | Any retained-owner diagnostic probe is active. |
| output | `recordCandidate` | Active retained record candidate. |
| output | `preArmModelOrderReady` | Fallback candidates and lifecycle evidence are both present before arming. |
| output | `anyPhysicalDuplicate` | A real physical side-effect owner already exists. |
| output | `defaultPromotionReady` | Pre-arm evidence is ready, no duplicate exists, and no probe is active. |
| output | blocker signals | Missing record, missing candidates, missing lifecycle evidence, physical duplicate, and probe-active diagnostics. |

## Logic Design

```text
recordCandidate =
  enable && !flush && recordValid

preArmModelOrderReady =
  fallbackCandidatesReady && lifecycleEvidenceProviderValid

anyPhysicalDuplicate =
  preArmModelOrderReady &&
  (robDuplicate ||
   rfDuplicate ||
   wakeupDuplicate ||
   lifecycleClearDuplicate)

defaultPromotionReady =
  preArmModelOrderReady &&
  !anyPhysicalDuplicate &&
  !probeActive
```

This is a diagnostic gate only. It does not drive ROB, RF, wakeup, replay-LIQ
clear, or the owner policy's `globalFallbackEnable`.

## Integration Status

R596 wires the module into `LinxCoreFrontendFetchRfAluTraceTop` beside the
fallback owner policy and model-order proof. The top feeds it the policy's
candidate join, the retained lifecycle evidence latch, raw unmasked duplicate
outputs from all four fallback guards, and a probe-active flag for no-physical,
emit, or live probes.

Generated RTL/QEMU default-path evidence:

```text
generated/r596-replay-default-promotion-readiness-xcheck/report/crosscheck_manifest.json
status=pass compared_rows=18 mismatch_count=0 qemu_cbstop=0 dut_cbstop=0

frontend_fetch_rf_alu_sideband_stats.json schema=v46
w2_retire_record_default_promotion_record_candidate=5
w2_retire_record_default_promotion_pre_arm_model_order_ready=5
w2_retire_record_default_promotion_any_physical_duplicate=5
w2_retire_record_default_promotion_ready=0
w2_retire_record_default_promotion_blocked_by_physical_duplicate=5
w2_retire_record_default_promotion_blocked_by_probe_active=0
w2_retire_record_default_promotion_blocked_by_missing_fallback_candidate=0
w2_retire_record_default_promotion_blocked_by_missing_lifecycle_evidence=0
```

The default path therefore has pre-arm evidence, but the current replay-LIQ
fixture still proves physical W2 owns the same side effects. The next owner is
a real no-physical side-effect source or a reviewed default-path ownership
change.

R597 wires `LoadReplayReturnPipeW2RetireRecordDuplicateVector` to the same
pre-arm evidence and raw duplicate inputs. The generated RTL/QEMU gate at
`generated/r597-replay-duplicate-vector-xcheck` passes with 18 compared rows,
zero mismatches, and zero QEMU/DUT CBSTOP rows. Sideband schema v47 records
`w2_retire_record_duplicate_vector_valid=5`,
`w2_retire_record_duplicate_vector_return_side_effect_bundle=5`,
`w2_retire_record_duplicate_vector_model_order_bundle=5`,
`w2_retire_record_duplicate_vector_partial=0`,
`w2_retire_record_duplicate_vector_single=0`,
`w2_retire_record_duplicate_vector_multi=5`, and
`w2_retire_record_duplicate_vector_count_sum=20`. This proves the current
default-promotion blocker is the full model-order physical duplicate bundle,
not an isolated side-effect duplicate.

## Verification

Focused gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2RetireRecordDefaultPromotionReadiness
bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop
LINXCORE_REPLAY_LIQ_EARLY_STA_ADDRESS=1 \
LINXCORE_REPLAY_LIQ_W2_COMPLETION_DELAY_CYCLES=12 \
FETCH_REPLAY_LIQ_REQUIRE_NONZERO=wait_replay_capture_accepted,replay_queue_out_fire,liq_alloc_accepted,lret_w2_slot_accepted,w2_promotion_live \
bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh \
  --fixture replay-ldi-sdi-ldi-sdi-ldi-ldi-loop \
  --build-dir generated/r596-replay-default-promotion-readiness-xcheck \
  --expected-rows 18 --capture-rows 32 --max-seconds 10 \
  --reduced-store-replay-liq --disable-store-memory-mutation \
  --allow-residual-replay-liq-wait
```

Reference tests cover ready, physical-duplicate block, probe-active block,
missing record, missing candidates, missing lifecycle evidence, flush, and
Chisel elaboration.
