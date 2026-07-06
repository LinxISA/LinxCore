# LoadReplayReturnPipeW2AtomicRequestGate

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2AtomicRequestGate.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/LoadReplayReturnPipeW2AtomicRequestGateSpec.scala`
- Future integrated user: `rtl/LinxCore/chisel/src/main/scala/linxcore/top/LinxCoreFrontendFetchRfAluTraceTop.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/iex/pipe/lda_pipe.cpp`: `LDAPipe::runW2`, `LDAPipe::move`
  - `model/LinxCoreModel/model/iex/pipe/agu_pipe.cpp`: `AGUPipe::runW2`, `AGUPipe::move`
  - `model/LinxCoreModel/model/lsu/load_unit/ldq.cpp`: `LDQInfo::returnData`
  - `model/LinxCoreModel/model/iex/iex.cpp`: `IEX::setMemData`
  - `model/LinxCoreModel/model/pe/PECommon/PROBCommon.cpp`: `ROBState::resolveData`
- Related Chisel contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2AtomicRequestEnablePolicy.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2AtomicLiveRequestControl.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2RowFillEnableControl.scala`
- Contract IDs: `LC-CHISEL-LSU-REPLAY-PIPE-W2-ATOMIC-REQUEST-GATE-001`

## Purpose

`LoadReplayReturnPipeW2AtomicRequestGate` is the module-local boundary between
the R527/R528 pre-request policy and the R363 atomic live-request owner. It is
not wired into the reduced top yet. The goal is to make the future
`requestEnable` replacement reviewable before adding more logic to the
constructor-sensitive top.

LinxCoreModel's returned-load W2 behavior is one resident-instruction boundary:
W2 may publish RF writeback, ROB/PE resolve, scalar wakeup, resident clear, and
W1-to-W2 refill before movement. The Chisel gate keeps those side effects
dormant unless both conditions are true:

- live replay-return mode is explicitly enabled;
- the pre-request policy says the resident or empty-refill evidence is safe.

## Interface

| Direction | Signal | Description |
|---|---|---|
| input | `enable` / `flush` | Replay-LIQ integration arm and flush suppression. |
| input | `liveModeEnable` | Future mode bit replacing the current top-level `requestEnable=false` tie-off. |
| input | `slotOccupied` | Resident W2 slot state. |
| input | `sideEffectCandidateValid` / `sideEffectRequiredMask` | Pre-request W2 resolve/writeback/wakeup evidence. |
| input | `sideEffectSinksReady` | Pre-request sink capacity for all required side effects. |
| input | `clearIntent` / `clearCommitReady` | Resident clear intent and clear/ROB identity proof. |
| input | `rowFillCandidateValid` | Complete commit-row replacement candidate. |
| input | `lifecycleRowClearReady` | Unique replay LIQ row selected for lifecycle clear. |
| input | `writeCandidateValid` | W1-to-W2 refill candidate. |
| output | `policyRequestEnableCandidate` | Raw pre-request policy candidate. |
| output | `gatedRequestEnable` | `liveModeEnable && policyRequestEnableCandidate`; this feeds the live-request owner inside the composite. |
| output | live-request outputs | `requestActive`, `requestEvidenceValid`, `sideEffectLiveRequested`, and `promotionRequested`. |
| output | policy blockers | Mode-disabled, policy-blocked, missing evidence, missing sink, missing clear, missing row fill, missing lifecycle row, missing side-effect mask, and malformed resident evidence. |
| output | `invalidRequestWithoutEvidence` | Defensive assertion-style diagnostic from the live-request owner after policy gating. |

## Logic Design

```text
policyRequestEnableCandidate =
  LoadReplayReturnPipeW2AtomicRequestEnablePolicy.requestEnableCandidate

gatedRequestEnable =
  liveModeEnable && policyRequestEnableCandidate

LoadReplayReturnPipeW2AtomicLiveRequestControl.requestEnable =
  gatedRequestEnable
```

The composite deliberately does not use post-request signals such as
`sideEffectFireComplete`, `liveClearReady`, `lifecycleReady`, or
`rowFillEnable`. Those signals are effects of asserting the atomic request and
would create an enable loop if they were prerequisites.

## Integration

R529 keeps this module unintegrated. A future top packet can replace the
current `LoadReplayReturnPipeW2AtomicLiveRequestControl` instance and
`requestEnable=false` tie-off with this composite, while still feeding
`gatedRequestEnable=false` through `liveModeEnable=false` until the generated-RTL
gate proves the full side-effect/clear/row-fill/lifecycle chain.

Because `LinxCoreFrontendFetchRfAluTraceTop` is near the JVM constructor method
size limit, this composite is intended as a constructor-containment boundary:
top-level code should wire one request gate rather than spreading policy,
request, and diagnostic logic across multiple helpers.

## Deferred Owners

- Top-level replacement of the R363 direct atomic live-request instance with
  this composite.
- Generated-RTL proof that `liveModeEnable=false` preserves the dormant reduced
  path after integration.
- Later generated-RTL proof with `liveModeEnable=true` once W2 side-effect
  sinks, clear, row fill, and replay-row lifecycle mutation all commit
  atomically.
- Multi-return-pipe arbitration when more than one returned-load W2 pipe exists.

## Verification

Focused gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2AtomicRequestGate
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2AtomicRequestEnablePolicy
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2AtomicLiveRequestControl
git diff --check
```

Reference tests cover a fully enabled live request, policy-approved request
blocked by disabled live mode, policy-blocked live mode, malformed resident
evidence before request issue, and Chisel elaboration of both child owners.
