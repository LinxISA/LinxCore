# LoadReplayReturnPipeW2RetireRecordLifecycleRequestProbe

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2RetireRecordLifecycleRequestProbe.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/LoadReplayReturnPipeW2RetireRecordLifecycleRequestProbeSpec.scala`
- Integrated user: `rtl/LinxCore/chisel/src/main/scala/linxcore/top/LinxCoreFrontendFetchRfAluTraceTop.scala`
- Related contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2RetireRecord.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2ReplayRowLifecycleReady.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2ReplayRowLifecycleRequestControl.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2ReplayRowLifecycleCommitPermit.scala`
- Contract IDs: `LC-CHISEL-LSU-REPLAY-PIPE-W2-RETIRE-RECORD-LIFECYCLE-REQUEST-PROBE-001`

## Purpose

`LoadReplayReturnPipeW2RetireRecordLifecycleRequestProbe` is the R580/R581
diagnostic bridge between the explicit W2 retire record and the existing live
replay-row lifecycle request path. R579 proved the retained record is consumed
only when its identity matches exactly one resolved LIQ row. R580 asks the next
question without mutating LIQ: when the retained record and lifecycle row are
ready, are the atomic request, row-fill candidate, and row-fill enable aligned
well enough to promote that retained record into the live request/commit/clear
owners?

The module deliberately has no `clearResolvedValid`, row-fill, ROB resolve, or
side-effect outputs. It only reports candidate and blocker signals so the
generated RTL/QEMU sideband can identify the first missing live prerequisite.

## Interface

| Direction | Signal | Description |
|---|---|---|
| input | `enable` / `flush` | Reduced replay-LIQ arm and flush suppression. |
| input | `retireRecordValid` | The explicit W2 retire record is retained. |
| input | `lifecycleRowClearReady` | The retained record matches exactly one resolved LIQ row. |
| input | `atomicRequestActive` | Existing W2 atomic live request is active. |
| input | `rowFillCandidateValid` | Existing W2 commit-row candidate is valid. |
| input | `rowFillEnable` | Existing W2 row-fill enable is live. |
| output | `requestCandidate` | Retained record plus unique lifecycle row are both present. |
| output | `livePromotionCandidate` | Request candidate also has atomic request, row-fill candidate, and row-fill enable. |
| output | blocker signals | Disabled, flush, no lifecycle row, no atomic request, no row-fill candidate, no row-fill enable, and invalid row-fill-without-request diagnostics. |

## Logic Design

```text
active = enable && !flush
requestCandidate = active && retireRecordValid && lifecycleRowClearReady

livePromotionCandidate =
  requestCandidate &&
  atomicRequestActive &&
  rowFillCandidateValid &&
  rowFillEnable

blockedByNoLifecycleRow = active && retireRecordValid && !lifecycleRowClearReady
blockedByNoAtomicRequest = requestCandidate && !atomicRequestActive
blockedByNoRowFillCandidate = requestCandidate && atomicRequestActive && !rowFillCandidateValid
blockedByNoRowFillEnable =
  requestCandidate && atomicRequestActive && rowFillCandidateValid && !rowFillEnable
```

## Integration Status

R580 introduces this probe as a standalone LSU module and focused test. A first
attempt to expose it through `LinxCoreFrontendFetchRfAluTraceTop` showed that
the generated top was already at the JVM method-size limit; the additional
module and IO pushed `LinxCoreFrontendFetchRfAluTraceTop.<init>` over that
limit.

R581 completes the needed top-maintenance step by grouping W2 module
construction behind `LinxCoreFrontendFetchRfAluTraceTopW2Modules`, then wires
the probe beside the R579 retire-record lifecycle matcher. The probe consumes
the retained-record valid bit, retained-record lifecycle `rowClearReady`, the
existing W2 atomic request, the existing commit-row candidate, and the existing
row-fill enable. Its outputs are diagnostic sideband only; they do not mutate
LIQ, select row-fill data, or replace the live physical-W2 request/commit/clear
path.

The R581 generated RTL/QEMU gate at
`generated/r581-replay-w2-retire-record-request-probe-xcheck` passes with
manifest `status="pass"`, `comparator_status=0`, `compared_rows=18`,
`mismatch_count=0`, and zero QEMU/DUT CBSTOP rows. Sideband schema v31 records
`w2_retire_record_lifecycle_request_candidate=3`,
`w2_retire_record_lifecycle_request_blocked_by_no_lifecycle_row=0`,
`w2_retire_record_lifecycle_request_blocked_by_no_row_fill_candidate=0`,
`w2_retire_record_lifecycle_request_blocked_by_no_row_fill_enable=0`,
`w2_retire_record_lifecycle_live_promotion_candidate=0`, and
`w2_retire_record_lifecycle_request_blocked_by_no_atomic_request=3`.

The next promotion packet should align the retained-record request candidate
with an atomic request source. The current blocker is not missing lifecycle-row
identity, row-fill candidate, or row-fill enable; it is that the existing live
atomic request is absent when the retained record is ready.

R582 adds `LoadReplayReturnPipeW2RetireRecordAtomicRequestProbe` to classify
the retained-record evidence without depending on `atomicRequestActive`.
That probe shows the retained record still lacks an aligned row-fill candidate:
`w2_retire_record_atomic_request_evidence_valid=3`,
`w2_retire_record_atomic_request_blocked_by_no_row_fill_candidate=3`, and
`w2_retire_record_atomic_request_row_fill_candidate_aligned=0`.
The next owner should build a retained-record commit-row or row-fill candidate
source before retrying atomic request promotion.

## Deferred Owners

- Live retire-record lifecycle request/commit/clear selection.
- Live atomic request source alignment for the retained-record lifecycle
  request candidate.
- Retire-record commit-row or row-fill source if physical W2 row-fill signals
  do not align with the retained record after W2 clears.
- LIQ `clearResolvedValid/index` mutation from the retained-record lifecycle
  row, after atomic side-effect and ROB resolve ordering are proven.

## Verification

Focused gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2RetireRecordLifecycleRequestProbe
bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop
LINXCORE_REPLAY_LIQ_EARLY_STA_ADDRESS=1 \
LINXCORE_REPLAY_LIQ_W2_COMPLETION_DELAY_CYCLES=12 \
FETCH_REPLAY_LIQ_REQUIRE_NONZERO=wait_replay_capture_accepted,replay_queue_out_fire,liq_alloc_accepted,lret_w2_slot_accepted,w2_promotion_live \
bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh \
  --fixture replay-ldi-sdi-ldi-sdi-ldi-ldi-loop \
  --build-dir generated/r581-replay-w2-retire-record-request-probe-xcheck \
  --expected-rows 18 --capture-rows 32 --max-seconds 10 \
  --reduced-store-replay-liq --disable-store-memory-mutation \
  --allow-residual-replay-liq-wait
```

Reference tests cover full live-promotion candidate formation, missing
lifecycle row, missing atomic request, missing row-fill candidate, missing
row-fill enable, disabled/flush suppression, and Chisel elaboration.
