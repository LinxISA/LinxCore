# LoadReplayReturnPipeW2ClearCommitGuard

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2ClearCommitGuard.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/LoadReplayReturnPipeW2ClearCommitGuardSpec.scala`
- Integrated user: `rtl/LinxCore/chisel/src/main/scala/linxcore/top/LinxCoreFrontendFetchRfAluTraceTop.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/iex/pipe/lda_pipe.cpp`: `LDAPipe::Work`, `LDAPipe::runW2`, `LDAPipe::move`
  - `model/LinxCoreModel/model/iex/pipe/agu_pipe.cpp`: `AGUPipe::Work`, `AGUPipe::runW2`, `AGUPipe::move`
  - `model/LinxCoreModel/model/iex/pipe/iex_pipe.h`: W2 stage contract as instruction resolve
  - `model/LinxCoreModel/model/ModelCommon/SimInstInfo.cpp`: `SimInstInfo::GenRslvBus`
- Related Chisel contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2ClearIntent.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2ResolveFirePayload.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2RobCompleteSource.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/backend/ReducedRobCompletionArbiter.scala`
- Contract IDs: `LC-CHISEL-LSU-REPLAY-PIPE-W2-CLEAR-COMMIT-GUARD-001`

## Purpose

`LoadReplayReturnPipeW2ClearCommitGuard` is the identity-coherence guard for a
future live replay-W2 clear. It verifies that the resident W2 slot RID, the
post-fire resolve payload RID, and the ROB-completion request all refer to the
same row before the clear can be treated as commit-backed.

The model runs W2 side effects before moving pipe residency: `LDAPipe::Work`
and `AGUPipe::Work` call `runW2()` before later stages, and `move()` overwrites
`w2_inst` from `w1_inst`. A live Chisel clear therefore must only advance after
the same resident instruction has produced the resolve/ROB completion evidence.
This module names that future requirement without changing today’s W2 slot
clear or enabling replay side effects.

## Interface

| Direction | Signal | Description |
|---|---|---|
| input | `enable` | Reduced replay-LIQ integration is enabled. |
| input | `flush` | Suppresses guard candidates during replay flush. |
| input | `slotOccupied` | W2 slot contains a resident replay-return entry. |
| input | `slotRid` | Resident W2 slot ROB identity. |
| input | `clearIntent` | R351 pre/post side-effect clear intent. |
| input | `liveClear` | R351/R356 explicit live-clear arm. |
| input | `resolveFireValid` / `resolveFireRid` | Post-fire resolve payload identity. |
| input | `robCompleteValid` / `robCompleteRobValue` | Replay ROB-completion source evidence. |
| output | `candidateValid` | Enabled, not flushed, and a W2 slot is resident. |
| output | `resolveMatchesSlot` | Resolve-fire RID is valid and equals the resident slot RID including wrap. |
| output | `robMatchesSlot` | ROB completion index equals the resident slot RID value. |
| output | `robMatchesResolve` | ROB completion index equals the resolve-fire RID value. |
| output | `commitClearReady` | Clear intent plus slot/resolve/ROB identity coherence. |
| output | `liveClearReady` | `commitClearReady` gated by the explicit live-clear arm. |
| output | blocker signals | Disabled, flush, no-slot, no-clear, live-disabled, invalid RID, missing evidence, mismatch, and stray-evidence diagnostics. |

## Logic Design

```text
active = enable && !flush
candidateValid = active && slotOccupied
clearCandidate = candidateValid && clearIntent

resolveMatchesSlot =
  resolveFireValid && resolveFireRid.valid &&
  slotRid.valid && ROBID.equal(resolveFireRid, slotRid)

robMatchesSlot =
  robCompleteValid && slotRid.valid &&
  robCompleteRobValue == slotRid.value

robMatchesResolve =
  robCompleteValid && resolveFireValid && resolveFireRid.valid &&
  robCompleteRobValue == resolveFireRid.value

commitClearReady =
  clearCandidate && slotRid.valid &&
  resolveMatchesSlot && robMatchesSlot && robMatchesResolve

liveClearReady = commitClearReady && liveClear
```

The ROB completion port carries only the ROB row index value, so wrap/epoch
coherence is checked between the resident slot RID and resolve-fire RID. The
ROB completion value is checked against both values. This matches the current
`DecodeRenameROBPath` completion interface while keeping the stricter
resident-row identity visible before live replay promotion.

## Integration

R365 wires the guard after `LoadReplayReturnPipeW2ClearIntent` in
`LinxCoreFrontendFetchRfAluTraceTop`:

- `slotRid` comes from the resident `LoadReplayReturnPipeW2Slot`;
- `clearIntent` and `liveClear` come from `LoadReplayReturnPipeW2ClearIntent`;
- resolve evidence comes from `LoadReplayReturnPipeW2ResolveFirePayload`;
- ROB evidence comes from `LoadReplayReturnPipeW2RobCompleteSource`.

The guard exposes compact diagnostics under
`reducedLoadReplayLiqLretPipeW2ClearCommitGuard*`. It does not feed the W2
slot `clear`, W2 advance/refill, RF writeback, wakeup, resolve, ROB
completion, or replay-row lifecycle paths.

## Deferred Owners

- Replace the current W2 slot `clear` source with a live guard-owned clear only
  when RF writeback, PE resolve, wakeup, ROB completion, and replay-row
  lifecycle promotion are committed together.
- Add replay load commit-row fill before live ROB completion can replace row
  contents.
- Extend ROB-completion identity with epoch/wrap only if the downstream ROB
  interface grows beyond index-only completion.

## Verification

Focused gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2ClearCommitGuard
bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop
FETCH_REDUCED_STORE_REPLAY_LIQ=1 BUILD_DIR=generated/r365-replay-w2-clear-commit-guard-xcheck bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh
```

Reference tests cover coherent clear/commit evidence, dormant live-clear
disable, missing ROB completion, resolve-vs-slot mismatch, ROB-vs-slot and
ROB-vs-resolve mismatches, invalid resolve RID, stray evidence without clear
intent, disabled/flush/empty suppression, and Chisel elaboration.
