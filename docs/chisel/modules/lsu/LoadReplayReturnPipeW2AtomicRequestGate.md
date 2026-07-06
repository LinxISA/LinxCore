# LoadReplayReturnPipeW2AtomicRequestGate

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2AtomicRequestGate.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/LoadReplayReturnPipeW2AtomicRequestGateSpec.scala`
- Integrated user: `rtl/LinxCore/chisel/src/main/scala/linxcore/top/LinxCoreFrontendFetchRfAluTraceTop.scala`
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
wired into the reduced top after R530, but with live mode and policy
prerequisites held dormant. The goal is to make the future `requestEnable`
replacement reviewable without adding scattered policy logic to the
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
| input | `requestClearIntent` / `requestWriteCandidateValid` | Raw request-evidence inputs forwarded only to the atomic live-request owner. These may stay connected when policy prerequisites are dormant. |
| output | `policyRequestEnableCandidate` | Raw pre-request policy candidate. |
| output | `gatedRequestEnable` | `liveModeEnable && policyRequestEnableCandidate`; this feeds the live-request owner inside the composite. |
| output | live-request outputs | `requestActive`, `requestEvidenceValid`, `sideEffectLiveRequested`, and `promotionRequested`. |
| output | policy blockers | Disabled, flush, mode-disabled, policy-blocked, missing evidence, missing sink, missing clear, missing row fill, missing lifecycle row, missing side-effect mask, and malformed resident evidence. |
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

R530 splits raw request evidence from policy prerequisites. The live-request
owner may still report clear/refill evidence while the policy arm is held
dormant, but policy readiness must not consume clear, row-fill, lifecycle, or
sink-ready signals from the current top until those signals are proven
pre-request. In the current reduced top, feeding them into policy creates a
cycle through live side-effect enables, so they remain tied false at the top
integration boundary.

## Integration

R529 keeps this module unintegrated. R530 replaces the current
`LoadReplayReturnPipeW2AtomicLiveRequestControl` instance and `requestEnable=false`
tie-off with this composite, while still feeding `gatedRequestEnable=false`
through `liveModeEnable=false` and tied-off policy prerequisites until the
generated-RTL gate proves the full side-effect/clear/row-fill/lifecycle chain.
R531 removes the unused current-cycle prerequisite owner parameters from the
top helper signature so the dormant policy tie-offs are explicit; future packets
must add proven pre-request producers deliberately.
R533 adds that proven register boundary by wiring
`LoadReplayReturnPipeW2AtomicPrereqSnapshot` into the top. Current-cycle
side-effect, clear, row-fill, and lifecycle owners feed the snapshot; this gate
consumes only snapshot outputs for policy prerequisites while `liveModeEnable`
remains false. The R533 generated-RTL xcheck proves the dormant
snapshot-fed top path still compares cleanly with zero mismatches and zero
QEMU/DUT CBSTOP rows.
R534 changes the reduced top to pass `liveModeEnable=true.B` into this gate;
the request still fires only when the snapshot-fed policy candidate is true.
R535 extends the generated-RTL sideband stats to persist this gate's top-level
request outputs plus downstream W2 side-effect, row-fill, and replay-row
lifecycle counters. Those counters are the required observation points for the
next positive replay-return packet; the default fixture may still leave them
zero.
R536 runs the constructed replay-LIQ loop against those counters. The probe
shows the reduced top samples the live W2 atomic owner
(`w2_atomic_live_active=108`), but the request gate never observes valid request
evidence (`w2_atomic_request_active=0`, `w2_atomic_evidence_valid=0`). The next
RTL promotion must provide a model-derived W2 slot/request-evidence producer
before expecting side-effect fire, row-fill, or replay-row lifecycle clear
counters to become nonzero.

Because `LinxCoreFrontendFetchRfAluTraceTop` is near the JVM constructor method
size limit, this composite is intended as a constructor-containment boundary:
top-level code should wire one request gate rather than spreading policy,
request, and diagnostic logic across multiple helpers.

## Deferred Owners

- Broader generated-RTL/QEMU proof with nonzero W2 side-effect, clear, row
  fill, and replay-row lifecycle mutation counters.
- Multi-return-pipe arbitration when more than one returned-load W2 pipe exists.

## Verification

Focused gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2AtomicRequestGate
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2AtomicRequestEnablePolicy
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2AtomicLiveRequestControl
BUILD_DIR=generated/r533-replay-w2-prereq-snapshot-top-xcheck bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh
BUILD_DIR=generated/r534-replay-w2-atomic-live-mode-xcheck bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh
BUILD_DIR=generated/r535-replay-w2-sideband-counters-xcheck bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh
git diff --check
```

The R533 generated-RTL manifest is
`generated/r533-replay-w2-prereq-snapshot-top-xcheck/report/crosscheck_manifest.json`;
it records pass status, comparator status 0, three compared rows, zero
mismatches, and zero QEMU/DUT CBSTOP rows.

Reference tests cover a fully enabled live request, policy-approved request
blocked by disabled live mode, policy-blocked live mode, malformed resident
evidence before request issue, raw request evidence with dormant policy
prerequisites, and Chisel elaboration of both child owners.
