# LoadReplayReturnPipeW2RetireRecordRobCompleteFallbackGuard

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2RetireRecordRobCompleteFallbackGuard.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/LoadReplayReturnPipeW2RetireRecordRobCompleteFallbackGuardSpec.scala`
- Integrated user: `rtl/LinxCore/chisel/src/main/scala/linxcore/top/LinxCoreFrontendFetchRfAluTraceTop.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/lsu/load_unit/ldq.cpp`: `LDQInfo::returnData`, `LDQInfo::retire`, `ResolveQ::retired`
  - `model/LinxCoreModel/model/iex/iex.cpp`: `IEX::setMemData`, `IEX::setMemWakeup`
- Related contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2RetireRecord.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2RetireRecordCommitRowCandidate.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2RobCompleteSource.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/backend/ReducedRobCompletionArbiter.scala`
- Contract IDs: `LC-CHISEL-LSU-REPLAY-PIPE-W2-RETIRE-RECORD-ROB-FALLBACK-001`

## Purpose

`LoadReplayReturnPipeW2RetireRecordRobCompleteFallbackGuard` is the R587
duplicate-prevention owner for retained replay-load ROB completion. R586 proved
that retained W2 retire records can form complete-row candidates, but the same
fixture also proves the physical W2 path already emits the ROB completion for
those RIDs. Promoting the retained complete row directly would double-complete
the same ROB entry.

The guard latches whether the physical W2 ROB completion source completed the
captured RID at the retire-record capture boundary. When the retained record is
later visible with a retained complete row, the guard classifies the row as a
duplicate if physical completion was already seen. Its fallback output remains
top-disabled in R587; the packet adds observability and a safe fallback shape,
not a live architectural mutation.

The model split is the reference boundary: load data return sends a memory
result toward IEX, where RF/ROB/wakeup side effects are emitted, while LDQ
retire later clears resolved queue rows in commit order. Chisel must preserve
that single ROB-completion ownership before retained LIQ clear, RF writeback,
or wakeup side effects can be promoted.

## Interface

| Direction | Signal | Description |
|---|---|---|
| input | `enable` / `flush` | Reduced replay-LIQ arm and flush suppression. |
| input | `fallbackEnable` | Explicit live fallback arm. Top ties this low in R587. |
| input | `captureAccepted` / `captureRid` | Retire-record capture boundary and captured ROB RID. |
| input | `physicalCompleteValid` / `physicalCompleteRobValue` | Physical W2 ROB completion emitted in the capture cycle. |
| input | `recordValid` / `recordRid` / `recordFire` | Pending retained retire record and consume pulse. |
| input | `retainedCompleteRowValid` / `retainedCompleteRow` | Retained complete-row candidate from the retained record. |
| output | `captureIntent` | Active capture with a valid capture RID. |
| output | `capturePhysicalComplete` | Capture RID matched the same-cycle physical ROB completion. |
| output | `recordCandidate` | Active retained record is visible. |
| output | `recordMatchesCapture` | Retained record RID matches the latched capture RID. |
| output | `duplicatePhysicalComplete` | Retained record has a complete-row candidate, but physical ROB completion already covered the RID. |
| output | `fallbackEligible` | Retained row could be a fallback because no prior physical completion was seen. |
| output | `fallbackCompleteValid` / `fallbackCompleteRobValue` / `fallbackCompleteRowValid` / `fallbackCompleteRow` | Disabled fallback completion output for a later no-physical-complete stimulus. |
| output | blocker signals | Disabled, flush, no record, invalid RID, no retained row, no capture evidence, prior physical complete, and fallback-disabled diagnostics. |

## Logic Design

```text
captureIntent =
  enable && !flush &&
  captureAccepted &&
  captureRid.valid

capturePhysicalComplete =
  captureIntent &&
  physicalCompleteValid &&
  physicalCompleteRobValue == captureRid.value

recordMatchesCapture =
  enable && !flush &&
  recordValid &&
  recordRid.valid &&
  captureValidReg &&
  recordRid.value == captureRidValueReg

duplicatePhysicalComplete =
  recordMatchesCapture && physicalCompleteSeenReg

fallbackEligible =
  recordValid &&
  retainedCompleteRowValid &&
  recordMatchesCapture &&
  !physicalCompleteSeenReg

fallbackCompleteValid =
  fallbackEnable && fallbackEligible
```

`captureValidReg` stores the most recent active capture RID. `physicalCompleteSeenReg`
stores whether the physical ROB completion source completed that RID in the same
cycle. Flush or disable clears both registers; consuming the retained record
clears the evidence when there is no same-cycle replacement capture.

The fallback output uses the retained complete row only when the guard proves
there was no physical ROB completion for the retained RID. R587 integration ties
`fallbackEnable=false`, so the guard cannot drive `DecodeRenameROBPath` or the
ROB completion arbiter.

## Integration Status

R587 wires the guard beside the existing physical replay ROB completion source
and retained complete-row candidate in `LinxCoreFrontendFetchRfAluTraceTop`.
The top exposes four v37 proof counters:

- `w2_retire_record_rob_fallback_capture_physical_complete`
- `w2_retire_record_rob_fallback_candidate`
- `w2_retire_record_rob_fallback_duplicate_physical_complete`
- `w2_retire_record_rob_fallback_complete_valid`

The same packet moves the existing ROB completion arbiter connections into
`LinxCoreFrontendFetchRfAluTraceTopRobCompleteArbiterWiring`. This is a
maintenance-only split required by the top constructor bytecode budget; it does
not change execute-vs-replay completion arbitration.

Generated RTL/QEMU evidence:

```text
generated/r587-replay-retire-record-rob-fallback-guard-xcheck/report/crosscheck_manifest.json
status=pass compared_rows=18 mismatch_count=0 qemu_cbstop=0 dut_cbstop=0

frontend_fetch_rf_alu_sideband_stats.json schema=v37
w2_retire_record_rob_fallback_capture_physical_complete=5
w2_retire_record_rob_fallback_candidate=5
w2_retire_record_rob_fallback_duplicate_physical_complete=5
w2_retire_record_rob_fallback_complete_valid=0
w2_retire_record_row_fill_enable=5
w2_retire_record_commit_row_complete_candidate=5
w2_side_effect_fire_complete=5
w2_row_fill_enable=5
```

The active owner has moved from retained complete-row formation to duplicate
suppression and no-physical-complete fallback stimulus. Do not enable retained
ROB/RF/LIQ side effects from this guard until a fixture proves
`fallbackEligible` without a matching physical completion.

R588 adds the matching RF writeback duplicate guard. The v38 replay-LIQ
sideband keeps the ROB duplicate shape unchanged
(`w2_retire_record_rob_fallback_duplicate_physical_complete=5`,
`w2_retire_record_rob_fallback_complete_valid=0`) and additionally proves the
retained RF payload would duplicate physical W2 writeback. This keeps ROB and
RF fallback promotion synchronized behind the same no-physical-side-effect
requirement.

## Verification

Focused gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2RetireRecordRobCompleteFallbackGuard
bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop
LINXCORE_REPLAY_LIQ_EARLY_STA_ADDRESS=1 \
LINXCORE_REPLAY_LIQ_W2_COMPLETION_DELAY_CYCLES=12 \
FETCH_REPLAY_LIQ_REQUIRE_NONZERO=wait_replay_capture_accepted,replay_queue_out_fire,liq_alloc_accepted,lret_w2_slot_accepted,w2_promotion_live \
bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh \
  --fixture replay-ldi-sdi-ldi-sdi-ldi-ldi-loop \
  --build-dir generated/r587-replay-retire-record-rob-fallback-guard-xcheck \
  --expected-rows 18 --capture-rows 32 --max-seconds 10 \
  --reduced-store-replay-liq --disable-store-memory-mutation \
  --allow-residual-replay-liq-wait
python3 tools/chisel/validate_frontend_fetch_rf_alu_sideband_stats.py \
  --expect-reduced-store-replay-liq \
  --require-nonzero replay_liq.w2_retire_record_rob_fallback_capture_physical_complete \
  --require-nonzero replay_liq.w2_retire_record_rob_fallback_candidate \
  --require-nonzero replay_liq.w2_retire_record_rob_fallback_duplicate_physical_complete \
  --require-zero replay_liq.w2_retire_record_rob_fallback_complete_valid \
  --require-nonzero replay_liq.w2_retire_record_row_fill_enable \
  --require-nonzero replay_liq.w2_retire_record_commit_row_complete_candidate \
  generated/r587-replay-retire-record-rob-fallback-guard-xcheck/report/frontend_fetch_rf_alu_sideband_stats.json
```

Reference tests cover duplicate physical-complete suppression, fallback emission
when no physical completion was captured, missing retained-row/capture blockers,
the explicit fallback-enable gate, and Chisel elaboration.
