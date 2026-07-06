# LoadReplayReturnPipeW2RetireRecordLifecycleClearFallbackGuard

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2RetireRecordLifecycleClearFallbackGuard.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/LoadReplayReturnPipeW2RetireRecordLifecycleClearFallbackGuardSpec.scala`
- Integrated user: `rtl/LinxCore/chisel/src/main/scala/linxcore/top/LinxCoreFrontendFetchRfAluTraceTop.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/lsu/load_unit/ldq.cpp`: `LDQInfo::returnData`
  - `model/LinxCoreModel/model/lsu/load_unit/ldq.cpp`: `LDQInfo::retire`
- Related contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2RetireRecord.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2RetireRecordLifecycleEvidenceLatch.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2ReplayRowClearRequest.scala`
- Contract IDs: `LC-CHISEL-LSU-REPLAY-PIPE-W2-RETIRE-RECORD-LIFECYCLE-CLEAR-FALLBACK-001`

## Purpose

`LoadReplayReturnPipeW2RetireRecordLifecycleClearFallbackGuard` is the R590
duplicate-prevention owner for retained replay-LIQ lifecycle clear. R587-R589
show that retained ROB completion, RF writeback, and wakeup all duplicate the
physical W2 side-effect paths in the current replay-LIQ fixture. R590 applies
the same promotion discipline to LIQ row clear before any retained lifecycle
mutation can be enabled.

In LinxCoreModel, `LDQInfo::returnData` resolves a load and performs return-time
side effects, while `LDQInfo::retire` later clears resolved LDQ rows by commit
order. The Chisel replay-LIQ path currently clears the resolved row through the
physical W2 lifecycle path. The guard latches whether that physical lifecycle
clear accepted the same row-clear index at retire-record capture time, then
suppresses retained fallback clear for the matching retained record.

## Interface

| Direction | Signal | Description |
|---|---|---|
| input | `enable` / `flush` | Reduced replay-LIQ arm and flush suppression. |
| input | `fallbackEnable` | Explicit live fallback arm. Top ties this low in R590. |
| input | `captureAccepted` / `captureRowClearReady` / `captureRowClearIndex` | Retire-record capture boundary and physical lifecycle row-clear evidence. |
| input | `physicalClearAccepted` / `physicalClearIndex` | Actual physical replay-row clear accepted by the LIQ clear path. |
| input | `recordValid` / `recordLifecycleClearReady` / `recordRowClearIndex` | Pending retained record and its retained row-clear evidence. |
| input | `recordFire` | Retained record consume pulse. |
| output | `capturePhysicalClear` | Physical W2 lifecycle clear accepted the captured row-clear index. |
| output | `recordCandidate` | Active retained record is visible. |
| output | `recordMatchesCapture` | Retained row-clear index matches the captured row-clear index. |
| output | `duplicatePhysicalClear` | Retained lifecycle clear would duplicate physical W2 lifecycle clear. |
| output | `fallbackClearValid` / `fallbackClearIndex` | Disabled fallback lifecycle clear output for a later no-physical-clear stimulus. |
| output | blocker signals | Disabled, flush, no record, no retained lifecycle clear, no capture evidence, prior physical clear, and fallback-disabled diagnostics. |

## Logic Design

```text
capturePhysicalClear =
  active &&
  captureAccepted &&
  captureRowClearReady &&
  physicalClearAccepted &&
  physicalClearIndex == captureRowClearIndex

recordMatchesCapture =
  active &&
  recordValid &&
  recordLifecycleClearReady &&
  captureValidReg &&
  recordRowClearIndex == captureRowClearIndexReg

duplicatePhysicalClear =
  recordMatchesCapture && physicalClearSeenReg

fallbackClearValid =
  fallbackEnable &&
  recordMatchesCapture &&
  !physicalClearSeenReg
```

Flush or disable clears the latched capture. A retained record consume clears
the latch when there is no same-cycle replacement capture. The guard does not
drive `ReducedLoadReplayLiqAllocPath.clearResolvedValid`; it only classifies
whether a retained clear would be duplicate-safe.

## Integration Status

R590 wires the guard next to `LoadReplayReturnPipeW2RetireRecordLifecycleEvidenceLatch`
and `LoadReplayReturnPipeW2ReplayRowClearRequest` in
`LinxCoreFrontendFetchRfAluTraceTop`. The top exposes four v40 proof counters:

- `w2_retire_record_lifecycle_clear_fallback_capture_physical_clear`
- `w2_retire_record_lifecycle_clear_fallback_candidate`
- `w2_retire_record_lifecycle_clear_fallback_duplicate_physical_clear`
- `w2_retire_record_lifecycle_clear_fallback_clear_valid`

Fallback enable remains tied low in the top. Generated RTL/QEMU evidence:

```text
generated/r590-replay-retire-record-lifecycle-clear-fallback-guard-xcheck/report/crosscheck_manifest.json
status=pass compared_rows=18 mismatch_count=0 qemu_cbstop=0 dut_cbstop=0

frontend_fetch_rf_alu_sideband_stats.json schema=v40
w2_retire_record_lifecycle_clear_fallback_capture_physical_clear=5
w2_retire_record_lifecycle_clear_fallback_candidate=5
w2_retire_record_lifecycle_clear_fallback_duplicate_physical_clear=5
w2_retire_record_lifecycle_clear_fallback_clear_valid=0
w2_retire_record_lifecycle_live_promotion_candidate=5
w2_lifecycle_clear_commit_enable=5
```

The current fixture therefore already clears the retained row through the
physical W2 lifecycle path. A later packet must provide a no-physical-clear
stimulus before `fallbackClearValid` can be armed.

## Verification

Focused gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2RetireRecordLifecycleClearFallbackGuard
bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop
LINXCORE_REPLAY_LIQ_EARLY_STA_ADDRESS=1 \
LINXCORE_REPLAY_LIQ_W2_COMPLETION_DELAY_CYCLES=12 \
FETCH_REPLAY_LIQ_REQUIRE_NONZERO=wait_replay_capture_accepted,replay_queue_out_fire,liq_alloc_accepted,lret_w2_slot_accepted,w2_promotion_live \
bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh \
  --fixture replay-ldi-sdi-ldi-sdi-ldi-ldi-loop \
  --build-dir generated/r590-replay-retire-record-lifecycle-clear-fallback-guard-xcheck \
  --expected-rows 18 --capture-rows 32 --max-seconds 10 \
  --reduced-store-replay-liq --disable-store-memory-mutation \
  --allow-residual-replay-liq-wait
```

Reference tests cover duplicate physical-clear suppression, no-physical-clear
fallback emission, missing retained lifecycle-clear and capture blockers, the
explicit fallback-enable gate, and Chisel elaboration.
