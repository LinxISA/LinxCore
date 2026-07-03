# LoadReplayReturnPipeW2WritebackFirePayload

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2WritebackFirePayload.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/LoadReplayReturnPipeW2WritebackFirePayloadSpec.scala`
- Integrated user: `rtl/LinxCore/chisel/src/main/scala/linxcore/top/LinxCoreFrontendFetchRfAluTraceTop.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/iex/pipe/lda_pipe.cpp`
    - `LDAPipe::runW2`
  - `model/LinxCoreModel/model/iex/pipe/agu_pipe.cpp`
    - `AGUPipe::runW2`
  - `model/LinxCoreModel/model/ModelCommon/SimInstInfo.cpp`
    - `SimInstInfo::GenRFReqBus`
  - `model/LinxCoreModel/model/ModelCommon/bus/RFReqBus.h`
    - `RFReqBus`
  - `model/LinxCoreModel/model/iex/iex_rf.cpp`
    - `RegFile::Work`
- Related Chisel contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2WritebackRequest.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2SideEffectFireVector.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2SideEffectIssuePermit.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2SideEffectPayloadPlan.scala`
- Contract IDs: `LC-CHISEL-LSU-REPLAY-PIPE-W2-WRITEBACK-FIRE-PAYLOAD-001`

## Purpose

`LoadReplayReturnPipeW2WritebackFirePayload` is the dormant fire-qualified RF
writeback payload boundary after the R346 W2 side-effect fire vector. R341
formats the post-completion W2 writeback request payload, and R346 decides
whether the writeback sink would fire. R348 joins those two facts so a future
live replay RF writeback owner can consume one typed payload only when the
writeback fire pulse and payload are both coherent.

In LinxCoreModel, `LDAPipe::runW2` and `AGUPipe::runW2` reset the RF write bus,
stamp W2 timing, optionally call `SimInstInfo::GenRFReqBus(false)`, then
publish resolve and wakeup side effects. `RFReqBus` carries PE/core/thread and
BID/GID/RID identity plus destination operands, and `RegFile::Work` applies
the destination vector to the matching register file. This Chisel module does
not write the RF. It only names the fire-qualified reduced scalar GPR payload
surface and reports fire/payload mismatches.

## Interface

| Direction | Signal | Description |
|---|---|---|
| input | `enable` | Reduced replay-LIQ feature enable. |
| input | `flush` | Suppresses fire-qualified payload publication during replay flush. |
| input | `writebackFire` | R346 writeback side-effect fire pulse. |
| input | `writebackPayloadValid` | R341 writeback request payload is valid. |
| input | `writebackTargetValid` / `writebackIdentityValid` | R341 target and BID/GID/RID validation bits. |
| input | `writebackDestinationValid` / `writebackGprDestination` | R341 destination guards for reduced scalar GPR writeback. |
| input | `writebackTargetIsAgu` / `writebackTargetIsLda` / `writebackTargetPipeIndex` | Validated W2 target route from the writeback request. |
| input | `writebackBid` / `writebackGid` / `writebackRid` / `writebackLoadLsId` | Writeback payload identity. |
| input | `writebackPc` | Copied resident PC diagnostic. |
| input | `writebackKind` / `writebackArchTag` / `writebackRelTag` / `writebackPhysTag` / `writebackOldPhysTag` | Copied destination payload. |
| input | `writebackData` | Copied returned scalar data. |
| output | `candidateValid` | Enabled, not flushed, and writeback fire is asserted. |
| output | `payloadValid` | Enabled, not flushed, and a writeback payload is present. |
| output | `targetValid` / `identityValid` / `destinationValid` / `gprDestination` | Fire-time payload guards. |
| output | `fireValid` | Fire, payload, target, identity, and reduced GPR destination are all valid. |
| output | copied `fire*` fields | Fire-qualified copy of the writeback payload; disabled or zero when `fireValid` is false. |
| output | blocker signals | Disabled, flush, missing fire, missing payload, invalid target, invalid identity, missing destination, and non-GPR diagnostics. |
| output | `invalidFireWithoutPayload` | Writeback fire asserted without the R341 payload. |
| output | `invalidPayloadWithoutFire` | R341 payload exists but R346 did not fire writeback. |

## Logic Design

```text
active = enable && !flush
candidateValid = active && writebackFire
payloadValid = active && writebackPayloadValid
destinationShapeValid = writebackDestinationValid && writebackGprDestination
fireValid =
  candidateValid &&
  writebackPayloadValid &&
  writebackTargetValid &&
  writebackIdentityValid &&
  destinationShapeValid
```

The module copies BID/GID/RID, load LSID, PC, destination tags, returned data,
target class, and target pipe only when `fireValid` is true. All copied fields
are disabled or zero otherwise. This lets future live RF mutation connect to a
single fire-qualified payload without observing partial request or payload
mismatches.

## Integration

`LinxCoreFrontendFetchRfAluTraceTop` wires this module behind the R346 fire
vector:

- `writebackFire` comes from `LoadReplayReturnPipeW2SideEffectFireVector`;
- payload and validation bits come from `LoadReplayReturnPipeW2WritebackRequest`;
- compact diagnostics are exposed under
  `reducedLoadReplayLiqLretPipeW2WritebackFirePayload*`.

The R348 integration remains observational. R358 now consumes this fire payload
into `LoadReplayReturnPipeW2WritebackArbiterInput`, but that pre-arbiter
boundary keeps its live RF write gate disabled. The fire payload therefore does
not feed W2 readiness, W2 slot clear, the live reduced scalar RF writeback
arbiter, RF state mutation, ready-table mutation, issue wakeup, ROB/PE resolve,
or replay-row lifecycle.

## Deferred Owners

- Live replay RF writeback arbitration and scalar register-file mutation.
- Non-GPR RF write destinations: T/U local links, stack, vector, and predicate
  destinations.
- PE/core/thread routing from the full model `RFReqBus`.
- Atomic W2 clear after resolve, writeback, and wakeup sinks have all fired.

## Verification

Focused gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2WritebackFirePayload
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2SideEffectFireVector
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2WritebackRequest
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2SideEffectIssuePermit
bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop
FETCH_REDUCED_STORE_REPLAY_LIQ=1 BUILD_DIR=generated/r348-replay-pipe-w2-writeback-fire-payload-xcheck bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh
```

Reference tests cover legal fire-qualified copying, fire without payload,
payload without fire, disabled/flush suppression, invalid target and identity,
missing or non-GPR destinations, and Chisel elaboration.
