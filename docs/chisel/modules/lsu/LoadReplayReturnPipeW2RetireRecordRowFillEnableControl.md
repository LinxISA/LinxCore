# LoadReplayReturnPipeW2RetireRecordRowFillEnableControl

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2RetireRecordRowFillEnableControl.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/LoadReplayReturnPipeW2RetireRecordRowFillEnableControlSpec.scala`
- Integrated user: `rtl/LinxCore/chisel/src/main/scala/linxcore/top/LinxCoreFrontendFetchRfAluTraceTop.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/lsu/load_unit/ldq.cpp`
    - `LDQInfo::returnData`, `LDQInfo::CheckMovRslvQ`, `ResolveQ::retired`, `LDQInfo::retire`
  - `model/LinxCoreModel/model/iex/pipe/lda_pipe.cpp`
    - `LDAPipe::runW2`
  - `model/LinxCoreModel/model/iex/iex.cpp`
    - `IEX::receiveFromLSU`, `IEX::setMemData`
- Related contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2RetireRecord.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2RetireRecordCommitRowCandidate.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2RetireRecordLifecycleEvidenceLatch.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2RetireRecordAtomicRequestProbe.scala`
- Contract IDs: `LC-CHISEL-LSU-REPLAY-PIPE-W2-RETIRE-RECORD-ROW-FILL-ENABLE-001`

## Purpose

`LoadReplayReturnPipeW2RetireRecordRowFillEnableControl` is the diagnostic
request owner that decides whether a retained W2 retire record has enough
aligned evidence to enable row fill. R585 proved that the retained record can
form a row-fill candidate, but top integration still tied row fill disabled.
R586 splits the enable predicate from row mutation so the generated RTL can
prove retained lifecycle evidence, row-fill candidate formation, and explicit
row-fill enable before any live LIQ or ROB mutation is promoted.

## Interface

| Direction | Signal | Description |
|---|---|---|
| input | `enable` | Reduced replay-LIQ wrapper is active. |
| input | `flush` | Suppresses retained row-fill enable. |
| input | `rowFillRequested` | Top-level diagnostic arm for retained row fill. |
| input | `retireRecordValid` | Retained W2 retire record is present. |
| input | `lifecycleRowClearReady` | Retained lifecycle evidence can identify the row to clear. |
| input | `rowFillCandidateValid` | Retained commit-row candidate can produce a row-fill payload. |
| output | `active` | `enable && !flush`. |
| output | `requestEvidenceValid` | Retained record and lifecycle row evidence are both present. |
| output | `candidateAligned` | Request evidence also has a retained row-fill candidate. |
| output | `rowFillEnable` | Diagnostic row-fill enable pulse. |
| output | `blockedByDisabled` | Inputs indicated intent while `enable` was low. |
| output | `blockedByFlush` | Inputs indicated intent while flush suppressed the path. |
| output | `blockedByRequestDisabled` | Candidate aligned but the explicit request arm was low. |
| output | `blockedByNoLifecycleRow` | Retained record exists without lifecycle row evidence. |
| output | `blockedByNoRowFillCandidate` | Retained request evidence lacks a row-fill candidate. |
| output | `invalidRowFillCandidateWithoutEvidence` | Row-fill candidate appeared without retained request evidence. |

## Logic Design

```text
active = enable && !flush
requestEvidenceValid =
  active &&
  retireRecordValid &&
  lifecycleRowClearReady

candidateAligned = requestEvidenceValid && rowFillCandidateValid
rowFillEnable = rowFillRequested && candidateAligned
```

The controller intentionally does not mutate LIQ, ROB, RF, or wakeup state. It
only publishes the point at which a retained record has enough aligned evidence
to make row-fill promotion meaningful. Blockers are structured so the harness
can tell the difference between missing lifecycle evidence, missing row-fill
candidate payload, disabled request arm, and inconsistent candidate-without-
evidence behavior.

## Integration Status

R586 wires this controller into `LinxCoreFrontendFetchRfAluTraceTop` using the
retained lifecycle-evidence provider and the retained commit-row candidate. The
generated RTL/QEMU gate at
`generated/r586-replay-retire-record-lifecycle-evidence-xcheck` passes with 18
compared rows and zero mismatches. Sideband schema v36 records:

- `w2_retire_record_row_fill_enable_request_evidence_valid=5`
- `w2_retire_record_row_fill_enable_candidate_aligned=5`
- `w2_retire_record_row_fill_enable=5`
- `w2_retire_record_row_fill_enable_blocked_by_no_lifecycle_row=0`
- `w2_retire_record_row_fill_enable_blocked_by_no_row_fill_candidate=0`
- `w2_retire_record_commit_row_complete_candidate=5`
- `w2_retire_record_atomic_request_row_fill_enable_aligned=5`
- `w2_retire_record_atomic_request_blocked_by_no_row_fill_enable=0`
- `w2_retire_record_lifecycle_request_live_promotion_candidate=5`

This moves the active blocker from row-fill enable toward live retained
mutation ordering. The next packet must decide how the retained complete row
interacts with LIQ clear, atomic request side effects, ROB resolve, RF
writeback, and replay wakeup.

## Deferred Owners

- Replace diagnostic row-fill enable with a live retained row-fill/mutation
  owner only after live clear ordering is proven.
- Confirm the retained complete row updates the same architectural row fields as
  the physical W2 path before enabling ROB resolve and RF writeback.
- Preserve zero blocker checks for lifecycle, row-fill candidate, and atomic
  request alignment in every promotion packet.

## Verification

Focused gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2RetireRecordRowFillEnableControl
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
  --require-nonzero replay_liq.w2_retire_record_row_fill_enable_request_evidence_valid \
  --require-nonzero replay_liq.w2_retire_record_row_fill_enable_candidate_aligned \
  --require-nonzero replay_liq.w2_retire_record_row_fill_enable \
  --require-nonzero replay_liq.w2_retire_record_commit_row_complete_candidate \
  --require-zero replay_liq.w2_retire_record_row_fill_enable_blocked_by_no_lifecycle_row \
  --require-zero replay_liq.w2_retire_record_row_fill_enable_blocked_by_no_row_fill_candidate \
  generated/r586-replay-retire-record-lifecycle-evidence-xcheck/report/frontend_fetch_rf_alu_sideband_stats.json
```
