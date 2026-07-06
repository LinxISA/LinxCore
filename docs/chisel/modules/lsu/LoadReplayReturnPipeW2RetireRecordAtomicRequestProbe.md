# LoadReplayReturnPipeW2RetireRecordAtomicRequestProbe

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2RetireRecordAtomicRequestProbe.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/LoadReplayReturnPipeW2RetireRecordAtomicRequestProbeSpec.scala`
- Integrated user: `rtl/LinxCore/chisel/src/main/scala/linxcore/top/LinxCoreFrontendFetchRfAluTraceTop.scala`
- Related contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2RetireRecord.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2RetireRecordLifecycleRequestProbe.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2CommitRowCandidate.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2RetireRecordCommitRowCandidate.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2RowFillEnableControl.scala`
- Contract IDs: `LC-CHISEL-LSU-REPLAY-PIPE-W2-RETIRE-RECORD-ATOMIC-REQUEST-PROBE-001`

## Purpose

`LoadReplayReturnPipeW2RetireRecordAtomicRequestProbe` is the R582 diagnostic
owner that treats the retained W2 retire record plus unique lifecycle row as
request evidence, independent of the existing physical-W2
`atomicRequestActive`. R581 showed that retained-record lifecycle request
candidates exist but do not overlap the physical-W2 atomic request. R582 asks
the next ordered question: if the retained record itself is the request
evidence, do the existing row-fill candidate and row-fill enable align with it?

The module is diagnostic only. It does not drive `requestActive`, row-fill,
LIQ clear, ROB resolve, RF writeback, ready-table wakeup, or W2 clear/refill.

## Interface

| Direction | Signal | Description |
|---|---|---|
| input | `enable` / `flush` | Reduced replay-LIQ arm and flush suppression. |
| input | `retireRecordValid` | The explicit W2 retire record is retained. |
| input | `lifecycleRowClearReady` | The retained record matches exactly one resolved LIQ row. |
| input | `rowFillCandidateValid` | Commit-row candidate valid bit being checked against retained request evidence. R582 used physical-W2 evidence; R583 top wiring uses retained-record candidate evidence. |
| input | `rowFillEnable` | Existing physical-W2 row-fill enable is live. |
| output | `requestEvidenceValid` | Retained record plus unique lifecycle row are both present. |
| output | `rowFillCandidateAligned` | Retained request evidence also sees an existing row-fill candidate. |
| output | `rowFillEnableAligned` | Retained request evidence also sees row-fill enable. |
| output | blocker signals | Disabled, flush, no lifecycle row, no row-fill candidate, no row-fill enable, and invalid row-fill-enable-without-evidence diagnostics. |

## Logic Design

```text
active = enable && !flush
requestEvidenceValid = active && retireRecordValid && lifecycleRowClearReady
rowFillCandidateAligned = requestEvidenceValid && rowFillCandidateValid
rowFillEnableAligned = rowFillCandidateAligned && rowFillEnable

blockedByNoLifecycleRow = active && retireRecordValid && !lifecycleRowClearReady
blockedByNoRowFillCandidate = requestEvidenceValid && !rowFillCandidateValid
blockedByNoRowFillEnable = rowFillCandidateAligned && !rowFillEnable
```

## Integration Status

R582 wires the probe into `LinxCoreFrontendFetchRfAluTraceTop` beside the R581
lifecycle request probe. The generated RTL/QEMU gate at
`generated/r582-replay-retire-record-atomic-request-probe-xcheck` passes with
manifest `status="pass"`, `comparator_status=0`, `compared_rows=18`,
`mismatch_count=0`, and zero QEMU/DUT CBSTOP rows.

Sideband schema v32 records:

- `w2_retire_record_atomic_request_evidence_valid=3`
- `w2_retire_record_atomic_request_blocked_by_no_lifecycle_row=0`
- `w2_retire_record_atomic_request_blocked_by_no_row_fill_candidate=3`
- `w2_retire_record_atomic_request_row_fill_candidate_aligned=0`
- `w2_retire_record_atomic_request_row_fill_enable_aligned=0`
- `w2_retire_record_atomic_request_blocked_by_no_row_fill_enable=0`

The next owner is therefore not retained-record lifecycle identity and not
physical-W2 atomic request generation. It is a retained-record commit-row or
row-fill candidate source that can align with the retained record after the
physical W2 slot has cleared.

R583 adds that retained-record candidate source and feeds it into this probe's
`rowFillCandidateValid` input. The gate at
`generated/r583-replay-retire-record-commit-row-candidate-xcheck` still records
`w2_retire_record_atomic_request_blocked_by_no_row_fill_candidate=3`, now
because the retained-record commit-row candidate is blocked by missing retained
instruction metadata:

- `w2_retire_record_commit_row_candidate_valid=3`
- `w2_retire_record_commit_row_fill_candidate=0`
- `w2_retire_record_commit_row_candidate_blocked_by_no_metadata=3`

The next owner is retained-record instruction metadata lifetime, not row-fill
enable.

## Deferred Owners

- Retained-record instruction metadata lifetime for the commit-row candidate.
- Retained-record row-fill enable and lifecycle clear request after candidate
  alignment is proven.
- LIQ mutation and architectural side effects from the retained-record path.

## Verification

Focused gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2RetireRecordAtomicRequestProbe
bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop
LINXCORE_REPLAY_LIQ_EARLY_STA_ADDRESS=1 \
LINXCORE_REPLAY_LIQ_W2_COMPLETION_DELAY_CYCLES=12 \
FETCH_REPLAY_LIQ_REQUIRE_NONZERO=wait_replay_capture_accepted,replay_queue_out_fire,liq_alloc_accepted,lret_w2_slot_accepted,w2_promotion_live \
bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh \
  --fixture replay-ldi-sdi-ldi-sdi-ldi-ldi-loop \
  --build-dir generated/r582-replay-retire-record-atomic-request-probe-xcheck \
  --expected-rows 18 --capture-rows 32 --max-seconds 10 \
  --reduced-store-replay-liq --disable-store-memory-mutation \
  --allow-residual-replay-liq-wait
```
