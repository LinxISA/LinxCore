# LoadReplayReturnPipeW2RetireRecordLifecycleRequestProbe

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2RetireRecordLifecycleRequestProbe.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/LoadReplayReturnPipeW2RetireRecordLifecycleRequestProbeSpec.scala`
- Integrated user: deferred; R580 keeps the module standalone until the
  generated-top constructor is split below the JVM method-size limit.
- Related contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2RetireRecord.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2ReplayRowLifecycleReady.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2ReplayRowLifecycleRequestControl.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2ReplayRowLifecycleCommitPermit.scala`
- Contract IDs: `LC-CHISEL-LSU-REPLAY-PIPE-W2-RETIRE-RECORD-LIFECYCLE-REQUEST-PROBE-001`

## Purpose

`LoadReplayReturnPipeW2RetireRecordLifecycleRequestProbe` is the R580
diagnostic bridge between the explicit W2 retire record and the existing live
replay-row lifecycle request path. R579 proved the retained record is consumed
only when its identity matches exactly one resolved LIQ row. R580 asks the next
question without mutating LIQ: when the retained record and lifecycle row are
ready, are the atomic request, row-fill candidate, and row-fill enable aligned
well enough to promote that retained record into the live request/commit/clear
owners?

The module deliberately has no `clearResolvedValid`, row-fill, ROB resolve, or
side-effect outputs. It only reports candidate and blocker signals so the
generated RTL/QEMU sideband can identify the first missing live prerequisite.

## Interface

| Direction | Signal | Description |
|---|---|---|
| input | `enable` / `flush` | Reduced replay-LIQ arm and flush suppression. |
| input | `retireRecordValid` | The explicit W2 retire record is retained. |
| input | `lifecycleRowClearReady` | The retained record matches exactly one resolved LIQ row. |
| input | `atomicRequestActive` | Existing W2 atomic live request is active. |
| input | `rowFillCandidateValid` | Existing W2 commit-row candidate is valid. |
| input | `rowFillEnable` | Existing W2 row-fill enable is live. |
| output | `requestCandidate` | Retained record plus unique lifecycle row are both present. |
| output | `livePromotionCandidate` | Request candidate also has atomic request, row-fill candidate, and row-fill enable. |
| output | blocker signals | Disabled, flush, no lifecycle row, no atomic request, no row-fill candidate, no row-fill enable, and invalid row-fill-without-request diagnostics. |

## Logic Design

```text
active = enable && !flush
requestCandidate = active && retireRecordValid && lifecycleRowClearReady

livePromotionCandidate =
  requestCandidate &&
  atomicRequestActive &&
  rowFillCandidateValid &&
  rowFillEnable

blockedByNoLifecycleRow = active && retireRecordValid && !lifecycleRowClearReady
blockedByNoAtomicRequest = requestCandidate && !atomicRequestActive
blockedByNoRowFillCandidate = requestCandidate && atomicRequestActive && !rowFillCandidateValid
blockedByNoRowFillEnable =
  requestCandidate && atomicRequestActive && rowFillCandidateValid && !rowFillEnable
```

## Integration Status

R580 introduces this probe as a standalone LSU module and focused test. A first
attempt to expose it through `LinxCoreFrontendFetchRfAluTraceTop` showed that
the generated top is already at the JVM method-size limit; the additional
module and IO pushed `LinxCoreFrontendFetchRfAluTraceTop.<init>` over that
limit. The packet therefore keeps the probe standalone and performs only a
mechanical top-constructor maintenance split for the repeated W2 lifecycle and
commit-row candidate constructors.

The next promotion packet should first split the reduced top into smaller
constructor/wiring surfaces, then wire this probe beside the R579 retire-record
lifecycle matcher. That evidence should decide whether the retained record
needs its own row-fill/commit-row source, or whether the existing physical W2
row-fill source can be aligned with the retained record.

## Deferred Owners

- Live retire-record lifecycle request/commit/clear selection.
- Top-level sideband exposure and generated RTL/QEMU xcheck once the top
  constructor is split enough to accept new diagnostics.
- Retire-record commit-row or row-fill source if physical W2 row-fill signals
  do not align with the retained record after W2 clears.
- LIQ `clearResolvedValid/index` mutation from the retained-record lifecycle
  row, after atomic side-effect and ROB resolve ordering are proven.

## Verification

Focused gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2RetireRecordLifecycleRequestProbe
bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop
```

Reference tests cover full live-promotion candidate formation, missing
lifecycle row, missing atomic request, missing row-fill candidate, missing
row-fill enable, disabled/flush suppression, and Chisel elaboration.
