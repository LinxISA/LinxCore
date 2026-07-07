# LoadReplayReturnPipeW2RetireRecordPhysicalBundleSuppressIdentityProof

## Purpose

`LoadReplayReturnPipeW2RetireRecordPhysicalBundleSuppressIdentityProof` is the
R602 sideband owner after the R601 registered physical-bundle suppression
boundary. It records the retained record identity present when R601 captures a
full physical suppression mask, then checks whether the one-cycle-later
registered mask still lines up with the live retained record and replay-row
lifecycle evidence.

The module is diagnostic-only. It does not drive physical guard masks, ROB
completion, RF writeback, wakeup, replay-row lifecycle clear, or replay
admission. The R602 generated run is intentionally recorded as negative
evidence: the registered mask exists, but the retained record is no longer
valid at the registered-mask candidate cycle.

## Source Mapping

- Chisel:
  `chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2RetireRecordPhysicalBundleSuppressIdentityProof.scala`
- Unit test:
  `chisel/src/test/scala/linxcore/lsu/LoadReplayReturnPipeW2RetireRecordPhysicalBundleSuppressIdentityProofSpec.scala`
- Top integration:
  `chisel/src/main/scala/linxcore/top/LinxCoreFrontendFetchRfAluTraceTop.scala`
- Upstream boundary:
  `chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2RetireRecordPhysicalBundleSuppressBoundary.scala`
- Harness sideband schema:
  `tools/chisel/frontend_fetch_rf_alu_trace_top_tb.cpp`
- Validator:
  `tools/chisel/validate_frontend_fetch_rf_alu_sideband_stats.py`

## Model Evidence

The C++ model treats the load-return record as a row-identity operation:

- `LDQInfo::returnData` in
  `/Users/zhoubot/linx-isa/model/LinxCoreModel/model/lsu/load_unit/ldq.cpp`
  writes the returned load data, produces the LRET side effect, wakes dependent
  instructions through IEX, and marks the LDQ entry resolved.
- `LDQInfo::retire` in the same file clears resolved LDQ entries by the
  commit/order identity (`bid`, `gid`, and `lsID`).
- `IEX::setMemWakeup` and `IEX::setMemData` in
  `/Users/zhoubot/linx-isa/model/LinxCoreModel/model/iex/iex.cpp` consume the
  memory result identity, including `rid`, for wakeup and data side effects.

R602 therefore checks both retained ROB identity (`rid`) and load-LSID identity
against the lifecycle row before any live physical suppression can be
considered.

## Interface

Inputs:

- `enable`, `flush`: local active window.
- `capture`: R601 accepted a full physical suppression mask.
- `captureRid`, `captureLoadLsId`: retained record identity sampled at capture.
- `captureLifecycleRowReady`, `captureLifecycleRowIndex`: lifecycle row
  evidence sampled at capture.
- `registeredValid`, `registeredFullMask`: one-cycle-later R601 mask state.
- `recordValid`, `recordRid`, `recordLoadLsId`: current retained record state.
- `lifecycleEvidenceProviderValid`, `lifecycleEvidenceRowClearIndex`: current
  lifecycle row evidence.

Outputs:

- `captureIdentity`: capture-cycle identity sample.
- `capturedIdentityValid`: stored identity is valid in the active window.
- `registeredCandidate`: R601 registered mask is visible.
- `retainedRecordAligned`: registered candidate matches the live retained
  record `rid` and `loadLsId`.
- `lifecycleRowAligned`: retained record alignment also matches lifecycle row
  evidence.
- `identityLifetimeAligned`: retained record and lifecycle alignment are both
  true.
- `eligibleRegisteredMask`: identity/lifetime alignment plus full registered
  mask.
- `blockedByNoCapturedIdentity`, `blockedByMissingRecord`,
  `blockedByRidMismatch`, `blockedByLoadLsIdMismatch`,
  `blockedByMissingLifecycleEvidence`, `blockedByLifecycleRowMismatch`,
  `blockedByNotFullMask`: mutually targeted blocker counters for generated
  sideband evidence.

## Logic Design

R602 stores identity only when R601 captures a full-mask boundary event:

```text
active = enable && !flush
captureIdentity = active && capture
```

The captured `rid`, `loadLsId`, lifecycle-ready bit, and lifecycle row index are
cleared on disable or flush and otherwise retained until a later capture
overwrites them.

The registered candidate is then checked against the current retained record:

```text
registeredCandidate = active && registeredValid
retainedRecordAligned =
  registeredCandidate &&
  capturedIdentityValid &&
  recordValid &&
  capturedRid == recordRid &&
  capturedLoadLsId == recordLoadLsId
```

Lifecycle alignment is intentionally downstream of retained-record alignment:

```text
lifecycleRowAligned =
  retainedRecordAligned &&
  capturedLifecycleReady &&
  lifecycleEvidenceProviderValid &&
  capturedLifecycleRowIndex == lifecycleEvidenceRowClearIndex

eligibleRegisteredMask = lifecycleRowAligned && registeredFullMask
```

This ordering prevents a future owner from citing a registered physical mask
when the retained record that produced the mask is already gone.

## Integration Notes

The reduced top wires R602 immediately after
`LoadReplayReturnPipeW2RetireRecordPhysicalBundleSuppressBoundary`. The proof
uses the existing retained record and lifecycle-evidence latch outputs; it does
not add a runtime knob.

The R602 generated replay-LIQ run proves that the R601 register boundary is not
yet a valid live suppression source. The registered mask appears one cycle
after capture, but `retireRecord.io.recordValid` is already false at that point.
The next owner must either carry enough identity/lifecycle state across the
boundary or adjust the lifetime/consumption point before enabling live guard
suppression.

## Verification

Focused module gate:

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2RetireRecordPhysicalBundleSuppressIdentityProof
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
  --build-dir generated/r602-replay-physical-suppress-identity-enabled-xcheck \
  --expected-rows 18 --capture-rows 32 --max-seconds 10 \
  --reduced-store-replay-liq --disable-store-memory-mutation \
  --allow-residual-replay-liq-wait
```

Evidence:

```text
generated/r602-replay-physical-suppress-identity-enabled-xcheck/report/crosscheck_manifest.json
status=pass comparator_status=0 compared=18 mismatches=0 cbstop_qemu=0 cbstop_dut=0

frontend_fetch_rf_alu_sideband_stats.json schema=v52
w2_retire_record_physical_bundle_suppress_boundary_capture=5
w2_retire_record_physical_bundle_suppress_boundary_registered_valid=5
w2_retire_record_physical_bundle_suppress_boundary_registered_full_mask=5
w2_retire_record_physical_bundle_suppress_identity_capture_identity=5
w2_retire_record_physical_bundle_suppress_identity_captured_identity_valid=144
w2_retire_record_physical_bundle_suppress_identity_registered_candidate=5
w2_retire_record_physical_bundle_suppress_identity_blocked_by_missing_record=5
w2_retire_record_physical_bundle_suppress_identity_retained_record_aligned=0
w2_retire_record_physical_bundle_suppress_identity_lifecycle_row_aligned=0
w2_retire_record_physical_bundle_suppress_identity_lifetime_aligned=0
w2_retire_record_physical_bundle_suppress_identity_eligible_registered_mask=0
w2_retire_record_physical_bundle_suppress_identity_blocked_by_rid_mismatch=0
w2_retire_record_physical_bundle_suppress_identity_blocked_by_load_lsid_mismatch=0
w2_retire_record_physical_bundle_suppress_identity_blocked_by_missing_lifecycle_evidence=0
w2_retire_record_physical_bundle_suppress_identity_blocked_by_lifecycle_row_mismatch=0
w2_retire_record_physical_bundle_suppress_identity_blocked_by_not_full_mask=0
```

The five registered candidates are all blocked by missing retained record
lifetime. This is a design handoff, not an implementation failure.

## Next Owner

R603 should make the boundary ownership explicit before live suppression. Two
valid directions are available:

- carry a registered identity/lifecycle proof bundle with the R601 mask and
  consume that bundle instead of the already-cleared retained record, or
- move the physical suppression consumption point earlier while still avoiding
  the same-cycle circular guard boundary that R601 was created to avoid.

Do not connect the R601/R602 mask to physical guard suppression until the next
owner proves a nonzero `eligibleRegisteredMask` or replaces it with an
equivalent, documented ownership predicate.
