# LoadReplaySourceReturnScbLiveControl

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplaySourceReturnScbLiveControl.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/LoadReplaySourceReturnScbLiveControlSpec.scala`
- Integrated user: `rtl/LinxCore/chisel/src/main/scala/linxcore/top/LinxCoreFrontendFetchRfAluTraceTop.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/lsu/load_unit/ldq.cpp`
    - `LDQInfo::handleSCBReceive`
    - `LDQInfo::handleSTQReceive`
    - `LDQInfo::pickL1`
    - `LDQInfo::returnData`
  - `model/LinxCoreModel/model/lsu/load_unit/ldq_cluster.cpp`
    - `LUEntryInfo::Reset`
    - `LUEntryInfo::rewait`
- Related Chisel contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplaySourceReturnReadiness.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayLaunchReadiness.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnReadiness.scala`
- Contract IDs: `LC-CHISEL-LSU-REPLAY-SCB-LIVE-001`

## Purpose

`LoadReplaySourceReturnScbLiveControl` names the live request boundary for the
future replay-LIQ external SCB source. It feeds
`LoadReplaySourceReturnReadiness.externalScbPending` and
`externalScbReturned` from one request/evidence owner instead of top-local
constants.

R389 keeps the integrated reduced top behavior unchanged. `requestEnable`,
`scbPendingEvidence`, and `scbReturnedEvidence` are all tied low, so the
readiness owner still treats the absent external SCB path as vacuously
returned. The module only exposes diagnostics for the later live path.

## Interface

### Inputs

| Signal | Description |
|---|---|
| `enable` | Replay-LIQ wrapper is active. |
| `flush` | Store/replay flush suppresses live SCB request outputs. |
| `requestEnable` | Future top-level mode gate for consuming live SCB pending/return evidence. Current top ties this false. |
| `scbPendingEvidence` | Future selected replay row requires an external SCB response. |
| `scbReturnedEvidence` | Future selected replay row's external SCB response has returned. |

### Outputs

| Signal | Description |
|---|---|
| `active` | `enable && !flush`. |
| `requestActive` | Active plus `requestEnable`. |
| `scbEvidenceValid` | Active cycle with pending or returned SCB evidence present. |
| `externalScbPending` | Armed pending bit sent to `LoadReplaySourceReturnReadiness`. |
| `externalScbReturned` | Armed returned bit sent to `LoadReplaySourceReturnReadiness`. |
| `blockedByDisabled` | Request/evidence exists while the wrapper is disabled. |
| `blockedByFlush` | Request/evidence exists during flush. |
| `blockedByRequestDisabled` | SCB evidence exists while the request gate is disabled. |
| `blockedByNoPending` | Returned evidence appeared without a pending external SCB dependency. |
| `blockedByScbReturn` | Pending external SCB dependency is still waiting for returned evidence. |

## State

The module is combinational and owns no state.

## Logic Design

The model resets `scbRnt` and `stqRnt` on entry reset and repick rewait.
`LDQInfo::handleSCBReceive` marks `entry.scbRnt = true` for `LDQ_REPICK`
entries, then merges returned data when valid. `LDQInfo::handleSTQReceive`
asserts that SCB has returned before marking `entry.stqRnt = true`. The
repick loop in `LDQInfo::pickL1` calls `returnData` only after
`(ldqRnt || l1Rnt) && scbRnt && stqRnt` and an IEX return pipe are available.

This Chisel owner only controls the external-SCB portion of that predicate:

1. `active = enable && !flush`.
2. `requestActive = active && requestEnable`.
3. Evidence is diagnostic when either pending or returned evidence is present
   in an active cycle.
4. `externalScbPending` is armed only when live requests are active and
   pending evidence exists.
5. `externalScbReturned` is armed only when that pending dependency also has
   returned evidence.
6. Request-disabled, disabled, flush, stray-return, and waiting-return
   blockers expose why live evidence did not release source readiness.

## Timing

The control is same-cycle combinational logic in front of
`LoadReplaySourceReturnReadiness`. It does not latch SCB return state; a future
SCB row owner must provide stable selected-row pending/returned evidence.

## Flush/Recovery

Flush clears `active`, suppresses both external SCB outputs, and reports
`blockedByFlush` when request or evidence is present.

## Deferred Owners

- Live external SCB pending/returned source tied to selected replay-LIQ rows.
- Stateful SCB return tracking across replay-row repick cycles.
- Full STQ/source-return sequencing after live SCB return.

## Verification

Focused gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadReplaySourceReturnScbLiveControl
bash tools/chisel/run_chisel_tests.sh --only LoadReplaySourceReturnReadiness
bash tools/chisel/run_chisel_tests.sh --only LoadReplayLaunchReadiness
bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop
FETCH_REDUCED_STORE_REPLAY_LIQ=1 BUILD_DIR=generated/r389x bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh
```

Reference tests cover the current no-SCB reduced path, pending/waiting SCB
evidence, returned SCB evidence, returned-without-pending diagnostics,
disabled/flush/request-disabled suppression, and Chisel elaboration.
