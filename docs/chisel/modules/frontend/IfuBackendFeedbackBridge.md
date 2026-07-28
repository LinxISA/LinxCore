# IfuBackendFeedbackBridge

## Purpose

`IfuBackendFeedbackBridge` is the production adapter between post-B-F4
Dispatch/BRU validation and the canonical IFU feedback ports. It consumes a
retained `RenamedUop` with its immutable final prediction plus the actual
block-control result. It emits B-SIDE training for every legal resolve and an
exact-keyed backend recovery only on mismatch.

This module does not compute SETC operands and does not own backend ROB/BROB
cleanup. Dispatch supplies operand-independent boundary results; BRU E1
supplies computed SETC direction or target. The backend recovery fabric remains
the owner of architectural cleanup and feeds the accepted restart to the IFU.

## Validation table

| Actual block kind | Owner | Resolve source | Compared prediction fields |
|---|---|---|---|
| `Fall` | Dispatch | boundary metadata | kind, branch PC, not-taken, fallthrough |
| `Direct` | Dispatch | BSTART static target | kind, branch PC, taken, target |
| `Call` | Dispatch | BSTART static target | kind, branch PC, taken, target |
| `Cond` | BRU E1 | `setc.cond` result | kind, branch PC, direction; target is not re-compared |
| `Ind` | BRU E1 | `setc.tgt` source | kind, branch PC, taken, target |
| `ICall` | BRU E1 | `setc.tgt` source | kind, branch PC, taken, target |
| `Ret` | BRU E1 | `setc.tgt` source | kind, branch PC, taken, target |

The interface explicitly classifies SETC as `None`, `Condition`, or `Target`.
Assertions reject SETC at Dispatch, `setc.tgt` for a conditional block, and a
condition SETC for indirect/call-return resolution. `actualBranchPc` comes from
backend block context and is never inferred from the SETC instruction PC.

LinxCoreModel performs the conditional direction and indirect/call-return
target checks in `IEX::branchResolve`. Its direct/fall/call path in
`BCtrlUnit::RslvDirectly` supplies the actual tuple but currently marks it
resolved without comparing the prediction. Dispatch comparison is therefore a
target Chisel improvement, not a claim about the Model's current comparison
location.

## Exact identity and atomicity

The final prediction sidecar preserves `transactionId`, `fetchPacketUid`,
`fetchSeq`, `requestPc`, prediction tag, epoch, and checkpoint. The bridge does
not infer one identity field from another. I-F3 carries the original
transaction ID independently through I-F4 and the Instruction Buffer; D1
copies it with the final record into every decoded/renamed lane.

A correct result produces one `BSideResolveUpdate(mispredict=false)`. A mismatch
produces both:

- `BSideResolveUpdate(mispredict=true)` with the actual tuple; and
- `IfuInnerFlushReason.BruRecovery` as an architectural backend restart
  proposal with exact history key and actual next PC.

The two mismatch outputs are atomic. If either sink is blocked, neither output
fires and the queued validation remains stable. In the production composition,
the exact queued training entry is allowed to consume its immutable checkpoint
in the same cycle that the matching canonical `BruRecovery` prune removes the
row; unrelated training remains blocked by an active prune.

## Predictor recovery

Backend recovery uses `KillAllThreadState`, because the producer has already
left the frontend and architectural recovery removes all transient work for
that STID. The live request-owned history row is matched before removal:

- conditional resolve restores GHR and appends actual direction once;
- Call/ICall restores RAS and pushes actual fallthrough/return address;
- Ret restores RAS and pops once;
- Fall/Direct/Ind perform no RAS delta.

`IfuRedirectArbiter` remains the sole allocator of the canonical new epoch.

## Verification

```bash
bash tools/chisel/run_chisel_tests.sh --only IfuBackendFeedbackBridge
bash tools/chisel/run_chisel_ifu_backend_feedback_bridge_probe.sh
```

The Chisel suite covers conditional direction-only comparison, atomic
asymmetric backpressure, Dispatch Call target validation, Return target
validation, actual next-PC selection, and typed GHR/RAS recovery. The emitted
RTL probe repeats conditional-correct, conditional-mispredict, Call-target, and
Return-target scenarios through Verilator.

## Production composition boundary

`LinxCoreComposition` now connects this wrapper directly to
`LinxCoreIfu.branchResolve/backendRedirect`. Dispatch and BRU must still
instantiate the validation-event producers, and the mismatch must also enter
the backend full-BID recovery fabric using the `RenamedUop`
RID/GID/BID/block-BID identity. This frontend composition does not by itself
prove four-lane dispatch/issue integration or natural CoreMark/Dhrystone
execution.
