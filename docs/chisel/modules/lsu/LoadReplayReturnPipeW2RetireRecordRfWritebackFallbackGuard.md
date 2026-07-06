# LoadReplayReturnPipeW2RetireRecordRfWritebackFallbackGuard

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2RetireRecordRfWritebackFallbackGuard.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/LoadReplayReturnPipeW2RetireRecordRfWritebackFallbackGuardSpec.scala`
- Integrated user: `rtl/LinxCore/chisel/src/main/scala/linxcore/top/LinxCoreFrontendFetchRfAluTraceTop.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/lsu/load_unit/ldq.cpp`: `LDQInfo::returnData`, `LDQInfo::retire`
  - `model/LinxCoreModel/model/iex/iex.cpp`: `IEX::setMemData`, `IEX::setMemWakeup`
- Related contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2RetireRecord.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2WritebackFirePayload.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2WritebackArbiterInput.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/execute/ReducedScalarWritebackArbiter.scala`
- Contract IDs: `LC-CHISEL-LSU-REPLAY-PIPE-W2-RETIRE-RECORD-RF-FALLBACK-001`

## Purpose

`LoadReplayReturnPipeW2RetireRecordRfWritebackFallbackGuard` is the R588
duplicate-prevention owner for retained replay-load RF writeback. R587 proved
that retained ROB fallback completion would duplicate the physical W2 ROB
completion path in the current replay-LIQ fixture. R588 applies the same
promotion discipline to scalar GPR writeback before any retained RF side effect
can be enabled.

The guard latches whether the physical W2 writeback fire payload wrote the same
RID at the retire-record capture boundary. When the retained record is later
visible, the guard compares the retained GPR destination tag and data against
that latched physical writeback payload. Matching payloads are classified as
duplicates; fallback writeback remains top-disabled in R588.

In the model, `LDQInfo::returnData` returns data to the IEX load-return pipe,
and `IEX::setMemData` updates the destination data and resolves the instruction.
`LDQInfo::retire` separately clears resolved LDQ rows by commit order. Chisel
therefore needs single ownership for RF writeback before retained LIQ lifecycle
clear can be promoted.

## Interface

| Direction | Signal | Description |
|---|---|---|
| input | `enable` / `flush` | Reduced replay-LIQ arm and flush suppression. |
| input | `fallbackEnable` | Explicit live fallback arm. Top ties this low in R588. |
| input | `captureAccepted` / `captureRid` | Retire-record capture boundary and captured ROB RID. |
| input | `physicalWritebackValid` / `physicalWritebackRid` / `physicalWritebackTag` / `physicalWritebackData` | Physical W2 RF writeback fire payload observed at capture. |
| input | `recordValid` / `recordRid` / `recordWritebackValid` / `recordWritebackTag` / `recordWritebackData` | Pending retained record and its scalar GPR writeback payload. |
| input | `recordFire` | Retained record consume pulse. |
| output | `capturePhysicalWriteback` | Physical W2 writeback fired for the captured RID. |
| output | `recordCandidate` | Active retained record is visible. |
| output | `recordMatchesCapture` | Retained record RID matches the latched capture RID. |
| output | `physicalWritebackPayloadMatches` | Retained tag/data match the physical writeback payload. |
| output | `duplicatePhysicalWriteback` | Retained writeback would duplicate physical W2 RF writeback. |
| output | `fallbackEligible` | Retained writeback could be used only when no physical writeback was seen. |
| output | `fallbackWritebackValid` / `fallbackWritebackTag` / `fallbackWritebackData` | Disabled fallback RF writeback output for a later no-physical-writeback stimulus. |
| output | blocker signals | Disabled, flush, no record, invalid RID, no retained writeback, no capture evidence, prior physical writeback, payload mismatch, and fallback-disabled diagnostics. |

## Logic Design

```text
capturePhysicalWriteback =
  active &&
  captureAccepted &&
  captureRid.valid &&
  physicalWritebackValid &&
  physicalWritebackRid.valid &&
  physicalWritebackRid.value == captureRid.value

recordMatchesCapture =
  active &&
  recordValid &&
  recordRid.valid &&
  captureValidReg &&
  recordRid.value == captureRidValueReg

physicalWritebackPayloadMatches =
  recordValid &&
  recordWritebackValid &&
  physicalWritebackSeenReg &&
  recordWritebackTag == physicalWritebackTagReg &&
  recordWritebackData == physicalWritebackDataReg

duplicatePhysicalWriteback =
  recordMatchesCapture && physicalWritebackPayloadMatches

fallbackWritebackValid =
  fallbackEnable &&
  recordValid &&
  recordWritebackValid &&
  recordMatchesCapture &&
  !physicalWritebackSeenReg
```

Flush or disable clears the latched capture. A retained record consume clears
the latch when there is no same-cycle replacement capture. If a physical
writeback was seen but the retained payload does not match, the module reports
`blockedByPhysicalWritebackPayloadMismatch`; it still suppresses fallback so a
later owner must debug the mismatch before emitting a second write.

## Integration Status

R588 wires the guard next to `LoadReplayReturnPipeW2WritebackFirePayload` in
`LinxCoreFrontendFetchRfAluTraceTop`. The top exposes four v38 proof counters:

- `w2_retire_record_rf_fallback_capture_physical_writeback`
- `w2_retire_record_rf_fallback_candidate`
- `w2_retire_record_rf_fallback_duplicate_physical_writeback`
- `w2_retire_record_rf_fallback_writeback_valid`

Generated RTL/QEMU evidence:

```text
generated/r588-replay-retire-record-rf-fallback-guard-xcheck/report/crosscheck_manifest.json
status=pass compared_rows=18 mismatch_count=0 qemu_cbstop=0 dut_cbstop=0

frontend_fetch_rf_alu_sideband_stats.json schema=v38
w2_retire_record_rf_fallback_capture_physical_writeback=5
w2_retire_record_rf_fallback_candidate=5
w2_retire_record_rf_fallback_duplicate_physical_writeback=5
w2_retire_record_rf_fallback_writeback_valid=0
w2_retire_record_rob_fallback_duplicate_physical_complete=5
w2_retire_record_rob_fallback_complete_valid=0
w2_side_effect_fire_complete=5
```

The active owner moves to retained LIQ lifecycle clear and wakeup duplicate
classification. Do not enable retained RF writeback while this fixture still
shows physical W2 writeback already owns the same RID/tag/data.

## Verification

Focused gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2RetireRecordRfWritebackFallbackGuard
bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop
LINXCORE_REPLAY_LIQ_EARLY_STA_ADDRESS=1 \
LINXCORE_REPLAY_LIQ_W2_COMPLETION_DELAY_CYCLES=12 \
FETCH_REPLAY_LIQ_REQUIRE_NONZERO=wait_replay_capture_accepted,replay_queue_out_fire,liq_alloc_accepted,lret_w2_slot_accepted,w2_promotion_live \
bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh \
  --fixture replay-ldi-sdi-ldi-sdi-ldi-ldi-loop \
  --build-dir generated/r588-replay-retire-record-rf-fallback-guard-xcheck \
  --expected-rows 18 --capture-rows 32 --max-seconds 10 \
  --reduced-store-replay-liq --disable-store-memory-mutation \
  --allow-residual-replay-liq-wait
python3 tools/chisel/validate_frontend_fetch_rf_alu_sideband_stats.py \
  --expect-reduced-store-replay-liq \
  --require-nonzero replay_liq.w2_retire_record_rf_fallback_capture_physical_writeback \
  --require-nonzero replay_liq.w2_retire_record_rf_fallback_candidate \
  --require-nonzero replay_liq.w2_retire_record_rf_fallback_duplicate_physical_writeback \
  --require-zero replay_liq.w2_retire_record_rf_fallback_writeback_valid \
  generated/r588-replay-retire-record-rf-fallback-guard-xcheck/report/frontend_fetch_rf_alu_sideband_stats.json
```

Reference tests cover duplicate physical-writeback suppression, no-physical
fallback emission, payload-mismatch diagnostics, missing retained-writeback and
capture blockers, the explicit fallback-enable gate, and Chisel elaboration.
