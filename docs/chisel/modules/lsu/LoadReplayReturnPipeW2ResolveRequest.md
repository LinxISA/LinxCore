# LoadReplayReturnPipeW2ResolveRequest

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2ResolveRequest.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/LoadReplayReturnPipeW2ResolveRequestSpec.scala`
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
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2SideEffectRequest.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2ResolveSinkReady.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2Slot.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnRobResolveDataCandidate.scala`
- Contract IDs: `LC-CHISEL-LSU-REPLAY-PIPE-W2-RESOLVE-REQUEST-001`

## Purpose

`LoadReplayReturnPipeW2ResolveRequest` names the post-completion W2 resolve
payload before any live ROB or PE resolve-array mutation exists. In
LinxCoreModel, `LDAPipe::runW2` and `AGUPipe::runW2` call
`SimInstInfo::GenRslvBus()`, choose the scalar or vector thread route, and
write the resulting `PEResolveBus` after W2 completion.

R340 keeps that mutation dormant. The module consumes the R339
`resolveRequest` pulse plus the resident W2 slot payload, validates target and
ROB identity shape, and exposes a diagnostic request carrying BID/GID/RID,
load LSID, PC, address, size, destination, data, target pipe, and completion
status.

## Interface

| Direction | Signal | Description |
|---|---|---|
| input | `resolveRequest` | Post-completion resolve request from `LoadReplayReturnPipeW2SideEffectRequest.resolveRequest`. |
| input | `slotOccupied` | W2 slot has a resident replayed-load entry. |
| input | `slotTargetIsAgu` / `slotTargetIsLda` | One-hot W2 target pipe class copied from the resident slot. |
| input | `slotPipeIndex` | W2 return-pipe index. |
| input | `slotBid` / `slotGid` / `slotRid` / `slotLoadLsId` | Resident ROB/load identity. BID/GID/RID must be valid for a legal request. |
| input | `slotPc` / `slotAddr` / `slotSize` | Resident PC and memory request identity. |
| input | `slotDst` / `slotData` | Resident destination and returned data sidebands. |
| output | `candidateValid` | Resolve request and W2 slot are both present. |
| output | `targetValid` | Candidate has exactly one W2 target class. |
| output | `resolveValid` | Candidate, target, and BID/GID/RID identity are all valid. |
| output | `isComplete` | Diagnostic completion flag for the future `PEResolveBus` shape. |
| output | `targetIsAgu` / `targetIsLda` / `targetPipeIndex` | Validated target pipe diagnostics. |
| output | `resolveBid` / `resolveGid` / `resolveRid` / `resolveLoadLsId` | Copied identity fields only when `resolveValid` is true. |
| output | `resolvePc` / `resolveAddr` / `resolveSize` | Copied PC and memory request identity only when `resolveValid` is true. |
| output | `resolveDst` / `resolveData` | Copied destination and returned data only when `resolveValid` is true. |
| output | `blockedByNoRequest` | A W2 slot is resident but no post-completion resolve request fired. |
| output | `blockedByNoSlot` | Resolve request fired without a resident W2 slot. |
| output | `blockedByInvalidTarget` | Candidate has no target or both LDA and AGU targets. |
| output | `blockedByInvalidBid` / `blockedByInvalidGid` / `blockedByInvalidRid` | Candidate has an invalid required ROB identity field. |
| output | `blockedByInvalidIdentity` | Aggregate invalid BID/GID/RID diagnostic. |

## Logic Design

```text
candidateValid = resolveRequest && slotOccupied
targetValid = slotTargetIsAgu ^ slotTargetIsLda
identityValid = slotBid.valid && slotGid.valid && slotRid.valid
resolveValid = candidateValid && targetValid && identityValid
```

When `resolveValid` is false, every copied payload field is driven to a
disabled or zero value. `slotLoadLsId`, destination, and data are copied for
diagnostics, but they are not required for `resolveValid` because the model
`PEResolveBus` completion path is primarily identified by BID/GID/RID and the
pipe-resident instruction.

## Integration

`LinxCoreFrontendFetchRfAluTraceTop` wires this module behind R339:

- `resolveRequest` comes from `LoadReplayReturnPipeW2SideEffectRequest`;
- all slot identity, target, data, and destination inputs come from the R333
  W2 slot;
- top-level diagnostics are exposed under
  `reducedLoadReplayLiqLretPipeW2ResolveRequest*`.

Because R336-R338 keep live W2 sinks disabled, R334 completion remains low,
R339 `resolveRequest` remains low, and R340 request outputs remain dormant in
the current reduced fixture.

## Deferred Owners

- Real `PEResolveBus` queue payload, including PE ID, scalar/vector thread
  route, and optional model sidebands beyond the reduced payload.
- Real ROB/PE resolve-array mutation driven by `resolveValid`.
- Ordering and atomicity between resolve mutation, writeback, wakeup, and W2
  slot clear.
- Multi-PE and vector-thread routing beyond the reduced scalar top.

## Verification

Focused gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2ResolveRequest
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2SideEffectRequest
bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop
FETCH_REDUCED_STORE_REPLAY_LIQ=1 BUILD_DIR=generated/r340-replay-pipe-w2-resolve-request-xcheck bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh
```

Reference tests cover legal payload copying, dormant resident-slot behavior,
request-without-slot diagnostics, invalid target diagnostics, invalid identity
diagnostics, and Chisel elaboration.
