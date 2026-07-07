# LoadReplayReturnPipeW2RetireRecordPhysicalBundleSuppressBoundary

## Purpose

`LoadReplayReturnPipeW2RetireRecordPhysicalBundleSuppressBoundary` is the R601
registered boundary after the R600 physical-bundle suppression probe. It
captures an explicit full-bundle suppression selection into a one-cycle-later
mask so a later live owner can consume a non-current-cycle signal.

The boundary remains sideband-only. It does not drive the fallback guard
`maskPhysicalInputs` signals, ROB completion, RF writeback, wakeup, or
replay-row lifecycle clear. Its job is to prove that the R600-selected all-or-
none mask can cross a timing boundary before any live suppression is attempted.

## Source Mapping

- Chisel:
  `chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2RetireRecordPhysicalBundleSuppressBoundary.scala`
- Unit test:
  `chisel/src/test/scala/linxcore/lsu/LoadReplayReturnPipeW2RetireRecordPhysicalBundleSuppressBoundarySpec.scala`
- Top integration:
  `chisel/src/main/scala/linxcore/top/LinxCoreFrontendFetchRfAluTraceTop.scala`
- Upstream selector:
  `chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2RetireRecordPhysicalBundleSuppressProbe.scala`
- Harness sideband schema:
  `tools/chisel/frontend_fetch_rf_alu_trace_top_tb.cpp`
- Validator:
  `tools/chisel/validate_frontend_fetch_rf_alu_sideband_stats.py`

## Interface

Inputs:

- `enable`, `flush`: local active window.
- `selected`: R600 selected a physical suppression bundle.
- `selectedMask`: R600 selected mask.
- `allOrNoneInputMask`: R600 input-mask invariant.

Outputs:

- `capture`: current-cycle full-mask selection accepted by the boundary.
- `registeredValid`: previous-cycle `capture` is visible.
- `registeredMask`: previous-cycle selected mask, or zero.
- `registeredFullMask`: registered mask is valid and equals `0xf`.
- `blockedByNoSelection`: active cycle without an R600 selection.
- `blockedByPartialMask`: R600 selected but did not provide an all-or-none
  mask.
- `clearedByFlush`: flush clears a previously registered mask.

## Logic Design

The capture condition accepts only a complete R600 selection:

```text
active = enable && !flush
capture =
  active &&
  selected &&
  allOrNoneInputMask &&
  selectedMask == 0xf
```

The state is a one-cycle register:

```text
when !enable || flush:
  registeredValid = false
  registeredMask = 0
otherwise:
  registeredValid = capture
  registeredMask = capture ? selectedMask : 0
```

This gives the next live owner a mask that is no longer produced by the same
current-cycle physical duplicate guards it may later suppress.

## Integration Notes

The reduced top wires this module immediately after
`LoadReplayReturnPipeW2RetireRecordPhysicalBundleSuppressProbe`. The explicit
probe knob remains `LINXCORE_REPLAY_LIQ_RETAINED_OWNER_PHYSICAL_SUPPRESS_PROBE`.
R601 adds no new knob and no live mutation path.

Do not connect `registeredMask` to the fallback guard mask inputs in the same
packet that first consumes it. The next packet should first define ownership
and lifetime rules for using the registered mask, including how it aligns with
retained record identity after W2 has advanced.

## Verification

Focused module gate:

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2RetireRecordPhysicalBundleSuppressBoundary
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
  --build-dir generated/r601-replay-physical-suppress-boundary-enabled-xcheck \
  --expected-rows 18 --capture-rows 32 --max-seconds 10 \
  --reduced-store-replay-liq --disable-store-memory-mutation \
  --allow-residual-replay-liq-wait
```

Evidence:

```text
generated/r601-replay-physical-suppress-boundary-enabled-xcheck/report/crosscheck_manifest.json
status=pass comparator_status=0 compared=18 mismatches=0 cbstop_qemu=0 cbstop_dut=0

frontend_fetch_rf_alu_sideband_stats.json schema=v51
w2_retire_record_physical_bundle_suppress_probe_candidate=5
w2_retire_record_physical_bundle_suppress_probe_selected=5
w2_retire_record_physical_bundle_suppress_probe_selected_mask_sum=75
w2_retire_record_physical_bundle_suppress_boundary_capture=5
w2_retire_record_physical_bundle_suppress_boundary_registered_valid=5
w2_retire_record_physical_bundle_suppress_boundary_registered_full_mask=5
w2_retire_record_physical_bundle_suppress_boundary_registered_mask_sum=75
w2_retire_record_physical_bundle_suppress_boundary_blocked_by_no_selection=189
w2_retire_record_physical_bundle_suppress_boundary_blocked_by_partial_mask=0
w2_retire_record_physical_bundle_suppress_boundary_cleared_by_flush=0
```

The nonzero no-selection count is idle-cycle diagnostic activity. The five
valid R600 selections each become a registered mask of `0xf`, giving a mask sum
of `75`.

## Next Owner

The next owner may use the registered mask as input evidence for a live
suppression design, but it must first prove retained-record identity and
lifetime alignment for the next-cycle mask. Do not consume the R601 mask as a
current-cycle duplicate guard override.
