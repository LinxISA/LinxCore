# LoadReplayReturnPipeW2WakeupFirePayload

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2WakeupFirePayload.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/LoadReplayReturnPipeW2WakeupFirePayloadSpec.scala`
- Integrated user: `rtl/LinxCore/chisel/src/main/scala/linxcore/top/LinxCoreFrontendFetchRfAluTraceTop.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/iex/pipe/lda_pipe.cpp`
    - `WakeupScalarLocalLinks`
    - `LDAPipe::runW2`
  - `model/LinxCoreModel/model/iex/pipe/agu_pipe.cpp`
    - `WakeupScalarLocalLinks`
    - `AGUPipe::runW2`
  - `model/LinxCoreModel/model/iex/iex.cpp`
    - `IEX::setMemWakeup`
  - `model/LinxCoreModel/model/iex/iex_iq.cpp`
    - `IssueQueue::WakeupIQTag`
- Related Chisel contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2WakeupRequest.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2SideEffectFireVector.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2SideEffectIssuePermit.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2SideEffectPayloadPlan.scala`
- Contract IDs: `LC-CHISEL-LSU-REPLAY-PIPE-W2-WAKEUP-FIRE-PAYLOAD-001`

## Purpose

`LoadReplayReturnPipeW2WakeupFirePayload` is the dormant fire-qualified
ready-table/issue-wakeup payload boundary after the R346 W2 side-effect fire
vector. R342 formats the post-completion W2 wakeup request payload, and R346
decides whether the wakeup sink would fire. R349 joins those two facts so a
future live wakeup owner can consume one typed payload only when the wakeup
fire pulse and payload are coherent.

In LinxCoreModel, `LDAPipe::runW2` and `AGUPipe::runW2` publish W2 RF
writeback, PE resolve, and scalar-local-link wakeup from the resident W2
instruction. Returned memory wakeups also flow through `IEX::setMemWakeup`,
which calls `IssueQueue::WakeupIQTag`; that path mutates issue queues and the
ready table. This Chisel module does not mutate either structure. It only
names the fire-qualified payload surface and reports fire/payload mismatches.

## Interface

| Direction | Signal | Description |
|---|---|---|
| input | `enable` | Reduced replay-LIQ feature enable. |
| input | `flush` | Suppresses fire-qualified payload publication during replay flush. |
| input | `wakeupFire` | R346 wakeup side-effect fire pulse. |
| input | `wakeupPayloadValid` | R342 wakeup request payload is valid. |
| input | `wakeupTargetValid` / `wakeupIdentityValid` | R342 target and BID/GID/RID validation bits. |
| input | `wakeupRequired` | R342 wakeup-required guard. |
| input | `wakeupDestinationValid` | R342 destination payload guard. |
| input | `wakeupReducedGpr` / `wakeupNonGpr` | R342 reduced-GPR versus scalar-local/non-GPR classification. |
| input | `wakeupTargetIsAgu` / `wakeupTargetIsLda` / `wakeupTargetPipeIndex` | Validated W2 target route from the wakeup request. |
| input | `wakeupBid` / `wakeupGid` / `wakeupRid` / `wakeupLoadLsId` | Wakeup payload identity. |
| input | `wakeupPc` | Copied resident PC diagnostic. |
| input | `wakeupKind` / `wakeupArchTag` / `wakeupRelTag` / `wakeupPhysTag` / `wakeupOldPhysTag` | Copied destination payload. |
| output | `candidateValid` | Enabled, not flushed, and wakeup fire is asserted. |
| output | `payloadValid` | Enabled, not flushed, and a wakeup payload is present. |
| output | `targetValid` / `identityValid` / `required` / `destinationValid` | Fire-time payload guards. |
| output | `reducedGprWakeup` / `nonGprWakeup` | Fire-qualified destination class diagnostics. |
| output | `fireValid` | Fire, payload, target, identity, wakeup-required, and destination are all valid. |
| output | copied `fire*` fields | Fire-qualified copy of the wakeup payload; disabled or zero when `fireValid` is false. |
| output | blocker signals | Disabled, flush, missing fire, missing payload, invalid target, invalid identity, wakeup-not-required, and missing-destination diagnostics. |
| output | `invalidFireWithoutPayload` | Wakeup fire asserted without the R342 payload. |
| output | `invalidPayloadWithoutFire` | R342 payload exists but R346 did not fire wakeup. |

## Logic Design

```text
active = enable && !flush
candidateValid = active && wakeupFire
payloadValid = active && wakeupPayloadValid
fireValid =
  candidateValid &&
  wakeupPayloadValid &&
  wakeupTargetValid &&
  wakeupIdentityValid &&
  wakeupRequired &&
  wakeupDestinationValid
```

The module copies BID/GID/RID, load LSID, PC, destination kind/tags, target
class, and target pipe only when `fireValid` is true. All copied fields are
disabled or zero otherwise. Both reduced GPR and non-GPR payloads are
preserved because the model has separate future wakeup routes for global
ready-table wakeups and scalar local-link T/U wakeups.

## Integration

`LinxCoreFrontendFetchRfAluTraceTop` wires this module behind the R346 fire
vector:

- `wakeupFire` comes from `LoadReplayReturnPipeW2SideEffectFireVector`;
- payload and validation bits come from `LoadReplayReturnPipeW2WakeupRequest`;
- compact diagnostics are exposed under
  `reducedLoadReplayLiqLretPipeW2WakeupFirePayload*`.

The R349 integration remains observational. It does not feed W2 readiness, W2
slot clear, ready-table mutation, issue wakeup, RF writeback, ROB/PE resolve,
or replay-row lifecycle.
R360 now consumes this fire payload into `LoadReplayReturnPipeW2WakeupArbiterInput`,
but that pre-arbiter boundary keeps `liveEnable=false` in the reduced top.

## Deferred Owners

- Live ready-table mutation and issue-queue wakeup fanout.
- GPR versus scalar-local-link wakeup routing to the final issue fabrics.
- Load spec-wakeup LPV/load-init metadata.
- PE/core/thread routing fields from the full model wakeup path.
- Atomic W2 clear after resolve, writeback, and wakeup sinks have all fired.

## Verification

Focused gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2WakeupFirePayload
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2SideEffectFireVector
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2WakeupRequest
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2SideEffectIssuePermit
bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop
FETCH_REDUCED_STORE_REPLAY_LIQ=1 BUILD_DIR=generated/r349-replay-pipe-w2-wakeup-fire-payload-xcheck bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh
```

Reference tests cover legal GPR and non-GPR fire-qualified copying, fire
without payload, payload without fire, disabled/flush suppression, invalid
target and identity, wakeup-not-required and no-destination blockers, and
Chisel elaboration.
