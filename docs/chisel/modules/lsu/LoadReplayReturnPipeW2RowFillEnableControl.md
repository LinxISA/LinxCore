# LoadReplayReturnPipeW2RowFillEnableControl

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2RowFillEnableControl.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/LoadReplayReturnPipeW2RowFillEnableControlSpec.scala`
- Integrated user: `rtl/LinxCore/chisel/src/main/scala/linxcore/top/LinxCoreFrontendFetchRfAluTraceTop.scala`
- LinxCoreModel evidence:
  - `tools/LinxCoreModel/model/lsu/load_unit/ldq.cpp`: `LDQInfo::handleMerge`, `LDQInfo::returnData`
  - `tools/LinxCoreModel/model/iex/iex.cpp`: `IEX::receiveFromLSU`, `IEX::setMemData`
  - `tools/LinxCoreModel/model/pe/PECommon/PROBCommon.cpp`: `ROBState::resolveData`
  - `tools/LinxCoreModel/model/iex/pipe/lda_pipe.cpp`: `LDAPipe::Work`, `LDAPipe::runW2`, `LDAPipe::move`
  - `tools/LinxCoreModel/model/iex/pipe/agu_pipe.cpp`: `AGUPipe::Work`, `AGUPipe::runW2`, `AGUPipe::move`
  - `tools/LinxCoreModel/model/ModelCommon/SimInstInfo.cpp`: `SimInstInfo::GenRFReqBus`, `SimInstInfo::GenRslvBus`
- Related Chisel contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2CommitRowCandidate.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2SideEffectFireComplete.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2ClearCommitGuard.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2AtomicLiveRequestControl.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2ReplayRowLifecycleReady.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2ReplayRowLifecycleRequestControl.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2ReplayRowClearRequest.scala`
- Contract IDs: `LC-CHISEL-LSU-REPLAY-PIPE-W2-ROW-FILL-ENABLE-001`

## Purpose

`LoadReplayReturnPipeW2RowFillEnableControl` is the final dormant arm for replay
load commit-row replacement. It exists because LinxCoreModel does not have a
separate `CommitTraceRow` object: load-return data flows from LDQ return data
through `IEX::setMemData`, mutates ROB destination data through
`ROBState::resolveData`, then the resident W2 instruction emits RF writeback,
PE resolve, and scalar-local wakeup side effects before pipe movement.

The Chisel reduced commit monitor still needs a row replacement payload before
the replay ROB-completion source may update a monitored ROB row. R367 therefore
keeps row fill behind one atomic enable that requires:

- the R366 commit-row candidate is fully shaped;
- W2 side-effect fire completion is proven;
- clear/commit identity evidence agrees;
- live clear is armed by the disabled atomic live request path;
- the R368 replay-row lifecycle guard reports live lifecycle readiness.

The integrated top now feeds `replayRowLifecycleReady` from
`LoadReplayReturnPipeW2ReplayRowLifecycleReady`, whose live lifecycle-clear arm
comes from the R369 `LoadReplayReturnPipeW2ReplayRowClearRequest` owner. R370
names the lifecycle request arm that feeds that selector, but the R363 atomic
live request remains disabled upstream. R371 names the final lifecycle commit
permit fed by `rowFillEnable`, so `rowFillEnable` remains false and no LIQ
lifecycle clear can commit.

## Interface

| Direction | Signal | Description |
|---|---|---|
| input | `enable` / `flush` | Replay-LIQ integration arm and flush suppression. |
| input | `rowFillRequested` | Atomic live request evidence. Current top feeds R363 `requestActive`, which is false. |
| input | `rowFillCandidateValid` | R366 shaped commit-row candidate evidence. |
| input | `sideEffectFireComplete` | R350 post-fire side-effect completeness evidence. |
| input | `clearCommitReady` | R365 resident slot / resolve / ROB completion identity coherence. |
| input | `liveClearReady` | R365 commit-clear evidence plus the future live-clear arm. |
| input | `replayRowLifecycleReady` | R368 consumed-row lifecycle guard after the R369 clear-request owner arms lifecycle clear readiness from the R370 request-control output. Current top keeps the R363 atomic live request false. |
| output | `candidateValid` | Enabled, not flushed, and a row-fill candidate exists. |
| output | `atomicPrerequisitesReady` | All prerequisites except the explicit request are present. |
| output | `rowFillEnable` | Final live row-fill arm for `LoadReplayReturnPipeW2CommitRowCandidate` and the R371 lifecycle clear commit permit. |
| output | blocker signals | Disabled, flush, request-disabled, missing candidate, missing side-effect completion, missing clear/commit, live-clear-disabled, and missing replay-row-lifecycle diagnostics. |

## Logic Design

```text
active = enable && !flush
requestActive = active && rowFillRequested
candidateValid = active && rowFillCandidateValid

atomicPrerequisitesReady =
  candidateValid &&
  sideEffectFireComplete &&
  clearCommitReady &&
  liveClearReady &&
  replayRowLifecycleReady

rowFillEnable = requestActive && atomicPrerequisitesReady
```

The control intentionally treats replay-row lifecycle readiness as a hard input.
Until that owner exists, a replay load cannot replace a ROB commit row even if
side-effect and clear evidence become coherent.

## Integration

R367 wires this owner in `LinxCoreFrontendFetchRfAluTraceTop`:

- `rowFillRequested` comes from
  `LoadReplayReturnPipeW2AtomicLiveRequestControl.requestActive`;
- `rowFillCandidateValid` comes from
  `LoadReplayReturnPipeW2CommitRowCandidate`;
- `sideEffectFireComplete` comes from
  `LoadReplayReturnPipeW2SideEffectFireComplete`;
- clear evidence comes from `LoadReplayReturnPipeW2ClearCommitGuard`;
- `replayRowLifecycleReady` comes from
  `LoadReplayReturnPipeW2ReplayRowLifecycleReady.lifecycleReady`;
- `rowFillEnable` feeds the R371 lifecycle commit permit so a future lifecycle
  clear cannot mutate LIQ before row fill commits;
- R370 derives that lifecycle request from `rowFillCandidateValid` plus the
  disabled R363 atomic request, keeping the live path named but dormant;
- `rowFillEnable` now feeds the R366 commit-row candidate instead of a literal
  false.

This changes ownership only. In generated RTL the row-fill path remains dormant
because the atomic live request and replay-row lifecycle live-clear arm are
false.

R554 adds generated-RTL sideband counters for this control's ordered blockers.
The replay-loop fixture at
`generated/r554-replay-w2-lifecycle-diagnostics/report/crosscheck_manifest.json`
passes with 9 compared rows, zero mismatches, and zero QEMU/DUT CBSTOP rows.
The sideband report records `w2_row_fill_candidate_valid=33`,
`w2_row_fill_prerequisites_ready=0`, `w2_row_fill_enable=0`,
`w2_row_fill_blocked_by_request_disabled=33`,
`w2_row_fill_blocked_by_no_side_effect_commit=33`,
`w2_row_fill_blocked_by_no_clear_commit=0`,
`w2_row_fill_blocked_by_live_clear_disabled=0`, and
`w2_row_fill_blocked_by_no_replay_row_lifecycle=0`. These row-fill blockers
are downstream of the disabled atomic request/fire path; the current first
owner is the lifecycle row match that feeds pre-request policy evidence.

## Deferred Owners

- Real replay-row lifecycle consume/retire mutation for the selected returned
  load row.
- Live atomic request promotion after replay RF writeback, ROB/PE resolve,
  ready-table wakeup, W2 clear/refill, ROB row fill, and replay-row lifecycle
  mutation can commit the same resident W2 instruction.
- Multi-return-pipe policy when more than one returned-load pipe is present.

## Verification

Focused gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2RowFillEnableControl
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2CommitRowCandidate
bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop
FETCH_REDUCED_STORE_REPLAY_LIQ=1 BUILD_DIR=generated/r367-replay-w2-row-fill-enable-control-xcheck bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh
```

Reference tests cover the fully armed row-fill case, request-disabled dormancy,
ordered missing-prerequisite blockers, disabled/flush/no-candidate blockers, and
Chisel elaboration.
