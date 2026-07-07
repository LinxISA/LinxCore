# LoadReplayReturnPipeW2RetireRecordFallbackOwnerPolicy

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2RetireRecordFallbackOwnerPolicy.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/LoadReplayReturnPipeW2RetireRecordFallbackOwnerPolicySpec.scala`
- Integrated user: `rtl/LinxCore/chisel/src/main/scala/linxcore/top/LinxCoreFrontendFetchRfAluTraceTop.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/lsu/load_unit/ldq.cpp`: `LDQInfo::returnData`
  - `model/LinxCoreModel/model/lsu/load_unit/ldq.cpp`: `LDQInfo::retire`
- Related contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2RetireRecordRobCompleteFallbackGuard.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2RetireRecordRfWritebackFallbackGuard.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2RetireRecordWakeupFallbackGuard.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2RetireRecordLifecycleClearFallbackGuard.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2RetireRecordModelOrderProof.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2RetireRecordDefaultPromotionReadiness.scala`
- Contract IDs: `LC-CHISEL-LSU-REPLAY-PIPE-W2-RETIRE-RECORD-FALLBACK-OWNER-001`

## Purpose

`LoadReplayReturnPipeW2RetireRecordFallbackOwnerPolicy` is the R591 ownership
gate for retained replay-load retire-record side effects. R587-R590 added
duplicate guards for the four retained side-effect classes: ROB completion, RF
writeback, wakeup, and LIQ lifecycle clear. Each guard proves whether the
physical W2 path already performed the same mutation for the captured returned
load.

The policy centralizes the live-enable decision for those guards. It only allows
retained side effects when all four retained fallback candidates are present and
none of the four physical duplicate detectors is set. A separate global fallback
arm must also be true. R591 ties that global arm low in the top, so this packet
adds an explicit proof surface without enabling live retained mutation.

## Interface

| Direction | Signal | Description |
|---|---|---|
| input | `enable` / `flush` | Reduced replay-LIQ arm and flush suppression. |
| input | `globalFallbackEnable` | Explicit experiment/live arm. The top ties this low in R591. |
| input | `recordValid` | Pending retained retire record. |
| input | `robCandidate` / `rfCandidate` / `wakeupCandidate` / `lifecycleClearCandidate` | Per-side-effect retained fallback candidate visibility from the four guards. |
| input | duplicate signals | Per-side-effect physical duplicate classifications from the four guards. |
| output | `recordCandidate` | Active retained record is visible. |
| output | `allFallbackCandidatesReady` | All four retained fallback candidates are visible for the same record observation window. |
| output | `anyPhysicalDuplicate` | At least one side-effect class is already owned by physical W2. |
| output | `retainedSoleOwnerEligible` | Retained record would be the sole owner if the global arm were enabled. |
| output | `sideEffectOwnerEnable` | Final fallback-enable signal fed back into the four guards. |
| output | blocker signals | Disabled, flush, no record, missing candidate, physical duplicate, and global-arm-disabled diagnostics. |

## Logic Design

```text
allFallbackCandidatesReady =
  active &&
  recordValid &&
  robCandidate &&
  rfCandidate &&
  wakeupCandidate &&
  lifecycleClearCandidate

anyPhysicalDuplicate =
  allFallbackCandidatesReady &&
  (robDuplicate || rfDuplicate || wakeupDuplicate || lifecycleClearDuplicate)

retainedSoleOwnerEligible =
  allFallbackCandidatesReady && !anyPhysicalDuplicate

sideEffectOwnerEnable =
  globalFallbackEnable && retainedSoleOwnerEligible
```

The policy has no payload fields and does not mutate ROB, RF, wakeup, or LIQ
state. It is intentionally a single owner for the enable decision so later
no-physical-side-effect stimuli do not arm the four fallbacks independently.

## Integration Status

R591 wires the policy into `LinxCoreFrontendFetchRfAluTraceTop`. The four
fallback guards now receive `policy.io.sideEffectOwnerEnable` instead of a
local `false.B`. The policy itself still receives `globalFallbackEnable :=
false.B`, preserving the R590 behavior while exposing four v41 counters:

- `w2_retire_record_fallback_owner_policy_candidate`
- `w2_retire_record_fallback_owner_policy_all_candidates_ready`
- `w2_retire_record_fallback_owner_policy_blocked_by_physical_duplicate`
- `w2_retire_record_fallback_owner_policy_side_effect_enable`

Generated RTL/QEMU evidence:

```text
generated/r591-replay-retire-record-fallback-owner-policy-xcheck/report/crosscheck_manifest.json
status=pass compared_rows=18 mismatch_count=0 qemu_cbstop=0 dut_cbstop=0

frontend_fetch_rf_alu_sideband_stats.json schema=v41
w2_retire_record_fallback_owner_policy_candidate=5
w2_retire_record_fallback_owner_policy_all_candidates_ready=5
w2_retire_record_fallback_owner_policy_blocked_by_physical_duplicate=5
w2_retire_record_fallback_owner_policy_side_effect_enable=0
```

The current replay-LIQ fixture therefore presents all retained fallback
candidates, but physical W2 already owns at least one side-effect class for each
retained record. The next packet should create a no-physical-side-effect
stimulus before raising the global fallback arm.

R592 adds the default-off reduced-top probe
`LINXCORE_REPLAY_LIQ_RETAINED_OWNER_NO_PHYSICAL_PROBE=1`. The probe masks the
physical-duplicate classifications only at the policy boundary; the individual
ROB/RF/wakeup/lifecycle guards and the physical W2 side-effect paths remain
unchanged and observable. The global retained-owner arm still stays low.

Generated RTL/QEMU probe evidence:

```text
generated/r592-replay-retained-owner-no-physical-probe-xcheck/report/crosscheck_manifest.json
status=pass compared_rows=18 mismatch_count=0 qemu_cbstop=0 dut_cbstop=0

frontend_fetch_rf_alu_sideband_stats.json schema=v42
w2_retire_record_fallback_owner_policy_candidate=5
w2_retire_record_fallback_owner_policy_all_candidates_ready=5
w2_retire_record_fallback_owner_policy_blocked_by_physical_duplicate=0
w2_retire_record_fallback_owner_policy_no_physical_probe_active=5
w2_retire_record_fallback_owner_policy_retained_sole_owner_eligible=5
w2_retire_record_fallback_owner_policy_blocked_by_global_fallback_disabled=5
w2_retire_record_fallback_owner_policy_side_effect_enable=0
```

The same report keeps the individual duplicate guards nonzero, proving R592 is
a policy-boundary probe rather than a mutation-path change:

```text
w2_retire_record_rob_fallback_duplicate_physical_complete=5
w2_retire_record_rf_fallback_duplicate_physical_writeback=5
w2_retire_record_wakeup_fallback_duplicate_physical_wakeup=5
w2_retire_record_lifecycle_clear_fallback_duplicate_physical_clear=5
```

R593 adds the separate default-off reduced-top probe
`LINXCORE_REPLAY_LIQ_RETAINED_OWNER_FALLBACK_EMIT_PROBE=1`. This probe masks
physical side-effect observations at the four guard inputs and raises the
policy global arm only for diagnostic fallback output emission. The retained
fallback outputs remain sideband-only in the top and are not wired into live
ROB, RF, wakeup, or LIQ mutation sinks.

Generated RTL/QEMU probe evidence:

```text
generated/r593-replay-retained-owner-fallback-emit-probe-xcheck/report/crosscheck_manifest.json
status=pass compared_rows=18 mismatch_count=0 qemu_cbstop=0 dut_cbstop=0

frontend_fetch_rf_alu_sideband_stats.json schema=v43
w2_retire_record_fallback_owner_policy_candidate=5
w2_retire_record_fallback_owner_policy_all_candidates_ready=5
w2_retire_record_fallback_owner_policy_blocked_by_physical_duplicate=0
w2_retire_record_fallback_owner_policy_no_physical_probe_active=5
w2_retire_record_fallback_owner_policy_fallback_emit_probe_active=5
w2_retire_record_fallback_owner_policy_retained_sole_owner_eligible=5
w2_retire_record_fallback_owner_policy_blocked_by_global_fallback_disabled=0
w2_retire_record_fallback_owner_policy_side_effect_enable=5
w2_retire_record_rob_fallback_complete_valid=5
w2_retire_record_rf_fallback_writeback_valid=5
w2_retire_record_wakeup_fallback_wakeup_valid=5
w2_retire_record_lifecycle_clear_fallback_clear_valid=5
```

The same report records all four duplicate-physical guard outputs at zero
because the probe deliberately masks physical inputs at the guard boundary.
This proves the retained fallback payloads can emit when treated as the sole
side-effect owner, while preserving architectural comparison. It is not a live
mutation promotion.

R594 adds the separate default-off reduced-top probe
`LINXCORE_REPLAY_LIQ_RETAINED_OWNER_FALLBACK_LIVE_PROBE=1`. This probe uses
the same no-physical and global-arm policy shape as the emit probe, but routes
the retained fallback payloads into the existing reduced live sink boundaries:
ROB completion through the replay completion arbiter input, scalar RF
writeback through the replay writeback arbiter input, returned-load wakeup
through the W2 wakeup arbiter input, and replay-LIQ row clear through the
clear-resolved mux. The default top path remains unchanged.

Generated RTL/QEMU live-probe evidence:

```text
generated/r594-replay-retained-owner-fallback-live-probe-xcheck/report/crosscheck_manifest.json
status=pass compared_rows=18 mismatch_count=0 qemu_cbstop=0 dut_cbstop=0

frontend_fetch_rf_alu_sideband_stats.json schema=v44
w2_retire_record_fallback_owner_policy_candidate=5
w2_retire_record_fallback_owner_policy_all_candidates_ready=5
w2_retire_record_fallback_owner_policy_no_physical_probe_active=5
w2_retire_record_fallback_owner_policy_fallback_live_probe_active=5
w2_retire_record_fallback_owner_policy_side_effect_enable=5
w2_retire_record_rob_fallback_complete_valid=5
w2_retire_record_rf_fallback_writeback_valid=5
w2_retire_record_wakeup_fallback_wakeup_valid=5
w2_retire_record_lifecycle_clear_fallback_clear_valid=5
w2_retire_record_rob_fallback_live_complete_selected=5
w2_retire_record_rf_fallback_live_writeback_selected=5
w2_retire_record_wakeup_fallback_live_wakeup_selected=5
w2_retire_record_lifecycle_clear_fallback_live_clear_selected=5
```

The same report keeps the four duplicate-physical guard outputs at zero because
the live probe deliberately masks physical observations at the guard boundary.
The lifecycle live-selected counter samples the retained lifecycle evidence
latch's `clearAccepted` signal, not the raw LIQ clear-accepted wire, so it
matches the retained-record owner being proved. This remains a reduced-top
diagnostic live-mutation probe; promotion to the default path still requires a
real no-physical source or an equivalent model-derived retained-order proof.

R595 adds `LoadReplayReturnPipeW2RetireRecordModelOrderProof` as a
policy-adjacent diagnostic proof. The proof consumes the policy's
`sideEffectOwnerEnable` plus the retained ROB/RF/wakeup/lifecycle fallback
outputs and retained lifecycle evidence. It reports whether the explicit live
probe has both return-side-effect evidence and later retire-clear evidence,
matching the model split between `LDQInfo::returnData` and `LDQInfo::retire`.

Generated RTL/QEMU model-order evidence:

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

This narrows the remaining promotion question from payload/order coherence to
default-path ownership. The default retained-owner arm still must stay off
outside explicit reduced-top probes.

R596 adds `LoadReplayReturnPipeW2RetireRecordDefaultPromotionReadiness` as the
policy-adjacent pre-arm gate for a future default retained-owner path. Unlike
the policy input duplicate signals under probes, this gate consumes raw
unmasked duplicate outputs from the four fallback guards and separately blocks
probe-active observations.

Generated RTL/QEMU default-path evidence:

```text
generated/r596-replay-default-promotion-readiness-xcheck/report/crosscheck_manifest.json
status=pass compared_rows=18 mismatch_count=0 qemu_cbstop=0 dut_cbstop=0

frontend_fetch_rf_alu_sideband_stats.json schema=v46
w2_retire_record_fallback_owner_policy_candidate=5
w2_retire_record_fallback_owner_policy_all_candidates_ready=5
w2_retire_record_fallback_owner_policy_blocked_by_physical_duplicate=5
w2_retire_record_fallback_owner_policy_side_effect_enable=0
w2_retire_record_default_promotion_record_candidate=5
w2_retire_record_default_promotion_pre_arm_model_order_ready=5
w2_retire_record_default_promotion_any_physical_duplicate=5
w2_retire_record_default_promotion_ready=0
w2_retire_record_default_promotion_blocked_by_physical_duplicate=5
w2_retire_record_default_promotion_blocked_by_probe_active=0
```

The default path is therefore not blocked by missing retained evidence; it is
blocked by real physical duplicate ownership. Future promotion work should
create a real no-physical side-effect source or explicitly redesign ownership.

R597 adds `LoadReplayReturnPipeW2RetireRecordDuplicateVector` as the
policy-adjacent raw duplicate classifier. It consumes the same unmasked
duplicate guard outputs as the R596 default-promotion gate and reports whether
the current physical duplicate block is partial or a same-record model-order
bundle.

Generated RTL/QEMU duplicate-vector evidence:

```text
generated/r597-replay-duplicate-vector-xcheck/report/crosscheck_manifest.json
status=pass compared_rows=18 mismatch_count=0 qemu_cbstop=0 dut_cbstop=0

frontend_fetch_rf_alu_sideband_stats.json schema=v47
w2_retire_record_default_promotion_record_candidate=5
w2_retire_record_default_promotion_pre_arm_model_order_ready=5
w2_retire_record_default_promotion_any_physical_duplicate=5
w2_retire_record_default_promotion_ready=0
w2_retire_record_duplicate_vector_valid=5
w2_retire_record_duplicate_vector_return_side_effect_bundle=5
w2_retire_record_duplicate_vector_model_order_bundle=5
w2_retire_record_duplicate_vector_partial=0
w2_retire_record_duplicate_vector_single=0
w2_retire_record_duplicate_vector_multi=5
w2_retire_record_duplicate_vector_count_sum=20
w2_retire_record_duplicate_vector_next_requires_bundle_transfer=5
```

The policy remains default-off. The R597 evidence says the next no-physical
source cannot be scoped to a single side effect; default promotion needs a
bundle-level ownership transfer or suppression for ROB completion, RF
writeback, wakeup, and lifecycle clear together.

R598 follows that evidence by adding
`LoadReplayReturnPipeW2RetireRecordBundleTransferPlan`. The plan is still
policy-adjacent and diagnostic-only; it does not change
`sideEffectOwnerEnable`. The generated RTL/QEMU gate at
`generated/r598-replay-bundle-transfer-plan-xcheck` passes with 18 compared
rows, zero mismatches, and zero QEMU/DUT CBSTOP rows. Sideband schema v48
records `w2_retire_record_bundle_transfer_plan_default_transfer_candidate=5`
and
`w2_retire_record_bundle_transfer_plan_requires_physical_bundle_suppression=5`.
This names the future promotion requirement as an atomic physical-bundle
suppression problem rather than a fallback-policy arm change.

R599 keeps that requirement outside the policy arm by adding
`LoadReplayReturnPipeW2RetireRecordPhysicalBundleSuppressPlan`. It consumes the
R598 transfer candidate and raw physical duplicate guards, emits only an
all-or-none diagnostic suppression mask, and leaves `sideEffectOwnerEnable`
unchanged. The v49 evidence records five atomic suppress candidates, all four
suppression intent counters at five, and zero incomplete-bundle blockers.

## Verification

Focused gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2RetireRecordFallbackOwnerPolicy
bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop
LINXCORE_REPLAY_LIQ_EARLY_STA_ADDRESS=1 \
LINXCORE_REPLAY_LIQ_W2_COMPLETION_DELAY_CYCLES=12 \
LINXCORE_REPLAY_LIQ_RETAINED_OWNER_NO_PHYSICAL_PROBE=1 \
FETCH_REPLAY_LIQ_REQUIRE_NONZERO=wait_replay_capture_accepted,replay_queue_out_fire,liq_alloc_accepted,lret_w2_slot_accepted,w2_promotion_live \
bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh \
  --fixture replay-ldi-sdi-ldi-sdi-ldi-ldi-loop \
  --build-dir generated/r592-replay-retained-owner-no-physical-probe-xcheck \
  --expected-rows 18 --capture-rows 32 --max-seconds 10 \
  --reduced-store-replay-liq --disable-store-memory-mutation \
  --allow-residual-replay-liq-wait
LINXCORE_REPLAY_LIQ_EARLY_STA_ADDRESS=1 \
LINXCORE_REPLAY_LIQ_W2_COMPLETION_DELAY_CYCLES=12 \
LINXCORE_REPLAY_LIQ_RETAINED_OWNER_FALLBACK_EMIT_PROBE=1 \
FETCH_REPLAY_LIQ_REQUIRE_NONZERO=wait_replay_capture_accepted,replay_queue_out_fire,liq_alloc_accepted,lret_w2_slot_accepted,w2_promotion_live \
bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh \
  --fixture replay-ldi-sdi-ldi-sdi-ldi-ldi-loop \
  --build-dir generated/r593-replay-retained-owner-fallback-emit-probe-xcheck \
  --expected-rows 18 --capture-rows 32 --max-seconds 10 \
  --reduced-store-replay-liq --disable-store-memory-mutation \
  --allow-residual-replay-liq-wait
LINXCORE_REPLAY_LIQ_EARLY_STA_ADDRESS=1 \
LINXCORE_REPLAY_LIQ_W2_COMPLETION_DELAY_CYCLES=12 \
LINXCORE_REPLAY_LIQ_RETAINED_OWNER_FALLBACK_LIVE_PROBE=1 \
FETCH_REPLAY_LIQ_REQUIRE_NONZERO=wait_replay_capture_accepted,replay_queue_out_fire,liq_alloc_accepted,lret_w2_slot_accepted,w2_promotion_live \
bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh \
  --fixture replay-ldi-sdi-ldi-sdi-ldi-ldi-loop \
  --build-dir generated/r594-replay-retained-owner-fallback-live-probe-xcheck \
  --expected-rows 18 --capture-rows 32 --max-seconds 10 \
  --reduced-store-replay-liq --disable-store-memory-mutation \
  --allow-residual-replay-liq-wait
```

The reference tests cover unique retained ownership, physical duplicate
blocking, missing-candidate blocking, the global fallback arm, and Chisel
elaboration.
