# LoadReplayReturnPipeW2ResolveFirePayload

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2ResolveFirePayload.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/LoadReplayReturnPipeW2ResolveFirePayloadSpec.scala`
- Integrated user: `rtl/LinxCore/chisel/src/main/scala/linxcore/top/LinxCoreFrontendFetchRfAluTraceTop.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/iex/pipe/lda_pipe.cpp`
    - `LDAPipe::runW2`
  - `model/LinxCoreModel/model/iex/pipe/agu_pipe.cpp`
    - `AGUPipe::runW2`
  - `model/LinxCoreModel/model/ModelCommon/SimInstInfo.cpp`
    - `SimInstInfo::GenRslvBus`
  - `model/LinxCoreModel/model/ModelCommon/bus/PEResolveBus.h`
    - `PEResolveBus`
- Related Chisel contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2ResolveRequest.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2SideEffectFireVector.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2SideEffectIssuePermit.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2SideEffectPayloadPlan.scala`
- Contract IDs: `LC-CHISEL-LSU-REPLAY-PIPE-W2-RESOLVE-FIRE-PAYLOAD-001`

## Purpose

`LoadReplayReturnPipeW2ResolveFirePayload` is the dormant fire-qualified
resolve payload boundary after the R346 W2 side-effect fire vector. R340
formats the post-completion W2 resolve payload, and R346 decides whether the
resolve sink would fire. R347 joins those two facts so a future live ROB/PE
resolve owner can consume one typed payload only when the resolve fire pulse
and payload are both coherent.

In LinxCoreModel, `LDAPipe::runW2` and `AGUPipe::runW2` call
`SimInstInfo::GenRslvBus()`, choose the scalar or vector thread route, and
write the `PEResolveBus` during the same logical W2 side-effect point as RF
writeback and scalar-local wakeup. This Chisel module does not perform that
mutation. It only names the fire-qualified payload surface and reports
fire/payload mismatches.

## Interface

| Direction | Signal | Description |
|---|---|---|
| input | `enable` | Reduced replay-LIQ feature enable. |
| input | `flush` | Suppresses fire-qualified payload publication during replay flush. |
| input | `resolveFire` | R346 resolve side-effect fire pulse. |
| input | `resolvePayloadValid` | R340 resolve request payload is valid. |
| input | `resolveComplete` | R340 completion bit for the future `PEResolveBus`. |
| input | `resolveTargetIsAgu` / `resolveTargetIsLda` / `resolveTargetPipeIndex` | Validated W2 target route from the resolve request. |
| input | `resolveBid` / `resolveGid` / `resolveRid` / `resolveLoadLsId` | Resolve payload identity. BID/GID/RID must remain valid to fire. |
| input | `resolvePc` / `resolveAddr` / `resolveSize` | Copied instruction and memory-request sidebands. |
| input | `resolveDst` / `resolveData` | Copied destination and returned data sidebands. |
| output | `candidateValid` | Enabled, not flushed, and resolve fire is asserted. |
| output | `payloadValid` | Enabled, not flushed, and a resolve payload is present. |
| output | `targetValid` / `identityValid` | Fire-time target and BID/GID/RID payload guards. |
| output | `fireValid` | Fire, payload, completion, target, and identity are all valid. |
| output | copied `fire*` fields | Fire-qualified copy of the resolve payload; disabled or zero when `fireValid` is false. |
| output | blocker signals | Disabled, flush, missing fire, missing payload, incomplete, invalid target, and invalid identity diagnostics. |
| output | `invalidFireWithoutPayload` | Resolve fire asserted without the R340 payload. |
| output | `invalidPayloadWithoutFire` | R340 payload exists but R346 did not fire resolve. |

## Logic Design

```text
active = enable && !flush
candidateValid = active && resolveFire
payloadValid = active && resolvePayloadValid
targetValid = resolveTargetIsAgu ^ resolveTargetIsLda
identityValid = resolveBid.valid && resolveGid.valid && resolveRid.valid
fireValid =
  candidateValid &&
  resolvePayloadValid &&
  resolveComplete &&
  targetValid &&
  identityValid
```

The module copies BID/GID/RID, load LSID, PC, address, size, destination, data,
target class, and target pipe only when `fireValid` is true. All copied fields
are disabled or zero otherwise. This lets future live resolve mutation connect
to a single fire-qualified payload without observing partial request or
payload mismatches.

## Integration

`LinxCoreFrontendFetchRfAluTraceTop` wires this module behind the R346 fire
vector:

- `resolveFire` comes from `LoadReplayReturnPipeW2SideEffectFireVector`;
- payload and copied fields come from `LoadReplayReturnPipeW2ResolveRequest`;
- compact diagnostics are exposed under
  `reducedLoadReplayLiqLretPipeW2ResolveFirePayload*`.

The R347 integration remains observational. R359 now consumes this fire payload
into `LoadReplayReturnPipeW2ResolveArbiterInput`, but that pre-arbiter boundary
keeps its live resolve gate disabled. The fire payload therefore does not feed
W2 readiness, W2 slot clear, ROB resolve mutation, replay RF writeback,
ready-table mutation, issue wakeup, or replay-row lifecycle.

## Deferred Owners

- Live `PEResolveBus` queue or ROB/PE resolve-array mutation.
- Scalar/vector thread routing beyond the reduced target diagnostics.
- Atomic W2 clear after resolve, writeback, and wakeup sinks have all fired.
- Replay-row lifecycle completion after real side effects mutate state.

## Verification

Focused gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2ResolveFirePayload
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2SideEffectFireVector
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2ResolveRequest
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2SideEffectIssuePermit
bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop
FETCH_REDUCED_STORE_REPLAY_LIQ=1 BUILD_DIR=generated/r347-replay-pipe-w2-resolve-fire-payload-xcheck bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh
```

Reference tests cover legal fire-qualified copying, fire without payload,
payload without fire, disabled/flush suppression, incomplete payloads,
invalid targets, invalid identity, and Chisel elaboration.
