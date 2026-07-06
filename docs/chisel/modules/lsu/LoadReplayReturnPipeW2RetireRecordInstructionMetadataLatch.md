# LoadReplayReturnPipeW2RetireRecordInstructionMetadataLatch

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2RetireRecordInstructionMetadataLatch.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/LoadReplayReturnPipeW2RetireRecordInstructionMetadataLatchSpec.scala`
- Integrated user: `rtl/LinxCore/chisel/src/main/scala/linxcore/top/LinxCoreFrontendFetchRfAluTraceTop.scala`
- Related contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2RetireRecord.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2RetireRecordCommitRowCandidate.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2Slot.scala`
- Contract IDs: `LC-CHISEL-LSU-REPLAY-PIPE-W2-RETIRE-RECORD-INSTRUCTION-METADATA-001`

## Purpose

`LoadReplayReturnPipeW2RetireRecordInstructionMetadataLatch` is the R584
diagnostic owner for retaining instruction metadata for a W2 retire record.
R583 proved that a retained record reaches the commit-row candidate wrapper,
but the candidate is blocked by missing instruction metadata. This module asks
whether metadata can be captured at retire-record creation from the resident W2
slot, with LRET drain metadata as a fallback.

The module is diagnostic only. It supplies metadata and blocker sidebands to the
retained commit-row candidate path, but it does not enable retained row fill,
LIQ clear, ROB mutation, RF writeback, wakeup, or W2 clear/refill.

## Interface

| Direction | Signal | Description |
|---|---|---|
| input | `enable` / `flush` | Reduced replay-LIQ arm and flush suppression. |
| input | `captureAccepted` / `capturePayloadRid` | Retire-record capture pulse and RID for the payload being recorded. |
| input | `w2Rid` / `w2InstructionValid` / `w2InstructionRaw` / `w2InstructionLen` | Resident W2 row identity and instruction metadata available during capture. |
| input | `drainInstructionCapture` / `drainRid` / `drainInstructionRaw` / `drainInstructionLen` | Later LRET drain metadata fallback. |
| input | `recordFire` / `recordFireRid` | Retained-record consume event used to clear the latched metadata. |
| input | `recordValid` / `recordRid` | Current retained record used to qualify provider validity. |
| output | `captureIntent` | Active retire-record capture was accepted. |
| output | `capturePayloadRidValid` / `w2RidValid` / `w2RidMatchesCapture` / `w2MetadataReady` | Capture prerequisite diagnostics. |
| output | `captureFromW2` / `captureFromDrain` | Selected metadata capture source. W2 has priority over drain fallback. |
| output | blocker signals | Missing capture RID, missing W2 RID, RID mismatch, and missing W2 metadata. |
| output | `clearAccepted` | The latched metadata was cleared by matching retained-record fire. |
| output | `providerValid` / `providerRaw` / `providerLen` | Metadata provider for the retained commit-row candidate. |

## Logic Design

The latch has one registered RID/raw/length entry. When enabled and not
flushed, W2 capture has priority:

```text
captureFromW2 =
  captureAccepted &&
  capturePayloadRid.valid &&
  w2Rid.valid &&
  capturePayloadRid == w2Rid &&
  w2InstructionValid &&
  w2InstructionLen != 0
```

If W2 capture does not fire, a valid drain metadata capture can populate the
same entry. Provider validity is asserted only when the current retained record
RID matches the latched RID. A matching retained-record fire clears the entry.

The blocker outputs intentionally report capture intent and why W2 capture did
not fire. They are sideband evidence for choosing the next owner, not promotion
conditions for mutation.

## Integration Status

R584 wires the latch into `LinxCoreFrontendFetchRfAluTraceTop`, feeds its
provider into `LoadReplayReturnPipeW2RetireRecordCommitRowCandidate`, and bumps
the replay-LIQ sideband stats schema to v34. The generated RTL/QEMU gate at
`generated/r584-replay-retire-record-metadata-probe-xcheck` passes with
manifest `status="pass"`, `comparator_status=0`, `compared_rows=18`,
`mismatch_count=0`, and zero QEMU/DUT CBSTOP rows.

Sideband schema v34 records:

- `w2_retire_record_commit_row_candidate_valid=3`
- `w2_retire_record_commit_row_fill_candidate=0`
- `w2_retire_record_commit_row_candidate_blocked_by_no_metadata=3`
- `w2_retire_record_instruction_metadata_capture_intent=3`
- `w2_retire_record_instruction_metadata_w2_metadata_ready=77`
- `w2_retire_record_instruction_metadata_w2_rid_matches_capture=0`
- `w2_retire_record_instruction_metadata_capture_from_w2=0`
- `w2_retire_record_instruction_metadata_capture_from_drain=6`
- `w2_retire_record_instruction_metadata_capture_blocked_by_rid_mismatch=3`
- `w2_retire_record_instruction_metadata_provider_valid=0`

The next owner is retained-record payload source identity. The current top
captures the live LRET enqueue payload while the retire-record capture
condition is driven by the resident W2 slot clear. Under the delayed-W2 fixture,
those RIDs do not match. Do not enable retained row fill or LIQ clear until the
retained record is sourced from the resident W2 row or an equivalent
model-derived payload boundary is proven.

## Verification

Focused gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2RetireRecordInstructionMetadataLatch
bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop
LINXCORE_REPLAY_LIQ_EARLY_STA_ADDRESS=1 \
LINXCORE_REPLAY_LIQ_W2_COMPLETION_DELAY_CYCLES=12 \
FETCH_REPLAY_LIQ_REQUIRE_NONZERO=wait_replay_capture_accepted,replay_queue_out_fire,liq_alloc_accepted,lret_w2_slot_accepted,w2_promotion_live \
bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh \
  --fixture replay-ldi-sdi-ldi-sdi-ldi-ldi-loop \
  --build-dir generated/r584-replay-retire-record-metadata-probe-xcheck \
  --expected-rows 18 --capture-rows 32 --max-seconds 10 \
  --reduced-store-replay-liq --disable-store-memory-mutation \
  --allow-residual-replay-liq-wait
python3 tools/chisel/validate_frontend_fetch_rf_alu_sideband_stats.py \
  --expect-reduced-store-replay-liq \
  --require-nonzero replay_liq.w2_retire_record_commit_row_candidate_valid \
  --require-nonzero replay_liq.w2_retire_record_instruction_metadata_capture_intent \
  --require-nonzero replay_liq.w2_retire_record_instruction_metadata_capture_blocked_by_rid_mismatch \
  --require-zero replay_liq.w2_retire_record_instruction_metadata_capture_from_w2 \
  generated/r584-replay-retire-record-metadata-probe-xcheck/report/frontend_fetch_rf_alu_sideband_stats.json
```

skill-evolve: no-update (R584 applies the existing prove-before-promote and top-split rules; the new finding is packet-local payload-source evidence).
