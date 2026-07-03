# LoadReplayReturnPipeW2WritebackRequest

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2WritebackRequest.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/LoadReplayReturnPipeW2WritebackRequestSpec.scala`
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
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2SideEffectRequest.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2WritebackSinkReady.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2CompletionCandidate.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2SideEffectPayloadPlan.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2WritebackFirePayload.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2Slot.scala`
- Contract IDs: `LC-CHISEL-LSU-REPLAY-PIPE-W2-WRITEBACK-REQUEST-001`

## Purpose

`LoadReplayReturnPipeW2WritebackRequest` names the post-completion W2 RF
writeback payload before live replay writes are allowed to mutate the scalar
register file. In LinxCoreModel, `LDAPipe::runW2` and `AGUPipe::runW2` stamp
W2 timing, optionally call `SimInstInfo::GenRFReqBus(false)`, then continue to
resolve and wakeup side effects. The generated `RFReqBus` carries
BID/GID/RID, PE/core/thread identity, and a destination operand vector that
`RegFile::Work` applies to the matching register file.

R341 keeps mutation dormant and preserves the reduced scalar boundary already
owned by `LoadReplayReturnPipeW2CompletionCandidate`: only GPR destinations
are shaped into a replay RF writeback payload. Local T/U, stack, vector, and
predicate writebacks remain deferred to their explicit owners.

## Interface

| Direction | Signal | Description |
|---|---|---|
| input | `writebackRequest` | Post-completion writeback request from `LoadReplayReturnPipeW2SideEffectRequest.writebackRequest`. |
| input | `slotOccupied` | W2 slot has a resident replayed-load entry. |
| input | `slotTargetIsAgu` / `slotTargetIsLda` | One-hot W2 target pipe class copied from the resident slot. |
| input | `slotPipeIndex` | W2 return-pipe index. |
| input | `slotBid` / `slotGid` / `slotRid` / `slotLoadLsId` | Resident ROB/load identity. BID/GID/RID must be valid for a legal request. |
| input | `slotPc` | Resident PC diagnostic for the future writeback bus owner. |
| input | `slotDst` / `slotData` | Resident destination and returned data sidebands. |
| output | `candidateValid` | Writeback request and W2 slot are both present. |
| output | `targetValid` | Candidate has exactly one W2 target class. |
| output | `identityValid` | Candidate target has valid BID/GID/RID identity. |
| output | `destinationValid` | Candidate identity has a present destination. |
| output | `gprDestination` | Candidate destination is a reduced scalar GPR destination. |
| output | `writebackValid` | Candidate, target, identity, and GPR destination are all valid. |
| output | `targetIsAgu` / `targetIsLda` / `targetPipeIndex` | Validated target pipe diagnostics. |
| output | `writebackBid` / `writebackGid` / `writebackRid` / `writebackLoadLsId` | Copied identity fields only when `writebackValid` is true. |
| output | `writebackPc` | Copied resident PC only when `writebackValid` is true. |
| output | `writebackKind` / `writebackArchTag` / `writebackRelTag` / `writebackPhysTag` / `writebackOldPhysTag` | Copied destination fields only when `writebackValid` is true. |
| output | `writebackData` | Copied returned scalar data only when `writebackValid` is true. |
| output | `blockedByNoRequest` | A W2 slot is resident but no post-completion writeback request fired. |
| output | `blockedByNoSlot` | Writeback request fired without a resident W2 slot. |
| output | `blockedByInvalidTarget` | Candidate has no target or both LDA and AGU targets. |
| output | `blockedByInvalidBid` / `blockedByInvalidGid` / `blockedByInvalidRid` | Candidate has an invalid required ROB identity field. |
| output | `blockedByInvalidIdentity` | Aggregate invalid BID/GID/RID diagnostic. |
| output | `blockedByNoDestination` | Candidate identity is legal but no destination is present. |
| output | `blockedByNonGprDestination` | Candidate identity has a destination that is not a reduced scalar GPR. |

## Logic Design

```text
candidateValid = writebackRequest && slotOccupied
targetValid = slotTargetIsAgu ^ slotTargetIsLda
identityValid = slotBid.valid && slotGid.valid && slotRid.valid
hasDestination = slotDst.valid && slotDst.kind != None
isGprDestination = hasDestination && slotDst.kind == Gpr
writebackValid = candidateValid && targetValid && identityValid && isGprDestination
```

When `writebackValid` is false, copied identity, destination, and data fields
are driven to disabled or zero values. `slotLoadLsId` is copied only as replay
diagnostic context; the model RF bus itself is identified by BID/GID/RID plus
destination operands.

## Integration

`LinxCoreFrontendFetchRfAluTraceTop` wires this module behind R339:

- `writebackRequest` comes from `LoadReplayReturnPipeW2SideEffectRequest`;
- all slot identity, target, destination, and data inputs come from the R333
  W2 slot;
- top-level diagnostics are exposed under
  `reducedLoadReplayLiqLretPipeW2WritebackRequest*`.

Because R336-R338 keep live W2 sinks disabled, R334 completion remains low,
R339 `writebackRequest` remains low, and R341 request outputs remain dormant
in the current reduced fixture.
R343 consumes `writebackValid` as the writeback payload bit in
`LoadReplayReturnPipeW2SideEffectPayloadPlan`; the plan remains observational
until future live W2 sinks are enabled.
R348 consumes the R341 payload and the R346 writeback fire pulse in
`LoadReplayReturnPipeW2WritebackFirePayload`. That boundary remains
observational and does not feed the scalar RF writeback arbiter or RF state.

## Deferred Owners

- Real replay RF writeback into `ReducedScalarWritebackArbiter` or the final
  scalar register-file write port.
- Non-GPR W2 RF destinations: T/U local links, stack, vector, and predicate
  destinations.
- PE/core/thread routing fields from the full model `RFReqBus`.
- Ordering and atomicity between RF writeback, resolve mutation, wakeup, and
  W2 slot clear.

## Verification

Focused gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2WritebackRequest
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2SideEffectRequest
bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop
FETCH_REDUCED_STORE_REPLAY_LIQ=1 BUILD_DIR=generated/r341-replay-pipe-w2-writeback-request-xcheck bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh
```

Reference tests cover legal GPR payload copying, dormant resident-slot
behavior, request-without-slot diagnostics, invalid target diagnostics,
invalid identity diagnostics, missing/non-GPR destination blockers, and
Chisel elaboration.
