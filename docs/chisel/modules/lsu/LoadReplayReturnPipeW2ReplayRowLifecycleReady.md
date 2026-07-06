# LoadReplayReturnPipeW2ReplayRowLifecycleReady

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2ReplayRowLifecycleReady.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/LoadReplayReturnPipeW2ReplayRowLifecycleReadySpec.scala`
- Integrated user: `rtl/LinxCore/chisel/src/main/scala/linxcore/top/LinxCoreFrontendFetchRfAluTraceTop.scala`
- LinxCoreModel evidence:
  - `tools/LinxCoreModel/model/lsu/load_unit/ldq.cpp`: `LDQInfo::returnData`, `LDQInfo::CheckMovRslvQ`, `LDQInfo::retire`
  - `tools/LinxCoreModel/model/lsu/load_unit/ldq_cluster.cpp`: `LUEntryInfo::Reset`
  - `tools/LinxCoreModel/model/lsu/load_unit/ldq.cpp`: `ResolveQ::insert`, `ResolveQ::retired`, `ResolveQ::flush`
  - `tools/LinxCoreModel/model/iex/iex.cpp`: `IEX::receiveFromLSU`, `IEX::setMemData`
  - `tools/LinxCoreModel/model/pe/PECommon/PROBCommon.cpp`: `ROBState::resolveData`
- Related Chisel contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadInflightQueue.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/ReducedLoadReplayLiqAllocPath.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2ReplayRowLifecycleRequestControl.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2ReplayRowClearRequest.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2RowFillEnableControl.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2Slot.scala`
- Contract IDs: `LC-CHISEL-LSU-REPLAY-PIPE-W2-ROW-LIFECYCLE-001`

## Purpose

`LoadReplayReturnPipeW2ReplayRowLifecycleReady` is the read-only lifecycle
guard for future replay-load W2 row fill and LIQ clear promotion. The C++ model
sets the LDQ row to `LDQ_RESOLVED` in `LDQInfo::returnData`, sends the returned
`MemReqBus` through `lsuIexLretArray`, and lets `IEX::setMemData` mutate the
ROB destination data through `ROBState::resolveData`. Separately,
`CheckMovRslvQ` moves resolved LDQ rows into `ResolveQ` and resets the active
LDQ entry.

R368 names the Chisel precondition for that row lifecycle: a resident replay W2
slot must match exactly one valid `Resolved` LIQ row by
`(bid,gid,rid,loadLsId)`. The module exposes the matching row index and
blockers, but the final `lifecycleReady` output remains gated by an explicit
`lifecycleClearEnable` input. R369 drives that input from
`LoadReplayReturnPipeW2ReplayRowClearRequest`; R370 now drives that selector's
request arm from `LoadReplayReturnPipeW2ReplayRowLifecycleRequestControl`.
R371 gates the selected lifecycle clear with the R367 row-fill enable before
R369 may drive LIQ mutation. The integrated top still keeps the upstream
atomic live request false, so replay-row lifecycle mutation and row-fill
remain disabled.

## Interface

| Direction | Signal | Description |
|---|---|---|
| input | `enable` / `flush` | Replay-LIQ integration arm and flush suppression. |
| input | `lifecycleClearEnable` | Future live arm that allows the matched resolved LIQ row to become row-fill-ready. R369 derives it from the clear-request owner, whose live lifecycle request now comes from R370 and whose final commit permit comes from R371. |
| input | `slotOccupied` | Resident W2 replay-return slot contains a returned load. |
| input | `slotBid` / `slotGid` / `slotRid` / `slotLoadLsId` | W2 slot identity for the returned load. |
| input | `rows` | Current `LoadInflightQueue` row image. |
| output | `candidateValid` | Enabled, not flushed, and a resident W2 slot exists. |
| output | `slotIdentityValid` | Candidate slot carries all identity fields as valid. |
| output | `matchedMask` / `matchCount` | Resolved LIQ rows matching the W2 slot identity. |
| output | `rowClearIndex` | Selected LIQ row index when exactly one resolved row matches. |
| output | `rowClearReady` | Candidate identity is valid and exactly one resolved LIQ row matches. |
| output | `lifecycleReady` | `rowClearReady` gated by `lifecycleClearEnable`. |
| output | blocker signals | Disabled, flush, no-slot, invalid slot identity, no resolved row, duplicate resolved rows, disabled lifecycle clear, and stray lifecycle clear diagnostics. |

## Logic Design

```text
active = enable && !flush
candidateValid = active && slotOccupied
slotIdentityValid = slotBid.valid && slotGid.valid && slotRid.valid && slotLoadLsId.valid

matchedMask[i] =
  rows[i].valid &&
  rows[i].status == Resolved &&
  rows[i].bid == slotBid &&
  rows[i].gid == slotGid &&
  rows[i].rid == slotRid &&
  rows[i].loadLsId == slotLoadLsId

rowClearReady = candidateValid && slotIdentityValid && PopCount(matchedMask) == 1
lifecycleReady = rowClearReady && lifecycleClearEnable
```

Duplicate matches are treated as a blocker because the model lifecycle consumes
one concrete LDQ row before `ResolveQ` publication or retirement. R369 feeds
`rowClearReady` and `rowClearIndex` into
`LoadReplayReturnPipeW2ReplayRowClearRequest`, but this module intentionally
does not mutate LIQ state.

## Integration

R368 wires this owner in `LinxCoreFrontendFetchRfAluTraceTop`:

- W2 slot identity comes from `LoadReplayReturnPipeW2Slot`;
- LIQ rows come from `ReducedLoadReplayLiqAllocPath`;
- `lifecycleClearEnable` comes from
  `LoadReplayReturnPipeW2ReplayRowClearRequest.lifecycleClearEnable`;
- that clear-request owner consumes the R370 lifecycle request-control output;
- R371 consumes the selected lifecycle clear and R367 row-fill enable before
  R369 can commit LIQ clear;
- `lifecycleReady` feeds
  `LoadReplayReturnPipeW2RowFillEnableControl.replayRowLifecycleReady`.

This replaces the previous literal lifecycle tie-off with a named owner while
preserving dormant behavior.

R554 adds sideband counters for the existing lifecycle match blockers. The
replay-loop fixture at
`generated/r554-replay-w2-lifecycle-diagnostics/report/crosscheck_manifest.json`
passes with 9 compared rows, zero mismatches, and zero QEMU/DUT CBSTOP rows.
The sideband report records `w2_lifecycle_candidate_valid=74`,
`w2_lifecycle_slot_identity_valid=74`,
`w2_lifecycle_resolved_row_match=0`,
`w2_lifecycle_row_clear_ready=0`,
`w2_lifecycle_blocked_by_no_resolved_row=74`,
`w2_lifecycle_blocked_by_multiple_resolved_rows=0`, and
`w2_lifecycle_blocked_by_clear_disabled=0`. The resident W2 identity is valid,
so the next owner is the replay-LIQ row status/lifecycle path that should
produce exactly one `Resolved` row for the returned load.

R555 closes that lifecycle match for the reduced replay-loop fixture. The top
now prevents the older complete-`Repick` head clear from clearing the same
return-complete row before the W2 lifecycle owner can observe it as
`Resolved`. It also treats accepted W2 lifecycle clear as a matching
`LoadResolveQueue` retire source, so the replay-LIQ row, W2 slot, and ResolveQ
row drain together instead of leaving a stale resolved record. The replay-loop
fixture at
`generated/r555-replay-w2-lifecycle-resolved-row/report/crosscheck_manifest.json`
passes with 9 compared rows, zero mismatches, and zero QEMU/DUT CBSTOP rows.
The sideband report records `liq_return_complete_valid=3`,
`lret_w2_slot_accepted=3`, `w2_lifecycle_resolved_row_match=6`,
`w2_lifecycle_row_clear_ready=6`, `w2_lifecycle_ready=3`,
`w2_lifecycle_blocked_by_no_resolved_row=0`, `w2_row_fill_enable=3`, and
`w2_atomic_request_active=3`. The next owner is no longer row identity or
resolved-row lifetime; it is the live W2 side-effect commit/clear path that
still decides when row-fill and lifecycle clear may commit architectural
effects.

## Deferred Owners

- Broaden the selected returned-load LIQ row match beyond the current
  reduced-loop proof window.
- Live replay-row lifecycle clear/consume path for that selected LIQ row in
  the default top.
- Atomic promotion that feeds `clearResolvedValid/index`, W2 clear/refill,
  replay RF writeback, ROB/PE resolve, ready-table wakeup, and commit-row fill
  from one coherent W2 instruction.
- Multi-return-pipe matching and arbitration if more than one W2 slot can clear
  a replay row in the same cycle.

## Verification

Focused gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2ReplayRowLifecycleReady
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2RowFillEnableControl
bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop
FETCH_REDUCED_STORE_REPLAY_LIQ=1 BUILD_DIR=generated/r368-replay-w2-row-lifecycle-ready-xcheck bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh
```

Reference tests cover unique resolved-row match, live-clear-disabled dormancy,
invalid slot identity, absent resolved row, duplicate resolved rows, disabled /
flush / empty-slot blockers, and Chisel elaboration.
