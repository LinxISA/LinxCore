# LoadReplayReturnPipeW2RetireRecordPhysicalBundleSuppressPlan

## Purpose

`LoadReplayReturnPipeW2RetireRecordPhysicalBundleSuppressPlan` is the R599
diagnostic owner after the retained W2 retire-record bundle-transfer plan. It
does not mutate ROB completion, RF writeback, wakeup, or replay-row lifecycle
state. It names the all-or-none physical suppression boundary that a later
default retained-owner promotion must consume.

The owner exists because R597 proved the current default-path blocker is a full
physical duplicate bundle and R598 proved the transfer candidate is present only
when the model-order bundle is present. R599 keeps those facts separate from
live mutation while making partial suppression unrepresentable at this boundary.

## Source Mapping

- Chisel:
  `chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2RetireRecordPhysicalBundleSuppressPlan.scala`
- Unit test:
  `chisel/src/test/scala/linxcore/lsu/LoadReplayReturnPipeW2RetireRecordPhysicalBundleSuppressPlanSpec.scala`
- Top integration:
  `chisel/src/main/scala/linxcore/top/LinxCoreFrontendFetchRfAluTraceTop.scala`
- Harness sideband schema:
  `tools/chisel/frontend_fetch_rf_alu_trace_top_tb.cpp`
- Validator:
  `tools/chisel/validate_frontend_fetch_rf_alu_sideband_stats.py`

## Interface

Inputs:

- `enable`, `flush`: local active window.
- `transferCandidate`: R598 default transfer candidate.
- `requiresPhysicalBundleSuppression`: R598 requirement that the physical
  ROB/RF/wakeup/lifecycle bundle must be suppressed or transferred atomically.
- `robDuplicatePhysicalComplete`: physical ROB completion duplicate evidence.
- `rfDuplicatePhysicalWriteback`: physical RF writeback duplicate evidence.
- `wakeupDuplicatePhysicalWakeup`: physical wakeup duplicate evidence.
- `lifecycleClearDuplicatePhysicalClear`: physical replay-row lifecycle clear
  duplicate evidence.

Outputs:

- `transferCandidateSeen`: active transfer candidate.
- `suppressionRequired`: active candidate that requires physical suppression.
- `physicalBundleComplete`: all four physical duplicate evidences are present.
- `atomicSuppressCandidate`: candidate for a later atomic suppression action.
- `suppressRobComplete`, `suppressRfWriteback`, `suppressWakeup`,
  `suppressLifecycleClear`: four identical diagnostic intents driven only from
  `atomicSuppressCandidate`.
- `suppressMask`: four-bit all-or-none diagnostic mask.
- `allOrNoneSuppress`: invariant indicator that the mask is either `0` or
  `0xf`.
- `blockedByNoTransferCandidate`, `blockedByNoSuppressionRequirement`,
  `blockedByIncompletePhysicalBundle`: local blocker diagnostics.

## Logic Design

The module first qualifies the active window:

```text
active = enable && !flush
transferCandidateSeen = active && transferCandidate
suppressionRequired = transferCandidateSeen && requiresPhysicalBundleSuppression
```

It then requires the complete physical duplicate bundle:

```text
physicalBundleComplete =
  suppressionRequired &&
  robDuplicatePhysicalComplete &&
  rfDuplicatePhysicalWriteback &&
  wakeupDuplicatePhysicalWakeup &&
  lifecycleClearDuplicatePhysicalClear
```

`atomicSuppressCandidate` is asserted only when `suppressionRequired` and the
complete physical bundle are both present. Every suppression intent is assigned
from that single signal, so this owner cannot express "suppress ROB but not RF"
or any other partial transfer.

## Integration Notes

The reduced top wires this owner immediately after
`LoadReplayReturnPipeW2RetireRecordBundleTransferPlan`. Inputs are sourced from
the R598 plan and the same raw fallback duplicate guards consumed by R596/R597.
The outputs are exposed only as top-level sidebands and v49 harness counters.
They are not connected to live arbitration, ROB completion, RF writeback,
wakeup, or replay-row lifecycle mutation.

## Verification

Focused module gate:

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2RetireRecordPhysicalBundleSuppressPlan
```

Affected top gate:

```bash
bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop
```

Generated-RTL/QEMU replay-LIQ gate:

```bash
LINXCORE_REPLAY_LIQ_EARLY_STA_ADDRESS=1 \
LINXCORE_REPLAY_LIQ_W2_COMPLETION_DELAY_CYCLES=12 \
FETCH_REPLAY_LIQ_REQUIRE_NONZERO=wait_replay_capture_accepted,replay_queue_out_fire,liq_alloc_accepted,lret_w2_slot_accepted,w2_promotion_live \
bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh \
  --fixture replay-ldi-sdi-ldi-sdi-ldi-ldi-loop \
  --build-dir generated/r599-replay-physical-bundle-suppress-plan-xcheck \
  --expected-rows 18 --capture-rows 32 --max-seconds 10 \
  --reduced-store-replay-liq --disable-store-memory-mutation \
  --allow-residual-replay-liq-wait
```

Evidence:

```text
generated/r599-replay-physical-bundle-suppress-plan-xcheck/report/crosscheck_manifest.json
status=pass compared_rows=18 mismatch_count=0 qemu_cbstop=0 dut_cbstop=0

frontend_fetch_rf_alu_sideband_stats.json schema=v49
w2_retire_record_bundle_transfer_plan_default_transfer_candidate=5
w2_retire_record_bundle_transfer_plan_requires_physical_bundle_suppression=5
w2_retire_record_physical_bundle_suppress_plan_transfer_candidate=5
w2_retire_record_physical_bundle_suppress_plan_suppression_required=5
w2_retire_record_physical_bundle_suppress_plan_physical_bundle_complete=5
w2_retire_record_physical_bundle_suppress_plan_atomic_suppress_candidate=5
w2_retire_record_physical_bundle_suppress_plan_suppress_rob_complete=5
w2_retire_record_physical_bundle_suppress_plan_suppress_rf_writeback=5
w2_retire_record_physical_bundle_suppress_plan_suppress_wakeup=5
w2_retire_record_physical_bundle_suppress_plan_suppress_lifecycle_clear=5
w2_retire_record_physical_bundle_suppress_plan_suppress_mask_sum=75
w2_retire_record_physical_bundle_suppress_plan_all_or_none_suppress=195
w2_retire_record_physical_bundle_suppress_plan_blocked_by_no_transfer_candidate=189
w2_retire_record_physical_bundle_suppress_plan_blocked_by_no_suppression_requirement=0
w2_retire_record_physical_bundle_suppress_plan_blocked_by_incomplete_physical_bundle=0
```

The nonzero no-transfer-candidate blocker is idle-cycle diagnostic activity.
The five valid transfer cycles each drive mask `0xf`, giving a mask sum of
`75`.

## Next Owner

R600 adds
`LoadReplayReturnPipeW2RetireRecordPhysicalBundleSuppressProbe`, a default-off
sideband owner that proves the R599 all-or-none mask can be selected under an
explicit physical-suppression probe. Live physical suppression is still
deferred. The next live owner must add a non-circular timing or ownership
boundary before consuming the mask; do not feed the current-cycle mask back
into the same duplicate guards that produce the R599 evidence, and do not
enable one retained side effect independently.
