# LoadReplayReturnPipeW2CommitRowTraceSource

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2CommitRowTraceSource.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/LoadReplayReturnPipeW2CommitRowTraceSourceSpec.scala`
- Integrated user: `rtl/LinxCore/chisel/src/main/scala/linxcore/top/LinxCoreFrontendFetchRfAluTraceTop.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/iex/pipe/lda_pipe.cpp`: `LDAPipe::runW2`
  - `model/LinxCoreModel/model/iex/pipe/agu_pipe.cpp`: `AGUPipe::runW2`
  - `model/LinxCoreModel/model/ModelCommon/SimInstInfo.cpp`: `SimInstInfo::GenRFReqBus`, `SimInstInfo::GenRslvBus`
  - `model/LinxCoreModel/model/iex/iex.cpp`: `IEX::setMemData`
  - `model/LinxCoreModel/model/pe/PECommon/PROBCommon.cpp`: `ROBState::resolveData`
- Related Chisel contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2CommitRowCandidate.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2RowFillEnableControl.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2RobCompleteSource.scala`
- Contract IDs: `LC-CHISEL-LSU-REPLAY-PIPE-W2-COMMIT-ROW-TRACE-SOURCE-001`

## Purpose

`LoadReplayReturnPipeW2CommitRowTraceSource` names the deferred instruction
metadata and source-trace provider boundary for replay-load W2 commit-row
replacement.

The LinxCoreModel W2 path emits RF writeback and PE resolve side effects from
the resident `SimInstInfo`; the C++ model does not build a separate monitored
`CommitTraceRow`. The Chisel reduced monitor therefore needs a row-fill
payload that reconstructs instruction raw/length and source operand traces
before `LoadReplayReturnPipeW2CommitRowCandidate` can shape a complete row.

R372 keeps the integrated top dormant by tying the future providers absent.
The module still replaces local literal false feeds with named outputs and
blockers, so the missing trace boundary is independently testable before live
row fill is promoted.

## Interface

| Direction | Signal | Description |
|---|---|---|
| input | `enable` / `flush` | Replay-LIQ integration arm and flush suppression. |
| input | `slotOccupied` | Resident W2 slot evidence. |
| input | `instructionProviderValid` / `instructionProviderRaw` / `instructionProviderLen` | Future provider for committed instruction raw bits and byte length. |
| input | `sourceTraceProviderValid` / `source0Provider` / `source1Provider` | Future provider for source operand trace rows. |
| output | `instructionValid` / `instructionRaw` / `instructionLen` | Gated instruction metadata fed to the commit-row candidate. |
| output | `sourceTraceValid` / `source0` / `source1` | Gated source trace fed to the commit-row candidate. |
| output | `traceReady` | Instruction metadata and source trace are both present for the resident slot. |
| output | blocker signals | Disabled, flush, no-slot, missing instruction metadata, and missing source trace diagnostics. |

## Logic Design

```text
active = enable && !flush
traceCandidateValid = active && slotOccupied
providerMetadataReady = instructionProviderValid && instructionProviderLen != 0
instructionMetadataReady = traceCandidateValid && providerMetadataReady
sourceTraceReady = instructionMetadataReady && sourceTraceProviderValid
traceReady = sourceTraceReady
```

Instruction outputs are valid only when metadata is ready. Source outputs are
valid only when the full trace is ready; otherwise the source bundles are
zeroed. This prevents stale provider payloads from leaking into the commit-row
candidate when the W2 slot is empty, flushed, disabled, or missing a required
trace source.

## Integration

R372 wires the module immediately before
`LoadReplayReturnPipeW2CommitRowCandidate` in
`LinxCoreFrontendFetchRfAluTraceTop`:

- resident W2 slot occupancy gates provider evidence;
- R373 feeds instruction metadata from `ROBRowCommitTraceLookup`;
- R374 keeps ROB-row source traces completion-only because allocation rows have
  register tags but no proven source data;
- source trace providers are currently tied absent in the top;
- gated outputs feed the R366 commit-row candidate;
- compact top-level diagnostics expose trace readiness and missing-provider
  blockers;
- the R367 row-fill enable, R371 lifecycle commit permit, and R363 atomic live
  request remain false, so generated behavior stays dormant.

## Deferred Owners

- Source operand trace reconstruction from the original RF-read/rename row.
- Live promotion of row fill after W2 side effects, clear/refill, replay-row
  lifecycle clear, and ROB completion-row replacement can commit atomically.

## Verification

Focused gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2CommitRowTraceSource
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2CommitRowCandidate
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2RowFillEnableControl
bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop
```

Reference tests cover the fully ready trace, missing instruction provider,
zero instruction length, missing source trace, disabled/flush/empty suppression,
and Chisel elaboration.
