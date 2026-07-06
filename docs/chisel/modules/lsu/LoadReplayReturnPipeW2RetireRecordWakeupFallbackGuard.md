# LoadReplayReturnPipeW2RetireRecordWakeupFallbackGuard

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2RetireRecordWakeupFallbackGuard.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/LoadReplayReturnPipeW2RetireRecordWakeupFallbackGuardSpec.scala`
- Integrated user: `rtl/LinxCore/chisel/src/main/scala/linxcore/top/LinxCoreFrontendFetchRfAluTraceTop.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/lsu/load_unit/ldq.cpp`: `LDQInfo::returnData`
  - `model/LinxCoreModel/model/iex/iex.cpp`: `IEX::setMemWakeup`
  - `model/LinxCoreModel/model/iex/pipe/lda_pipe.cpp`: speculative `setMemWakeup` producer
  - `model/LinxCoreModel/model/iex/pipe/agu_pipe.cpp`: speculative `setMemWakeup` producer
- Related contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2RetireRecord.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2WakeupRequest.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2WakeupFirePayload.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2WakeupArbiterInput.scala`
- Contract IDs: `LC-CHISEL-LSU-REPLAY-PIPE-W2-RETIRE-RECORD-WAKEUP-FALLBACK-001`

## Purpose

`LoadReplayReturnPipeW2RetireRecordWakeupFallbackGuard` is the R589
duplicate-prevention owner for retained replay-load wakeup. R587 and R588 prove
that retained ROB completion and RF writeback would duplicate the physical W2
paths for the current replay-LIQ fixture. R589 applies the same rule to the
load wakeup side effect before any retained LIQ lifecycle clear or wakeup
fallback can be promoted.

In LinxCoreModel, `LDQInfo::returnData` writes the returned load to the IEX
load-return pipe and calls `IEX::setMemWakeup` only when the request is not
speculative and not stack-owned. `IEX::setMemWakeup` then wakes IQ/local
dependents from the returned load's destination tags. The Chisel guard mirrors
that boundary: retained wakeup is present only for a non-speculative,
non-stack record with a real destination, and a retained fallback is suppressed
when physical W2 already fired the same RID/tag wakeup.

## Interface

| Direction | Signal | Description |
|---|---|---|
| input | `enable` / `flush` | Reduced replay-LIQ arm and flush suppression. |
| input | `fallbackEnable` | Explicit live fallback arm. Top ties this low in R589. |
| input | `captureAccepted` / `captureRid` | Retire-record capture boundary and captured ROB RID. |
| input | `physicalWakeupValid` / `physicalWakeupRid` / `physicalReducedGprWakeup` / `physicalWakeupTag` | Physical W2 wakeup fire payload observed at capture. |
| input | `recordValid` / `recordRid` / `recordWakeupValid` / `recordReducedGprWakeup` / `recordWakeupTag` | Pending retained record and its wakeup payload. |
| input | `recordFire` | Retained record consume pulse. |
| output | `capturePhysicalWakeup` | Physical W2 wakeup fired for the captured RID. |
| output | `recordCandidate` | Active retained record is visible. |
| output | `recordMatchesCapture` | Retained record RID matches the latched capture RID. |
| output | `physicalWakeupPayloadMatches` | Retained reduced-GPR bit and physical tag match the physical wakeup payload. |
| output | `duplicatePhysicalWakeup` | Retained wakeup would duplicate physical W2 wakeup. |
| output | `fallbackEligible` | Retained wakeup could be used only when no physical wakeup was seen. |
| output | `fallbackWakeupValid` / `fallbackReducedGprWakeup` / `fallbackWakeupTag` | Disabled fallback wakeup output for a later no-physical-wakeup stimulus. |
| output | blocker signals | Disabled, flush, no record, invalid RID, no retained wakeup, no capture evidence, prior physical wakeup, payload mismatch, and fallback-disabled diagnostics. |

## Logic Design

```text
capturePhysicalWakeup =
  active &&
  captureAccepted &&
  captureRid.valid &&
  physicalWakeupValid &&
  physicalWakeupRid.valid &&
  physicalWakeupRid.value == captureRid.value

recordMatchesCapture =
  active &&
  recordValid &&
  recordRid.valid &&
  captureValidReg &&
  recordRid.value == captureRidValueReg

physicalWakeupPayloadMatches =
  recordValid &&
  recordWakeupValid &&
  physicalWakeupSeenReg &&
  recordReducedGprWakeup == physicalReducedGprWakeupReg &&
  recordWakeupTag == physicalWakeupTagReg

duplicatePhysicalWakeup =
  recordMatchesCapture && physicalWakeupPayloadMatches

fallbackWakeupValid =
  fallbackEnable &&
  recordValid &&
  recordWakeupValid &&
  recordMatchesCapture &&
  !physicalWakeupSeenReg
```

Flush or disable clears the latched capture. A retained record consume clears
the latch when there is no same-cycle replacement capture. If a physical wakeup
was seen but the retained payload does not match, the module reports
`blockedByPhysicalWakeupPayloadMismatch`; it still suppresses fallback so a
later owner must debug the mismatch before emitting a second wakeup.

## Integration Status

R589 wires the guard next to `LoadReplayReturnPipeW2WakeupFirePayload` in
`LinxCoreFrontendFetchRfAluTraceTop`. The top exposes four v39 proof counters:

- `w2_retire_record_wakeup_fallback_capture_physical_wakeup`
- `w2_retire_record_wakeup_fallback_candidate`
- `w2_retire_record_wakeup_fallback_duplicate_physical_wakeup`
- `w2_retire_record_wakeup_fallback_wakeup_valid`

Fallback enable remains tied low in the top. Generated RTL/QEMU evidence:

```text
generated/r589-replay-retire-record-wakeup-fallback-guard-xcheck/report/crosscheck_manifest.json
status=pass compared_rows=18 mismatch_count=0 qemu_cbstop=0 dut_cbstop=0

frontend_fetch_rf_alu_sideband_stats.json schema=v39
w2_retire_record_wakeup_fallback_capture_physical_wakeup=5
w2_retire_record_wakeup_fallback_candidate=5
w2_retire_record_wakeup_fallback_duplicate_physical_wakeup=5
w2_retire_record_wakeup_fallback_wakeup_valid=0
w2_retire_record_rf_fallback_duplicate_physical_writeback=5
w2_retire_record_rf_fallback_writeback_valid=0
w2_retire_record_rob_fallback_duplicate_physical_complete=5
w2_retire_record_rob_fallback_complete_valid=0
```

The current fixture therefore already fires the physical W2 wakeup for the
same retained RID/tag payload. A later packet must provide a no-physical-wakeup
stimulus before `fallbackWakeupValid` can be armed.

## Verification

Focused gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2RetireRecordWakeupFallbackGuard
bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop
LINXCORE_REPLAY_LIQ_EARLY_STA_ADDRESS=1 \
LINXCORE_REPLAY_LIQ_W2_COMPLETION_DELAY_CYCLES=12 \
FETCH_REPLAY_LIQ_REQUIRE_NONZERO=wait_replay_capture_accepted,replay_queue_out_fire,liq_alloc_accepted,lret_w2_slot_accepted,w2_promotion_live \
bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh \
  --fixture replay-ldi-sdi-ldi-sdi-ldi-ldi-loop \
  --build-dir generated/r589-replay-retire-record-wakeup-fallback-guard-xcheck \
  --expected-rows 18 --capture-rows 32 --max-seconds 10 \
  --reduced-store-replay-liq --disable-store-memory-mutation \
  --allow-residual-replay-liq-wait
python3 tools/chisel/validate_frontend_fetch_rf_alu_sideband_stats.py \
  --expect-reduced-store-replay-liq \
  --require-nonzero replay_liq.w2_retire_record_wakeup_fallback_capture_physical_wakeup \
  --require-nonzero replay_liq.w2_retire_record_wakeup_fallback_candidate \
  --require-nonzero replay_liq.w2_retire_record_wakeup_fallback_duplicate_physical_wakeup \
  --require-zero replay_liq.w2_retire_record_wakeup_fallback_wakeup_valid \
  generated/r589-replay-retire-record-wakeup-fallback-guard-xcheck/report/frontend_fetch_rf_alu_sideband_stats.json
```

Reference tests cover duplicate physical-wakeup suppression, no-physical
fallback emission, payload-mismatch diagnostics, missing retained-wakeup and
capture blockers, the explicit fallback-enable gate, and Chisel elaboration.
