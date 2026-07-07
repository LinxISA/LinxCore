# LoadReplayReturnPipeW2RetireRecordPhysicalBundleSuppressSelect

## Purpose

`LoadReplayReturnPipeW2RetireRecordPhysicalBundleSuppressSelect` is the R608
control selector between the R600 diagnostic probe path and the promoted R599
physical-bundle suppress plan. It lets the reduced replay-retire path feed the
R601/R603/R604 ownership boundary from a default-off promoted plan while the
diagnostic probe remains disabled.

The selector preserves the all-or-none contract. It can emit only an idle mask
`0x0` or a full physical bundle mask `0xf`; partial ROB/RF/wakeup/lifecycle
suppression is blocked before the registered ownership boundary.

## Source Mapping

- Chisel:
  `chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2RetireRecordPhysicalBundleSuppressSelect.scala`
- Unit test:
  `chisel/src/test/scala/linxcore/lsu/LoadReplayReturnPipeW2RetireRecordPhysicalBundleSuppressSelectSpec.scala`
- Top integration:
  `chisel/src/main/scala/linxcore/top/LinxCoreFrontendFetchRfAluTraceTop.scala`
- Reduced emitted-top knob:
  `LINXCORE_REPLAY_LIQ_RETAINED_OWNER_PHYSICAL_SUPPRESS_PROMOTE`
- Existing live-mask knob:
  `LINXCORE_REPLAY_LIQ_RETAINED_OWNER_PHYSICAL_SUPPRESS_LIVE_MASK`
- Harness sideband schema:
  `tools/chisel/frontend_fetch_rf_alu_trace_top_tb.cpp`
- Validator:
  `tools/chisel/validate_frontend_fetch_rf_alu_sideband_stats.py`

## Model Evidence

The selector is based on the LinxCoreModel returned-load W2 ownership order:

- `LDAPipe::Work()` runs W2 before earlier stages, and `LDAPipe::move()` moves
  `w1_inst` into `w2_inst`, in
  `/Users/zhoubot/linx-isa/model/LinxCoreModel/model/iex/pipe/lda_pipe.cpp`.
- `LDAPipe::runW2` and `AGUPipe::runW2` publish one resident returned-load
  instruction to RF writeback, PE resolve, and scalar wakeup.
- `LDQInfo::returnData` in
  `/Users/zhoubot/linx-isa/model/LinxCoreModel/model/lsu/load_unit/ldq.cpp`
  marks the load row resolved, writes the return bus, and calls
  `iex->setMemWakeup(bus)` when the returned load is wakeup-visible.
- `IEX::setMemData` in
  `/Users/zhoubot/linx-isa/model/LinxCoreModel/model/iex/iex.cpp` resolves the
  ROB lane, writes destination data, performs load-branch resolve, and inserts
  the instruction into an E4 LDA/AGU slot.
- `LDQInfo::retire` clears resolved LDQ rows by commit bus and load identity.

Together these sources make returned-load W2 ownership a resident-instruction
bundle: RF writeback, ROB/PE resolve, wakeup, and row lifecycle move together.
R608 therefore promotes only the full R599 bundle and feeds the existing
registered ownership boundary; it does not create a separate live mask for any
single side effect.

## Interface

Inputs:

- `enable`, `flush`: local active window.
- `promoteEnable`: default-off promoted-control knob.
- `planAtomicSuppressCandidate`: R599 complete physical bundle candidate.
- `planSuppressMask`: R599 `{lifecycle, wakeup, rf, rob}` mask.
- `planAllOrNoneSuppress`: R599 all-or-none invariant.
- `probeSelected`: existing R600 probe selection.
- `probeSelectedMask`: R600 selected mask.
- `probeAllOrNoneInputMask`: R600 input-mask invariant.

Outputs:

- `planPromotedCandidate`: promoted plan selected a full bundle.
- `selected`: either the existing probe path or the promoted plan path selected.
- `selectedMask`: full `0xf` mask when selected, otherwise `0x0`.
- `allOrNoneInputMask`: output mask invariant for the boundary.
- `selectedFromProbe`: diagnostic probe supplied the selection.
- `selectedFromPromotion`: promoted plan supplied the selection.
- `blockedByPromoteDisabled`: a plan candidate existed while promotion was off.
- `blockedByNoPlanCandidate`: promotion was armed without a plan candidate.
- `blockedByPartialPlanMask`: promotion was armed but the plan mask was not a
  complete all-or-none bundle.
- `invalidProbePromotionMaskMismatch`: diagnostic mismatch when both paths are
  active but their masks differ.

## Logic Design

```text
active = enable && !flush
planCandidate = active && planAtomicSuppressCandidate
planFullMask = planAllOrNoneSuppress && planSuppressMask == 0xf
planPromotedCandidate = planCandidate && promoteEnable && planFullMask

probeFullCandidate =
  active && probeSelected && probeAllOrNoneInputMask && probeSelectedMask == 0xf

selected = probeFullCandidate || planPromotedCandidate
selectedMask = selected ? 0xf : 0x0
```

The top wires this selector before
`LoadReplayReturnPipeW2RetireRecordPhysicalBundleSuppressBoundary`. R600 can
still drive the boundary for diagnostic probe runs. R608 additionally allows
the R599 plan to drive the same boundary when
`LINXCORE_REPLAY_LIQ_RETAINED_OWNER_PHYSICAL_SUPPRESS_PROMOTE=1`, even when
`LINXCORE_REPLAY_LIQ_RETAINED_OWNER_PHYSICAL_SUPPRESS_PROBE` is left unset.

R610 exposes the selector outputs as top-level sidebands and harness counters:

- `w2_retire_record_physical_bundle_suppress_select_plan_promoted_candidate`
- `w2_retire_record_physical_bundle_suppress_select_selected`
- `w2_retire_record_physical_bundle_suppress_select_selected_mask_sum`
- `w2_retire_record_physical_bundle_suppress_select_all_or_none_input_mask`
- `w2_retire_record_physical_bundle_suppress_select_selected_from_probe`
- `w2_retire_record_physical_bundle_suppress_select_selected_from_promotion`
- `w2_retire_record_physical_bundle_suppress_select_blocked_by_promote_disabled`
- `w2_retire_record_physical_bundle_suppress_select_blocked_by_no_plan_candidate`
- `w2_retire_record_physical_bundle_suppress_select_blocked_by_partial_plan_mask`
- `w2_retire_record_physical_bundle_suppress_select_invalid_probe_promotion_mask_mismatch`

These counters make long-window reports self-identifying: a boundary capture can
be attributed directly to the promoted plan or the diagnostic probe instead of
being inferred from adjacent probe and boundary counters.

## Verification

Focused module gate:

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2RetireRecordPhysicalBundleSuppressSelect
```

Affected gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2RetireRecordPhysicalBundleSuppressLiveMask
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2RetireRecordPhysicalBundleSuppressClearProof
bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop
```

Promoted generated-RTL/QEMU replay-LIQ gate:

```bash
LINXCORE_REPLAY_LIQ_EARLY_STA_ADDRESS=1 \
LINXCORE_REPLAY_LIQ_W2_COMPLETION_DELAY_CYCLES=12 \
LINXCORE_REPLAY_LIQ_RETAINED_OWNER_PHYSICAL_SUPPRESS_PROMOTE=1 \
LINXCORE_REPLAY_LIQ_RETAINED_OWNER_PHYSICAL_SUPPRESS_LIVE_MASK=1 \
FETCH_REPLAY_LIQ_REQUIRE_NONZERO=wait_replay_capture_accepted,replay_queue_out_fire,liq_alloc_accepted,lret_w2_slot_accepted,w2_promotion_live,w2_retire_record_physical_bundle_suppress_plan_atomic_suppress_candidate,w2_retire_record_physical_bundle_suppress_select_selected_from_promotion,w2_retire_record_physical_bundle_suppress_boundary_capture,w2_retire_record_physical_bundle_suppress_ownership_eligible_registered_mask,w2_retire_record_physical_bundle_suppress_live_mask_enabled_candidate,w2_retire_record_physical_bundle_suppress_clear_proof_all_clear_aligned \
bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh \
  --fixture replay-ldi-sdi-ldi-sdi-ldi-ldi-loop \
  --build-dir generated/r610-replay-physical-suppress-selector-origin-xcheck \
  --expected-rows 18 --capture-rows 32 --max-seconds 10 \
  --reduced-store-replay-liq --disable-store-memory-mutation \
  --allow-residual-replay-liq-wait
```

Evidence:

```text
generated/r608-replay-physical-suppress-promoted-select-xcheck/report/crosscheck_manifest.json
status=pass compared_rows=18 mismatch_count=0 qemu_cbstop=0 dut_cbstop=0

frontend_fetch_rf_alu_sideband_stats.json
w2_retire_record_physical_bundle_suppress_plan_atomic_suppress_candidate=5
w2_retire_record_physical_bundle_suppress_select_plan_promoted_candidate=5
w2_retire_record_physical_bundle_suppress_select_selected=5
w2_retire_record_physical_bundle_suppress_select_selected_from_probe=0
w2_retire_record_physical_bundle_suppress_select_selected_from_promotion=5
w2_retire_record_physical_bundle_suppress_select_invalid_probe_promotion_mask_mismatch=0
w2_retire_record_physical_bundle_suppress_boundary_capture=5
w2_retire_record_physical_bundle_suppress_ownership_eligible_registered_mask=5
w2_retire_record_physical_bundle_suppress_live_mask_enabled_candidate=5
w2_retire_record_physical_bundle_suppress_clear_proof_all_clear_aligned=5
```

The nonzero `selected_from_promotion` counter and zero `selected_from_probe`
counter prove the registered boundary was fed by the promoted plan path, not by
the diagnostic probe. The five boundary captures then propagate through the
existing carried ownership, live mask, and clear-proof evidence with zero
QEMU/DUT mismatches.

## Next Owner

R610 promotes only the replay fixture path under explicit default-off knobs and
adds auditability for future long-window runs. The next packet should either
find a later CoreMark/direct-boot window with natural replay-LIQ returned-load
phasing, or use the selector-origin counters when running broader
promotion-enabled no-regression coverage.
