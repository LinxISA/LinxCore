# LoadReplayReturnPipeW2SideEffectLiveControl

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2SideEffectLiveControl.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/LoadReplayReturnPipeW2SideEffectLiveControlSpec.scala`
- Integrated user: `rtl/LinxCore/chisel/src/main/scala/linxcore/top/LinxCoreFrontendFetchRfAluTraceTop.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/iex/pipe/lda_pipe.cpp`
    - `LDAPipe::runW2`
  - `model/LinxCoreModel/model/iex/pipe/agu_pipe.cpp`
    - `AGUPipe::runW2`
- Related Chisel contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2AtomicLiveRequestControl.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2CompletionCandidate.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2ResolveSinkReady.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2WritebackSinkReady.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2WakeupSinkReady.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2SideEffectReady.scala`
- Contract IDs: `LC-CHISEL-LSU-REPLAY-PIPE-W2-SIDE-EFFECT-LIVE-CONTROL-001`

## Purpose

`LoadReplayReturnPipeW2SideEffectLiveControl` is the shared live-enable owner
for the W2 side-effect sinks and their pre-arbiter inputs. LinxCoreModel
`LDAPipe::runW2` and
`AGUPipe::runW2` treat a resident W2 instruction as one side-effect point:
optional RF writeback, PE/ROB resolve publication, and scalar/local wakeup
occur before the pipe stage can be consumed.

R357 replaces the previous three sink-local `false.B` ties with one module that
computes the required sink mask and the live-enable mask. R361 extends the same
live-enable outputs to the R358/R359/R360 pre-arbiter input boundaries. R363
feeds `liveRequested` from `LoadReplayReturnPipeW2AtomicLiveRequestControl`
instead of a helper-local constant. That owner still ties its `requestEnable`
false, so resolve, writeback, and wakeup sinks and arbiter inputs stay dormant
and no architectural state changes.

## Interface

| Direction | Signal | Description |
|---|---|---|
| input | `enable` | Replay-LIQ returned-load pipe wrapper is active. |
| input | `flush` | Suppresses live W2 side effects during reduced-store flush. |
| input | `liveRequested` | Request to arm live W2 side-effect sinks. Current top drives this from R363 atomic live-request control, whose request gate is false. |
| input | `sideEffectCandidateValid` | The resident W2 entry is a legal side-effect candidate. Current top feeds R334 `resolveRequired`. |
| input | `resolveRequired` | Resident W2 entry must publish resolve state. |
| input | `writebackRequired` | Resident W2 entry must publish a reduced scalar RF writeback. |
| input | `wakeupRequired` | Resident W2 entry must publish ready-table/issue-wakeup state. |
| output | `active` | Enabled and not flushed. |
| output | `requestActive` | Active plus top-level live request. |
| output | `candidateValid` | Active candidate after enable/flush gating. |
| output | `requiredMask` | `{wakeupRequired, writebackRequired, resolveRequired}`. |
| output | `liveEnableMask` | Required mask when the candidate and live request are both present, otherwise zero. |
| output | `anyRequired` | At least one W2 sink is required. Legal W2 completions normally require resolve. |
| output | `allRequiredLiveEnabled` | Candidate, request, and at least one required sink are all present. |
| output | `resolveLiveEnable` | Live enable for `LoadReplayReturnPipeW2ResolveSinkReady` and `LoadReplayReturnPipeW2ResolveArbiterInput`. |
| output | `writebackLiveEnable` | Live enable for `LoadReplayReturnPipeW2WritebackSinkReady` and `LoadReplayReturnPipeW2WritebackArbiterInput`. |
| output | `wakeupLiveEnable` | Live enable for `LoadReplayReturnPipeW2WakeupSinkReady` and `LoadReplayReturnPipeW2WakeupArbiterInput`. |
| output | blocker signals | Disabled, flush, no-candidate, no-required-sink, and live-disabled diagnostics. |

## Logic Design

```text
active = enable && !flush
requestActive = active && liveRequested
candidateValid = active && sideEffectCandidateValid
requiredMask = {wakeupRequired, writebackRequired, resolveRequired}
liveAllowed = candidateValid && liveRequested && requiredMask != 0
liveEnableMask = liveAllowed ? requiredMask : 0
```

The per-sink live enables are direct bits of `liveEnableMask`. Optional
writeback and wakeup sinks are not enabled when their corresponding requirement
bit is clear. Resolve remains represented as bit 0 so the mask shape matches
R343 payload planning, R344 issue permit, R345 completion permit, and R346 fire
vector diagnostics.

## Integration

`LinxCoreFrontendFetchRfAluTraceTop` wires this module in the W2 request
payload helper:

- `liveRequested` comes from R363 atomic live-request control, whose
  `requestEnable` remains false;
- `requiredMask` is derived from `LoadReplayReturnPipeW2CompletionCandidate`;
- `resolveLiveEnable`, `writebackLiveEnable`, and `wakeupLiveEnable` drive
  the R336/R337/R338 sink owners and the R358/R359/R360 pre-arbiter input
  owners instead of local constants;
- compact diagnostics are exposed under
  `reducedLoadReplayLiqLretPipeW2SideEffectLiveControl*`.

Because the atomic request remains false, R335 side-effect readiness still
blocks completion, the R358/R359/R360 arbiter-input valid outputs remain false,
R334 does not clear the W2 slot, and R351/R356 promotion remains dormant.

## Deferred Owners

- Replace the R363 atomic request control's false `requestEnable` only after
  ROB/PE resolve, replay RF writeback, ready-table/issue wakeup, W2 clear, and
  replay-row lifecycle mutation can commit the same resident W2 entry atomically.
- Extend the single reduced-pipe live request into per-return-pipe policy when
  multiple returned-load pipes are instantiated.
- Feed real sink capacity and backpressure into the live-control request owner
  before enabling side-effect mutation in generated RTL.

## Verification

Focused gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2SideEffectLiveControl
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2AtomicLiveRequestControl
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2ResolveSinkReady
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2WritebackSinkReady
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2WakeupSinkReady
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2SideEffectReady
bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop
FETCH_REDUCED_STORE_REPLAY_LIQ=1 BUILD_DIR=generated/r357-replay-pipe-w2-side-effect-live-control-xcheck bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh
```

Reference tests cover live-disabled default behavior, required-sink mask
selection, disabled/flush blockers, no-candidate/no-required-sink blockers,
and Chisel elaboration.
