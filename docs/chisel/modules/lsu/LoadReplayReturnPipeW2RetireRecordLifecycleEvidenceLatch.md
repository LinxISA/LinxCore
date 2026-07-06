# LoadReplayReturnPipeW2RetireRecordLifecycleEvidenceLatch

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2RetireRecordLifecycleEvidenceLatch.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/LoadReplayReturnPipeW2RetireRecordLifecycleEvidenceLatchSpec.scala`
- Integrated user: `rtl/LinxCore/chisel/src/main/scala/linxcore/top/LinxCoreFrontendFetchRfAluTraceTop.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/iex/pipe/lda_pipe.cpp`
    - `LDAPipe::runW2`, `LDAPipe::move`
  - `model/LinxCoreModel/model/iex/pipe/agu_pipe.cpp`
    - `AGUPipe::runW2`, `AGUPipe::move`
  - `model/LinxCoreModel/model/lsu/load_unit/ldq.cpp`
    - `LDQInfo::returnData`, `LDQInfo::CheckMovRslvQ`, `LDQInfo::retire`
- Related contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2RetireRecord.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2ReplayRowLifecycleReady.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2RetireRecordRowFillEnableControl.scala`
- Contract IDs: `LC-CHISEL-LSU-REPLAY-PIPE-W2-RETIRE-RECORD-LIFECYCLE-EVIDENCE-001`

## Purpose

`LoadReplayReturnPipeW2RetireRecordLifecycleEvidenceLatch` retains the physical
W2 replay-row lifecycle evidence that existed when
`LoadReplayReturnPipeW2RetireRecord` accepted a consumed W2 payload. R585 proved
the retained payload and metadata can survive after physical W2 clears, but the
R586 row-fill enable probe showed that re-querying LIQ lifecycle state from the
retained record is too late: the physical W2 lifecycle path has already consumed
or cleared the matching row.

The latch keeps the model-aligned W2 side-effect boundary intact. In the model,
`LDAPipe::runW2` and `AGUPipe::runW2` perform the side effects for the current
W2 row before `move()` advances the pipe. The retained Chisel record therefore
needs to carry the same row-clear evidence captured at that boundary rather than
rediscovering it later from mutable LIQ state.

## Interface

| Direction | Signal | Description |
|---|---|---|
| input | `enable` | Reduced replay-LIQ wrapper is active. |
| input | `flush` | Clears retained lifecycle evidence and suppresses provider validity. |
| input | `captureAccepted` | Retire-record capture boundary. |
| input | `captureLifecycleRowClearReady` | Physical W2 lifecycle owner had a unique row ready to clear at capture. |
| input | `captureRowClearIndex` | Physical W2 lifecycle row index captured with the retire record. |
| input | `recordValid` | Retained retire record is present. |
| input | `recordFire` | Retained retire record is consumed. |
| output | `active` | `enable && !flush`. |
| output | `captureIntent` | Active cycle accepted a retire-record capture. |
| output | `captureFromLifecycle` | Capture accepted with physical lifecycle row evidence. |
| output | `captureBlockedByNoLifecycle` | Capture accepted but no physical lifecycle evidence was available. |
| output | `clearAccepted` | Retained lifecycle evidence is consumed with the retire record. |
| output | `providerValid` | Retained lifecycle evidence and retained retire record are both present. |
| output | `providerRowClearReady` | Provider-valid alias used as retained `recordReady`. |
| output | `providerRowClearIndex` | Captured physical lifecycle row index when valid. |
| output | `providerValidWithoutRecord` | Evidence is present but the retained record is absent. |
| output | `recordValidWithoutProvider` | Retained record is present without captured lifecycle evidence. |

## Logic Design

```text
active = enable && !flush
captureIntent = active && captureAccepted
captureFromLifecycle = captureIntent && captureLifecycleRowClearReady
clearAccepted = active && recordFire && validReg
providerValid = active && validReg && recordValid
providerRowClearReady = providerValid
```

Flush or disable clears the retained evidence. A successful
`captureFromLifecycle` stores `captureRowClearIndex` and sets `validReg`.
`clearAccepted` clears the stored evidence when the retained record fires unless
a same-cycle capture refreshes it. The provider intentionally requires both
latched evidence and a retained record so diagnostic counters can distinguish
missing evidence from missing record state.

## Integration Status

R586 wires this latch into `LinxCoreFrontendFetchRfAluTraceTop` at the physical
W2 retire-record capture boundary. It captures the existing physical
`LoadReplayReturnPipeW2ReplayRowLifecycleReady` row-clear result and replaces
the prior retained-record lifecycle re-query with the latched provider. The
legacy retained lifecycle sideband is still emitted, but now reflects captured
physical lifecycle evidence:

- `w2_retire_record_lifecycle_resolved_row_match`
- `w2_retire_record_lifecycle_row_clear_ready`
- `w2_retire_record_lifecycle_blocked_by_no_resolved_row`

The generated RTL/QEMU gate at
`generated/r586-replay-retire-record-lifecycle-evidence-xcheck` passes with
manifest `status="pass"`, `comparator_status=0`, 18 compared rows, zero
mismatches, and zero QEMU/DUT CBSTOP rows. Sideband schema v36 records:

- `w2_retire_record_lifecycle_evidence_capture_intent=5`
- `w2_retire_record_lifecycle_evidence_capture_from_lifecycle=5`
- `w2_retire_record_lifecycle_evidence_capture_blocked_by_no_lifecycle=0`
- `w2_retire_record_lifecycle_evidence_clear_accepted=5`
- `w2_retire_record_lifecycle_evidence_provider_valid=5`
- `w2_retire_record_lifecycle_evidence_provider_valid_without_record=0`
- `w2_retire_record_lifecycle_evidence_record_valid_without_provider=0`

This proves the retained record has aligned lifecycle evidence without delaying
or reordering the physical W2 lifecycle path.

## Deferred Owners

- Promote retained lifecycle evidence from diagnostic readiness into live LIQ
  clear mutation.
- Prove retained row-clear mutation ordering with row-fill, atomic request,
  ROB resolve, and RF writeback enabled.
- Keep this latch as the boundary owner; do not reintroduce retained-record LIQ
  re-query after physical W2 lifecycle clear.

## Verification

Focused gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2RetireRecordLifecycleEvidenceLatch
bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop
LINXCORE_REPLAY_LIQ_EARLY_STA_ADDRESS=1 \
LINXCORE_REPLAY_LIQ_W2_COMPLETION_DELAY_CYCLES=12 \
FETCH_REPLAY_LIQ_REQUIRE_NONZERO=wait_replay_capture_accepted,replay_queue_out_fire,liq_alloc_accepted,lret_w2_slot_accepted,w2_promotion_live \
bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh \
  --fixture replay-ldi-sdi-ldi-sdi-ldi-ldi-loop \
  --build-dir generated/r586-replay-retire-record-lifecycle-evidence-xcheck \
  --expected-rows 18 --capture-rows 32 --max-seconds 10 \
  --reduced-store-replay-liq --disable-store-memory-mutation \
  --allow-residual-replay-liq-wait
python3 tools/chisel/validate_frontend_fetch_rf_alu_sideband_stats.py \
  --expect-reduced-store-replay-liq \
  --require-nonzero replay_liq.w2_retire_record_lifecycle_evidence_capture_from_lifecycle \
  --require-nonzero replay_liq.w2_retire_record_lifecycle_evidence_provider_valid \
  --require-zero replay_liq.w2_retire_record_lifecycle_evidence_capture_blocked_by_no_lifecycle \
  generated/r586-replay-retire-record-lifecycle-evidence-xcheck/report/frontend_fetch_rf_alu_sideband_stats.json
```
