# LoadReplayReturnPipeW2RetireRecordPhysicalBundleSuppressProbe

## Purpose

`LoadReplayReturnPipeW2RetireRecordPhysicalBundleSuppressProbe` is the R600
sideband owner after the R599 physical-bundle suppression plan. It consumes the
R599 all-or-none suppression intent and reports whether an explicit default-off
probe would select the complete physical ROB/RF/wakeup/lifecycle bundle.

The probe is intentionally sideband-only. It does not feed the selected mask
back into ROB completion, RF writeback, wakeup, replay-row lifecycle clear, or
the fallback duplicate guards. Feeding the R599 mask into those same guard
families in this packet would create a circular current-cycle ownership
boundary: the evidence used to prove the mask would be hidden by the mask.

## Source Mapping

- Chisel:
  `chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2RetireRecordPhysicalBundleSuppressProbe.scala`
- Unit test:
  `chisel/src/test/scala/linxcore/lsu/LoadReplayReturnPipeW2RetireRecordPhysicalBundleSuppressProbeSpec.scala`
- Top integration:
  `chisel/src/main/scala/linxcore/top/LinxCoreFrontendFetchRfAluTraceTop.scala`
- Reduced emitted-top knob:
  `LINXCORE_REPLAY_LIQ_RETAINED_OWNER_PHYSICAL_SUPPRESS_PROBE`
- Harness sideband schema:
  `tools/chisel/frontend_fetch_rf_alu_trace_top_tb.cpp`
- Validator:
  `tools/chisel/validate_frontend_fetch_rf_alu_sideband_stats.py`
- Model-order source:
  `model/LinxCoreModel/model/lsu/load_unit/ldq.cpp`: `LDQInfo::returnData`,
  `LDQInfo::retire`

## Interface

Inputs:

- `enable`, `flush`: local active window.
- `probeEnable`: explicit reduced-top probe arm.
- `atomicSuppressCandidate`: R599 complete physical suppression candidate.
- `suppressRobComplete`, `suppressRfWriteback`, `suppressWakeup`,
  `suppressLifecycleClear`: the R599 all-or-none suppression intents.

Outputs:

- `active`: module is enabled and not flushed.
- `suppressCandidate`: active R599 atomic suppression candidate.
- `probeEnabledCandidate`: candidate with the explicit probe arm set.
- `selected`: the probe selected the complete physical suppression bundle.
- `selectedMask`: the selected four-bit mask, or zero when not selected.
- `allOrNoneInputMask`: the input mask is either `0` or `0xf`.
- `blockedByProbeDisabled`: candidate was present but the probe knob was off.
- `blockedByNoAtomicCandidate`: probe was enabled during an active non-candidate
  cycle.
- `blockedByPartialMask`: probe was enabled for a candidate but the input mask
  was partial.

Mask bit order is `{lifecycle, wakeup, rf, rob}`.

## Logic Design

The module first constructs the R599 input mask and classifies it:

```text
active = enable && !flush
inputMask = {suppressLifecycleClear, suppressWakeup, suppressRfWriteback, suppressRobComplete}
allOrNoneInputMask = inputMask == 0 || inputMask == 0xf
```

Selection requires the active R599 candidate, the explicit probe arm, and the
complete all-or-none mask:

```text
suppressCandidate = active && atomicSuppressCandidate
probeEnabledCandidate = suppressCandidate && probeEnable
selected = probeEnabledCandidate && allOrNoneInputMask && inputMask == 0xf
selectedMask = selected ? inputMask : 0
```

The blocker outputs deliberately distinguish a disabled probe from a missing
candidate and from a partial mask. This keeps the next owner from citing a
default-off run as a live-suppression proof.

## Integration Notes

The reduced top wires this owner after
`LoadReplayReturnPipeW2RetireRecordPhysicalBundleSuppressPlan`. The default
generated path leaves `probeEnable=false`, so R600 must report candidates
blocked by the disabled probe and zero selected masks. The explicit probe path
sets `LINXCORE_REPLAY_LIQ_RETAINED_OWNER_PHYSICAL_SUPPRESS_PROBE=1`, so the
same R599 candidates must become selected full-bundle masks without changing
the QEMU/RTL architectural comparison.

This owner is not wired into `maskPhysicalInputs`, the existing fallback guard
inputs, or any live sink. A live suppression packet must introduce a
non-circular timing or ownership boundary before it can consume `selectedMask`.

## Verification

Focused module gate:

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2RetireRecordPhysicalBundleSuppressProbe
```

Affected top gate:

```bash
bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop
```

Default generated-RTL/QEMU replay-LIQ gate:

```bash
LINXCORE_REPLAY_LIQ_EARLY_STA_ADDRESS=1 \
LINXCORE_REPLAY_LIQ_W2_COMPLETION_DELAY_CYCLES=12 \
FETCH_REPLAY_LIQ_REQUIRE_NONZERO=wait_replay_capture_accepted,replay_queue_out_fire,liq_alloc_accepted,lret_w2_slot_accepted,w2_promotion_live \
bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh \
  --fixture replay-ldi-sdi-ldi-sdi-ldi-ldi-loop \
  --build-dir generated/r600-replay-physical-suppress-probe-default-xcheck \
  --expected-rows 18 --capture-rows 32 --max-seconds 10 \
  --reduced-store-replay-liq --disable-store-memory-mutation \
  --allow-residual-replay-liq-wait
```

Default evidence:

```text
generated/r600-replay-physical-suppress-probe-default-xcheck/report/crosscheck_manifest.json
status=pass compared_rows=18 mismatch_count=0 qemu_cbstop=0 dut_cbstop=0

frontend_fetch_rf_alu_sideband_stats.json schema=v50
w2_retire_record_physical_bundle_suppress_plan_atomic_suppress_candidate=5
w2_retire_record_physical_bundle_suppress_probe_candidate=5
w2_retire_record_physical_bundle_suppress_probe_enabled_candidate=0
w2_retire_record_physical_bundle_suppress_probe_selected=0
w2_retire_record_physical_bundle_suppress_probe_selected_mask_sum=0
w2_retire_record_physical_bundle_suppress_probe_all_or_none_input_mask=195
w2_retire_record_physical_bundle_suppress_probe_blocked_by_probe_disabled=5
w2_retire_record_physical_bundle_suppress_probe_blocked_by_no_atomic_candidate=0
w2_retire_record_physical_bundle_suppress_probe_blocked_by_partial_mask=0
```

Probe-enabled generated-RTL/QEMU replay-LIQ gate:

```bash
LINXCORE_REPLAY_LIQ_EARLY_STA_ADDRESS=1 \
LINXCORE_REPLAY_LIQ_W2_COMPLETION_DELAY_CYCLES=12 \
LINXCORE_REPLAY_LIQ_RETAINED_OWNER_PHYSICAL_SUPPRESS_PROBE=1 \
FETCH_REPLAY_LIQ_REQUIRE_NONZERO=wait_replay_capture_accepted,replay_queue_out_fire,liq_alloc_accepted,lret_w2_slot_accepted,w2_promotion_live \
bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh \
  --fixture replay-ldi-sdi-ldi-sdi-ldi-ldi-loop \
  --build-dir generated/r600-replay-physical-suppress-probe-enabled-xcheck \
  --expected-rows 18 --capture-rows 32 --max-seconds 10 \
  --reduced-store-replay-liq --disable-store-memory-mutation \
  --allow-residual-replay-liq-wait
```

Probe-enabled evidence:

```text
generated/r600-replay-physical-suppress-probe-enabled-xcheck/report/crosscheck_manifest.json
status=pass compared_rows=18 mismatch_count=0 qemu_cbstop=0 dut_cbstop=0

frontend_fetch_rf_alu_sideband_stats.json schema=v50
w2_retire_record_physical_bundle_suppress_plan_atomic_suppress_candidate=5
w2_retire_record_physical_bundle_suppress_probe_candidate=5
w2_retire_record_physical_bundle_suppress_probe_enabled_candidate=5
w2_retire_record_physical_bundle_suppress_probe_selected=5
w2_retire_record_physical_bundle_suppress_probe_selected_mask_sum=75
w2_retire_record_physical_bundle_suppress_probe_all_or_none_input_mask=195
w2_retire_record_physical_bundle_suppress_probe_blocked_by_probe_disabled=0
w2_retire_record_physical_bundle_suppress_probe_blocked_by_no_atomic_candidate=189
w2_retire_record_physical_bundle_suppress_probe_blocked_by_partial_mask=0
```

The nonzero no-atomic-candidate count in the probe-enabled run is idle-cycle
diagnostic activity. The five valid retained atomic candidates each select mask
`0xf`, giving a selected-mask sum of `75`.

## Next Owner

R601 adds
`LoadReplayReturnPipeW2RetireRecordPhysicalBundleSuppressBoundary`, a
sideband-only registered owner that captures the R600 full-mask selection into
a one-cycle-later mask. Live physical suppression is still deferred. The next
owner must prove retained-record identity and lifetime alignment for the
registered mask before connecting it to any physical guard or live sink.
