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
`LoadReplayReturnPipeW2RowFillEnableControl`, but that control still keeps
`rowFillEnable=false` because the atomic live request and replay-row lifecycle
readiness remain absent. This exposes the missing row-fill owners without
allowing replay completion to replace allocation/rename row contents.

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
- instruction metadata and source trace are tied absent;
- R367 `LoadReplayReturnPipeW2RowFillEnableControl` drives `rowFillEnable`;
- `completeRowValid` and `completeRow` feed the row-fill inlet on
  `LoadReplayReturnPipeW2RobCompleteSource`;
- diagnostics are exposed as
  `reducedLoadReplayLiqLretPipeW2CommitRowCandidate*`.

Because the R367 control cannot assert `rowFillEnable` in the integrated top,
the candidate cannot assert `completeRowValid` and `ReducedRobCompletionArbiter`
still receives no replay row replacement payload.

## Deferred Owners

- A ROB-row or replay-row metadata lookup that supplies instruction raw/length
  for the resident replay W2 row.
- Source operand trace reconstruction for load commit rows.
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
