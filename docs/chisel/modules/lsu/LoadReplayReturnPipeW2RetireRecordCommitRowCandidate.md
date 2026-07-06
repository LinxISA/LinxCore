# LoadReplayReturnPipeW2RetireRecordCommitRowCandidate

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2RetireRecordCommitRowCandidate.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/LoadReplayReturnPipeW2RetireRecordCommitRowCandidateSpec.scala`
- Integrated user: `rtl/LinxCore/chisel/src/main/scala/linxcore/top/LinxCoreFrontendFetchRfAluTraceTop.scala`
- Related contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2RetireRecord.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2CommitRowCandidate.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2RetireRecordAtomicRequestProbe.scala`
- Contract IDs: `LC-CHISEL-LSU-REPLAY-PIPE-W2-RETIRE-RECORD-COMMIT-ROW-CANDIDATE-001`

## Purpose

`LoadReplayReturnPipeW2RetireRecordCommitRowCandidate` is the R583 diagnostic
owner for shaping a commit-row candidate from the retained W2 retire record
rather than the physical W2 slot. R582 proved that retained-record lifecycle
request evidence exists but the physical W2 row-fill candidate does not align
after W2 clears. This module asks whether the retained LRET payload can form
the same row-fill candidate shape using the existing
`LoadReplayReturnPipeW2CommitRowCandidate` logic.

The module is diagnostic only. Top integration ties `rowFillEnable=false`, so
it does not drive row replacement, LIQ clear, ROB resolve, RF writeback,
wakeup, or W2 clear/refill.

## Interface

| Direction | Signal | Description |
|---|---|---|
| input | `enable` / `flush` | Reduced replay-LIQ arm and flush suppression. |
| input | `rowFillEnable` | Completion arm for the wrapped commit-row candidate. Top ties this low in R583. |
| input | `recordValid` / `record` | Retained `LoadReplayReturnLretEntry` from the W2 retire-record owner. |
| input | `instructionValid` / `instructionRaw` / `instructionLen` | Instruction metadata associated with the retained record RID. |
| output | `candidateValid` | Retained record is present and active. |
| output | `rowFillCandidateValid` | The retained record satisfies identity, metadata, source-trace, size, and GPR destination checks. |
| output | `completeRowValid` / `completeRow` | Wrapped commit row; dormant in top while `rowFillEnable=false`. |
| output | blocker signals | Disabled, flush, no retained record, invalid identity, missing metadata, missing source trace, invalid size, missing/non-GPR destination, and row-fill disabled. |

## Logic Design

The module wraps `LoadReplayReturnPipeW2CommitRowCandidate` instead of
duplicating row-format logic. It maps the retained record into the physical
candidate shape:

```text
slotOccupied = recordValid && record.valid
slotTargetIsAgu = false
slotTargetIsLda = true
slotBid/Gid/Rid/Pc/Addr/Size/Dst/Data = record payload
sourceTraceValid/source0/source1 = record source-trace payload
instruction* = retained instruction metadata inputs
```

This keeps the retained-record candidate bit-compatible with the physical W2
commit-row candidate while separating the ownership question from physical W2
slot residency.

## Integration Status

R583 wires the module into `LinxCoreFrontendFetchRfAluTraceTop`, records v33
sideband counters, and feeds the retained candidate bit into the diagnostic
retire-record lifecycle/atomic request probes. The generated RTL/QEMU gate at
`generated/r583-replay-retire-record-commit-row-candidate-xcheck` passes with
manifest `status="pass"`, `comparator_status=0`, `compared_rows=18`,
`mismatch_count=0`, and zero QEMU/DUT CBSTOP rows.

Sideband schema v33 records:

- `w2_retire_record_commit_row_candidate_valid=3`
- `w2_retire_record_commit_row_fill_candidate=0`
- `w2_retire_record_commit_row_candidate_blocked_by_no_metadata=3`
- `w2_retire_record_commit_row_candidate_blocked_by_no_source_trace=0`
- `w2_retire_record_commit_row_candidate_blocked_by_invalid_size=0`
- `w2_retire_record_commit_row_candidate_blocked_by_non_gpr_destination=0`
- `w2_retire_record_atomic_request_blocked_by_no_row_fill_candidate=3`

R584 adds `LoadReplayReturnPipeW2RetireRecordInstructionMetadataLatch` to test
that next owner. The generated fixture still observes the retained-record
candidate blocked by missing metadata, but the new v34 sideband proves why:
retire-record capture intent fires while W2 instruction metadata is ready, yet
the capture payload RID never matches the resident W2 slot RID. The next owner
is retained-record payload source identity, not generic instruction metadata
lifetime. A later packet should source the retained record from the resident W2
row, or prove an equivalent model-derived payload boundary, before promoting
row-fill enable or retained-record LIQ mutation.

R585 sources the retained record from the resident W2 slot instead of the live
LRET enqueue row and holds W2-captured metadata against later drain fallback
overwrites. The retained commit-row candidate now forms successfully while top
integration still keeps row fill disabled. The generated RTL/QEMU gate at
`generated/r585-replay-retire-record-payload-source-latch-hold-xcheck` passes
with `status="pass"`, `comparator_status=0`, 18 compared rows, zero
mismatches, and zero QEMU/DUT CBSTOP rows. Sideband schema v34 records:

- `w2_retire_record_commit_row_candidate_valid=145`
- `w2_retire_record_commit_row_fill_candidate=145`
- `w2_retire_record_commit_row_candidate_blocked_by_no_metadata=0`
- `w2_retire_record_instruction_metadata_provider_valid=145`
- `w2_retire_record_atomic_request_blocked_by_no_row_fill_candidate=0`
- `w2_retire_record_commit_row_candidate_blocked_by_row_fill_disabled=145`

The next owner is retained-record row-fill enable/request promotion and its
mutation ordering. Payload source and metadata lifetime are no longer the
active blockers in this fixture.

R586 adds retained lifecycle-evidence capture and
`LoadReplayReturnPipeW2RetireRecordRowFillEnableControl`, so the retained
candidate is no longer held behind a diagnostic row-fill-disabled blocker. The
generated RTL/QEMU gate at
`generated/r586-replay-retire-record-lifecycle-evidence-xcheck` passes with
`status="pass"`, `comparator_status=0`, 18 compared rows, zero mismatches, and
zero QEMU/DUT CBSTOP rows. Sideband schema v36 records:

- `w2_retire_record_commit_row_candidate_valid=5`
- `w2_retire_record_commit_row_fill_candidate=5`
- `w2_retire_record_commit_row_complete_candidate=5`
- `w2_retire_record_commit_row_candidate_blocked_by_no_metadata=0`
- `w2_retire_record_commit_row_candidate_blocked_by_row_fill_disabled=0`
- `w2_retire_record_row_fill_enable_request_evidence_valid=5`
- `w2_retire_record_row_fill_enable_candidate_aligned=5`
- `w2_retire_record_row_fill_enable=5`

The active owner has moved from candidate formation to live mutation ordering:
the retained complete row must still be wired through LIQ clear, ROB resolve,
RF writeback, and replay wakeup without perturbing the physical W2 path.

## Verification

Focused gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2RetireRecordCommitRowCandidate
bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop
LINXCORE_REPLAY_LIQ_EARLY_STA_ADDRESS=1 \
LINXCORE_REPLAY_LIQ_W2_COMPLETION_DELAY_CYCLES=12 \
FETCH_REPLAY_LIQ_REQUIRE_NONZERO=wait_replay_capture_accepted,replay_queue_out_fire,liq_alloc_accepted,lret_w2_slot_accepted,w2_promotion_live \
bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh \
  --fixture replay-ldi-sdi-ldi-sdi-ldi-ldi-loop \
  --build-dir generated/r583-replay-retire-record-commit-row-candidate-xcheck \
  --expected-rows 18 --capture-rows 32 --max-seconds 10 \
  --reduced-store-replay-liq --disable-store-memory-mutation \
  --allow-residual-replay-liq-wait
python3 tools/chisel/validate_frontend_fetch_rf_alu_sideband_stats.py \
  --expect-reduced-store-replay-liq \
  --require-nonzero replay_liq.w2_retire_record_commit_row_candidate_valid \
  --require-nonzero replay_liq.w2_retire_record_commit_row_candidate_blocked_by_no_metadata \
  --require-zero replay_liq.w2_retire_record_commit_row_fill_candidate \
  generated/r583-replay-retire-record-commit-row-candidate-xcheck/report/frontend_fetch_rf_alu_sideband_stats.json
```
