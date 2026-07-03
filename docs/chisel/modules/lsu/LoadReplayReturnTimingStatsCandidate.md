# LoadReplayReturnTimingStatsCandidate

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnTimingStatsCandidate.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/LoadReplayReturnTimingStatsCandidateSpec.scala`
- Integrated user: `rtl/LinxCore/chisel/src/main/scala/linxcore/top/LinxCoreFrontendFetchRfAluTraceTop.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/iex/iex.cpp`
    - `IEX::setMemData`
  - `model/LinxCoreModel/model/ModelCommon/bus/MemReqBus.h`
- Related Chisel contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnFinalMetadataCandidate.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnIexPipeInsertCandidate.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeResidencyCandidate.scala`
- Contract IDs: `LC-CHISEL-LSU-REPLAY-TIMING-STATS-001`

## Purpose

`LoadReplayReturnTimingStatsCandidate` is the diagnostic timing/stat sideband
boundary in the model `IEX::setMemData` sequence. After final load-return
metadata, LinxCoreModel copies returned-load timing fields from `MemReqBus`
into `inst->pipeCycle`, copies `mem.iq_name`, stamps `ldRntCycle` with the
current simulator cycle, increments `total_mem_load_req_cnt`, and adds
`currentCycle - mem.lsuRecvCycle` to `total_mem_load_latency`.

R327 names that sideband step before the existing E4 insert-shaped diagnostic.
It computes the copied cycle outputs and stats-increment intent, but it does
not allocate pipe-cycle storage or mutate real counters.

## Interface

| Direction | Signal | Description |
|---|---|---|
| input | `enable` | Replay-LIQ wrapper is active. |
| input | `flush` | Suppresses timing/stat diagnostics for the cycle. |
| input | `finalMetadataValid` | R326 final metadata permit. |
| input | `currentCycle` | Cycle value used for diagnostic `ldRntCycle` and latency intent. |
| input | `memLsuRecvCycle` | MemReqBus `lsuRecvCycle`. |
| input | `memLdqPickCycle` | MemReqBus `ldqPickCycle`. |
| input | `memLdqIssueCycle` | MemReqBus `ldqIssueCycle`. |
| input | `memL1MissCycle` | MemReqBus `l1MissCycle`. |
| input | `memL2MissCycle` | MemReqBus `l2MissCycle`. |
| input | `memMemRntCycle` | MemReqBus `memRntCycle`. |
| input | `memL2RntCycle` | MemReqBus `l2RntCycle`. |
| input | `memL1RntCycle` | MemReqBus `l1RntCycle`. |
| output | `candidateValid` | Enabled, not flushing, and final metadata input is valid. |
| output | `timingSidebandValid` | Pipe-cycle sideband copy intent is valid. |
| output | `iqNameSidebandValid` | Model `iq_name` copy point is valid; the string payload is deferred. |
| output | `ldRntCycleValid` | `ldRntCycle` stamp is valid. |
| output | `statsUpdateValid` | Load request-count and latency counter update intent is valid. |
| output | `*Cycle` | Copied timing fields and diagnostic `ldRntCycle` when valid, otherwise zero. |
| output | `statsLatencyIncrement` | Unsigned diagnostic equivalent of `currentCycle - memLsuRecvCycle`. |
| output | `latencyUnderflow` | Diagnostic flag for unexpected `currentCycle < memLsuRecvCycle`. |
| output | `readyForPipeInsert` | Alias for `candidateValid`; feeds the R322 E4 insert-shaped diagnostic. |
| output | `blockedBy*` | Disabled, flush, and missing final-metadata diagnostics. |

## Logic Design

```text
candidateValid = enable && !flush && finalMetadataValid
timingSidebandValid = candidateValid
iqNameSidebandValid = candidateValid
ldRntCycleValid = candidateValid
statsUpdateValid = candidateValid
ldRntCycle = currentCycle when candidateValid else 0
statsLatencyIncrement = currentCycle - memLsuRecvCycle when candidateValid else 0
readyForPipeInsert = candidateValid
```

The latency subtraction intentionally preserves unsigned hardware behavior.
`latencyUnderflow` is only a diagnostic guardrail; legal model timing should
present `currentCycle >= memLsuRecvCycle`.

## Integration

`LinxCoreFrontendFetchRfAluTraceTop` wires R327 between
`LoadReplayReturnFinalMetadataCandidate` and
`LoadReplayReturnIexPipeInsertCandidate`:

- `finalMetadataValid` comes from R326 `readyForPipeInsert`;
- the current reduced top ties all timing inputs to zero until a real cycle
  source and MemReqBus timing sidecar owner exist;
- R322 consumes R327 `readyForPipeInsert`;
- top-level diagnostics expose candidate validity, sideband validity,
  `ldRntCycle`, latency increment, underflow, and blockers.

This preserves the model order:

```text
setMemData admission
  -> ROB resolve-data intent
  -> lane-completion permit
  -> TLOAD sub-instruction completion permit
  -> final load-return metadata
  -> timing/stat sideband intent
  -> IEX E4 insert-shaped candidate
  -> LDA/AGU pipe-residency intent
```

## Deferred Owners

- Real pipe-cycle storage on the cloned pipe instruction.
- Real `iq_name` payload storage.
- Real load statistics counters.
- A live cycle source and MemReqBus timing sidecars in the reduced replay path.
- R328 names diagnostic LDA/AGU E4 pipe-residency intent after insertion, but
  real E4 pipe residency, replay-row lifecycle, ready-table update, issue
  wakeup, and RF writeback after insertion remain deferred.

## Verification

Focused gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnTimingStatsCandidate
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnFinalMetadataCandidate
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnIexPipeInsertCandidate
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeResidencyCandidate
bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop
FETCH_REDUCED_STORE_REPLAY_LIQ=1 BUILD_DIR=generated/r327-replay-timing-stats-xcheck bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh
```

Reference tests cover sideband copy intent, `ldRntCycle` stamping, unsigned
latency increment, underflow diagnostics, disabled/flush/missing-input
blockers, and Chisel elaboration.
