# LoadReplayReturnPipeW2SideEffectFireComplete

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2SideEffectFireComplete.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/LoadReplayReturnPipeW2SideEffectFireCompleteSpec.scala`
- Integrated user: `rtl/LinxCore/chisel/src/main/scala/linxcore/top/LinxCoreFrontendFetchRfAluTraceTop.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/iex/pipe/lda_pipe.cpp`
    - `LDAPipe::runW2`
  - `model/LinxCoreModel/model/iex/pipe/agu_pipe.cpp`
    - `AGUPipe::runW2`
  - `model/LinxCoreModel/model/iex/iex.cpp`
    - `IEX::setMemWakeup`
  - `model/LinxCoreModel/model/iex/iex_iq.cpp`
    - `IssueQueue::WakeupIQTag`
- Related Chisel contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2SideEffectFireVector.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2ResolveFirePayload.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2WritebackFirePayload.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2WakeupFirePayload.scala`
- Contract IDs: `LC-CHISEL-LSU-REPLAY-PIPE-W2-SIDE-EFFECT-FIRE-COMPLETE-001`

## Purpose

`LoadReplayReturnPipeW2SideEffectFireComplete` is the dormant post-fire
completion checker for W2 side effects. R346 produces the accepted per-sink
fire mask, while R347, R348, and R349 independently validate the resolve,
writeback, and wakeup fire payloads. R350 joins those fire-payload valids back
against the fire mask so a future W2 clear owner can require that every fired
side-effect payload was accepted, and that no downstream payload fired without
being in the accepted mask.

In LinxCoreModel, `LDAPipe::runW2` and `AGUPipe::runW2` emit RF writeback,
PE resolve, and scalar-local-link wakeup from the same resident W2 instruction.
The returned-memory wakeup path eventually calls `IssueQueue::WakeupIQTag`,
which mutates issue queues and the ready table. This Chisel module does not
mutate those structures and does not clear the W2 slot. It only names the
post-fire completeness predicate needed before a later live clear path.

## Interface

| Direction | Signal | Description |
|---|---|---|
| input | `enable` | Reduced replay-LIQ feature enable. |
| input | `flush` | Suppresses fire-completion publication during replay flush. |
| input | `fireVectorValid` | R346 accepted a coherent side-effect fire vector. |
| input | `fireMask` | R346 accepted sink mask: bit 0 resolve, bit 1 writeback, bit 2 wakeup. |
| input | `resolveFirePayloadValid` | R347 validated the resolve fire payload. |
| input | `writebackFirePayloadValid` | R348 validated the writeback fire payload. |
| input | `wakeupFirePayloadValid` | R349 validated the wakeup fire payload. |
| output | `candidateValid` | Enabled, not flushed, and a fire vector is present. |
| output | `observedFireMask` | Fire mask copied only while `candidateValid` is true. |
| output | `payloadFireMask` | Payload-valid mask from R347/R348/R349. |
| output | `missingPayloadFireMask` | Fired sinks whose downstream payload did not validate. |
| output | `unexpectedPayloadFireMask` | Downstream payloads that validated without a fired sink. |
| output | `payloadMatchesFire` | Payload-valid mask exactly equals the fire mask. |
| output | `fireComplete` | Candidate has at least one fired sink and payload mask equals fire mask. |
| output | `futureClearEligible` | Alias of `fireComplete` for the future clear owner. R351 consumes it only as diagnostic evidence. |
| output | blocker signals | Disabled, flush, missing fire vector, empty fire mask, and payload mismatch diagnostics. |
| output | `invalidFireWithoutPayload` | At least one fired sink lacks a validated fire payload. |
| output | `invalidPayloadWithoutFire` | At least one fire payload validated without the corresponding fired sink or without any fire vector. |

## Logic Design

```text
active = enable && !flush
payloadFireMask = {wakeupFirePayloadValid, writebackFirePayloadValid, resolveFirePayloadValid}
candidateValid = active && fireVectorValid
missingPayloadFireMask = fireMask & ~payloadFireMask
unexpectedPayloadFireMask = payloadFireMask & ~fireMask
payloadMatchesFire = payloadFireMask == fireMask
fireComplete = candidateValid && fireMask.nonEmpty && payloadMatchesFire
```

`futureClearEligible` intentionally mirrors `fireComplete` and remains
diagnostic. R351 combines it with W2 slot state and pre-completion sink
readiness in `LoadReplayReturnPipeW2ClearIntent`, but that owner still keeps
live W2 clear and replay-row lifecycle retirement disabled.

## Integration

`LinxCoreFrontendFetchRfAluTraceTop` wires this module after the three
fire-payload modules:

- `fireVectorValid` and `fireMask` come from
  `LoadReplayReturnPipeW2SideEffectFireVector`;
- payload valids come from `LoadReplayReturnPipeW2ResolveFirePayload`,
  `LoadReplayReturnPipeW2WritebackFirePayload`, and
  `LoadReplayReturnPipeW2WakeupFirePayload`;
- compact diagnostics are exposed under
  `reducedLoadReplayLiqLretPipeW2SideEffectFireComplete*`.
- R351 observes `futureClearEligible` through
  `LoadReplayReturnPipeW2ClearIntent` but keeps that path live-disabled.

The integration remains observational. It does not feed W2 readiness, W2 slot
clear, ROB/PE resolve, replay RF writeback, ready-table mutation, issue wakeup,
or replay-row lifecycle.

## Deferred Owners

- Live ROB/PE resolve, replay RF writeback, ready-table, and issue-wakeup
  mutation sinks.
- Atomic W2 clear once all required live sinks accept the same resident
  instruction.
- Replay-row lifecycle retirement after live W2 side effects have fired.

## Verification

Focused gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2SideEffectFireComplete
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2SideEffectFireVector
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2ResolveFirePayload
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2WritebackFirePayload
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2WakeupFirePayload
bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop
```

Reference tests cover full and resolve-only fire completion, missing payloads,
unexpected payloads, disabled/flush suppression, payload-without-vector
diagnostics, and Chisel elaboration.
