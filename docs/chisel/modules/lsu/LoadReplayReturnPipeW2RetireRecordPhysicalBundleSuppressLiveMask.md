# LoadReplayReturnPipeW2RetireRecordPhysicalBundleSuppressLiveMask

## Purpose

`LoadReplayReturnPipeW2RetireRecordPhysicalBundleSuppressLiveMask` is the R604
consumer after the R603 carried-ownership boundary. R603 proves that the
registered physical suppression mask has an explicit capture-time RID,
load-LSID, and lifecycle row even after the live retained record has cleared.
R604 turns that eligible registered ownership candidate into a default-off
all-or-none live mask for duplicate physical replay-return side effects.

The module never emits a partial mask. ROB completion, RF writeback, wakeup, and
replay-row lifecycle clear are either all suppressed or all left untouched.

## Source Mapping

- Chisel:
  `chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2RetireRecordPhysicalBundleSuppressLiveMask.scala`
- Unit test:
  `chisel/src/test/scala/linxcore/lsu/LoadReplayReturnPipeW2RetireRecordPhysicalBundleSuppressLiveMaskSpec.scala`
- Top integration:
  `chisel/src/main/scala/linxcore/top/LinxCoreFrontendFetchRfAluTraceTop.scala`
- Upstream ownership boundary:
  `chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2RetireRecordPhysicalBundleSuppressOwnershipBoundary.scala`
- Harness sideband schema:
  `tools/chisel/frontend_fetch_rf_alu_trace_top_tb.cpp`
- Validator:
  `tools/chisel/validate_frontend_fetch_rf_alu_sideband_stats.py`

## Model Evidence

The relevant model ownership path is unchanged from R603:

- `LDQInfo::returnData` in
  `/Users/zhoubot/linx-isa/model/LinxCoreModel/model/lsu/load_unit/ldq.cpp`
  produces the load-return data and wakeup side effects for one load identity.
- `LDQInfo::retire` in the same file clears the resolved LDQ row by commit and
  load identity.
- `IEX::setMemWakeup` and `IEX::setMemData` in
  `/Users/zhoubot/linx-isa/model/LinxCoreModel/model/iex/iex.cpp` consume the
  returned memory side effects.

R604 uses the R603 carried bundle as the ownership proof for a registered
duplicate-physical suppression opportunity. It does not re-read the already
cleared retained record.

## Interface

Inputs:

- `enable`, `flush`: local active window.
- `liveMaskEnable`: default-off top-level knob.
- `eligibleRegisteredMask`: R603 ownership-qualified registered mask.

Outputs:

- `maskCandidate`: active ownership-qualified candidate exists.
- `liveMaskCandidate`: candidate selected by the live-mask knob.
- `suppressRobComplete`, `suppressRfWriteback`, `suppressWakeup`,
  `suppressLifecycleClear`: all-four duplicate physical side-effect mask.
- `suppressMask`: packed `{lifecycle, wakeup, rf, rob}` mask.
- `allOrNoneMask`: true for idle `0x0` or selected `0xf`.
- `blockedByLiveMaskDisabled`: ownership candidate was present while the
  default-off live-mask knob was disabled.
- `blockedByNoEligibleOwnership`: active cycle without an R603 candidate.

## Logic Design

```text
active = enable && !flush
maskCandidate = active && eligibleRegisteredMask
liveMaskCandidate = maskCandidate && liveMaskEnable
suppressMask = liveMaskCandidate ? 0xf : 0x0
```

The reduced top wires each bit to a live consumer boundary:

- bit 0 masks replay ROB completion before `ReducedRobCompletionArbiter`.
- bit 1 masks replay RF writeback before `ReducedScalarWritebackArbiter`.
- bit 2 masks replay wakeup before `LoadReplayReturnPipeW2WakeupArbiterInput`.
- bit 3 masks replay-row lifecycle clear before `LoadReplayReturnPipeW2ReplayRowClearRequest`.

The current-cycle duplicate detectors remain unchanged, so R599-R603 evidence is
not made circular.

R608 inserts
`LoadReplayReturnPipeW2RetireRecordPhysicalBundleSuppressSelect` ahead of the
registered boundary. The selector keeps the R600 diagnostic probe path intact,
but also allows `LINXCORE_REPLAY_LIQ_RETAINED_OWNER_PHYSICAL_SUPPRESS_PROMOTE=1`
to feed the same R601/R603/R604 boundary from the R599 full-bundle plan while
`LINXCORE_REPLAY_LIQ_RETAINED_OWNER_PHYSICAL_SUPPRESS_PROBE` remains disabled.
The live mask still consumes only the carried ownership-qualified registered
candidate and still emits only `0x0` or `0xf`.

## Verification

Focused module gate:

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2RetireRecordPhysicalBundleSuppressLiveMask
```

Affected top gate:

```bash
bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop
```

Generated-RTL/QEMU R604 gate:

```bash
LINXCORE_REPLAY_LIQ_EARLY_STA_ADDRESS=1 \
LINXCORE_REPLAY_LIQ_W2_COMPLETION_DELAY_CYCLES=12 \
LINXCORE_REPLAY_LIQ_RETAINED_OWNER_PHYSICAL_SUPPRESS_PROBE=1 \
LINXCORE_REPLAY_LIQ_RETAINED_OWNER_PHYSICAL_SUPPRESS_LIVE_MASK=1 \
FETCH_REPLAY_LIQ_REQUIRE_NONZERO=wait_replay_capture_accepted,replay_queue_out_fire,liq_alloc_accepted,lret_w2_slot_accepted,w2_promotion_live \
bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh \
  --fixture replay-ldi-sdi-ldi-sdi-ldi-ldi-loop \
  --build-dir generated/r604-replay-physical-suppress-live-mask-xcheck \
  --expected-rows 18 --capture-rows 32 --max-seconds 10 \
  --reduced-store-replay-liq --disable-store-memory-mutation \
  --allow-residual-replay-liq-wait
```

Evidence:

```text
generated/r604-replay-physical-suppress-live-mask-xcheck/report/crosscheck_manifest.json
status=pass comparator_status=0 compared_rows=18 mismatch_count=0
cbstop_qemu=0 cbstop_dut=0

frontend_fetch_rf_alu_sideband_stats.json schema=v54
w2_retire_record_physical_bundle_suppress_ownership_eligible_registered_mask=5
w2_retire_record_physical_bundle_suppress_live_mask_candidate=5
w2_retire_record_physical_bundle_suppress_live_mask_enabled_candidate=5
w2_retire_record_physical_bundle_suppress_live_mask_suppress_rob_complete=5
w2_retire_record_physical_bundle_suppress_live_mask_suppress_rf_writeback=5
w2_retire_record_physical_bundle_suppress_live_mask_suppress_wakeup=5
w2_retire_record_physical_bundle_suppress_live_mask_suppress_lifecycle_clear=5
w2_retire_record_physical_bundle_suppress_live_mask_suppress_mask_sum=75
w2_retire_record_physical_bundle_suppress_live_mask_blocked_by_live_mask_disabled=0
w2_retire_record_physical_bundle_suppress_ownership_blocked_by_no_captured_ownership=0
w2_retire_record_physical_bundle_suppress_ownership_blocked_by_missing_rid=0
w2_retire_record_physical_bundle_suppress_ownership_blocked_by_missing_load_lsid=0
w2_retire_record_physical_bundle_suppress_ownership_blocked_by_missing_lifecycle_row=0
w2_retire_record_physical_bundle_suppress_ownership_blocked_by_not_full_mask=0
```

The mask sum equals `5 * 0xf`, proving that every selected candidate used the
full four-bit bundle.

## Next Owner

If R604 generated-RTL/QEMU evidence passes with nonzero live-mask counters and
zero comparator mismatches, the next packet should inspect whether physical
fire-complete and W2 clear timing still matches the model after duplicate
side-effect suppression. Do not promote this path as full replacement evidence
until broader QEMU/CoreMark windows prove no delayed replay-row leak.
