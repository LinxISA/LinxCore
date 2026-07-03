# LoadReplayReturnWakeupCandidate

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnWakeupCandidate.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/LoadReplayReturnWakeupCandidateSpec.scala`
- Integrated user: `rtl/LinxCore/chisel/src/main/scala/linxcore/top/LinxCoreFrontendFetchRfAluTraceTop.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/iex/iex.cpp`
    - `IEX::setMemWakeup`
    - `IEX::setMemData`
  - `model/LinxCoreModel/model/iex/iex_iq.cpp`
    - `IssueQueue::WakeupIQTag`
  - `model/LinxCoreModel/model/iex/rtable.cpp`
    - `ReadyState::SetRegReadyTable`
- Related Chisel contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayDestination.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnLretPayload.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnWritebackCandidate.scala`
- Contract IDs: `LC-CHISEL-LSU-REPLAY-WAKEUP-001`

## Purpose

`LoadReplayReturnWakeupCandidate` is the diagnostic boundary between replay
LRET payload formatting and a future ready-table/issue-wakeup owner. The model
keeps the effects separate: `IEX::setMemData` stores returned data into ROB
destination payloads, while `IEX::setMemWakeup` waits until the real request
count is satisfied and then calls `IssueQueue::WakeupIQTag` for each eligible
destination. `WakeupIQTag` fans out to issue queues and calls
`ReadyState::SetRegReadyTable`.

The current reduced Chisel path carries only one compact destination sideband
and does not own those mutations. This module exposes the wakeup intent and the
current reduced-GPR subset as diagnostics only.

## Interface

| Direction | Signal | Description |
|---|---|---|
| input | `enable` | Replay-LIQ wrapper is active. |
| input | `payloadValid` | `LoadReplayReturnLretPayload` has a valid diagnostic payload. |
| input | `payloadWakeupRequired` | Payload says the regular mem wakeup is required. This is false for `specWakeup` or stack rows. |
| input | `payloadDst` | One compact replay destination sideband from the payload. |
| output | `candidateValid` | Payload is valid while the wrapper is enabled. |
| output | `wakeupRequired` | Candidate exists and the payload requires regular wakeup. |
| output | `wakeupValid` | Wakeup is required and a destination sideband exists. |
| output | `wakeupKind` | Destination kind for the future ready-table/wakeup owner. |
| output | `wakeupTag` | Destination physical tag for the future ready-table/wakeup owner. |
| output | `reducedGprWakeupValid` | Current reduced scalar-GPR subset of `wakeupValid`. |
| output | `nonGprWakeup` | Wakeup is valid but belongs to T/U or another future non-GPR owner. |
| output | `suppressedWakeupNotRequired` | Payload exists but regular wakeup is suppressed by payload semantics. |
| output | `ignoredNoDestination` | Wakeup is required but no destination sideband is present. |
| output | `blockedByDisabled` | Payload exists while replay-LIQ mode is disabled. |

## Logic Design

The diagnostic predicate sequence is:

```text
candidateValid = enable && payloadValid
wakeupRequired = candidateValid && payloadWakeupRequired
wakeupValid = wakeupRequired && payloadDst.valid && payloadDst.kind != None
reducedGprWakeupValid = wakeupValid && payloadDst.kind == Gpr
nonGprWakeup = wakeupValid && payloadDst.kind != Gpr
```

`wakeupKind` and `wakeupTag` are zeroed to `None` and `0` unless
`wakeupValid` is true. The module does not track model `wakeupCnt`,
`realReqCnt`, lane sets, LPV pipe metadata, or multi-destination payloads; those
remain future owners. It consumes `payloadWakeupRequired` from
`LoadReplayReturnLretPayload`, which already applies the regular-return
`!specWakeup && !stack_vld` rule used by `LDQInfo::returnData`.

## Deferred Owners

- Real ready-table update and issue-queue wakeup fanout.
- Multi-destination wakeup for load-pair, vector, tile, and local T/U paths.
- Model `wakeupCnt`/`realReqCnt` tracking and LPV pipe metadata.
- Backpressure from ready-table/wakeup queues into replay return publication.
- Live replay launch and LRET enqueue.

## Verification

Focused gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnWakeupCandidate
bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop
FETCH_REDUCED_STORE_REPLAY_LIQ=1 BUILD_DIR=generated/r313-replay-return-wakeup-candidate-xcheck bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh
```

Reference tests cover GPR wakeup candidate formation, suppressed regular
wakeup, missing destination diagnostics, non-GPR wakeup surfacing, disabled
payload blocking, stale-field suppression, and Chisel elaboration.
