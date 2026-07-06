# LoadReplayReturnPipeW2CommitRowCandidate

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2CommitRowCandidate.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/LoadReplayReturnPipeW2CommitRowCandidateSpec.scala`
- Integrated user: `rtl/LinxCore/chisel/src/main/scala/linxcore/top/LinxCoreFrontendFetchRfAluTraceTop.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/iex/pipe/lda_pipe.cpp`: `LDAPipe::Work`, `LDAPipe::runW2`, `LDAPipe::move`
  - `model/LinxCoreModel/model/iex/pipe/agu_pipe.cpp`: `AGUPipe::Work`, `AGUPipe::runW2`, `AGUPipe::move`
  - `model/LinxCoreModel/model/ModelCommon/SimInstInfo.cpp`: `SimInstInfo::GenRFReqBus`, `SimInstInfo::GenRslvBus`
  - `model/LinxCoreModel/model/iex/iex.cpp`: `IEX::setMemData`
- Related Chisel contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2Slot.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2CommitRowTraceSource.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2RobCompleteSource.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2RowFillEnableControl.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/backend/ReducedRobCompletionArbiter.scala`
- Contract IDs: `LC-CHISEL-LSU-REPLAY-PIPE-W2-COMMIT-ROW-CANDIDATE-001`

## Purpose

`LoadReplayReturnPipeW2CommitRowCandidate` names the replay-load commit-row
replacement boundary for the W2 return pipe. It shapes a `CommitTraceRow` from
resident W2 slot evidence, instruction metadata, source trace data, and returned
load data before the replay ROB-complete source may replace the ROB row image.

R366 kept the integrated top dormant by wiring resident W2 slot fields while
tying instruction metadata, source trace, and `rowFillEnable` false. R367
replaces the literal row-fill tie-off with
`LoadReplayReturnPipeW2RowFillEnableControl`, R372 replaces the metadata and
source-trace tie-offs with `LoadReplayReturnPipeW2CommitRowTraceSource`, and
R373 feeds instruction metadata from the read-only ROB row commit-trace lookup.
R377 feeds source traces from the resident W2 slot payload that R376 carried
through the return pipe. Replay completion still cannot replace
allocation/rename row contents because `completeRowValid` remains gated by the
R367 row-fill enable control.

## Interface

| Direction | Signal | Description |
|---|---|---|
| input | `enable` / `flush` | Reduced replay-LIQ integration arm and flush suppression. |
| input | `rowFillEnable` | Final live row-fill arm; false in the reduced top. |
| input | `slotOccupied` / `slotTargetIsAgu` / `slotTargetIsLda` | Resident W2 slot presence and one-hot pipe target. |
| input | `slotBid` / `slotGid` / `slotRid` | Native row identities copied into commit sidebands. |
| input | `slotPc` / `slotAddr` / `slotSize` / `slotDst` / `slotData` | Load commit row PC, memory, destination, and returned-data fields. |
| input | `instructionValid` / `instructionRaw` / `instructionLen` | Deferred instruction row metadata provider. |
| input | `sourceTraceValid` / `source0` / `source1` | Deferred source operand trace provider. |
| output | `rowFillCandidateValid` | All evidence except final live row-fill arm is coherent. |
| output | `completeRowValid` / `completeRow` | Commit row replacement payload gated by `rowFillEnable`. |
| output | blocker signals | Disabled, flush, no-slot, invalid target, invalid identity, missing metadata, missing source trace, invalid size, destination, and row-fill-disabled blockers. |

## Logic Design

```text
active = enable && !flush
candidateValid = active && slotOccupied
targetValid = slotTargetIsAgu xor slotTargetIsLda
identityValid = slotBid.valid && slotGid.valid && slotRid.valid
metadataReady = instructionValid && instructionLen != 0
sourceTraceReady = sourceTraceValid
sizeSupported = slotSize in {1, 2, 4, 8}
destinationGpr = slotDst.valid && slotDst.kind == Gpr

rowFillCandidateValid =
  candidateValid && targetValid && identityValid &&
  metadataReady && sourceTraceReady && sizeSupported && destinationGpr

completeRowValid = rowFillCandidateValid && rowFillEnable
```

The shaped row uses the resident native identities for `identity.*` and
`rob.*`, uses `slotData` as both `wb.data` and `mem.rdata`, marks `mem.isStore`
false, and computes `nextPc = slotPc + instructionLen`. Source trace valid bits
are still gated by `completeRowValid`.

## Integration

R366 wires the candidate in `LinxCoreFrontendFetchRfAluTraceTop` before
`LoadReplayReturnPipeW2RobCompleteSource`:

- resident W2 slot fields feed the candidate;
- instruction metadata comes from the R372/R373 trace-source owner and source
  trace comes from the resident W2 slot source payload joined in R377;
- R367 `LoadReplayReturnPipeW2RowFillEnableControl` drives `rowFillEnable`;
- `completeRowValid` and `completeRow` feed the row-fill inlet on
  `LoadReplayReturnPipeW2RobCompleteSource`;
- diagnostics are exposed as
  `reducedLoadReplayLiqLretPipeW2CommitRowCandidate*`.

Because the R367 control cannot assert `rowFillEnable` in the integrated top,
the candidate can expose coherent row-fill evidence only as a diagnostic and
cannot assert `completeRowValid`; `ReducedRobCompletionArbiter` still receives
no replay row replacement payload.

R552 adds generated-RTL sideband counters for this candidate's specific
blockers. The replay-loop fixture shows the resident W2 candidate is present
but lacks instruction metadata: `w2_commit_row_candidate_valid=74`,
`w2_commit_row_fill_candidate=0`,
`w2_commit_row_candidate_blocked_by_no_metadata=74`,
`w2_commit_row_candidate_blocked_by_no_source_trace=0`,
`w2_commit_row_candidate_blocked_by_invalid_size=0`, and
`w2_commit_row_candidate_blocked_by_non_gpr_destination=0`. The next owner is
therefore the ROB commit-trace lookup query/provider path, not source-trace
provenance, load size, or destination kind.

R553 wires that provider through a top-level LRET-drain metadata latch and keeps
ROB deallocation at the R547 drain-release boundary. The replay-loop fixture at
`generated/r553-replay-w2-drain-metadata-latch/report/crosscheck_manifest.json`
passes with 9 compared rows, zero mismatches, and zero QEMU/DUT CBSTOP rows.
The sideband report records `w2_commit_row_fill_candidate=33`,
`w2_row_fill_candidate_valid=33`,
`w2_commit_row_candidate_blocked_by_no_metadata=41`,
`w2_commit_row_candidate_blocked_by_no_source_trace=0`, and
`w2_row_fill_prerequisites_ready=0`. The remaining blocker is now row-fill
prerequisite/lifecycle readiness before `rowFillEnable` can promote
`completeRowValid`.

## Deferred Owners

- Replay-row lifecycle readiness and final live request promotion through the
  R367 row-fill enable owner.
- Non-GPR destination commit-row policy if replay loads to local T/U state must
  become visible in the monitored commit stream.

## Verification

Focused gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2CommitRowCandidate
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2RobCompleteSource
bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop
```

Reference tests cover valid row-fill evidence, dormant row-fill disable, missing
instruction metadata, missing source trace, invalid target, invalid identity,
invalid size, non-GPR destination, disabled/flush/empty suppression, and Chisel
elaboration.
