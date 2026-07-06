# LoadReplayReturnPipeW2RobCompleteSource

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2RobCompleteSource.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/LoadReplayReturnPipeW2RobCompleteSourceSpec.scala`
- Integrated user: `rtl/LinxCore/chisel/src/main/scala/linxcore/top/LinxCoreFrontendFetchRfAluTraceTop.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/iex/pipe/lda_pipe.cpp`: `LDAPipe::move`, `LDAPipe::runW2`
  - `model/LinxCoreModel/model/iex/pipe/agu_pipe.cpp`: `AGUPipe::move`, `AGUPipe::runW2`
  - `model/LinxCoreModel/model/ModelCommon/SimInstInfo.cpp`: `SimInstInfo::GenRFReqBus`, `SimInstInfo::GenRslvBus`
  - `model/LinxCoreModel/model/iex/iex.cpp`: `IEX::setMemWakeup`
  - `model/LinxCoreModel/model/iex/iex_iq.cpp`: `IssueQueue::WakeupIQTag`
- Related Chisel contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2ResolveArbiterInput.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2CommitRowCandidate.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/backend/ReducedRobCompletionArbiter.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2ResolveSinkReady.scala`
- Contract IDs: `LC-CHISEL-LSU-REPLAY-PIPE-W2-ROB-COMPLETE-SOURCE-001`

## Purpose

`LoadReplayReturnPipeW2RobCompleteSource` is the replay-load W2 boundary that
turns a live resolve candidate into a ROB completion-port request. It is the
first typed handoff from the replay W2 resolve path toward
`DecodeRenameROBPath` completion.

The model runs W2 side effects from the same resident instruction:
`LDAPipe::runW2` and `AGUPipe::runW2` generate RF writeback, write a
`PEResolveBus`, then wake scalar-local dependents. The Chisel reduced path now
has named RF writeback, resolve, wakeup, clear, and promotion boundaries. This
module only prepares the ROB completion side of the resolve boundary. It does
not enable the live replay request by itself.

## Interface

| Direction | Signal | Description |
|---|---|---|
| input | `enable` | Reduced replay-LIQ integration is enabled. |
| input | `flush` | Replay path flush suppresses completion output. |
| input | `resolveValid` | Live-gated W2 resolve arbiter input is valid. |
| input | `resolveRid` | Native ROB RID to complete. |
| input | `executeCompleteValid` | Ordinary execute completion owns the ROB completion port this cycle. |
| input | `completeRowInputValid` / `completeRowInput` | Optional replay load row replacement payload from R366. |
| output | `sinkReady` | Structural readiness for the W2 resolve sink; false when execute uses the completion port. |
| output | `active` | `enable && !flush`. |
| output | `candidateValid` | Active live resolve candidate is present. |
| output | `completeValid` | Replay ROB completion can issue this cycle. |
| output | `completeRobValue` | RID value for the completion port when `completeValid` is true. |
| output | `completeRowValid` / `completeRow` | Selected row replacement payload when both completion and row-fill input are valid. |
| output | blocker signals | Disabled, flush, no-resolve, invalid-RID, and execute-port pressure diagnostics. |

## Logic Design

```text
active = enable && !flush
candidateValid = active && resolveValid
legalCandidate = candidateValid && resolveRid.valid
sinkReady = !executeCompleteValid
completeValid = legalCandidate && sinkReady
completeRobValue = completeValid ? resolveRid.value : 0
completeRowValid = completeValid && completeRowInputValid
```

`sinkReady` intentionally depends only on the structural ROB completion-port
conflict with execute. It feeds `LoadReplayReturnPipeW2ResolveSinkReady`, so a
future live replay W2 request cannot fire resolve side effects when the execute
completion port is already occupied.

`completeRowValid` only passes through when a replay completion is actually
emitted. In R366 the integrated top feeds this inlet from
`LoadReplayReturnPipeW2CommitRowCandidate`, but that candidate still ties
metadata/source/fill enables false, so `ROBEntryBank` preserves the row written
by allocation/rename-update.

## Integration

R364 wires the source after
`LoadReplayReturnPipeW2ResolveArbiterInput` in
`LinxCoreFrontendFetchRfAluTraceTop`:

- `resolveValid` and `resolveRid` come from the live-gated W2 resolve arbiter
  input;
- `completeRowInput*` comes from the R366 replay W2 commit-row candidate;
- `sinkReady` drives the structural `sinkReady` input of
  `LoadReplayReturnPipeW2ResolveSinkReady`;
- `complete*` drives the replay side of `ReducedRobCompletionArbiter`;
- diagnostics are exposed under
  `reducedLoadReplayLiqLretPipeW2RobCompleteSource*`.

Because R363 still ties `LoadReplayReturnPipeW2AtomicLiveRequestControl`
`requestEnable` low, the source is dormant in the integrated top and cannot
complete a replay row yet.

R587 adds `LoadReplayReturnPipeW2RetireRecordRobCompleteFallbackGuard` beside
this physical source. In the focused replay-LIQ fixture, the physical source
already completes the same RIDs later visible through retained retire records:
sideband schema v37 records
`w2_retire_record_rob_fallback_capture_physical_complete=5` and
`w2_retire_record_rob_fallback_duplicate_physical_complete=5`, while retained
fallback completion remains disabled and zero. This preserves single ownership
of ROB completion before any retained-record fallback can be promoted.

The same packet extracts the top-level completion-port assignments into
`LinxCoreFrontendFetchRfAluTraceTopRobCompleteArbiterWiring`. That helper is a
constructor-size maintenance split only; execute completion still has priority
over replay completion through `ReducedRobCompletionArbiter`.

R588 keeps this ROB ownership intact while adding the parallel RF writeback
fallback guard. The v38 sideband records the ROB duplicate counters unchanged
and adds nonzero RF duplicate counters, so retained ROB completion and retained
RF writeback remain disabled together until a no-physical-side-effect case is
proven.

## Deferred Owners

- Live replay load commit-row fill after instruction metadata and source trace
  providers are wired.
- Live replay-row lifecycle mutation after resolve, RF writeback, wakeup, and
  W2 clear all succeed.
- PE/thread resolve-array publication beyond the reduced ROB completion port.
- Branch/recovery side effects carried by the full `PEResolveBus`.

## Verification

Focused gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2RobCompleteSource
bash tools/chisel/run_chisel_tests.sh --only ReducedRobCompletionArbiter
bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop
FETCH_REDUCED_STORE_REPLAY_LIQ=1 BUILD_DIR=generated/r364-replay-w2-rob-complete-source-xcheck bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh
```

Reference tests cover idle-port completion, execute-port blocking,
row-fill input pass-through, disabled/flush suppression, invalid RID
suppression, active no-resolve diagnostics, and Chisel elaboration.
