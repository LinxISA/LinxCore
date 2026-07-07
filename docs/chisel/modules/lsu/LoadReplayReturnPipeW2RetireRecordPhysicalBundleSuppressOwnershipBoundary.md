# LoadReplayReturnPipeW2RetireRecordPhysicalBundleSuppressOwnershipBoundary

## Purpose

`LoadReplayReturnPipeW2RetireRecordPhysicalBundleSuppressOwnershipBoundary` is
the R603 sideband owner after the R602 lifetime proof. R602 showed that the R601
registered physical suppression mask appears after `retireRecord.io.recordValid`
has cleared. R603 therefore carries the capture-time identity and lifecycle row
with the registered mask instead of requiring the live retained record to still
exist one cycle later.

The module remains diagnostic-only. It proves an ownership predicate for a
registered mask; it does not drive physical guard suppression, ROB completion,
RF writeback, wakeup, or replay-row lifecycle clear.

## Source Mapping

- Chisel:
  `chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2RetireRecordPhysicalBundleSuppressOwnershipBoundary.scala`
- Unit test:
  `chisel/src/test/scala/linxcore/lsu/LoadReplayReturnPipeW2RetireRecordPhysicalBundleSuppressOwnershipBoundarySpec.scala`
- Top integration:
  `chisel/src/main/scala/linxcore/top/LinxCoreFrontendFetchRfAluTraceTop.scala`
- Upstream boundary:
  `chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2RetireRecordPhysicalBundleSuppressBoundary.scala`
- R602 lifetime proof:
  `chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2RetireRecordPhysicalBundleSuppressIdentityProof.scala`
- Harness sideband schema:
  `tools/chisel/frontend_fetch_rf_alu_trace_top_tb.cpp`
- Validator:
  `tools/chisel/validate_frontend_fetch_rf_alu_sideband_stats.py`

## Model Evidence

The model path is the same retained load-return ownership path cited by R602:

- `LDQInfo::returnData` in
  `/Users/zhoubot/linx-isa/model/LinxCoreModel/model/lsu/load_unit/ldq.cpp`
  produces LRET data, wakeup, and resolved LDQ state for the load identity.
- `LDQInfo::retire` in the same file clears resolved LDQ entries by
  commit/order identity, including load LSID.
- `IEX::setMemWakeup` and `IEX::setMemData` in
  `/Users/zhoubot/linx-isa/model/LinxCoreModel/model/iex/iex.cpp` consume the
  returned memory identity for wakeup/data side effects.

R603 keeps that identity attached to the registered mask so the next live owner
does not need the already-cleared retained record to re-prove ownership.

## Interface

Inputs:

- `enable`, `flush`: local active window.
- `capture`: R601 accepted a full physical suppression mask.
- `captureRid`, `captureLoadLsId`: retained record identity sampled at capture.
- `captureLifecycleRowReady`, `captureLifecycleRowIndex`: lifecycle row
  evidence sampled at capture.
- `registeredValid`, `registeredFullMask`: one-cycle-later R601 mask state.

Outputs:

- `captureOwnership`: capture-cycle ownership sample.
- `capturedOwnershipValid`: stored ownership is valid in the active window.
- `registeredCandidate`: R601 registered mask is visible.
- `registeredRid`, `registeredLoadLsId`: carried identity for the registered
  mask.
- `registeredLifecycleRowReady`, `registeredLifecycleRowIndex`: carried
  lifecycle row evidence.
- `registeredRidValid`, `registeredLoadLsIdValid`: carried identity validity.
- `registeredOwnershipBundleReady`: carried RID, load-LSID, and lifecycle row
  are all present.
- `eligibleRegisteredMask`: registered full mask plus ready ownership bundle.
- `blockedByNoCapturedOwnership`, `blockedByMissingRid`,
  `blockedByMissingLoadLsId`, `blockedByMissingLifecycleRow`,
  `blockedByNotFullMask`: ownership-boundary blockers.

## Logic Design

R603 samples the same boundary event as R601/R602:

```text
active = enable && !flush
captureOwnership = active && capture
```

On capture, it registers the retained record `rid`, retained record
`loadLsId`, lifecycle row ready bit, and lifecycle row index. Disable or flush
clears the carried ownership bundle.

The registered mask is eligible only when the one-cycle-later R601 mask is
visible and the carried bundle is complete:

```text
registeredCandidate = active && registeredValid
registeredOwnershipBundleReady =
  capturedOwnershipValid &&
  registeredRid.valid &&
  registeredLoadLsId.valid &&
  registeredLifecycleRowReady

eligibleRegisteredMask =
  registeredCandidate &&
  registeredOwnershipBundleReady &&
  registeredFullMask
```

This is intentionally different from R602. R602 checks the live retained record
at the registered cycle and proves that it is gone. R603 carries the ownership
needed for a future registered-mask consumer.

## Integration Notes

The reduced top wires R603 immediately after R601/R602 and exports only
diagnostic booleans to the harness. The carried `registeredRid`,
`registeredLoadLsId`, and lifecycle row remain module outputs for the next owner
but are not consumed by live side-effect logic in R603.

R603 is the first positive registered-mask ownership packet after R602's
negative lifetime proof. It still does not establish that live physical guard
suppression is behaviorally safe; the next packet must define the exact guard
override timing and consume the carried bundle as an atomic ownership source.

## Verification

Focused module gate:

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2RetireRecordPhysicalBundleSuppressOwnershipBoundary
```

Affected top gate:

```bash
bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop
```

Probe-enabled generated-RTL/QEMU replay-LIQ gate:

```bash
LINXCORE_REPLAY_LIQ_EARLY_STA_ADDRESS=1 \
LINXCORE_REPLAY_LIQ_W2_COMPLETION_DELAY_CYCLES=12 \
LINXCORE_REPLAY_LIQ_RETAINED_OWNER_PHYSICAL_SUPPRESS_PROBE=1 \
FETCH_REPLAY_LIQ_REQUIRE_NONZERO=wait_replay_capture_accepted,replay_queue_out_fire,liq_alloc_accepted,lret_w2_slot_accepted,w2_promotion_live \
bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh \
  --fixture replay-ldi-sdi-ldi-sdi-ldi-ldi-loop \
  --build-dir generated/r603-replay-physical-suppress-ownership-enabled-xcheck \
  --expected-rows 18 --capture-rows 32 --max-seconds 10 \
  --reduced-store-replay-liq --disable-store-memory-mutation \
  --allow-residual-replay-liq-wait
```

Evidence:

```text
generated/r603-replay-physical-suppress-ownership-enabled-xcheck/report/crosscheck_manifest.json
status=pass compared=18 mismatches=0 cbstop_qemu=0 cbstop_dut=0

frontend_fetch_rf_alu_sideband_stats.json schema=v53
w2_retire_record_physical_bundle_suppress_identity_blocked_by_missing_record=5
w2_retire_record_physical_bundle_suppress_identity_eligible_registered_mask=0
w2_retire_record_physical_bundle_suppress_ownership_capture_ownership=5
w2_retire_record_physical_bundle_suppress_ownership_registered_candidate=5
w2_retire_record_physical_bundle_suppress_ownership_registered_rid_valid=144
w2_retire_record_physical_bundle_suppress_ownership_registered_load_lsid_valid=144
w2_retire_record_physical_bundle_suppress_ownership_registered_lifecycle_row_ready=144
w2_retire_record_physical_bundle_suppress_ownership_registered_bundle_ready=144
w2_retire_record_physical_bundle_suppress_ownership_eligible_registered_mask=5
w2_retire_record_physical_bundle_suppress_ownership_blocked_by_no_captured_ownership=0
w2_retire_record_physical_bundle_suppress_ownership_blocked_by_missing_rid=0
w2_retire_record_physical_bundle_suppress_ownership_blocked_by_missing_load_lsid=0
w2_retire_record_physical_bundle_suppress_ownership_blocked_by_missing_lifecycle_row=0
w2_retire_record_physical_bundle_suppress_ownership_blocked_by_not_full_mask=0
```

The repeated value `144` is the active-window count for a carried bundle that
remains valid after the first capture. The five registered candidates are the
important ownership-consumption opportunities.

## Next Owner

The next owner may consume `eligibleRegisteredMask` and the carried ownership
bundle as input evidence for a live physical guard override, but only as an
atomic bundle. Do not suppress ROB completion, RF writeback, wakeup, or
lifecycle clear independently.
