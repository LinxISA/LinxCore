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
```

The reference tests cover unique retained ownership, physical duplicate
blocking, missing-candidate blocking, the global fallback arm, and Chisel
elaboration.
