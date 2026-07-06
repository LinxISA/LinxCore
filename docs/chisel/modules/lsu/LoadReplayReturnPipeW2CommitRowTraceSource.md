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

R372 kept the integrated top dormant by tying the source-trace provider absent,
and R373 wired instruction metadata from the read-only ROB row commit-trace
lookup. R377 connects the source-trace provider to the resident W2 slot payload
that R376 carried from the RF-derived replay-LIQ source provenance path. The
module remains independently testable before live row fill is promoted because
the R367 row-fill enable control still keeps replacement disabled.

## Interface

| Direction | Signal | Description |
|---|---|---|
| input | `enable` / `flush` | Replay-LIQ integration arm and flush suppression. |
| input | `slotOccupied` | Resident W2 slot evidence. |
| input | `instructionProviderValid` / `instructionProviderRaw` / `instructionProviderLen` | Future provider for committed instruction raw bits and byte length. |
| input | `sourceTraceProviderValid` / `source0Provider` / `source1Provider` | Source operand trace provider. In the integrated top this now comes from the resident W2 slot's registered RF-derived source-trace sideband. |
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
  register tags but no proven source data, and the top leaves ROB source-trace
  lookup disabled;
- R375/R376 carry RF-derived source traces through execute wait/replay capture,
  the relaunch queue, LIQ row residency, launch selection diagnostics, LRET,
  IEX pipe-insert, E4 residency, W1, and W2 slot state;
- R377 connects the resident W2 slot source-trace payload to this provider;
- gated outputs feed the R366 commit-row candidate;
- compact top-level diagnostics expose trace readiness and missing-provider
  blockers;
- R552 adds harness sideband counters for these diagnostics and proves the
  replay-loop fixture has resident W2 source traces but no ROB instruction
  provider evidence yet (`lret_w2_slot_source_trace_valid=74`,
  `w2_commit_row_trace_source_rob_lookup_instruction_valid=0`, and
  `w2_commit_row_trace_source_blocked_by_no_metadata=74`);
- the R367 row-fill enable, R371 lifecycle commit permit, and R363 atomic live
  request remain false, so generated behavior stays dormant.

## Deferred Owners

- Drive the read-only ROB commit-trace lookup from the resident W2 RID so this
  module can expose instruction raw/length for replay row fill.
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
